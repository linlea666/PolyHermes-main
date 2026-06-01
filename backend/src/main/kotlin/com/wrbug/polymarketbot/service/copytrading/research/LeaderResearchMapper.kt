package com.wrbug.polymarketbot.service.copytrading.research

import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.entity.*
import com.wrbug.polymarketbot.enums.LeaderPoolStatus
import com.wrbug.polymarketbot.repository.LeaderPoolRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.repository.LeaderResearchSourceStateRepository
import org.springframework.stereotype.Component
import java.math.BigDecimal

data class LeaderResearchCandidateDtoContext(
    val leadersById: Map<Long, Leader> = emptyMap(),
    val poolsById: Map<Long, LeaderPool> = emptyMap(),
    val latestSessionsByCandidateId: Map<Long, LeaderPaperSession> = emptyMap()
)

@Component
class LeaderResearchMapper(
    private val leaderRepository: LeaderRepository,
    private val leaderPoolRepository: LeaderPoolRepository,
    private val sourceStateRepository: LeaderResearchSourceStateRepository
) {
    fun runDto(run: LeaderResearchRun): LeaderResearchRunDto {
        return LeaderResearchRunDto(
            id = run.id ?: 0,
            status = run.status.name,
            triggerType = run.triggerType.name,
            dryRun = run.dryRun,
            startedAt = run.startedAt,
            finishedAt = run.finishedAt,
            durationMs = run.durationMs,
            sourceCountsJson = run.sourceCountsJson,
            candidateCountsJson = run.candidateCountsJson,
            partialFailure = run.partialFailure,
            skippedReason = run.skippedReason,
            errorClass = run.errorClass,
            errorMessage = run.errorMessage
        )
    }

    fun candidateDto(candidate: LeaderResearchCandidate, latestSession: LeaderPaperSession? = null): LeaderResearchCandidateDto {
        val leader = candidate.leaderId?.let { leaderRepository.findById(it).orElse(null) }
        val pool = candidate.poolId?.let { leaderPoolRepository.findById(it).orElse(null) }
        return candidateDto(candidate, leader, pool, latestSession)
    }

    fun candidateDtos(candidates: List<LeaderResearchCandidate>, context: LeaderResearchCandidateDtoContext): List<LeaderResearchCandidateDto> {
        return candidates.map { candidate ->
            val candidateId = candidate.id
            candidateDto(
                candidate = candidate,
                leader = candidate.leaderId?.let { context.leadersById[it] },
                pool = candidate.poolId?.let { context.poolsById[it] },
                latestSession = candidateId?.let { context.latestSessionsByCandidateId[it] }
            )
        }
    }

    private fun candidateDto(
        candidate: LeaderResearchCandidate,
        leader: Leader?,
        pool: LeaderPool?,
        latestSession: LeaderPaperSession?
    ): LeaderResearchCandidateDto {
        return LeaderResearchCandidateDto(
            id = candidate.id ?: 0,
            normalizedWallet = candidate.normalizedWallet,
            leaderId = candidate.leaderId,
            leaderName = leader?.leaderName,
            poolId = candidate.poolId,
            poolStatus = pool?.status?.name,
            suggestedFixedAmount = pool?.suggestedFixedAmount?.strip(),
            suggestedMaxDailyLoss = pool?.suggestedMaxDailyLoss?.strip(),
            suggestedMaxDailyOrders = pool?.suggestedMaxDailyOrders,
            suggestedMinPrice = pool?.suggestedMinPrice?.strip(),
            suggestedMaxPrice = pool?.suggestedMaxPrice?.strip(),
            suggestedMaxPositionValue = pool?.suggestedMaxPositionValue?.strip(),
            researchState = candidate.researchState.name,
            source = candidate.source,
            sourceRank = candidate.sourceRank,
            score = candidate.score?.strip(),
            scoreVersion = candidate.scoreVersion,
            reason = candidate.reason,
            riskFlags = splitFlags(candidate.riskFlags),
            locked = candidate.locked,
            agentOwned = candidate.agentOwned,
            provenance = candidate.provenance.name,
            sourceEvidence = candidate.sourceEvidence,
            firstSeenAt = candidate.firstSeenAt,
            lastSourceSeenAt = candidate.lastSourceSeenAt,
            lastScoredAt = candidate.lastScoredAt,
            cooldownUntil = candidate.cooldownUntil,
            cooldownCount = candidate.cooldownCount,
            trialReadyAt = candidate.trialReadyAt,
            retiredAt = candidate.retiredAt,
            lastPaperSessionId = candidate.lastPaperSessionId,
            latestPaperSession = latestSession?.let { paperSessionDto(it) }
        )
    }

    fun scoreDto(score: LeaderResearchScore): LeaderResearchScoreDto {
        return LeaderResearchScoreDto(
            id = score.id ?: 0,
            candidateId = score.candidateId,
            runId = score.runId,
            scoreVersion = score.scoreVersion,
            totalScore = score.totalScore.strip(),
            profitSignal = score.profitSignal.strip(),
            repeatability = score.repeatability.strip(),
            liquidityFit = score.liquidityFit.strip(),
            entryPriceFit = score.entryPriceFit.strip(),
            slippageRisk = score.slippageRisk.strip(),
            holdingPeriodFit = score.holdingPeriodFit.strip(),
            marketTypeRisk = score.marketTypeRisk.strip(),
            drawdownRisk = score.drawdownRisk.strip(),
            exitLiquidityRisk = score.exitLiquidityRisk.strip(),
            dataFreshness = score.dataFreshness.strip(),
            filterPassRate = score.filterPassRate.strip(),
            sampleTradeCount = score.sampleTradeCount,
            reason = score.reason,
            createdAt = score.createdAt
        )
    }

    fun paperSessionDto(session: LeaderPaperSession): LeaderPaperSessionDto {
        return LeaderPaperSessionDto(
            id = session.id ?: 0,
            candidateId = session.candidateId,
            status = session.status.name,
            startedAt = session.startedAt,
            endedAt = session.endedAt,
            tradeCount = session.tradeCount,
            filteredCount = session.filteredCount,
            openExposure = session.openExposure.strip(),
            totalRealizedPnl = session.totalRealizedPnl.strip(),
            totalUnrealizedPnl = session.totalUnrealizedPnl.strip(),
            copyablePnl = session.copyablePnl.strip(),
            maxDrawdown = session.maxDrawdown.strip(),
            unknownValuationExposure = session.unknownValuationExposure.strip(),
            confirmedZeroExposure = session.confirmedZeroExposure.strip(),
            filteredRatio = session.filteredRatio.strip(),
            lastProcessedEventTime = session.lastProcessedEventTime,
            scoreSnapshot = session.scoreSnapshot?.strip()
        )
    }

    fun paperTradeDto(trade: LeaderPaperTrade): LeaderPaperTradeDto {
        return LeaderPaperTradeDto(
            id = trade.id ?: 0,
            sessionId = trade.sessionId,
            candidateId = trade.candidateId,
            activityEventId = trade.activityEventId,
            leaderTradeId = trade.leaderTradeId,
            marketId = trade.marketId,
            marketTitle = trade.marketTitle,
            marketSlug = trade.marketSlug,
            side = trade.side,
            outcome = trade.outcome,
            outcomeIndex = trade.outcomeIndex,
            leaderPrice = trade.leaderPrice?.strip(),
            leaderSize = trade.leaderSize?.strip(),
            simulatedPrice = trade.simulatedPrice?.strip(),
            simulatedSize = trade.simulatedSize?.strip(),
            simulatedAmount = trade.simulatedAmount?.strip(),
            fillAssumption = trade.fillAssumption.name,
            quoteConfidence = trade.quoteConfidence.name,
            quoteSource = trade.quoteSource,
            quoteTimestamp = trade.quoteTimestamp,
            filterResult = trade.filterResult.name,
            filterReason = trade.filterReason,
            valuationStatus = trade.valuationStatus.name,
            realizedPnl = trade.realizedPnl?.strip(),
            eventTime = trade.eventTime,
            createdAt = trade.createdAt
        )
    }

    fun paperPositionDto(position: LeaderPaperPosition): LeaderPaperPositionDto {
        return LeaderPaperPositionDto(
            id = position.id ?: 0,
            sessionId = position.sessionId,
            candidateId = position.candidateId,
            marketId = position.marketId,
            outcome = position.outcome,
            outcomeIndex = position.outcomeIndex,
            quantity = position.quantity.strip(),
            cost = position.cost.strip(),
            avgPrice = position.avgPrice.strip(),
            currentPrice = position.currentPrice?.strip(),
            currentValue = position.currentValue.strip(),
            realizedPnl = position.realizedPnl.strip(),
            unrealizedPnl = position.unrealizedPnl.strip(),
            valuationStatus = position.valuationStatus.name,
            quoteConfidence = position.quoteConfidence.name,
            quoteSource = position.quoteSource,
            quoteTimestamp = position.quoteTimestamp,
            updatedAt = position.updatedAt
        )
    }

    fun sourceStateDto(state: LeaderResearchSourceState): LeaderResearchSourceStateDto {
        return LeaderResearchSourceStateDto(
            sourceType = state.sourceType.name,
            status = state.status.name,
            lastSuccessAt = state.lastSuccessAt,
            lastFailureAt = state.lastFailureAt,
            lastRunAt = state.lastRunAt,
            lastCandidateCount = state.lastCandidateCount,
            errorClass = state.errorClass,
            errorMessage = state.errorMessage,
            stale = state.stale,
            disabledReason = state.disabledReason,
            lastCursor = state.lastCursor,
            updatedAt = state.updatedAt
        )
    }

    fun eventDto(event: LeaderResearchEvent): LeaderResearchEventDto {
        return LeaderResearchEventDto(
            id = event.id ?: 0,
            candidateId = event.candidateId,
            runId = event.runId,
            eventType = event.eventType.name,
            reason = event.reason,
            payloadSummary = event.payloadSummary,
            notificationStatus = event.notificationStatus.name,
            notificationError = event.notificationError,
            dedupeKey = event.dedupeKey,
            createdAt = event.createdAt,
            notifiedAt = event.notifiedAt
        )
    }

    fun sourceLimitations(): List<String> {
        return sourceStateRepository.findAllByOrderByUpdatedAtDesc()
            .filter { it.stale || it.status.name == "DISABLED" || !it.disabledReason.isNullOrBlank() }
            .map { "${it.sourceType.name}: ${it.disabledReason ?: it.errorMessage ?: it.status.name}" }
    }

    fun isTrialOrActive(status: LeaderPoolStatus?): Boolean {
        return status == LeaderPoolStatus.TRIAL || status == LeaderPoolStatus.ACTIVE
    }

    private fun splitFlags(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(",", "\n", ";").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun BigDecimal.strip(): String = stripTrailingZeros().toPlainString()
}
