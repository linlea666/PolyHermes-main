package com.wrbug.polymarketbot.service.copytrading.research

import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.entity.LeaderPool
import com.wrbug.polymarketbot.entity.LeaderResearchCandidate
import com.wrbug.polymarketbot.enums.LeaderPoolStatus
import com.wrbug.polymarketbot.enums.LeaderResearchState
import com.wrbug.polymarketbot.repository.LeaderPoolRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.repository.LeaderResearchCandidateRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@Service
class LeaderResearchPoolMappingService(
    private val leaderRepository: LeaderRepository,
    private val leaderPoolRepository: LeaderPoolRepository,
    private val candidateRepository: LeaderResearchCandidateRepository
) {
    @Transactional
    fun syncCandidate(candidate: LeaderResearchCandidate): LeaderResearchCandidate {
        require(candidate.researchState != LeaderResearchState.DISCOVERED) {
            "DISCOVERED research candidates must not be synced to Leader Pool"
        }
        val now = System.currentTimeMillis()
        val leader = ensureLeader(candidate)
        val pool = ensurePool(candidate, leader)
        val badge = when (candidate.researchState) {
            LeaderResearchState.TRIAL_READY -> "RESEARCH_TRIAL_READY"
            LeaderResearchState.PAPER -> "RESEARCH_PAPER"
            LeaderResearchState.COOLDOWN -> "RESEARCH_COOLDOWN"
            else -> null
        }
        val savedPool = leaderPoolRepository.save(
            pool.copy(
                researchCandidateId = candidate.id,
                researchState = candidate.researchState,
                researchBadge = badge,
                researchSummary = candidate.reason?.take(1000),
                researchScore = candidate.score,
                researchUpdatedAt = now,
                updatedAt = now
            )
        )
        return candidateRepository.save(
            candidate.copy(
                leaderId = leader.id,
                poolId = savedPool.id,
                updatedAt = now
            )
        )
    }

    private fun ensureLeader(candidate: LeaderResearchCandidate): Leader {
        candidate.leaderId?.let { id ->
            leaderRepository.findById(id).orElse(null)?.let { return it }
        }
        leaderRepository.findByLeaderAddress(candidate.normalizedWallet)?.let { return it }
        val now = System.currentTimeMillis()
        return leaderRepository.save(
            Leader(
                leaderAddress = candidate.normalizedWallet,
                leaderName = "Research ${candidate.normalizedWallet.take(6)}...${candidate.normalizedWallet.takeLast(4)}",
                remark = "Created by Leader Research Agent. Manual enable is required before real-money copy trading.",
                createdAt = now,
                updatedAt = now
            )
        )
    }

    private fun ensurePool(candidate: LeaderResearchCandidate, leader: Leader): LeaderPool {
        leader.id?.let { leaderPoolRepository.findByLeaderId(it) }?.let { return it }
        val now = System.currentTimeMillis()
        return leaderPoolRepository.save(
            LeaderPool(
                leaderId = leader.id ?: 0,
                status = LeaderPoolStatus.WATCH,
                source = "RESEARCH_AGENT",
                score = candidate.score,
                reason = candidate.reason,
                notes = "Research agent candidate. Pool row is informational until you approve a disabled trial config.",
                suggestedFixedAmount = BigDecimal("1.00000000"),
                suggestedMaxDailyOrders = 10,
                suggestedMaxDailyLoss = BigDecimal("5.00000000"),
                suggestedMinPrice = BigDecimal("0.10000000"),
                suggestedMaxPrice = BigDecimal("0.80000000"),
                suggestedMaxPositionValue = BigDecimal("5.00000000"),
                researchCandidateId = candidate.id,
                researchState = candidate.researchState,
                researchScore = candidate.score,
                researchSummary = candidate.reason,
                researchUpdatedAt = now,
                createdAt = now,
                updatedAt = now
            )
        )
    }
}
