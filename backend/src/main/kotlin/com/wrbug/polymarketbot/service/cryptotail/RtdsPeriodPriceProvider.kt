package com.wrbug.polymarketbot.service.cryptotail

import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * Polymarket RTDS 实现：障碍模式免凭证价源（默认）。
 *
 * 价源为 [PolymarketRtdsCryptoPriceService]（订阅 Polymarket 官方 RTDS 的 crypto_prices_chainlink，
 * 与结算同源的 Chainlink 流，免鉴权）：
 * - 期初价 = 周期起点时间戳处的历史价（floorEntry）；
 * - 当前价 = RTDS 最新价；
 * - 期末价 = 周期终点时间戳处的历史价；
 * - σ = 由 [BarrierSigmaEstimator] 按 MAD/EWMA/GK 估计（边界价取自 RTDS 历史价；冷启动样本不足返回 null）。
 *
 * 失败安全：任一价缺失返回 null，调用方据此跳过下单。
 */
@Service
class RtdsPeriodPriceProvider(
    private val rtds: PolymarketRtdsCryptoPriceService,
    private val sigmaEstimator: BarrierSigmaEstimator
) : PeriodPriceProvider {

    /** σ 基准缓存：(slug-interval-period-method) -> sigmaPerSqrtS，按周期复用，避免每 tick 重复采样 */
    private val sigmaCache = ConcurrentHashMap<String, BigDecimal>()
    private val sigmaCacheMax = 2000

    override fun isAvailable(marketSlugPrefix: String): Boolean = rtds.isReady(marketSlugPrefix)

    override fun getReadiness(marketSlugPrefix: String): PeriodPriceProvider.PriceReadiness =
        rtds.readiness(marketSlugPrefix)

    override fun getCurrentOpenClose(marketSlugPrefix: String, intervalSeconds: Int, periodStartUnix: Long): Pair<BigDecimal, BigDecimal>? {
        val open = rtds.priceAt(marketSlugPrefix, periodStartUnix) ?: return null
        val current = rtds.currentPrice(marketSlugPrefix) ?: return null
        return open to current
    }

    override fun getFinalOpenClose(marketSlugPrefix: String, intervalSeconds: Int, periodStartUnix: Long): Pair<BigDecimal, BigDecimal>? {
        val open = rtds.priceAt(marketSlugPrefix, periodStartUnix) ?: return null
        val close = rtds.priceAt(marketSlugPrefix, periodStartUnix + intervalSeconds) ?: return null
        return open to close
    }

    override fun getRecentOhlc1m(marketSlugPrefix: String, minutes: Int, nowSeconds: Long): List<PeriodPriceProvider.Ohlc1m> {
        return rtds.recentOhlc1m(marketSlugPrefix, minutes, nowSeconds)
    }

    override fun getCurrentPriceAgeMs(marketSlugPrefix: String): Long? =
        rtds.currentPriceAgeMs(marketSlugPrefix)

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
        if (!rtds.isReady(marketSlugPrefix)) return null
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
            priceAt = { ts -> rtds.priceAt(marketSlugPrefix, ts) },
            currentPrice = { rtds.currentPrice(marketSlugPrefix) }
        ) ?: return null
        if (sigmaCache.size > sigmaCacheMax) sigmaCache.clear()
        sigmaCache[cacheKey] = sigma
        return sigma
    }
}
