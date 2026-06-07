package com.wrbug.polymarketbot.service.cryptotail.taildiff

import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import com.wrbug.polymarketbot.enums.TradingMode
import com.wrbug.polymarketbot.util.toJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class TailDiffExitPresetTest {

    private val resolver = TailDiffExitPresetResolver()

    private fun strategyWithNormalPreset(json: String?): CryptoTailStrategy =
        CryptoTailStrategy(mode = TradingMode.TAIL_DIFF, tailDiffExitPresetNormalJson = json)

    @Test
    fun `parses snake_case preset overriding defaults`() {
        val json = """
            {"hold_to_expiry":false,
             "tp_limit":{"enabled":true,"price":"0.97","ratio":"0.5"},
             "stop_loss":{"enabled":true,"offset":"0.15","min_price":"0.72","ratio":"1"},
             "dynamic_exit":{"enabled":true,"min_model_prob_after_entry":"0.9","min_odds_after_entry":"0.82","max_reverse_velocity_sigma":"0.35"},
             "execution":{"stop_slippage":"0.03","worst_price":"0.6"}}
        """.trimIndent()
        val preset = resolver.resolveForTier(strategyWithNormalPreset(json), TailDiffTier.NORMAL)

        assertEquals(BigDecimal("0.97"), preset.tpLimit.price)
        assertEquals(BigDecimal("0.5"), preset.tpLimit.ratio)
        assertEquals(BigDecimal("0.72"), preset.stopLoss.minPrice)
        assertEquals(BigDecimal("0.9"), preset.dynamicExit.minModelProbAfterEntry)
        assertEquals(BigDecimal("0.03"), preset.execution.stopSlippage)
        assertEquals(BigDecimal("0.6"), preset.execution.worstPrice)
    }

    @Test
    fun `parses camelCase aliases identically to snake_case`() {
        val json = """
            {"holdToExpiry":false,
             "tpLimit":{"enabled":true,"price":"0.97","ratio":"0.5"},
             "stopLoss":{"enabled":true,"offset":"0.15","minPrice":"0.72","ratio":"1"},
             "dynamicExit":{"minModelProbAfterEntry":"0.9","minOddsAfterEntry":"0.82","maxReverseVelocitySigma":"0.35"},
             "execution":{"stopSlippage":"0.03","worstPrice":"0.6"}}
        """.trimIndent()
        val preset = resolver.resolveForTier(strategyWithNormalPreset(json), TailDiffTier.NORMAL)

        assertEquals(BigDecimal("0.97"), preset.tpLimit.price)
        assertEquals(BigDecimal("0.72"), preset.stopLoss.minPrice)
        assertEquals(BigDecimal("0.9"), preset.dynamicExit.minModelProbAfterEntry)
        assertEquals(BigDecimal("0.82"), preset.dynamicExit.minOddsAfterEntry)
        assertEquals(BigDecimal("0.03"), preset.execution.stopSlippage)
        assertEquals(BigDecimal("0.6"), preset.execution.worstPrice)
    }

    @Test
    fun `blank json falls back to tier default`() {
        val preset = resolver.resolveForTier(strategyWithNormalPreset(null), TailDiffTier.NORMAL)
        val default = resolver.defaultForTier(TailDiffTier.NORMAL)
        assertEquals(default, preset)
    }

    @Test
    fun `toMap round trips through parsePreset`() {
        val original = resolver.defaultForTier(TailDiffTier.PREMIUM)
        val json = original.toMap().toJson()
        val reparsed = resolver.resolveForTier(
            CryptoTailStrategy(mode = TradingMode.TAIL_DIFF, tailDiffExitPresetPremiumJson = json),
            TailDiffTier.PREMIUM
        )
        assertEquals(original.tpLimit, reparsed.tpLimit)
        assertEquals(original.stopLoss, reparsed.stopLoss)
        assertEquals(original.dynamicExit, reparsed.dynamicExit)
        assertEquals(original.holdToExpiry, reparsed.holdToExpiry)
    }

    @Test
    fun `isValid accepts blank and well-formed json`() {
        assertTrue(resolver.isValid(null))
        assertTrue(resolver.isValid(""))
        assertTrue(resolver.isValid("""{"tp_limit":{"price":"0.98","ratio":"1"}}"""))
        assertTrue(resolver.isValid("""{"tpLimit":{"price":"0.98"}}"""))
    }

    @Test
    fun `isValid rejects malformed json and out-of-range values`() {
        assertFalse(resolver.isValid("{not json"))
        // price > 1
        assertFalse(resolver.isValid("""{"tp_limit":{"price":"1.5"}}"""))
        // sub-block not an object
        assertFalse(resolver.isValid("""{"tp_limit":"oops"}"""))
        // negative slippage
        assertFalse(resolver.isValid("""{"execution":{"stop_slippage":"-0.1"}}"""))
    }

    // ===== V73 真熔断字段（catastrophe_bid_floor / max_drawdown_pct） =====

    @Test
    fun `parses catastrophe fields snake and camel case`() {
        val snake = resolver.resolveForTier(
            strategyWithNormalPreset("""{"dynamic_exit":{"catastrophe_bid_floor":"0.7","max_drawdown_pct":"0.45"}}"""),
            TailDiffTier.NORMAL
        )
        assertEquals(BigDecimal("0.7"), snake.dynamicExit.catastropheBidFloor)
        assertEquals(BigDecimal("0.45"), snake.dynamicExit.maxDrawdownPct)
        val camel = resolver.resolveForTier(
            strategyWithNormalPreset("""{"dynamicExit":{"catastropheBidFloor":"0.66","maxDrawdownPct":"0.4"}}"""),
            TailDiffTier.NORMAL
        )
        assertEquals(BigDecimal("0.66"), camel.dynamicExit.catastropheBidFloor)
        assertEquals(BigDecimal("0.4"), camel.dynamicExit.maxDrawdownPct)
    }

    @Test
    fun `catastrophe fields default to zero (disabled, zero regression)`() {
        // 默认档与未提供该字段时均为 0=关闭，确保旧配置行为不变
        assertEquals(BigDecimal.ZERO, resolver.defaultForTier(TailDiffTier.TOP).dynamicExit.catastropheBidFloor)
        assertEquals(BigDecimal.ZERO, resolver.defaultForTier(TailDiffTier.TOP).dynamicExit.maxDrawdownPct)
        val noField = resolver.resolveForTier(
            strategyWithNormalPreset("""{"dynamic_exit":{"min_odds_after_entry":"0.8"}}"""),
            TailDiffTier.NORMAL
        )
        assertEquals(BigDecimal.ZERO, noField.dynamicExit.catastropheBidFloor)
        assertEquals(BigDecimal.ZERO, noField.dynamicExit.maxDrawdownPct)
    }

    @Test
    fun `catastrophe fields round trip through toMap`() {
        val original = resolver.defaultForTier(TailDiffTier.TOP).let {
            it.copy(dynamicExit = it.dynamicExit.copy(catastropheBidFloor = BigDecimal("0.62"), maxDrawdownPct = BigDecimal("0.45")))
        }
        val reparsed = resolver.resolveForTier(
            CryptoTailStrategy(mode = TradingMode.TAIL_DIFF, tailDiffExitPresetTopJson = original.toMap().toJson()),
            TailDiffTier.TOP
        )
        assertEquals(original.dynamicExit, reparsed.dynamicExit)
    }

    @Test
    fun `parses and round-trips catastrophe immediate flag`() {
        val parsed = resolver.resolveForTier(
            strategyWithNormalPreset("""{"dynamic_exit":{"catastrophe_bid_floor":"0.7","catastrophe_immediate":true}}"""),
            TailDiffTier.NORMAL
        )
        assertTrue(parsed.dynamicExit.catastropheImmediate)
        // 默认（未提供）为 false，零回归
        val def = resolver.resolveForTier(
            strategyWithNormalPreset("""{"dynamic_exit":{"catastrophe_bid_floor":"0.7"}}"""),
            TailDiffTier.NORMAL
        )
        assertFalse(def.dynamicExit.catastropheImmediate)
        // round-trip
        val original = resolver.defaultForTier(TailDiffTier.TOP).let {
            it.copy(dynamicExit = it.dynamicExit.copy(catastropheBidFloor = BigDecimal("0.62"), catastropheImmediate = true))
        }
        val reparsed = resolver.resolveForTier(
            CryptoTailStrategy(mode = TradingMode.TAIL_DIFF, tailDiffExitPresetTopJson = original.toMap().toJson()),
            TailDiffTier.TOP
        )
        assertEquals(original.dynamicExit, reparsed.dynamicExit)
    }

    @Test
    fun `isValid rejects out-of-range catastrophe values`() {
        assertTrue(resolver.isValid("""{"dynamic_exit":{"catastrophe_bid_floor":"0.7","max_drawdown_pct":"0.45"}}"""))
        // bidFloor > 1
        assertFalse(resolver.isValid("""{"dynamic_exit":{"catastrophe_bid_floor":"1.2"}}"""))
        // drawdown > 1
        assertFalse(resolver.isValid("""{"dynamic_exit":{"max_drawdown_pct":"1.5"}}"""))
    }
}
