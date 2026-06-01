package com.wrbug.polymarketbot.service.copytrading.research

import com.wrbug.polymarketbot.dto.LeaderPaperSessionDto
import com.wrbug.polymarketbot.dto.LeaderResearchCandidateDetailDto
import com.wrbug.polymarketbot.dto.LeaderResearchCandidateDto
import com.wrbug.polymarketbot.dto.LeaderResearchCandidateListRequest
import com.wrbug.polymarketbot.dto.LeaderResearchCandidateListResponse
import com.wrbug.polymarketbot.dto.LeaderResearchEventDto
import com.wrbug.polymarketbot.dto.LeaderResearchSourceStateDto
import com.wrbug.polymarketbot.dto.LeaderResearchSummaryDto
import com.wrbug.polymarketbot.enums.LeaderResearchState
import com.wrbug.polymarketbot.repository.LeaderPaperPositionRepository
import com.wrbug.polymarketbot.repository.LeaderPaperSessionRepository
import com.wrbug.polymarketbot.repository.LeaderPaperTradeRepository
import com.wrbug.polymarketbot.repository.LeaderPoolRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.repository.LeaderResearchCandidateRepository
import com.wrbug.polymarketbot.repository.LeaderResearchEventRepository
import com.wrbug.polymarketbot.repository.LeaderResearchRunRepository
import com.wrbug.polymarketbot.repository.LeaderResearchScoreRepository
import com.wrbug.polymarketbot.repository.LeaderResearchSourceStateRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service

@Service
class LeaderResearchService(
    private val candidateRepository: LeaderResearchCandidateRepository,
    private val runRepository: LeaderResearchRunRepository,
    private val scoreRepository: LeaderResearchScoreRepository,
    private val sourceStateRepository: LeaderResearchSourceStateRepository,
    private val eventRepository: LeaderResearchEventRepository,
    private val paperSessionRepository: LeaderPaperSessionRepository,
    private val paperTradeRepository: LeaderPaperTradeRepository,
    private val paperPositionRepository: LeaderPaperPositionRepository,
    private val leaderRepository: LeaderRepository,
    private val leaderPoolRepository: LeaderPoolRepository,
    private val mapper: LeaderResearchMapper
) {
    fun summary(): LeaderResearchSummaryDto {
        return LeaderResearchSummaryDto(
            discoveredCount = candidateRepository.countByResearchState(LeaderResearchState.DISCOVERED),
            candidateCount = candidateRepository.countByResearchState(LeaderResearchState.CANDIDATE),
            paperCount = candidateRepository.countByResearchState(LeaderResearchState.PAPER),
            trialReadyCount = candidateRepository.countByResearchState(LeaderResearchState.TRIAL_READY),
            cooldownCount = candidateRepository.countByResearchState(LeaderResearchState.COOLDOWN),
            retiredCount = candidateRepository.countByResearchState(LeaderResearchState.RETIRED),
            activePaperSessions = candidateRepository.findByResearchStateIn(listOf(LeaderResearchState.PAPER, LeaderResearchState.TRIAL_READY)).count().toLong(),
            pendingRiskCount = candidateRepository.findByResearchStateIn(listOf(LeaderResearchState.COOLDOWN)).count().toLong(),
            lastRun = runRepository.findTopByOrderByStartedAtDesc()?.let { mapper.runDto(it) },
            sourceLimitations = mapper.sourceLimitations()
        )
    }

    fun listCandidates(request: LeaderResearchCandidateListRequest): LeaderResearchCandidateListResponse {
        val pageable = PageRequest.of(request.page.coerceAtLeast(0), request.size.coerceIn(1, 100))
        val state = request.state?.trim()?.takeIf { it.isNotBlank() }?.let { LeaderResearchState.valueOf(it.uppercase()) }
        val query = request.query?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val page = candidateRepository.search(state, query, pageable)
        val content = page.content
        return LeaderResearchCandidateListResponse(
            list = mapper.candidateDtos(content, listContext(content)),
            total = page.totalElements,
            summary = summary()
        )
    }

    private fun listContext(candidates: List<com.wrbug.polymarketbot.entity.LeaderResearchCandidate>): LeaderResearchCandidateDtoContext {
        if (candidates.isEmpty()) return LeaderResearchCandidateDtoContext()
        val leaderIds = candidates.mapNotNull { it.leaderId }.distinct()
        val poolIds = candidates.mapNotNull { it.poolId }.distinct()
        val candidateIds = candidates.mapNotNull { it.id }.distinct()
        return LeaderResearchCandidateDtoContext(
            leadersById = if (leaderIds.isEmpty()) emptyMap() else leaderRepository.findByIdIn(leaderIds)
                .mapNotNull { leader -> leader.id?.let { it to leader } }
                .toMap(),
            poolsById = if (poolIds.isEmpty()) emptyMap() else leaderPoolRepository.findByIdIn(poolIds)
                .mapNotNull { pool -> pool.id?.let { it to pool } }
                .toMap(),
            latestSessionsByCandidateId = if (candidateIds.isEmpty()) emptyMap() else paperSessionRepository.findLatestByCandidateIds(candidateIds)
                .associateBy { it.candidateId }
        )
    }

    fun detail(candidateId: Long): LeaderResearchCandidateDetailDto {
        val candidate = candidateRepository.findById(candidateId).orElseThrow { IllegalArgumentException("候选不存在") }
        val sessions = paperSessionRepository.findByCandidateIdOrderByStartedAtDesc(candidateId)
        val latestSession = sessions.firstOrNull()
        val trades = latestSession?.id?.let {
            paperTradeRepository.findBySessionIdOrderByEventTimeDesc(it, PageRequest.of(0, 100)).content
        }.orEmpty()
        val positions = latestSession?.id?.let { paperPositionRepository.findBySessionIdOrderByUpdatedAtDesc(it) }.orEmpty()
        return LeaderResearchCandidateDetailDto(
            candidate = mapper.candidateDto(candidate, latestSession),
            latestScore = scoreRepository.findTopByCandidateIdOrderByCreatedAtDesc(candidateId)?.let { mapper.scoreDto(it) },
            paperSessions = sessions.map { mapper.paperSessionDto(it) },
            paperTrades = trades.map { mapper.paperTradeDto(it) },
            paperPositions = positions.map { mapper.paperPositionDto(it) },
            events = eventRepository.findByCandidateIdOrderByCreatedAtDesc(candidateId, PageRequest.of(0, 100)).content.map { mapper.eventDto(it) }
        )
    }

    fun sourceHealth(): List<LeaderResearchSourceStateDto> {
        return sourceStateRepository.findAllByOrderByUpdatedAtDesc().map { mapper.sourceStateDto(it) }
    }

    fun events(page: Int, size: Int): List<LeaderResearchEventDto> {
        return eventRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, 100)))
            .content
            .map { mapper.eventDto(it) }
    }

    fun paperSessions(candidateId: Long): List<LeaderPaperSessionDto> {
        return paperSessionRepository.findByCandidateIdOrderByStartedAtDesc(candidateId).map { mapper.paperSessionDto(it) }
    }
}
