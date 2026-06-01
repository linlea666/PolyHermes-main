package com.wrbug.polymarketbot.service.cryptotail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class BarrierProbabilityTest {

    @Test
    fun `phi matches known standard normal cdf values`() {
        assertEquals(0.5, BarrierProbability.phi(0.0), 1e-6)
        // Phi(1.645) ≈ 0.95, Phi(1.96) ≈ 0.975
        assertEquals(0.95, BarrierProbability.phi(1.6448536), 1e-4)
        assertEquals(0.975, BarrierProbability.phi(1.959964), 1e-4)
        // 对称性
        assertEquals(1.0, BarrierProbability.phi(2.0) + BarrierProbability.phi(-2.0), 1e-6)
    }

    @Test
    fun `pWin increases monotonically as gap grows`() {
        val sigma = BigDecimal("1.0")
        val remaining = 100.0
        var prev = 0.0
        for (g in 0..10) {
            val r = BarrierProbability.winProbTerminal(BigDecimal(g), sigma, remaining)
            assertNotNull(r)
            val p = r!!.pWin.toDouble()
            assertTrue(p >= prev - 1e-9, "pWin 应随 gap 单调不减: gap=$g, p=$p, prev=$prev")
            prev = p
        }
    }

    @Test
    fun `side is up for positive gap and down for negative gap`() {
        val sigma = BigDecimal("1.0")
        assertEquals(0, BarrierProbability.winProbTerminal(BigDecimal("5"), sigma, 100.0)!!.side)
        assertEquals(1, BarrierProbability.winProbTerminal(BigDecimal("-5"), sigma, 100.0)!!.side)
        // gap=0 按 UP 处理，pWin=0.5
        val zero = BarrierProbability.winProbTerminal(BigDecimal.ZERO, sigma, 100.0)!!
        assertEquals(0, zero.side)
        assertEquals(0.5, zero.pWin.toDouble(), 1e-6)
    }

    @Test
    fun `smaller remaining time yields higher pWin for same gap`() {
        val sigma = BigDecimal("1.0")
        val gap = BigDecimal("10")
        val pLongTime = BarrierProbability.winProbTerminal(gap, sigma, 400.0)!!.pWin.toDouble()
        val pShortTime = BarrierProbability.winProbTerminal(gap, sigma, 25.0)!!.pWin.toDouble()
        assertTrue(pShortTime > pLongTime, "剩余时间越短，pWin 越高: short=$pShortTime, long=$pLongTime")
    }

    @Test
    fun `degenerate inputs return null`() {
        assertNull(BarrierProbability.winProbTerminal(BigDecimal("5"), BigDecimal.ZERO, 100.0))
        assertNull(BarrierProbability.winProbTerminal(BigDecimal("5"), BigDecimal("-1"), 100.0))
        assertNull(BarrierProbability.winProbTerminal(BigDecimal("5"), BigDecimal("1.0"), 0.0))
        assertNull(BarrierProbability.winProbTerminal(BigDecimal("5"), BigDecimal("1.0"), -10.0))
    }

    @Test
    fun `safeRatio equals z and pWin equals phi of z`() {
        val sigma = BigDecimal("2.0")
        val gap = BigDecimal("3.0")
        val remaining = 100.0
        val r = BarrierProbability.winProbTerminal(gap, sigma, remaining)!!
        // sd = 2 * sqrt(100) = 20; z = 3/20 = 0.15
        assertEquals(0.15, r.safeRatio.toDouble(), 1e-6)
        assertEquals(BarrierProbability.phi(0.15), r.pWin.toDouble(), 1e-6)
        assertEquals(20.0, r.expectedMove.toDouble(), 1e-6)
        assertEquals(3.0, r.requiredMove.toDouble(), 1e-6)
    }
}
