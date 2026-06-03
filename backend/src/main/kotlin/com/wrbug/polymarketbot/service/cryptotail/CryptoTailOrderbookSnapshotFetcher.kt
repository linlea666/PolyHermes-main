package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.api.PolymarketClobApi
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal

/**
 * Shared CLOB /book parser for crypto-tail entry refresh, retry refresh and exit polling.
 */
@Service
class CryptoTailOrderbookSnapshotFetcher {

    private val logger = LoggerFactory.getLogger(CryptoTailOrderbookSnapshotFetcher::class.java)

    suspend fun fetch(clobApi: PolymarketClobApi, tokenId: String): OrderbookQualitySnapshot? {
        return try {
            val resp = clobApi.getOrderbook(tokenId = tokenId)
            if (!resp.isSuccessful) return null
            val body = resp.body() ?: return null
            val bidLevels = body.bids.mapNotNull { entry ->
                val price = entry.price.toSafeBigDecimal()
                val size = entry.size.toSafeBigDecimal()
                if (price > BigDecimal.ZERO && size > BigDecimal.ZERO) {
                    OrderbookQualitySnapshot.BookLevel(price, size)
                } else {
                    null
                }
            }.sortedByDescending { it.price }
            if (bidLevels.isEmpty()) return null
            val askLevels = body.asks.mapNotNull { entry ->
                val price = entry.price.toSafeBigDecimal()
                val size = entry.size.toSafeBigDecimal()
                if (price > BigDecimal.ZERO && size > BigDecimal.ZERO) {
                    OrderbookQualitySnapshot.BookLevel(price, size)
                } else {
                    null
                }
            }.sortedBy { it.price }
            val bestBid = bidLevels.first()
            val bestAsk = askLevels.firstOrNull()
            val nowMs = System.currentTimeMillis()
            OrderbookQualitySnapshot(
                tokenId = tokenId,
                bestBid = bestBid.price,
                bestAsk = bestAsk?.price,
                bidSize = bestBid.size,
                askSize = bestAsk?.size,
                bidDepthUsd = bidLevels.fold(BigDecimal.ZERO) { acc, level -> acc.add(level.price.multiply(level.size)) },
                askDepthUsd = askLevels.fold(BigDecimal.ZERO) { acc, level -> acc.add(level.price.multiply(level.size)) },
                spread = bestAsk?.price?.subtract(bestBid.price),
                quoteUpdatedAtMs = nowMs,
                depthUpdatedAtMs = nowMs,
                depthStale = false,
                bidLevels = bidLevels,
                askLevels = askLevels
            )
        } catch (e: Exception) {
            logger.warn("查询订单簿失败: tokenId=$tokenId, ${e.message}")
            null
        }
    }
}
