package com.wrbug.polymarketbot.service.copytrading.research

import com.wrbug.polymarketbot.entity.LeaderResearchEvent
import com.wrbug.polymarketbot.enums.LeaderResearchEventType
import com.wrbug.polymarketbot.enums.LeaderResearchNotificationStatus
import com.wrbug.polymarketbot.repository.LeaderResearchEventRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class LeaderResearchNotificationSummary(
    val total: Int,
    val newCandidates: Int,
    val trialReady: Int,
    val cooldowns: Int,
    val sourceFailures: Int,
    val valuationWarnings: Int,
    val approvalWarnings: Int,
    val lines: List<String>
)

@Service
class LeaderResearchNotificationSummaryService(
    private val eventRepository: LeaderResearchEventRepository
) {
    fun buildPendingSummary(limit: Int = 100): LeaderResearchNotificationSummary {
        val events = eventRepository.findByNotificationStatusOrderByCreatedAtAsc(
            LeaderResearchNotificationStatus.PENDING,
            PageRequest.of(0, limit.coerceIn(1, 500))
        ).content
        return summarize(events)
    }

    @Transactional
    fun markPendingAsSkipped(limit: Int = 100, reason: String = "operator_console_only"): LeaderResearchNotificationSummary {
        val events = eventRepository.findByNotificationStatusOrderByCreatedAtAsc(
            LeaderResearchNotificationStatus.PENDING,
            PageRequest.of(0, limit.coerceIn(1, 500))
        ).content
        val now = System.currentTimeMillis()
        events.forEach { event ->
            eventRepository.save(
                event.copy(
                    notificationStatus = LeaderResearchNotificationStatus.SKIPPED,
                    notificationError = reason,
                    notifiedAt = now
                )
            )
        }
        return summarize(events)
    }

    private fun summarize(events: List<LeaderResearchEvent>): LeaderResearchNotificationSummary {
        val lines = events.take(20).map { event ->
            "${event.eventType.name}: ${event.reason ?: event.payloadSummary ?: "no details"}"
        }
        return LeaderResearchNotificationSummary(
            total = events.size,
            newCandidates = events.count { it.eventType == LeaderResearchEventType.CANDIDATE_DISCOVERED },
            trialReady = events.count { it.eventType == LeaderResearchEventType.TRIAL_READY },
            cooldowns = events.count { it.eventType == LeaderResearchEventType.COOLDOWN },
            sourceFailures = events.count { it.eventType == LeaderResearchEventType.SOURCE_FAILURE },
            valuationWarnings = events.count { it.eventType == LeaderResearchEventType.VALUATION_STALE },
            approvalWarnings = events.count {
                it.eventType == LeaderResearchEventType.APPROVAL_REJECTED ||
                    it.eventType == LeaderResearchEventType.DUPLICATE_APPROVAL ||
                    it.eventType == LeaderResearchEventType.REAL_MONEY_ACTIVATION_FORBIDDEN
            },
            lines = lines
        )
    }
}
