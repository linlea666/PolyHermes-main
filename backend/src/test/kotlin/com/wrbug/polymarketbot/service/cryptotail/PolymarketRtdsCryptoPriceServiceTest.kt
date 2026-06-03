package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.websocket.PolymarketWebSocketClient
import okhttp3.WebSocket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.lang.reflect.Method
import java.math.BigDecimal

class PolymarketRtdsCryptoPriceServiceTest {

    @Test
    fun `fresh ETH snapshot marks readiness OK with snapshot mode`() {
        val service = readyService()
        val ts = System.currentTimeMillis() - 1_000

        service.accept(
            """
            {
              "topic":"crypto_prices",
              "type":"subscribe",
              "payload":{"symbol":"eth/usd","data":[{"timestamp":$ts,"value":1861.12}]}
            }
            """.trimIndent()
        )

        val readiness = service.readiness("eth-updown-5m")
        assertTrue(readiness.ready)
        assertEquals("OK", readiness.reason)
        assertEquals(PolymarketRtdsCryptoPriceService.PRICE_MODE_SUBSCRIBE_SNAPSHOT, readiness.priceMode)
        assertEquals(ts, readiness.latestSampleTime)
        assertNotNull(service.currentPrice("eth-updown-5m"))
    }

    @Test
    fun `old snapshot does not overwrite newer latest price`() {
        val service = readyService()
        val freshTs = System.currentTimeMillis() - 1_000
        val oldTs = freshTs - 10_000

        service.accept(update("btc/usd", freshTs, "66700000000000000000000", 66700.0))
        service.accept(snapshot("btc/usd", oldTs, 66000.0))

        assertEquals(BigDecimal("66700.00000000"), service.currentPrice("btc-updown-15m"))
        val readiness = service.readiness("btc-updown-15m")
        assertEquals(PolymarketRtdsCryptoPriceService.PRICE_MODE_REALTIME_UPDATE, readiness.priceMode)
        assertEquals(freshTs, readiness.latestSampleTime)
    }

    @Test
    fun `duplicate snapshot sample is not written to history twice`() {
        val service = readyService()
        val currentMinute = (System.currentTimeMillis() / 1000).let { it - (it % 60) }
        val nowSeconds = currentMinute
        val ts = (currentMinute - 50) * 1000

        service.accept(snapshot("eth/usd", ts, 1800.0))
        service.accept(snapshot("eth/usd", ts, 1810.0))

        val candles = service.recentOhlc1m("eth-updown-5m", 1, nowSeconds)
        assertEquals(1, candles.size)
        assertEquals(1, candles.single().tickCount)
        assertEquals(BigDecimal("1800.00000000"), candles.single().close)
    }

    @Test
    fun `snapshot without timestamp does not mark readiness OK`() {
        val service = readyService()

        service.accept(
            """
            {
              "topic":"crypto_prices",
              "type":"subscribe",
              "payload":{"symbol":"eth/usd","data":[{"timestamp":0,"value":1861.12}]}
            }
            """.trimIndent()
        )

        val readiness = service.readiness("eth-updown-5m")
        assertFalse(readiness.ready)
        assertEquals("NO_LATEST_PRICE", readiness.reason)
        assertNull(service.currentPrice("eth-updown-5m"))
    }

    @Test
    fun `BTC realtime update is not overwritten by older snapshot`() {
        val service = readyService()
        val freshTs = System.currentTimeMillis() - 1_000
        val oldTs = freshTs - 3_000

        service.accept(update("btc/usd", freshTs, "66800000000000000000000", 66800.0))
        service.accept(snapshot("btc/usd", oldTs, 66700.0))

        assertEquals(BigDecimal("66800.00000000"), service.currentPrice("btc-updown"))
        assertEquals(PolymarketRtdsCryptoPriceService.PRICE_MODE_REALTIME_UPDATE, service.readiness("btc-updown").priceMode)
    }

    private fun readyService(): PolymarketRtdsCryptoPriceService {
        val service = PolymarketRtdsCryptoPriceService()
        val client = PolymarketWebSocketClient(
            url = "wss://example.invalid",
            sessionId = "test",
            onMessage = {}
        )
        setPrivate(client, "isConnected", true)
        setPrivate(client, "webSocket", Mockito.mock(WebSocket::class.java))
        setPrivate(service, "wsClient", client)
        setPrivate(service, "started", true)
        return service
    }

    private fun PolymarketRtdsCryptoPriceService.accept(text: String) {
        val method: Method = javaClass.getDeclaredMethod("handleMessage", String::class.java)
        method.isAccessible = true
        method.invoke(this, text)
    }

    private fun snapshot(symbol: String, ts: Long, value: Double): String =
        """{"topic":"crypto_prices","type":"subscribe","payload":{"symbol":"$symbol","data":[{"timestamp":$ts,"value":$value}]}}"""

    private fun update(symbol: String, ts: Long, full: String, value: Double): String =
        """{"topic":"crypto_prices_chainlink","type":"update","payload":{"symbol":"$symbol","timestamp":$ts,"full_accuracy_value":"$full","value":$value}}"""

    private fun setPrivate(target: Any, name: String, value: Any?) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }
}
