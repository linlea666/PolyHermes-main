package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.enums.TradingMode
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * EV-aware FAK entry limit pricing.
 *
 * entryFakSlippage is treated as the maximum allowed extra limit, while the
 * final limit remains bounded by the EV edge and the mode-specific price cap.
 */
object CryptoTailFakPricingPolicy {

    data class Request(
        val mode: TradingMode,
        val pWin: BigDecimal,
        val requiredEdge: BigDecimal,
        val bestBid: BigDecimal,
        val bestAsk: BigDecimal?,
        val costBuffer: BigDecimal,
        val configuredSlippage: BigDecimal,
        val takerFeeBps: Int,
        val priceCap: BigDecimal
    )

    data class Result(
        val mode: TradingMode,
        val rawAsk: BigDecimal,
        val executableAsk: BigDecimal,
        val configuredSlippage: BigDecimal,
        val configuredLimit: BigDecimal,
        val evSafeLimit: BigDecimal,
        val priceCap: BigDecimal,
        val finalLimit: BigDecimal,
        val limitEdge: BigDecimal,
        val pricingClampReason: String,
        val canSubmit: Boolean
    )

    fun price(request: Request): Result {
        val rawAsk = (request.bestAsk ?: request.bestBid.add(request.costBuffer)).max(BigDecimal.ZERO)
        val executableAsk = rawAsk.setScale(4, RoundingMode.UP)
        val configuredLimit = rawAsk.add(request.configuredSlippage)
            .setScale(4, RoundingMode.UP)
        val feeMultiplier = BigDecimal.ONE.add(
            BigDecimal(request.takerFeeBps).divide(BigDecimal(10000), 18, RoundingMode.HALF_UP)
        )
        val evBudget = request.pWin.subtract(request.requiredEdge)
        val evSafeLimit = if (evBudget > BigDecimal.ZERO && feeMultiplier > BigDecimal.ZERO) {
            evBudget.divide(feeMultiplier, 18, RoundingMode.DOWN)
                .setScale(4, RoundingMode.DOWN)
        } else {
            BigDecimal.ZERO.setScale(4, RoundingMode.DOWN)
        }
        val cappedPrice = request.priceCap.setScale(4, RoundingMode.DOWN)
        val finalLimit = minOf(configuredLimit, evSafeLimit, cappedPrice)
            .setScale(4, RoundingMode.DOWN)
        val limitEffectiveCost = finalLimit.multiply(feeMultiplier).setScale(8, RoundingMode.HALF_UP)
        val limitEdge = request.pWin.subtract(limitEffectiveCost).setScale(8, RoundingMode.HALF_UP)
        val clampReason = when (finalLimit) {
            evSafeLimit -> "EV_SAFE_LIMIT"
            cappedPrice -> "PRICE_CAP"
            else -> "CONFIGURED_SLIPPAGE"
        }

        return Result(
            mode = request.mode,
            rawAsk = rawAsk,
            executableAsk = executableAsk,
            configuredSlippage = request.configuredSlippage,
            configuredLimit = configuredLimit,
            evSafeLimit = evSafeLimit,
            priceCap = cappedPrice,
            finalLimit = finalLimit,
            limitEdge = limitEdge,
            pricingClampReason = clampReason,
            canSubmit = finalLimit >= executableAsk && finalLimit > BigDecimal.ZERO
        )
    }
}
