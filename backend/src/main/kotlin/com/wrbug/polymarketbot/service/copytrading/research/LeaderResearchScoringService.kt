package com.wrbug.polymarketbot.service.copytrading.research

import com.wrbug.polymarketbot.entity.LeaderPaperSession
import com.wrbug.polymarketbot.entity.LeaderResearchCandidate
import com.wrbug.polymarketbot.entity.LeaderResearchScore
import com.wrbug.polymarketbot.enums.LeaderResearchState
import com.wrbug.polymarketbot.repository.LeaderPaperSessionRepository
import com.wrbug.polymarketbot.repository.LeaderResearchCandidateRepository
import com.wrbug.polymarketbot.repository.LeaderResearchScoreRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

@Service
class LeaderResearchScoringService(
    private val candidateRepository: LeaderResearchCandidateRepository,
    private val paperSessionRepository: LeaderPaperSessionRepository,
    private val scoreRepository: LeaderResearchScoreRepository
) {
    @Transactional
    fun scoreAll(runId: Long?): List<LeaderResearchScore> {
        return candidateRepository.findByResearchStateIn(
            listOf(
                LeaderResearchState.DISCOVERED,
                LeaderResearchState.CANDIDATE,
                LeaderResearchState.PAPER,
                LeaderResearchState.TRIAL_READY,
                LeaderResearchState.COOLDOWN
            )
        ).map { scoreCandidate(it, runId) }
    }

    @Transactional
    fun scoreCandidate(candidate: LeaderResearchCandidate, runId: Long?): LeaderResearchScore {
        val session = candidate.id?.let { paperSessionRepository.findTopByCandidateIdOrderByStartedAtDesc(it) }
        val score = compute(candidate, session, runId)
        val savedScore = scoreRepository.save(score)
        val now = System.currentTimeMillis()
        candidateRepository.save(
            candidate.copy(
                score = savedScore.totalScore,
                scoreVersion = savedScore.scoreVersion,
                reason = savedScore.reason,
                riskFlags = buildRiskFlags(session),
                lastScoredAt = now,
                updatedAt = now
            )
        )
        return savedScore
    }

    fun compute(candidate: LeaderResearchCandidate, session: LeaderPaperSession?, runId: Long?): LeaderResearchScore {
        val now = System.currentTimeMillis()
        val sourceFresh = candidate.lastSourceSeenAt?.let { now - it <= SOURCE_FRESH_MS } == true
        val paperAgeMs = session?.let { now - it.startedAt } ?: 0L
        val unknownRatio = session?.unknownRatio() ?: BigDecimal.ONE
        val filteredRatio = session?.filteredRatio ?: BigDecimal.ONE
        val copyablePnl = session?.copyablePnl ?: BigDecimal.ZERO
        val tradeCount = session?.tradeCount ?: 0

        val profitSignal = when {
            copyablePnl > BigDecimal("10") -> BigDecimal("20")
            copyablePnl > BigDecimal.ZERO -> copyablePnl.multiply(BigDecimal("2")).clamp(BigDecimal.ZERO, BigDecimal("20"))
            else -> BigDecimal.ZERO
        }
        val repeatability = BigDecimal(tradeCount).multiply(BigDecimal("1.5")).clamp(BigDecimal.ZERO, BigDecimal("15"))
        val liquidityFit = BigDecimal("10").subtract(unknownRatio.multiply(BigDecimal("10"))).clamp(BigDecimal.ZERO, BigDecimal("10"))
        val entryPriceFit = BigDecimal("10").subtract(filteredRatio.multiply(BigDecimal("10"))).clamp(BigDecimal.ZERO, BigDecimal("10"))
        val slippageRisk = if (unknownRatio <= BigDecimal("0.20")) BigDecimal("10") else BigDecimal("4")
        val holdingPeriodFit = if (paperAgeMs >= PAPER_MIN_AGE_MS) BigDecimal("5") else BigDecimal(paperAgeMs).safeDivide(BigDecimal(PAPER_MIN_AGE_MS)).multiply(BigDecimal("5"))
        val marketTypeRisk = BigDecimal("5")
        val drawdownRisk = when {
            session == null -> BigDecimal("5")
            session.maxDrawdown >= BigDecimal("-5") -> BigDecimal("10")
            session.maxDrawdown >= BigDecimal("-15") -> BigDecimal("7")
            session.maxDrawdown >= BigDecimal("-20") -> BigDecimal("3")
            else -> BigDecimal.ZERO
        }
        val exitLiquidityRisk = if (unknownRatio <= BigDecimal("0.20")) BigDecimal("5") else BigDecimal("1")
        val dataFreshness = if (sourceFresh) BigDecimal("5") else BigDecimal.ZERO
        val filterPassRate = BigDecimal("5").subtract(filteredRatio.multiply(BigDecimal("5"))).clamp(BigDecimal.ZERO, BigDecimal("5"))
        val rawTotal = listOf(
            profitSignal,
            repeatability,
            liquidityFit,
            entryPriceFit,
            slippageRisk,
            holdingPeriodFit,
            marketTypeRisk,
            drawdownRisk,
            exitLiquidityRisk,
            dataFreshness,
            filterPassRate
        ).fold(BigDecimal.ZERO, BigDecimal::add).setScale(8, RoundingMode.HALF_UP)
        val sampleCapApplied = tradeCount < PAPER_MIN_TRADES && rawTotal > SAMPLE_INSUFFICIENT_CAP
        val total = if (sampleCapApplied) SAMPLE_INSUFFICIENT_CAP else rawTotal

        val reason = listOf(
            "score_v1=$total",
            "copyable_pnl=$copyablePnl",
            "trades=$tradeCount",
            "sample_cap_applied=$sampleCapApplied",
            "unknown_quote_ratio=${unknownRatio.setScale(4, RoundingMode.HALF_UP)}",
            "filtered_ratio=${filteredRatio.setScale(4, RoundingMode.HALF_UP)}",
            "source_fresh=$sourceFresh"
        ).joinToString("; ")

        return LeaderResearchScore(
            candidateId = candidate.id ?: 0,
            runId = runId,
            scoreVersion = SCORE_VERSION,
            totalScore = total,
            profitSignal = profitSignal,
            repeatability = repeatability,
            liquidityFit = liquidityFit,
            entryPriceFit = entryPriceFit,
            slippageRisk = slippageRisk,
            holdingPeriodFit = holdingPeriodFit,
            marketTypeRisk = marketTypeRisk,
            drawdownRisk = drawdownRisk,
            exitLiquidityRisk = exitLiquidityRisk,
            dataFreshness = dataFreshness,
            filterPassRate = filterPassRate,
            sampleTradeCount = tradeCount,
            reason = reason,
            createdAt = System.currentTimeMillis()
        )
    }

    private fun buildRiskFlags(session: LeaderPaperSession?): String? {
        if (session == null) return "no_paper_session"
        val flags = mutableListOf<String>()
        if (session.maxDrawdown < BigDecimal("-15")) flags += "drawdown_gt_15"
        if (session.filteredRatio >= BigDecimal("0.50")) flags += "high_filtered_ratio"
        if (session.unknownRatio() > BigDecimal("0.20")) flags += "high_unknown_quote_exposure"
        if (session.tradeCount < 10) flags += "small_sample"
        return flags.takeIf { it.isNotEmpty() }?.joinToString(",")
    }

    private fun LeaderPaperSession.unknownRatio(): BigDecimal {
        if (openExposure <= BigDecimal.ZERO) return BigDecimal.ZERO
        return unknownValuationExposure.safeDivide(openExposure)
    }

    private fun BigDecimal.safeDivide(other: BigDecimal): BigDecimal {
        if (other.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO
        return divide(other, 8, RoundingMode.HALF_UP)
    }

    private fun BigDecimal.clamp(min: BigDecimal, max: BigDecimal): BigDecimal {
        return when {
            this < min -> min
            this > max -> max
            else -> this
        }
    }

    companion object {
        const val SCORE_VERSION = "research-copyability-v1"
        private val SAMPLE_INSUFFICIENT_CAP = BigDecimal("59")
        private const val PAPER_MIN_TRADES = 10
        private const val SOURCE_FRESH_MS = 48L * 60 * 60 * 1000
        private const val PAPER_MIN_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
