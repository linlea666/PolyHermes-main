package com.wrbug.polymarketbot.service.cryptotail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class OrderbookQualitySnapshotTest {

    @Test
    fun `computes executable bid size at FAK limit`() {
        val snapshot = snapshot(
            bids = listOf(
                "0.78" to "3.00",
                "0.77" to "4.00",
                "0.75" to "10.00"
            )
        )

        assertEquals(
            "7.00",
            snapshot.executableBidSizeAtBestOrBetter(BigDecimal("0.77"), BigDecimal("12.00")).toPlainString()
        )
    }

    @Test
    fun `computes executable ask size at safe limit`() {
        val snapshot = snapshot(
            asks = listOf(
                "0.76" to "2.50",
                "0.77" to "1.50",
                "0.79" to "5.00"
            )
        )

        assertEquals(
            "4.00",
            snapshot.executableAskSizeAtOrBelow(BigDecimal("0.77"), BigDecimal("10.00")).toPlainString()
        )
    }

    @Test
    fun `expected exit price reflects only available bid depth`() {
        val snapshot = snapshot(
            bids = listOf(
                "0.80" to "2.00",
                "0.75" to "2.00"
            )
        )

        assertEquals("3.1000", snapshot.executableBidDepthUsd(BigDecimal("4.00")).toPlainString())
        assertEquals("0.77500000", snapshot.expectedExitPrice(BigDecimal("4.00"))?.toPlainString())
    }

    private fun snapshot(
        bids: List<Pair<String, String>> = emptyList(),
        asks: List<Pair<String, String>> = emptyList()
    ): OrderbookQualitySnapshot {
        return OrderbookQualitySnapshot(
            tokenId = "token",
            bestBid = BigDecimal("0.78"),
            bestAsk = BigDecimal("0.79"),
            bidSize = null,
            askSize = null,
            bidDepthUsd = null,
            askDepthUsd = null,
            spread = BigDecimal("0.01"),
            quoteUpdatedAtMs = 1000,
            depthUpdatedAtMs = 1000,
            bidLevels = bids.map { OrderbookQualitySnapshot.BookLevel(BigDecimal(it.first), BigDecimal(it.second)) },
            askLevels = asks.map { OrderbookQualitySnapshot.BookLevel(BigDecimal(it.first), BigDecimal(it.second)) }
        )
    }
}
