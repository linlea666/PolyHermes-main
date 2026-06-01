package com.wrbug.polymarketbot.service.copytrading.statistics

import com.wrbug.polymarketbot.entity.CopyOrderTracking
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.entity.SellMatchDetail
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CopyTradingRiskDiagnosisServiceTest {

    @Test
    fun `builds loss attribution with top losing markets and quote completeness`() {
        val buyOrders = listOf(
            buyOrder(id = 1, marketId = "market-a", quantity = "10", price = "0.60", remainingQuantity = "4"),
            buyOrder(id = 2, marketId = "market-b", quantity = "5", price = "0.20", remainingQuantity = "5")
        )
        val matchDetails = listOf(
            matchDetail(trackingId = 1, buyOrderId = "buy-1", sellPrice = "0", pnl = "-3.60"),
            matchDetail(trackingId = 2, buyOrderId = "buy-2", sellPrice = "0.50", pnl = "1.50")
        )
        val pnl = CopyTradingPnlCalculator.calculate(
            buyOrders = buyOrders,
            sellRecords = emptyList(),
            matchDetails = matchDetails,
            quotes = listOf(
                PositionValuationQuote(marketId = "market-a", outcomeIndex = 0, side = "0", currentPrice = BigDecimal.ZERO),
                PositionValuationQuote.unavailable("positions timeout")
            )
        )

        val diagnosis = CopyTradingRiskDiagnosisService.buildDiagnosis(
            copyTrading = riskyCopyTrading(),
            buyOrders = buyOrders,
            sellRecordsCount = 0,
            matchDetails = matchDetails,
            filteredOrderCount = 0,
            pnl = pnl,
            generatedAt = 1234
        )

        assertEquals("UNAVAILABLE", diagnosis.quoteOverallStatus)
        assertTrue(diagnosis.dataIncomplete)
        assertEquals(2, diagnosis.totalBuyOrders)
        assertEquals(2, diagnosis.sampleSize)
        assertEquals("3.40", diagnosis.zeroValuePositionCost)
        assertEquals("2.40", diagnosis.confirmedZeroValuePositionCost)
        assertEquals("3.60", diagnosis.zeroSellLoss)
        assertEquals("market-a", diagnosis.topLosingMarkets.first().marketId)
        assertEquals("-3.60", diagnosis.topLosingMarkets.first().realizedPnl)
        assertTrue(diagnosis.riskWarnings.any { it.field == "maxDailyOrders" && it.severity == RiskSeverity.HIGH.name })
        assertEquals(1234, diagnosis.generatedAt)
    }

    @Test
    fun `marks profitable tiny samples as low confidence`() {
        val buyOrders = listOf(
            buyOrder(id = 1, marketId = "market-a", quantity = "10", price = "0.40", remainingQuantity = "0"),
            buyOrder(id = 2, marketId = "market-b", quantity = "10", price = "0.40", remainingQuantity = "0")
        )
        val matchDetails = listOf(
            matchDetail(trackingId = 1, buyOrderId = "buy-1", sellPrice = "0.80", pnl = "4.00"),
            matchDetail(trackingId = 2, buyOrderId = "buy-2", sellPrice = "0.60", pnl = "2.00")
        )
        val pnl = CopyTradingPnlCalculator.calculate(
            buyOrders = buyOrders,
            sellRecords = emptyList(),
            matchDetails = matchDetails,
            quotes = emptyList()
        )

        val diagnosis = CopyTradingRiskDiagnosisService.buildDiagnosis(
            copyTrading = conservativeCopyTrading(),
            buyOrders = buyOrders,
            sellRecordsCount = 0,
            matchDetails = matchDetails,
            filteredOrderCount = 3,
            pnl = pnl,
            generatedAt = 1234
        )

        assertTrue(diagnosis.lowConfidence)
        assertTrue(diagnosis.confidenceReason.contains("样本"))
        assertEquals("6.00", diagnosis.totalPnl)
        assertEquals(3, diagnosis.filteredOrderCount)
        assertTrue(diagnosis.riskWarnings.all { it.severity != RiskSeverity.HIGH.name })
    }

    @Test
    fun `returns field level conservative suggestions for unsafe config`() {
        val warnings = CopyTradingRiskDiagnosisService.inspectRiskConfig(riskyCopyTrading())

        assertTrue(warnings.any { it.field == "maxDailyLoss" && it.currentValue == "10000" && it.suggestedValue == "10" })
        assertTrue(warnings.any { it.field == "minPrice" && it.currentValue == null && it.suggestedValue == "0.10" })
        assertTrue(warnings.any { it.field == "maxPrice" && it.currentValue == null && it.suggestedValue == "0.80" })
        assertTrue(warnings.any { it.field == "maxPositionValue" && it.currentValue == null && it.suggestedValue == "10" })
        assertTrue(warnings.any { it.field == "minOrderDepth" })
        assertTrue(warnings.any { it.field == "maxSpread" })
    }

    private fun riskyCopyTrading() = CopyTrading(
        id = 1,
        accountId = 1,
        leaderId = 1,
        fixedAmount = bd("1"),
        maxDailyLoss = bd("10000"),
        maxDailyOrders = 100,
        priceTolerance = bd("5"),
        minPrice = null,
        maxPrice = null,
        maxPositionValue = null,
        minOrderDepth = null,
        maxSpread = null
    )

    private fun conservativeCopyTrading() = CopyTrading(
        id = 1,
        accountId = 1,
        leaderId = 1,
        fixedAmount = bd("1"),
        maxDailyLoss = bd("5"),
        maxDailyOrders = 10,
        priceTolerance = bd("2"),
        minPrice = bd("0.10"),
        maxPrice = bd("0.80"),
        maxPositionValue = bd("5"),
        minOrderDepth = bd("100"),
        maxSpread = bd("0.03")
    )

    private fun buyOrder(
        id: Long,
        marketId: String,
        quantity: String,
        price: String,
        remainingQuantity: String
    ) = CopyOrderTracking(
        id = id,
        copyTradingId = 1,
        accountId = 1,
        leaderId = 1,
        marketId = marketId,
        side = "0",
        outcomeIndex = 0,
        buyOrderId = "buy-$id",
        leaderBuyTradeId = "leader-buy-$id",
        leaderBuyQuantity = null,
        quantity = bd(quantity),
        price = bd(price),
        matchedQuantity = bd(quantity).subtract(bd(remainingQuantity)),
        remainingQuantity = bd(remainingQuantity),
        status = if (bd(remainingQuantity).signum() == 0) "fully_matched" else "filled",
        source = "test",
        createdAt = id,
        updatedAt = id
    )

    private fun matchDetail(
        trackingId: Long,
        buyOrderId: String,
        sellPrice: String,
        pnl: String
    ) = SellMatchDetail(
        id = trackingId,
        matchRecordId = 1,
        trackingId = trackingId,
        buyOrderId = buyOrderId,
        matchedQuantity = bd("10"),
        buyPrice = bd("0.40"),
        sellPrice = bd(sellPrice),
        realizedPnl = bd(pnl),
        createdAt = 1
    )

    private fun bd(value: String) = BigDecimal(value)
}
