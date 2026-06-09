package com.wrbug.polymarketbot.service.cryptotail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * [resolveScalpEvLimit] 纯函数回归：覆盖 CLAMP/GUARD/OFF 三模式与边界。
 * 典型尾盘场景：evSafeLimit≈pWin=0.88 < ask=0.93，base=ask+滑点=0.95（封顶前）。
 */
class ScalpEvLimitResolverTest {

    private val base = BigDecimal("0.95")
    private val evSafeLimit = BigDecimal("0.88")
    private val ask = BigDecimal("0.93")

    @Test
    fun `CLAMP caps limit to ask when evSafeLimit below ask (current behavior, slippage neutralized)`() {
        val limit = resolveScalpEvLimit("CLAMP", BigDecimal("0.10"), base, evSafeLimit, ask)
        // chaseCeil = max(0.88, 0.93) = 0.93 → base.min(0.93) = 0.93 = ask（滑点被抹平）
        assertEquals("0.93", limit.toPlainString())
    }

    @Test
    fun `unknown mode falls back to CLAMP`() {
        val limit = resolveScalpEvLimit("WHATEVER", BigDecimal("0.10"), base, evSafeLimit, ask)
        assertEquals("0.93", limit.toPlainString())
    }

    @Test
    fun `OFF never caps to EV safe price, slippage fully alive`() {
        val limit = resolveScalpEvLimit("OFF", BigDecimal("0.10"), base, evSafeLimit, ask)
        assertEquals("0.95", limit.toPlainString())
    }

    @Test
    fun `GUARD passes through base on normal divergence (within margin)`() {
        // divergence = ask - evSafeLimit = 0.05 <= margin 0.10 → 放行 base（滑点生效）
        val limit = resolveScalpEvLimit("GUARD", BigDecimal("0.10"), base, evSafeLimit, ask)
        assertEquals("0.95", limit.toPlainString())
    }

    @Test
    fun `GUARD clamps to ask on extreme divergence (beyond margin)`() {
        // 坏数据/飞刀：evSafeLimit=0.60, ask=0.93 → divergence 0.33 > margin 0.10 → 钳到 chaseCeil=ask=0.93
        val limit = resolveScalpEvLimit("GUARD", BigDecimal("0.10"), base, BigDecimal("0.60"), ask)
        assertEquals("0.93", limit.toPlainString())
    }

    @Test
    fun `GUARD with non-positive margin degrades to CLAMP`() {
        val limit = resolveScalpEvLimit("GUARD", BigDecimal.ZERO, base, evSafeLimit, ask)
        assertEquals("0.93", limit.toPlainString())
    }

    @Test
    fun `case-insensitive and trimmed mode`() {
        val limit = resolveScalpEvLimit("  off  ", BigDecimal("0.10"), base, evSafeLimit, ask)
        assertEquals("0.95", limit.toPlainString())
    }

    @Test
    fun `chaseCeil never below ask, so no mode rejects entry (limit always at least ask)`() {
        // evSafeLimit > ask（模型比市场更看多）：CLAMP chaseCeil=max(0.96,0.93)=0.96 → base.min(0.96)=base 0.95（滑点存活）
        val limit = resolveScalpEvLimit("CLAMP", BigDecimal("0.10"), base, BigDecimal("0.96"), ask)
        assertEquals("0.95", limit.toPlainString())
    }
}
