package com.wrbug.polymarketbot.service.cryptotail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * V96 盘口不稳定追踪器单测：
 *  - 异常分类：ASK_JUMP（达记录地板才入环）/ ASK_VANISH（ask 消失 bid 仍在）/ SPREAD_WIDE（含防抖）；
 *  - 查询口径：lookback 窗口过滤、minMagnitude 阈值过滤、ASK_VANISH 恒命中；
 *  - 基线断裂：观察间隔超 MAX_OBS_GAP_MS 只重置基线不判跳变。
 */
class CryptoTailBookInstabilityTrackerTest {

    private fun snapshot(
        tokenId: String = "tok",
        bid: String = "0.95",
        ask: String? = "0.97",
        spread: String? = null,
        atMs: Long
    ) = OrderbookQualitySnapshot(
        tokenId = tokenId,
        bestBid = BigDecimal(bid),
        bestAsk = ask?.let { BigDecimal(it) },
        bidSize = null,
        askSize = null,
        bidDepthUsd = null,
        askDepthUsd = null,
        spread = spread?.let { BigDecimal(it) },
        quoteUpdatedAtMs = atMs,
        depthUpdatedAtMs = null
    )

    @Test
    fun `ask jump at or above record floor is recorded and queryable`() {
        val tracker = CryptoTailBookInstabilityTracker()
        tracker.observe(snapshot(ask = "0.97", atMs = 1_000L), nowMs = 1_000L)
        tracker.observe(snapshot(ask = "0.72", atMs = 1_200L), nowMs = 1_200L)
        val event = tracker.recentAnomaly("tok", lookbackSec = 60, minMagnitude = BigDecimal("0.20"), nowMs = 1_500L)
        assertNotNull(event)
        assertEquals(CryptoTailBookInstabilityTracker.AnomalyType.ASK_JUMP, event!!.type)
        assertEquals(0, BigDecimal("0.25").compareTo(event.magnitude))
    }

    @Test
    fun `ask jump below record floor is not recorded`() {
        val tracker = CryptoTailBookInstabilityTracker()
        tracker.observe(snapshot(ask = "0.97", atMs = 1_000L), nowMs = 1_000L)
        tracker.observe(snapshot(ask = "0.92", atMs = 1_200L), nowMs = 1_200L)
        assertNull(tracker.recentAnomaly("tok", lookbackSec = 60, minMagnitude = BigDecimal.ZERO, nowMs = 1_500L))
    }

    @Test
    fun `min magnitude filters recorded but sub-threshold jumps`() {
        val tracker = CryptoTailBookInstabilityTracker()
        tracker.observe(snapshot(ask = "0.97", atMs = 1_000L), nowMs = 1_000L)
        tracker.observe(snapshot(ask = "0.82", atMs = 1_200L), nowMs = 1_200L) // Δ=0.15 >= 记录地板 0.10
        // 策略阈值 0.30：0.15 的跳变不命中
        assertNull(tracker.recentAnomaly("tok", lookbackSec = 60, minMagnitude = BigDecimal("0.30"), nowMs = 1_500L))
        // 阈值 0（不过滤量级）：命中
        assertNotNull(tracker.recentAnomaly("tok", lookbackSec = 60, minMagnitude = BigDecimal.ZERO, nowMs = 1_500L))
    }

    @Test
    fun `ask vanish always hits regardless of min magnitude`() {
        val tracker = CryptoTailBookInstabilityTracker()
        tracker.observe(snapshot(ask = "0.03", atMs = 1_000L), nowMs = 1_000L)
        tracker.observe(snapshot(ask = null, atMs = 1_300L), nowMs = 1_300L)
        val event = tracker.recentAnomaly("tok", lookbackSec = 60, minMagnitude = BigDecimal("0.99"), nowMs = 1_500L)
        assertNotNull(event)
        assertEquals(CryptoTailBookInstabilityTracker.AnomalyType.ASK_VANISH, event!!.type)
    }

    @Test
    fun `spread wide is recorded and deduped within dedup window`() {
        val tracker = CryptoTailBookInstabilityTracker()
        tracker.observe(snapshot(ask = "0.97", spread = "0.40", atMs = 1_000L), nowMs = 1_000L)
        // 同态爆宽 1 秒后再喂：在防抖窗内不重复入环
        tracker.observe(snapshot(ask = "0.97", spread = "0.40", atMs = 2_000L), nowMs = 2_000L)
        val first = tracker.recentAnomaly("tok", lookbackSec = 60, minMagnitude = BigDecimal("0.30"), nowMs = 2_100L)
        assertNotNull(first)
        assertEquals(CryptoTailBookInstabilityTracker.AnomalyType.SPREAD_WIDE, first!!.type)
        assertEquals(1_000L, first.atMs)
        // 超过防抖窗后再喂：记录新事件
        tracker.observe(snapshot(ask = "0.97", spread = "0.40", atMs = 6_000L), nowMs = 6_000L)
        val second = tracker.recentAnomaly("tok", lookbackSec = 60, minMagnitude = BigDecimal("0.30"), nowMs = 6_100L)
        assertEquals(6_000L, second!!.atMs)
    }

    @Test
    fun `events outside lookback window are not returned`() {
        val tracker = CryptoTailBookInstabilityTracker()
        tracker.observe(snapshot(ask = "0.97", atMs = 1_000L), nowMs = 1_000L)
        tracker.observe(snapshot(ask = "0.50", atMs = 1_200L), nowMs = 1_200L)
        assertNotNull(tracker.recentAnomaly("tok", lookbackSec = 10, minMagnitude = BigDecimal.ZERO, nowMs = 5_000L))
        assertNull(tracker.recentAnomaly("tok", lookbackSec = 10, minMagnitude = BigDecimal.ZERO, nowMs = 12_000L))
    }

    @Test
    fun `ask jump within same millisecond is detected`() {
        // 闪针时连续两条 WS 消息可落在同一毫秒（obsGapMs=0），不得漏检
        val tracker = CryptoTailBookInstabilityTracker()
        tracker.observe(snapshot(ask = "0.97", atMs = 1_000L), nowMs = 1_000L)
        tracker.observe(snapshot(ask = "0.60", atMs = 1_000L), nowMs = 1_000L)
        val event = tracker.recentAnomaly("tok", lookbackSec = 60, minMagnitude = BigDecimal("0.30"), nowMs = 1_100L)
        assertNotNull(event)
        assertEquals(CryptoTailBookInstabilityTracker.AnomalyType.ASK_JUMP, event!!.type)
        assertEquals(0, BigDecimal("0.37").compareTo(event.magnitude))
    }

    @Test
    fun `observation gap beyond max resets baseline without classifying jump`() {
        val tracker = CryptoTailBookInstabilityTracker()
        tracker.observe(snapshot(ask = "0.97", atMs = 1_000L), nowMs = 1_000L)
        // 断流 15s 后 ask 大变：基线断裂，不判 ASK_JUMP
        tracker.observe(snapshot(ask = "0.40", atMs = 16_000L), nowMs = 16_000L)
        assertNull(tracker.recentAnomaly("tok", lookbackSec = 60, minMagnitude = BigDecimal.ZERO, nowMs = 16_100L))
    }

    @Test
    fun `lookbackSec zero disables querying`() {
        val tracker = CryptoTailBookInstabilityTracker()
        tracker.observe(snapshot(ask = "0.97", atMs = 1_000L), nowMs = 1_000L)
        tracker.observe(snapshot(ask = "0.50", atMs = 1_200L), nowMs = 1_200L)
        assertNull(tracker.recentAnomaly("tok", lookbackSec = 0, minMagnitude = BigDecimal.ZERO, nowMs = 1_500L))
    }

    @Test
    fun `tokens are tracked independently`() {
        val tracker = CryptoTailBookInstabilityTracker()
        tracker.observe(snapshot(tokenId = "a", ask = "0.97", atMs = 1_000L), nowMs = 1_000L)
        tracker.observe(snapshot(tokenId = "a", ask = "0.50", atMs = 1_200L), nowMs = 1_200L)
        assertNotNull(tracker.recentAnomaly("a", lookbackSec = 60, minMagnitude = BigDecimal.ZERO, nowMs = 1_500L))
        assertNull(tracker.recentAnomaly("b", lookbackSec = 60, minMagnitude = BigDecimal.ZERO, nowMs = 1_500L))
    }
}
