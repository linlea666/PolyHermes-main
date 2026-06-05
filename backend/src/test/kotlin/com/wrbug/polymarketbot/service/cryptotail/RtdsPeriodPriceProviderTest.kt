package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.websocket.PolymarketWebSocketClient
import okhttp3.WebSocket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.lang.reflect.Method
import java.math.BigDecimal

class RtdsPeriodPriceProviderTest {

    @Test
    fun `getCurrentOpenClose returns null when period open is missing`() {
        val rtds = readyService()
        val now = System.currentTimeMillis()
        rtds.accept(snapshot("eth/usd", now - 1_000, 1860.0))
        val resolver = Mockito.mock(CryptoTailOfficialOpenResolver::class.java)
        Mockito.`when`(resolver.resolve("eth-updown-5m", 300, now / 1000 - 300)).thenReturn(null)
        val provider = RtdsPeriodPriceProvider(rtds, Mockito.mock(BarrierSigmaEstimator::class.java), resolver)

        val result = provider.getCurrentOpenClose("eth-updown-5m", 300, now / 1000 - 300)

        assertNull(result)
    }

    @Test
    fun `ETH can compute pWin and safeRatio after official open exists`() {
        val rtds = readyService()
        val periodStart = System.currentTimeMillis() / 1000 - 60
        rtds.accept(snapshot("eth/usd", periodStart * 1000, 1800.0))
        rtds.accept(snapshot("eth/usd", System.currentTimeMillis() - 1_000, 1810.0))
        val resolver = Mockito.mock(CryptoTailOfficialOpenResolver::class.java)
        Mockito.`when`(resolver.resolve("eth-updown-5m", 300, periodStart)).thenReturn(null)
        val provider = RtdsPeriodPriceProvider(rtds, Mockito.mock(BarrierSigmaEstimator::class.java), resolver)

        val oc = provider.getCurrentOpenClose("eth-updown-5m", 300, periodStart)
        assertNotNull(oc)
        val gap = oc!!.second.subtract(oc.first)
        val result = BarrierProbability.winProbTerminal(gap, BigDecimal("0.5"), 120.0)

        assertNotNull(result)
        assertEquals(0, result!!.side)
        assertNotNull(result.pWin)
        assertNotNull(result.safeRatio)
    }

    @Test
    fun `fresh snapshot rescues a stale realtime price (guard bugfix)`() {
        val rtds = readyService()
        val now = System.currentTimeMillis()
        // realtime 已断流 20s（< freshnessMs 30s）：旧逻辑会把后续新鲜 snapshot 丢弃
        rtds.accept(realtime("btc/usd", now - 20_000, 50_000.0))
        // 4s 补订阅拿回的新鲜 snapshot（最新点 1s 前）应救场覆盖陈旧 realtime
        rtds.accept(snapshot("btc/usd", now - 1_000, 50_100.0))

        val price = rtds.currentPrice("btc-updown-5m")
        val age = rtds.currentPriceAgeMs("btc-updown-5m")
        assertNotNull(price)
        assertEquals(0, BigDecimal("50100").compareTo(price), "fresh snapshot 应覆盖陈旧 realtime，实际=$price")
        assertNotNull(age)
        assertEquals(true, age!! < 5_000, "priceAge 应回落到秒级，实际=${age}ms")
    }

    @Test
    fun `stale snapshot does not override fresher realtime (anti-backfill preserved)`() {
        val rtds = readyService()
        val now = System.currentTimeMillis()
        // 新鲜 realtime（1s 前）
        rtds.accept(realtime("btc/usd", now - 1_000, 50_000.0))
        // 较旧 snapshot（最新点 10s 前）样本时间更旧 → 不应覆盖（防倒灌）
        rtds.accept(snapshot("btc/usd", now - 10_000, 49_800.0))

        val price = rtds.currentPrice("btc-updown-5m")
        assertNotNull(price)
        assertEquals(0, BigDecimal("50000").compareTo(price), "更旧 snapshot 不应覆盖新鲜 realtime，实际=$price")
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

    /** 实时单点更新（无 data 数组 → 走 realtime 分支） */
    private fun realtime(symbol: String, ts: Long, value: Double): String =
        """{"topic":"crypto_prices_chainlink","type":"update","payload":{"symbol":"$symbol","value":$value,"timestamp":$ts}}"""

    private fun setPrivate(target: Any, name: String, value: Any?) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }
}
