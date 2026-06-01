package com.wrbug.polymarketbot.service.copytrading.research

import com.wrbug.polymarketbot.entity.LeaderResearchSourceState
import com.wrbug.polymarketbot.enums.LeaderResearchSourceStatus
import com.wrbug.polymarketbot.enums.LeaderResearchSourceType
import com.wrbug.polymarketbot.repository.LeaderResearchSourceStateRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class LeaderResearchSourceHealthService(
    private val sourceStateRepository: LeaderResearchSourceStateRepository
) {
    @Transactional
    fun record(
        sourceType: LeaderResearchSourceType,
        status: LeaderResearchSourceStatus,
        candidateCount: Int = 0,
        errorClass: String? = null,
        errorMessage: String? = null,
        disabledReason: String? = null,
        stale: Boolean = false,
        lastCursor: String? = null,
        now: Long = System.currentTimeMillis()
    ): LeaderResearchSourceState {
        val existing = sourceStateRepository.findBySourceType(sourceType)
        val failedLike = status == LeaderResearchSourceStatus.FAILURE ||
            status == LeaderResearchSourceStatus.DEGRADED ||
            status == LeaderResearchSourceStatus.STALE
        val nextDisabledReason = when {
            disabledReason != null -> disabledReason
            status == LeaderResearchSourceStatus.SUCCESS -> null
            else -> existing?.disabledReason
        }
        val state = existing?.copy(
            status = status,
            lastSuccessAt = if (status == LeaderResearchSourceStatus.SUCCESS) now else existing.lastSuccessAt,
            lastFailureAt = if (failedLike) now else existing.lastFailureAt,
            lastRunAt = now,
            lastCandidateCount = candidateCount,
            errorClass = errorClass,
            errorMessage = errorMessage,
            stale = stale || status == LeaderResearchSourceStatus.STALE,
            disabledReason = nextDisabledReason,
            lastCursor = lastCursor ?: existing.lastCursor,
            updatedAt = now
        ) ?: LeaderResearchSourceState(
            sourceType = sourceType,
            status = status,
            lastSuccessAt = if (status == LeaderResearchSourceStatus.SUCCESS) now else null,
            lastFailureAt = if (failedLike) now else null,
            lastRunAt = now,
            lastCandidateCount = candidateCount,
            errorClass = errorClass,
            errorMessage = errorMessage,
            stale = stale || status == LeaderResearchSourceStatus.STALE,
            disabledReason = nextDisabledReason,
            lastCursor = lastCursor,
            createdAt = now,
            updatedAt = now
        )
        return sourceStateRepository.save(state)
    }
}
