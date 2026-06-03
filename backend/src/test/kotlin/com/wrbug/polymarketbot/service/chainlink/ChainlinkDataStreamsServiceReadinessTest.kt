package com.wrbug.polymarketbot.service.chainlink

import com.wrbug.polymarketbot.service.system.SystemConfigService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

class ChainlinkDataStreamsServiceReadinessTest {

    @Test
    fun `unsupported slug is explicit`() {
        val service = service()

        val readiness = service.readiness("doge-updown")

        assertEquals("CHAINLINK", readiness.source)
        assertEquals("UNSUPPORTED_SLUG", readiness.reason)
        assertFalse(readiness.ready)
    }

    @Test
    fun `missing credentials or feed reports feed not configured`() {
        val service = service(apiKey = null, apiSecret = null, feedId = null)

        val readiness = service.readiness("btc-updown")

        assertEquals("btc", readiness.coin)
        assertEquals("FEED_NOT_CONFIGURED", readiness.reason)
        assertFalse(readiness.ready)
    }

    @Test
    fun `configured feed without latest price is not ready`() {
        val service = service()

        val readiness = service.readiness("btc-updown")

        assertEquals("NO_LATEST_PRICE", readiness.reason)
        assertFalse(readiness.ready)
    }

    @Test
    fun `fresh latest price is ready and stale latest price is not`() {
        val service = service(feedId = "feed-btc")
        latestCache(service)["feed-btc"] = BigDecimal("50000") to System.currentTimeMillis()

        val fresh = service.readiness("btc-updown")

        assertEquals("OK", fresh.reason)
        assertTrue(fresh.ready)

        latestCache(service)["feed-btc"] = BigDecimal("50000") to (System.currentTimeMillis() - 2_000L)
        val stale = service.readiness("btc-updown")

        assertEquals("STALE_PRICE", stale.reason)
        assertFalse(stale.ready)
    }

    private fun service(
        apiKey: String? = "key",
        apiSecret: String? = "secret",
        feedId: String? = "feed-btc"
    ): ChainlinkDataStreamsService {
        val systemConfig = Mockito.mock(SystemConfigService::class.java)
        Mockito.`when`(systemConfig.getChainlinkApiKey()).thenReturn(apiKey)
        Mockito.`when`(systemConfig.getChainlinkApiSecret()).thenReturn(apiSecret)
        Mockito.`when`(systemConfig.getChainlinkFeedId("btc-updown")).thenReturn(feedId)
        Mockito.`when`(systemConfig.getChainlinkFeedId("btc")).thenReturn(feedId)
        return ChainlinkDataStreamsService(systemConfig, "", "", "")
    }

    @Suppress("UNCHECKED_CAST")
    private fun latestCache(service: ChainlinkDataStreamsService): ConcurrentHashMap<String, Pair<BigDecimal, Long>> {
        val field = ChainlinkDataStreamsService::class.java.getDeclaredField("latestCache")
        field.isAccessible = true
        return field.get(service) as ConcurrentHashMap<String, Pair<BigDecimal, Long>>
    }
}
