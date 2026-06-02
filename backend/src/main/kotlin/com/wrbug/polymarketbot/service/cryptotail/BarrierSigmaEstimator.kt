package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.service.binance.BinanceKlineAutoSpreadService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 障碍模型每 √秒 波动率 σ_per_√s 估计器（价源无关）。
 *
 * 从 [ChainlinkPeriodPriceProvider] 提取的共享 σ 数学（MAD / EWMA / Garman-Klass / IQR），
 * 供 Chainlink、RTDS 等不同价源实现共用：边界价通过注入的 [priceAt]/[currentPrice] 取得，
 * 与具体价源解耦。各价源实现负责"可用性检查 + 结果缓存"，本类只做无状态计算。
 *
 * 公式与原 Chainlink 实现完全一致：σ = baseSpread × sigmaScale / √interval。
 */
@Service
class BarrierSigmaEstimator(
    private val binanceKlineAutoSpreadService: BinanceKlineAutoSpreadService
) {

    private val logger = LoggerFactory.getLogger(BarrierSigmaEstimator::class.java)

    /** σ 估计回看周期数 */
    private val sigmaLookbackPeriods = 20
    private val minSamples = 3

    /** σ 日志按周期去重：同一 (市场-周期-方法-场景) 仅打一次，避免每个 WS tick 刷屏 */
    private val loggedOnce = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private fun firstTime(key: String): Boolean {
        if (loggedOnce.size > 5000) loggedOnce.clear()
        return loggedOnce.add(key)
    }

    /**
     * 计算 σ_per_√s。无法估计（样本不足/参数非法）返回 null。
     * @param priceAt 取指定 Unix 秒时间戳处价的函数（边界价）
     * @param currentPrice 取当前最新价的函数（GK 参考价兜底）
     */
    fun sigmaPerSqrtS(
        marketSlugPrefix: String,
        intervalSeconds: Int,
        periodStartUnix: Long,
        sigmaScale: BigDecimal,
        sigmaMethod: String,
        ewmaLambda: BigDecimal,
        priceAt: (Long) -> BigDecimal?,
        currentPrice: () -> BigDecimal?
    ): BigDecimal? {
        if (intervalSeconds <= 0 || sigmaScale <= BigDecimal.ZERO) return null
        val method = sigmaMethod.uppercase()
        val sqrtInterval = BigDecimal(sqrt(intervalSeconds.toDouble()))
        if (sqrtInterval <= BigDecimal.ZERO) return null

        val keyBase = "$marketSlugPrefix-$intervalSeconds-$periodStartUnix-$method"
        // baseSpread：每周期价格位移的代表性幅度（价格单位），三法分别估计
        val baseSpread: BigDecimal? = when (method) {
            "EWMA" -> computeEwmaBaseSpread(keyBase, intervalSeconds, periodStartUnix, ewmaLambda, priceAt)
            "GARMAN_KLASS" -> computeGarmanKlassBaseSpread(keyBase, marketSlugPrefix, intervalSeconds, periodStartUnix, priceAt, currentPrice)
            else -> computeMadBaseSpread(keyBase, intervalSeconds, periodStartUnix, priceAt)
        }
        if (baseSpread == null || baseSpread <= BigDecimal.ZERO) {
            if (firstTime("$keyBase-fail")) logger.warn("障碍 σ 估计失败: market=$marketSlugPrefix interval=${intervalSeconds}s method=$method")
            return null
        }
        val sigma = baseSpread.multiply(sigmaScale).divide(sqrtInterval, 18, RoundingMode.HALF_UP)
        if (firstTime("$keyBase-ok")) logger.info("障碍 σ 已计算: market=$marketSlugPrefix interval=${intervalSeconds}s period=$periodStartUnix method=$method baseSpread=${baseSpread.toPlainString()} sigmaPerSqrtS=${sigma.toPlainString()}")
        return sigma
    }

    /** 采样过去 N+1 个周期边界价，t_k = periodStartUnix - k*interval（k=0..N） */
    private fun sampleBoundaryPrices(intervalSeconds: Int, periodStartUnix: Long, priceAt: (Long) -> BigDecimal?): List<BigDecimal> {
        val prices = ArrayList<BigDecimal>(sigmaLookbackPeriods + 1)
        for (k in 0..sigmaLookbackPeriods) {
            val ts = periodStartUnix - k.toLong() * intervalSeconds
            val p = priceAt(ts) ?: continue
            if (p > BigDecimal.ZERO) prices.add(p)
        }
        return prices
    }

    /** MAD：相邻周期绝对位移的 IQR 平均（原口径，sigmaScale 默认 √(π/2) 修正为标准差） */
    private fun computeMadBaseSpread(keyBase: String, intervalSeconds: Int, periodStartUnix: Long, priceAt: (Long) -> BigDecimal?): BigDecimal? {
        val prices = sampleBoundaryPrices(intervalSeconds, periodStartUnix, priceAt)
        if (prices.size < minSamples + 1) {
            if (firstTime("$keyBase-insuf")) logger.warn("障碍 σ(MAD) 样本不足: 样本=${prices.size}（边界价取自价源内存历史，冷启动需累积多周期；建议改用 Garman-Klass）")
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
    private fun computeEwmaBaseSpread(keyBase: String, intervalSeconds: Int, periodStartUnix: Long, lambda: BigDecimal, priceAt: (Long) -> BigDecimal?): BigDecimal? {
        val prices = sampleBoundaryPrices(intervalSeconds, periodStartUnix, priceAt)
        if (prices.size < minSamples + 1) {
            if (firstTime("$keyBase-insuf")) logger.warn("障碍 σ(EWMA) 样本不足: 样本=${prices.size}（边界价取自价源内存历史，冷启动需累积多周期；建议改用 Garman-Klass）")
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
     * Garman-Klass：用币安 OHLC 作波动幅度代理（与价源价格水平解耦，仅取波动率量级）。
     * 每周期 σ²_GK(对数) = 0.5·(ln(H/L))² − (2ln2−1)·(ln(C/O))²；IQR 平均后开方得对数波动，
     * 再乘当期参考价（期初价）换算为价格量纲。
     */
    private fun computeGarmanKlassBaseSpread(
        keyBase: String,
        marketSlugPrefix: String,
        intervalSeconds: Int,
        periodStartUnix: Long,
        priceAt: (Long) -> BigDecimal?,
        currentPrice: () -> BigDecimal?
    ): BigDecimal? {
        val ohlcList = binanceKlineAutoSpreadService.fetchRecentOhlc(marketSlugPrefix, intervalSeconds, sigmaLookbackPeriods, periodStartUnix)
        if (ohlcList == null || ohlcList.size < minSamples) {
            if (firstTime("$keyBase-insuf")) logger.warn("障碍 σ(GK) 币安OHLC样本不足: market=$marketSlugPrefix 样本=${ohlcList?.size ?: 0}")
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
        // 参考价：期初价；缺失则用最新价
        val refPrice = priceAt(periodStartUnix)
            ?: currentPrice()
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
