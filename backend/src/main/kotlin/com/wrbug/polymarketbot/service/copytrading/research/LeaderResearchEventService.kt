package com.wrbug.polymarketbot.service.copytrading.research

import com.wrbug.polymarketbot.entity.LeaderResearchEvent
import com.wrbug.polymarketbot.enums.LeaderResearchEventType
import com.wrbug.polymarketbot.enums.LeaderResearchNotificationStatus
import com.wrbug.polymarketbot.repository.LeaderResearchEventRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service

@Service
class LeaderResearchEventService(
    private val eventRepository: LeaderResearchEventRepository
) {
    private val logger = LoggerFactory.getLogger(LeaderResearchEventService::class.java)

    fun record(
        type: LeaderResearchEventType,
        candidateId: Long? = null,
        runId: Long? = null,
        reason: String? = null,
        payloadSummary: String? = null,
        dedupeKey: String? = null,
        notificationStatus: LeaderResearchNotificationStatus = LeaderResearchNotificationStatus.PENDING
    ): LeaderResearchEvent? {
        return try {
            if (!dedupeKey.isNullOrBlank()) {
                eventRepository.findTopByDedupeKey(dedupeKey)?.let { return it }
            }
            eventRepository.save(
                LeaderResearchEvent(
                    candidateId = candidateId,
                    runId = runId,
                    eventType = type,
                    reason = reason,
                    payloadSummary = payloadSummary,
                    notificationStatus = notificationStatus,
                    dedupeKey = dedupeKey,
                    createdAt = System.currentTimeMillis()
                )
            )
        } catch (e: DataIntegrityViolationException) {
            logger.debug("Research event deduped: type={}, dedupeKey={}", type, dedupeKey)
            dedupeKey?.let { eventRepository.findTopByDedupeKey(it) }
        } catch (e: Exception) {
            logger.warn("Failed to record research event: type={}, candidateId={}, error={}", type, candidateId, e.message)
            null
        }
    }
}
