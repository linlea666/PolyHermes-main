package com.wrbug.polymarketbot.service.cryptotail.taildiff

import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import com.wrbug.polymarketbot.enums.TradingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class TailDiffEntrySegmentResolverTest {

    private val resolver = TailDiffEntrySegmentResolver()

    private fun strategy(json: String?): CryptoTailStrategy = CryptoTailStrategy(
        mode = TradingMode.TAIL_DIFF,
        tailDiffEntrySegmentsJson = json,
        tailDiffMinModelProb = BigDecimal("0.95"),
        tailDiffMinDiffSigma = BigDecimal("1.8"),
        tailDiffMinEntryScore = 70,
        tailDiffMinRemainingSeconds = 50,
        tailDiffWindowStartSeconds = 150,
        tailDiffMinPrice = BigDecimal("0.86")
    )

    @Test
    fun `empty segments returns default overlay leaving strategy unchanged`() {
        val s = strategy(null)
        val seg = resolver.resolve(s, 100)
        assertNotNull(seg)
        assertTrue(seg!!.isDefault)
        assertEquals(s, resolver.applyOverrides(s, seg))
    }

    @Test
    fun `applyOverrides applies min_model_prob and other overrides`() {
        val json = """[{"name":"early","remaining_hi":300,"remaining_lo":150,"min_score":90,"min_diff_sigma":2.2,"min_edge":0.04,"min_model_prob":0.97,"min_ask":0.91,"max_ask":0.92}]"""
        val s = strategy(json)
        val seg = resolver.resolve(s, 200)
        assertNotNull(seg)
        val eff = resolver.applyOverrides(s, seg!!)

        assertEquals(BigDecimal("0.97"), eff.tailDiffMinModelProb)
        assertEquals(BigDecimal("2.2"), eff.tailDiffMinDiffSigma)
        assertEquals(BigDecimal("0.04"), eff.tailDiffMinEdge)
        assertEquals(BigDecimal("0.91"), eff.tailDiffMinPrice)
        assertEquals(BigDecimal("0.92"), eff.tailDiffHardMaxPrice)
        assertEquals(90, eff.tailDiffMinEntryScore)
        assertEquals(300, eff.tailDiffWindowStartSeconds)
        assertEquals(150, eff.tailDiffWindowEndSeconds)
    }

    @Test
    fun `omitted min_ask falls back to strategy global min price`() {
        val json = """[{"name":"late","remaining_hi":150,"remaining_lo":60}]"""
        val s = strategy(json)
        val seg = resolver.resolve(s, 100)!!
        val eff = resolver.applyOverrides(s, seg)
        assertEquals(s.tailDiffMinPrice, eff.tailDiffMinPrice)
    }

    @Test
    fun `omitted min_model_prob falls back to strategy global`() {
        val json = """[{"name":"late","remaining_hi":150,"remaining_lo":60}]"""
        val s = strategy(json)
        val seg = resolver.resolve(s, 100)!!
        val eff = resolver.applyOverrides(s, seg)
        assertEquals(s.tailDiffMinModelProb, eff.tailDiffMinModelProb)
    }

    @Test
    fun `non-matching remaining returns null when segments configured`() {
        val json = """[{"name":"early","remaining_hi":300,"remaining_lo":150}]"""
        assertNull(resolver.resolve(strategy(json), 100))
    }

    @Test
    fun `windowEnvelope spans all segments`() {
        val json = """[{"remaining_hi":300,"remaining_lo":150},{"remaining_hi":150,"remaining_lo":60}]"""
        val (lo, hi) = resolver.windowEnvelope(strategy(json))
        assertEquals(60, lo)
        assertEquals(300, hi)
    }

    @Test
    fun `windowEnvelope falls back to single window when empty`() {
        val (lo, hi) = resolver.windowEnvelope(strategy(null))
        assertEquals(50, lo)
        assertEquals(150, hi)
    }

    @Test
    fun `isValid enforces ranges including min_model_prob`() {
        assertTrue(resolver.isValid(null))
        assertTrue(resolver.isValid("""[{"remaining_hi":300,"remaining_lo":150,"min_model_prob":0.96}]"""))
        // min_model_prob out of unit range
        assertFalse(resolver.isValid("""[{"remaining_hi":300,"remaining_lo":150,"min_model_prob":1.5}]"""))
        // hi < lo
        assertFalse(resolver.isValid("""[{"remaining_hi":100,"remaining_lo":150}]"""))
        // empty array
        assertFalse(resolver.isValid("[]"))
        // min_ask within unit range OK
        assertTrue(resolver.isValid("""[{"remaining_hi":300,"remaining_lo":150,"min_ask":0.90}]"""))
        // min_ask out of unit range
        assertFalse(resolver.isValid("""[{"remaining_hi":300,"remaining_lo":150,"min_ask":1.5}]"""))
        // min_ask > max_ask → 该段恒不可入场，非法
        assertFalse(resolver.isValid("""[{"remaining_hi":300,"remaining_lo":150,"min_ask":0.95,"max_ask":0.90}]"""))
    }
}
