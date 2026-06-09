package com.wrbug.polymarketbot.service.cryptotail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CryptoTailOrderbookCacheTest {

    private val token = "tk"

    private fun bid(price: String, size: String) = OrderbookQualitySnapshot.BookLevel(BigDecimal(price), BigDecimal(size))
    private fun ask(price: String, size: String) = OrderbookQualitySnapshot.BookLevel(BigDecimal(price), BigDecimal(size))
    private fun change(price: String, size: String, isBid: Boolean) =
        CryptoTailOrderbookCache.LevelChange(BigDecimal(price), BigDecimal(size), isBid)

    @Test
    fun `book seeds full ladder with best quotes and depth`() {
        val cache = CryptoTailOrderbookCache()
        val result = cache.applyBook(
            token,
            bidLevels = listOf(bid("0.50", "100"), bid("0.48", "200")),
            askLevels = listOf(ask("0.55", "100"), ask("0.57", "50")),
            nowMs = 1000
        )
        assertTrue(result.seeded)
        assertEquals(0, result.prunedLevels)
        val snap = result.snapshot
        assertNotNull(snap)
        assertEquals("0.50", snap!!.bestBid.toPlainString())
        assertEquals("0.55", snap.bestAsk!!.toPlainString())
        // 深度 = Σ price*size：bid 0.50*100+0.48*200=146；ask 0.55*100+0.57*50=83.5
        assertEquals("146.00", snap.bidDepthUsd!!.toPlainString())
        assertEquals("83.50", snap.askDepthUsd!!.toPlainString())
        assertTrue(!snap.depthStale)
    }

    @Test
    fun `price_change before book is not seeded (cold start falls back to REST)`() {
        val cache = CryptoTailOrderbookCache()
        val result = cache.applyPriceChange(
            token,
            changes = listOf(change("0.55", "100", isBid = false)),
            bestBidHint = BigDecimal("0.50"),
            bestAskHint = BigDecimal("0.55"),
            nowMs = 1000
        )
        assertNull(result.snapshot)
        assertTrue(!result.seeded)
        assertNull(cache.latestSnapshot(token))
    }

    @Test
    fun `price_change sets new level and size zero removes it`() {
        val cache = CryptoTailOrderbookCache()
        cache.applyBook(token, listOf(bid("0.50", "100")), listOf(ask("0.55", "100")), 1000)

        // 新增更优卖档 0.54，best_ask hint 同步为 0.54
        val s1 = cache.applyPriceChange(
            token,
            listOf(change("0.54", "80", isBid = false)),
            bestBidHint = BigDecimal("0.50"),
            bestAskHint = BigDecimal("0.54"),
            nowMs = 1010
        )
        assertEquals("0.54", s1.snapshot!!.bestAsk!!.toPlainString())

        // 撤掉 0.54（size=0），best_ask hint 回到 0.55
        val s2 = cache.applyPriceChange(
            token,
            listOf(change("0.54", "0", isBid = false)),
            bestBidHint = BigDecimal("0.50"),
            bestAskHint = BigDecimal("0.55"),
            nowMs = 1020
        )
        assertEquals("0.55", s2.snapshot!!.bestAsk!!.toPlainString())
    }

    @Test
    fun `best_ask hint self-heals stale low ask from missed message`() {
        val cache = CryptoTailOrderbookCache()
        // 初始卖档含 0.55；模拟漏掉了"0.55 被吃掉"的消息，本地仍残留 0.55
        cache.applyBook(token, listOf(bid("0.50", "100")), listOf(ask("0.55", "100"), ask("0.58", "100")), 1000)

        // 下一条 price_change 只改了买价，但权威 best_ask=0.58（说明 0.55 已不在盘口）
        val result = cache.applyPriceChange(
            token,
            listOf(change("0.50", "120", isBid = true)),
            bestBidHint = BigDecimal("0.50"),
            bestAskHint = BigDecimal("0.58"),
            nowMs = 1010
        )
        // 自愈：剪掉 < 0.58 的陈旧卖档 0.55，bestAsk 修正为 0.58（杜绝偏低 ask 致 FAK 限价过低）
        assertEquals("0.58", result.snapshot!!.bestAsk!!.toPlainString())
        // 诊断：自愈确实剪掉了 1 档（漏包信号），供 SELF_HEAL WARN 使用
        assertEquals(1, result.prunedLevels)
    }

    @Test
    fun `book re-seed replaces prior ladder`() {
        val cache = CryptoTailOrderbookCache()
        cache.applyBook(token, listOf(bid("0.50", "100")), listOf(ask("0.55", "100")), 1000)
        val snap = cache.applyBook(token, listOf(bid("0.60", "10")), listOf(ask("0.62", "10")), 2000).snapshot
        assertEquals("0.60", snap!!.bestBid.toPlainString())
        assertEquals("0.62", snap.bestAsk!!.toPlainString())
    }

    @Test
    fun `ask freshness tracks quote when ask hint present`() {
        val cache = CryptoTailOrderbookCache()
        cache.applyBook(token, listOf(bid("0.50", "100")), listOf(ask("0.55", "100")), 1000)
        // 仅买价变更，但带 best_ask hint → 卖档顶部经校验，askUpdatedAtMs 应随报价刷新
        cache.applyPriceChange(
            token,
            listOf(change("0.50", "120", isBid = true)),
            bestBidHint = BigDecimal("0.50"),
            bestAskHint = BigDecimal("0.55"),
            nowMs = 5000
        )
        val snap = cache.latestSnapshot(token)!!
        assertEquals(0L, snap.askAgeMs(5000))
        assertEquals(0L, snap.quoteAgeMs(5000))
    }

    @Test
    fun `normal price_change does not report pruned levels`() {
        val cache = CryptoTailOrderbookCache()
        cache.applyBook(token, listOf(bid("0.50", "100")), listOf(ask("0.55", "100")), 1000)
        val result = cache.applyPriceChange(
            token,
            listOf(change("0.50", "120", isBid = true)),
            bestBidHint = BigDecimal("0.50"),
            bestAskHint = BigDecimal("0.55"),
            nowMs = 1010
        )
        assertTrue(result.seeded)
        assertEquals(0, result.prunedLevels)
    }
}
