package com.wrbug.polymarketbot.service.cryptotail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 现货领先早警遥测（A6）单测：验证按交易所归属的首穿时间戳/每源 leadMs/早警动作标记。
 */
class CryptoTailSpotLeadTelemetryTest {

    private val sid = 1L
    private val period = 1_700_000_000L
    private val oi = 0

    @Test
    fun `binance lead over chainlink computes positive leadMs and okx stays null`() {
        val t = CryptoTailSpotLeadTelemetry()
        // 币安先穿(t0)，Chainlink 后穿(t0+120ms)
        t.observe(sid, period, oi, 1_000L, binCrossed = true, okxCrossed = false, clCrossed = false)
        t.observe(sid, period, oi, 1_120L, binCrossed = true, okxCrossed = false, clCrossed = true)

        val snap = t.snapshot(sid, period, oi)
        assertEquals(1_000L, snap.binFirstCrossTs)
        assertEquals(1_120L, snap.clFirstCrossTs)
        assertEquals(120L, snap.leadMs)
        assertNull(snap.okxFirstCrossTs)
        assertNull(snap.okxLeadMs)
    }

    @Test
    fun `okx lead computes okxLeadMs independently`() {
        val t = CryptoTailSpotLeadTelemetry()
        t.observe(sid, period, oi, 2_000L, binCrossed = false, okxCrossed = true, clCrossed = false)
        t.observe(sid, period, oi, 2_080L, binCrossed = false, okxCrossed = false, clCrossed = true)

        val snap = t.snapshot(sid, period, oi)
        assertEquals(2_000L, snap.okxFirstCrossTs)
        assertEquals(80L, snap.okxLeadMs)
        assertNull(snap.binFirstCrossTs)
        assertNull(snap.leadMs)
    }

    @Test
    fun `first cross timestamp is sticky and not overwritten by later observations`() {
        val t = CryptoTailSpotLeadTelemetry()
        t.observe(sid, period, oi, 5_000L, binCrossed = true, okxCrossed = false, clCrossed = false)
        // 后续再次穿价不应覆盖首穿时间
        t.observe(sid, period, oi, 5_500L, binCrossed = true, okxCrossed = false, clCrossed = false)

        assertEquals(5_000L, t.snapshot(sid, period, oi).binFirstCrossTs)
    }

    @Test
    fun `no cross yields empty snapshot with null leads`() {
        val t = CryptoTailSpotLeadTelemetry()
        // 全 false 不写入任何记录
        t.observe(sid, period, oi, 9_000L, binCrossed = false, okxCrossed = false, clCrossed = false)

        val snap = t.snapshot(sid, period, oi)
        assertNull(snap.binFirstCrossTs)
        assertNull(snap.okxFirstCrossTs)
        assertNull(snap.clFirstCrossTs)
        assertNull(snap.leadMs)
        assertNull(snap.okxLeadMs)
        assertFalse(snap.earlyWarningActed)
    }

    @Test
    fun `early warning acted flag is sticky`() {
        val t = CryptoTailSpotLeadTelemetry()
        t.observe(sid, period, oi, 1_000L, binCrossed = true, okxCrossed = false, clCrossed = false)
        t.markEarlyWarningActed(sid, period, oi)

        assertTrue(t.snapshot(sid, period, oi).earlyWarningActed)
    }

    @Test
    fun `lead is null when chainlink crossed but spot never did`() {
        val t = CryptoTailSpotLeadTelemetry()
        t.observe(sid, period, oi, 1_000L, binCrossed = false, okxCrossed = false, clCrossed = true)

        val snap = t.snapshot(sid, period, oi)
        assertEquals(1_000L, snap.clFirstCrossTs)
        assertNull(snap.leadMs)
        assertNull(snap.okxLeadMs)
    }
}
