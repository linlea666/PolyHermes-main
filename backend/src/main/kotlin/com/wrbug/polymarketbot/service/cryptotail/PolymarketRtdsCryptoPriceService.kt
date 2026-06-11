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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicLong

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

    private data class LatestPriceState(
        val price: BigDecimal,
        val sampleTimeMs: Long,
        val receivedAtMs: Long,
        val priceMode: String
    )

    /** 当前最新价：coin -> RTDS 样本价；新鲜度必须按 sampleTimeMs 计算，不能用收到消息的时间伪造。 */
    private val latestPrice = ConcurrentHashMap<String, LatestPriceState>()

    /** 稀疏历史价：coin -> (RTDS 样本秒 -> price)，floorEntry 取期初/边界价 */
    private val priceHistory = ConcurrentHashMap<String, ConcurrentSkipListMap<Long, BigDecimal>>()

    /** 历史样本去重：coin -> exact sampleTimeMs 集合，避免重复 snapshot 污染 OHLC/sigma/wick。 */
    private val seenHistorySamples = ConcurrentHashMap<String, MutableSet<Long>>()

    private val lastSnapshotAt = ConcurrentHashMap<String, Long>()
    private val lastRealtimeUpdateAt = ConcurrentHashMap<String, Long>()
    private val nextRefreshAllowedAt = ConcurrentHashMap<String, AtomicLong>()

    /** 历史价保留时长（秒）：覆盖最深 σ 回看（15m × 20 周期 = 5h），留足 6h */
    private val historyRetentionSeconds = 6L * 3600
    /** 最新价新鲜度阈值（毫秒）：RTDS 约每秒一推，超过则视为未就绪 */
    private val freshnessMs = 30_000L
    /** snapshot 刷新节流：只给缺 realtime / 缺 latest / stale 的币种补订阅。 */
    private val snapshotRefreshIntervalMs = 4_000L
    private val snapshotRefreshBackoffMs = 15_000L
    private val priceScale = 8

    companion object {
        /** RTDS chainlink 支持的币种（btc/usd、eth/usd、sol/usd、xrp/usd） */
        private val SUPPORTED_COINS = CryptoTailCoinResolver.supportedCoins
        private val WEI = BigDecimal.TEN.pow(18)
        const val PRICE_MODE_REALTIME_UPDATE = "REALTIME_UPDATE"
        const val PRICE_MODE_SUBSCRIBE_SNAPSHOT = "SUBSCRIBE_SNAPSHOT"
    }

    // ---------------- 对外接口 ----------------

    /** 从市场 slug（btc-updown / btc-updown-5m / btc-updown-15m）解析币种代码（btc/eth/sol/xrp） */
    fun coinOfSlug(marketSlugPrefix: String): String? {
        return CryptoTailCoinResolver.coinOfSlug(marketSlugPrefix)
    }

    /** 该币种价源是否就绪：已连接且最新价新鲜 */
    fun isReady(marketSlugPrefix: String): Boolean {
        val coin = coinOfSlug(marketSlugPrefix) ?: return false
        ensureStarted()
        val cached = latestPrice[coin] ?: return false
        return wsClient?.isConnected() == true && priceAgeMs(cached) <= freshnessMs
    }

    /** 当前最新价；未连接/不新鲜返回 null */
    fun currentPrice(marketSlugPrefix: String): BigDecimal? {
        val coin = coinOfSlug(marketSlugPrefix) ?: return null
        ensureStarted()
        val cached = latestPrice[coin] ?: return null
        if (priceAgeMs(cached) > freshnessMs) return null
        return cached.price
    }

    fun currentPriceAgeMs(marketSlugPrefix: String): Long? {
        val coin = coinOfSlug(marketSlugPrefix) ?: return null
        ensureStarted()
        val cached = latestPrice[coin] ?: return null
        return priceAgeMs(cached)
    }

    fun readiness(marketSlugPrefix: String): PeriodPriceProvider.PriceReadiness {
        val coin = coinOfSlug(marketSlugPrefix)
            ?: return PeriodPriceProvider.PriceReadiness("RTDS", null, false, "UNSUPPORTED_SLUG")
        ensureStarted()
        val connected = wsClient?.isConnected() == true
        if (!connected) return readinessFor(coin, false, "WS_DISCONNECTED")
        val cached = latestPrice[coin]
            ?: return readinessFor(coin, false, "NO_LATEST_PRICE")
        val age = priceAgeMs(cached)
        return if (age <= freshnessMs) {
            readinessFor(coin, true, "OK", cached)
        } else {
            readinessFor(coin, false, "STALE_PRICE", cached)
        }
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
                    close = points.last(),
                    tickCount = points.size
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
            startSnapshotRefreshLoop()
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
            wsClient?.sendMessage(subscribeMessage(SUPPORTED_COINS))
            logger.info("RTDS 加密价源已订阅 crypto_prices_chainlink（$SUPPORTED_COINS）")
        } catch (e: Exception) {
            logger.warn("RTDS 加密价源订阅失败: ${e.message}")
            backoffAllRefreshes()
        }
    }

    private fun subscribeCoins(coins: Collection<String>): Boolean {
        if (coins.isEmpty()) return true
        return try {
            wsClient?.sendMessage(subscribeMessage(coins))
            true
        } catch (e: Exception) {
            val now = System.currentTimeMillis()
            coins.forEach { nextRefreshAllowedAt.computeIfAbsent(it) { AtomicLong(0) }.set(now + snapshotRefreshBackoffMs) }
            logger.warn("RTDS 加密价源补订阅失败 coins=$coins: ${e.message}")
            false
        }
    }

    private fun subscribeMessage(coins: Collection<String>): String {
        val subs = coins.joinToString(",") { coin ->
            "{\"topic\":\"crypto_prices_chainlink\",\"type\":\"*\",\"filters\":\"{\\\"symbol\\\":\\\"$coin/usd\\\"}\"}"
        }
        return "{\"action\":\"subscribe\",\"subscriptions\":[$subs]}"
    }

    private fun startSnapshotRefreshLoop() {
        scope.launch {
            while (isActive) {
                delay(1_000L)
                refreshSnapshotSubscriptionsIfNeeded()
            }
        }
    }

    private fun refreshSnapshotSubscriptionsIfNeeded(now: Long = System.currentTimeMillis()) {
        val client = wsClient
        if (client?.isConnected() != true) {
            SUPPORTED_COINS.forEach { nextRefreshAllowedAt.computeIfAbsent(it) { AtomicLong(0) }.set(now + snapshotRefreshIntervalMs) }
            return
        }
        val due = SUPPORTED_COINS.filter { coin ->
            val allowedAt = nextRefreshAllowedAt.computeIfAbsent(coin) { AtomicLong(0) }
            if (now < allowedAt.get()) return@filter false
            val latest = latestPrice[coin]
            // 价已陈旧（含无数据）即补订阅；阈值用补订阅间隔(4s)而非 freshnessMs(30s)：
            // realtime 断流多落在 6-29s(<30s) 区间，旧阈值在此区间不触发补订阅，snapshot 根本没被请求，
            // 导致 priceAge 长期卡在 10-30s。改为按实际新鲜度触发，使新鲜 snapshot 及时被请求并救场。
            latest == null || priceAgeMs(latest, now) > snapshotRefreshIntervalMs
        }
        if (due.isEmpty()) return
        due.forEach { nextRefreshAllowedAt.computeIfAbsent(it) { AtomicLong(0) }.set(now + snapshotRefreshIntervalMs) }
        subscribeCoins(due)
    }

    private fun handleMessage(text: String) {
        if (text.isBlank() || text == "ping" || text == "pong") return
        val msg = text.fromJson<RtdsMessage>() ?: return
        val payload = msg.payload ?: return
        val coin = coinOfSymbol(payload.symbol) ?: return
        when {
            // 订阅回填快照：data 数组（最近约 2 分钟逐秒价）
            payload.data != null -> {
                lastSnapshotAt[coin] = System.currentTimeMillis()
                for (point in payload.data) {
                    val price = doubleToPrice(point.value) ?: continue
                    val sampleTimeMs = point.timestamp.takeIf { it > 0 } ?: continue
                    recordHistory(coin, sampleTimeMs, price)
                    updateLatest(coin, price, sampleTimeMs, PRICE_MODE_SUBSCRIBE_SNAPSHOT)
                }
            }
            // 实时更新：单点
            else -> {
                val price = payload.priceBd() ?: return
                val sampleTimeMs = when {
                    payload.timestamp > 0 -> payload.timestamp
                    msg.timestamp > 0 -> msg.timestamp
                    else -> return
                }
                lastRealtimeUpdateAt[coin] = System.currentTimeMillis()
                updateLatest(coin, price, sampleTimeMs, PRICE_MODE_REALTIME_UPDATE)
                recordHistory(coin, sampleTimeMs, price)
            }
        }
    }

    // ---------------- 内部缓存 ----------------

    private fun updateLatest(coin: String, price: BigDecimal, sampleTimeMs: Long, priceMode: String) {
        if (sampleTimeMs <= 0) return
        val now = System.currentTimeMillis()
        latestPrice.compute(coin) { _, old ->
            val incoming = LatestPriceState(price, sampleTimeMs, now, priceMode)
            when {
                old == null -> incoming
                // 实时更新：realtime 是权威实时源——覆盖任何 snapshot；realtime 之间按样本时间单调，防止旧实时 tick 倒灌。
                priceMode == PRICE_MODE_REALTIME_UPDATE ->
                    if (old.priceMode == PRICE_MODE_REALTIME_UPDATE) {
                        if (sampleTimeMs > old.sampleTimeMs) incoming else old
                    } else {
                        incoming
                    }
                // 来的是 snapshot：仅当样本时间确实更新才可能覆盖（单调 strict > 防旧 snapshot 倒灌；
                // 与 realtime 同样本时间则保留 old，realtime 优先）。
                // 且若 old 是"样本仍新鲜"的 realtime（样本龄 <= snapshotRefreshIntervalMs，与全局新鲜度口径一致按
                // sampleTimeMs 计龄），即使 snapshot 样本时间更新也不覆盖——上游批处理/时钟偏移会让 snapshot
                // 样本时间虚高，权威实时 tick 不能被倒灌；realtime 真断流时（样本龄 >4s，正是补订阅触发线）照常救场。
                // 历史 bug：此前用 30s freshnessMs 阻断 snapshot，救场窗口被掐死 → 阈值必须对齐补订阅节奏而非 30s。
                sampleTimeMs > old.sampleTimeMs &&
                    (old.priceMode != PRICE_MODE_REALTIME_UPDATE || now - old.sampleTimeMs > snapshotRefreshIntervalMs) -> incoming
                else -> old
            }
        }
    }

    /** 按 RTDS 样本秒写入历史，并用 exact sampleTimeMs 去重。 */
    private fun recordHistory(coin: String, sampleTimeMs: Long, price: BigDecimal) {
        if (sampleTimeMs <= 0) return
        val seen = seenHistorySamples.computeIfAbsent(coin) { ConcurrentHashMap.newKeySet() }
        if (!seen.add(sampleTimeMs)) return
        val tsSeconds = sampleTimeMs / 1000
        if (tsSeconds <= 0) return
        val map = priceHistory.computeIfAbsent(coin) { ConcurrentSkipListMap() }
        map[tsSeconds] = price
        val cutoff = (System.currentTimeMillis() / 1000) - historyRetentionSeconds
        val first = if (map.isEmpty()) null else map.firstKey()
        if (first != null && first < cutoff) {
            map.headMap(cutoff).clear()
            seen.removeIf { (it / 1000) < cutoff }
        }
    }

    private fun priceAgeMs(state: LatestPriceState, now: Long = System.currentTimeMillis()): Long =
        (now - state.sampleTimeMs).coerceAtLeast(0L)

    private fun readinessFor(
        coin: String,
        ready: Boolean,
        reason: String,
        latest: LatestPriceState? = latestPrice[coin]
    ): PeriodPriceProvider.PriceReadiness {
        val age = latest?.let { priceAgeMs(it) }
        return PeriodPriceProvider.PriceReadiness(
            source = "RTDS",
            coin = coin,
            ready = ready,
            reason = reason,
            ageMs = age,
            priceMode = latest?.priceMode,
            lastSnapshotAt = lastSnapshotAt[coin],
            lastRealtimeUpdateAt = lastRealtimeUpdateAt[coin],
            latestPriceAgeMs = age,
            latestSampleTime = latest?.sampleTimeMs
        )
    }

    private fun backoffAllRefreshes() {
        val next = System.currentTimeMillis() + snapshotRefreshBackoffMs
        SUPPORTED_COINS.forEach { nextRefreshAllowedAt.computeIfAbsent(it) { AtomicLong(0) }.set(next) }
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
