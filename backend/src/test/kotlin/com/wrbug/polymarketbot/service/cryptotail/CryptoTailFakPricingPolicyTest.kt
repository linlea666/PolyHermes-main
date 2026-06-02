package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.enums.TradingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CryptoTailFakPricingPolicyTest {

    @Test
    fun `allows configured slippage when EV budget covers it`() {
        val result = price(
            mode = TradingMode.BARRIER_HOLD,
            pWin = "0.86",
            requiredEdge = "0.03",
            bestBid = "0.75",
            bestAsk = "0.76",
            slippage = "0.02",
            cap = "0.97"
        )

        assertTrue(result.canSubmit)
        assertEquals("0.7800", result.finalLimit.toPlainString())
        assertEquals("CONFIGURED_SLIPPAGE", result.pricingClampReason)
    }

    @Test
    fun `clamps high configured slippage to EV safe limit`() {
        val result = price(
            mode = TradingMode.BARRIER_HOLD,
            pWin = "0.79538",
            requiredEdge = "0.03",
            bestBid = "0.75",
            bestAsk = "0.76",
            slippage = "0.02",
            cap = "0.97"
        )

        assertTrue(result.canSubmit)
        assertEquals("0.7653", result.finalLimit.toPlainString())
        assertEquals("EV_SAFE_LIMIT", result.pricingClampReason)
    }

    @Test
    fun `rejects when EV safe limit cannot reach executable ask`() {
        val result = price(
            mode = TradingMode.BARRIER_HOLD,
            pWin = "0.79538",
            requiredEdge = "0.03",
            bestBid = "0.75",
            bestAsk = "0.80",
            slippage = "0.02",
            cap = "0.97"
        )

        assertFalse(result.canSubmit)
        assertEquals("0.7653", result.finalLimit.toPlainString())
        assertEquals("0.8000", result.executableAsk.toPlainString())
    }

    @Test
    fun `uses bestBid plus costBuffer when bestAsk is missing`() {
        val result = price(
            mode = TradingMode.BARRIER_HOLD,
            pWin = "0.90",
            requiredEdge = "0.03",
            bestBid = "0.70",
            bestAsk = null,
            costBuffer = "0.02",
            slippage = "0.03",
            cap = "0.97"
        )

        assertTrue(result.canSubmit)
        assertEquals("0.72", result.rawAsk.toPlainString())
        assertEquals("0.7500", result.finalLimit.toPlainString())
    }

    @Test
    fun `accounts for taker fee in EV safe limit`() {
        val result = price(
            mode = TradingMode.BARRIER_HOLD,
            pWin = "0.83",
            requiredEdge = "0.03",
            bestBid = "0.78",
            bestAsk = "0.79",
            slippage = "0.03",
            takerFeeBps = 100,
            cap = "0.97"
        )

        assertTrue(result.canSubmit)
        assertEquals("0.7920", result.finalLimit.toPlainString())
        assertEquals("EV_SAFE_LIMIT", result.pricingClampReason)
    }

    @Test
    fun `uses bracket edge and cap independently`() {
        val result = price(
            mode = TradingMode.BRACKET_DYNAMIC,
            pWin = "0.93",
            requiredEdge = "0.04",
            bestBid = "0.86",
            bestAsk = "0.87",
            slippage = "0.04",
            cap = "0.89"
        )

        assertTrue(result.canSubmit)
        assertEquals("0.8900", result.finalLimit.toPlainString())
        assertEquals("EV_SAFE_LIMIT", result.pricingClampReason)
    }

    private fun price(
        mode: TradingMode,
        pWin: String,
        requiredEdge: String,
        bestBid: String,
        bestAsk: String?,
        costBuffer: String = "0.02",
        slippage: String,
        takerFeeBps: Int = 0,
        cap: String
    ): CryptoTailFakPricingPolicy.Result {
        return CryptoTailFakPricingPolicy.price(
            CryptoTailFakPricingPolicy.Request(
                mode = mode,
                pWin = BigDecimal(pWin),
                requiredEdge = BigDecimal(requiredEdge),
                bestBid = BigDecimal(bestBid),
                bestAsk = bestAsk?.let { BigDecimal(it) },
                costBuffer = BigDecimal(costBuffer),
                configuredSlippage = BigDecimal(slippage),
                takerFeeBps = takerFeeBps,
                priceCap = BigDecimal(cap)
            )
        )
    }
}
