package com.wrbug.polymarketbot.service.cryptotail

import java.math.BigDecimal

data class OrderbookQualitySnapshot(
    val tokenId: String,
    val bestBid: BigDecimal,
    val bestAsk: BigDecimal?,
    val bidSize: BigDecimal?,
    val askSize: BigDecimal?,
    val bidDepthUsd: BigDecimal?,
    val askDepthUsd: BigDecimal?,
    val spread: BigDecimal?,
    val quoteUpdatedAtMs: Long,
    val depthUpdatedAtMs: Long?,
    val depthStale: Boolean = false,
    val bidLevels: List<BookLevel> = emptyList()
) {
    data class BookLevel(
        val price: BigDecimal,
        val size: BigDecimal
    )

    fun quoteAgeMs(nowMs: Long = System.currentTimeMillis()): Long =
        (nowMs - quoteUpdatedAtMs).coerceAtLeast(0L)

    fun depthAgeMs(nowMs: Long = System.currentTimeMillis()): Long? =
        depthUpdatedAtMs?.let { (nowMs - it).coerceAtLeast(0L) }

    fun executableBidDepthUsd(targetSize: BigDecimal): BigDecimal {
        if (targetSize <= BigDecimal.ZERO) return BigDecimal.ZERO
        var remaining = targetSize
        var total = BigDecimal.ZERO
        for (level in bidLevels.sortedByDescending { it.price }) {
            if (remaining <= BigDecimal.ZERO) break
            val fillSize = remaining.min(level.size)
            total = total.add(fillSize.multiply(level.price))
            remaining = remaining.subtract(fillSize)
        }
        return total
    }

    fun expectedExitPrice(targetSize: BigDecimal): BigDecimal? {
        if (targetSize <= BigDecimal.ZERO) return null
        val depthUsd = executableBidDepthUsd(targetSize)
        return if (depthUsd > BigDecimal.ZERO) {
            depthUsd.divide(targetSize, 8, java.math.RoundingMode.HALF_UP)
        } else null
    }

    fun toPayload(nowMs: Long = System.currentTimeMillis()): Map<String, Any> = mapOf(
        "bestBid" to bestBid.toPlainString(),
        "bestAsk" to (bestAsk?.toPlainString() ?: ""),
        "bidSize" to (bidSize?.toPlainString() ?: ""),
        "askSize" to (askSize?.toPlainString() ?: ""),
        "bidDepthUsd" to (bidDepthUsd?.toPlainString() ?: ""),
        "askDepthUsd" to (askDepthUsd?.toPlainString() ?: ""),
        "spread" to (spread?.toPlainString() ?: ""),
        "quoteAgeMs" to quoteAgeMs(nowMs),
        "depthAgeMs" to (depthAgeMs(nowMs) ?: ""),
        "depthStale" to depthStale,
        "bidLevelCount" to bidLevels.size
    )
}
