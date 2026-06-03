package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.service.chainlink.ChainlinkDataStreamsService
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * 周期价源抽象：为 crypto-tail 障碍模型提供与「结算同源」的期初/当前/期末价与波动率。
 *
 * 障碍模式价源必须与 Polymarket 结算源（Chainlink BTC/USD Data Stream）一致。本接口有多个实现：
 * - [ChainlinkPeriodPriceProvider]：用户自建 Chainlink Data Streams 直连（需 API 凭证 + feedID）。
 * - [RtdsPeriodPriceProvider]：Polymarket 官方 RTDS 免鉴权代理同一条 Chainlink 流（默认）。
 * 由 [PeriodPriceProviderRouter] 按系统配置选择其一。旧 spread 模式仍直接用 BinanceKlineService，不走此抽象。
 */
interface PeriodPriceProvider {
    data class PriceReadiness(
        val source: String,
        val coin: String?,
        val ready: Boolean,
        val reason: String,
        val ageMs: Long? = null,
        val priceMode: String? = null,
        val lastSnapshotAt: Long? = null,
        val lastRealtimeUpdateAt: Long? = null,
        val latestPriceAgeMs: Long? = ageMs,
        val latestSampleTime: Long? = null
    )

    data class Ohlc1m(
        val minuteStartUnix: Long,
        val open: BigDecimal,
        val high: BigDecimal,
        val low: BigDecimal,
        val close: BigDecimal,
        val tickCount: Int = 0
    )

    /** 该市场价源是否可用（凭证 + feedID / 连接 + 数据就绪） */
    fun isAvailable(marketSlugPrefix: String): Boolean

    /** 当前周期 (期初价, 当前价)；任一缺失返回 null */
    fun getCurrentOpenClose(marketSlugPrefix: String, intervalSeconds: Int, periodStartUnix: Long): Pair<BigDecimal, BigDecimal>?

    /** 结算用 (期初价, 期末价)；任一缺失返回 null */
    fun getFinalOpenClose(marketSlugPrefix: String, intervalSeconds: Int, periodStartUnix: Long): Pair<BigDecimal, BigDecimal>?

    /** 最近 1m OHLC，按时间升序返回；价源不支持时返回空列表。 */
    fun getRecentOhlc1m(marketSlugPrefix: String, minutes: Int, nowSeconds: Long = System.currentTimeMillis() / 1000): List<Ohlc1m> = emptyList()

    /** 当前价缓存年龄（毫秒）；价源无法提供时返回 null，调用方不据此拦截。 */
    fun getCurrentPriceAgeMs(marketSlugPrefix: String): Long? = null

    /** 结构化价源状态，用于 PRICE_SOURCE 日志与监控页诊断。 */
    fun getReadiness(marketSlugPrefix: String): PriceReadiness =
        PriceReadiness("UNKNOWN", null, isAvailable(marketSlugPrefix), if (isAvailable(marketSlugPrefix)) "OK" else "UNKNOWN")

    /**
     * 每 √秒 波动率 σ_per_√s。outcomeIndex 仅为兼容接口（终值波动率与方向无关，实现忽略之）。
     * @param sigmaMethod σ 估计方法 MAD/EWMA/GARMAN_KLASS
     * @param ewmaLambda EWMA 衰减系数（仅 EWMA 生效）
     * 不可用返回 null。
     */
    fun getSigmaPerSqrtS(
        marketSlugPrefix: String,
        intervalSeconds: Int,
        periodStartUnix: Long,
        outcomeIndex: Int,
        sigmaScale: BigDecimal,
        sigmaMethod: String = "MAD",
        ewmaLambda: BigDecimal = BigDecimal("0.94")
    ): BigDecimal?
}

/**
 * Chainlink Data Streams 实现：障碍模式价源（用户自建直连）。
 * - 期初价 = 窗口起点时间戳处的 Chainlink benchmark；
 * - 当前价 = Chainlink 最新 benchmark；
 * - 期末价 = 窗口终点时间戳处的 benchmark；
 * - σ = 由 [BarrierSigmaEstimator] 按 MAD/EWMA/GK 估计（边界价取自 Chainlink）。
 */
@Service
class ChainlinkPeriodPriceProvider(
    private val chainlink: ChainlinkDataStreamsService,
    private val sigmaEstimator: BarrierSigmaEstimator
) : PeriodPriceProvider {

    /** σ 基准缓存：(slug-interval-period-method) -> sigmaPerSqrtS，按周期复用，避免每 tick 重复采样 */
    private val sigmaCache = ConcurrentHashMap<String, BigDecimal>()
    private val sigmaCacheMax = 2000

    override fun isAvailable(marketSlugPrefix: String): Boolean = chainlink.isConfiguredFor(marketSlugPrefix)

    override fun getReadiness(marketSlugPrefix: String): PeriodPriceProvider.PriceReadiness =
        chainlink.readiness(marketSlugPrefix)

    override fun getCurrentPriceAgeMs(marketSlugPrefix: String): Long? =
        chainlink.currentPriceAgeMs(marketSlugPrefix)

    override fun getCurrentOpenClose(marketSlugPrefix: String, intervalSeconds: Int, periodStartUnix: Long): Pair<BigDecimal, BigDecimal>? {
        val open = chainlink.getPriceAtTimestamp(marketSlugPrefix, periodStartUnix) ?: return null
        val current = chainlink.getCurrentPrice(marketSlugPrefix) ?: return null
        return open to current
    }

    override fun getFinalOpenClose(marketSlugPrefix: String, intervalSeconds: Int, periodStartUnix: Long): Pair<BigDecimal, BigDecimal>? {
        val open = chainlink.getPriceAtTimestamp(marketSlugPrefix, periodStartUnix) ?: return null
        val close = chainlink.getPriceAtTimestamp(marketSlugPrefix, periodStartUnix + intervalSeconds) ?: return null
        return open to close
    }

    override fun getRecentOhlc1m(marketSlugPrefix: String, minutes: Int, nowSeconds: Long): List<PeriodPriceProvider.Ohlc1m> {
        if (minutes <= 0) return emptyList()
        val result = ArrayList<PeriodPriceProvider.Ohlc1m>(minutes)
        val endMinute = nowSeconds - (nowSeconds % 60)
        for (i in minutes downTo 1) {
            val start = endMinute - i * 60L
            val points = (0..59 step 5).mapNotNull { offset ->
                chainlink.getPriceAtTimestamp(marketSlugPrefix, start + offset)
            }
            if (points.isEmpty()) continue
            result.add(
                PeriodPriceProvider.Ohlc1m(
                    minuteStartUnix = start,
                    open = points.first(),
                    high = points.maxOrNull() ?: points.first(),
                    low = points.minOrNull() ?: points.first(),
                    close = points.last(),
                    tickCount = points.size
                )
            )
        }
        return result
    }

    override fun getSigmaPerSqrtS(
        marketSlugPrefix: String,
        intervalSeconds: Int,
        periodStartUnix: Long,
        outcomeIndex: Int,
        sigmaScale: BigDecimal,
        sigmaMethod: String,
        ewmaLambda: BigDecimal
    ): BigDecimal? {
        if (intervalSeconds <= 0 || sigmaScale <= BigDecimal.ZERO) return null
        if (!chainlink.isConfiguredFor(marketSlugPrefix)) return null
        val method = sigmaMethod.uppercase()
        val cacheKey = "$marketSlugPrefix-$intervalSeconds-$periodStartUnix-$method"
        sigmaCache[cacheKey]?.let { return it }
        val sigma = sigmaEstimator.sigmaPerSqrtS(
            marketSlugPrefix = marketSlugPrefix,
            intervalSeconds = intervalSeconds,
            periodStartUnix = periodStartUnix,
            sigmaScale = sigmaScale,
            sigmaMethod = sigmaMethod,
            ewmaLambda = ewmaLambda,
            priceAt = { ts -> chainlink.getPriceAtTimestamp(marketSlugPrefix, ts) },
            currentPrice = { chainlink.getCurrentPrice(marketSlugPrefix) }
        ) ?: return null
        if (sigmaCache.size > sigmaCacheMax) sigmaCache.clear()
        sigmaCache[cacheKey] = sigma
        return sigma
    }
}
