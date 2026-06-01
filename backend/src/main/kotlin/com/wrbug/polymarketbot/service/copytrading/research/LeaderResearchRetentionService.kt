package com.wrbug.polymarketbot.service.copytrading.research

import com.wrbug.polymarketbot.enums.LeaderPaperProcessingStatus
import com.wrbug.polymarketbot.enums.LeaderPaperSessionStatus
import com.wrbug.polymarketbot.repository.LeaderActivityEventRepository
import com.wrbug.polymarketbot.repository.LeaderPaperSessionRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class LeaderResearchRetentionResult(
    val deletedActivityEvents: Long,
    val deletedPaperSessions: Long
)

@Service
class LeaderResearchRetentionService(
    private val activityEventRepository: LeaderActivityEventRepository,
    private val paperSessionRepository: LeaderPaperSessionRepository,
    @Value("\${leader.research.retention.enabled:true}") private val enabled: Boolean,
    @Value("\${leader.research.retention.activity-days:90}") private val activityRetentionDays: Long,
    @Value("\${leader.research.retention.paper-session-days:180}") private val paperSessionRetentionDays: Long,
    @Value("\${leader.research.retention.max-paper-sessions-per-run:100}") private val maxPaperSessionsPerRun: Int
) {
    @Scheduled(cron = "\${leader.research.retention.cron:0 17 3 * * *}")
    fun scheduledCleanup() {
        if (!enabled) return
        cleanup()
    }

    @Transactional
    fun cleanup(now: Long = System.currentTimeMillis()): LeaderResearchRetentionResult {
        if (!enabled) return LeaderResearchRetentionResult(0, 0)
        val activityCutoff = now - activityRetentionDays.coerceAtLeast(7) * MILLIS_PER_DAY
        val paperCutoff = now - paperSessionRetentionDays.coerceAtLeast(30) * MILLIS_PER_DAY
        val deletedActivities = activityEventRepository.deleteByEventTimeLessThanAndPaperProcessingStatusIn(
            activityCutoff,
            listOf(
                LeaderPaperProcessingStatus.PROCESSED,
                LeaderPaperProcessingStatus.FILTERED,
                LeaderPaperProcessingStatus.FAILED
            )
        )
        val staleSessions = paperSessionRepository.findByUpdatedAtLessThanAndStatusIn(
            paperCutoff,
            listOf(LeaderPaperSessionStatus.COMPLETED, LeaderPaperSessionStatus.FAILED),
            PageRequest.of(0, maxPaperSessionsPerRun.coerceIn(1, 1000))
        )
        paperSessionRepository.deleteAll(staleSessions.content)
        return LeaderResearchRetentionResult(
            deletedActivityEvents = deletedActivities,
            deletedPaperSessions = staleSessions.content.size.toLong()
        )
    }

    companion object {
        private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
    }
}
