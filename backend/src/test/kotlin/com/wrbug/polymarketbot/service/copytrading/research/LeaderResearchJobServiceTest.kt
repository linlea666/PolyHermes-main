package com.wrbug.polymarketbot.service.copytrading.research

import com.wrbug.polymarketbot.entity.LeaderActivityEvent
import com.wrbug.polymarketbot.entity.LeaderResearchRun
import com.wrbug.polymarketbot.enums.LeaderResearchRunStatus
import com.wrbug.polymarketbot.enums.LeaderResearchSourceStatus
import com.wrbug.polymarketbot.enums.LeaderResearchSourceType
import com.wrbug.polymarketbot.enums.LeaderResearchState
import com.wrbug.polymarketbot.enums.LeaderResearchTriggerType
import com.wrbug.polymarketbot.repository.LeaderActivityEventRepository
import com.wrbug.polymarketbot.repository.LeaderResearchCandidateRepository
import com.wrbug.polymarketbot.repository.LeaderResearchRunRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LeaderResearchJobServiceTest {
    private val runRepository: LeaderResearchRunRepository = mock()
    private val activityEventRepository: LeaderActivityEventRepository = mock()
    private val candidateRepository: LeaderResearchCandidateRepository = mock()
    private val sourceService: LeaderResearchSourceService = mock()
    private val paperTradingService: LeaderPaperTradingService = mock()
    private val scoringService: LeaderResearchScoringService = mock()
    private val stateMachine: LeaderResearchStateMachine = mock()
    private val eventService: LeaderResearchEventService = mock()

    @Test
    fun `successful run writes run record counts cursor and processing phases`() {
        val service = service()
        stubRunSaves()
        Mockito.`when`(sourceService.discoverCandidates(1L)).thenReturn(
            listOf(LeaderResearchSourceRunResult(LeaderResearchSourceType.WATCHLIST, emptyList(), LeaderResearchSourceStatus.SUCCESS))
        )
        LeaderResearchState.values().forEach { state ->
            Mockito.`when`(candidateRepository.countByResearchState(state)).thenReturn(2)
        }
        Mockito.`when`(activityEventRepository.findTopByOrderByEventTimeDesc()).thenReturn(
            LeaderActivityEvent(source = "ACTIVITY_DERIVED", stableEventKey = "cursor-1", eventTime = 123, rawPayloadHash = "hash")
        )

        val run = service.runOnce(dryRun = false, triggerType = LeaderResearchTriggerType.MANUAL)

        assertEquals(LeaderResearchRunStatus.SUCCESS, run.status)
        assertFalse(run.partialFailure)
        assertTrue(run.sourceCountsJson!!.contains("\"WATCHLIST\":0"))
        assertTrue(run.candidateCountsJson!!.contains("\"PAPER\":2"))
        assertEquals("123:cursor-1", run.lastEventCursor)
        Mockito.verify(scoringService, Mockito.times(2)).scoreAll(run.id)
        Mockito.verify(stateMachine, Mockito.times(2)).advanceAll(run.id)
        Mockito.verify(paperTradingService).processPaperCandidates(run.id)
    }

    @Test
    fun `degraded source marks run partial failure without aborting run`() {
        val service = service()
        stubRunSaves()
        Mockito.`when`(sourceService.discoverCandidates(1L)).thenReturn(
            listOf(
                LeaderResearchSourceRunResult(LeaderResearchSourceType.WATCHLIST, emptyList(), LeaderResearchSourceStatus.SUCCESS),
                LeaderResearchSourceRunResult(
                    LeaderResearchSourceType.ACTIVITY_DERIVED,
                    emptyList(),
                    LeaderResearchSourceStatus.DEGRADED,
                    errorClass = "DataApiFailure",
                    errorMessage = "timeout"
                )
            )
        )

        val run = service.runOnce(dryRun = false, triggerType = LeaderResearchTriggerType.MANUAL)

        assertEquals(LeaderResearchRunStatus.PARTIAL_FAILURE, run.status)
        assertTrue(run.partialFailure)
        Mockito.verify(paperTradingService).processPaperCandidates(run.id)
    }

    @Test
    fun `expected disabled sources do not mark run partial failure`() {
        val service = service()
        stubRunSaves()
        Mockito.`when`(sourceService.discoverCandidates(1L)).thenReturn(
            listOf(
                LeaderResearchSourceRunResult(LeaderResearchSourceType.WATCHLIST, emptyList(), LeaderResearchSourceStatus.SUCCESS),
                LeaderResearchSourceRunResult(
                    LeaderResearchSourceType.ACTIVITY_DERIVED,
                    emptyList(),
                    LeaderResearchSourceStatus.DEGRADED,
                    limitation = "Global activity capture is disabled",
                    expectedLimitation = true
                ),
                LeaderResearchSourceRunResult(
                    LeaderResearchSourceType.GLOBAL_ACTIVITY_CAPTURE,
                    emptyList(),
                    LeaderResearchSourceStatus.DISABLED,
                    limitation = "Global activity capture is disabled",
                    expectedLimitation = true
                ),
                LeaderResearchSourceRunResult(
                    LeaderResearchSourceType.PUBLIC_LEADERBOARD,
                    emptyList(),
                    LeaderResearchSourceStatus.DISABLED,
                    limitation = "Public leaderboard source is intentionally disabled",
                    expectedLimitation = true
                )
            )
        )

        val run = service.runOnce(dryRun = false, triggerType = LeaderResearchTriggerType.MANUAL)

        assertEquals(LeaderResearchRunStatus.SUCCESS, run.status)
        assertFalse(run.partialFailure)
        Mockito.verify(paperTradingService).processPaperCandidates(run.id)
    }

    @Test
    fun `preview run does not score advance or paper trade`() {
        val service = service()
        stubRunSaves()
        Mockito.`when`(sourceService.previewCandidates()).thenReturn(
            listOf(LeaderResearchSourceRunResult(LeaderResearchSourceType.WATCHLIST, emptyList(), LeaderResearchSourceStatus.SUCCESS))
        )

        val run = service.runOnce(dryRun = true, triggerType = LeaderResearchTriggerType.PREVIEW)

        assertEquals(LeaderResearchRunStatus.SUCCESS, run.status)
        assertTrue(run.dryRun)
        Mockito.verify(sourceService).previewCandidates()
        Mockito.verifyNoInteractions(scoringService, stateMachine, paperTradingService)
    }

    @Test
    fun `async run returns running record before background execution completes`() {
        val service = service()
        val executor = Executors.newSingleThreadExecutor()
        service.runExecutor = executor
        stubRunSaves()
        Mockito.`when`(sourceService.discoverCandidates(1L)).thenAnswer {
            Thread.sleep(100)
            emptyList<LeaderResearchSourceRunResult>()
        }

        val run = service.startAsync(dryRun = false, triggerType = LeaderResearchTriggerType.MANUAL)

        assertEquals(LeaderResearchRunStatus.RUNNING, run.status)
        executor.shutdown()
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        Mockito.verify(sourceService).discoverCandidates(run.id)
    }

    @Test
    fun `overlap guard records skipped run while outer run continues`() {
        lateinit var service: LeaderResearchJobService
        val savedRuns = mutableListOf<LeaderResearchRun>()
        stubRunSaves(savedRuns)
        service = service()
        Mockito.`when`(sourceService.discoverCandidates(1L)).thenAnswer {
            val skipped = service.runOnce(dryRun = false, triggerType = LeaderResearchTriggerType.MANUAL)
            assertEquals(LeaderResearchRunStatus.SKIPPED, skipped.status)
            emptyList<LeaderResearchSourceRunResult>()
        }.thenReturn(emptyList())

        val outer = service.runOnce(dryRun = false, triggerType = LeaderResearchTriggerType.MANUAL)

        assertEquals(LeaderResearchRunStatus.SUCCESS, outer.status)
        assertNotNull(savedRuns.firstOrNull { it.status == LeaderResearchRunStatus.SKIPPED })
        assertEquals("another_run_in_progress", savedRuns.first { it.status == LeaderResearchRunStatus.SKIPPED }.skippedReason)
    }

    private fun service() = LeaderResearchJobService(
        runRepository = runRepository,
        activityEventRepository = activityEventRepository,
        candidateRepository = candidateRepository,
        sourceService = sourceService,
        paperTradingService = paperTradingService,
        scoringService = scoringService,
        stateMachine = stateMachine,
        eventService = eventService,
        scheduledEnabled = false
    )

    private fun stubRunSaves(savedRuns: MutableList<LeaderResearchRun> = mutableListOf()) {
        var nextId = 1L
        Mockito.`when`(runRepository.save(anyRun())).thenAnswer {
            val incoming = it.arguments[0] as LeaderResearchRun
            incoming.copy(id = incoming.id ?: nextId++).also { savedRuns += it }
        }
    }

    private fun anyRun(): LeaderResearchRun {
        Mockito.any(LeaderResearchRun::class.java)
        return LeaderResearchRun()
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)
}
