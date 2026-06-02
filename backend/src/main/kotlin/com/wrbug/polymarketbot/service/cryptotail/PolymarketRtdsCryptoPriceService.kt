package com.wrbug.polymarketbot.service.cryptotail

import com.google.gson.annotations.SerializedName
import com.wrbug.polymarketbot.constants.PolymarketConstants
import com.wrbug.polymarketbot.util.fromJson
import com.wrbug.polymarketbot.websocket.PolymarketWebSocketClient
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap

/**
 * Polymarket RTDS 加密价源服务（crypto-tail 障碍模式免凭证价源）。
 *
 * 根因：Polymarket 的 BTC/ETH/SOL/XRP up/down 5m/15m 市场以 Chainlink <COIN>/USD Data Stream 结算。
 * Polymarket 官方 RTDS（wss://ws-live-data.polymarket.com 的 crypto_prices_chainlink 频道）免鉴权推送
 * 同一条 Chainlink 流（含 full_accuracy_value，×1e18 全精度），故本服务直接订阅它，免去用户自建 Chainlink
 * Data Streams 凭证/feedID 的门槛，与 Polymarket 结算源保持一致。
 *
 * 设计：单条长连接（扩展复用 PolymarketWebSocketClient 的 ping/重连/代理）；内存维护每币种最新价与稀疏历史价。
 * 失败安全：未连接/数据未就绪一律返回 null，调用方据此跳过下单——绝不在错价或缺价上交易。
 */
@Service
class PolymarketRtdsCryptoPriceService {

    private val logger = LoggerFactory.getLogger(PolymarketRtdsCryptoPriceService::class.java)

    /** wss://ws-live-data.polymarket.com（RTDS crypto/comments/equity 同 host） */
    private val wsUrl = PolymarketConstants.ACTIVITY_WS_URL

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile
    private var wsClient: PolymarketWebSocketClient? = null

    @Volatile
    private var started = false

    /** 当前最新价：coin -> (price, tsMillis) */
    private val latestPrice = ConcurrentHashMap<String, Pair<BigDecimal, Long>>()

    /** 稀疏历史价：coin -> (tsSeconds 网格 -> price)，floorEntry 取期初/边界价 */
    private val priceHistory = ConcurrentHashMap<String, ConcurrentSkipListMap<Long, BigDecimal>>()

    /** 历史价保留时长（秒）：覆盖最深 σ 回看（15m × 20 周期 = 5h），留足 6h */
    private val historyRetentionSeconds = 6L * 3600
    /** 历史价采样网格（秒）：控制内存与 floorEntry 精度，5s 对 gap/σ 量级可忽略 */
    private val historyGridSeconds = 5L
    /** 最新价新鲜度阈值（毫秒）：RTDS 约每秒一推，超过则视为未就绪 */
    private val freshnessMs = 30_000L
    private val priceScale = 8

    companion object {
        /** RTDS chainlink 支持的币种（btc/usd、eth/usd、sol/usd、xrp/usd） */
        private val SUPPORTED_COINS = setOf("btc", "eth", "sol", "xrp")
        private val WEI = BigDecimal.TEN.pow(18)
    }

    // ---------------- 对外接口 ----------------

    /** 从市场 slug（btc-updown / btc-updown-5m / btc-updown-15m）解析币种代码（btc/eth/sol/xrp） */
    fun coinOfSlug(marketSlugPrefix: String): String? {
        val base = marketSlugPrefix.lowercase()
            .removeSuffix("-15m")
            .removeSuffix("-5m")
            .removeSuffix("-updown")
        return base.takeIf { it in SUPPORTED_COINS }
    }

    /** 该币种价源是否就绪：已连接且最新价新鲜 */
    fun isReady(marketSlugPrefix: String): Boolean {
        val coin = coinOfSlug(marketSlugPrefix) ?: return false
        ensureStarted()
        val cached = latestPrice[coin] ?: return false
        return wsClient?.isConnected() == true && System.currentTimeMillis() - cached.second <= freshnessMs
    }

    /** 当前最新价；未连接/不新鲜返回 null */
    fun currentPrice(marketSlugPrefix: String): BigDecimal? {
        val coin = coinOfSlug(marketSlugPrefix) ?: return null
        ensureStarted()
        val cached = latestPrice[coin] ?: return null
        if (System.currentTimeMillis() - cached.second > freshnessMs) return null
        return cached.first
    }

    /** 指定 Unix 秒时间戳处的价（floorEntry：取该时刻或之前最近一笔）；无覆盖返回 null */
    fun priceAt(marketSlugPrefix: String, tsSeconds: Long): BigDecimal? {
        val coin = coinOfSlug(marketSlugPrefix) ?: return null
        ensureStarted()
        val history = priceHistory[coin] ?: return null
        return history.floorEntry(tsSeconds)?.value
    }

    /** 最近完整 1m OHLC，按时间升序返回。使用 RTDS 历史价 5s 网格合成，不引入外部口径。 */
    fun recentOhlc1m(marketSlugPrefix: String, minutes: Int, nowSeconds: Long = System.currentTimeMillis() / 1000): List<PeriodPriceProvider.Ohlc1m> {
        if (minutes <= 0) return emptyList()
        val coin = coinOfSlug(marketSlugPrefix) ?: return emptyList()
        ensureStarted()
        val history = priceHistory[coin] ?: return emptyList()
        val endMinute = nowSeconds - (nowSeconds % 60)
        val result = ArrayList<PeriodPriceProvider.Ohlc1m>(minutes)
        for (i in minutes downTo 1) {
            val start = endMinute - i * 60L
            val end = start + 59L
            val points = history.subMap(start, true, end, true).entries
                .sortedBy { it.key }
                .map { it.value }
            if (points.isEmpty()) continue
            result.add(
                PeriodPriceProvider.Ohlc1m(
                    minuteStartUnix = start,
                    open = points.first(),
                    high = points.maxOrNull() ?: points.first(),
                    low = points.minOrNull() ?: points.first(),
                    close = points.last()
                )
            )
        }
        return result
    }

    // ---------------- 连接 / 订阅 ----------------

    private fun ensureStarted() {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            connect()
        }
    }

    private fun connect() {
        val client = PolymarketWebSocketClient(
            url = wsUrl,
            sessionId = "crypto-tail-rtds-chainlink",
            onMessage = { msg -> handleMessage(msg) },
            onOpen = { subscribe() },
            onReconnect = { subscribe() }
        )
        wsClient = client
        scope.launch {
            try {
                client.connect()
            } catch (e: Exception) {
                logger.error("RTDS 加密价源连接失败: ${e.message}", e)
            }
        }
    }

    private fun subscribe() {
        try {
            // 按已验证的报文格式逐币种订阅（filters 为 JSON 字符串 {"symbol":"btc/usd"}），稳妥且与 Polymarket 协议一致
            val subs = SUPPORTED_COINS.joinToString(",") { coin ->
                "{\"topic\":\"crypto_prices_chainlink\",\"type\":\"*\",\"filters\":\"{\\\"symbol\\\":\\\"$coin/usd\\\"}\"}"
            }
            wsClient?.sendMessage("{\"action\":\"subscribe\",\"subscriptions\":[$subs]}")
            logger.info("RTDS 加密价源已订阅 crypto_prices_chainlink（$SUPPORTED_COINS）")
        } catch (e: Exception) {
            logger.warn("RTDS 加密价源订阅失败: ${e.message}")
        }
    }

    private fun handleMessage(text: String) {
        if (text.isBlank() || text == "ping" || text == "pong") return
        val msg = text.fromJson<RtdsMessage>() ?: return
        val payload = msg.payload ?: return
        val coin = coinOfSymbol(payload.symbol) ?: return
        when {
            // 订阅回填快照：data 数组（最近约 2 分钟逐秒价）
            payload.data != null -> {
                for (point in payload.data) {
                    val price = doubleToPrice(point.value) ?: continue
                    if (point.timestamp > 0) recordHistory(coin, point.timestamp / 1000, price)
                }
            }
            // 实时更新：单点
            else -> {
                val price = payload.priceBd() ?: return
                val tsMs = when {
                    payload.timestamp > 0 -> payload.timestamp
                    msg.timestamp > 0 -> msg.timestamp
                    else -> System.currentTimeMillis()
                }
                updateLatest(coin, price, tsMs)
                recordHistory(coin, tsMs / 1000, price)
            }
        }
    }

    // ---------------- 内部缓存 ----------------

    private fun updateLatest(coin: String, price: BigDecimal, tsMs: Long) {
        latestPrice.compute(coin) { _, old ->
            if (old == null || tsMs >= old.second) price to tsMs else old
        }
    }

    /** 按 historyGridSeconds 网格落点写入（同槽位以最新价覆盖），并裁剪超期数据 */
    private fun recordHistory(coin: String, tsSeconds: Long, price: BigDecimal) {
        if (tsSeconds <= 0) return
        val map = priceHistory.computeIfAbsent(coin) { ConcurrentSkipListMap() }
        val slot = tsSeconds - (tsSeconds % historyGridSeconds)
        map[slot] = price
        val cutoff = (System.currentTimeMillis() / 1000) - historyRetentionSeconds
        val first = map.firstKey()
        if (first != null && first < cutoff) {
            map.headMap(cutoff).clear()
        }
    }

    private fun coinOfSymbol(symbol: String): String? {
        if (symbol.isBlank()) return null
        val base = symbol.lowercase().substringBefore("/")
        return base.takeIf { it in SUPPORTED_COINS }
    }

    private fun doubleToPrice(value: Double): BigDecimal? {
        if (value <= 0.0 || !value.isFinite()) return null
        return BigDecimal.valueOf(value).setScale(priceScale, RoundingMode.HALF_UP)
    }

    private fun RtdsPayload.priceBd(): BigDecimal? {
        val full = fullAccuracyValue
        if (!full.isNullOrBlank()) {
            return try {
                BigDecimal(BigInteger(full.trim())).divide(WEI, priceScale, RoundingMode.HALF_UP)
                    .takeIf { it > BigDecimal.ZERO }
            } catch (e: Exception) {
                doubleToPrice(value)
            }
        }
        return doubleToPrice(value)
    }

    @PreDestroy
    fun destroy() {
        try {
            wsClient?.closeConnection()
        } catch (e: Exception) {
            logger.warn("RTDS 加密价源关闭异常: ${e.message}")
        }
        wsClient = null
        scope.cancel()
    }

    // ---------------- RTDS 报文 DTO ----------------

    private data class RtdsMessage(
        val topic: String = "",
        val type: String = "",
        val timestamp: Long = 0,
        val payload: RtdsPayload? = null
    )

    private data class RtdsPayload(
        val symbol: String = "",
        val value: Double = 0.0,
        @SerializedName("full_accuracy_value") val fullAccuracyValue: String? = null,
        val timestamp: Long = 0,
        val data: List<RtdsPricePoint>? = null
    )

    private data class RtdsPricePoint(
        val timestamp: Long = 0,
        val value: Double = 0.0
    )
}
