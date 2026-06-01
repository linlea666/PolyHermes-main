package com.wrbug.polymarketbot.service.copytrading.research

import com.wrbug.polymarketbot.entity.LeaderResearchSourceState
import com.wrbug.polymarketbot.enums.LeaderResearchSourceStatus
import com.wrbug.polymarketbot.enums.LeaderResearchSourceType
import com.wrbug.polymarketbot.repository.LeaderResearchSourceStateRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class LeaderResearchSourceHealthServiceTest {
    private val repository: LeaderResearchSourceStateRepository = mock()
    private val service = LeaderResearchSourceHealthService(repository)

    @Test
    fun `records disabled websocket capture state`() {
        Mockito.`when`(repository.findBySourceType(LeaderResearchSourceType.GLOBAL_ACTIVITY_CAPTURE)).thenReturn(null)
        Mockito.`when`(repository.save(anyState())).thenAnswer { it.arguments[0] }

        val state = service.record(
            sourceType = LeaderResearchSourceType.GLOBAL_ACTIVITY_CAPTURE,
            status = LeaderResearchSourceStatus.DISABLED,
            disabledReason = "Global activity capture is disabled",
            now = 100
        )

        assertEquals(LeaderResearchSourceStatus.DISABLED, state.status)
        assertEquals("Global activity capture is disabled", state.disabledReason)
        assertEquals(100, state.lastRunAt)
    }

    @Test
    fun `degraded source keeps failure timestamp and error`() {
        Mockito.`when`(repository.findBySourceType(LeaderResearchSourceType.ACTIVITY_DERIVED)).thenReturn(null)
        Mockito.`when`(repository.save(anyState())).thenAnswer { it.arguments[0] }

        val state = service.record(
            sourceType = LeaderResearchSourceType.ACTIVITY_DERIVED,
            status = LeaderResearchSourceStatus.DEGRADED,
            errorClass = "DataApiFailure",
            errorMessage = "429",
            now = 200
        )

        assertEquals(LeaderResearchSourceStatus.DEGRADED, state.status)
        assertEquals(200, state.lastFailureAt)
        assertEquals("DataApiFailure", state.errorClass)
        assertEquals("429", state.errorMessage)
    }

    @Test
    fun `success clears disabled reason but preserves cursor update`() {
        val existing = LeaderResearchSourceState(
            sourceType = LeaderResearchSourceType.GLOBAL_ACTIVITY_CAPTURE,
            status = LeaderResearchSourceStatus.DISABLED,
            disabledReason = "disabled",
            lastCursor = "old"
        )
        Mockito.`when`(repository.findBySourceType(LeaderResearchSourceType.GLOBAL_ACTIVITY_CAPTURE)).thenReturn(existing)
        Mockito.`when`(repository.save(anyState())).thenAnswer { it.arguments[0] }

        val state = service.record(
            sourceType = LeaderResearchSourceType.GLOBAL_ACTIVITY_CAPTURE,
            status = LeaderResearchSourceStatus.SUCCESS,
            candidateCount = 3,
            lastCursor = "new",
            now = 300
        )

        assertEquals(LeaderResearchSourceStatus.SUCCESS, state.status)
        assertEquals(300, state.lastSuccessAt)
        assertEquals(3, state.lastCandidateCount)
        assertEquals("new", state.lastCursor)
        assertNull(state.disabledReason)
    }

    @Test
    fun `stale status is flagged stale`() {
        Mockito.`when`(repository.findBySourceType(LeaderResearchSourceType.ACTIVITY_DERIVED)).thenReturn(null)
        Mockito.`when`(repository.save(anyState())).thenAnswer { it.arguments[0] }

        val state = service.record(
            sourceType = LeaderResearchSourceType.ACTIVITY_DERIVED,
            status = LeaderResearchSourceStatus.STALE
        )

        assertTrue(state.stale)
    }

    private fun anyState(): LeaderResearchSourceState {
        Mockito.any(LeaderResearchSourceState::class.java)
        return LeaderResearchSourceState(sourceType = LeaderResearchSourceType.ACTIVITY_DERIVED)
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)
}
