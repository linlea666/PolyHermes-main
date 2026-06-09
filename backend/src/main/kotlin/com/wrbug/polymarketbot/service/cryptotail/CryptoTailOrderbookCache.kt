package com.wrbug.polymarketbot.service.cryptotail

import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap

/**
 * 加密价差策略本地 L2 盘口（tokenId -> 完整买卖档位 + 派生最新快照）。
 *
 * 从 [CryptoTailOrderbookWsService] 提取为独立组件：WS 服务收到 book/price_change 时调用 [applyBook]/[applyPriceChange]
 * 维护完整本地盘口，执行服务通过 [latestSnapshot] 读取"当前最新 WS 帧"（含完整深度，WS 主 / REST 兜底）。
 * 提取而非互相注入，是为避免执行服务与 WS 服务循环依赖（WS 服务已注入执行服务用于触发下单）。
 *
 * 重建语义（Polymarket market 频道）：
 *  - `book` 为全量快照，到达即清空重填（天然 resync 点）。
 *  - `price_change` 的 size 为该价位**新聚合量**（绝对量置位，非增量），size=0 表示该价位移除。
 *  - 每条 price_change 带权威 best_bid/best_ask，用于自愈顶档：漏包导致我方顶端残留陈旧档时按 hint 剪除，
 *    确保 bestAsk 不会偏低、bestBid 不会偏高（直接消除"偏低 ask 致 FAK 限价过低被 kill"的根因）。
 */
@Component
class CryptoTailOrderbookCache {

    /** 单档价位变更（来自 price_change）：isBid=true 为买档(BUY)，false 为卖档(SELL)；size<=0 表示该价位移除 */
    data class LevelChange(val price: BigDecimal, val size: BigDecimal, val isBid: Boolean)

    /**
     * 应用结果（供 WS 诊断）：
     *  - [snapshot]：派生的最新快照，null 表示无可用快照（冷启动未播种 / 空买档）。
     *  - [prunedLevels]：本次用 best_bid/ask hint 自愈剪掉的陈旧档数；>0 即漏包信号。
     *  - [seeded]：本地盘口是否已被 book 播种（false=冷启动，price_change 暂无法重建）。
     */
    data class ApplyResult(
        val snapshot: OrderbookQualitySnapshot?,
        val prunedLevels: Int = 0,
        val seeded: Boolean = true
    )

    private val books = ConcurrentHashMap<String, BookState>()

    /** book 全量快照到达：清空并按快照重填。bids 为空 snapshot=null（与旧行为一致，不发布无买价快照） */
    fun applyBook(
        tokenId: String,
        bidLevels: List<OrderbookQualitySnapshot.BookLevel>,
        askLevels: List<OrderbookQualitySnapshot.BookLevel>,
        nowMs: Long = System.currentTimeMillis()
    ): ApplyResult {
        val state = books.getOrPut(tokenId) { BookState(tokenId) }
        return state.applyBook(bidLevels, askLevels, nowMs)
    }

    /** price_change 增量到达：逐档置位/删档 + 按 best_bid/ask hint 自愈顶档；未播种(无 book) seeded=false（保持现状走 REST 兜底） */
    fun applyPriceChange(
        tokenId: String,
        changes: List<LevelChange>,
        bestBidHint: BigDecimal?,
        bestAskHint: BigDecimal?,
        nowMs: Long = System.currentTimeMillis()
    ): ApplyResult {
        val state = books[tokenId] ?: return ApplyResult(null, 0, seeded = false)
        return state.applyPriceChange(changes, bestBidHint, bestAskHint, nowMs)
    }

    /** 仅保留当前活跃 token 的本地盘口，淘汰已下线周期的 token，防止跨周期轮换内存无界增长 */
    fun retainTokens(activeTokenIds: Set<String>) {
        books.keys.retainAll(activeTokenIds)
    }

    fun get(tokenId: String): OrderbookQualitySnapshot? = books[tokenId]?.snapshot

    /** 取该 token 当前最新 WS 盘口快照（含完整深度，持续随 WS 消息更新）；无则 null */
    fun latestSnapshot(tokenId: String): OrderbookQualitySnapshot? = books[tokenId]?.snapshot

    /**
     * 单 token 的本地盘口状态。买档价降序、卖档价升序。所有变更方法 @Synchronized（WS 单连接回调串行写，
     * 重连期可能短暂双连接并发，故加锁兜底）；[snapshot] @Volatile 由执行线程无锁读取最近发布的不可变快照。
     */
    private class BookState(private val tokenId: String) {
        private val bids = TreeMap<BigDecimal, BigDecimal>(reverseOrder())
        private val asks = TreeMap<BigDecimal, BigDecimal>()
        private var lastBookAtMs = 0L
        private var quoteUpdatedAtMs = 0L
        private var depthUpdatedAtMs = 0L
        private var askUpdatedAtMs = 0L

        @Volatile
        var snapshot: OrderbookQualitySnapshot? = null
            private set

        @Synchronized
        fun applyBook(
            bidLevels: List<OrderbookQualitySnapshot.BookLevel>,
            askLevels: List<OrderbookQualitySnapshot.BookLevel>,
            nowMs: Long
        ): ApplyResult {
            bids.clear()
            asks.clear()
            for (l in bidLevels) if (l.size > BigDecimal.ZERO) bids[l.price] = l.size
            for (l in askLevels) if (l.size > BigDecimal.ZERO) asks[l.price] = l.size
            lastBookAtMs = nowMs
            quoteUpdatedAtMs = nowMs
            depthUpdatedAtMs = nowMs
            askUpdatedAtMs = nowMs
            return ApplyResult(publish(), prunedLevels = 0, seeded = true)
        }

        @Synchronized
        fun applyPriceChange(
            changes: List<LevelChange>,
            bestBidHint: BigDecimal?,
            bestAskHint: BigDecimal?,
            nowMs: Long
        ): ApplyResult {
            if (lastBookAtMs == 0L) return ApplyResult(null, 0, seeded = false)
            var askTouched = false
            for (c in changes) {
                val side = if (c.isBid) bids else asks
                if (c.size <= BigDecimal.ZERO) side.remove(c.price) else side[c.price] = c.size
                if (!c.isBid) askTouched = true
            }
            // 用权威 best_bid/ask 自愈顶档：剪掉我方顶端的陈旧残留档（漏包时偏低 ask / 偏高 bid）
            var pruned = 0
            if (bestBidHint != null && bestBidHint > BigDecimal.ZERO) {
                while (bids.isNotEmpty() && bids.firstKey() > bestBidHint) { bids.remove(bids.firstKey()); pruned++ }
            }
            if (bestAskHint != null && bestAskHint > BigDecimal.ZERO) {
                while (asks.isNotEmpty() && asks.firstKey() < bestAskHint) { asks.remove(asks.firstKey()); pruned++ }
            }
            quoteUpdatedAtMs = nowMs
            depthUpdatedAtMs = nowMs
            // 每条 price_change 都带 best_ask hint 校验过卖档顶部，故卖档新鲜度=报价新鲜度；
            // 缺 ask hint 时仅在本条确有卖档变更时才刷新，避免谎报新鲜。
            if ((bestAskHint != null && bestAskHint > BigDecimal.ZERO) || askTouched) askUpdatedAtMs = nowMs
            return ApplyResult(publish(), prunedLevels = pruned, seeded = true)
        }

        private fun publish(): OrderbookQualitySnapshot? {
            val bestBidEntry = bids.firstEntry()
            if (bestBidEntry == null) {
                snapshot = null
                return null
            }
            val bestAskEntry = asks.firstEntry()
            val bidLevels = bids.entries.map { OrderbookQualitySnapshot.BookLevel(it.key, it.value) }
            val askLevels = asks.entries.map { OrderbookQualitySnapshot.BookLevel(it.key, it.value) }
            val bidDepthUsd = bidLevels.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.price.multiply(l.size)) }
            val askDepthUsd = askLevels.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.price.multiply(l.size)) }
            val bestBid = bestBidEntry.key
            val bestAsk = bestAskEntry?.key
            val snap = OrderbookQualitySnapshot(
                tokenId = tokenId,
                bestBid = bestBid,
                bestAsk = bestAsk,
                bidSize = bestBidEntry.value,
                askSize = bestAskEntry?.value,
                bidDepthUsd = bidDepthUsd,
                askDepthUsd = askDepthUsd,
                spread = bestAsk?.subtract(bestBid),
                quoteUpdatedAtMs = quoteUpdatedAtMs,
                depthUpdatedAtMs = depthUpdatedAtMs,
                askUpdatedAtMs = askUpdatedAtMs,
                depthStale = false,
                bidLevels = bidLevels,
                askLevels = askLevels
            )
            snapshot = snap
            return snap
        }
    }
}
