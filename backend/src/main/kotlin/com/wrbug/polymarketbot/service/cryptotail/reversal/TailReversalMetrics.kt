package com.wrbug.polymarketbot.service.cryptotail.reversal

/**
 * 历史反转统计的精度指标内核（纯函数，BINANCE / POLYMARKET 共用）。
 *
 * 统一口径：以"领先方向胜率 p ∈ [0,1]"为路径变量
 *  - POLYMARKET：p = 真实赔率（favorite 为领先方向时直接取，对手方向取 1-odds）。
 *  - BINANCE：p = BarrierProbability.winProbTerminal 推导的终值胜率（同样换算到领先方向）。
 *
 * 在某观测点入场后，沿"该周期内此观测点之后的所有观测点"前瞻，计算：
 *  - MAE/MFE：相对入场 p 的最大不利/有利偏移；
 *  - 虚拟括号退出：成本=入场 p，先触达 TP(p>=tpLevel) 即按 tpLevel 结清；先触达 STOP(p<=stopLevel) 即按 stopLevel 结清；
 *    两者都未触达则按结算结清（领先方向赢=1，输=0）。pnl = proceeds - cost。
 */
object TailReversalMetrics {

    /** 虚拟括号止盈水平（领先方向胜率） */
    const val TP_LEVEL = 0.99
    /** 虚拟括号止损水平（领先方向胜率） */
    const val STOP_LEVEL = 0.70

    data class BracketResult(
        val mae: Double,
        val mfe: Double,
        val tpHit: Boolean,
        val stopHit: Boolean,
        val pnl: Double,
        val win: Boolean
    )

    /**
     * @param entryP 入场时领先方向胜率（0~1）
     * @param forwardP 该周期内入场点之后的领先方向胜率序列（按时间升序）
     * @param settledLeadWin 周期最终结算时领先方向是否获胜
     */
    fun bracket(entryP: Double, forwardP: List<Double>, settledLeadWin: Boolean): BracketResult {
        var minP = entryP
        var maxP = entryP
        for (p in forwardP) {
            if (p < minP) minP = p
            if (p > maxP) maxP = p
        }
        val mae = (entryP - minP).coerceAtLeast(0.0)
        val mfe = (maxP - entryP).coerceAtLeast(0.0)

        var proceeds: Double? = null
        var tpHit = false
        var stopHit = false
        for (p in forwardP) {
            if (p >= TP_LEVEL) {
                proceeds = TP_LEVEL; tpHit = true; break
            }
            if (p <= STOP_LEVEL) {
                proceeds = STOP_LEVEL; stopHit = true; break
            }
        }
        val finalProceeds = proceeds ?: if (settledLeadWin) 1.0 else 0.0
        val pnl = finalProceeds - entryP
        return BracketResult(mae = mae, mfe = mfe, tpHit = tpHit, stopHit = stopHit, pnl = pnl, win = pnl > 0.0)
    }
}
