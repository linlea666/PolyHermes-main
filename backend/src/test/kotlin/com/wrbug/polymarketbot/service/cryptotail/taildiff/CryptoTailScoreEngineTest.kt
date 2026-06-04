package com.wrbug.polymarketbot.service.cryptotail.taildiff

import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import com.wrbug.polymarketbot.enums.TradingMode
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CryptoTailScoreEngineTest {

    private val engine = CryptoTailScoreEngine()

    private fun strategy() = CryptoTailStrategy(
        mode = TradingMode.TAIL_DIFF,
        tailDiffMinPrice = BigDecimal("0.88"),
        tailDiffMaxPrice = BigDecimal("0.93"),
        tailDiffHardMaxPrice = BigDecimal("0.94"),
        tailDiffMinModelProb = BigDecimal("0.95"),
        tailDiffMinEdge = BigDecimal("0.025"),
        tailDiffMinDiffSigma = BigDecimal("1.8"),
        tailDiffWindowStartSeconds = 150,
        tailDiffWindowEndSeconds = 60,
        tailDiffMinRemainingSeconds = 50,
        tailDiffMaxSpread = BigDecimal("0.02"),
        tailDiffDepthMultiplier = BigDecimal("3.0"),
        tailDiffMaxOrderbookAgeMs = 2000,
        tailDiffMaxPriceAgeMs = 2000,
        tailDiffMaxReverseVelocitySigma = BigDecimal("0.30"),
        tailDiffStatsMinSamples = 50,
        tailDiffMinEntryScore = 1,
        tailDiffPremiumScore = 80,
        tailDiffTopScore = 90,
        tailDiffWeightDiff = 25,
        tailDiffWeightTime = 15,
        tailDiffWeightOddsUnderprice = 20,
        tailDiffWeightOddsLag = 10,
        tailDiffWeightHistory = 15,
        tailDiffWeightBook = 10,
        tailDiffWeightData = 5
    )

    private fun input(
        outcomeIndex: Int = 0,
        modelSide: Int = 0,
        bestBid: String = "0.90",
        bestAsk: String? = "0.92",
        spread: String? = "0.01",
        modelProb: String = "0.97",
        edge: String = "0.05",
        diffSigma: String = "2.5",
        remainingSeconds: Int = 100,
        bidDepthUsd: String = "100",
        askDepthUsd: String = "100",
        orderbookAgeMs: Long = 100,
        priceAgeMs: Long? = 100,
        reverseVelocity: String = "0"
    ) = CryptoTailScoreEngine.Input(
        coin = "BTC",
        open = BigDecimal("100000"),
        close = BigDecimal("100500"),
        rawDiff = BigDecimal("500"),
        diffPct = BigDecimal("0.005"),
        diffSigma = BigDecimal(diffSigma),
        outcomeIndex = outcomeIndex,
        modelSide = modelSide,
        remainingSeconds = remainingSeconds,
        periodSeconds = 300,
        modelProb = BigDecimal(modelProb),
        modelProbSource = "STATS",
        statsSampleCount = 120,
        effectiveCost = BigDecimal("0.92"),
        edge = BigDecimal(edge),
        midImpliedProb = BigDecimal("0.90"),
        bestBid = BigDecimal(bestBid),
        bestAsk = bestAsk?.let { BigDecimal(it) },
        spread = spread?.let { BigDecimal(it) },
        bidDepthUsd = BigDecimal(bidDepthUsd),
        askDepthUsd = BigDecimal(askDepthUsd),
        orderbookAgeMs = orderbookAgeMs,
        priceAgeMs = priceAgeMs,
        reverseVelocitySigmaPerSec = BigDecimal(reverseVelocity),
        reverseVelocityReason = null,
        candidateAmountUsdc = BigDecimal.ONE
    )

    @Test
    fun `clean high-quality input passes with a tier and no vetoes`() {
        val out = engine.evaluate(input(), strategy())
        assertTrue(out.vetoes.isEmpty(), "expected no vetoes, got ${out.vetoes}")
        assertTrue(out.passed)
        assertNotNull(out.tier)
        assertTrue(out.score in 1..100)
    }

    @Test
    fun `direction mismatch is vetoed`() {
        val out = engine.evaluate(input(outcomeIndex = 1, modelSide = 0), strategy())
        assertTrue(out.vetoes.contains("MODEL_DIRECTION_MISMATCH"))
        assertFalse(out.passed)
        assertNull(out.tier)
    }

    @Test
    fun `window too early and too late are vetoed`() {
        assertTrue(engine.evaluate(input(remainingSeconds = 200), strategy()).vetoes.contains("WINDOW_TOO_EARLY"))
        assertTrue(engine.evaluate(input(remainingSeconds = 40), strategy()).vetoes.contains("WINDOW_TOO_LATE"))
    }

    @Test
    fun `low model prob and shallow depth are vetoed`() {
        assertTrue(engine.evaluate(input(modelProb = "0.80"), strategy()).vetoes.contains("MODEL_PROB_TOO_LOW"))
        assertTrue(engine.evaluate(input(bidDepthUsd = "1", askDepthUsd = "1"), strategy()).vetoes.contains("DEPTH_TOO_SHALLOW"))
    }

    @Test
    fun `fast reverse velocity is vetoed`() {
        assertTrue(engine.evaluate(input(reverseVelocity = "0.9"), strategy()).vetoes.contains("PRICE_RETRACING_FAST"))
    }
}
