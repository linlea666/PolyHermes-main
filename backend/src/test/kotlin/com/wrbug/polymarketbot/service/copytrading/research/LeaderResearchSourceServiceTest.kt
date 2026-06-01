package com.wrbug.polymarketbot.service.copytrading.research

import com.google.gson.Gson
import com.wrbug.polymarketbot.api.PolymarketDataApi
import com.wrbug.polymarketbot.api.UserActivityResponse
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.entity.LeaderActivityEvent
import com.wrbug.polymarketbot.entity.LeaderPool
import com.wrbug.polymarketbot.entity.LeaderResearchCandidate
import com.wrbug.polymarketbot.entity.SystemConfig
import com.wrbug.polymarketbot.enums.LeaderCandidateProvenance
import com.wrbug.polymarketbot.enums.LeaderResearchSourceStatus
import com.wrbug.polymarketbot.enums.LeaderResearchSourceType
import com.wrbug.polymarketbot.repository.LeaderActivityEventRepository
import com.wrbug.polymarketbot.repository.LeaderPoolRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.repository.LeaderResearchCandidateRepository
import com.wrbug.polymarketbot.repository.SystemConfigRepository
import com.wrbug.polymarketbot.util.RetrofitFactory
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import retrofit2.Response

class LeaderResearchSourceServiceTest {
    private val candidateRepository: LeaderResearchCandidateRepository = mock()
    private val leaderRepository: LeaderRepository = mock()
    private val leaderPoolRepository: LeaderPoolRepository = mock()
    private val activityEventRepository: LeaderActivityEventRepository = mock()
    private val sourceHealthService: LeaderResearchSourceHealthService = mock()
    private val systemConfigRepository: SystemConfigRepository = mock()
    private val retrofitFactory: RetrofitFactory = mock()
    private val eventService: LeaderResearchEventService = mock()
    private val ingestionService = LeaderActivityIngestionService(mock(), Gson())
    private val dataApi: PolymarketDataApi = mock()

    @Test
    fun `discover candidates handles empty disabled invalid duplicate existing leader and locked protection`() {
        val watchWallet = "0x1111111111111111111111111111111111111111"
        val existingWallet = "0x2222222222222222222222222222222222222222"
        val activityWallet = "0x3333333333333333333333333333333333333333"
        val locked = LeaderResearchCandidate(
            id = 30L,
            normalizedWallet = activityWallet,
            source = "manual",
            provenance = LeaderCandidateProvenance.MANUAL_LOCKED,
            locked = true,
            sourceEvidence = "manual note"
        )
        stubCommonDataApi(success = true)
        Mockito.`when`(systemConfigRepository.findByConfigKey(LeaderResearchSourceService.CONFIG_WATCHLIST))
            .thenReturn(SystemConfig(configKey = LeaderResearchSourceService.CONFIG_WATCHLIST, configValue = "$watchWallet,not-a-wallet,$watchWallet"))
        Mockito.`when`(leaderRepository.findByLeaderAddress(watchWallet)).thenReturn(null)
        Mockito.`when`(leaderRepository.findByLeaderAddress(activityWallet)).thenReturn(null)
        Mockito.`when`(leaderRepository.findAllByOrderByCreatedAtAsc())
            .thenReturn(listOf(Leader(id = 2L, leaderAddress = existingWallet, leaderName = "known")))
        Mockito.`when`(leaderPoolRepository.findByLeaderId(2L)).thenReturn(LeaderPool(id = 20L, leaderId = 2L))
        Mockito.`when`(activityEventRepository.findByUsableForDiscoveryTrueAndEventTimeGreaterThanEqual(Mockito.anyLong()))
            .thenReturn(listOf(activityEvent(activityWallet), activityEvent(activityWallet)))
        Mockito.`when`(candidateRepository.findByResearchStateIn(anyResearchStates())).thenReturn(emptyList())
        Mockito.`when`(candidateRepository.findByNormalizedWallet(watchWallet)).thenReturn(null)
        Mockito.`when`(candidateRepository.findByNormalizedWallet(existingWallet)).thenReturn(null)
        Mockito.`when`(candidateRepository.findByNormalizedWallet(activityWallet)).thenReturn(locked)
        Mockito.`when`(candidateRepository.save(anyCandidate())).thenAnswer {
            val candidate = it.arguments[0] as LeaderResearchCandidate
            candidate.copy(id = candidate.id ?: candidate.normalizedWallet.last().digitToInt().toLong())
        }

        val results = service(globalCaptureEnabled = false).discoverCandidates(runId = 99L)

        assertEquals(5, results.size)
        assertEquals(1, results.first { it.sourceType == LeaderResearchSourceType.WATCHLIST }.candidates.size)
        assertEquals(LeaderResearchSourceStatus.DEGRADED, results.first { it.sourceType == LeaderResearchSourceType.ACTIVITY_DERIVED }.status)
        assertTrue(results.first { it.sourceType == LeaderResearchSourceType.ACTIVITY_DERIVED }.expectedLimitation)
        assertEquals(LeaderResearchSourceStatus.DISABLED, results.first { it.sourceType == LeaderResearchSourceType.GLOBAL_ACTIVITY_CAPTURE }.status)
        val preserved = results.first { it.sourceType == LeaderResearchSourceType.ACTIVITY_DERIVED }.candidates.single()
        assertTrue(preserved.locked)
        assertEquals("manual", preserved.source)
        assertEquals(LeaderCandidateProvenance.MANUAL_LOCKED, preserved.provenance)
        assertTrue(preserved.sourceEvidence!!.contains("manual note"))
        assertTrue(preserved.sourceEvidence!!.contains("leader_activity_event:fresh_count=2"))
    }

    @Test
    fun `source failure degrades only failing source and preserves other candidates`() {
        val watchWallet = "0x1111111111111111111111111111111111111111"
        val existingWallet = "0x2222222222222222222222222222222222222222"
        val activityWallet = "0x3333333333333333333333333333333333333333"
        stubCommonDataApi(success = false)
        Mockito.`when`(systemConfigRepository.findByConfigKey(LeaderResearchSourceService.CONFIG_WATCHLIST))
            .thenReturn(SystemConfig(configKey = LeaderResearchSourceService.CONFIG_WATCHLIST, configValue = watchWallet))
        Mockito.`when`(leaderRepository.findAllByOrderByCreatedAtAsc())
            .thenReturn(listOf(Leader(id = 2L, leaderAddress = existingWallet)))
        Mockito.`when`(leaderRepository.findByLeaderAddress(Mockito.anyString())).thenReturn(null)
        Mockito.`when`(activityEventRepository.findByUsableForDiscoveryTrueAndEventTimeGreaterThanEqual(Mockito.anyLong()))
            .thenReturn(listOf(activityEvent(activityWallet)))
        Mockito.`when`(candidateRepository.findByResearchStateIn(anyResearchStates()))
            .thenReturn(listOf(LeaderResearchCandidate(normalizedWallet = activityWallet)))
        Mockito.`when`(candidateRepository.findByNormalizedWallet(Mockito.anyString())).thenReturn(null)
        Mockito.`when`(candidateRepository.save(anyCandidate())).thenAnswer { it.arguments[0] }

        val results = service(globalCaptureEnabled = true).discoverCandidates(runId = 99L)

        assertEquals(4, results.size)
        assertEquals(LeaderResearchSourceStatus.DEGRADED, results.first { it.sourceType == LeaderResearchSourceType.WATCHLIST }.status)
        assertEquals(LeaderResearchSourceStatus.DEGRADED, results.first { it.sourceType == LeaderResearchSourceType.EXISTING_LEADER }.status)
        assertEquals(LeaderResearchSourceStatus.DEGRADED, results.first { it.sourceType == LeaderResearchSourceType.ACTIVITY_DERIVED }.status)
        assertFalse(results.first { it.sourceType == LeaderResearchSourceType.ACTIVITY_DERIVED }.expectedLimitation)
        assertTrue(results.flatMap { it.candidates }.map { it.normalizedWallet }.containsAll(listOf(watchWallet, existingWallet, activityWallet)))
    }

    @Test
    fun `preview returns source limitation without persisting candidates`() {
        val watchWallet = "0x1111111111111111111111111111111111111111"
        val activityWallet = "0x3333333333333333333333333333333333333333"
        Mockito.`when`(systemConfigRepository.findByConfigKey(LeaderResearchSourceService.CONFIG_WATCHLIST))
            .thenReturn(SystemConfig(configKey = LeaderResearchSourceService.CONFIG_WATCHLIST, configValue = watchWallet))
        Mockito.`when`(leaderRepository.findAllByOrderByCreatedAtAsc()).thenReturn(emptyList())
        Mockito.`when`(leaderRepository.findByLeaderAddress(Mockito.anyString())).thenReturn(null)
        Mockito.`when`(activityEventRepository.findByUsableForDiscoveryTrueAndEventTimeGreaterThanEqual(Mockito.anyLong()))
            .thenReturn(listOf(activityEvent(activityWallet)))

        val results = service(globalCaptureEnabled = false).previewCandidates()

        assertEquals(LeaderResearchSourceStatus.DEGRADED, results.first { it.sourceType == LeaderResearchSourceType.ACTIVITY_DERIVED }.status)
        assertEquals(LeaderResearchSourceStatus.DISABLED, results.first { it.sourceType == LeaderResearchSourceType.GLOBAL_ACTIVITY_CAPTURE }.status)
        assertFalse(results.flatMap { it.candidates }.isEmpty())
        Mockito.verify(candidateRepository, Mockito.never()).save(anyCandidate())
    }

    private fun service(globalCaptureEnabled: Boolean) = LeaderResearchSourceService(
        candidateRepository = candidateRepository,
        leaderRepository = leaderRepository,
        leaderPoolRepository = leaderPoolRepository,
        activityEventRepository = activityEventRepository,
        sourceHealthService = sourceHealthService,
        systemConfigRepository = systemConfigRepository,
        retrofitFactory = retrofitFactory,
        eventService = eventService,
        ingestionService = ingestionService,
        backfillLimit = 200,
        globalCaptureEnabled = globalCaptureEnabled
    )

    private fun stubCommonDataApi(success: Boolean) {
        Mockito.`when`(retrofitFactory.createDataApi()).thenReturn(dataApi)
        runBlocking {
            if (success) {
                Mockito.`when`(
                    dataApi.getUserActivity(
                        user = Mockito.anyString(),
                        limit = Mockito.anyInt(),
                        offset = Mockito.isNull(),
                        market = Mockito.isNull(),
                        eventId = Mockito.isNull(),
                        type = anyStringList(),
                        start = Mockito.anyLong(),
                        end = Mockito.anyLong(),
                        sortBy = Mockito.anyString(),
                        sortDirection = Mockito.anyString(),
                        side = Mockito.isNull()
                    )
                ).thenReturn(Response.success(emptyList<UserActivityResponse>()))
            } else {
                Mockito.`when`(
                    dataApi.getUserActivity(
                        user = Mockito.anyString(),
                        limit = Mockito.anyInt(),
                        offset = Mockito.isNull(),
                        market = Mockito.isNull(),
                        eventId = Mockito.isNull(),
                        type = anyStringList(),
                        start = Mockito.anyLong(),
                        end = Mockito.anyLong(),
                        sortBy = Mockito.anyString(),
                        sortDirection = Mockito.anyString(),
                        side = Mockito.isNull()
                    )
                ).thenThrow(IllegalStateException("timeout"))
            }
        }
    }

    private fun activityEvent(wallet: String) = LeaderActivityEvent(
        source = "ACTIVITY_DERIVED",
        stableEventKey = "event-$wallet",
        normalizedWallet = wallet,
        eventTime = System.currentTimeMillis(),
        rawPayloadHash = "hash",
        usableForDiscovery = true
    )

    private fun anyCandidate(): LeaderResearchCandidate {
        Mockito.any(LeaderResearchCandidate::class.java)
        return LeaderResearchCandidate(normalizedWallet = "0x1111111111111111111111111111111111111111")
    }

    private fun anyResearchStates(): Collection<com.wrbug.polymarketbot.enums.LeaderResearchState> {
        Mockito.anyCollection<com.wrbug.polymarketbot.enums.LeaderResearchState>()
        return emptyList()
    }

    private fun anyStringList(): List<String> {
        Mockito.anyList<String>()
        return emptyList()
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)
}
