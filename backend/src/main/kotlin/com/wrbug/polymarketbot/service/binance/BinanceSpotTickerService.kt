package com.wrbug.polymarketbot.service.binance

import com.wrbug.polymarketbot.util.createClient
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
 * 币安实时现价（@bookTicker）WebSocket：按需订阅 SCALP_FLIP 且开启现货领先早警的策略所用币种，
 * 维护每币种最新 mid=(bestBid+bestAsk)/2 及本地接收时间戳，供现货领先早警(V93+)做亚秒级现价对比。
 *
 * 与 [BinanceKlineService] 的区别：bookTicker 是按 symbol 推送的最新最优买卖价（与周期/interval 无关），
 * 消息结构与 kline 不同，故独立实现；仅复用 [BinanceSymbolResolver] 的交易对映射。
 * 仅当存在开启该功能的策略时才建立连接，功能关闭时零连接、零开销。
 */
@Service
class BinanceSpotTickerService {

    private val logger = LoggerFactory.getLogger(BinanceSpotTickerService::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val wsBase = "wss://stream.binance.com:9443"
    private val client by lazy {
        createClient().build()
    }

    /** symbol(如 BTCUSDC) -> (mid, 本地接收时间戳ms)；bookTicker 与周期无关，故只按 symbol 缓存 */
    private val latestBySymbol = ConcurrentHashMap<String, Pair<BigDecimal, Long>>()

    /** 已连接的 WebSocket: symbol -> WebSocket */
    private val connectedWebSockets = ConcurrentHashMap<String, WebSocket>()
    /** 当前需要订阅的 symbol 集合，由加密价差策略刷新时更新 */
    private val requiredSymbols = AtomicReference<Set<String>>(emptySet())
    private val subscriptionLock = Any()
    private var reconnectJob: Job? = null

    /**
     * 该市场对应币种的最新现价 mid，无数据返回 null。
     * @param marketSlugPrefix 完整市场 slug（如 btc-updown-5m），内部解析为币安交易对
     */
    fun getLatestMid(marketSlugPrefix: String): BigDecimal? {
        val symbol = BinanceSymbolResolver.symbolOfMarketSlug(marketSlugPrefix) ?: return null
        return latestBySymbol[symbol]?.first
    }

    /**
     * 该市场对应币种最新现价的本地接收时间戳(ms)，无数据返回 null。
     * 供现货领先早警计算 age 做新鲜度门禁（fail-safe，绝不用过期 tick 误触发）。
     */
    fun getLastUpdateMs(marketSlugPrefix: String): Long? {
        val symbol = BinanceSymbolResolver.symbolOfMarketSlug(marketSlugPrefix) ?: return null
        return latestBySymbol[symbol]?.second
    }

    /** 供 API 健康检查使用：各币种 tick 连接状态 */
    fun getConnectionStatuses(): Map<String, Boolean> {
        return connectedWebSockets.keys.associateWith { connectedWebSockets[it] != null }
    }

    /**
     * 按需更新订阅：仅订阅传入市场对应的币种 bookTicker。
     * 由 CryptoTailOrderbookWsService 在刷新订阅时根据"开启现货领先早警"的 SCALP_FLIP 策略调用。
     * @param marketPrefixes 需要现价的完整市场集合，如 ["btc-updown-5m"]；空集合时关闭所有连接（零开销）
     */
    fun updateSubscriptions(marketPrefixes: Set<String>) {
        val symbolsNeeded = marketPrefixes
            .mapNotNull { BinanceSymbolResolver.symbolOfMarketSlug(it) }
            .toSet()

        // 检查是否有需要的连接缺失（可能因网络问题断开）
        val hasMissingConnection = symbolsNeeded.any { it !in connectedWebSockets.keys }

        // 只有当集合相同且所有需要的连接都存在时才跳过
        if (symbolsNeeded == requiredSymbols.get() && !hasMissingConnection) return
        requiredSymbols.set(symbolsNeeded)
        synchronized(subscriptionLock) {
            connectedWebSockets.keys.toList().forEach { symbol ->
                if (symbol !in symbolsNeeded) {
                    connectedWebSockets.remove(symbol)?.close(1000, "subscription_update")
                    latestBySymbol.remove(symbol)
                    logger.info("币安现价 WS 已关闭（无策略使用）: $symbol")
                }
            }
            symbolsNeeded.forEach { symbol ->
                connectStream(symbol)
            }
        }
    }

    private fun connectStream(symbol: String) {
        if (connectedWebSockets[symbol] != null) return
        val streamName = "${symbol.lowercase()}@bookTicker"
        val url = "$wsBase/ws/$streamName"
        val request = Request.Builder().url(url).build()
        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                connectedWebSockets[symbol] = webSocket
                logger.info("币安现价 WS 已连接: $streamName")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parseBookTickerMid(text)?.let { mid ->
                    latestBySymbol[symbol] = mid to System.currentTimeMillis()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                connectedWebSockets.remove(symbol)
                logger.warn("币安现价 WS 异常 $streamName: ${t.message}")
                scheduleReconnect()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                connectedWebSockets.remove(symbol)
                if (code != 1000) scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connectedWebSockets.remove(symbol)
            }
        })
    }

    /**
     * 解析 bookTicker 原始流消息，返回 mid=(bestBid+bestAsk)/2。
     * bookTicker 原始流无 "e" 事件类型字段，按 b(best bid)/a(best ask) 存在性解析。
     */
    private fun parseBookTickerMid(text: String): BigDecimal? {
        return try {
            val json = com.google.gson.JsonParser.parseString(text).asJsonObject
            val bid = json.get("b")?.asString?.toSafeBigDecimal() ?: return null
            val ask = json.get("a")?.asString?.toSafeBigDecimal() ?: return null
            if (bid <= BigDecimal.ZERO || ask <= BigDecimal.ZERO) return null
            bid.add(ask).divide(BigDecimal(2))
        } catch (e: Exception) {
            logger.debug("解析币安 bookTicker 消息失败: ${e.message}")
            null
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            delay(3_000)
            reconnectJob = null
            val current = requiredSymbols.get()
            connectedWebSockets.values.forEach { it.close(1000, "reconnect") }
            connectedWebSockets.clear()
            logger.info("币安现价 WS 尝试重连")
            // 清空 requiredSymbols，否则 updateSubscriptions 内会因集合相同直接 return，不会重新 connectStream
            requiredSymbols.set(emptySet())
            // 重新按 symbol 重连：bookTicker 与 marketPrefix 无关，直接对当前 symbol 集建连
            synchronized(subscriptionLock) {
                requiredSymbols.set(current)
                current.forEach { connectStream(it) }
            }
        }
    }

    @PreDestroy
    fun destroy() {
        reconnectJob?.cancel()
        connectedWebSockets.values.forEach { it.close(1000, "shutdown") }
        connectedWebSockets.clear()
    }
}
