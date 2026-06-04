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

    // ===== V72 增强：零回归 + 新功能 =====

    @Test
    fun `STATIC lag mode ignores dynamic inputs (zero regression)`() {
        val baseline = engine.evaluate(input(), strategy())
        // 默认 STATIC：即使喂入动态领先/赔率动量，分数与分项也完全不变
        val withDynamic = engine.evaluate(
            input().copy(priceLeadMoveSigma = BigDecimal("0.5"), oddsMoveOverWindow = BigDecimal.ZERO),
            strategy()
        )
        assertEquals(baseline.score, withDynamic.score)
        assertEquals(baseline.component.scoreOddsLag, withDynamic.component.scoreOddsLag)
    }

    @Test
    fun `default anchors reproduce previous hardcoded scoring (zero regression)`() {
        // 默认 strategy 的锚点字段 = 原硬编码常量（edge/0.10, lag/0.15, history[0.90,1.0], 3×minSigma）
        // 这里固定一组输入，验证分项与历史实现一致（edge=0.05→0.5; midGap=0.07→0.4667...; diffSigma=2.5,min1.8,max5.4）
        val out = engine.evaluate(input(), strategy())
        // edge 0.05 / 0.10 = 0.5 → ×权重20 = 10.00
        assertEquals(BigDecimal("10.00"), out.component.scoreOddsUnderprice)
        // diffSigma 2.5：min1.8 max5.4 → (2.5-1.8)/(5.4-1.8)=0.19444 → ×25 = 4.86
        assertEquals(BigDecimal("4.86"), out.component.scoreDiff)
    }

    @Test
    fun `DYNAMIC lag rewards underlying lead extension without odds catch-up`() {
        val dynStrategy = strategy().copy(tailDiffOddsLagMode = "DYNAMIC")
        // 标的大幅扩大领先（0.5σ）但赔率纹丝不动（0）→ 滞后分高
        val highLag = engine.evaluate(
            input().copy(priceLeadMoveSigma = BigDecimal("0.5"), oddsMoveOverWindow = BigDecimal.ZERO),
            dynStrategy
        )
        // 同样领先扩大，但赔率已经追上（涨 0.05）→ 滞后分低
        val lowLag = engine.evaluate(
            input().copy(priceLeadMoveSigma = BigDecimal("0.5"), oddsMoveOverWindow = BigDecimal("0.05")),
            dynStrategy
        )
        assertTrue(
            highLag.component.scoreOddsLag > lowLag.component.scoreOddsLag,
            "expected high-lag(${highLag.component.scoreOddsLag}) > low-lag(${lowLag.component.scoreOddsLag})"
        )
    }

    @Test
    fun `DYNAMIC lag falls back to static when dynamic inputs unavailable`() {
        val dynStrategy = strategy().copy(tailDiffOddsLagMode = "DYNAMIC")
        // 动态数据不可用（null）→ 回退静态滞后，分项应与 STATIC 一致
        val dynamicNa = engine.evaluate(input(), dynStrategy)
        val static = engine.evaluate(input(), strategy())
        assertEquals(static.component.scoreOddsLag, dynamicNa.component.scoreOddsLag)
    }

    @Test
    fun `configurable edge full scale changes underprice score`() {
        // 把满分锚点从 0.10 收紧到 0.05 → edge=0.05 直接满分（原来只有 0.5）
        val tightStrategy = strategy().copy(tailDiffEdgeFullScale = BigDecimal("0.05"))
        val out = engine.evaluate(input(edge = "0.05"), tightStrategy)
        // 满分 ×权重20 = 20.00
        assertEquals(BigDecimal("20.00"), out.component.scoreOddsUnderprice)
    }
}
