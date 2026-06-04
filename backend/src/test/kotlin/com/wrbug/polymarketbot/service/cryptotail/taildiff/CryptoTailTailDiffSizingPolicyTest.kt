package com.wrbug.polymarketbot.service.cryptotail.taildiff

import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import com.wrbug.polymarketbot.enums.TradingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CryptoTailTailDiffSizingPolicyTest {

    private val policy = CryptoTailTailDiffSizingPolicy()

    private fun strategy() = CryptoTailStrategy(
        mode = TradingMode.TAIL_DIFF,
        tailDiffBaseAmount = BigDecimal.ONE,
        tailDiffTierNormalMult = BigDecimal("1.0"),
        tailDiffTierPremiumMult = BigDecimal("1.5"),
        tailDiffTierTopMult = BigDecimal("2.0"),
        tailDiffMaxAmountPerOrder = BigDecimal("5")
    )

    @Test
    fun `default tier sizing unchanged when caps disabled (zero regression)`() {
        // 默认 enableKellyCap=false, depthFillRatio=0：即使传入 modelProb/depth 也不缩量
        val r = policy.computeAmount(
            strategy = strategy(),
            tier = TailDiffTier.TOP,
            spendableBalance = BigDecimal("100"),
            modelProb = BigDecimal("0.97"),
            effectiveCost = BigDecimal("0.92"),
            availableDepthUsd = BigDecimal("1")
        )
        assertEquals(0, r.amountUsdc.compareTo(BigDecimal("2")), "base 1 × top 2.0 = 2")
        assertFalse(r.cappedByKelly)
        assertFalse(r.cappedByDepth)
        assertEquals(null, r.clampReason)
    }

    @Test
    fun `kelly cap binds when enabled`() {
        val s = strategy().copy(
            tailDiffBaseAmount = BigDecimal("5"),
            tailDiffMaxAmountPerOrder = BigDecimal("100"),
            tailDiffEnableKellyCap = true,
            tailDiffKellyFraction = BigDecimal("0.10")
        )
        // raw = 5 × 2.0 = 10；kellyFull=(0.97-0.92)/(1-0.92)=0.625；cap=0.625×0.1×100=6.25 → 绑定
        val r = policy.computeAmount(
            strategy = s,
            tier = TailDiffTier.TOP,
            spendableBalance = BigDecimal("100"),
            modelProb = BigDecimal("0.97"),
            effectiveCost = BigDecimal("0.92"),
            availableDepthUsd = BigDecimal("1000")
        )
        assertEquals(0, r.amountUsdc.compareTo(BigDecimal("6.25")))
        assertTrue(r.cappedByKelly)
        assertEquals("KELLY", r.clampReason)
    }

    @Test
    fun `depth cap binds when ratio set`() {
        val s = strategy().copy(
            tailDiffBaseAmount = BigDecimal("5"),
            tailDiffMaxAmountPerOrder = BigDecimal("100"),
            tailDiffDepthFillRatio = BigDecimal("0.5")
        )
        // raw = 10；depthCap = 12 × 0.5 = 6 → 绑定
        val r = policy.computeAmount(
            strategy = s,
            tier = TailDiffTier.TOP,
            spendableBalance = BigDecimal("100"),
            availableDepthUsd = BigDecimal("12")
        )
        assertEquals(0, r.amountUsdc.compareTo(BigDecimal("6")))
        assertTrue(r.cappedByDepth)
        assertEquals("DEPTH", r.clampReason)
    }

    @Test
    fun `balance clamp still applies after caps`() {
        val r = policy.computeAmount(
            strategy = strategy(),
            tier = TailDiffTier.TOP,
            spendableBalance = BigDecimal("1.5")
        )
        // raw=2，余额 1.5 → 钳到 1.5
        assertEquals(0, r.amountUsdc.compareTo(BigDecimal("1.5")))
        assertTrue(r.cappedByBalance)
    }
}
