package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.api.GammaEventBySlugResponse
import com.wrbug.polymarketbot.constants.PolymarketConstants
import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import com.wrbug.polymarketbot.enums.SpreadMode
import com.wrbug.polymarketbot.enums.TradingMode
import com.wrbug.polymarketbot.event.CryptoTailStrategyChangedEvent
import com.wrbug.polymarketbot.repository.CryptoTailStrategyRepository
import com.wrbug.polymarketbot.service.binance.BinanceKlineAutoSpreadService
import com.wrbug.polymarketbot.service.binance.BinanceKlineService
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.createClient
import com.wrbug.polymarketbot.util.fromJson
import com.wrbug.polymarketbot.util.gt
import com.wrbug.polymarketbot.util.toJson
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 加密价差策略订单簿 WebSocket 监听：订阅 CLOB Market 频道，收到订单簿/价格变更时若满足条件立即触发下单。
 */
@Service
class CryptoTailOrderbookWsService(
    private val strategyRepository: CryptoTailStrategyRepository,
    private val executionService: CryptoTailStrategyExecutionService,
    private val retrofitFactory: RetrofitFactory,
    private val binanceKlineAutoSpreadService: BinanceKlineAutoSpreadService,
    private val binanceKlineService: BinanceKlineService,
    private val periodPriceProvider: PeriodPriceProvider,
    private val bracketExitService: CryptoTailBracketExitService,
    private val entrySegmentResolver: com.wrbug.polymarketbot.service.cryptotail.taildiff.TailDiffEntrySegmentResolver,
    private val orderbookCache: CryptoTailOrderbookCache
) {

    private val logger = LoggerFactory.getLogger(CryptoTailOrderbookWsService::class.java)

    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + scopeJob)

    /** tokenId -> list of (strategy, periodStartUnix, marketTitle, tokenIds, outcomeIndex) */
    private val tokenToEntries = AtomicReference<Map<String, List<WsBookEntry>>>(emptyMap())

    /** assetId -> 最近一次盘口质量快照（共享缓存，供执行服务发单前读取最新 WS 帧）；price_change 缺深度时复用 book 快照并标记 depthStale */

    private var webSocket: WebSocket? = null
    private val wsUrl = PolymarketConstants.RTDS_WS_URL + "/ws/market"
    private val client = createClient().build()

    /** 订阅成功后设置的倒计时 Job，在周期结束时自动刷新订阅 */
    private var periodEndCountdownJob: Job? = null

    /** 重连延迟（毫秒） */
    private val reconnectDelayMs = 3_000L

    /** 因无启用策略而主动关闭 WS 时置为 true，onClosing 中不触发重连 */
    private val closedForNoStrategies = AtomicBoolean(false)

    /** 保护 connect() 的互斥锁，避免多线程并发创建连接 */
    private val connectLock = Any()

    /** 保护 refreshAndSubscribe() 的互斥锁，避免多线程并发刷新订阅 */
    private val refreshLock = Any()

    /** 标记是否正在刷新订阅，避免重复调用 */
    private val isRefreshing = AtomicBoolean(false)

    /**
     * 启用策略列表短时缓存：handleMessage 每条 WS 消息都要做"周期是否变化"检查（maybeRefreshSubscriptionIfPeriodChanged），
     * 原实现每条消息都 findAllByEnabledTrue() 查库，高频盘口下放大 DB 负载。这里加 1s TTL 缓存；
     * 策略增删改由 onStrategyChanged 直接触发 refreshAndSubscribe，缓存失效在那里强制刷新即可，故 1s 陈旧无副作用。
     */
    @Volatile
    private var cachedEnabledStrategies: List<CryptoTailStrategy> = emptyList()

    @Volatile
    private var cachedEnabledAt: Long = 0L

    private val enabledCacheTtlMs = 1_000L

    /** per-subscriptionKey 入场评估在途守卫：同一订阅同时只跑一个入场评估，避免高频盘口下协程无界扇出吃满 CPU */
    private val entryEvalInFlight = java.util.concurrent.ConcurrentHashMap<String, AtomicBoolean>()

    /** per-subscriptionKey 退出评估在途守卫：同上，避免退出评估协程堆积 */
    private val exitEvalInFlight = java.util.concurrent.ConcurrentHashMap<String, AtomicBoolean>()

    private fun enabledStrategiesCached(): List<CryptoTailStrategy> {
        val now = System.currentTimeMillis()
        if (now - cachedEnabledAt < enabledCacheTtlMs) return cachedEnabledStrategies
        val fresh = strategyRepository.findAllByEnabledTrue()
        cachedEnabledStrategies = fresh
        cachedEnabledAt = now
        return fresh
    }

    data class WsBookEntry(
        val strategy: CryptoTailStrategy,
        val periodStartUnix: Long,
        val marketSlug: String,
        val tokenId: String,
        val marketTitle: String?,
        val tokenIds: List<String>,
        val outcomeIndex: Int,
        val subscriptionKey: String
    )

    @PostConstruct
    fun init() {
        if (strategyRepository.findAllByEnabledTrue().isNotEmpty()) connect()
    }

    @PreDestroy
    fun destroy() {
        periodEndCountdownJob?.cancel()
        periodEndCountdownJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        synchronized(precomputeJobs) {
            precomputeJobs.forEach { it.cancel() }
            precomputeJobs.clear()
        }
        closedForNoStrategies.set(true)
        try {
            webSocket?.close(1000, "shutdown")
        } catch (e: Exception) {
            logger.debug("关闭加密价差策略 WebSocket 时异常: ${e.message}")
        }
        webSocket = null
        scopeJob.cancel()
    }

    private fun connect() {
        synchronized(connectLock) {
            if (webSocket != null) return
            try {
                val request = Request.Builder().url(wsUrl).build()
                webSocket = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                        logger.info("加密价差策略订单簿 WebSocket 已连接")
                        refreshAndSubscribe(fromConnect = true)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        handleMessage(text)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        this@CryptoTailOrderbookWsService.webSocket = null
                        if (!closedForNoStrategies.getAndSet(false)) scheduleReconnect()
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                        logger.warn("加密价差策略订单簿 WebSocket 异常: ${t.message}")
                        this@CryptoTailOrderbookWsService.webSocket = null
                        scheduleReconnect()
                    }
                })
            } catch (e: Exception) {
                logger.error("加密价差策略订单簿 WebSocket 连接失败: ${e.message}", e)
                scheduleReconnect()
            }
        }
    }

    private var reconnectJob: Job? = null

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            delay(reconnectDelayMs)
            reconnectJob = null
            if (strategyRepository.findAllByEnabledTrue().isEmpty()) return@launch
            logger.info("加密价差策略订单簿 WebSocket 尝试重连")
            connect()
        }
    }

    private fun handleMessage(text: String) {
        if (text == "pong" || text.isEmpty()) return
        if (closedForNoStrategies.get()) return
        maybeRefreshSubscriptionIfPeriodChanged()
        val json = text.fromJson<com.google.gson.JsonObject>() ?: return
        val eventType = (json.get("event_type") as? com.google.gson.JsonPrimitive)?.asString ?: return

        when (eventType) {
            "book" -> {
                val assetId = (json.get("asset_id") as? com.google.gson.JsonPrimitive)?.asString ?: return
                val bids = json.get("bids") as? com.google.gson.JsonArray
                if (bids == null || bids.isEmpty) return
                // Polymarket book 的 bids 为价格升序，bids[0] 为最低买价；bestBid 应取最高买价
                var bestBid: BigDecimal? = null
                var bestBidSize: BigDecimal? = null
                var bidDepthUsd = BigDecimal.ZERO
                val bidLevels = mutableListOf<OrderbookQualitySnapshot.BookLevel>()
                for (i in 0 until bids.size()) {
                    val level = bids.get(i) as? com.google.gson.JsonObject ?: continue
                    val p = (level.get("price") as? com.google.gson.JsonPrimitive)?.asString?.toSafeBigDecimal() ?: continue
                    val size = (level.get("size") as? com.google.gson.JsonPrimitive)?.asString?.toSafeBigDecimal() ?: BigDecimal.ZERO
                    bidDepthUsd = bidDepthUsd.add(p.multiply(size))
                    bidLevels.add(OrderbookQualitySnapshot.BookLevel(p, size))
                    if (bestBid == null || p.gt(bestBid)) {
                        bestBid = p
                        bestBidSize = size
                    }
                }
                // asks 取最低卖价作为 bestAsk（用于障碍模式 EV 闸的有效成本）
                val asks = json.get("asks") as? com.google.gson.JsonArray
                var bestAsk: BigDecimal? = null
                var bestAskSize: BigDecimal? = null
                var askDepthUsd = BigDecimal.ZERO
                val askLevels = mutableListOf<OrderbookQualitySnapshot.BookLevel>()
                if (asks != null) {
                    for (i in 0 until asks.size()) {
                        val level = asks.get(i) as? com.google.gson.JsonObject ?: continue
                        val p = (level.get("price") as? com.google.gson.JsonPrimitive)?.asString?.toSafeBigDecimal() ?: continue
                        val size = (level.get("size") as? com.google.gson.JsonPrimitive)?.asString?.toSafeBigDecimal() ?: BigDecimal.ZERO
                        askDepthUsd = askDepthUsd.add(p.multiply(size))
                        askLevels.add(OrderbookQualitySnapshot.BookLevel(p, size))
                        if (bestAsk == null || p < bestAsk) {
                            bestAsk = p
                            bestAskSize = size
                        }
                    }
                }
                if (bestBid != null) {
                    val nowMs = System.currentTimeMillis()
                    val snapshot = OrderbookQualitySnapshot(
                        tokenId = assetId,
                        bestBid = bestBid,
                        bestAsk = bestAsk,
                        bidSize = bestBidSize,
                        askSize = bestAskSize,
                        bidDepthUsd = bidDepthUsd,
                        askDepthUsd = askDepthUsd,
                        spread = bestAsk?.subtract(bestBid),
                        quoteUpdatedAtMs = nowMs,
                        depthUpdatedAtMs = nowMs,
                        askUpdatedAtMs = bestAsk?.let { nowMs },
                        depthStale = false,
                        bidLevels = bidLevels.sortedByDescending { it.price },
                        askLevels = askLevels.sortedBy { it.price }
                    )
                    orderbookCache.put(assetId, snapshot)
                    onBestBid(assetId, snapshot)
                }
            }

            "price_change" -> {
                val priceChanges = json.get("price_changes") as? com.google.gson.JsonArray ?: return
                for (i in 0 until priceChanges.size()) {
                    val pc = priceChanges.get(i) as? com.google.gson.JsonObject ?: continue
                    val assetId = (pc.get("asset_id") as? com.google.gson.JsonPrimitive)?.asString ?: continue
                    val bestBidStr = (pc.get("best_bid") as? com.google.gson.JsonPrimitive)?.asString
                    val bestBid = bestBidStr?.toSafeBigDecimal()
                    val bestAskStr = (pc.get("best_ask") as? com.google.gson.JsonPrimitive)?.asString
                    val bestAsk = bestAskStr?.toSafeBigDecimal()
                    if (bestBid != null) {
                        val prev = orderbookCache.get(assetId)
                        val nowMs = System.currentTimeMillis()
                        val snapshot = OrderbookQualitySnapshot(
                            tokenId = assetId,
                            bestBid = bestBid,
                            bestAsk = bestAsk ?: prev?.bestAsk,
                            bidSize = prev?.bidSize,
                            askSize = prev?.askSize,
                            bidDepthUsd = prev?.bidDepthUsd,
                            askDepthUsd = prev?.askDepthUsd,
                            spread = (bestAsk ?: prev?.bestAsk)?.subtract(bestBid),
                            quoteUpdatedAtMs = nowMs,
                            depthUpdatedAtMs = prev?.depthUpdatedAtMs,
                            askUpdatedAtMs = if (bestAsk != null) nowMs else prev?.askUpdatedAtMs,
                            depthStale = prev?.depthUpdatedAtMs == null,
                            bidLevels = prev?.bidLevels ?: emptyList()
                        )
                        orderbookCache.put(assetId, snapshot)
                        onBestBid(assetId, snapshot)
                    }
                }
            }
        }
    }

    fun latestSnapshot(tokenId: String): OrderbookQualitySnapshot? = orderbookCache.latestSnapshot(tokenId)

    /**
     * TAIL_DIFF 评分预览的实时上下文：取该策略某 outcome 当前已订阅周期 + 最新盘口快照。
     * 仅当策略已启用且 WS 已订阅该周期 token、且已收到至少一条盘口时返回；否则 null
     * （preview 据此返回"实时数据未就绪"，与实盘 evaluate 的价源/盘口未就绪语义一致）。
     */
    data class LivePreviewContext(
        val periodStartUnix: Long,
        val tokenId: String,
        val orderbook: OrderbookQualitySnapshot
    )

    fun livePreviewContext(strategyId: Long, outcomeIndex: Int): LivePreviewContext? {
        val entry = tokenToEntries.get().values.asSequence().flatten()
            .firstOrNull { it.strategy.id == strategyId && it.outcomeIndex == outcomeIndex }
            ?: return null
        val snapshot = orderbookCache.get(entry.tokenId) ?: return null
        return LivePreviewContext(entry.periodStartUnix, entry.tokenId, snapshot)
    }

    private fun onBestBid(tokenId: String, orderbook: OrderbookQualitySnapshot) {
        if (closedForNoStrategies.get()) return
        val entries = tokenToEntries.get()[tokenId]
        if (entries == null) return
        val nowSeconds = System.currentTimeMillis() / 1000
        for (e in entries) {
            // 入场窗口预过滤：
            //  - TAIL_DIFF 用分段包络（remaining 维度），覆盖早窗分段，避免被全局 elapsed 窗（windowStart/EndSeconds）误卡；
            //    精确窗口/分段命中仍由 DecisionService 内的 segment resolve + WINDOW_* 否决把关，这里只做粗过滤减少协程启动。
            //  - 其他模式保持原 elapsed 时间窗行为不变。
            val inEntryWindow = when (e.strategy.mode) {
                TradingMode.TAIL_DIFF -> {
                    val (envLo, envHi) = entrySegmentResolver.windowEnvelope(e.strategy)
                    val remaining = (e.periodStartUnix + e.strategy.intervalSeconds) - nowSeconds
                    remaining in envLo.toLong()..envHi.toLong()
                }
                // SCALP_FLIP 用专属窗口（elapsed 维度）：[scalpWindowStart, scalpWindowEnd) 或 windowEnd=0 时收口到 interval-minRemaining；
                // 精确窗口/剩余时间仍由 evaluateScalpEntryGates 内的 SCALP_WINDOW/SCALP_MIN_REMAINING 否决把关，这里只做粗过滤。
                TradingMode.SCALP_FLIP -> {
                    val s = e.strategy
                    val windowStart = e.periodStartUnix + s.scalpWindowStartSeconds
                    val upperElapsed = if (s.scalpWindowEndSeconds > 0) s.scalpWindowEndSeconds else (s.intervalSeconds - s.scalpMinRemainingSeconds)
                    val windowEnd = e.periodStartUnix + upperElapsed
                    nowSeconds >= windowStart && nowSeconds < windowEnd
                }
                else -> {
                    val windowStart = e.periodStartUnix + e.strategy.windowStartSeconds
                    val windowEnd = e.periodStartUnix + e.strategy.windowEndSeconds
                    nowSeconds >= windowStart && nowSeconds < windowEnd
                }
            }

            // 入场分支：仅在时间窗内评估。
            // per-subscriptionKey 在途守卫：同一订阅已有入场评估在跑则跳过本 tick，避免协程无界堆积；
            // 下一条盘口消息会用更新的快照重新评估，不会漏单。
            if (inEntryWindow) {
                val entryGuard = entryEvalInFlight.getOrPut(e.subscriptionKey) { AtomicBoolean(false) }
                if (entryGuard.compareAndSet(false, true)) {
                    scope.launch {
                        try {
                            executionService.tryTriggerWithPriceFromWs(
                                strategy = e.strategy,
                                periodStartUnix = e.periodStartUnix,
                                marketTitle = e.marketTitle,
                                tokenIds = e.tokenIds,
                                outcomeIndex = e.outcomeIndex,
                                bestBid = orderbook.bestBid,
                                bestAsk = orderbook.bestAsk,
                                orderbook = orderbook
                            )
                        } catch (ex: Exception) {
                            logger.error("WS 触发下单异常: strategyId=${e.strategy.id}, ${ex.message}", ex)
                        } finally {
                            entryGuard.set(false)
                        }
                    }
                }
            }

            // 退出分支：概率模式启用，且不受时间窗限制
            //  - 持仓监听整个周期内都需活跃，包括 forceExitBeforeSettleSeconds 兜底窗口
            //  - 跨周期由 SettlementService 兜底（HELD_TO_SETTLE）
            if (e.strategy.mode != TradingMode.LEGACY_SPREAD && e.strategy.enableExitManager) {
                val exitGuard = exitEvalInFlight.getOrPut(e.subscriptionKey) { AtomicBoolean(false) }
                if (exitGuard.compareAndSet(false, true)) {
                    scope.launch {
                        try {
                            bracketExitService.evaluatePeriodOutcome(
                                strategy = e.strategy,
                                periodStartUnix = e.periodStartUnix,
                                outcomeIndex = e.outcomeIndex,
                                bestBid = orderbook.bestBid,
                                nowSeconds = nowSeconds,
                                orderbook = orderbook
                            )
                        } catch (ex: Exception) {
                            logger.error("WS 阶梯退出评估异常: strategyId=${e.strategy.id}, ${ex.message}", ex)
                        } finally {
                            exitGuard.set(false)
                        }
                    }
                }
            }
        }
    }

    /**
     * 事件驱动：仅在收到 WS 消息时检查当前周期是否变化，若变化则刷新订阅，无需定时轮询。
     */
    private fun maybeRefreshSubscriptionIfPeriodChanged() {
        val subscribed = tokenToEntries.get().values.flatten().distinctBy { it.strategy.id }
            .associate { it.strategy.id!! to it.periodStartUnix }
        if (subscribed.isEmpty()) return
        val strategies = enabledStrategiesCached()
        val nowSeconds = System.currentTimeMillis() / 1000
        val currentStrategyIds = strategies.map { it.id!! }.toSet()
        if (subscribed.keys != currentStrategyIds) {
            refreshAndSubscribe()
            return
        }
        for (s in strategies) {
            val currentPeriod = (nowSeconds / s.intervalSeconds) * s.intervalSeconds
            val subPeriod = subscribed[s.id!!] ?: continue
            if (currentPeriod != subPeriod) {
                refreshAndSubscribe()
                return
            }
        }
    }

    private fun refreshAndSubscribe(fromConnect: Boolean = false) {
        synchronized(refreshLock) {
            // 如果正在刷新，直接返回，避免重复调用
            if (isRefreshing.get()) {
                logger.debug("加密价差策略订阅刷新已在进行中，跳过本次调用")
                return
            }
            isRefreshing.set(true)
        }
        try {
            val strategies = strategyRepository.findAllByEnabledTrue()
            binanceKlineService.updateSubscriptions(strategies.map { it.marketSlugPrefix }.toSet())
            periodEndCountdownJob?.cancel()
            periodEndCountdownJob = null
            val oldTokenIds = tokenToEntries.get().keys.toSet()
            val (tokenIds, newMap) = buildSubscriptionMap()
            tokenToEntries.set(newMap)
            if (tokenIds.isEmpty()) {
                closeWebSocketForNoStrategies()
                return
            }
            if (!fromConnect) {
                if (webSocket == null) {
                    connect()
                    return
                }
                if (oldTokenIds == tokenIds.toSet()) {
                    scheduleRefreshAtPeriodEnd(newMap)
                    precomputeAutoSpreadForCurrentPeriods(newMap)
                    return
                }
                closeWebSocketAndReconnect()
                return
            }
            val marketSlugs = newMap.values.asSequence().flatten()
                .distinctBy { "${it.strategy.marketSlugPrefix}-${it.periodStartUnix}" }
                .map { "${it.strategy.marketSlugPrefix}-${it.periodStartUnix}" }
                .toList()
            val msg = """{"type":"MARKET","assets_ids":${tokenIds.toJson()}}"""
            try {
                webSocket?.send(msg)
                logger.info("加密价差策略订单簿订阅: ${tokenIds.size} 个 token, 市场: $marketSlugs")
            } catch (e: Exception) {
                logger.warn("发送订阅失败: ${e.message}")
                return
            }
            scheduleRefreshAtPeriodEnd(newMap)
            precomputeAutoSpreadForCurrentPeriods(newMap)
        } finally {
            isRefreshing.set(false)
        }
    }

    /**
     * 订阅更新时关闭当前 WebSocket，由 onClosing 触发重连，重连后 onOpen 会重新订阅。
     */
    private fun closeWebSocketAndReconnect() {
        val ws = webSocket
        if (ws != null) {
            webSocket = null
            try {
                ws.close(1000, "subscription_change")
            } catch (e: Exception) {
                logger.debug("关闭加密价差策略 WebSocket 时异常: ${e.message}")
            }
            logger.info("加密价差策略订单簿 WebSocket 已关闭（订阅更新，将重连）")
        }
    }

    /** 跟踪预计算价差的协程 Job，用于在关闭时取消 */
    private val precomputeJobs = mutableSetOf<Job>()

    /**
     * AUTO 模式：在周期开始（刷新订阅）时预拉历史 30 根 K 线并计算该周期价差，触发时直接用缓存。
     */
    private fun precomputeAutoSpreadForCurrentPeriods(newMap: Map<String, List<WsBookEntry>>) {
        // V53 修正：预热触发条件按 mode 派生，包含 BARRIER_HOLD 和 BRACKET_DYNAMIC（两者都依赖 Chainlink 期初/σ）。
        // 此前用 strategy.barrierEnabled 会漏掉 BRACKET 模式（barrierEnabled=false），导致热路径首个 tick 同步阻塞拉链上价。
        val autoPeriods = newMap.values.asSequence().flatten()
            .filter { it.strategy.spreadMode == SpreadMode.AUTO || it.strategy.mode != TradingMode.LEGACY_SPREAD }
            .distinctBy { "${it.strategy.marketSlugPrefix}-${it.strategy.intervalSeconds}-${it.periodStartUnix}" }
            .map { Triple(it.strategy.marketSlugPrefix, it.strategy.intervalSeconds, it.periodStartUnix) }
            .toList()
        if (autoPeriods.isEmpty()) return
        // 障碍/阶梯模式：周期开始预热 Chainlink 价源（期初/当前/σ），让 WS 热路径命中缓存，避免首个 tick 阻塞拉取
        val barrierPeriods = newMap.values.asSequence().flatten()
            .filter { it.strategy.mode != TradingMode.LEGACY_SPREAD }
            .distinctBy { "${it.strategy.marketSlugPrefix}-${it.strategy.intervalSeconds}-${it.periodStartUnix}" }
            .map {
                Triple(it.strategy.marketSlugPrefix, it.strategy.intervalSeconds, it.periodStartUnix) to
                    Triple(it.strategy.sigmaScale, it.strategy.sigmaMethod, it.strategy.ewmaLambda)
            }
            .toList()

        val job = scope.launch {
            for ((marketPrefix, intervalSeconds, periodStartUnix) in autoPeriods) {
                try {
                    val pair = binanceKlineAutoSpreadService.computeAndCache(marketPrefix, intervalSeconds, periodStartUnix)
                    if (pair != null) {
                        logger.info(
                            "周期开始初始价差: market=$marketPrefix interval=${intervalSeconds}s periodStartUnix=$periodStartUnix " +
                                    "baseSpreadUp=${pair.first.toPlainString()} baseSpreadDown=${pair.second.toPlainString()}"
                        )
                    }
                } catch (e: Exception) {
                    logger.warn("周期开始预计算 AUTO 价差失败: market=$marketPrefix interval=$intervalSeconds periodStartUnix=$periodStartUnix ${e.message}")
                }
            }
            for ((triple, sigmaCfg) in barrierPeriods) {
                val (marketPrefix, intervalSeconds, periodStartUnix) = triple
                val (sigmaScale, sigmaMethod, ewmaLambda) = sigmaCfg
                if (!periodPriceProvider.isAvailable(marketPrefix)) continue
                try {
                    periodPriceProvider.getCurrentOpenClose(marketPrefix, intervalSeconds, periodStartUnix)
                    periodPriceProvider.getSigmaPerSqrtS(marketPrefix, intervalSeconds, periodStartUnix, 0, sigmaScale, sigmaMethod, ewmaLambda)
                    logger.info("障碍模式周期开始预热 Chainlink 价源: market=$marketPrefix interval=${intervalSeconds}s periodStartUnix=$periodStartUnix")
                } catch (e: Exception) {
                    logger.warn("障碍模式预热 Chainlink 失败: market=$marketPrefix interval=$intervalSeconds periodStartUnix=$periodStartUnix ${e.message}")
                }
            }
        }
        synchronized(precomputeJobs) {
            precomputeJobs.add(job)
            // 清理已完成的 Job，避免集合无限增长
            precomputeJobs.removeIf { !it.isActive }
        }
    }

    /**
     * 无启用策略或无需订阅时关闭 WebSocket，并取消重连；停用策略后刷新订阅会走到此处。
     */
    private fun closeWebSocketForNoStrategies() {
        reconnectJob?.cancel()
        reconnectJob = null
        val ws = webSocket
        if (ws != null) {
            closedForNoStrategies.set(true)
            webSocket = null
            try {
                ws.close(1000, "no_enabled_strategies")
            } catch (e: Exception) {
                logger.debug("关闭加密价差策略 WebSocket 时异常: ${e.message}")
            }
            logger.info("加密价差策略订单簿 WebSocket 已关闭（无启用策略）")
        }
    }

    /**
     * 订阅成功后设置倒计时：在当前周期结束时自动刷新订阅，无需等消息触发。
     */
    private fun scheduleRefreshAtPeriodEnd(newMap: Map<String, List<WsBookEntry>>) {
        val entries = newMap.values.flatten()
        if (entries.isEmpty()) return
        val nextPeriodEndSeconds = entries.minOf { it.periodStartUnix + it.strategy.intervalSeconds }
        val delayMs = (nextPeriodEndSeconds * 1000) - System.currentTimeMillis() + 2000
        if (delayMs <= 0) return
        periodEndCountdownJob = scope.launch {
            delay(delayMs)
            periodEndCountdownJob = null
            refreshAndSubscribe()
        }
        logger.debug("加密价差策略订单簿订阅倒计时: ${delayMs / 1000}s 后刷新")
    }

    private fun buildSubscriptionMap(): Pair<List<String>, Map<String, List<WsBookEntry>>> {
        val strategies = strategyRepository.findAllByEnabledTrue()
        val nowSeconds = System.currentTimeMillis() / 1000
        val tokenIdSet = mutableSetOf<String>()
        val map = mutableMapOf<String, MutableList<WsBookEntry>>()

        for (strategy in strategies) {
            val interval = strategy.intervalSeconds
            val periodStartUnix = (nowSeconds / interval) * interval
            val windowEnd = periodStartUnix + strategy.windowEndSeconds
            val periodEnd = periodStartUnix + interval
            val needFullPeriodMonitor = strategy.mode != TradingMode.LEGACY_SPREAD && strategy.enableExitManager
            val cutoff = if (needFullPeriodMonitor) periodEnd else windowEnd
            val fullPeriodReason = when {
                strategy.mode == TradingMode.LEGACY_SPREAD -> "LEGACY_SPREAD_ENTRY_ONLY"
                !strategy.enableExitManager -> "EXIT_MANAGER_DISABLED"
                else -> "EXIT_MANAGER_ENABLED"
            }
            val fullPeriodLog =
                "FULL_PERIOD_MONITOR_ENABLED strategyId=${strategy.id}, slug=${strategy.marketSlugPrefix}, " +
                    "enabled=$needFullPeriodMonitor, mode=${strategy.mode.name}, windowEnd=$windowEnd, " +
                    "periodEnd=$periodEnd, cutoff=$cutoff, reason=$fullPeriodReason"
            if (needFullPeriodMonitor) logger.info(fullPeriodLog) else logger.debug(fullPeriodLog)
            if (nowSeconds >= cutoff) {
                logger.debug(
                    "加密价差策略跳过（已过订阅窗口）: strategyId=${strategy.id}, slug=${strategy.marketSlugPrefix}, " +
                        "mode=${strategy.mode.name}, cutoff=$cutoff (windowEnd=$windowEnd, periodEnd=$periodEnd)"
                )
                continue
            }
            val slug = "${strategy.marketSlugPrefix}-$periodStartUnix"
            val event = runBlocking { fetchEventBySlugWithRetry(slug).getOrNull() }
            if (event == null) {
                logger.warn("加密价差策略跳过（拉取事件失败）: strategyId=${strategy.id}, slug=$slug，请确认 Gamma 是否存在该 slug 或稍后重试")
                continue
            }
            val market = event.markets?.firstOrNull()
            if (market == null) {
                logger.warn("加密价差策略跳过（事件无市场）: strategyId=${strategy.id}, slug=$slug")
                continue
            }
            val tokenIds = parseClobTokenIds(market.clobTokenIds)
            if (tokenIds.size < 2) {
                logger.warn("加密价差策略跳过（token 数量不足）: strategyId=${strategy.id}, slug=$slug, tokenCount=${tokenIds.size}")
                continue
            }
            tokenIdSet.addAll(tokenIds)
            for (i in tokenIds.indices) {
                val tokenId = tokenIds[i]
                val subscriptionKey = "$slug:$tokenId:$i:$periodStartUnix"
                map.getOrPut(tokenId) { mutableListOf() }.add(
                    WsBookEntry(strategy, periodStartUnix, slug, tokenId, event.title, tokenIds, i, subscriptionKey)
                )
            }
        }

        return Pair(tokenIdSet.toList(), map)
    }

    /** 拉取事件，失败时重试最多 2 次（间隔 1s），避免瞬时失败导致多策略只订阅到其中一个 */
    private suspend fun fetchEventBySlugWithRetry(slug: String, maxAttempts: Int = 3): Result<GammaEventBySlugResponse> {
        var lastFailure: Exception? = null
        repeat(maxAttempts) { attempt ->
            val result = fetchEventBySlug(slug)
            if (result.isSuccess) return result
            lastFailure = result.exceptionOrNull() as? Exception
            if (attempt < maxAttempts - 1) delay(1000L)
        }
        return Result.failure(lastFailure ?: Exception("fetchEventBySlug failed"))
    }

    private suspend fun fetchEventBySlug(slug: String): Result<GammaEventBySlugResponse> {
        return try {
            val api = retrofitFactory.createGammaApi()
            val response = api.getEventBySlug(slug)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseClobTokenIds(clobTokenIds: String?): List<String> {
        if (clobTokenIds.isNullOrBlank()) return emptyList()
        val parsed = clobTokenIds.fromJson<List<String>>()
        return parsed ?: emptyList()
    }

    @EventListener
    fun onStrategyChanged(event: CryptoTailStrategyChangedEvent) {
        cachedEnabledAt = 0L  // 强制下次周期检查重新查库，避免用陈旧缓存漏掉启用/停用变更
        refreshAndSubscribe()
    }
}
