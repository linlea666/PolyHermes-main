package com.wrbug.polymarketbot.service.cryptotail

import java.math.BigDecimal

/**
 * 持有到结算 / 智能硬止损复核 的纯决策逻辑（无状态、无依赖，便于单测）。
 *
 * 对齐 [CryptoTailFakPricingPolicy] 的"纯对象 + 静态方法"模式。本对象同时承载两处判定，
 * 由 [CryptoTailBracketExitService.decideExit] 复用，避免逻辑分叉：
 *
 *  1) [canHoldToSettle]：普通 HOLD_TO_SETTLE 分支判定（行为等价于此前内联实现：
 *     剩余时间 <= 阈值 且 pWin >= 阈值 且 gap 仍支持持仓方向）。
 *
 *  2) [evaluateHardStopBypass]：智能硬止损复核（Smart Hard Stop）。HARD_STOP 命中后先复核，
 *     仅当"开关开启 + 价源新鲜 + 模型方向未反 + gap 仍顺 + 临近结算 + pWin 达标 + safeRatio 达标"
 *     全部满足时，才允许放弃机械硬止损、继续持有到结算。任一不满足都强制走 HARD_STOP。
 */
object CryptoTailHoldToSettlePolicy {

    /**
     * Smart Hard Stop 豁免的 safeRatio 硬下限（代码常量）。
     * 实际下限取 max(strategy.exitSafeRatio, 此常量)，避免把豁免做成"无脑硬扛"。
     */
    val SMART_HARD_STOP_MIN_SAFE_RATIO: BigDecimal = BigDecimal("1.30")

    /**
     * gap 是否仍支持持仓方向：
     *  - UP(outcomeIndex=0)：gap>0（当前价高于期初价）
     *  - DOWN(outcomeIndex=1)：gap<0（当前价低于期初价）
     */
    fun gapSupportsHolding(outcomeIndex: Int, gap: BigDecimal): Boolean =
        when (outcomeIndex) {
            0 -> gap > BigDecimal.ZERO
            1 -> gap < BigDecimal.ZERO
            else -> false
        }

    /**
     * 普通 HOLD_TO_SETTLE 判定（与原内联条件完全等价）。
     */
    fun canHoldToSettle(
        outcomeIndex: Int,
        gap: BigDecimal,
        pWinHolding: BigDecimal,
        remainingSeconds: Int,
        holdToSettlePwin: BigDecimal,
        holdToSettleSeconds: Int
    ): Boolean =
        remainingSeconds <= holdToSettleSeconds &&
            pWinHolding >= holdToSettlePwin &&
            gapSupportsHolding(outcomeIndex, gap)

    /** 智能硬止损复核结果：是否豁免 + 原因（用于审计日志）。 */
    data class BypassResult(
        val bypass: Boolean,
        val reason: String
    )

    /**
     * 智能硬止损复核：HARD_STOP 命中后判断是否可被 HOLD_TO_SETTLE 覆盖。
     *
     * 返回 bypass=true 表示放弃硬止损、继续持有到结算；false 表示强制硬止损，reason 标明原因。
     *
     * @param enabled strategy.enableSmartHardStop
     * @param priceReady 价源是否新鲜（getCurrentPriceAgeMs 非空且 <= maxPriceAgeMs；不新鲜/缺失=false）
     * @param outcomeIndex 持仓方向 0=UP 1=DOWN
     * @param modelSide 当前模型方向
     * @param gap 当前 gap（current - open）
     * @param pWinHolding 当前持仓方向胜率
     * @param safeRatio 当前安全比
     * @param remainingSeconds 距结算剩余秒
     * @param bypassMinPwin 插针容忍 pWin 下限：判为盘口插针、放弃本次硬止损所需的最低持仓胜率。
     *        与"持有到结算 pWin"解耦——忽略一次插针只需模型仍明显站我方，不需 0.96 级的持有到结算信心。
     *        BARRIER 传 holdToSettlePwin（行为不变）；SCALP_FLIP 传 scalpSmartStopMinPwin（默认 0.70）。
     * @param holdToSettleSeconds 持有到结算的剩余秒阈值
     * @param exitSafeRatio 退出安全比阈值（与 minSafeRatioFloor 取较大者作为下限）
     * @param minSafeRatioFloor safeRatio 硬下限（默认 SMART_HARD_STOP_MIN_SAFE_RATIO=1.30）；SCALP 由 scalpSmartStopMinSafeRatio 配置
     */
    fun evaluateHardStopBypass(
        enabled: Boolean,
        priceReady: Boolean,
        outcomeIndex: Int,
        modelSide: Int,
        gap: BigDecimal,
        pWinHolding: BigDecimal,
        safeRatio: BigDecimal,
        remainingSeconds: Int,
        bypassMinPwin: BigDecimal,
        holdToSettleSeconds: Int,
        exitSafeRatio: BigDecimal,
        minSafeRatioFloor: BigDecimal = SMART_HARD_STOP_MIN_SAFE_RATIO
    ): BypassResult {
        if (!enabled) return BypassResult(false, "SMART_HARD_STOP_DISABLED")
        // 价源 stale/不可用：绝不允许用过期数据豁免
        if (!priceReady) return BypassResult(false, "PRICE_SOURCE_INVALID")
        // 模型方向已反转：必须退出
        if (modelSide != outcomeIndex) return BypassResult(false, "MODEL_FLIP")
        // gap 已反转：必须退出
        if (!gapSupportsHolding(outcomeIndex, gap)) return BypassResult(false, "GAP_FLIP")
        // 还很长，不是临近结算：必须退出
        if (remainingSeconds > holdToSettleSeconds) return BypassResult(false, "NOT_NEAR_SETTLE")
        // pWin 明显低于插针容忍下限：判为真反转，必须退出
        if (pWinHolding < bypassMinPwin) return BypassResult(false, "PWIN_BELOW_THRESHOLD")
        // safeRatio 低于安全下限：必须退出。下限 = max(exitSafeRatio, minSafeRatioFloor)。
        // minSafeRatioFloor 默认取 SMART_HARD_STOP_MIN_SAFE_RATIO(1.30)；SCALP 可由 scalpSmartStopMinSafeRatio 配置。
        val safeFloor = exitSafeRatio.max(minSafeRatioFloor)
        if (safeRatio < safeFloor) return BypassResult(false, "SAFE_RATIO_BELOW_FLOOR")
        return BypassResult(true, "HARD_STOP_BYPASSED_BY_HOLD_TO_SETTLE")
    }
}
