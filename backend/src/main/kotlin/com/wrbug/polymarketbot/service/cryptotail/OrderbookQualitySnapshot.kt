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
    val depthStale: Boolean = false
) {
    fun quoteAgeMs(nowMs: Long = System.currentTimeMillis()): Long =
        (nowMs - quoteUpdatedAtMs).coerceAtLeast(0L)

    fun depthAgeMs(nowMs: Long = System.currentTimeMillis()): Long? =
        depthUpdatedAtMs?.let { (nowMs - it).coerceAtLeast(0L) }

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
        "depthStale" to depthStale
    )
}
