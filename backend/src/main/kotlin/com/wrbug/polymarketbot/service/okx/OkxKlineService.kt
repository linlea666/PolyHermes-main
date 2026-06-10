package com.wrbug.polymarketbot.service.okx

import com.wrbug.polymarketbot.util.createClient
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import jakarta.annotation.PreDestroy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * OKX K 线（candle 频道）WebSocket：为现货领先早警(v2) OKX 数据源提供同周期 (open, close)，
 * 其中 open 作为"周期开盘价 strike"，与 [OkxSpotTickerService] 的实时 mid 同源构成 binGap=current-open。
 *
 * 频道与端点：OKX candle 频道在 **business** 端点（`/ws/v5/business`，公共免鉴权）。心跳每 20s 发 "ping"。
 * 与币安 [com.wrbug.polymarketbot.service.binance.BinanceKlineService] 同构，仅消息协议为 OKX 专属。
 */
@Service
class OkxKlineService {

    private val logger = LoggerFactory.getLogger(OkxKlineService::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val wsUrl = "wss://ws.okx.com:8443/ws/v5/business"
    private val client by lazy { createClient().build() }

    /** (marketSlugPrefix, intervalSeconds, periodStartUnix) -> (open, close) */
    private val openCloseByPeriod = ConcurrentHashMap<String, Pair<BigDecimal, BigDecimal>>()

    /** (marketSlugPrefix, intervalSeconds, periodStartUnix) -> 最近一次 candle 推送本地接收时间戳(ms)，供 age 新鲜度门禁 */
    private val updatedAtByPeriod = ConcurrentHashMap<String, Long>()

    /** 已连接的 WebSocket: wsKey (instId-interval) -> WebSocket */
    private val connectedWebSockets = ConcurrentHashMap<String, WebSocket>()
    private val requiredMarketPrefixes = AtomicReference<Set<String>>(emptySet())
    private val subscriptionLock = Any()
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null

    private fun key(marketSlugPrefix: String, intervalSeconds: Int, periodStartUnix: Long): String =
        "$marketSlugPrefix-$intervalSeconds-$periodStartUnix"

    fun getCurrentOpenClose(marketSlugPrefix: String, intervalSeconds: Int, periodStartUnix: Long): Pair<BigDecimal, BigDecimal>? =
        openCloseByPeriod[key(marketSlugPrefix, intervalSeconds, periodStartUnix)]

    fun getLastUpdateMs(marketSlugPrefix: String, intervalSeconds: Int, periodStartUnix: Long): Long? =
        updatedAtByPeriod[key(marketSlugPrefix, intervalSeconds, periodStartUnix)]

    fun getConnectionStatuses(): Map<String, Boolean> =
        connectedWebSockets.keys.associateWith { connectedWebSockets[it] != null }

    /**
     * 按需更新订阅：仅订阅策略用到的 (instId, 周期)。
     * @param marketPrefixes 完整市场集合，如 ["btc-updown-5m"]；空集合时关闭所有连接
     */
    fun updateSubscriptions(marketPrefixes: Set<String>) {
        val normalized = marketPrefixes.map { it.lowercase() }.toSet()
        val parsed = normalized.mapNotNull { full ->
            OkxSymbolResolver.parseMarketSlug(full)?.let { (base, interval) ->
                val instId = OkxSymbolResolver.getInstId(base) ?: return@let null
                val channel = OkxSymbolResolver.candleChannel(interval) ?: return@let null
                FourTuple(full, instId, interval, channel)
            }
        }.toSet()
        val wsKeysNeeded = parsed.map { "${it.instId}-${it.interval}" }.toSet()

        val hasMissingConnection = wsKeysNeeded.any { it !in connectedWebSockets.keys }
        if (normalized == requiredMarketPrefixes.get() && !hasMissingConnection) return
        requiredMarketPrefixes.set(normalized)
        synchronized(subscriptionLock) {
            connectedWebSockets.keys.toList().forEach { wsKey ->
                if (wsKey !in wsKeysNeeded) {
                    connectedWebSockets.remove(wsKey)?.close(1000, "subscription_update")
                    logger.info("OKX K 线 WS 已关闭（无策略使用）: $wsKey")
                }
            }
            parsed.forEach { connectStream(it) }
        }
        ensureHeartbeat()
    }

    private data class FourTuple(val fullPrefix: String, val instId: String, val interval: String, val channel: String)

    private fun connectStream(t: FourTuple) {
        val wsKey = "${t.instId}-${t.interval}"
        if (connectedWebSockets[wsKey] != null) return
        val intervalSeconds = when (t.interval) {
            "5m" -> 300
            "15m" -> 900
            else -> 300
        }
        val request = Request.Builder().url(wsUrl).build()
        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                connectedWebSockets[wsKey] = webSocket
                val sub = "{\"op\":\"subscribe\",\"args\":[{\"channel\":\"${t.channel}\",\"instId\":\"${t.instId}\"}]}"
                webSocket.send(sub)
                logger.info("OKX K 线 WS 已连接并订阅 ${t.channel}: ${t.instId}")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text == "pong") return
                parseCandleMessage(text)?.let { (tMs, o, c) ->
                    val periodSec = tMs / 1000
                    val cacheKey = key(t.fullPrefix, intervalSeconds, periodSec)
                    openCloseByPeriod[cacheKey] = o to c
                    updatedAtByPeriod[cacheKey] = System.currentTimeMillis()
                }
            }

            override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: okhttp3.Response?) {
                connectedWebSockets.remove(wsKey)
                logger.warn("OKX K 线 WS 异常 $wsKey: ${throwable.message}")
                scheduleReconnect()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                connectedWebSockets.remove(wsKey)
                if (code != 1000) scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connectedWebSockets.remove(wsKey)
            }
        })
    }

    /**
     * 解析 OKX candle 推送，返回 (周期开始时间ms, open, close)。
     * 形如 {"arg":{...},"data":[["ts","o","h","l","c",...]]}；data[0]: [0]=ts(ms), [1]=open, [4]=close。
     */
    private fun parseCandleMessage(text: String): Triple<Long, BigDecimal, BigDecimal>? {
        return try {
            val json = com.google.gson.JsonParser.parseString(text).asJsonObject
            val data = json.getAsJsonArray("data") ?: return null
            if (data.size() == 0) return null
            val row = data[0].asJsonArray
            if (row.size() < 5) return null
            val tMs = row[0].asString.toLongOrNull() ?: return null
            val o = row[1].asString.toSafeBigDecimal()
            val c = row[4].asString.toSafeBigDecimal()
            if (o <= BigDecimal.ZERO || c <= BigDecimal.ZERO) return null
            Triple(tMs, o, c)
        } catch (e: Exception) {
            logger.debug("解析 OKX candle 消息失败: ${e.message}")
            null
        }
    }

    private fun ensureHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(20_000)
                val sockets = connectedWebSockets.values.toList()
                if (sockets.isEmpty()) {
                    heartbeatJob = null
                    break
                }
                sockets.forEach {
                    try {
                        it.send("ping")
                    } catch (e: Exception) {
                        logger.debug("OKX K 线 WS 心跳发送失败: ${e.message}")
                    }
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            delay(3_000)
            reconnectJob = null
            val current = requiredMarketPrefixes.get()
            connectedWebSockets.values.forEach { it.close(1000, "reconnect") }
            connectedWebSockets.clear()
            logger.info("OKX K 线 WS 尝试重连")
            requiredMarketPrefixes.set(emptySet())
            updateSubscriptions(current)
        }
    }

    @PreDestroy
    fun destroy() {
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        connectedWebSockets.values.forEach { it.close(1000, "shutdown") }
        connectedWebSockets.clear()
    }
}
