package com.wrbug.polymarketbot.service.cryptotail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Smart Hard Stop / Hold-to-settle 纯决策逻辑单测。
 *
 * 复盘单据参数（ETH 5m / DOWN）：entry≈0.87, bestBid 回撤至 0.73,
 * 但 pWin≈0.949 / safeRatio≈1.63 / modelSide 仍=DOWN(1) / gap<0 仍顺 / remaining≈71s。
 * 期望：开启 Smart Hard Stop 且阈值设为 holdToSettlePwin=0.94 / holdToSettleSeconds=90 时豁免持有。
 */
class CryptoTailHoldToSettlePolicyTest {

    private val DOWN = 1
    private val UP = 0

    /** ETH 5m DOWN 复盘场景的默认入参（豁免应成立） */
    private fun ethDownBypass(
        enabled: Boolean = true,
        priceReady: Boolean = true,
        modelSide: Int = DOWN,
        gap: String = "-12.5",
        pWinHolding: String = "0.949",
        safeRatio: String = "1.63",
        remainingSeconds: Int = 71,
        holdToSettlePwin: String = "0.94",
        holdToSettleSeconds: Int = 90,
        exitSafeRatio: String = "0.80"
    ) = CryptoTailHoldToSettlePolicy.evaluateHardStopBypass(
        enabled = enabled,
        priceReady = priceReady,
        outcomeIndex = DOWN,
        modelSide = modelSide,
        gap = BigDecimal(gap),
        pWinHolding = BigDecimal(pWinHolding),
        safeRatio = BigDecimal(safeRatio),
        remainingSeconds = remainingSeconds,
        bypassMinPwin = BigDecimal(holdToSettlePwin),
        holdToSettleSeconds = holdToSettleSeconds,
        exitSafeRatio = BigDecimal(exitSafeRatio)
    )

    // 场景 1：HARD_STOP 命中但模型仍强、gap 顺、方向一致、临近结算 → 豁免持有
    @Test
    fun `bypasses hard stop when model still strong near settlement`() {
        val r = ethDownBypass()
        assertTrue(r.bypass)
        assertEquals("HARD_STOP_BYPASSED_BY_HOLD_TO_SETTLE", r.reason)
    }

    // 场景 2：gap 反转（DOWN 持仓但 gap>0）→ 必须退出
    @Test
    fun `forces hard stop when gap flipped`() {
        val r = ethDownBypass(gap = "5.0")
        assertFalse(r.bypass)
        assertEquals("GAP_FLIP", r.reason)
    }

    // 场景 3：模型方向反转（modelSide=UP，持仓 DOWN）→ 必须退出
    @Test
    fun `forces hard stop when model side flipped`() {
        val r = ethDownBypass(modelSide = UP)
        assertFalse(r.bypass)
        assertEquals("MODEL_FLIP", r.reason)
    }

    // 场景 4：pWinHolding 低于 holdToSettlePwin → 必须退出
    @Test
    fun `forces hard stop when pWin below threshold`() {
        val r = ethDownBypass(pWinHolding = "0.90", holdToSettlePwin = "0.94")
        assertFalse(r.bypass)
        assertEquals("PWIN_BELOW_THRESHOLD", r.reason)
    }

    // 场景 5：remainingSeconds 太长（不是临近结算）→ 必须退出
    @Test
    fun `forces hard stop when too far from settlement`() {
        val r = ethDownBypass(remainingSeconds = 200, holdToSettleSeconds = 90)
        assertFalse(r.bypass)
        assertEquals("NOT_NEAR_SETTLE", r.reason)
    }

    // 场景 6：价源 stale/缺失 → 绝不允许豁免
    @Test
    fun `forces hard stop when price source stale`() {
        val r = ethDownBypass(priceReady = false)
        assertFalse(r.bypass)
        assertEquals("PRICE_SOURCE_INVALID", r.reason)
    }

    // 开关关闭 → 必须退出（默认行为不变）
    @Test
    fun `forces hard stop when disabled`() {
        val r = ethDownBypass(enabled = false)
        assertFalse(r.bypass)
        assertEquals("SMART_HARD_STOP_DISABLED", r.reason)
    }

    // safeRatio 低于硬下限 max(exitSafeRatio, 1.30)：exitSafeRatio=0.80 时下限=1.30，safeRatio=1.20 不足
    @Test
    fun `forces hard stop when safe ratio below floor`() {
        val r = ethDownBypass(safeRatio = "1.20", exitSafeRatio = "0.80")
        assertFalse(r.bypass)
        assertEquals("SAFE_RATIO_BELOW_FLOOR", r.reason)
    }

    // safeRatio 恰好等于硬下限 1.30 → 通过
    @Test
    fun `allows bypass when safe ratio exactly at floor`() {
        val r = ethDownBypass(safeRatio = "1.30", exitSafeRatio = "0.80")
        assertTrue(r.bypass)
    }

    // exitSafeRatio 高于 1.30 时，下限取 exitSafeRatio：1.50 下 safeRatio=1.40 不足
    @Test
    fun `safe ratio floor uses max of exitSafeRatio and constant`() {
        val r = ethDownBypass(safeRatio = "1.40", exitSafeRatio = "1.50")
        assertFalse(r.bypass)
        assertEquals("SAFE_RATIO_BELOW_FLOOR", r.reason)
    }

    // remainingSeconds 恰好等于阈值（边界 <=）→ 通过
    @Test
    fun `allows bypass when remaining equals threshold`() {
        val r = ethDownBypass(remainingSeconds = 90, holdToSettleSeconds = 90)
        assertTrue(r.bypass)
    }

    // BTC 15m UP 场景：同一套逻辑通用（interval 体现在调用方传入的 remainingSeconds/阈值上）
    @Test
    fun `bypasses for btc 15m up position when strong`() {
        val r = CryptoTailHoldToSettlePolicy.evaluateHardStopBypass(
            enabled = true,
            priceReady = true,
            outcomeIndex = UP,
            modelSide = UP,
            gap = BigDecimal("8.0"),
            pWinHolding = BigDecimal("0.96"),
            safeRatio = BigDecimal("1.80"),
            remainingSeconds = 80,
            bypassMinPwin = BigDecimal("0.95"),
            holdToSettleSeconds = 90,
            exitSafeRatio = BigDecimal("0.80")
        )
        assertTrue(r.bypass)
    }

    // BTC 15m UP：gap<0 反转 → 退出
    @Test
    fun `forces hard stop for btc 15m up when gap flipped`() {
        val r = CryptoTailHoldToSettlePolicy.evaluateHardStopBypass(
            enabled = true,
            priceReady = true,
            outcomeIndex = UP,
            modelSide = UP,
            gap = BigDecimal("-3.0"),
            pWinHolding = BigDecimal("0.96"),
            safeRatio = BigDecimal("1.80"),
            remainingSeconds = 80,
            bypassMinPwin = BigDecimal("0.95"),
            holdToSettleSeconds = 90,
            exitSafeRatio = BigDecimal("0.80")
        )
        assertFalse(r.bypass)
        assertEquals("GAP_FLIP", r.reason)
    }

    // 普通 HOLD_TO_SETTLE 分支：行为等价校验（DOWN，gap<0，pWin达标，临近结算）
    @Test
    fun `canHoldToSettle true when conditions met`() {
        assertTrue(
            CryptoTailHoldToSettlePolicy.canHoldToSettle(
                outcomeIndex = DOWN,
                gap = BigDecimal("-12.5"),
                pWinHolding = BigDecimal("0.949"),
                remainingSeconds = 71,
                holdToSettlePwin = BigDecimal("0.94"),
                holdToSettleSeconds = 90
            )
        )
    }

    @Test
    fun `canHoldToSettle false when gap not supporting`() {
        assertFalse(
            CryptoTailHoldToSettlePolicy.canHoldToSettle(
                outcomeIndex = DOWN,
                gap = BigDecimal("3.0"),
                pWinHolding = BigDecimal("0.949"),
                remainingSeconds = 71,
                holdToSettlePwin = BigDecimal("0.94"),
                holdToSettleSeconds = 90
            )
        )
    }

    @Test
    fun `canHoldToSettle false when remaining too long`() {
        assertFalse(
            CryptoTailHoldToSettlePolicy.canHoldToSettle(
                outcomeIndex = DOWN,
                gap = BigDecimal("-12.5"),
                pWinHolding = BigDecimal("0.949"),
                remainingSeconds = 200,
                holdToSettlePwin = BigDecimal("0.94"),
                holdToSettleSeconds = 90
            )
        )
    }

    @Test
    fun `gapSupportsHolding direction semantics`() {
        assertTrue(CryptoTailHoldToSettlePolicy.gapSupportsHolding(UP, BigDecimal("1")))
        assertFalse(CryptoTailHoldToSettlePolicy.gapSupportsHolding(UP, BigDecimal("-1")))
        assertTrue(CryptoTailHoldToSettlePolicy.gapSupportsHolding(DOWN, BigDecimal("-1")))
        assertFalse(CryptoTailHoldToSettlePolicy.gapSupportsHolding(DOWN, BigDecimal("1")))
        // gap 恰为 0：任一方向都不支持持有
        assertFalse(CryptoTailHoldToSettlePolicy.gapSupportsHolding(UP, BigDecimal.ZERO))
        assertFalse(CryptoTailHoldToSettlePolicy.gapSupportsHolding(DOWN, BigDecimal.ZERO))
    }
}
