package com.wrbug.polymarketbot.service.copytrading.research

import com.wrbug.polymarketbot.entity.LeaderResearchEvent
import com.wrbug.polymarketbot.enums.LeaderResearchEventType
import com.wrbug.polymarketbot.enums.LeaderResearchNotificationStatus
import com.wrbug.polymarketbot.repository.LeaderResearchEventRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest

class LeaderResearchNotificationSummaryServiceTest {
    private val repository: LeaderResearchEventRepository = mock()
    private val service = LeaderResearchNotificationSummaryService(repository)

    @Test
    fun `builds pending safety summary`() {
        val page = PageRequest.of(0, 50)
        Mockito.`when`(
            repository.findByNotificationStatusOrderByCreatedAtAsc(
                LeaderResearchNotificationStatus.PENDING,
                page
            )
        ).thenReturn(PageImpl(events()))

        val summary = service.buildPendingSummary(limit = 50)

        assertEquals(4, summary.total)
        assertEquals(1, summary.newCandidates)
        assertEquals(1, summary.trialReady)
        assertEquals(1, summary.sourceFailures)
        assertEquals(1, summary.approvalWarnings)
        assertEquals(4, summary.lines.size)
    }

    @Test
    fun `mark pending as skipped preserves events and marks notification failure reason`() {
        val page = PageRequest.of(0, 100)
        Mockito.`when`(
            repository.findByNotificationStatusOrderByCreatedAtAsc(
                LeaderResearchNotificationStatus.PENDING,
                page
            )
        ).thenReturn(PageImpl(events()))
        Mockito.`when`(repository.save(anyEvent())).thenAnswer { it.arguments[0] }

        val summary = service.markPendingAsSkipped(reason = "operator_console_only")

        assertEquals(4, summary.total)
        Mockito.verify(repository, Mockito.times(4)).save(anyEvent())
    }

    private fun events() = listOf(
        LeaderResearchEvent(eventType = LeaderResearchEventType.CANDIDATE_DISCOVERED, reason = "new"),
        LeaderResearchEvent(eventType = LeaderResearchEventType.TRIAL_READY, reason = "ready"),
        LeaderResearchEvent(eventType = LeaderResearchEventType.SOURCE_FAILURE, reason = "source failed"),
        LeaderResearchEvent(eventType = LeaderResearchEventType.DUPLICATE_APPROVAL, reason = "duplicate")
    )

    private fun anyEvent(): LeaderResearchEvent {
        Mockito.any(LeaderResearchEvent::class.java)
        return LeaderResearchEvent(eventType = LeaderResearchEventType.CANDIDATE_DISCOVERED)
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)
}
