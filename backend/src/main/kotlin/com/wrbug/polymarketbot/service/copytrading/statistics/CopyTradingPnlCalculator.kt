package com.wrbug.polymarketbot.service.copytrading.statistics

import com.wrbug.polymarketbot.entity.CopyOrderTracking
import com.wrbug.polymarketbot.entity.SellMatchDetail
import com.wrbug.polymarketbot.entity.SellMatchRecord
import com.wrbug.polymarketbot.util.div
import com.wrbug.polymarketbot.util.gt
import com.wrbug.polymarketbot.util.lte
import com.wrbug.polymarketbot.util.multi
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Pure calculator for copy-trading PnL.
 *
 * The statistics API used to expose totalPnl as realized-only PnL and hard-code
 * unrealized PnL/current position value to zero. That makes active or expired
 * open positions invisible. This calculator keeps the accounting explicit:
 *
 * - currentPositionCost: remaining shares at their tracked buy cost
 * - currentPositionValue: remaining shares marked by current Polymarket price
 * - totalUnrealizedPnl: current value - current cost
 * - totalPnl: realized + unrealized
 */
object CopyTradingPnlCalculator {
    fun calculate(
        buyOrders: List<CopyOrderTracking>,
        sellRecords: List<SellMatchRecord>,
        matchDetails: List<SellMatchDetail>,
        quotes: List<PositionValuationQuote> = emptyList()
    ): CopyTradingPnlStatistics {
        val totalBuyQuantity = buyOrders.sumOf { it.quantity.toSafeBigDecimal() }
        val totalBuyAmount = buyOrders.sumOf { it.quantity.toSafeBigDecimal().multi(it.price) }
        val totalBuyOrders = buyOrders.size.toLong()
        val avgBuyPrice = if (totalBuyQuantity.gt(BigDecimal.ZERO)) {
            totalBuyAmount.div(totalBuyQuantity)
        } else {
            BigDecimal.ZERO
        }

        val totalSellQuantity = sellRecords.sumOf { it.totalMatchedQuantity.toSafeBigDecimal() }
        val totalSellAmount = matchDetails.sumOf { it.matchedQuantity.toSafeBigDecimal().multi(it.sellPrice) }
        val totalSellOrders = sellRecords.size.toLong()

        val openOrders = buyOrders.filter { it.remainingQuantity.toSafeBigDecimal().gt(BigDecimal.ZERO) }
        val currentPositionQuantity = openOrders.sumOf { it.remainingQuantity.toSafeBigDecimal() }
        val currentPositionCost = openOrders.sumOf { it.remainingQuantity.toSafeBigDecimal().multi(it.price) }
        val hasUnavailableQuotes = quotes.any { it.status == PositionQuoteStatus.UNAVAILABLE }
        val quotedOpenPositions = openOrders.map { order ->
            val quote = findQuote(order, quotes)
            val status = when {
                quote?.status == PositionQuoteStatus.AVAILABLE -> PositionQuoteStatus.AVAILABLE
                hasUnavailableQuotes -> PositionQuoteStatus.UNAVAILABLE
                else -> PositionQuoteStatus.NO_MATCH
            }
            QuotedOpenPosition(
                order = order,
                status = status,
                currentPrice = quote?.currentPrice ?: BigDecimal.ZERO
            )
        }
        val currentPositionValue = quotedOpenPositions.sumOf { position ->
            position.order.remainingQuantity.toSafeBigDecimal().multi(position.currentPrice)
        }
        val zeroValuePositionCost = quotedOpenPositions
            .filter { it.currentPrice.lte(BigDecimal.ZERO) }
            .sumOf { it.order.remainingQuantity.toSafeBigDecimal().multi(it.order.price) }
        val confirmedZeroValuePositionCost = quotedOpenPositions
            .filter { it.status == PositionQuoteStatus.AVAILABLE && it.currentPrice.lte(BigDecimal.ZERO) }
            .sumOf { it.order.remainingQuantity.toSafeBigDecimal().multi(it.order.price) }
        val quoteStatusSummary = QuoteStatusSummary.from(quotedOpenPositions.map { it.status })

        val totalRealizedPnl = matchDetails.sumOf { it.realizedPnl.toSafeBigDecimal() }
        val totalUnrealizedPnl = currentPositionValue.subtract(currentPositionCost)
        val totalPnl = totalRealizedPnl.add(totalUnrealizedPnl)

        return CopyTradingPnlStatistics(
            totalBuyQuantity = totalBuyQuantity,
            totalBuyOrders = totalBuyOrders,
            totalBuyAmount = totalBuyAmount,
            avgBuyPrice = avgBuyPrice,
            totalSellQuantity = totalSellQuantity,
            totalSellOrders = totalSellOrders,
            totalSellAmount = totalSellAmount,
            currentPositionQuantity = currentPositionQuantity,
            currentPositionCost = currentPositionCost,
            currentPositionValue = currentPositionValue,
            zeroValuePositionCost = zeroValuePositionCost,
            confirmedZeroValuePositionCost = confirmedZeroValuePositionCost,
            quoteStatusSummary = quoteStatusSummary,
            totalRealizedPnl = totalRealizedPnl,
            totalUnrealizedPnl = totalUnrealizedPnl,
            totalPnl = totalPnl,
            totalPnlPercent = calculatePnlPercent(totalBuyAmount, totalPnl)
        )
    }

    private fun findQuote(
        order: CopyOrderTracking,
        quotes: List<PositionValuationQuote>
    ): PositionValuationQuote? {
        return quotes.firstOrNull { quote ->
            quote.marketId == order.marketId &&
                order.outcomeIndex != null &&
                quote.outcomeIndex == order.outcomeIndex
        } ?: quotes.firstOrNull { quote ->
            quote.marketId == order.marketId &&
                order.outcomeIndex == null &&
                !quote.side.isNullOrBlank() &&
                quote.side.equals(order.side, ignoreCase = true)
        }
    }

    private fun calculatePnlPercent(totalBuyAmount: BigDecimal, totalPnl: BigDecimal): BigDecimal {
        if (totalBuyAmount.lte(BigDecimal.ZERO)) return BigDecimal.ZERO.setScale(2)
        return totalPnl.div(totalBuyAmount).multi(100).setScale(2, RoundingMode.HALF_UP)
    }
}

data class PositionValuationQuote(
    val marketId: String,
    val outcomeIndex: Int?,
    val side: String?,
    val currentPrice: BigDecimal,
    val status: PositionQuoteStatus = PositionQuoteStatus.AVAILABLE,
    val failureReason: String? = null
) {
    companion object {
        fun unavailable(reason: String? = null): PositionValuationQuote {
            return PositionValuationQuote(
                marketId = "__unavailable__",
                outcomeIndex = null,
                side = null,
                currentPrice = BigDecimal.ZERO,
                status = PositionQuoteStatus.UNAVAILABLE,
                failureReason = reason
            )
        }
    }
}

enum class PositionQuoteStatus {
    AVAILABLE,
    NO_MATCH,
    UNAVAILABLE
}

data class QuoteStatusSummary(
    val overallStatus: PositionQuoteStatus,
    val availableCount: Int,
    val noMatchCount: Int,
    val unavailableCount: Int
) {
    companion object {
        fun from(statuses: List<PositionQuoteStatus>): QuoteStatusSummary {
            val unavailableCount = statuses.count { it == PositionQuoteStatus.UNAVAILABLE }
            val noMatchCount = statuses.count { it == PositionQuoteStatus.NO_MATCH }
            val availableCount = statuses.count { it == PositionQuoteStatus.AVAILABLE }
            val overallStatus = when {
                unavailableCount > 0 -> PositionQuoteStatus.UNAVAILABLE
                noMatchCount > 0 -> PositionQuoteStatus.NO_MATCH
                else -> PositionQuoteStatus.AVAILABLE
            }
            return QuoteStatusSummary(
                overallStatus = overallStatus,
                availableCount = availableCount,
                noMatchCount = noMatchCount,
                unavailableCount = unavailableCount
            )
        }
    }
}

private data class QuotedOpenPosition(
    val order: CopyOrderTracking,
    val status: PositionQuoteStatus,
    val currentPrice: BigDecimal
)

data class CopyTradingPnlStatistics(
    val totalBuyQuantity: BigDecimal,
    val totalBuyOrders: Long,
    val totalBuyAmount: BigDecimal,
    val avgBuyPrice: BigDecimal,
    val totalSellQuantity: BigDecimal,
    val totalSellOrders: Long,
    val totalSellAmount: BigDecimal,
    val currentPositionQuantity: BigDecimal,
    val currentPositionCost: BigDecimal,
    val currentPositionValue: BigDecimal,
    val zeroValuePositionCost: BigDecimal,
    val confirmedZeroValuePositionCost: BigDecimal,
    val quoteStatusSummary: QuoteStatusSummary,
    val totalRealizedPnl: BigDecimal,
    val totalUnrealizedPnl: BigDecimal,
    val totalPnl: BigDecimal,
    val totalPnlPercent: BigDecimal
)
