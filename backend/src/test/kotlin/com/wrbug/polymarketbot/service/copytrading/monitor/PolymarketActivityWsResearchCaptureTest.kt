package com.wrbug.polymarketbot.service.copytrading.monitor

import com.google.gson.Gson
import com.wrbug.polymarketbot.entity.LeaderActivityEvent
import com.wrbug.polymarketbot.enums.LeaderResearchSourceStatus
import com.wrbug.polymarketbot.enums.LeaderResearchSourceType
import com.wrbug.polymarketbot.repository.LeaderActivityEventRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.service.copytrading.research.LeaderActivityIngestionService
import com.wrbug.polymarketbot.service.copytrading.research.LeaderResearchSourceHealthService
import com.wrbug.polymarketbot.service.copytrading.statistics.CopyOrderTrackingService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.ObjectProvider

class PolymarketActivityWsResearchCaptureTest {
    private val copyOrderTrackingService: CopyOrderTrackingService = mock()
    private val leaderRepository: LeaderRepository = mock()
    private val activityEventRepository: LeaderActivityEventRepository = mock()
    private val ingestionService = LeaderActivityIngestionService(activityEventRepository, Gson())
    private val healthService: LeaderResearchSourceHealthService = mock()

    @Test
    fun `disabled global capture records disabled source health without parsing message`() {
        val service = service(globalCaptureEnabled = false)

        invokeHandleMessage(service, "not-json")

        val invocation = Mockito.mockingDetails(healthService).invocations.single()
        assertEquals(LeaderResearchSourceType.GLOBAL_ACTIVITY_CAPTURE, invocation.arguments[0])
        assertEquals(LeaderResearchSourceStatus.DISABLED, invocation.arguments[1])
        assertEquals("Global activity capture is disabled", invocation.arguments[5])
    }

    @Test
    fun `disabled global capture health is written once to avoid source state lock churn`() {
        val service = service(globalCaptureEnabled = false)

        invokeHandleMessage(service, "not-json")
        invokeHandleMessage(service, "still-not-json")

        assertEquals(1, Mockito.mockingDetails(healthService).invocations.size)
    }

    @Test
    fun `write cap records degraded source health`() {
        val service = service(globalCaptureEnabled = true, maxWritesPerMinute = 0)

        invokeHandleMessage(service, activityMessage("tx-capped"))

        val invocation = Mockito.mockingDetails(healthService).invocations.single()
        assertEquals(LeaderResearchSourceStatus.DEGRADED, invocation.arguments[1])
        assertEquals("WriteCapReached", invocation.arguments[3])
    }

    @Test
    fun `parse failure records failure source health`() {
        val service = service(globalCaptureEnabled = true)

        invokeHandleMessage(service, "not-json")

        val invocation = Mockito.mockingDetails(healthService).invocations.single()
        assertEquals(LeaderResearchSourceStatus.FAILURE, invocation.arguments[1])
        assertEquals("JsonParseFailure", invocation.arguments[3])
    }

    @Test
    fun `successful research capture writes success source health cursor before known leader filtering`() {
        Mockito.`when`(activityEventRepository.findByStableEventKey(Mockito.anyString())).thenReturn(null)
        Mockito.`when`(activityEventRepository.save(anyActivityEvent())).thenAnswer { it.arguments[0] }
        val service = service(globalCaptureEnabled = true)

        invokeHandleMessage(service, activityMessage("tx-success"))

        val invocation = Mockito.mockingDetails(healthService).invocations.single()
        assertEquals(LeaderResearchSourceStatus.SUCCESS, invocation.arguments[1])
        assertEquals(1, invocation.arguments[2])
        assertTrue((invocation.arguments[7] as String).contains("tx-success"))
    }

    private fun service(
        globalCaptureEnabled: Boolean,
        maxWritesPerMinute: Long = 120
    ) = PolymarketActivityWsService(
        copyOrderTrackingService = copyOrderTrackingService,
        leaderRepository = leaderRepository,
        researchIngestionProvider = provider(ingestionService),
        researchSourceHealthProvider = provider(healthService),
        researchGlobalCaptureEnabled = globalCaptureEnabled,
        researchGlobalCaptureMaxWritesPerMinute = maxWritesPerMinute
    )

    private fun invokeHandleMessage(service: PolymarketActivityWsService, message: String) {
        val method = PolymarketActivityWsService::class.java.getDeclaredMethod("handleMessage", String::class.java)
        method.isAccessible = true
        method.invoke(service, message)
    }

    private fun activityMessage(txHash: String): String {
        return """
            {
              "topic": "activity",
              "type": "trades",
              "payload": {
                "proxyWallet": "0x9999999999999999999999999999999999999999",
                "conditionId": "market-1",
                "side": "BUY",
                "price": "0.42",
                "size": "2.5",
                "asset": "asset-1",
                "transactionHash": "$txHash"
              }
            }
        """.trimIndent()
    }

    private fun anyActivityEvent(): LeaderActivityEvent {
        Mockito.any(LeaderActivityEvent::class.java)
        return LeaderActivityEvent(source = "GLOBAL_ACTIVITY_CAPTURE", stableEventKey = "dummy", eventTime = 1, rawPayloadHash = "hash")
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> provider(value: T): ObjectProvider<T> {
        val provider = Mockito.mock(ObjectProvider::class.java) as ObjectProvider<T>
        Mockito.`when`(provider.getIfAvailable()).thenReturn(value)
        return provider
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)
}
