package com.wrbug.polymarketbot.service.cryptotail

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * crypto-tail 终值概率内核（纯函数，无外部依赖）。
 *
 * 模型：Up/Down 市场仅以周期末 close 是否越过 open 结算（终值型，非触碰障碍）。
 * 给定当前价相对开盘价的位移 gap 与剩余时间波动，计算"周期末停留在当前方向"的概率 pWin。
 *
 * gap  = close - open；side = UP(0) if gap>=0 else DOWN(1)
 * sd   = sigmaPerSqrtS * sqrt(remainingSeconds)
 * z    = |gap| / sd
 * pWin = Phi(z)（标准正态 CDF，erf 近似，JVM 无 Math.erf）
 */
object BarrierProbability {

    /** winProbTerminal 计算结果 */
    data class Result(
        /** 模型方向：0=UP, 1=DOWN（gap=0 时按 UP 处理，z=0 → pWin=0.5） */
        val side: Int,
        /** 终值胜率，理论区间 [0.5, 1.0] */
        val pWin: BigDecimal,
        /** 当前距开盘的位移绝对值（= 需要维持的方向幅度） */
        val requiredMove: BigDecimal,
        /** 剩余时间的预期波动 sd */
        val expectedMove: BigDecimal,
        /** 安全比 = requiredMove / expectedMove（即 z 值） */
        val safeRatio: BigDecimal
    )

    private const val SCALE = 10
    private const val SQRT2 = 1.4142135623730951

    /**
     * 标准正态分布 CDF Phi(x)，基于 erf 近似（Abramowitz & Stegun 7.1.26，绝对误差 < 1.5e-7）。
     */
    fun phi(x: Double): Double = 0.5 * (1.0 + erf(x / SQRT2))

    /** erf 近似：Abramowitz & Stegun 7.1.26，最大绝对误差 1.5e-7 */
    private fun erf(x: Double): Double {
        val sign = if (x < 0) -1.0 else 1.0
        val ax = abs(x)
        val t = 1.0 / (1.0 + 0.3275911 * ax)
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-ax * ax)
        return sign * y
    }

    /**
     * 计算终值胜率。
     *
     * @param gap close - open（带符号，价格单位）
     * @param sigmaPerSqrtS 每 √秒 的波动率（价格单位）
     * @param remainingSeconds 距周期末剩余秒数
     * @return null 表示无法计算（sigmaPerSqrtS<=0 或 remainingSeconds<=0），调用方应按"跳过"处理
     */
    fun winProbTerminal(gap: BigDecimal, sigmaPerSqrtS: BigDecimal, remainingSeconds: Double): Result? {
        if (sigmaPerSqrtS <= BigDecimal.ZERO) return null
        if (remainingSeconds <= 0.0) return null
        val sd = sigmaPerSqrtS.toDouble() * sqrt(remainingSeconds)
        if (sd <= 0.0) return null
        val side = if (gap.signum() >= 0) 0 else 1
        val requiredMove = gap.abs()
        val z = requiredMove.toDouble() / sd
        val pWin = phi(z)
        return Result(
            side = side,
            pWin = BigDecimal(pWin).setScale(SCALE, RoundingMode.HALF_UP),
            requiredMove = requiredMove,
            expectedMove = BigDecimal(sd).setScale(SCALE, RoundingMode.HALF_UP),
            safeRatio = BigDecimal(z).setScale(SCALE, RoundingMode.HALF_UP)
        )
    }
}
