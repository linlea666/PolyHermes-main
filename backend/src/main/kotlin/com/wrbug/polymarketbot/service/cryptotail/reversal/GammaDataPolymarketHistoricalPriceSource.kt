package com.wrbug.polymarketbot.service.cryptotail.reversal

import com.wrbug.polymarketbot.entity.CryptoTailPmPeriodStatus
import com.wrbug.polymarketbot.entity.CryptoTailPolymarketPriceHistory
import com.wrbug.polymarketbot.repository.CryptoTailPmPeriodStatusRepository
import com.wrbug.polymarketbot.repository.CryptoTailPolymarketPriceHistoryRepository
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.fromJson
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * 基于 Gamma（事件/市场）+ CLOB prices-history 的 Polymarket 历史赔率数据源实现。
 *
 * 增量复用（V71）：
 *   - 周期状态表 [CryptoTailPmPeriodStatus] 按周期记录采集结果，实现真正的周期级跳过：
 *     - RESOLVED：记录 up_token_id，复用时直接从价格缓存读回（零网络，含跳过 Gamma）；
 *     - 已结算无数据（SLUG_NOT_FOUND/HISTORY_EMPTY）：写永久负缓存，复用时零网络跳过；
 *     - FETCH_ERROR（网络/限流/5xx）：不落库 → 下次自动重试。
 *   - 解决根因：原价格缓存键为 token_id，而拿 token_id 必须先调 Gamma，导致即使价格已缓存仍重复打 Gamma。
 *
 * 任意网络失败均归类为 FETCH_ERROR 并跳过（PoC 不阻塞），不会被误写为永久负缓存。
 */
@Component
class GammaDataPolymarketHistoricalPriceSource(
    private val retrofitFactory: RetrofitFactory,
    private val priceHistoryRepository: CryptoTailPolymarketPriceHistoryRepository,
    private val periodStatusRepository: CryptoTailPmPeriodStatusRepository
) : PolymarketHistoricalPriceSource {

    private val logger = LoggerFactory.getLogger(GammaDataPolymarketHistoricalPriceSource::class.java)

    companion object {
        private const val STATUS_RESOLVED = "RESOLVED"
        private const val STATUS_SLUG_NOT_FOUND = "SLUG_NOT_FOUND"
        private const val STATUS_HISTORY_EMPTY = "HISTORY_EMPTY"

        /**
         * 周期结算后需经过的宽限期（秒）才允许写永久负缓存：
         * 防止刚结算的市场尚未被 CLOB 索引时被误判为永久无数据。窗口内的无数据按临时失败处理，下次重试。
         */
        private const val NEG_CACHE_GRACE_SECONDS = 1800L
    }

    /** Gamma 解析 Up 代币结果：区分永久未找到与临时失败，避免临时失败被写成永久负缓存。 */
    private sealed class TokenResolve {
        data class Found(val tokenId: String) : TokenResolve()
        object NotFound : TokenResolve()
        object TransientError : TokenResolve()
    }

    /** CLOB 拉取结果：区分"成功但可能为空"与"临时失败"。 */
    private sealed class ClobResult {
        data class Ok(val history: List<com.wrbug.polymarketbot.api.PriceHistoryPoint>) : ClobResult()
        object TransientError : ClobResult()
    }

    override fun cacheStates(
        fullSlugPrefix: String,
        fromPeriodUnix: Long,
        toPeriodUnix: Long
    ): Map<Long, PolymarketHistoricalPriceSource.CacheState> {
        if (fromPeriodUnix > toPeriodUnix) return emptyMap()
        val rows = try {
            periodStatusRepository.findWindow(fullSlugPrefix, fromPeriodUnix, toPeriodUnix)
        } catch (e: Exception) {
            logger.debug("预载周期状态失败 prefix=$fullSlugPrefix: ${e.message}")
            return emptyMap()
        }
        val map = HashMap<Long, PolymarketHistoricalPriceSource.CacheState>(rows.size * 2)
        for (r in rows) {
            map[r.periodStartUnix] = if (r.status == STATUS_RESOLVED) {
                PolymarketHistoricalPriceSource.CacheState.CACHED_OK
            } else {
                PolymarketHistoricalPriceSource.CacheState.CACHED_EMPTY
            }
        }
        return map
    }

    override fun fetchPeriod(
        fullSlugPrefix: String,
        intervalSeconds: Int,
        periodStartUnix: Long
    ): PolymarketHistoricalPriceSource.FetchResult {
        val errorResult = PolymarketHistoricalPriceSource.FetchResult(PolymarketHistoricalPriceSource.FetchOutcome.FETCH_ERROR)
        if (intervalSeconds <= 0 || periodStartUnix <= 0) return errorResult
        val slug = "$fullSlugPrefix-$periodStartUnix"

        // 1. 命中周期状态缓存：零网络返回
        val cachedStatus = try {
            periodStatusRepository.findBySlug(slug)
        } catch (e: Exception) {
            logger.debug("查询周期状态失败 slug=$slug: ${e.message}")
            null
        }
        if (cachedStatus != null) {
            when (cachedStatus.status) {
                STATUS_RESOLVED -> {
                    val token = cachedStatus.upTokenId
                    if (!token.isNullOrBlank()) {
                        val cached = priceHistoryRepository.findByTokenIdOrderByTUnixAsc(token)
                        if (cached.isNotEmpty()) {
                            return PolymarketHistoricalPriceSource.FetchResult(
                                PolymarketHistoricalPriceSource.FetchOutcome.OK,
                                PolymarketHistoricalPriceSource.PeriodPath(
                                    periodStartUnix = periodStartUnix,
                                    periodSeconds = intervalSeconds,
                                    upTokenId = token,
                                    points = cached.map { it.tUnix to it.price }
                                ),
                                fromCache = true
                            )
                        }
                    }
                    // RESOLVED 但价格缓存缺失（异常情况）→ 落空，走网络重抓
                }

                STATUS_SLUG_NOT_FOUND -> return PolymarketHistoricalPriceSource.FetchResult(
                    PolymarketHistoricalPriceSource.FetchOutcome.SLUG_NOT_FOUND, fromCache = true
                )

                STATUS_HISTORY_EMPTY -> return PolymarketHistoricalPriceSource.FetchResult(
                    PolymarketHistoricalPriceSource.FetchOutcome.HISTORY_EMPTY, fromCache = true
                )
            }
        }

        // 2. 未缓存 → 联网采集
        when (val resolve = resolveUpToken(fullSlugPrefix, periodStartUnix)) {
            is TokenResolve.TransientError -> return errorResult
            is TokenResolve.NotFound -> {
                recordEmptyIfSettled(slug, fullSlugPrefix, intervalSeconds, periodStartUnix, STATUS_SLUG_NOT_FOUND)
                return PolymarketHistoricalPriceSource.FetchResult(PolymarketHistoricalPriceSource.FetchOutcome.SLUG_NOT_FOUND)
            }

            is TokenResolve.Found -> {
                val upTokenId = resolve.tokenId

                // token 已知，价格缓存可能已存在（历史遗留缓存）→ 优先读并补登状态
                val preCached = priceHistoryRepository.findByTokenIdOrderByTUnixAsc(upTokenId)
                if (preCached.isNotEmpty()) {
                    upsertStatus(slug, fullSlugPrefix, intervalSeconds, periodStartUnix, STATUS_RESOLVED, upTokenId)
                    return PolymarketHistoricalPriceSource.FetchResult(
                        PolymarketHistoricalPriceSource.FetchOutcome.OK,
                        PolymarketHistoricalPriceSource.PeriodPath(
                            periodStartUnix = periodStartUnix,
                            periodSeconds = intervalSeconds,
                            upTokenId = upTokenId,
                            points = preCached.map { it.tUnix to it.price }
                        )
                    )
                }

                val periodEndUnix = periodStartUnix + intervalSeconds
                val clob = fetchClobHistory(upTokenId, periodStartUnix, periodEndUnix)
                if (clob is ClobResult.TransientError) return errorResult

                val history = (clob as ClobResult.Ok).history
                if (history.isEmpty()) {
                    recordEmptyIfSettled(slug, fullSlugPrefix, intervalSeconds, periodStartUnix, STATUS_HISTORY_EMPTY)
                    return PolymarketHistoricalPriceSource.FetchResult(PolymarketHistoricalPriceSource.FetchOutcome.HISTORY_EMPTY)
                }

                val points = history
                    .filter { it.t in periodStartUnix..periodEndUnix && it.p in 0.0..1.0 }
                    .sortedBy { it.t }
                    .map { it.t to BigDecimal.valueOf(it.p) }
                if (points.isEmpty()) {
                    recordEmptyIfSettled(slug, fullSlugPrefix, intervalSeconds, periodStartUnix, STATUS_HISTORY_EMPTY)
                    return PolymarketHistoricalPriceSource.FetchResult(PolymarketHistoricalPriceSource.FetchOutcome.HISTORY_EMPTY)
                }

                savePriceHistory(upTokenId, periodStartUnix, points)
                upsertStatus(slug, fullSlugPrefix, intervalSeconds, periodStartUnix, STATUS_RESOLVED, upTokenId)
                return PolymarketHistoricalPriceSource.FetchResult(
                    PolymarketHistoricalPriceSource.FetchOutcome.OK,
                    PolymarketHistoricalPriceSource.PeriodPath(
                        periodStartUnix = periodStartUnix,
                        periodSeconds = intervalSeconds,
                        upTokenId = upTokenId,
                        points = points
                    )
                )
            }
        }
    }

    private fun fetchClobHistory(upTokenId: String, startTs: Long, endTs: Long): ClobResult {
        return try {
            runBlocking {
                val response = retrofitFactory.createClobApiWithoutAuth().getPricesHistory(
                    market = upTokenId,
                    startTs = startTs,
                    endTs = endTs,
                    fidelity = 1
                )
                if (!response.isSuccessful) {
                    logger.debug("prices-history 失败 token=$upTokenId code=${response.code()}")
                    ClobResult.TransientError
                } else {
                    ClobResult.Ok(response.body()?.history ?: emptyList())
                }
            }
        } catch (e: Exception) {
            logger.debug("prices-history 异常 token=$upTokenId: ${e.message}")
            ClobResult.TransientError
        }
    }

    private fun savePriceHistory(
        upTokenId: String,
        periodStartUnix: Long,
        points: List<Pair<Long, BigDecimal>>
    ) {
        try {
            val now = System.currentTimeMillis()
            val entities = points.map { (t, p) ->
                CryptoTailPolymarketPriceHistory(
                    tokenId = upTokenId,
                    periodStartUnix = periodStartUnix,
                    tUnix = t,
                    price = p,
                    createdAt = now
                )
            }
            priceHistoryRepository.saveAll(entities)
        } catch (e: Exception) {
            // 缓存写入失败不影响 PoC 计算
            logger.debug("缓存历史赔率失败 token=$upTokenId: ${e.message}")
        }
    }

    /** 仅当周期已结算且超过宽限期时，写永久负缓存；否则视为临时（下次重试）。 */
    private fun recordEmptyIfSettled(
        slug: String,
        slugPrefix: String,
        intervalSeconds: Int,
        periodStartUnix: Long,
        status: String
    ) {
        val nowUnix = System.currentTimeMillis() / 1000L
        val periodEndUnix = periodStartUnix + intervalSeconds
        if (nowUnix - periodEndUnix < NEG_CACHE_GRACE_SECONDS) return
        upsertStatus(slug, slugPrefix, intervalSeconds, periodStartUnix, status, null)
    }

    /** 写入/更新周期状态（幂等，按 slug 唯一）。失败不影响 PoC 计算。 */
    private fun upsertStatus(
        slug: String,
        slugPrefix: String,
        intervalSeconds: Int,
        periodStartUnix: Long,
        status: String,
        upTokenId: String?
    ) {
        try {
            val now = System.currentTimeMillis()
            val existing = periodStatusRepository.findBySlug(slug)
            val entity = existing?.copy(
                status = status,
                upTokenId = upTokenId,
                updatedAt = now
            ) ?: CryptoTailPmPeriodStatus(
                slug = slug,
                slugPrefix = slugPrefix,
                intervalSeconds = intervalSeconds,
                periodStartUnix = periodStartUnix,
                status = status,
                upTokenId = upTokenId,
                createdAt = now,
                updatedAt = now
            )
            periodStatusRepository.save(entity)
        } catch (e: Exception) {
            logger.debug("写入周期状态失败 slug=$slug: ${e.message}")
        }
    }

    private fun resolveUpToken(fullSlugPrefix: String, periodStartUnix: Long): TokenResolve {
        val slug = "$fullSlugPrefix-$periodStartUnix"
        return try {
            runBlocking {
                val response = retrofitFactory.createGammaApi().getEventBySlug(slug)
                if (!response.isSuccessful) {
                    if (response.code() == 404) TokenResolve.NotFound else TokenResolve.TransientError
                } else {
                    val market = response.body()?.markets?.firstOrNull { !it.clobTokenIds.isNullOrBlank() }
                    val token = parseClobTokenIds(market?.clobTokenIds).firstOrNull()
                    if (token != null) TokenResolve.Found(token) else TokenResolve.NotFound
                }
            }
        } catch (e: Exception) {
            logger.debug("解析 Up tokenId 失败 slug=$slug: ${e.message}")
            TokenResolve.TransientError
        }
    }

    private fun parseClobTokenIds(clobTokenIds: String?): List<String> {
        if (clobTokenIds.isNullOrBlank()) return emptyList()
        return clobTokenIds.fromJson<List<String>>() ?: emptyList()
    }
}
