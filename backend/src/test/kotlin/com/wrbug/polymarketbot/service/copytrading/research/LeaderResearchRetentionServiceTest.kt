package com.wrbug.polymarketbot.service.copytrading.research

import com.wrbug.polymarketbot.entity.LeaderPaperSession
import com.wrbug.polymarketbot.enums.LeaderPaperProcessingStatus
import com.wrbug.polymarketbot.enums.LeaderPaperSessionStatus
import com.wrbug.polymarketbot.repository.LeaderActivityEventRepository
import com.wrbug.polymarketbot.repository.LeaderPaperSessionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest

class LeaderResearchRetentionServiceTest {
    private val activityRepository: LeaderActivityEventRepository = mock()
    private val sessionRepository: LeaderPaperSessionRepository = mock()

    @Test
    fun `cleanup deletes only terminal activity events and terminal paper sessions`() {
        val staleSessions = listOf(
            LeaderPaperSession(id = 1, candidateId = 1, status = LeaderPaperSessionStatus.COMPLETED),
            LeaderPaperSession(id = 2, candidateId = 2, status = LeaderPaperSessionStatus.FAILED)
        )
        val terminalActivityStatuses = listOf(
            LeaderPaperProcessingStatus.PROCESSED,
            LeaderPaperProcessingStatus.FILTERED,
            LeaderPaperProcessingStatus.FAILED
        )
        val terminalSessionStatuses = listOf(
            LeaderPaperSessionStatus.COMPLETED,
            LeaderPaperSessionStatus.FAILED
        )
        val now = 1_000_000_000L
        val activityCutoff = -6_776_000_000L
        val paperCutoff = -14_552_000_000L
        val paperPage = PageRequest.of(0, 100)
        val service = LeaderResearchRetentionService(
            activityEventRepository = activityRepository,
            paperSessionRepository = sessionRepository,
            enabled = true,
            activityRetentionDays = 90,
            paperSessionRetentionDays = 180,
            maxPaperSessionsPerRun = 100
        )
        Mockito.`when`(
            activityRepository.deleteByEventTimeLessThanAndPaperProcessingStatusIn(
                activityCutoff,
                terminalActivityStatuses
            )
        ).thenReturn(7)
        Mockito.`when`(
            sessionRepository.findByUpdatedAtLessThanAndStatusIn(
                paperCutoff,
                terminalSessionStatuses,
                paperPage
            )
        ).thenReturn(PageImpl(staleSessions))

        val result = service.cleanup(now = now)

        assertEquals(7, result.deletedActivityEvents)
        assertEquals(2, result.deletedPaperSessions)
        Mockito.verify(activityRepository).deleteByEventTimeLessThanAndPaperProcessingStatusIn(
            activityCutoff,
            terminalActivityStatuses
        )
        Mockito.verify(sessionRepository).deleteAll(staleSessions)
    }

    @Test
    fun `disabled cleanup does nothing`() {
        val service = LeaderResearchRetentionService(
            activityEventRepository = activityRepository,
            paperSessionRepository = sessionRepository,
            enabled = false,
            activityRetentionDays = 90,
            paperSessionRetentionDays = 180,
            maxPaperSessionsPerRun = 100
        )

        val result = service.cleanup()

        assertEquals(0, result.deletedActivityEvents)
        assertEquals(0, result.deletedPaperSessions)
        Mockito.verifyNoInteractions(activityRepository, sessionRepository)
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)
}
