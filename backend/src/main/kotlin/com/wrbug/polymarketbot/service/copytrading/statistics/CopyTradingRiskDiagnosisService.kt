package com.wrbug.polymarketbot.service.copytrading.statistics

import com.wrbug.polymarketbot.dto.CopyTradingRiskDiagnosisDto
import com.wrbug.polymarketbot.dto.RiskWarningDto
import com.wrbug.polymarketbot.dto.TopLosingMarketDto
import com.wrbug.polymarketbot.entity.CopyOrderTracking
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.entity.SellMatchDetail
import com.wrbug.polymarketbot.util.gt
import com.wrbug.polymarketbot.util.lte
import java.math.BigDecimal

object CopyTradingRiskDiagnosisService {
    private const val MIN_CONFIDENCE_SAMPLE_SIZE = 10

    fun buildDiagnosis(
        copyTrading: CopyTrading,
        buyOrders: List<CopyOrderTracking>,
        sellRecordsCount: Int,
        matchDetails: List<SellMatchDetail>,
        filteredOrderCount: Long,
        pnl: CopyTradingPnlStatistics,
        generatedAt: Long = System.currentTimeMillis()
    ): CopyTradingRiskDiagnosisDto {
        val trackingByBuyOrderId = buyOrders.associateBy { it.buyOrderId }
        val topLosingMarkets = matchDetails
            .groupBy { detail -> trackingByBuyOrderId[detail.buyOrderId]?.marketId ?: detail.buyOrderId }
            .map { (marketId, details) ->
                TopLosingMarketDto(
                    marketId = marketId,
                    realizedPnl = details.sumOf { it.realizedPnl }.toPlainString(),
                    matchedOrders = details.size
                )
            }
            .filter { it.realizedPnl.toBigDecimalOrNull()?.lt(BigDecimal.ZERO) == true }
            .sortedBy { it.realizedPnl.toBigDecimalOrNull() ?: BigDecimal.ZERO }
            .take(10)
        val zeroSellLoss = matchDetails
            .filter { it.sellPrice.lte(BigDecimal.ZERO) }
            .sumOf { it.realizedPnl.abs() }
        val sampleSize = buyOrders.size
        val lowConfidence = pnl.totalPnl.gt(BigDecimal.ZERO) && sampleSize < MIN_CONFIDENCE_SAMPLE_SIZE
        val missingSources = buildList {
            if (pnl.quoteStatusSummary.overallStatus == PositionQuoteStatus.UNAVAILABLE) add("position-quotes")
        }

        return CopyTradingRiskDiagnosisDto(
            copyTradingId = copyTrading.id ?: 0,
            totalRealizedPnl = pnl.totalRealizedPnl.toPlainString(),
            totalUnrealizedPnl = pnl.totalUnrealizedPnl.toPlainString(),
            totalPnl = pnl.totalPnl.toPlainString(),
            currentPositionCost = pnl.currentPositionCost.toPlainString(),
            currentPositionValue = pnl.currentPositionValue.toPlainString(),
            zeroValuePositionCost = pnl.zeroValuePositionCost.toPlainString(),
            confirmedZeroValuePositionCost = pnl.confirmedZeroValuePositionCost.toPlainString(),
            zeroSellLoss = zeroSellLoss.toPlainString(),
            openPositionQuantity = pnl.currentPositionQuantity.toPlainString(),
            totalBuyOrders = buyOrders.size,
            totalSellRecords = sellRecordsCount,
            totalMatchDetails = matchDetails.size,
            filteredOrderCount = filteredOrderCount,
            sampleSize = sampleSize,
            lowConfidence = lowConfidence,
            confidenceReason = if (lowConfidence) {
                "样本量 ${sampleSize} 笔，低于 ${MIN_CONFIDENCE_SAMPLE_SIZE} 笔，不能视为已验证盈利"
            } else {
                "样本量满足第一版诊断阈值"
            },
            quoteOverallStatus = pnl.quoteStatusSummary.overallStatus.name,
            quoteAvailableCount = pnl.quoteStatusSummary.availableCount,
            quoteNoMatchCount = pnl.quoteStatusSummary.noMatchCount,
            quoteUnavailableCount = pnl.quoteStatusSummary.unavailableCount,
            dataIncomplete = missingSources.isNotEmpty(),
            missingSources = missingSources,
            topLosingMarkets = topLosingMarkets,
            riskWarnings = inspectRiskConfig(copyTrading),
            generatedAt = generatedAt
        )
    }

    fun inspectRiskConfig(copyTrading: CopyTrading): List<RiskWarningDto> {
        return buildList {
            if (copyTrading.maxDailyOrders > 20) {
                add(
                    RiskWarningDto(
                        field = "maxDailyOrders",
                        currentValue = copyTrading.maxDailyOrders.toString(),
                        suggestedValue = "20",
                        severity = RiskSeverity.HIGH.name,
                        reason = "每日订单数过高，短周期市场会快速放大亏损"
                    )
                )
            }
            if (copyTrading.maxDailyLoss > BigDecimal.TEN) {
                add(
                    RiskWarningDto(
                        field = "maxDailyLoss",
                        currentValue = copyTrading.maxDailyLoss.strip(),
                        suggestedValue = "10",
                        severity = RiskSeverity.HIGH.name,
                        reason = "每日最大亏损过高，不能起到止血作用"
                    )
                )
            }
            if (copyTrading.minPrice == null) {
                addMissingGuard("minPrice", "0.10", "缺少最低价格限制，容易跟到极端赔率订单")
            }
            if (copyTrading.maxPrice == null) {
                addMissingGuard("maxPrice", "0.80", "缺少最高价格限制，容易在高价位承担不对称下行")
            }
            if (copyTrading.maxPositionValue == null) {
                addMissingGuard("maxPositionValue", "10", "缺少单市场仓位上限，同一市场可以堆出过大暴露")
            }
            if (copyTrading.minOrderDepth == null) {
                addMissingGuard("minOrderDepth", "100", "缺少深度过滤，薄盘口订单更容易滑点")
            }
            if (copyTrading.maxSpread == null) {
                addMissingGuard("maxSpread", "0.03", "缺少价差过滤，宽价差市场成交质量更差")
            }
            if (copyTrading.priceTolerance > BigDecimal("3")) {
                add(
                    RiskWarningDto(
                        field = "priceTolerance",
                        currentValue = copyTrading.priceTolerance.strip(),
                        suggestedValue = "3",
                        severity = RiskSeverity.MEDIUM.name,
                        reason = "价格容忍度偏宽，实际成交可能偏离 leader 价格"
                    )
                )
            }
        }
    }

    private fun MutableList<RiskWarningDto>.addMissingGuard(field: String, suggestedValue: String, reason: String) {
        add(
            RiskWarningDto(
                field = field,
                currentValue = null,
                suggestedValue = suggestedValue,
                severity = RiskSeverity.HIGH.name,
                reason = reason
            )
        )
    }

    private fun BigDecimal.strip(): String = stripTrailingZeros().toPlainString()

    private fun BigDecimal.lt(other: BigDecimal): Boolean = compareTo(other) < 0
}

enum class RiskSeverity {
    LOW,
    MEDIUM,
    HIGH
}
