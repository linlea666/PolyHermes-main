package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.service.binance.BinanceKlineAutoSpreadService
import com.wrbug.polymarketbot.service.chainlink.ChainlinkDataStreamsService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 周期价源抽象：为 crypto-tail 障碍模型提供与「结算同源」的期初/当前/期末价与波动率。
 *
 * 障碍模式必须用 Chainlink（结算源）。旧 spread 模式仍直接用 BinanceKlineService，不走此抽象（保守，不动旧逻辑）。
 */
interface PeriodPriceProvider {
    /** 该市场价源是否可用（凭证 + feedID） */
    fun isAvailable(marketSlugPrefix: String): Boolean

    /** 当前周期 (期初价, 当前价)；任一缺失返回 null */
    fun getCurrentOpenClose(marketSlugPrefix: String, intervalSeconds: Int, periodStartUnix: Long): Pair<BigDecimal, BigDecimal>?

    /** 结算用 (期初价, 期末价)；任一缺失返回 null */
    fun getFinalOpenClose(marketSlugPrefix: String, intervalSeconds: Int, periodStartUnix: Long): Pair<BigDecimal, BigDecimal>?

    /**
     * 每 √秒 波动率 σ_per_√s。outcomeIndex 仅为兼容接口（终值波动率与方向无关，Chainlink 实现忽略之）。
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
 * Chainlink Data Streams 实现：障碍模式价源。
 * - 期初价 = 窗口起点时间戳处的 Chainlink benchmark；
 * - 当前价 = Chainlink 最新 benchmark；
 * - 期末价 = 窗口终点时间戳处的 benchmark；
 * - σ = 过去 N 个周期边界价的平均绝对位移 × sigmaScale / √interval（与原币安公式同口径）。
 */
@Service
class ChainlinkPeriodPriceProvider(
    private val chainlink: ChainlinkDataStreamsService,
    private val binanceKlineAutoSpreadService: BinanceKlineAutoSpreadService
) : PeriodPriceProvider {

    private val logger = LoggerFactory.getLogger(ChainlinkPeriodPriceProvider::class.java)

    /** σ 基准缓存：(slug-interval-period) -> sigmaPerSqrtS，按周期复用，避免每 tick 重复采样 */
    private val sigmaCache = ConcurrentHashMap<String, BigDecimal>()
    private val sigmaCacheMax = 2000

    /** σ 估计回看周期数 */
    private val sigmaLookbackPeriods = 20
    private val minSamples = 3

    override fun isAvailable(marketSlugPrefix: String): Boolean = chainlink.isConfiguredFor(marketSlugPrefix)

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

        val sqrtInterval = BigDecimal(sqrt(intervalSeconds.toDouble()))
        if (sqrtInterval <= BigDecimal.ZERO) return null

        // baseSpread：每周期价格位移的代表性幅度（价格单位），三法分别估计
        val baseSpread: BigDecimal? = when (method) {
            "EWMA" -> computeEwmaBaseSpread(marketSlugPrefix, intervalSeconds, periodStartUnix, ewmaLambda)
            "GARMAN_KLASS" -> computeGarmanKlassBaseSpread(marketSlugPrefix, intervalSeconds, periodStartUnix)
            else -> computeMadBaseSpread(marketSlugPrefix, intervalSeconds, periodStartUnix)
        }
        if (baseSpread == null || baseSpread <= BigDecimal.ZERO) {
            logger.warn("Chainlink σ 估计失败: market=$marketSlugPrefix interval=${intervalSeconds}s method=$method")
            return null
        }
        val sigma = baseSpread.multiply(sigmaScale).divide(sqrtInterval, 18, RoundingMode.HALF_UP)
        if (sigmaCache.size > sigmaCacheMax) sigmaCache.clear()
        sigmaCache[cacheKey] = sigma
        logger.info("Chainlink σ 已计算: market=$marketSlugPrefix interval=${intervalSeconds}s period=$periodStartUnix method=$method baseSpread=${baseSpread.toPlainString()} sigmaPerSqrtS=${sigma.toPlainString()}")
        return sigma
    }

    /** 采样过去 N+1 个周期边界价（Chainlink），t_k = periodStartUnix - k*interval（k=0..N） */
    private fun sampleBoundaryPrices(marketSlugPrefix: String, intervalSeconds: Int, periodStartUnix: Long): List<BigDecimal> {
        val prices = ArrayList<BigDecimal>(sigmaLookbackPeriods + 1)
        for (k in 0..sigmaLookbackPeriods) {
            val ts = periodStartUnix - k.toLong() * intervalSeconds
            val p = chainlink.getPriceAtTimestamp(marketSlugPrefix, ts) ?: continue
            if (p > BigDecimal.ZERO) prices.add(p)
        }
        return prices
    }

    /** MAD：相邻周期绝对位移的 IQR 平均（原口径，sigmaScale 默认 √(π/2) 修正为标准差） */
    private fun computeMadBaseSpread(marketSlugPrefix: String, intervalSeconds: Int, periodStartUnix: Long): BigDecimal? {
        val prices = sampleBoundaryPrices(marketSlugPrefix, intervalSeconds, periodStartUnix)
        if (prices.size < minSamples + 1) {
            logger.warn("Chainlink σ(MAD) 样本不足: market=$marketSlugPrefix 样本=${prices.size}")
            return null
        }
        val displacements = ArrayList<BigDecimal>(prices.size - 1)
        for (i in 0 until prices.size - 1) {
            displacements.add(prices[i].subtract(prices[i + 1]).abs())
        }
        return averageAfterIqr(displacements)
    }

    /**
     * EWMA：对相邻周期位移平方做指数加权（最新权重高），开方得 RMS 位移幅度（标准差量纲）。
     * σ²_t = λ·σ²_{t-1} + (1-λ)·r²_t；按时间从旧到新递推。EWMA 已是标准差量纲，sigmaScale 建议设 1.0。
     */
    private fun computeEwmaBaseSpread(marketSlugPrefix: String, intervalSeconds: Int, periodStartUnix: Long, lambda: BigDecimal): BigDecimal? {
        val prices = sampleBoundaryPrices(marketSlugPrefix, intervalSeconds, periodStartUnix)
        if (prices.size < minSamples + 1) {
            logger.warn("Chainlink σ(EWMA) 样本不足: market=$marketSlugPrefix 样本=${prices.size}")
            return null
        }
        val lam = lambda.toDouble().coerceIn(0.0, 0.999)
        // prices[0]=最新，越大越旧；位移 r_i = prices[i]-prices[i+1]，从最旧到最新递推
        val displacements = ArrayList<Double>(prices.size - 1)
        for (i in 0 until prices.size - 1) {
            displacements.add(prices[i].subtract(prices[i + 1]).toDouble())
        }
        // 反转为时间升序（最旧在前）
        displacements.reverse()
        var variance = displacements.first() * displacements.first()
        for (i in 1 until displacements.size) {
            val r = displacements[i]
            variance = lam * variance + (1 - lam) * r * r
        }
        if (variance <= 0.0) return null
        return BigDecimal(sqrt(variance))
    }

    /**
     * Garman-Klass：用币安 OHLC 作波动幅度代理（与 Chainlink 价格水平解耦，仅取波动率量级）。
     * 每周期 σ²_GK(对数) = 0.5·(ln(H/L))² − (2ln2−1)·(ln(C/O))²；IQR 平均后开方得对数波动，
     * 再乘当期参考价（Chainlink 期初价）换算为价格量纲。
     */
    private fun computeGarmanKlassBaseSpread(marketSlugPrefix: String, intervalSeconds: Int, periodStartUnix: Long): BigDecimal? {
        val ohlcList = binanceKlineAutoSpreadService.fetchRecentOhlc(marketSlugPrefix, intervalSeconds, sigmaLookbackPeriods, periodStartUnix)
        if (ohlcList == null || ohlcList.size < minSamples) {
            logger.warn("Chainlink σ(GK) 币安OHLC样本不足: market=$marketSlugPrefix 样本=${ohlcList?.size ?: 0}")
            return null
        }
        val twoLn2Minus1 = 2.0 * ln(2.0) - 1.0
        val gkVars = ArrayList<BigDecimal>(ohlcList.size)
        for (o in ohlcList) {
            val hl = ln(o.high.toDouble() / o.low.toDouble())
            val co = ln(o.close.toDouble() / o.open.toDouble())
            val v = 0.5 * hl * hl - twoLn2Minus1 * co * co
            if (v.isFinite() && v > 0.0) gkVars.add(BigDecimal(v))
        }
        if (gkVars.size < minSamples) return null
        val avgVarLog = averageAfterIqr(gkVars)
        if (avgVarLog <= BigDecimal.ZERO) return null
        val sigmaLog = sqrt(avgVarLog.toDouble())
        // 参考价：Chainlink 期初价；缺失则用最新价
        val refPrice = chainlink.getPriceAtTimestamp(marketSlugPrefix, periodStartUnix)
            ?: chainlink.getCurrentPrice(marketSlugPrefix)
            ?: return null
        if (refPrice <= BigDecimal.ZERO) return null
        // 对数波动 × 参考价 ≈ 价格量纲的每周期位移幅度（标准差量纲）
        return BigDecimal(sigmaLog).multiply(refPrice)
    }

    /** IQR 剔除异常后求平均；剔除后样本 < minSamples 则用全量。与 BinanceKlineAutoSpreadService 同口径。 */
    private fun averageAfterIqr(list: List<BigDecimal>): BigDecimal {
        if (list.isEmpty()) return BigDecimal.ZERO
        val sorted = list.sorted()
        val n = sorted.size
        val q1 = sorted[(n * 0.25).toInt().coerceIn(0, n - 1)]
        val q3 = sorted[(n * 0.75).toInt().coerceIn(0, n - 1)]
        val iqr = q3.subtract(q1)
        val lower = q1.subtract(iqr.multiply(BigDecimal("1.5")))
        val upper = q3.add(iqr.multiply(BigDecimal("1.5")))
        val filtered = sorted.filter { it >= lower && it <= upper }
        val use = if (filtered.size < minSamples) sorted else filtered
        return use.fold(BigDecimal.ZERO) { a, b -> a.add(b) }.divide(BigDecimal(use.size), 18, RoundingMode.HALF_UP)
    }
}
