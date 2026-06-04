package com.wrbug.polymarketbot.service.cryptotail.reversal

import com.wrbug.polymarketbot.entity.CryptoTailPolymarketPriceHistory
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
 * 流程：
 *   1. 由 slug `${fullSlugPrefix}-${periodStartUnix}` 经 Gamma getEventBySlug 取到该周期市场；
 *   2. 解析市场 clobTokenIds，取 Up/Yes 代币 id（数组第一个）；
 *   3. 命中本地缓存则直接返回；否则经 CLOB /prices-history 拉取该周期 [start, end] 内 1 分钟粒度赔率点并落缓存。
 *
 * 任意环节失败均返回 null（PoC 不阻塞）。
 */
@Component
class GammaDataPolymarketHistoricalPriceSource(
    private val retrofitFactory: RetrofitFactory,
    private val priceHistoryRepository: CryptoTailPolymarketPriceHistoryRepository
) : PolymarketHistoricalPriceSource {

    private val logger = LoggerFactory.getLogger(GammaDataPolymarketHistoricalPriceSource::class.java)

    override fun fetchPeriodPath(
        fullSlugPrefix: String,
        intervalSeconds: Int,
        periodStartUnix: Long
    ): PolymarketHistoricalPriceSource.PeriodPath? {
        if (intervalSeconds <= 0 || periodStartUnix <= 0) return null

        val upTokenId = resolveUpTokenId(fullSlugPrefix, periodStartUnix) ?: return null

        // 命中缓存优先
        val cached = priceHistoryRepository.findByTokenIdOrderByTUnixAsc(upTokenId)
        if (cached.isNotEmpty()) {
            return PolymarketHistoricalPriceSource.PeriodPath(
                periodStartUnix = periodStartUnix,
                periodSeconds = intervalSeconds,
                upTokenId = upTokenId,
                points = cached.map { it.tUnix to it.price }
            )
        }

        val periodEndUnix = periodStartUnix + intervalSeconds
        val history = try {
            runBlocking {
                val response = retrofitFactory.createClobApiWithoutAuth().getPricesHistory(
                    market = upTokenId,
                    startTs = periodStartUnix,
                    endTs = periodEndUnix,
                    fidelity = 1
                )
                if (!response.isSuccessful) {
                    logger.debug("prices-history 失败 token=$upTokenId code=${response.code()}")
                    null
                } else {
                    response.body()?.history
                }
            }
        } catch (e: Exception) {
            logger.debug("prices-history 异常 token=$upTokenId: ${e.message}")
            null
        } ?: return null

        if (history.isEmpty()) return null

        val points = history
            .filter { it.t in periodStartUnix..periodEndUnix && it.p in 0.0..1.0 }
            .sortedBy { it.t }
            .map { it.t to BigDecimal.valueOf(it.p) }
        if (points.isEmpty()) return null

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

        return PolymarketHistoricalPriceSource.PeriodPath(
            periodStartUnix = periodStartUnix,
            periodSeconds = intervalSeconds,
            upTokenId = upTokenId,
            points = points
        )
    }

    private fun resolveUpTokenId(fullSlugPrefix: String, periodStartUnix: Long): String? {
        val slug = "$fullSlugPrefix-$periodStartUnix"
        return try {
            runBlocking {
                val response = retrofitFactory.createGammaApi().getEventBySlug(slug)
                if (!response.isSuccessful) {
                    null
                } else {
                    val market = response.body()?.markets?.firstOrNull { !it.clobTokenIds.isNullOrBlank() }
                    parseClobTokenIds(market?.clobTokenIds).firstOrNull()
                }
            }
        } catch (e: Exception) {
            logger.debug("解析 Up tokenId 失败 slug=$slug: ${e.message}")
            null
        }
    }

    private fun parseClobTokenIds(clobTokenIds: String?): List<String> {
        if (clobTokenIds.isNullOrBlank()) return emptyList()
        return clobTokenIds.fromJson<List<String>>() ?: emptyList()
    }
}
