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
 * OKX 实时现价（tickers 频道）WebSocket：作为现货领先早警(v2)的第二数据源，
 * 维护每 instId 最新 mid=(bidPx+askPx)/2 及本地接收时间戳，供亚秒级现价对比。
 *
 * 频道选择（根因/约束）：OKX 自 2026-03-03 起 `bbo-tbt` 需登录鉴权，与"零鉴权纯 WS"目标冲突；
 * 故改用**公共免鉴权** `tickers` 频道（`/ws/v5/public`，约 100ms 推送），既无需 API 凭证又保持 WS 推送低延迟。
 * 心跳：OKX 30s 无收发即断链，故每 20s 主动发送 "ping" 文本（服务端回 "pong"）。
 *
 * 与币安 [com.wrbug.polymarketbot.service.binance.BinanceSpotTickerService] 同构（按需订阅、断线重连、零开销关闭），
 * 仅消息协议为 OKX 专属，故独立实现。
 */
@Service
class OkxSpotTickerService {

    private val logger = LoggerFactory.getLogger(OkxSpotTickerService::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val wsUrl = "wss://ws.okx.com:8443/ws/v5/public"
    private val client by lazy { createClient().build() }

    /** instId(如 BTC-USDT) -> (mid, 本地接收时间戳ms)；tickers 与周期无关，按 instId 缓存 */
    private val latestByInstId = ConcurrentHashMap<String, Pair<BigDecimal, Long>>()

    /** 已连接的 WebSocket: instId -> WebSocket */
    private val connectedWebSockets = ConcurrentHashMap<String, WebSocket>()
    private val requiredInstIds = AtomicReference<Set<String>>(emptySet())
    private val subscriptionLock = Any()
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null

    /**
     * tick 推送监听器（供现货领先推送触发器注册）：每条 tick 缓存后回调 (instId, mid, tsMs)。
     * 用函数类型而非共享接口，避免 okx 包与 cryptotail 包的反向耦合。null=无监听（零开销）。
     */
    @Volatile
    private var tickListener: ((instId: String, mid: BigDecimal, tsMs: Long) -> Unit)? = null

    fun setTickListener(listener: (instId: String, mid: BigDecimal, tsMs: Long) -> Unit) {
        tickListener = listener
    }

    /** 该市场对应 instId 的最新现价 mid，无数据返回 null */
    fun getLatestMid(marketSlugPrefix: String): BigDecimal? {
        val instId = OkxSymbolResolver.instIdOfMarketSlug(marketSlugPrefix) ?: return null
        return latestByInstId[instId]?.first
    }

    /** 该市场对应 instId 最新现价的本地接收时间戳(ms)，无数据返回 null（供 age 新鲜度门禁） */
    fun getLastUpdateMs(marketSlugPrefix: String): Long? {
        val instId = OkxSymbolResolver.instIdOfMarketSlug(marketSlugPrefix) ?: return null
        return latestByInstId[instId]?.second
    }

    /** 供 API 健康检查使用：各 instId tick 连接状态 */
    fun getConnectionStatuses(): Map<String, Boolean> {
        return connectedWebSockets.keys.associateWith { connectedWebSockets[it] != null }
    }

    /**
     * 按需更新订阅：仅订阅传入市场对应的 instId tickers。
     * @param marketPrefixes 需要现价的完整市场集合，如 ["btc-updown-5m"]；空集合时关闭所有连接（零开销）
     */
    fun updateSubscriptions(marketPrefixes: Set<String>) {
        val instIdsNeeded = marketPrefixes
            .mapNotNull { OkxSymbolResolver.instIdOfMarketSlug(it) }
            .toSet()

        val hasMissingConnection = instIdsNeeded.any { it !in connectedWebSockets.keys }
        if (instIdsNeeded == requiredInstIds.get() && !hasMissingConnection) return
        requiredInstIds.set(instIdsNeeded)
        synchronized(subscriptionLock) {
            connectedWebSockets.keys.toList().forEach { instId ->
                if (instId !in instIdsNeeded) {
                    connectedWebSockets.remove(instId)?.close(1000, "subscription_update")
                    latestByInstId.remove(instId)
                    logger.info("OKX 现价 WS 已关闭（无策略使用）: $instId")
                }
            }
            instIdsNeeded.forEach { instId -> connectStream(instId) }
        }
        ensureHeartbeat()
    }

    private fun connectStream(instId: String) {
        if (connectedWebSockets[instId] != null) return
        val request = Request.Builder().url(wsUrl).build()
        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                connectedWebSockets[instId] = webSocket
                val sub = "{\"op\":\"subscribe\",\"args\":[{\"channel\":\"tickers\",\"instId\":\"$instId\"}]}"
                webSocket.send(sub)
                logger.info("OKX 现价 WS 已连接并订阅 tickers: $instId")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text == "pong") return
                parseTickerMid(text, instId)?.let { (mid, tsMs) ->
                    latestByInstId[instId] = mid to System.currentTimeMillis()
                    tickListener?.let { l ->
                        try {
                            l(instId, mid, tsMs)
                        } catch (e: Exception) {
                            logger.debug("OKX tick 监听器回调异常: ${e.message}")
                        }
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                connectedWebSockets.remove(instId)
                logger.warn("OKX 现价 WS 异常 $instId: ${t.message}")
                scheduleReconnect()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                connectedWebSockets.remove(instId)
                if (code != 1000) scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connectedWebSockets.remove(instId)
            }
        })
    }

    /**
     * 解析 OKX tickers 推送，返回 (mid=(bidPx+askPx)/2, 数据时间戳ms)。
     * 形如 {"arg":{...},"data":[{"bidPx":"..","askPx":"..","ts":".."}]}；事件/订阅确认无 data，返回 null。
     */
    private fun parseTickerMid(text: String, instId: String): Pair<BigDecimal, Long>? {
        return try {
            val json = com.google.gson.JsonParser.parseString(text).asJsonObject
            val data = json.getAsJsonArray("data") ?: return null
            if (data.size() == 0) return null
            val obj = data[0].asJsonObject
            val bid = obj.get("bidPx")?.asString?.toSafeBigDecimal() ?: return null
            val ask = obj.get("askPx")?.asString?.toSafeBigDecimal() ?: return null
            if (bid <= BigDecimal.ZERO || ask <= BigDecimal.ZERO) return null
            val tsMs = obj.get("ts")?.asString?.toLongOrNull() ?: System.currentTimeMillis()
            bid.add(ask).divide(BigDecimal(2)) to tsMs
        } catch (e: Exception) {
            logger.debug("解析 OKX tickers 消息失败: ${e.message}")
            null
        }
    }

    /** OKX 30s 无收发即断链：每 20s 发 "ping" 文本保活（仅在有连接时运行） */
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
                        logger.debug("OKX 现价 WS 心跳发送失败: ${e.message}")
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
            val current = requiredInstIds.get()
            connectedWebSockets.values.forEach { it.close(1000, "reconnect") }
            connectedWebSockets.clear()
            logger.info("OKX 现价 WS 尝试重连")
            requiredInstIds.set(emptySet())
            synchronized(subscriptionLock) {
                requiredInstIds.set(current)
                current.forEach { connectStream(it) }
            }
            ensureHeartbeat()
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
