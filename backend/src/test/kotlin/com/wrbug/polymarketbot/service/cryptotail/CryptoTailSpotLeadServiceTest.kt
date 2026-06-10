package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.service.cryptotail.CryptoTailSpotLeadService.SpotLeadState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal

/**
 * 现货领先早警核心纯逻辑单测：
 *  - [SpotLeadState.danger] 危险判定边界（穿价/近翻转/新鲜度）；
 *  - [CryptoTailSpotLeadService.mergeConsensus] 双源融合矩阵（共识/降级/全陈旧）。
 */
class CryptoTailSpotLeadServiceTest {

    private fun service(): CryptoTailSpotLeadService = CryptoTailSpotLeadService(
        Mockito.mock(SpotLeadPriceProviderRouter::class.java),
        Mockito.mock(PeriodPriceProvider::class.java)
    )

    private fun state(
        fresh: Boolean = true,
        supportsHolding: Boolean = true,
        distance: BigDecimal? = null,
        spotGap: BigDecimal = BigDecimal.ONE,
        exchange: String? = null
    ): SpotLeadState = SpotLeadState(
        fresh = fresh,
        spotGap = spotGap,
        supportsHolding = supportsHolding,
        distanceToFlipSigma = distance,
        ageMs = 100L,
        source = CryptoTailSpotLeadService.SpotPriceSource.TICK,
        exchange = exchange
    )

    // ===================== danger() 边界 =====================

    @Test
    fun `danger is false when not fresh even if crossed`() {
        assertFalse(state(fresh = false, supportsHolding = false).danger(BigDecimal.ZERO))
    }

    @Test
    fun `danger is true when fresh and crossed`() {
        assertTrue(state(fresh = true, supportsHolding = false).danger(BigDecimal.ZERO))
    }

    @Test
    fun `near-flip disabled when flipDistanceSigma is zero`() {
        // 仍支持持仓方向 + 阈值=0 → 仅认实际穿价，不触发近翻转
        assertFalse(state(supportsHolding = true, distance = BigDecimal("0.1")).danger(BigDecimal.ZERO))
    }

    @Test
    fun `near-flip triggers when distance within threshold`() {
        assertTrue(state(supportsHolding = true, distance = BigDecimal("0.5")).danger(BigDecimal("1.0")))
        // 边界相等也算危险（<=）
        assertTrue(state(supportsHolding = true, distance = BigDecimal("1.0")).danger(BigDecimal("1.0")))
    }

    @Test
    fun `near-flip does not trigger when distance beyond threshold`() {
        assertFalse(state(supportsHolding = true, distance = BigDecimal("2.0")).danger(BigDecimal("1.0")))
    }

    @Test
    fun `near-flip does not trigger when distance is null`() {
        assertFalse(state(supportsHolding = true, distance = null).danger(BigDecimal("1.0")))
    }

    // ===================== mergeConsensus 矩阵 =====================

    @Test
    fun `merge both null returns null`() {
        assertNull(service().mergeConsensus(null, null))
    }

    @Test
    fun `merge degrades to okx when binance missing`() {
        val merged = service().mergeConsensus(null, state())
        assertEquals("CONSENSUS_DEGRADED_OKX", merged?.exchange)
    }

    @Test
    fun `merge degrades to binance when okx missing`() {
        val merged = service().mergeConsensus(state(), null)
        assertEquals("CONSENSUS_DEGRADED_BINANCE", merged?.exchange)
    }

    @Test
    fun `merge both fresh and both crossed is danger consensus`() {
        val a = state(fresh = true, supportsHolding = false) // 币安穿价
        val b = state(fresh = true, supportsHolding = false) // OKX 穿价
        val merged = service().mergeConsensus(a, b)
        assertEquals("CONSENSUS", merged?.exchange)
        assertTrue(merged!!.fresh)
        // 双源皆穿 → supportsHolding=false → crossed=true → danger
        assertTrue(merged.crossed)
        assertTrue(merged.danger(BigDecimal.ZERO))
    }

    @Test
    fun `merge both fresh but only one crossed is not danger`() {
        val a = state(fresh = true, supportsHolding = false) // 币安穿价
        val b = state(fresh = true, supportsHolding = true)  // OKX 仍支持
        val merged = service().mergeConsensus(a, b)
        assertEquals("CONSENSUS", merged?.exchange)
        // 仅单源穿价 → 共识不判穿 → 不危险
        assertFalse(merged!!.crossed)
        assertFalse(merged.danger(BigDecimal.ZERO))
    }

    @Test
    fun `merge degrades to fresh side when other is stale`() {
        val a = state(fresh = true)
        val b = state(fresh = false)
        assertEquals("CONSENSUS_DEGRADED_BINANCE", service().mergeConsensus(a, b)?.exchange)
        assertEquals("CONSENSUS_DEGRADED_OKX", service().mergeConsensus(b, a)?.exchange)
    }

    @Test
    fun `merge both stale yields non-fresh consensus stale`() {
        val a = state(fresh = false)
        val b = state(fresh = false)
        val merged = service().mergeConsensus(a, b)
        assertEquals("CONSENSUS_STALE", merged?.exchange)
        assertFalse(merged!!.fresh)
    }
}
