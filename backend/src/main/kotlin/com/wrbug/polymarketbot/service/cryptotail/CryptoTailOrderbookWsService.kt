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
    private val bracketExitService: CryptoTailBracketExitService
) {

    private val logger = LoggerFactory.getLogger(CryptoTailOrderbookWsService::class.java)

    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + scopeJob)

    /** tokenId -> list of (strategy, periodStartUnix, marketTitle, tokenIds, outcomeIndex) */
    private val tokenToEntries = AtomicReference<Map<String, List<WsBookEntry>>>(emptyMap())

    /** assetId -> 最近一次 bestAsk（最低卖价），障碍模式 EV 闸的有效成本来源；price_change 缺 best_ask 时回退此缓存 */
    private val bestAskCache = java.util.concurrent.ConcurrentHashMap<String, BigDecimal>()

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

    data class WsBookEntry(
        val strategy: CryptoTailStrategy,
        val periodStartUnix: Long,
        val marketTitle: String?,
        val tokenIds: List<String>,
        val outcomeIndex: Int
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
                for (i in 0 until bids.size()) {
                    val level = bids.get(i) as? com.google.gson.JsonObject ?: continue
                    val p = (level.get("price") as? com.google.gson.JsonPrimitive)?.asString?.toSafeBigDecimal() ?: continue
                    if (bestBid == null || p.gt(bestBid)) bestBid = p
                }
                // asks 取最低卖价作为 bestAsk（用于障碍模式 EV 闸的有效成本）
                val asks = json.get("asks") as? com.google.gson.JsonArray
                var bestAsk: BigDecimal? = null
                if (asks != null) {
                    for (i in 0 until asks.size()) {
                        val level = asks.get(i) as? com.google.gson.JsonObject ?: continue
                        val p = (level.get("price") as? com.google.gson.JsonPrimitive)?.asString?.toSafeBigDecimal() ?: continue
                        if (bestAsk == null || p < bestAsk) bestAsk = p
                    }
                }
                if (bestAsk != null) bestAskCache[assetId] = bestAsk
                if (bestBid != null) onBestBid(assetId, bestBid, bestAsk ?: bestAskCache[assetId])
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
                    if (bestAsk != null) bestAskCache[assetId] = bestAsk
                    if (bestBid != null) onBestBid(assetId, bestBid, bestAsk ?: bestAskCache[assetId])
                }
            }
        }
    }

    private fun onBestBid(tokenId: String, bestBid: BigDecimal, bestAsk: BigDecimal? = null) {
        if (closedForNoStrategies.get()) return
        val entries = tokenToEntries.get()[tokenId]
        if (entries == null) return
        val nowSeconds = System.currentTimeMillis() / 1000
        for (e in entries) {
            val windowStart = e.periodStartUnix + e.strategy.windowStartSeconds
            val windowEnd = e.periodStartUnix + e.strategy.windowEndSeconds
            val inEntryWindow = nowSeconds >= windowStart && nowSeconds < windowEnd

            // 入场分支：仅在时间窗内评估（旧行为不变）
            if (inEntryWindow) {
                scope.launch {
                    try {
                        executionService.tryTriggerWithPriceFromWs(
                            strategy = e.strategy,
                            periodStartUnix = e.periodStartUnix,
                            marketTitle = e.marketTitle,
                            tokenIds = e.tokenIds,
                            outcomeIndex = e.outcomeIndex,
                            bestBid = bestBid,
                            bestAsk = bestAsk
                        )
                    } catch (ex: Exception) {
                        logger.error("WS 触发下单异常: strategyId=${e.strategy.id}, ${ex.message}", ex)
                    }
                }
            }

            // 退出分支：仅 BRACKET_DYNAMIC 模式启用，且不受时间窗限制
            //  - 持仓监听整个周期内都需活跃，包括 forceExitBeforeSettleSeconds 兜底窗口
            //  - 跨周期由 SettlementService 兜底（HELD_TO_SETTLE）
            if (e.strategy.mode == TradingMode.BRACKET_DYNAMIC) {
                scope.launch {
                    try {
                        bracketExitService.evaluatePeriodOutcome(
                            strategy = e.strategy,
                            periodStartUnix = e.periodStartUnix,
                            outcomeIndex = e.outcomeIndex,
                            bestBid = bestBid,
                            nowSeconds = nowSeconds
                        )
                    } catch (ex: Exception) {
                        logger.error("WS 阶梯退出评估异常: strategyId=${e.strategy.id}, ${ex.message}", ex)
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
        val strategies = strategyRepository.findAllByEnabledTrue()
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
            // V53 修正：BRACKET_DYNAMIC 退出依赖 WS bestBid 全周期监听，须订阅到 periodEnd（不能在 windowEnd 即停）。
            // BARRIER/LEGACY 仅入场期需要价格监听，windowEnd 后即可释放订阅。
            val needFullPeriodMonitor = strategy.mode == TradingMode.BRACKET_DYNAMIC
            val cutoff = if (needFullPeriodMonitor) periodEnd else windowEnd
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
                map.getOrPut(tokenIds[i]) { mutableListOf() }.add(
                    WsBookEntry(strategy, periodStartUnix, event.title, tokenIds, i)
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
        refreshAndSubscribe()
    }
}
