package com.wrbug.polymarketbot.service.copytrading.research

import com.wrbug.polymarketbot.entity.LeaderPaperSession
import com.wrbug.polymarketbot.entity.LeaderResearchCandidate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class LeaderResearchScoringServiceTest {
    private val service = LeaderResearchScoringService(
        candidateRepository = mock(),
        paperSessionRepository = mock(),
        scoreRepository = mock()
    )

    @Test
    fun `compute rewards profitable repeatable fresh paper session`() {
        val now = System.currentTimeMillis()
        val candidate = LeaderResearchCandidate(
            id = 1L,
            normalizedWallet = "0x1111111111111111111111111111111111111111",
            lastSourceSeenAt = now
        )
        val session = LeaderPaperSession(
            id = 10L,
            candidateId = 1L,
            startedAt = now - 8L * 24 * 60 * 60 * 1000,
            tradeCount = 12,
            filteredCount = 1,
            openExposure = BigDecimal("10"),
            copyablePnl = BigDecimal("4"),
            maxDrawdown = BigDecimal("-3"),
            unknownValuationExposure = BigDecimal("1"),
            filteredRatio = BigDecimal("0.08")
        )

        val score = service.compute(candidate, session, runId = 99L)

        assertTrue(score.totalScore >= BigDecimal("60"))
        assertEquals("research-copyability-v1", score.scoreVersion)
        assertEquals(12, score.sampleTradeCount)
        assertTrue(score.reason!!.contains("source_fresh=true"))
    }

    @Test
    fun `compute penalizes stale source and unknown quotes`() {
        val candidate = LeaderResearchCandidate(
            id = 1L,
            normalizedWallet = "0x1111111111111111111111111111111111111111",
            lastSourceSeenAt = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        )
        val session = LeaderPaperSession(
            id = 10L,
            candidateId = 1L,
            tradeCount = 2,
            openExposure = BigDecimal("10"),
            unknownValuationExposure = BigDecimal("8"),
            filteredRatio = BigDecimal("0.50")
        )

        val score = service.compute(candidate, session, runId = null)

        assertTrue(score.totalScore < BigDecimal("60"))
        assertTrue(score.reason!!.contains("source_fresh=false"))
    }

    @Test
    fun `compute caps small samples below promotion threshold`() {
        val now = System.currentTimeMillis()
        val candidate = LeaderResearchCandidate(
            id = 1L,
            normalizedWallet = "0x1111111111111111111111111111111111111111",
            lastSourceSeenAt = now
        )
        val session = LeaderPaperSession(
            id = 10L,
            candidateId = 1L,
            startedAt = now - 8L * 24 * 60 * 60 * 1000,
            tradeCount = 1,
            openExposure = BigDecimal("1"),
            copyablePnl = BigDecimal("100"),
            maxDrawdown = BigDecimal.ZERO,
            filteredRatio = BigDecimal.ZERO
        )

        val score = service.compute(candidate, session, runId = null)

        assertTrue(score.totalScore <= BigDecimal("59"))
        assertTrue(score.reason!!.contains("sample_cap_applied=true"))
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> mock(): T = org.mockito.Mockito.mock(T::class.java)
}
