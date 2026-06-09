package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.api.GammaEventBySlugResponse
import com.wrbug.polymarketbot.api.NewOrderRequest
import com.wrbug.polymarketbot.api.NewOrderResponse
import com.wrbug.polymarketbot.api.PolymarketClobApi
import com.wrbug.polymarketbot.dto.CryptoTailManualOrderRequest
import com.wrbug.polymarketbot.dto.CryptoTailManualOrderResponse
import com.wrbug.polymarketbot.dto.ManualOrderDetails
import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.entity.CryptoTailDecisionEvent
import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import com.wrbug.polymarketbot.entity.CryptoTailStrategyTrigger
import com.wrbug.polymarketbot.enums.ExitStatus
import com.wrbug.polymarketbot.enums.SpreadMode
import com.wrbug.polymarketbot.enums.SpreadDirection
import com.wrbug.polymarketbot.enums.TradingMode
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CryptoTailStrategyRepository
import com.wrbug.polymarketbot.repository.CryptoTailStrategyTriggerRepository
import com.wrbug.polymarketbot.service.binance.BinanceKlineAutoSpreadService
import com.wrbug.polymarketbot.service.binance.BinanceKlineService
import com.wrbug.polymarketbot.service.common.PolymarketClobService
import com.wrbug.polymarketbot.service.copytrading.orders.OrderSigningService
import com.wrbug.polymarketbot.service.cryptotail.taildiff.CryptoTailTailDiffDecisionService
import com.wrbug.polymarketbot.service.cryptotail.taildiff.TailDiffBuckets
import com.wrbug.polymarketbot.service.cryptotail.taildiff.TailDiffExitPreset
import com.wrbug.polymarketbot.service.cryptotail.taildiff.TailReversalStatsLookup
import com.wrbug.polymarketbot.service.cryptotail.taildiff.TailDiffTier
import com.wrbug.polymarketbot.util.CryptoUtils
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.div
import com.wrbug.polymarketbot.util.fromJson
import com.wrbug.polymarketbot.util.multi
import com.wrbug.polymarketbot.util.toJson
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import jakarta.annotation.PreDestroy
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/** 加密价差策略固定下单价格（最高价 0.99），不再在触发时拉取最优价 */
private const val TRIGGER_FIXED_PRICE = "0.99"

/** 最大价差模式（MAX）时，买入价格调整系数（加在触发价格上） */
private const val SPREAD_MAX_PRICE_ADJUSTMENT = "0.02"

/** 数量小数位数，与 OrderSigningService 的 roundConfig.size 一致 */
private const val SIZE_DECIMAL_SCALE = 2

/** 单笔下单最小 USDC 金额（平台限制），RATIO 模式计算值低于此值时按此值下单 */
private val MIN_ORDER_USDC = BigDecimal("1")

/**
 * 周期内预置上下文：账户、解密凭证、费率、签名类型、CLOB 客户端；不含预签订单。
 * 触发时 FIXED/RATIO 均按 outcomeIndex 计算 size 并签名提交。
 */
private data class PeriodContext(
    val strategy: CryptoTailStrategy,
    val periodStartUnix: Long,
    val account: Account,
    val decryptedPrivateKey: String,
    val apiSecretDecrypted: String,
    val apiPassphraseDecrypted: String,
    val clobApi: PolymarketClobApi,
    val signatureType: Int,
    val tokenIds: List<String>,
    val marketTitle: String?
)

/**
 * 加密价差策略执行服务：按周期与时间窗口检查价格并下单，每周期最多触发一次。
 * 周期开始预置账户、解密、费率、签名类型、CLOB 客户端；触发时按 outcomeIndex 计算 size 并签名提交。
 */
@Service
class CryptoTailStrategyExecutionService(
    private val strategyRepository: CryptoTailStrategyRepository,
    private val triggerRepository: CryptoTailStrategyTriggerRepository,
    private val accountRepository: AccountRepository,
    private val entryGuardService: CryptoTailEntryGuardService,
    private val retrofitFactory: RetrofitFactory,
    private val clobService: PolymarketClobService,
    private val orderSigningService: OrderSigningService,
    private val cryptoUtils: CryptoUtils,
    private val binanceKlineService: BinanceKlineService,
    private val binanceKlineAutoSpreadService: BinanceKlineAutoSpreadService,
    private val cryptoTailRiskService: CryptoTailRiskService,
    private val decisionRecorder: CryptoTailDecisionRecorder,
    private val periodPriceProvider: PeriodPriceProvider,
    private val calibrationService: CryptoTailCalibrationService,
    private val wickSignalService: CryptoTailWickSignalService,
    private val orderbookSnapshotFetcher: CryptoTailOrderbookSnapshotFetcher,
    private val wsDiag: CryptoTailWsDiag,
    private val orderbookCache: CryptoTailOrderbookCache,
    private val boostService: CryptoTailBoostService,
    private val tailDiffDecisionService: CryptoTailTailDiffDecisionService,
    private val tailDiffEntrySegmentResolver: com.wrbug.polymarketbot.service.cryptotail.taildiff.TailDiffEntrySegmentResolver,
    private val reversalStatsLookup: TailReversalStatsLookup,
    @Value("\${crypto-tail.scalp.ws-feed-alive-bound-ms:2000}") private val scalpWsFeedAliveBoundMs: Long
) {

    private val logger = LoggerFactory.getLogger(CryptoTailStrategyExecutionService::class.java)

    /** 按 (strategyId, periodStartUnix) 加锁，避免同一周期被调度器与 WebSocket 等多路并发重复下单 */
    private val triggerMutexMap = ConcurrentHashMap<String, Mutex>()

    /** 按 accountId 加锁，串行化同账户多策略入场的余额预留与重复持仓检查 */
    private val accountEntryMutexMap = ConcurrentHashMap<Long, Mutex>()

    /** 过期锁 key 保留时间（秒），超过则清理，防止 map 无界增长 */
    private val triggerMutexExpireSeconds = 3600L

    private fun triggerLockKey(strategyId: Long, periodStartUnix: Long): String = "$strategyId-$periodStartUnix"

    private fun getTriggerMutex(strategyId: Long, periodStartUnix: Long): Mutex {
        cleanExpiredTriggerMutexKeys()
        return triggerMutexMap.getOrPut(triggerLockKey(strategyId, periodStartUnix)) { Mutex() }
    }

    private fun getAccountEntryMutex(accountId: Long): Mutex =
        accountEntryMutexMap.getOrPut(accountId) { Mutex() }

    /** 清理已过期的 (strategyId, periodStartUnix) 锁，避免内存泄漏 */
    private fun cleanExpiredTriggerMutexKeys() {
        val nowSeconds = System.currentTimeMillis() / 1000
        val expireThreshold = nowSeconds - triggerMutexExpireSeconds
        val keysToRemove = triggerMutexMap.keys.filter { key ->
            key.substringAfterLast('-').toLongOrNull()?.let { it < expireThreshold } ?: false
        }
        keysToRemove.forEach { triggerMutexMap.remove(it) }
    }

    /** 周期预置上下文缓存：(strategyId-periodStartUnix) -> PeriodContext，过期周期在读取时剔除 */
    private val periodContextCache = ConcurrentHashMap<String, PeriodContext>()

    /** 已打印「首次满足条件」日志的周期：LRU 容量 100，每周期只打一次 */
    private val conditionLoggedCache: Cache<String, Long> = Caffeine.newBuilder()
        .maximumSize(100)
        .build()

    /** 决策日志去重：每 (strategyId-period-key) 只记一次，避免每个 WS tick 重复刷库 */
    private val decisionLoggedCache: Cache<String, Boolean> = Caffeine.newBuilder()
        .maximumSize(500)
        .build()

    /**
     * TAIL_DIFF 决策快照：评分通过后落库时由 saveTriggerRecord 读取该 map 把 score/tier/exit_preset_json
     * 等字段写入 crypto_tail_strategy_trigger（这些字段是 V62 新增，其他模式恒为 NULL）。
     *
     * key = strategyId-periodStartUnix-outcomeIndex；每周期最多一次入场，使用即清。
     */
    private val tailDiffEntrySnapshotCache: Cache<String, TailDiffEntrySnapshot> = Caffeine.newBuilder()
        .maximumSize(500)
        .expireAfterWrite(java.time.Duration.ofHours(1))
        .build()

    /**
     * SCALP_FLIP 入场冻结快照（key=strategyId-periodStartUnix-outcomeIndex），saveTriggerRecord 落库后即清。
     * 除退出预设 JSON 外，冻结入场信号（modelSide/pWin/safeRatio/gap/remaining），使退出引擎的方向翻转止损
     * (MODEL_FLIP 依赖 trigger.entryModelSide) 对 SCALP 生效，并填充触发记录/成交快照的入场信号列。
     */
    private val scalpEntrySnapshotCache: Cache<String, ScalpEntrySnapshot> = Caffeine.newBuilder()
        .maximumSize(500)
        .expireAfterWrite(java.time.Duration.ofHours(1))
        .build()

    private data class TailDiffEntrySnapshot(
        val score: Int,
        val tier: TailDiffTier,
        val exitPresetJson: String,
        val rawDiff: BigDecimal?,
        val diffPct: BigDecimal?,
        val diffSigma: BigDecimal?,
        val modelProbSource: String?
    )

    /** SCALP_FLIP 入场冻结快照：退出预设 + 入场信号（价源不可用时信号字段为 null，graceful 降级与历史一致） */
    private data class ScalpEntrySnapshot(
        val exitPresetJson: String,
        val modelSide: Int?,
        val pWin: BigDecimal?,
        val safeRatio: BigDecimal?,
        val gap: BigDecimal?,
        val remainingSeconds: Int?
    )

    /** SCALP_FLIP 入场信号（winProbTerminal 输出 + gap/remaining），用于冻结到 trigger 入场列 */
    private data class ScalpEntrySignal(
        val modelSide: Int,
        val pWin: BigDecimal,
        val safeRatio: BigDecimal,
        val gap: BigDecimal,
        val remainingSeconds: Int
    )

    /**
     * 在周期内首次需要时构建并缓存预置上下文；失败返回 null，触发流程将走完整路径。
     * 预置：账户、解密、费率、签名类型、CLOB 客户端；不预签订单，触发时再签名。
     */
    private suspend fun ensurePeriodContext(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        tokenIds: List<String>,
        marketTitle: String?
    ): PeriodContext? {
        val key = triggerLockKey(strategy.id!!, periodStartUnix)
        periodContextCache[key]?.let { return it }

        val account = accountRepository.findById(strategy.accountId).orElse(null) ?: return null
        if (account.apiKey == null || account.apiSecret == null || account.apiPassphrase == null) return null

        val decryptedKey = try {
            cryptoUtils.decrypt(account.privateKey) ?: return null
        } catch (e: Exception) {
            logger.warn("加密价差策略周期上下文解密私钥失败: accountId=${account.id}", e)
            return null
        }
        val apiSecret = try {
            account.apiSecret.let { cryptoUtils.decrypt(it) }
        } catch (e: Exception) {
            ""
        }
        val apiPassphrase = try {
            account.apiPassphrase.let { cryptoUtils.decrypt(it) }
        } catch (e: Exception) {
            ""
        }

        val clobApi = retrofitFactory.createClobApi(account.apiKey, apiSecret, apiPassphrase, account.walletAddress)
        val signatureType = orderSigningService.getSignatureTypeForWalletType(account.walletType)

        if (strategy.amountMode.uppercase() != "RATIO" && strategy.amountValue < MIN_ORDER_USDC) return null

        val ctx = PeriodContext(
            strategy = strategy,
            periodStartUnix = periodStartUnix,
            account = account,
            decryptedPrivateKey = decryptedKey,
            apiSecretDecrypted = apiSecret,
            apiPassphraseDecrypted = apiPassphrase,
            clobApi = clobApi,
            signatureType = signatureType,
            tokenIds = tokenIds,
            marketTitle = marketTitle
        )
        periodContextCache[key] = ctx
        return ctx
    }

    /**
     * 按投入金额和价格计算可买张数：size = ceil(amountUsdc/price)，保留小数，至少 1。
     * 与 OrderSigningService 一致使用小数数量，向上取整保证不超过投入金额。
     */
    private fun computeSize(amountUsdc: BigDecimal, price: BigDecimal): String {
        val size = amountUsdc.divide(price, SIZE_DECIMAL_SCALE, RoundingMode.UP).max(BigDecimal.ONE)
        return size.toPlainString()
    }

    private fun requiredEntryEdge(strategy: CryptoTailStrategy): BigDecimal {
        return when (strategy.mode) {
            TradingMode.BARRIER_HOLD -> strategy.entryEdge
            TradingMode.BRACKET_DYNAMIC -> probabilityEntryEdge(strategy)
            // TAIL_DIFF 用自己的 minEdge；FAK 定价仍走 EV 安全限价复用 BARRIER 算式
            TradingMode.TAIL_DIFF -> strategy.tailDiffMinEdge
            // SCALP_FLIP 不走 EV 安全限价定价路径（与 LEGACY 同走 computeBuyPrice），此处仅为穷举完整
            TradingMode.SCALP_FLIP -> strategy.scalpMinEdge
            TradingMode.LEGACY_SPREAD -> BigDecimal.ZERO
        }
    }

    private fun entryPriceCap(strategy: CryptoTailStrategy): BigDecimal {
        return when (strategy.mode) {
            TradingMode.BARRIER_HOLD -> strategy.maxEntryPrice
            TradingMode.BRACKET_DYNAMIC -> probabilityMaxEntryPrice(strategy)
            // TAIL_DIFF 用 hardMaxPrice（更严的兜底；规则书"绝对禁止"上限）
            TradingMode.TAIL_DIFF -> strategy.tailDiffHardMaxPrice
            // SCALP_FLIP 买入限价封顶
            TradingMode.SCALP_FLIP -> strategy.scalpMaxFillPrice
            TradingMode.LEGACY_SPREAD -> BigDecimal(TRIGGER_FIXED_PRICE)
        }
    }

    private fun probabilityEntryProb(strategy: CryptoTailStrategy): BigDecimal =
        if (strategy.mode == TradingMode.BRACKET_DYNAMIC) strategy.entryProb.max(strategy.bracketEntryProb) else strategy.entryProb

    private fun probabilityEntryEdge(strategy: CryptoTailStrategy): BigDecimal =
        if (strategy.mode == TradingMode.BRACKET_DYNAMIC) strategy.entryEdge.max(strategy.bracketEntryEdge) else strategy.entryEdge

    private fun probabilityMaxEntryPrice(strategy: CryptoTailStrategy): BigDecimal =
        if (strategy.mode == TradingMode.BRACKET_DYNAMIC) strategy.maxEntryPrice.min(strategy.bracketMaxEntryPrice) else strategy.maxEntryPrice

    private fun requiredSafeRatio(strategy: CryptoTailStrategy, outcomeIndex: Int): BigDecimal {
        val directional = if (outcomeIndex == 0) strategy.minSafeRatioUp else strategy.minSafeRatioDown
        return strategy.minSafeRatio.max(directional)
    }

    private fun computeFakPricing(
        strategy: CryptoTailStrategy,
        bestBid: BigDecimal,
        bestAsk: BigDecimal?,
        pWin: BigDecimal
    ): CryptoTailFakPricingPolicy.Result {
        return CryptoTailFakPricingPolicy.price(
            CryptoTailFakPricingPolicy.Request(
                mode = strategy.mode,
                pWin = pWin,
                requiredEdge = requiredEntryEdge(strategy),
                bestBid = bestBid,
                bestAsk = bestAsk,
                costBuffer = strategy.costBuffer,
                configuredSlippage = strategy.entryFakSlippage,
                takerFeeBps = strategy.takerFeeBps,
                priceCap = entryPriceCap(strategy)
            )
        )
    }

    private fun pricingPayload(
        pricing: CryptoTailFakPricingPolicy.Result?,
        orderbookRefreshed: Boolean
    ): Map<String, Any> {
        if (pricing == null) {
            return mapOf(
                "rawAsk" to "",
                "configuredSlippage" to "",
                "configuredLimit" to "",
                "evSafeLimit" to "",
                // evSafeMaxPrice 与 evSafeLimit 同义，统一与 ORDER_RESULT payload 命名，便于前端排查口径一致
                "evSafeMaxPrice" to "",
                "priceCap" to "",
                "finalLimitPrice" to "",
                "limitEdge" to "",
                "pricingClampReason" to "",
                "orderbookRefreshed" to orderbookRefreshed
            )
        }
        return mapOf(
            "rawAsk" to pricing.rawAsk.toPlainString(),
            "configuredSlippage" to pricing.configuredSlippage.toPlainString(),
            "configuredLimit" to pricing.configuredLimit.toPlainString(),
            "evSafeLimit" to pricing.evSafeLimit.toPlainString(),
            // evSafeMaxPrice 与 evSafeLimit 同义，统一与 ORDER_RESULT payload 命名，便于前端排查口径一致
            "evSafeMaxPrice" to pricing.evSafeLimit.toPlainString(),
            "priceCap" to pricing.priceCap.toPlainString(),
            "finalLimitPrice" to pricing.finalLimit.toPlainString(),
            "limitEdge" to pricing.limitEdge.toPlainString(),
            "pricingClampReason" to pricing.pricingClampReason,
            "orderbookRefreshed" to orderbookRefreshed
        )
    }

    private fun orderbookRefreshPayload(
        preRefreshOrderbook: OrderbookQualitySnapshot?,
        refreshedOrderbook: OrderbookQualitySnapshot?,
        orderbookRefreshed: Boolean,
        restSkipped: Boolean = false
    ): Map<String, Any> = mapOf(
        "preRefreshBestBid" to (preRefreshOrderbook?.bestBid?.toPlainString() ?: ""),
        "preRefreshBestAsk" to (preRefreshOrderbook?.bestAsk?.toPlainString() ?: ""),
        "refreshedBestBid" to (refreshedOrderbook?.bestBid?.toPlainString() ?: ""),
        "refreshedBestAsk" to (refreshedOrderbook?.bestAsk?.toPlainString() ?: ""),
        "refreshedSpread" to (refreshedOrderbook?.spread?.toPlainString() ?: ""),
        // orderbookRefreshed=发单前真做了 REST 重拉；restSkipped=WS3 因 WS 快照够新跳过 REST、直接复用 WS 快照复检(并非未刷新)
        "orderbookRefreshed" to orderbookRefreshed,
        "restSkipped" to restSkipped,
        // orderbookSource=本次发单前盘口取数来源：WS_LIVE=实时 WS 帧(WS 主)、REST=REST 兜底重拉、NONE=未刷新(LEGACY)
        "orderbookSource" to when {
            restSkipped -> "WS_LIVE"
            orderbookRefreshed -> "REST"
            else -> "NONE"
        }
    )

    private fun avgFillPrice(filledSize: BigDecimal?, filledAmount: BigDecimal?): BigDecimal? {
        if (filledSize == null || filledAmount == null || filledSize <= BigDecimal.ZERO) return null
        return filledAmount.divide(filledSize, 8, RoundingMode.HALF_UP)
    }

    private fun isRetryableFakMiss(message: String?): Boolean {
        val m = message?.lowercase() ?: return false
        return m.contains("no orders found") ||
                m.contains("no match") ||
                m.contains("零成交") ||
                m.contains("无对手盘") ||
                (m.contains("fak") && m.contains("unfilled"))
    }

    private fun getOrInvalidatePeriodContext(strategy: CryptoTailStrategy, periodStartUnix: Long): PeriodContext? {
        val key = triggerLockKey(strategy.id!!, periodStartUnix)
        val nowSeconds = System.currentTimeMillis() / 1000
        val ctx = periodContextCache[key] ?: return null
        if (periodStartUnix + strategy.intervalSeconds <= nowSeconds) {
            periodContextCache.remove(key)
            cleanExpiredPeriodContextCache(nowSeconds)
            return null
        }
        return ctx
    }

    /** 清理已过期的周期上下文缓存，避免内存泄漏 */
    private fun cleanExpiredPeriodContextCache(nowSeconds: Long) {
        val keysToRemove = periodContextCache.entries
            .filter { (_, ctx) -> ctx.periodStartUnix + ctx.strategy.intervalSeconds <= nowSeconds }
            .map { it.key }
        keysToRemove.forEach { periodContextCache.remove(it) }
    }

    /**
     * 由订单簿 WebSocket 触发：当收到某 token 的 bestBid 且满足区间时调用，若本周期未触发则下单。
     */
    suspend fun tryTriggerWithPriceFromWs(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        marketTitle: String?,
        tokenIds: List<String>,
        outcomeIndex: Int,
        bestBid: BigDecimal,
        bestAsk: BigDecimal? = null,
        orderbook: OrderbookQualitySnapshot? = null
    ) {
        if (outcomeIndex < 0 || outcomeIndex >= tokenIds.size) return
        // 仅旧价差模式走价格区间预过滤；
        //  - BARRIER_HOLD: 由 barrierMinMarketProb 等闸把关
        //  - BRACKET_DYNAMIC: 由 bracketMaxEntryPrice 在闸内把关，且 bestBid 还需用于持仓退出监听
        if (strategy.mode == TradingMode.LEGACY_SPREAD && (bestBid < strategy.minPrice || bestBid > strategy.maxPrice)) return

        val mutex = getTriggerMutex(strategy.id!!, periodStartUnix)
        mutex.withLock {
            if (triggerRepository.findByStrategyIdAndPeriodStartUnix(
                    strategy.id!!,
                    periodStartUnix
                ) != null
            ) return@withLock
            val logKey = triggerLockKey(strategy.id!!, periodStartUnix)
            if (conditionLoggedCache.getIfPresent(logKey) == null) {
                conditionLoggedCache.put(logKey, periodStartUnix + strategy.intervalSeconds)
                // 非 LEGACY 模式日志也保持结算同源口径；价源缺失时宁可记未知，不能用 Binance 混源。
                val oc = if (strategy.mode != TradingMode.LEGACY_SPREAD) {
                    periodPriceProvider.getCurrentOpenClose(strategy.marketSlugPrefix, strategy.intervalSeconds, periodStartUnix)
                } else {
                    binanceKlineService.getCurrentOpenClose(
                        strategy.marketSlugPrefix,
                        strategy.intervalSeconds,
                        periodStartUnix
                    )
                }
                val openP = oc?.first
                val closeP = oc?.second
                val openPrice = openP?.toPlainString() ?: "-"
                val closePrice = closeP?.toPlainString() ?: "-"
                val strategyName = strategy.name?.takeIf { it.isNotBlank() } ?: "加密价差策略-${strategy.marketSlugPrefix}"
                // 评估的是哪个 outcome token（仅标识被评估的方向，非模型预测）
                val evalOutcome = if (outcomeIndex == 0) "Up" else "Down"
                // 模型按 gap=close-open 的方向判定（close≥open→涨，否则跌）；价源未就绪则未知
                val modelSide = if (openP != null && closeP != null) {
                    if (closeP >= openP) "Up(涨)" else "Down(跌)"
                } else "未知(价源未就绪)"
                val modeStr = when (strategy.mode) {
                    TradingMode.BARRIER_HOLD -> "障碍模式"
                    TradingMode.BRACKET_DYNAMIC -> "概率阶梯止盈"
                    TradingMode.TAIL_DIFF -> "尾盘价差"
                    TradingMode.SCALP_FLIP -> "快进快出"
                    TradingMode.LEGACY_SPREAD -> if (strategy.spreadDirection == SpreadDirection.MAX) "最大价差" else "最小价差"
                }
                logger.info(
                    "加密价差策略首次满足条件: strategyName=$strategyName, strategyId=${strategy.id}, " +
                            "openPrice=$openPrice, closePrice=$closePrice, marketPrice=${bestBid.toPlainString()}, " +
                            "评估outcome=$evalOutcome, 模型方向(close vs open)=$modelSide, outcomeIndex=$outcomeIndex, mode=$modeStr"
                )
            }

            val tokenIdForOutcome = tokenIds.getOrNull(outcomeIndex)
            val accountMutex = getAccountEntryMutex(strategy.accountId)
            accountMutex.withLock accountLock@{
                if (entryGuardService.hasDuplicateMarketPosition(strategy, periodStartUnix, outcomeIndex)) {
                    recordDecisionOncePerPeriod(
                        strategy, periodStartUnix, "GATE_FAILED-DUPLICATE_MARKET_POSITION", outcomeIndex,
                        eventType = "GATE_FAILED", gateName = "DUPLICATE_MARKET_POSITION", passed = false,
                        reason = "同账户同 market/period/outcome 已有 success/pending 入场，allowDuplicateMarketPosition=false",
                        payloadJson = mapOf(
                            "allowDuplicateMarketPosition" to strategy.allowDuplicateMarketPosition,
                            "duplicateKey" to "${strategy.accountId}:${strategy.marketSlugPrefix}:$periodStartUnix:$outcomeIndex"
                        ).toJson(),
                        triggerId = null,
                        tokenId = tokenIdForOutcome
                    )
                    return@accountLock
                }

                when (strategy.mode) {
                    TradingMode.BARRIER_HOLD -> {
                        val eval = evaluateBarrierGates(strategy, periodStartUnix, outcomeIndex, bestBid, bestAsk, orderbook)
                        recordBarrierDecision(strategy, periodStartUnix, outcomeIndex, eval, tokenIdForOutcome)
                        if (!eval.pass) return@accountLock
                        val risk = cryptoTailRiskService.checkRiskGate(strategy)
                        if (!risk.passed) {
                            recordDecisionOncePerPeriod(
                                strategy, periodStartUnix, "GATE_FAILED-${risk.gateName}", outcomeIndex,
                                eventType = "GATE_FAILED", gateName = risk.gateName, passed = false, reason = risk.reason,
                                payloadJson = eval.payloadJson, triggerId = null, tokenId = tokenIdForOutcome
                            )
                            return@accountLock
                        }
                        val scaling = calibrationService.evaluateScalingGate(strategy)
                        val amountOverride = if (scaling.useProbe) strategy.probeAmountUsdc else null
                        ensurePeriodContext(strategy, periodStartUnix, tokenIds, marketTitle)
                        placeOrderForTrigger(strategy, periodStartUnix, marketTitle, tokenIds, outcomeIndex, bestBid, bestAsk, amountOverride, eval.metrics, scaling, orderbook)
                    }
                    TradingMode.BRACKET_DYNAMIC -> {
                        val eval = evaluateBracketEntryGates(strategy, periodStartUnix, outcomeIndex, bestBid, bestAsk, orderbook)
                        recordBarrierDecision(strategy, periodStartUnix, outcomeIndex, eval, tokenIdForOutcome)
                        if (!eval.pass) return@accountLock
                        val risk = cryptoTailRiskService.checkRiskGate(strategy)
                        if (!risk.passed) {
                            recordDecisionOncePerPeriod(
                                strategy, periodStartUnix, "GATE_FAILED-${risk.gateName}", outcomeIndex,
                                eventType = "GATE_FAILED", gateName = risk.gateName, passed = false, reason = risk.reason,
                                payloadJson = eval.payloadJson, triggerId = null, tokenId = tokenIdForOutcome
                            )
                            return@accountLock
                        }
                        // 阶梯模式不参与放量闸/校准（独立模式，校准样本来自 BARRIER）
                        ensurePeriodContext(strategy, periodStartUnix, tokenIds, marketTitle)
                        placeOrderForTrigger(strategy, periodStartUnix, marketTitle, tokenIds, outcomeIndex, bestBid, bestAsk, null, eval.metrics, preRefreshOrderbook = orderbook)
                    }
                    TradingMode.TAIL_DIFF -> {
                        if (orderbook == null) {
                            recordBarrierDecision(
                                strategy, periodStartUnix, outcomeIndex,
                                BarrierEval(false, "ORDERBOOK_STALE", "TAIL_DIFF 需要订单簿快照", orderbookPayload(null).toJson()),
                                tokenIdForOutcome
                            )
                            return@accountLock
                        }
                        val balanceSnapshot = entryGuardService.loadEntryBalanceSnapshot(strategy.accountId)
                        val decision = tailDiffDecisionService.evaluate(strategy, periodStartUnix, outcomeIndex, orderbook, balanceSnapshot.spendable)
                        when (decision.outcome) {
                            CryptoTailTailDiffDecisionService.TailDiffDecision.Outcome.SKIP,
                            CryptoTailTailDiffDecisionService.TailDiffDecision.Outcome.WATCH -> return@accountLock
                            CryptoTailTailDiffDecisionService.TailDiffDecision.Outcome.BUY -> Unit
                        }
                        val risk = cryptoTailRiskService.checkRiskGate(strategy)
                        if (!risk.passed) {
                            recordDecisionOncePerPeriod(
                                strategy, periodStartUnix, "GATE_FAILED-${risk.gateName}", outcomeIndex,
                                eventType = "GATE_FAILED", gateName = risk.gateName, passed = false, reason = risk.reason,
                                payloadJson = mapOf(
                                    "mode" to "TAIL_DIFF",
                                    "score" to decision.score,
                                    "tier" to (decision.tier?.label ?: "")
                                ).toJson(),
                                triggerId = null, tokenId = tokenIdForOutcome
                            )
                            return@accountLock
                        }
                        // 冻结快照供 saveTriggerRecord 写入 trigger 新列
                        val tier = decision.tier!!
                        tailDiffEntrySnapshotCache.put(
                            tailDiffSnapshotKey(strategy.id!!, periodStartUnix, outcomeIndex),
                            TailDiffEntrySnapshot(
                                score = decision.score,
                                tier = tier,
                                exitPresetJson = decision.exitPresetJson ?: "",
                                rawDiff = decision.rawDiff,
                                diffPct = decision.diffPct,
                                diffSigma = decision.diffSigma,
                                modelProbSource = decision.modelProbSource
                            )
                        )
                        ensurePeriodContext(strategy, periodStartUnix, tokenIds, marketTitle)
                        // 复用 BARRIER/BRACKET 的下单链路：FAK 定价、retry、决策日志、订单结果
                        placeOrderForTrigger(
                            strategy, periodStartUnix, marketTitle, tokenIds, outcomeIndex, bestBid, bestAsk,
                            amountOverrideUsdc = decision.amountUsdc, metrics = null, scaling = null, preRefreshOrderbook = orderbook
                        )
                    }
                    TradingMode.SCALP_FLIP -> {
                        if (orderbook == null) {
                            recordBarrierDecision(
                                strategy, periodStartUnix, outcomeIndex,
                                BarrierEval(false, "ORDERBOOK_STALE", "SCALP_FLIP 需要订单簿快照", orderbookPayload(null).toJson()),
                                tokenIdForOutcome
                            )
                            return@accountLock
                        }
                        val eval = evaluateScalpEntryGates(strategy, periodStartUnix, outcomeIndex, bestBid, bestAsk, orderbook)
                        recordBarrierDecision(strategy, periodStartUnix, outcomeIndex, eval, tokenIdForOutcome)
                        if (!eval.pass) return@accountLock
                        val risk = cryptoTailRiskService.checkRiskGate(strategy)
                        if (!risk.passed) {
                            recordDecisionOncePerPeriod(
                                strategy, periodStartUnix, "GATE_FAILED-${risk.gateName}", outcomeIndex,
                                eventType = "GATE_FAILED", gateName = risk.gateName, passed = false, reason = risk.reason,
                                payloadJson = mapOf("mode" to "SCALP_FLIP").toJson(),
                                triggerId = null, tokenId = tokenIdForOutcome
                            )
                            return@accountLock
                        }
                        // 入场时冻结一份退出预设 + 入场信号到独立快照缓存，saveTriggerRecord 落库到 trigger；
                        // 后续退出评估走 BracketExitService.decideTailDiffExit 读取该快照，策略表中途修改不影响在途持仓。
                        // 冻结 modelSide 使退出引擎方向翻转止损(MODEL_FLIP)对 SCALP 生效；价源不可用时信号留空，graceful 降级。
                        val scalpRemaining = (periodStartUnix + strategy.intervalSeconds - System.currentTimeMillis() / 1000).toInt()
                        val scalpSignal = computeScalpEntrySignal(strategy, periodStartUnix, outcomeIndex, scalpRemaining)
                        scalpEntrySnapshotCache.put(
                            tailDiffSnapshotKey(strategy.id!!, periodStartUnix, outcomeIndex),
                            ScalpEntrySnapshot(
                                exitPresetJson = buildScalpExitPresetJson(strategy),
                                modelSide = scalpSignal?.modelSide,
                                pWin = scalpSignal?.pWin,
                                safeRatio = scalpSignal?.safeRatio,
                                gap = scalpSignal?.gap,
                                remainingSeconds = scalpSignal?.remainingSeconds
                            )
                        )
                        ensurePeriodContext(strategy, periodStartUnix, tokenIds, marketTitle)
                        // 复用极简下单链路（与 LEGACY 同走 computeBuyPrice + FAK），bestAsk 用于买入限价计算
                        placeOrderForTrigger(
                            strategy, periodStartUnix, marketTitle, tokenIds, outcomeIndex, bestBid, bestAsk,
                            amountOverrideUsdc = null, metrics = null, scaling = null, preRefreshOrderbook = orderbook
                        )
                    }
                    TradingMode.LEGACY_SPREAD -> {
                        if (!passSpreadCheck(strategy, periodStartUnix, outcomeIndex)) return@accountLock
                        ensurePeriodContext(strategy, periodStartUnix, tokenIds, marketTitle)
                        placeOrderForTrigger(strategy, periodStartUnix, marketTitle, tokenIds, outcomeIndex, bestBid, null)
                    }
                }
            }
        }
    }

    private fun tailDiffSnapshotKey(strategyId: Long, periodStartUnix: Long, outcomeIndex: Int): String =
        "$strategyId-$periodStartUnix-$outcomeIndex"

    // ===================== SCALP_FLIP（快进快出）进场与退出预设 =====================

    /**
     * 快进快出进场闸（极简）：时间窗口 + 盘口新鲜度 + 价格区间(按 bestAsk) + 退出流动性深度 + 同方向并发上限 + 可选反转率门槛。
     * 不复用 BARRIER 的 pWin/EV/wick 闸，物理隔离；返回 metrics=null（SCALP 不走 EV 定价）。
     */
    private fun evaluateScalpEntryGates(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        outcomeIndex: Int,
        bestBid: BigDecimal,
        bestAsk: BigDecimal?,
        orderbook: OrderbookQualitySnapshot
    ): BarrierEval {
        val nowMs = System.currentTimeMillis()
        val nowSeconds = nowMs / 1000
        val remainingSeconds = (periodStartUnix + strategy.intervalSeconds - nowSeconds).toInt()
        val elapsedSeconds = (nowSeconds - periodStartUnix).toInt()

        // 1) 进场时间窗口
        if (strategy.scalpWindowStartSeconds > 0 && elapsedSeconds < strategy.scalpWindowStartSeconds) {
            return BarrierEval(false, "SCALP_WINDOW", "未到进场窗口起点: elapsed=${elapsedSeconds}s<${strategy.scalpWindowStartSeconds}s",
                mapOf("elapsedSeconds" to elapsedSeconds, "windowStart" to strategy.scalpWindowStartSeconds).toJson())
        }
        if (strategy.scalpWindowEndSeconds > 0 && elapsedSeconds > strategy.scalpWindowEndSeconds) {
            return BarrierEval(false, "SCALP_WINDOW", "已过进场窗口终点: elapsed=${elapsedSeconds}s>${strategy.scalpWindowEndSeconds}s",
                mapOf("elapsedSeconds" to elapsedSeconds, "windowEnd" to strategy.scalpWindowEndSeconds).toJson())
        }
        if (strategy.scalpMinRemainingSeconds > 0 && remainingSeconds < strategy.scalpMinRemainingSeconds) {
            return BarrierEval(false, "SCALP_MIN_REMAINING", "剩余不足: remaining=${remainingSeconds}s<${strategy.scalpMinRemainingSeconds}s",
                mapOf("remainingSeconds" to remainingSeconds, "minRemaining" to strategy.scalpMinRemainingSeconds).toJson())
        }

        // 2) 盘口新鲜度（复用通用 maxOrderbookAgeMs）
        val quoteAge = orderbook.quoteAgeMs(nowMs)
        if (strategy.maxOrderbookAgeMs > 0 && quoteAge > strategy.maxOrderbookAgeMs) {
            return BarrierEval(false, "ORDERBOOK_STALE", "订单簿过期: quoteAgeMs=$quoteAge>${strategy.maxOrderbookAgeMs}",
                orderbookPayload(orderbook, nowMs).toJson())
        }

        // 2.5) 最大价差（复用通用 maxEntrySpread，默认 0.03；宽价差=盘口不稳易瞬时塌陷，过滤"接飞刀"低价成交风险）
        val spread = orderbook.spread
        if (strategy.maxEntrySpread > BigDecimal.ZERO && spread != null && spread > strategy.maxEntrySpread) {
            return BarrierEval(false, "SCALP_SPREAD_TOO_WIDE",
                "盘口价差过大: spread=${spread.toPlainString()}>${strategy.maxEntrySpread.toPlainString()}",
                orderbookPayload(orderbook, nowMs).toJson())
        }

        // 3) 价格区间（按 bestAsk 判定买入价，∈[entryMin, entryMax]）
        val ask = bestAsk ?: orderbook.bestAsk
            ?: return BarrierEval(false, "SCALP_ASK_UNAVAILABLE", "盘口缺少 bestAsk，无法判定进场价", orderbookPayload(orderbook, nowMs).toJson())
        if (ask < strategy.scalpEntryMinPrice || ask > strategy.scalpEntryMaxPrice) {
            return BarrierEval(false, "SCALP_PRICE_OUT_OF_RANGE",
                "bestAsk=${ask.toPlainString()}∉[${strategy.scalpEntryMinPrice.toPlainString()},${strategy.scalpEntryMaxPrice.toPlainString()}]",
                orderbookPayload(orderbook, nowMs).toJson())
        }

        // 3.5) 进场实时方向确认（P0）：要求标的模型方向(modelSide)==买入侧 且 pWin 达标，过滤"下跌穿越"飞刀。
        // 静态价格 + 历史分桶反转率无法区分"上涨穿越 0.95(真赢家)"与"下跌穿越 0.95(飞刀)"；此处用本周期实时标的信号确认方向。
        // entrySignal 在此算一次，下方反转率分桶(diffSigma)复用，避免重复计算。价源不可用→graceful 降级放行+记可观测事件（与反转率门槛降级同口径）。
        val entrySignal = computeScalpEntrySignal(strategy, periodStartUnix, outcomeIndex, remainingSeconds)
        if (strategy.scalpRequireUnderlyingAgreement) {
            if (entrySignal == null) {
                recordDecisionOncePerPeriod(
                    strategy, periodStartUnix, "SCALP_DIRECTION_DEGRADED", outcomeIndex,
                    eventType = "DIRECTION_DEGRADED", gateName = "SCALP_UNDERLYING_AGREEMENT", passed = true,
                    reason = "标的价源不可用，无法确认进场方向，已降级放行（requireUnderlyingAgreement=true）",
                    payloadJson = mapOf("mode" to "SCALP_FLIP", "outcomeIndex" to outcomeIndex).toJson(),
                    triggerId = null
                )
            } else {
                if (entrySignal.modelSide != outcomeIndex) {
                    return BarrierEval(false, "SCALP_DIRECTION_MISMATCH",
                        "标的模型方向 modelSide=${entrySignal.modelSide}!=买入侧 outcomeIndex=$outcomeIndex（疑似下跌穿越/反向，pWin=${entrySignal.pWin.toPlainString()}）",
                        orderbookPayload(orderbook, nowMs).plus(scalpSignalPayload(entrySignal)).toJson())
                }
                if (entrySignal.pWin < strategy.scalpEntryMinPwin) {
                    return BarrierEval(false, "SCALP_DIRECTION_WEAK",
                        "标的模型胜率不足 pWin=${entrySignal.pWin.toPlainString()}<${strategy.scalpEntryMinPwin.toPlainString()} (modelSide=${entrySignal.modelSide})",
                        orderbookPayload(orderbook, nowMs).plus(scalpSignalPayload(entrySignal)).toJson())
                }
            }
        }

        // 3.6) 进场价差闸（V87，默认关）：领先优势(diff_sigma/|gap|)不足则拒单，应对"目标价与当前价过近易反转割肉"。
        // 复用上方 entrySignal（safeRatio=diff_sigma、gap）；价源不可用(entrySignal==null)无法计算领先优势 → 降级放行（不阻断正常进场）。
        if (strategy.scalpGapGateEnabled && entrySignal != null) {
            val lo = strategy.scalpGapGateRemainingLo
            val hi = strategy.scalpGapGateRemainingHi
            // 窗口语义：remaining >= lo 且（hi<=0 视为无上界）。(0,0)→全周期（与历史一致）；
            // 仅填 lo（hi=0）不再静默失效，而是"剩余>=lo 全程生效"，避免误配后以为开了实则没生效。
            val windowActive = remainingSeconds >= lo && (hi <= 0 || remainingSeconds <= hi)
            if (windowActive) {
                val minSigma = strategy.scalpMinEntryDiffSigma
                if (minSigma > BigDecimal.ZERO && entrySignal.safeRatio < minSigma) {
                    return BarrierEval(false, "SCALP_GAP_TOO_SMALL",
                        "领先优势不足: diffSigma=${entrySignal.safeRatio.toPlainString()}<${minSigma.toPlainString()} (remaining=${remainingSeconds}s)",
                        orderbookPayload(orderbook, nowMs).plus("remainingSeconds" to remainingSeconds).plus(scalpSignalPayload(entrySignal)).toJson())
                }
                val minGapAbs = strategy.scalpMinEntryGapAbs
                if (minGapAbs > BigDecimal.ZERO && entrySignal.gap.abs() < minGapAbs) {
                    return BarrierEval(false, "SCALP_GAP_TOO_SMALL",
                        "价差过小: |gap|=${entrySignal.gap.abs().toPlainString()}<${minGapAbs.toPlainString()} (remaining=${remainingSeconds}s)",
                        orderbookPayload(orderbook, nowMs).plus("remainingSeconds" to remainingSeconds).plus(scalpSignalPayload(entrySignal)).toJson())
                }
            }
        }

        // 4) 退出流动性深度（确保可退出；null=不检查）
        val minDepth = strategy.scalpMinExitBidDepthUsdc
        if (minDepth != null && minDepth > BigDecimal.ZERO) {
            val bidDepth = orderbook.bidDepthUsd ?: BigDecimal.ZERO
            if (bidDepth < minDepth) {
                return BarrierEval(false, "SCALP_EXIT_DEPTH_INSUFFICIENT",
                    "卖盘深度不足: bidDepth=${bidDepth.toPlainString()}<${minDepth.toPlainString()}",
                    orderbookPayload(orderbook, nowMs).toJson())
            }
        }

        // 5) 同方向并发上限（NULL=不限制）
        val maxConc = strategy.scalpMaxConcurrentSameDirection
        if (maxConc != null && maxConc > 0) {
            val openSameDir = triggerRepository.countByStrategyIdAndOutcomeIndexAndStatusAndResolvedFalse(strategy.id!!, outcomeIndex, "success")
            if (openSameDir >= maxConc) {
                return BarrierEval(false, "SCALP_MAX_CONCURRENT_SAME_DIRECTION",
                    "同方向未结算敞口 $openSameDir>=$maxConc (outcomeIndex=$outcomeIndex)",
                    mapOf("openSameDir" to openSameDir, "maxConcurrent" to maxConc, "outcomeIndex" to outcomeIndex).toJson())
            }
        }

        // 6) 历史反转率门槛（可选）。复用上方已算的 entrySignal 提供 diffSigma，避免重复计算。
        if (strategy.scalpReversalGateEnabled) {
            val (pass, reason) = evaluateScalpReversalGate(strategy, outcomeIndex, ask, periodStartUnix, remainingSeconds, entrySignal)
            if (!pass) {
                return BarrierEval(false, "SCALP_REVERSAL_GATE", reason,
                    orderbookPayload(orderbook, nowMs).plus("bestAsk" to ask.toPlainString()).plus("remainingSeconds" to remainingSeconds).plus(scalpSignalPayload(entrySignal)).toJson())
            }
            return BarrierEval(true, "ALL", "SCALP 进场通过: bestAsk=${ask.toPlainString()} remaining=${remainingSeconds}s ($reason)",
                orderbookPayload(orderbook, nowMs).plus(scalpSignalPayload(entrySignal)).toJson())
        }
        return BarrierEval(true, "ALL", "SCALP 进场通过: bestAsk=${ask.toPlainString()} remaining=${remainingSeconds}s (反转门槛关闭)",
            orderbookPayload(orderbook, nowMs).plus(scalpSignalPayload(entrySignal)).toJson())
    }

    /** P1：SCALP 进场信号(modelSide/pWin/safeRatio/gap)作为决策 payload 字段，供事后验证方向是否一致；信号缺失返回空。 */
    private fun scalpSignalPayload(signal: ScalpEntrySignal?): Map<String, Any?> =
        if (signal == null) emptyMap()
        else mapOf(
            "modelSide" to signal.modelSide,
            "pWin" to signal.pWin.toPlainString(),
            "safeRatio" to signal.safeRatio.toPlainString(),
            "gap" to signal.gap.toPlainString()
        )

    /**
     * 反转率门槛：买 favorite（ask∈区间，odds>0.5）时领先方向=outcomeIndex，
     * 查 model_prob（领先方向维持到结算的历史概率），>= scalpMinModelProb 且 edge 达标才放行。
     * 数据源 HYBRID=POLYMARKET 优先回退 BINANCE；统计不可用时按 scalpRequireStats 决定拦截/降级放行。
     */
    private fun evaluateScalpReversalGate(
        strategy: CryptoTailStrategy,
        outcomeIndex: Int,
        ask: BigDecimal,
        periodStartUnix: Long,
        remainingSeconds: Int,
        entrySignal: ScalpEntrySignal?
    ): Pair<Boolean, String> {
        val coin = inferScalpCoin(strategy)
        val leadOutcome = outcomeIndex
        // 复用上游已算的进场信号 safeRatio 作为 diffSigma（BINANCE 桶精确匹配用）；缺失时回退 0（POLYMARKET 走 ANY 不依赖）。
        val diffSigma = entrySignal?.safeRatio ?: BigDecimal.ZERO
        val oddsBucket = TailDiffBuckets.oddsBucket(ask)
        val remainingBucket = TailDiffBuckets.remainingBucket(remainingSeconds)
        val result = queryScalpReversal(strategy.scalpStatsSource.uppercase(), coin, strategy, leadOutcome, diffSigma, oddsBucket, remainingBucket)
        val statsAvailable = result.modelProb != null && result.sampleCount >= strategy.scalpStatsMinSamples
        if (!statsAvailable) {
            if (strategy.scalpRequireStats) {
                return false to "反转率统计不可用(modelProb=${result.modelProb}, samples=${result.sampleCount}<${strategy.scalpStatsMinSamples}) 且 requireStats=true，拦截进场"
            }
            // F5 可观测性：降级为纯价格放行不是「门槛生效」而是「门槛失效兜底」，显式记一条决策事件（每周期一次），
            // 避免运营误以为反转率筛选在起作用其实并未命中数据。requireStats=true 的拦截已由上层 GATE_FAILED 记录。
            recordDecisionOncePerPeriod(
                strategy, periodStartUnix, "SCALP_REVERSAL_DEGRADED", outcomeIndex,
                eventType = "REVERSAL_DEGRADED", gateName = "SCALP_REVERSAL_GATE", passed = true,
                reason = "反转率统计不可用(modelProb=${result.modelProb}, samples=${result.sampleCount}<${strategy.scalpStatsMinSamples}, source=${result.source})，已降级为纯价格区间放行（requireStats=false）",
                payloadJson = mapOf(
                    "coin" to (coin ?: ""),
                    "leadOutcome" to leadOutcome,
                    "diffSigma" to diffSigma.toPlainString(),
                    "oddsBucket" to oddsBucket,
                    "remainingBucket" to remainingBucket,
                    "sampleCount" to result.sampleCount,
                    "minSamples" to strategy.scalpStatsMinSamples,
                    "statsSource" to strategy.scalpStatsSource,
                    "requireStats" to strategy.scalpRequireStats
                ).toJson(),
                triggerId = null
            )
            return true to "反转率统计不可用(samples=${result.sampleCount})，降级为纯价格区间放行"
        }
        val modelProb = result.modelProb!!
        if (modelProb < strategy.scalpMinModelProb) {
            return false to "modelProb=${modelProb.toPlainString()}<${strategy.scalpMinModelProb.toPlainString()} (samples=${result.sampleCount}, source=${result.source})"
        }
        if (strategy.scalpMinEdge > BigDecimal.ZERO) {
            val edge = modelProb.subtract(ask)
            if (edge < strategy.scalpMinEdge) {
                return false to "edge=${edge.toPlainString()}<${strategy.scalpMinEdge.toPlainString()} (modelProb=${modelProb.toPlainString()}, ask=${ask.toPlainString()})"
            }
        }
        return true to "modelProb=${modelProb.toPlainString()}>=${strategy.scalpMinModelProb.toPlainString()} (samples=${result.sampleCount}, source=${result.source})"
    }

    /** HYBRID=POLYMARKET 优先样本不足回退 BINANCE；POLYMARKET/BINANCE 单源直查。 */
    private fun queryScalpReversal(
        source: String,
        coin: String?,
        strategy: CryptoTailStrategy,
        leadOutcome: Int,
        diffSigma: BigDecimal,
        oddsBucket: String,
        remainingBucket: String
    ): TailReversalStatsLookup.Result {
        fun q(ds: String) = reversalStatsLookup.queryReversalProb(
            TailReversalStatsLookup.Query(
                coin = coin,
                intervalSeconds = strategy.intervalSeconds,
                leadOutcome = leadOutcome,
                diffSigma = diffSigma,
                oddsBucket = oddsBucket,
                remainingBucket = remainingBucket,
                lookbackDays = strategy.scalpStatsLookbackDays,
                dataSource = ds
            )
        )
        return when (source) {
            "POLYMARKET" -> q("POLYMARKET")
            "BINANCE" -> q("BINANCE")
            else -> {
                val poly = q("POLYMARKET")
                if (poly.modelProb != null && poly.sampleCount >= strategy.scalpStatsMinSamples) poly else q("BINANCE")
            }
        }
    }

    /**
     * 尽力计算 SCALP 入场信号（modelSide/pWin/safeRatio/gap/remaining），复用 BARRIER 内核 winProbTerminal。
     * 价源未就绪返回 null（与历史 graceful 降级一致：信号列留空，不阻断进场）。
     */
    private fun computeScalpEntrySignal(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        outcomeIndex: Int,
        remainingSeconds: Int
    ): ScalpEntrySignal? {
        if (remainingSeconds <= 0) return null
        if (!periodPriceProvider.isAvailable(strategy.marketSlugPrefix)) return null
        val oc = periodPriceProvider.getCurrentOpenClose(strategy.marketSlugPrefix, strategy.intervalSeconds, periodStartUnix) ?: return null
        val (openP, closeP) = oc
        val gap = closeP.subtract(openP)
        val sigma = periodPriceProvider.getSigmaPerSqrtS(
            strategy.marketSlugPrefix, strategy.intervalSeconds, periodStartUnix,
            outcomeIndex, strategy.sigmaScale, strategy.sigmaMethod, strategy.ewmaLambda
        ) ?: return null
        val r = BarrierProbability.winProbTerminal(gap, sigma, remainingSeconds.toDouble()) ?: return null
        return ScalpEntrySignal(
            modelSide = r.side,
            pWin = r.pWin,
            safeRatio = r.safeRatio,
            gap = gap,
            remainingSeconds = remainingSeconds
        )
    }

    private fun inferScalpCoin(strategy: CryptoTailStrategy): String? {
        val slug = strategy.marketSlugPrefix.lowercase()
        return when {
            slug.contains("btc") -> "BTC"
            slug.contains("eth") -> "ETH"
            else -> null
        }
    }

    /**
     * 入场冻结一份退出预设 JSON：
     *  - hold_to_expiry 恒为 false（确保 stop_loss + dynamic_exit 始终被评估）；
     *  - tp_limit.enabled = !scalpHoldWinnerToSettle：true=挂止盈(scalpTpPrice)，false=不挂(赢单持有到结算拿 1.0)；
     *  - stop_loss = 价位止损(回撤 offset + 绝对地板 minPrice)；
     *  - dynamic_exit = 标的方向(diff_sigma 跌破)/反抽速度/minOdds 软止损。
     * 退出评估走 CryptoTailBracketExitService.decideTailDiffExit 读取该快照。
     */
    private fun buildScalpExitPresetJson(strategy: CryptoTailStrategy): String {
        val preset = TailDiffExitPreset(
            holdToExpiry = false,
            tpLimit = TailDiffExitPreset.TpLimit(
                enabled = !strategy.scalpHoldWinnerToSettle,
                price = strategy.scalpTpPrice,
                ratio = BigDecimal.ONE
            ),
            stopLoss = TailDiffExitPreset.StopLoss(
                enabled = strategy.scalpStopEnabled,
                offset = strategy.scalpStopOffset,
                minPrice = strategy.scalpStopMinPrice,
                ratio = BigDecimal.ONE
            ),
            dynamicExit = TailDiffExitPreset.DynamicExit(
                enabled = strategy.scalpUnderlyingStopEnabled || strategy.scalpReverseVelocityStopEnabled ||
                    strategy.scalpMinOddsAfterEntry > BigDecimal.ZERO ||
                    strategy.scalpMinModelProbAfterEntry > BigDecimal.ZERO ||
                    strategy.scalpMaxDiffRetracePct > BigDecimal.ZERO ||
                    strategy.scalpCatastropheBidFloor > BigDecimal.ZERO ||
                    strategy.scalpCatastropheFloorRatio > BigDecimal.ZERO,
                minDiffSigmaAfterEntry = if (strategy.scalpUnderlyingStopEnabled) strategy.scalpUnderlyingStopSigma else BigDecimal.ZERO,
                maxDiffRetracePct = strategy.scalpMaxDiffRetracePct,
                minModelProbAfterEntry = strategy.scalpMinModelProbAfterEntry,
                minOddsAfterEntry = strategy.scalpMinOddsAfterEntry,
                maxReverseVelocitySigma = if (strategy.scalpReverseVelocityStopEnabled) strategy.scalpMaxReverseVelocitySigma else BigDecimal.ZERO,
                catastropheBidFloor = strategy.scalpCatastropheBidFloor,
                maxDrawdownPct = BigDecimal.ZERO,
                catastropheImmediate = strategy.scalpCatastropheImmediate,
                catastropheFloorRatio = strategy.scalpCatastropheFloorRatio
            ),
            execution = TailDiffExitPreset.Execution()
        )
        return preset.toMap().toJson()
    }

    /** 障碍模式闸评估结果 */
    private data class BarrierEval(
        val pass: Boolean,
        val gateName: String?,
        val reason: String?,
        val payloadJson: String?,
        val metrics: BarrierMetrics? = null
    )

    /** 障碍模式进场时的结构化信号/盘口指标，供 ORDER_SUBMITTED 快照复用，避免重复解析 JSON */
    private data class BarrierMetrics(
        val gap: BigDecimal,
        val open: BigDecimal,
        val close: BigDecimal,
        val sigma: BigDecimal,
        val remaining: Double,
        val pWin: BigDecimal,
        val side: Int,
        val safeRatio: BigDecimal,
        val effectiveCost: BigDecimal,
        val edge: BigDecimal,
        val coin: String?,
        val officialPriceSource: String,
        val officialPriceAgeMs: Long?,
        val priceReadyReason: String,
        val priceMode: String?,
        val lastSnapshotAt: Long?,
        val lastRealtimeUpdateAt: Long?,
        val latestPriceAgeMs: Long?,
        val latestSampleTime: Long?
    )

    private data class FakOrderAttempt(
        val orderRequest: NewOrderRequest,
        val limitPrice: BigDecimal,
        val metrics: BarrierMetrics? = null,
        val pricing: CryptoTailFakPricingPolicy.Result? = null,
        val orderbook: OrderbookQualitySnapshot? = null,
        val orderbookRefreshed: Boolean = false,
        // 重试单盘口来源（供 ORDER_RESULT / RESULT 诊断准确归因）：WS 主命中=true，REST 兜底=false
        val restSkipped: Boolean = false,
        val restFallbackReason: String? = null
    )

    private data class FakSubmitResult(
        val status: String,
        val orderId: String?,
        val filledSize: BigDecimal?,
        val filledAmount: BigDecimal?,
        val failReason: String?,
        val retryable: Boolean
    )

    private data class OrderResultContext(
        val preRefreshOrderbook: OrderbookQualitySnapshot? = null,
        val preSubmitOrderbook: OrderbookQualitySnapshot? = null,
        val pricing: CryptoTailFakPricingPolicy.Result? = null,
        val submitLatencyMs: Long? = null,
        val postFailBestAsk: BigDecimal? = null,
        val orderbookRefreshed: Boolean = false,
        val restSkipped: Boolean = false,
        val restFallbackReason: String? = null
    )

    private fun orderbookPayload(orderbook: OrderbookQualitySnapshot?, nowMs: Long = System.currentTimeMillis()): Map<String, Any> {
        return orderbook?.toPayload(nowMs) ?: mapOf(
            "bestBid" to "",
            "bestAsk" to "",
            "bidSize" to "",
            "askSize" to "",
            "bidDepthUsd" to "",
            "askDepthUsd" to "",
            "spread" to "",
            "quoteAgeMs" to "",
            "depthAgeMs" to "",
            "depthStale" to true
        )
    }

    private fun readinessDiagnostics(status: PeriodPriceProvider.PriceReadiness): Map<String, Any> = mapOf(
        "priceMode" to (status.priceMode ?: ""),
        "lastSnapshotAt" to (status.lastSnapshotAt ?: ""),
        "lastRealtimeUpdateAt" to (status.lastRealtimeUpdateAt ?: ""),
        "latestPriceAgeMs" to (status.latestPriceAgeMs ?: ""),
        "latestSampleTime" to (status.latestSampleTime ?: "")
    )

    private fun metricsDiagnostics(metrics: BarrierMetrics): Map<String, Any> = mapOf(
        "priceMode" to (metrics.priceMode ?: ""),
        "lastSnapshotAt" to (metrics.lastSnapshotAt ?: ""),
        "lastRealtimeUpdateAt" to (metrics.lastRealtimeUpdateAt ?: ""),
        "latestPriceAgeMs" to (metrics.latestPriceAgeMs ?: ""),
        "latestSampleTime" to (metrics.latestSampleTime ?: "")
    )

    private fun statusPayload(status: PeriodPriceProvider.PriceReadiness): Map<String, Any> = mapOf(
        "priceSource" to status.source,
        "coin" to (status.coin ?: ""),
        "officialPriceSource" to status.source,
        "officialPriceAgeMs" to (status.ageMs ?: ""),
        "officialOpen" to "",
        "officialClose" to "",
        "priceReadyReason" to status.reason,
        "priceAgeMs" to (status.ageMs ?: ""),
        "fallbackUsed" to false
    ).plus(readinessDiagnostics(status))


    private fun checkEntryMarketQuality(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        orderbook: OrderbookQualitySnapshot?
    ): BarrierEval? {
        val nowMs = System.currentTimeMillis()
        val nowSeconds = nowMs / 1000
        val remainingSeconds = (periodStartUnix + strategy.intervalSeconds - nowSeconds).toInt()
        // TAIL_DIFF 用独立窗口：未配置入场分段时取 tailDiffMinRemainingSeconds~tailDiffWindowStartSeconds（默认 50~150）；
        // 配置了分段则取所有段的窗口包络 [min(remaining_lo), max(remaining_hi)]，避免早窗候选被预过滤误拦。
        // 其他模式沿用 BARRIER/BRACKET 的 minRemainingSeconds/maxRemainingSeconds 全周期窗口。
        val (effMinRemaining, effMaxRemaining) = if (strategy.mode == TradingMode.TAIL_DIFF) {
            tailDiffEntrySegmentResolver.windowEnvelope(strategy)
        } else {
            strategy.minRemainingSeconds to strategy.maxRemainingSeconds
        }
        val belowMinRemaining = effMinRemaining > 0 && remainingSeconds < effMinRemaining
        val aboveMaxRemaining = effMaxRemaining > 0 && remainingSeconds > effMaxRemaining
        if (belowMinRemaining || aboveMaxRemaining) {
            return BarrierEval(
                false,
                "REMAINING_TIME",
                "入场剩余时间=${remainingSeconds}s 不在 ${effMinRemaining}~${effMaxRemaining}s",
                mapOf(
                    "remainingSeconds" to remainingSeconds,
                    "minRemainingSeconds" to effMinRemaining,
                    "maxRemainingSeconds" to effMaxRemaining,
                    "mode" to strategy.mode.name
                ).toJson()
            )
        }
        if (orderbook == null) {
            return BarrierEval(false, "ORDERBOOK_STALE", "订单簿快照缺失，禁止入场", orderbookPayload(null).toJson())
        }
        // TAIL_DIFF 用独立盘口/价龄阈值（tailDiffMax*），与尾盘打分门保持一致，避免下单守卫用更严的通用阈值二次卡门；
        // 其他模式沿用通用 maxOrderbookAgeMs/maxEntrySpread/maxPriceAgeMs。
        val isTailDiff = strategy.mode == TradingMode.TAIL_DIFF
        val effMaxOrderbookAgeMs = if (isTailDiff) strategy.tailDiffMaxOrderbookAgeMs else strategy.maxOrderbookAgeMs
        val effMaxSpread = if (isTailDiff) strategy.tailDiffMaxSpread else strategy.maxEntrySpread
        val effMaxPriceAgeMs = if (isTailDiff) strategy.tailDiffMaxPriceAgeMs else strategy.maxPriceAgeMs
        val quoteAge = orderbook.quoteAgeMs(nowMs)
        if (effMaxOrderbookAgeMs > 0 && quoteAge > effMaxOrderbookAgeMs) {
            return BarrierEval(
                false,
                "ORDERBOOK_STALE",
                "订单簿过期: quoteAgeMs=$quoteAge>$effMaxOrderbookAgeMs",
                orderbookPayload(orderbook, nowMs).toJson()
            )
        }
        val spread = orderbook.spread
        if (orderbook.bestAsk == null || spread == null) {
            return BarrierEval(
                false,
                "SPREAD_UNAVAILABLE",
                "盘口缺少 bestAsk/spread，禁止概率模式入场",
                orderbookPayload(orderbook, nowMs).toJson()
            )
        }
        if (effMaxSpread > BigDecimal.ZERO && spread > effMaxSpread) {
            return BarrierEval(
                false,
                "SPREAD_TOO_WIDE",
                "入场盘口价差=${spread.toPlainString()}>maxEntrySpread=${effMaxSpread.toPlainString()}",
                orderbookPayload(orderbook, nowMs).toJson()
            )
        }
        val priceAge = periodPriceProvider.getCurrentPriceAgeMs(strategy.marketSlugPrefix)
        if (effMaxPriceAgeMs > 0 && priceAge != null && priceAge > effMaxPriceAgeMs) {
            val payload = orderbookPayload(orderbook, nowMs).plus("priceAgeMs" to priceAge).toJson()
            return BarrierEval(
                false,
                "PRICE_STALE",
                "结算同源价源过期: priceAgeMs=$priceAge>$effMaxPriceAgeMs",
                payload
            )
        }
        return null
    }

    /**
     * 障碍（终值概率）模式闸：方向一致性 / pWin / 市场概率 / 扣费 EV。
     * 时间窗已由 WS onBestBid 复用现有 window 把关，此处不再重复校验。
     */
    private fun evaluateBarrierGates(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        outcomeIndex: Int,
        bestBid: BigDecimal,
        bestAsk: BigDecimal?,
        orderbook: OrderbookQualitySnapshot?
    ): BarrierEval {
        checkEntryMarketQuality(strategy, periodStartUnix, orderbook)?.let { return it }
        // 障碍模式价源必须与 Polymarket 结算源(Chainlink)一致；缺凭证/feedID 时安全跳过，绝不回退币安
        if (!periodPriceProvider.isAvailable(strategy.marketSlugPrefix)) {
            val status = periodPriceProvider.getReadiness(strategy.marketSlugPrefix)
            return BarrierEval(false, "PRICE_SOURCE", "价源未就绪: ${status.source}/${status.coin ?: ""}/${status.reason}", statusPayload(status).toJson())
        }
        val oc = periodPriceProvider.getCurrentOpenClose(strategy.marketSlugPrefix, strategy.intervalSeconds, periodStartUnix)
            ?: run {
                val status = periodPriceProvider.getReadiness(strategy.marketSlugPrefix)
                return BarrierEval(false, "PRICE_SOURCE", "期初价未就绪: ${status.source}/${status.coin ?: ""}/${status.reason}", statusPayload(status).toJson())
            }
        val (openP, closeP) = oc
        val priceStatus = periodPriceProvider.getReadiness(strategy.marketSlugPrefix)
        val gap = closeP.subtract(openP)
        val nowSeconds = System.currentTimeMillis() / 1000
        val remaining = (periodStartUnix + strategy.intervalSeconds - nowSeconds).toDouble()
        // 即便 σ 未就绪，也带上 open/close/gap 的方向快照，让决策日志能看出"模型按 gap 判定的方向"
        val sideByGap = if (gap.signum() >= 0) 0 else 1
        val preSigmaSnapshot = mapOf(
            "open" to openP.toPlainString(),
            "close" to closeP.toPlainString(),
            "officialOpen" to openP.toPlainString(),
            "officialClose" to closeP.toPlainString(),
            "officialPriceSource" to priceStatus.source,
            "officialPriceAgeMs" to (priceStatus.ageMs ?: ""),
            "priceSource" to priceStatus.source,
            "coin" to (priceStatus.coin ?: ""),
            "priceReadyReason" to priceStatus.reason,
            "priceAgeMs" to (priceStatus.ageMs ?: ""),
            "fallbackUsed" to false,
            "gap" to gap.toPlainString(),
            "remainingSeconds" to remaining,
            "modelSideByGap" to sideByGap,
            "modelSideByGapText" to (if (sideByGap == 0) "Up(涨)" else "Down(跌)"),
            "evalOutcomeIndex" to outcomeIndex
        ).plus(readinessDiagnostics(priceStatus)).toJson()
        val sigma = periodPriceProvider.getSigmaPerSqrtS(
            strategy.marketSlugPrefix, strategy.intervalSeconds, periodStartUnix, outcomeIndex, strategy.sigmaScale,
            strategy.sigmaMethod, strategy.ewmaLambda
        ) ?: return BarrierEval(false, "PWIN", "σ基准不可用(价源历史样本不足，冷启动需累积若干周期后自动恢复)", preSigmaSnapshot)
        val r = BarrierProbability.winProbTerminal(gap, sigma, remaining)
            ?: return BarrierEval(false, "PWIN", "无法计算pWin(剩余时间或σ无效)", null)

        // 统一有效成本口径，按进场订单类型区分：
        //  - FAK 吃单：有效成本 = bestAsk(缺失用 bestBid+costBuffer) + taker 手续费(takerFeeBps)
        //  - MAKER 挂单：有效成本 = 预期挂单价 - maker 返佣(makerRebateBps)，返佣降低成本
        val isMaker = strategy.entryOrderType.uppercase() == "MAKER"
        val rawPrice: BigDecimal
        val feePerShare: BigDecimal
        val rawEffectiveCost: BigDecimal
        if (isMaker) {
            rawPrice = computeMakerPrice(strategy, bestBid, bestAsk)
            feePerShare = rawPrice.multiply(BigDecimal(strategy.makerRebateBps)).divide(BigDecimal(10000), 8, RoundingMode.HALF_UP).negate()
            rawEffectiveCost = rawPrice.add(feePerShare)
        } else {
            rawPrice = bestAsk ?: bestBid.add(strategy.costBuffer)
            feePerShare = rawPrice.multiply(BigDecimal(strategy.takerFeeBps)).divide(BigDecimal(10000), 8, RoundingMode.HALF_UP)
            rawEffectiveCost = rawPrice.add(feePerShare)
        }
        val edge = r.pWin.subtract(rawEffectiveCost)
        val metrics = BarrierMetrics(
            gap = gap,
            open = openP,
            close = closeP,
            sigma = sigma,
            remaining = remaining,
            pWin = r.pWin,
            side = r.side,
            safeRatio = r.safeRatio,
            effectiveCost = rawEffectiveCost,
            edge = edge,
            coin = priceStatus.coin,
            officialPriceSource = priceStatus.source,
            officialPriceAgeMs = priceStatus.ageMs,
            priceReadyReason = priceStatus.reason,
            priceMode = priceStatus.priceMode,
            lastSnapshotAt = priceStatus.lastSnapshotAt,
            lastRealtimeUpdateAt = priceStatus.lastRealtimeUpdateAt,
            latestPriceAgeMs = priceStatus.latestPriceAgeMs,
            latestSampleTime = priceStatus.latestSampleTime
        )
        val payload = mutableMapOf<String, Any>(
            "gap" to gap.toPlainString(),
            "open" to openP.toPlainString(),
            "close" to closeP.toPlainString(),
            "officialOpen" to openP.toPlainString(),
            "officialClose" to closeP.toPlainString(),
            "officialPriceSource" to priceStatus.source,
            "officialPriceAgeMs" to (priceStatus.ageMs ?: ""),
            "priceReadyReason" to priceStatus.reason,
            "coin" to (priceStatus.coin ?: ""),
            "fallbackUsed" to false,
            "sigmaPerSqrtS" to sigma.toPlainString(),
            "remainingSeconds" to remaining,
            "pWin" to r.pWin.toPlainString(),
            "modelSide" to r.side,
            "safeRatio" to r.safeRatio.toPlainString(),
            "bestBid" to bestBid.toPlainString(),
            "bestAsk" to (bestAsk?.toPlainString() ?: ""),
            "rawPrice" to rawPrice.toPlainString(),
            "entryOrderType" to strategy.entryOrderType,
            "takerFeeBps" to strategy.takerFeeBps,
            "makerRebateBps" to strategy.makerRebateBps,
            "feePerShare" to feePerShare.toPlainString(),
            "effectiveCost" to rawEffectiveCost.toPlainString(),
            "edge" to edge.toPlainString(),
            "entryProb" to strategy.entryProb.toPlainString(),
            "entryEdge" to strategy.entryEdge.toPlainString(),
            "barrierMinMarketProb" to strategy.barrierMinMarketProb.toPlainString(),
            "minSafeRatio" to strategy.minSafeRatio.toPlainString(),
            "minSafeRatioUp" to strategy.minSafeRatioUp.toPlainString(),
            "minSafeRatioDown" to strategy.minSafeRatioDown.toPlainString(),
            "highPriceThreshold" to strategy.highPriceThreshold.toPlainString(),
            "highPriceMinPWin" to strategy.highPriceMinPWin.toPlainString(),
            "highPriceMinSafeRatio" to strategy.highPriceMinSafeRatio.toPlainString(),
            "maxEntrySpread" to strategy.maxEntrySpread.toPlainString(),
            "maxOrderbookAgeMs" to strategy.maxOrderbookAgeMs,
            "maxPriceAgeMs" to strategy.maxPriceAgeMs
        )
        payload.putAll(readinessDiagnostics(priceStatus))
        payload.putAll(orderbookPayload(orderbook))
        fun snapshot(): String = payload.toJson()

        if (r.side != outcomeIndex) {
            return BarrierEval(false, "DIRECTION", "模型方向=${r.side}与当前outcome=${outcomeIndex}不一致", snapshot(), metrics)
        }
        if (r.pWin < strategy.entryProb) {
            return BarrierEval(false, "PWIN", "pWin=${r.pWin.toPlainString()}<entryProb=${strategy.entryProb.toPlainString()}", snapshot(), metrics)
        }
        val safeRatioRequired = requiredSafeRatio(strategy, outcomeIndex)
        if (r.safeRatio < safeRatioRequired) {
            return BarrierEval(false, "SAFE_RATIO", "safeRatio=${r.safeRatio.toPlainString()}<required=${safeRatioRequired.toPlainString()}", snapshot(), metrics)
        }
        if (strategy.barrierMinMarketProb > BigDecimal.ZERO && bestBid < strategy.barrierMinMarketProb) {
            return BarrierEval(false, "MARKET_PROB", "市场概率=${bestBid.toPlainString()}<下限=${strategy.barrierMinMarketProb.toPlainString()}", snapshot(), metrics)
        }
        if (rawEffectiveCost > strategy.maxEntryPrice) {
            return BarrierEval(false, "MAX_ENTRY_PRICE", "有效成本=${rawEffectiveCost.toPlainString()}>maxEntryPrice=${strategy.maxEntryPrice.toPlainString()}", snapshot(), metrics)
        }
        if (edge < strategy.entryEdge) {
            return BarrierEval(false, "EV", "扣费edge=${edge.toPlainString()}<entryEdge=${strategy.entryEdge.toPlainString()}", snapshot(), metrics)
        }
        if (rawEffectiveCost > strategy.highPriceThreshold &&
            (r.pWin < strategy.highPriceMinPWin || r.safeRatio < strategy.highPriceMinSafeRatio)
        ) {
            return BarrierEval(false, "HIGH_PRICE", "高价入场保护: effectiveCost=${rawEffectiveCost.toPlainString()} pWin=${r.pWin.toPlainString()} safeRatio=${r.safeRatio.toPlainString()}", snapshot(), metrics)
        }
        val wick = wickSignalService.evaluate(strategy, outcomeIndex)
        payload.putAll(wick.toPayload().mapValues { it.value ?: "" })
        payload["wickFilterMode"] = strategy.wickFilterMode
        if (strategy.wickFilterMode.uppercase() == "ENFORCE" && wick.available && wick.reversalScore >= strategy.wickEntryBlockScore) {
            return BarrierEval(false, "WICK_REVERSAL", "影线反转分数=${wick.reversalScore}>=${strategy.wickEntryBlockScore}", snapshot(), metrics)
        }
        return BarrierEval(true, "ALL", "通过全部障碍闸", snapshot(), metrics)
    }

    /**
     * TAIL_DIFF 进场前安全复核闸（仅用于 FAK 救单/重签前的最后保底；完整入场决策由 CryptoTailTailDiffDecisionService 负责）。
     *
     * 复用 BARRIER 的价源/盘口质量校验与 σ/pWin 内核，但只套 TAIL_DIFF 专属阈值，绝不套 BARRIER 的
     * entryProb(0.55)、entryEdge、barrierMinMarketProb、highPrice 保护、wick 入场闸（那些是 BARRIER 模式语义）。
     * TAIL_DIFF 恒 FAK 进场：有效成本 = bestAsk(缺则 bestBid + tailDiffCostBuffer) + taker 费。
     * 闸：方向一致 / 有效成本<=tailDiffHardMaxPrice / 扣费 edge>=tailDiffMinEdge / pWin>=tailDiffMinModelProb / safeRatio>=tailDiffMinDiffSigma。
     */
    private fun evaluateTailDiffEntryGates(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        outcomeIndex: Int,
        bestBid: BigDecimal,
        bestAsk: BigDecimal?,
        orderbook: OrderbookQualitySnapshot?
    ): BarrierEval {
        checkEntryMarketQuality(strategy, periodStartUnix, orderbook)?.let { return it }
        if (!periodPriceProvider.isAvailable(strategy.marketSlugPrefix)) {
            val status = periodPriceProvider.getReadiness(strategy.marketSlugPrefix)
            return BarrierEval(false, "PRICE_SOURCE", "价源未就绪: ${status.source}/${status.coin ?: ""}/${status.reason}", statusPayload(status).toJson())
        }
        val oc = periodPriceProvider.getCurrentOpenClose(strategy.marketSlugPrefix, strategy.intervalSeconds, periodStartUnix)
            ?: run {
                val status = periodPriceProvider.getReadiness(strategy.marketSlugPrefix)
                return BarrierEval(false, "PRICE_SOURCE", "期初价未就绪: ${status.source}/${status.coin ?: ""}/${status.reason}", statusPayload(status).toJson())
            }
        val (openP, closeP) = oc
        val priceStatus = periodPriceProvider.getReadiness(strategy.marketSlugPrefix)
        val gap = closeP.subtract(openP)
        val nowSeconds = System.currentTimeMillis() / 1000
        val remaining = (periodStartUnix + strategy.intervalSeconds - nowSeconds).toDouble()
        val sigma = periodPriceProvider.getSigmaPerSqrtS(
            strategy.marketSlugPrefix, strategy.intervalSeconds, periodStartUnix, outcomeIndex, strategy.sigmaScale,
            strategy.sigmaMethod, strategy.ewmaLambda
        ) ?: return BarrierEval(false, "PWIN", "σ基准不可用(价源历史样本不足)", null)
        val r = BarrierProbability.winProbTerminal(gap, sigma, remaining)
            ?: return BarrierEval(false, "PWIN", "无法计算pWin(剩余时间或σ无效)", null)

        // TAIL_DIFF 恒 FAK 进场：有效成本 = bestAsk(缺则 bestBid + tailDiffCostBuffer) + taker 费
        val rawPrice = bestAsk ?: bestBid.add(strategy.tailDiffCostBuffer)
        val feePerShare = rawPrice.multiply(BigDecimal(strategy.takerFeeBps)).divide(BigDecimal(10000), 8, RoundingMode.HALF_UP)
        val rawEffectiveCost = rawPrice.add(feePerShare)
        val edge = r.pWin.subtract(rawEffectiveCost)
        val metrics = BarrierMetrics(
            gap = gap,
            open = openP,
            close = closeP,
            sigma = sigma,
            remaining = remaining,
            pWin = r.pWin,
            side = r.side,
            safeRatio = r.safeRatio,
            effectiveCost = rawEffectiveCost,
            edge = edge,
            coin = priceStatus.coin,
            officialPriceSource = priceStatus.source,
            officialPriceAgeMs = priceStatus.ageMs,
            priceReadyReason = priceStatus.reason,
            priceMode = priceStatus.priceMode,
            lastSnapshotAt = priceStatus.lastSnapshotAt,
            lastRealtimeUpdateAt = priceStatus.lastRealtimeUpdateAt,
            latestPriceAgeMs = priceStatus.latestPriceAgeMs,
            latestSampleTime = priceStatus.latestSampleTime
        )
        val payload = mutableMapOf<String, Any>(
            "mode" to "TAIL_DIFF",
            "gap" to gap.toPlainString(),
            "open" to openP.toPlainString(),
            "close" to closeP.toPlainString(),
            "priceReadyReason" to priceStatus.reason,
            "coin" to (priceStatus.coin ?: ""),
            "sigmaPerSqrtS" to sigma.toPlainString(),
            "remainingSeconds" to remaining,
            "pWin" to r.pWin.toPlainString(),
            "modelSide" to r.side,
            "safeRatio" to r.safeRatio.toPlainString(),
            "bestBid" to bestBid.toPlainString(),
            "bestAsk" to (bestAsk?.toPlainString() ?: ""),
            "rawPrice" to rawPrice.toPlainString(),
            "takerFeeBps" to strategy.takerFeeBps,
            "feePerShare" to feePerShare.toPlainString(),
            "effectiveCost" to rawEffectiveCost.toPlainString(),
            "edge" to edge.toPlainString(),
            "tailDiffMinModelProb" to strategy.tailDiffMinModelProb.toPlainString(),
            "tailDiffMinEdge" to strategy.tailDiffMinEdge.toPlainString(),
            "tailDiffMinDiffSigma" to strategy.tailDiffMinDiffSigma.toPlainString(),
            "tailDiffHardMaxPrice" to strategy.tailDiffHardMaxPrice.toPlainString(),
            "maxOrderbookAgeMs" to strategy.maxOrderbookAgeMs,
            "maxPriceAgeMs" to strategy.maxPriceAgeMs
        )
        payload.putAll(readinessDiagnostics(priceStatus))
        payload.putAll(orderbookPayload(orderbook))
        fun snapshot(): String = payload.toJson()

        if (r.side != outcomeIndex) {
            return BarrierEval(false, "DIRECTION", "模型方向=${r.side}与当前outcome=${outcomeIndex}不一致", snapshot(), metrics)
        }
        if (rawEffectiveCost > strategy.tailDiffHardMaxPrice) {
            return BarrierEval(false, "MAX_ENTRY_PRICE", "有效成本=${rawEffectiveCost.toPlainString()}>tailDiffHardMaxPrice=${strategy.tailDiffHardMaxPrice.toPlainString()}", snapshot(), metrics)
        }
        if (r.pWin < strategy.tailDiffMinModelProb) {
            return BarrierEval(false, "PWIN", "pWin=${r.pWin.toPlainString()}<tailDiffMinModelProb=${strategy.tailDiffMinModelProb.toPlainString()}", snapshot(), metrics)
        }
        if (r.safeRatio < strategy.tailDiffMinDiffSigma) {
            return BarrierEval(false, "SAFE_RATIO", "diffSigma=${r.safeRatio.toPlainString()}<tailDiffMinDiffSigma=${strategy.tailDiffMinDiffSigma.toPlainString()}", snapshot(), metrics)
        }
        if (edge < strategy.tailDiffMinEdge) {
            return BarrierEval(false, "EV", "扣费edge=${edge.toPlainString()}<tailDiffMinEdge=${strategy.tailDiffMinEdge.toPlainString()}", snapshot(), metrics)
        }
        return BarrierEval(true, "ALL", "通过 TAIL_DIFF 进场安全复核闸", snapshot(), metrics)
    }

    /**
     * 概率阶梯止盈模式入场闸（BRACKET_DYNAMIC）：与 BARRIER 共用 pWin/EV/方向计算，但闸阈值独立。
     * 决策：
     *  - 方向一致：模型 side == outcomeIndex
     *  - pWin >= bracketEntryProb（默认 0.80，比障碍 0.55 严苛）
     *  - 扣费 edge >= bracketEntryEdge（默认 0.04，比障碍 0.02 严苛）
     *  - 有效成本 <= bracketMaxEntryPrice（避免高位接盘）
     * 阶梯模式强制 FAK 进场，因此有效成本=bestAsk(缺则 bestBid+costBuffer) + taker 费。
     */
    private fun evaluateBracketEntryGates(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        outcomeIndex: Int,
        bestBid: BigDecimal,
        bestAsk: BigDecimal?,
        orderbook: OrderbookQualitySnapshot?
    ): BarrierEval {
        checkEntryMarketQuality(strategy, periodStartUnix, orderbook)?.let { return it }
        if (!periodPriceProvider.isAvailable(strategy.marketSlugPrefix)) {
            val status = periodPriceProvider.getReadiness(strategy.marketSlugPrefix)
            return BarrierEval(false, "PRICE_SOURCE", "价源未就绪: ${status.source}/${status.coin ?: ""}/${status.reason}", statusPayload(status).toJson())
        }
        val oc = periodPriceProvider.getCurrentOpenClose(strategy.marketSlugPrefix, strategy.intervalSeconds, periodStartUnix)
            ?: run {
                val status = periodPriceProvider.getReadiness(strategy.marketSlugPrefix)
                return BarrierEval(false, "PRICE_SOURCE", "期初价未就绪: ${status.source}/${status.coin ?: ""}/${status.reason}", statusPayload(status).toJson())
            }
        val (openP, closeP) = oc
        val priceStatus = periodPriceProvider.getReadiness(strategy.marketSlugPrefix)
        val gap = closeP.subtract(openP)
        val nowSeconds = System.currentTimeMillis() / 1000
        val remaining = (periodStartUnix + strategy.intervalSeconds - nowSeconds).toDouble()
        val sideByGap = if (gap.signum() >= 0) 0 else 1
        val preSigmaSnapshot = mapOf(
            "open" to openP.toPlainString(),
            "close" to closeP.toPlainString(),
            "officialOpen" to openP.toPlainString(),
            "officialClose" to closeP.toPlainString(),
            "officialPriceSource" to priceStatus.source,
            "officialPriceAgeMs" to (priceStatus.ageMs ?: ""),
            "priceSource" to priceStatus.source,
            "coin" to (priceStatus.coin ?: ""),
            "priceReadyReason" to priceStatus.reason,
            "priceAgeMs" to (priceStatus.ageMs ?: ""),
            "fallbackUsed" to false,
            "gap" to gap.toPlainString(),
            "remainingSeconds" to remaining,
            "modelSideByGap" to sideByGap,
            "modelSideByGapText" to (if (sideByGap == 0) "Up(涨)" else "Down(跌)"),
            "evalOutcomeIndex" to outcomeIndex
        ).plus(readinessDiagnostics(priceStatus)).toJson()
        val sigma = periodPriceProvider.getSigmaPerSqrtS(
            strategy.marketSlugPrefix, strategy.intervalSeconds, periodStartUnix, outcomeIndex, strategy.sigmaScale,
            strategy.sigmaMethod, strategy.ewmaLambda
        ) ?: return BarrierEval(false, "PWIN", "σ基准不可用(价源历史样本不足，冷启动需累积若干周期后自动恢复)", preSigmaSnapshot)
        val r = BarrierProbability.winProbTerminal(gap, sigma, remaining)
            ?: return BarrierEval(false, "PWIN", "无法计算pWin(剩余时间或σ无效)", null)

        // BRACKET 模式强制 FAK 进场：有效成本 = bestAsk(缺则 bestBid+costBuffer) + taker 费
        val rawPrice = bestAsk ?: bestBid.add(strategy.costBuffer)
        val feePerShare = rawPrice.multiply(BigDecimal(strategy.takerFeeBps))
            .divide(BigDecimal(10000), 8, RoundingMode.HALF_UP)
        val effectiveCost = rawPrice.add(feePerShare)
        val edge = r.pWin.subtract(effectiveCost)
        val metrics = BarrierMetrics(
            gap = gap,
            open = openP,
            close = closeP,
            sigma = sigma,
            remaining = remaining,
            pWin = r.pWin,
            side = r.side,
            safeRatio = r.safeRatio,
            effectiveCost = effectiveCost,
            edge = edge,
            coin = priceStatus.coin,
            officialPriceSource = priceStatus.source,
            officialPriceAgeMs = priceStatus.ageMs,
            priceReadyReason = priceStatus.reason,
            priceMode = priceStatus.priceMode,
            lastSnapshotAt = priceStatus.lastSnapshotAt,
            lastRealtimeUpdateAt = priceStatus.lastRealtimeUpdateAt,
            latestPriceAgeMs = priceStatus.latestPriceAgeMs,
            latestSampleTime = priceStatus.latestSampleTime
        )
        val entryProb = probabilityEntryProb(strategy)
        val entryEdge = probabilityEntryEdge(strategy)
        val maxEntryPrice = probabilityMaxEntryPrice(strategy)
        val payload = mutableMapOf<String, Any>(
            "mode" to "BRACKET_DYNAMIC",
            "gap" to gap.toPlainString(),
            "open" to openP.toPlainString(),
            "close" to closeP.toPlainString(),
            "officialOpen" to openP.toPlainString(),
            "officialClose" to closeP.toPlainString(),
            "officialPriceSource" to priceStatus.source,
            "officialPriceAgeMs" to (priceStatus.ageMs ?: ""),
            "priceReadyReason" to priceStatus.reason,
            "coin" to (priceStatus.coin ?: ""),
            "fallbackUsed" to false,
            "sigmaPerSqrtS" to sigma.toPlainString(),
            "remainingSeconds" to remaining,
            "pWin" to r.pWin.toPlainString(),
            "modelSide" to r.side,
            "safeRatio" to r.safeRatio.toPlainString(),
            "bestBid" to bestBid.toPlainString(),
            "bestAsk" to (bestAsk?.toPlainString() ?: ""),
            "rawPrice" to rawPrice.toPlainString(),
            "takerFeeBps" to strategy.takerFeeBps,
            "feePerShare" to feePerShare.toPlainString(),
            "effectiveCost" to effectiveCost.toPlainString(),
            "edge" to edge.toPlainString(),
            "bracketEntryProb" to strategy.bracketEntryProb.toPlainString(),
            "bracketEntryEdge" to strategy.bracketEntryEdge.toPlainString(),
            "bracketMaxEntryPrice" to strategy.bracketMaxEntryPrice.toPlainString(),
            "entryProb" to entryProb.toPlainString(),
            "entryEdge" to entryEdge.toPlainString(),
            "maxEntryPrice" to maxEntryPrice.toPlainString(),
            "barrierMinMarketProb" to strategy.barrierMinMarketProb.toPlainString(),
            "minSafeRatio" to strategy.minSafeRatio.toPlainString(),
            "minSafeRatioUp" to strategy.minSafeRatioUp.toPlainString(),
            "minSafeRatioDown" to strategy.minSafeRatioDown.toPlainString(),
            "highPriceThreshold" to strategy.highPriceThreshold.toPlainString(),
            "highPriceMinPWin" to strategy.highPriceMinPWin.toPlainString(),
            "highPriceMinSafeRatio" to strategy.highPriceMinSafeRatio.toPlainString(),
            "maxEntrySpread" to strategy.maxEntrySpread.toPlainString(),
            "maxOrderbookAgeMs" to strategy.maxOrderbookAgeMs,
            "maxPriceAgeMs" to strategy.maxPriceAgeMs
        )
        payload.putAll(readinessDiagnostics(priceStatus))
        payload.putAll(orderbookPayload(orderbook))
        fun snapshot(): String = payload.toJson()

        if (r.side != outcomeIndex) {
            return BarrierEval(false, "DIRECTION", "模型方向=${r.side}与当前outcome=${outcomeIndex}不一致", snapshot(), metrics)
        }
        if (r.pWin < entryProb) {
            return BarrierEval(false, "PWIN", "pWin=${r.pWin.toPlainString()}<entryProb=${entryProb.toPlainString()}", snapshot(), metrics)
        }
        val safeRatioRequired = requiredSafeRatio(strategy, outcomeIndex)
        if (r.safeRatio < safeRatioRequired) {
            return BarrierEval(false, "SAFE_RATIO", "safeRatio=${r.safeRatio.toPlainString()}<required=${safeRatioRequired.toPlainString()}", snapshot(), metrics)
        }
        if (strategy.barrierMinMarketProb > BigDecimal.ZERO && bestBid < strategy.barrierMinMarketProb) {
            return BarrierEval(false, "MARKET_PROB", "市场概率=${bestBid.toPlainString()}<下限=${strategy.barrierMinMarketProb.toPlainString()}", snapshot(), metrics)
        }
        if (effectiveCost > maxEntryPrice) {
            return BarrierEval(false, "MAX_ENTRY_PRICE", "有效成本=${effectiveCost.toPlainString()}>maxEntryPrice=${maxEntryPrice.toPlainString()}", snapshot(), metrics)
        }
        if (edge < entryEdge) {
            return BarrierEval(false, "EV", "扣费edge=${edge.toPlainString()}<entryEdge=${entryEdge.toPlainString()}", snapshot(), metrics)
        }
        if (effectiveCost > strategy.highPriceThreshold &&
            (r.pWin < strategy.highPriceMinPWin || r.safeRatio < strategy.highPriceMinSafeRatio)
        ) {
            return BarrierEval(false, "HIGH_PRICE", "高价入场保护: effectiveCost=${effectiveCost.toPlainString()} pWin=${r.pWin.toPlainString()} safeRatio=${r.safeRatio.toPlainString()}", snapshot(), metrics)
        }
        val wick = wickSignalService.evaluate(strategy, outcomeIndex)
        payload.putAll(wick.toPayload().mapValues { it.value ?: "" })
        payload["wickFilterMode"] = strategy.wickFilterMode
        if (strategy.wickFilterMode.uppercase() == "ENFORCE" && wick.available && wick.reversalScore >= strategy.wickEntryBlockScore) {
            return BarrierEval(false, "WICK_REVERSAL", "影线反转分数=${wick.reversalScore}>=${strategy.wickEntryBlockScore}", snapshot(), metrics)
        }
        return BarrierEval(true, "ALL", "通过全部阶梯入场闸", snapshot(), metrics)
    }

    /** 记录障碍闸结果（每周期每结果去重，避免每个 tick 刷库） */
    private fun recordBarrierDecision(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        outcomeIndex: Int,
        eval: BarrierEval,
        tokenId: String? = null,
        stage: String? = null
    ) {
        // stage 非空（如 PRESUBMIT）：用独立去重键 + 原因前缀区分"发单前复检"与初次闸评估，避免同周期同 gate 被去重吞没（Q1 观测盲区）。
        val stagePrefix = stage?.let { "$it-" } ?: ""
        val reasonPrefix = stage?.let { "[发单前复检] " } ?: ""
        if (eval.pass) {
            recordDecisionOncePerPeriod(
                strategy, periodStartUnix, "GATE_PASSED-${stagePrefix}ALL", outcomeIndex,
                eventType = "GATE_PASSED", gateName = "ALL", passed = true, reason = reasonPrefix + (eval.reason ?: ""),
                payloadJson = eval.payloadJson, triggerId = null, tokenId = tokenId
            )
        } else {
            recordDecisionOncePerPeriod(
                strategy, periodStartUnix, "GATE_FAILED-${stagePrefix}${eval.gateName}", outcomeIndex,
                eventType = "GATE_FAILED", gateName = eval.gateName, passed = false, reason = reasonPrefix + (eval.reason ?: ""),
                payloadJson = eval.payloadJson, triggerId = null, tokenId = tokenId
            )
        }
    }

    /** 记录 Strong Gap Boost 决策（BOOST_*）。每周期去重一次（入场每周期至多一次）。 */
    private fun recordBoostDecision(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        outcomeIndex: Int,
        boost: CryptoTailBoostService.BoostDecision
    ) {
        recordDecisionOncePerPeriod(
            strategy, periodStartUnix, "BOOST-${boost.eventType}", outcomeIndex,
            eventType = boost.eventType, gateName = "STRONG_GAP_BOOST", passed = boost.applied,
            reason = boost.reason, payloadJson = boost.payload.toJson(), triggerId = null
        )
    }

    /** 发单前盘口复检结果：Abort=调用方应立即放弃下单；Proceed=继续，metricsEval 携带概率模式的 metrics（SCALP/LEGACY 为 null）。 */
    private sealed class PreSubmitOutcome {
        object Abort : PreSubmitOutcome()
        data class Proceed(val metricsEval: BarrierEval?) : PreSubmitOutcome()
    }

    /**
     * 发单前盘口复检（统一快/慢路径，根因修复）。
     * 进场判定基于 WS 盘口，到实际 REST 发单存在 1~3s 时点差，期间盘口可能塌陷，导致 FAK 跌破价格下限"接飞刀"成交。
     * 统一在发单前用刷新后的最新盘口复检进场闸：
     *  - refreshedOrderbook==null（LEGACY 或未刷新）→ Proceed(null)，沿用入场判定。
     *  - SCALP_FLIP → 复用 evaluateScalpEntryGates 对最新盘口复检（价格区间/窗口/剩余/深度/价差/反转/并发）；通过 Proceed(null)（SCALP 无 metrics），失败记录 GATE_FAILED 并 Abort。
     *  - 概率/尾盘模式 → 复用 evaluateProbabilityEntryGates；通过 Proceed(eval)（携带 metrics），失败记录并 Abort。
     */
    private fun resolvePreSubmitEval(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        outcomeIndex: Int,
        triggerPrice: BigDecimal,
        bestAsk: BigDecimal?,
        preRefreshOrderbook: OrderbookQualitySnapshot?,
        refreshedOrderbook: OrderbookQualitySnapshot?,
        tokenId: String?
    ): PreSubmitOutcome {
        if (refreshedOrderbook == null) return PreSubmitOutcome.Proceed(null)
        recordRefreshedAskDriftDiagnostic(strategy, periodStartUnix, outcomeIndex, preRefreshOrderbook, refreshedOrderbook, tokenId)
        if (strategy.mode == TradingMode.SCALP_FLIP) {
            val eval = evaluateScalpEntryGates(
                strategy, periodStartUnix, outcomeIndex,
                refreshedOrderbook.bestBid ?: triggerPrice, refreshedOrderbook.bestAsk ?: bestAsk, refreshedOrderbook
            )
            if (!eval.pass) {
                recordBarrierDecision(strategy, periodStartUnix, outcomeIndex, eval, tokenId, stage = "PRESUBMIT")
                return PreSubmitOutcome.Abort
            }
            return PreSubmitOutcome.Proceed(null)
        }
        val eval = evaluateProbabilityEntryGates(strategy, periodStartUnix, outcomeIndex, refreshedOrderbook)
        if (!eval.pass || eval.metrics == null) {
            recordBarrierDecision(strategy, periodStartUnix, outcomeIndex, eval, stage = "PRESUBMIT")
            return PreSubmitOutcome.Abort
        }
        return PreSubmitOutcome.Proceed(eval)
    }

    private fun evaluateProbabilityEntryGates(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        outcomeIndex: Int,
        orderbook: OrderbookQualitySnapshot
    ): BarrierEval {
        return when (strategy.mode) {
            TradingMode.BARRIER_HOLD -> evaluateBarrierGates(
                strategy,
                periodStartUnix,
                outcomeIndex,
                orderbook.bestBid,
                orderbook.bestAsk,
                orderbook
            )
            TradingMode.BRACKET_DYNAMIC -> evaluateBracketEntryGates(
                strategy,
                periodStartUnix,
                outcomeIndex,
                orderbook.bestBid,
                orderbook.bestAsk,
                orderbook
            )
            // TAIL_DIFF 用专属安全复核闸（重新签 FAK 救单时），TailDiffDecisionService 已在分发时通过完整决策；
            // 这里仅做价源/盘口保底 + TAIL_DIFF 专属阈值复核，绝不套 BARRIER 的 entryProb/entryEdge/wick 等阈值。
            TradingMode.TAIL_DIFF -> evaluateTailDiffEntryGates(
                strategy,
                periodStartUnix,
                outcomeIndex,
                orderbook.bestBid,
                orderbook.bestAsk,
                orderbook
            )
            TradingMode.LEGACY_SPREAD -> BarrierEval(true, "ALL", "旧价差模式不走概率入场闸", null)
            // SCALP_FLIP 与 LEGACY 一样走极简进场（跳过概率/EV 复核），进场闸已在 dispatch 分支独立把关
            TradingMode.SCALP_FLIP -> BarrierEval(true, "ALL", "快进快出模式不走概率入场闸", null)
        }
    }

    /**
     * 记录 ORDER_SUBMITTED（下单前），携带信号/盘口/阈值/订单意图全量快照，供单笔分析快照投影。
     * 每周期去重一次。metrics 为空（理论不应发生于通过分支）时跳过。
     */
    private fun recordOrderSubmitted(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        outcomeIndex: Int,
        metrics: BarrierMetrics?,
        bestBid: BigDecimal,
        bestAsk: BigDecimal?,
        scaling: CryptoTailCalibrationService.ScalingDecision? = null,
        amountOverrideUsdc: BigDecimal? = null,
        pricing: CryptoTailFakPricingPolicy.Result? = null,
        orderbookRefreshed: Boolean = false,
        preRefreshOrderbook: OrderbookQualitySnapshot? = null,
        refreshedOrderbook: OrderbookQualitySnapshot? = null,
        restSkipped: Boolean = false,
        tokenId: String? = null
    ) {
        if (metrics == null) return
        val isMaker = strategy.entryOrderType.uppercase() == "MAKER"
        val targetPrice = when {
            isMaker -> computeMakerPrice(strategy, bestBid, bestAsk)
            pricing != null -> pricing.finalLimit
            else -> computeBuyPrice(strategy, bestBid, bestAsk)
        }
        val mid = bestAsk?.let { bestBid.add(it).divide(BigDecimal(2), 8, RoundingMode.HALF_UP) } ?: bestBid
        val payload = mapOf(
            "marketSlug" to strategy.marketSlugPrefix,
            "coin" to (metrics.coin ?: ""),
            "intervalSeconds" to strategy.intervalSeconds,
            "gap" to metrics.gap.toPlainString(),
            "open" to metrics.open.toPlainString(),
            "close" to metrics.close.toPlainString(),
            "officialOpen" to metrics.open.toPlainString(),
            "officialClose" to metrics.close.toPlainString(),
            "officialPriceSource" to metrics.officialPriceSource,
            "officialPriceAgeMs" to (metrics.officialPriceAgeMs ?: ""),
            "priceReadyReason" to metrics.priceReadyReason,
            "fallbackUsed" to false,
            "sigmaPerSqrtS" to metrics.sigma.toPlainString(),
            "remainingSeconds" to metrics.remaining.toLong().toString(),
            "pWin" to metrics.pWin.toPlainString(),
            "modelSide" to metrics.side.toString(),
            "safeRatio" to metrics.safeRatio.toPlainString(),
            "bestBid" to bestBid.toPlainString(),
            "bestAsk" to (bestAsk?.toPlainString() ?: ""),
            "outcomeBestBid" to bestBid.toPlainString(),
            "outcomeBestAsk" to (bestAsk?.toPlainString() ?: ""),
            "spread" to (bestAsk?.subtract(bestBid)?.toPlainString() ?: ""),
            "mid" to mid.toPlainString(),
            "effectiveCost" to metrics.effectiveCost.toPlainString(),
            "edge" to metrics.edge.toPlainString(),
            "entryProb" to strategy.entryProb.toPlainString(),
            "entryEdge" to strategy.entryEdge.toPlainString(),
            "barrierMinMarketProb" to strategy.barrierMinMarketProb.toPlainString(),
            "sigmaScale" to strategy.sigmaScale.toPlainString(),
            "maxEntryPrice" to strategy.maxEntryPrice.toPlainString(),
            "costBuffer" to strategy.costBuffer.toPlainString(),
            "orderType" to (if (isMaker) "GTC_POST_ONLY" else "FAK"),
            "targetPrice" to targetPrice.toPlainString(),
            "scalingMode" to (if (scaling != null && scaling.useProbe) "PROBE" else "FULL"),
            "scalingReason" to (scaling?.reason ?: ""),
            "probeAmountUsdc" to strategy.probeAmountUsdc.toPlainString(),
            "effectiveAmountUsdc" to (amountOverrideUsdc?.toPlainString() ?: strategy.amountValue.toPlainString())
        ).plus(metricsDiagnostics(metrics))
            .plus(pricingPayload(pricing, orderbookRefreshed))
            .plus(orderbookRefreshPayload(preRefreshOrderbook, refreshedOrderbook, orderbookRefreshed, restSkipped))
            .toJson()
        val scalingNote = if (scaling != null && scaling.useProbe) " [放量闸:小额 ${strategy.probeAmountUsdc.toPlainString()}]" else ""
        recordDecisionOncePerPeriod(
            strategy, periodStartUnix, "ORDER_SUBMITTED", outcomeIndex,
            eventType = "ORDER_SUBMITTED", gateName = null, passed = null,
            reason = "已提交下单 目标价=${targetPrice.toPlainString()} 剩余=${metrics.remaining.toLong()}s$scalingNote",
            payloadJson = payload, triggerId = null, tokenId = tokenId
        )
    }

    /**
     * 阶梯模式入场单提交快照：与 [recordOrderSubmitted] 同形但写入 bracket* 阈值与全部止盈/止损配置，
     * 便于后续复盘"当时阈值是什么"。
     */
    private fun recordBracketOrderSubmitted(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        outcomeIndex: Int,
        metrics: BarrierMetrics?,
        bestBid: BigDecimal,
        bestAsk: BigDecimal?,
        pricing: CryptoTailFakPricingPolicy.Result? = null,
        orderbookRefreshed: Boolean = false,
        preRefreshOrderbook: OrderbookQualitySnapshot? = null,
        refreshedOrderbook: OrderbookQualitySnapshot? = null,
        restSkipped: Boolean = false,
        tokenId: String? = null
    ) {
        if (metrics == null) return
        val targetPrice = pricing?.finalLimit ?: computeBuyPrice(strategy, bestBid, bestAsk)
        val mid = bestAsk?.let { bestBid.add(it).divide(BigDecimal(2), 8, RoundingMode.HALF_UP) } ?: bestBid
        val payload = mapOf(
            "mode" to "BRACKET_DYNAMIC",
            "marketSlug" to strategy.marketSlugPrefix,
            "coin" to (metrics.coin ?: ""),
            "intervalSeconds" to strategy.intervalSeconds,
            "gap" to metrics.gap.toPlainString(),
            "open" to metrics.open.toPlainString(),
            "close" to metrics.close.toPlainString(),
            "officialOpen" to metrics.open.toPlainString(),
            "officialClose" to metrics.close.toPlainString(),
            "officialPriceSource" to metrics.officialPriceSource,
            "officialPriceAgeMs" to (metrics.officialPriceAgeMs ?: ""),
            "priceReadyReason" to metrics.priceReadyReason,
            "fallbackUsed" to false,
            "sigmaPerSqrtS" to metrics.sigma.toPlainString(),
            "remainingSeconds" to metrics.remaining.toLong().toString(),
            "pWin" to metrics.pWin.toPlainString(),
            "modelSide" to metrics.side.toString(),
            "safeRatio" to metrics.safeRatio.toPlainString(),
            "bestBid" to bestBid.toPlainString(),
            "bestAsk" to (bestAsk?.toPlainString() ?: ""),
            "outcomeBestBid" to bestBid.toPlainString(),
            "outcomeBestAsk" to (bestAsk?.toPlainString() ?: ""),
            "spread" to (bestAsk?.subtract(bestBid)?.toPlainString() ?: ""),
            "mid" to mid.toPlainString(),
            "effectiveCost" to metrics.effectiveCost.toPlainString(),
            "edge" to metrics.edge.toPlainString(),
            "bracketEntryProb" to strategy.bracketEntryProb.toPlainString(),
            "bracketEntryEdge" to strategy.bracketEntryEdge.toPlainString(),
            "bracketMaxEntryPrice" to strategy.bracketMaxEntryPrice.toPlainString(),
            "tp1Price" to strategy.tp1Price.toPlainString(),
            "tp1Ratio" to strategy.tp1Ratio.toPlainString(),
            "tp1HoldPwin" to strategy.tp1HoldPwin.toPlainString(),
            "tp2Price" to strategy.tp2Price.toPlainString(),
            "tp2Ratio" to strategy.tp2Ratio.toPlainString(),
            "tp2HoldPwin" to strategy.tp2HoldPwin.toPlainString(),
            "holdToSettlePwin" to strategy.holdToSettlePwin.toPlainString(),
            "holdToSettleSeconds" to strategy.holdToSettleSeconds,
            "stopProb" to strategy.stopProb.toPlainString(),
            "stopPrice" to strategy.stopPrice.toPlainString(),
            "forceExitBeforeSettleSeconds" to strategy.forceExitBeforeSettleSeconds,
            "exitOrderType" to strategy.exitOrderType,
            "orderType" to "FAK",
            "targetPrice" to targetPrice.toPlainString(),
            "effectiveAmountUsdc" to strategy.amountValue.toPlainString()
        ).plus(metricsDiagnostics(metrics))
            .plus(pricingPayload(pricing, orderbookRefreshed))
            .plus(orderbookRefreshPayload(preRefreshOrderbook, refreshedOrderbook, orderbookRefreshed, restSkipped))
            .toJson()
        recordDecisionOncePerPeriod(
            strategy, periodStartUnix, "ORDER_SUBMITTED", outcomeIndex,
            eventType = "ORDER_SUBMITTED", gateName = null, passed = null,
            reason = "已提交阶梯入场单 目标价=${targetPrice.toPlainString()} 剩余=${metrics.remaining.toLong()}s",
            payloadJson = payload, triggerId = null, tokenId = tokenId
        )
    }

    /**
     * 快进快出（SCALP_FLIP）入场单提交快照：发 ORDER_SUBMITTED 决策事件，供成交快照投影/复盘因子读取进场口径。
     *
     * 背景：SCALP 进场原先只记 ORDER_RESULT/SETTLED，从不记 ORDER_SUBMITTED，导致投影器建出的快照
     *   submitTs 与进场字段(gap/pWin/safeRatio)全空，使复盘因子的回填(按 submitTs 过滤)与页面日期过滤(按 entryTs)
     *   将 SCALP 全部排除。此处补记，使 SCALP 与 BARRIER/BRACKET 同口径进入复盘因子链路。
     *
     * 复用决策：独立新写（不复用 recordOrderSubmitted/recordBracketOrderSubmitted，二者强依赖 BarrierMetrics，
     *   而 SCALP 只有 ScalpEntrySignal；强行复用会引入坏耦合）。进场信号取自入场时冻结的 scalpEntrySnapshotCache
     *   （此刻尚未被 saveTriggerRecord 清理）；价源不可用时信号为空（与历史 graceful 降级一致，留空不阻断）。
     *   open/close/σ 不在 SCALP 信号内，留空（投影器对 null 容忍）。盘口刷新/定价诊断字段复用既有 *Payload 辅助。
     */
    private fun recordScalpOrderSubmitted(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        outcomeIndex: Int,
        bestBid: BigDecimal,
        bestAsk: BigDecimal?,
        targetPrice: BigDecimal,
        pricing: CryptoTailFakPricingPolicy.Result? = null,
        orderbookRefreshed: Boolean = false,
        preRefreshOrderbook: OrderbookQualitySnapshot? = null,
        refreshedOrderbook: OrderbookQualitySnapshot? = null,
        restSkipped: Boolean = false,
        tokenId: String? = null
    ) {
        val signal = scalpEntrySnapshotCache.getIfPresent(tailDiffSnapshotKey(strategy.id!!, periodStartUnix, outcomeIndex))
        val remainingSeconds = signal?.remainingSeconds
            ?: (periodStartUnix + strategy.intervalSeconds - System.currentTimeMillis() / 1000).toInt()
        val mid = bestAsk?.let { bestBid.add(it).divide(BigDecimal(2), 8, RoundingMode.HALF_UP) } ?: bestBid
        val payload = mapOf(
            "mode" to "SCALP_FLIP",
            "marketSlug" to strategy.marketSlugPrefix,
            "intervalSeconds" to strategy.intervalSeconds,
            "gap" to (signal?.gap?.toPlainString() ?: ""),
            "pWin" to (signal?.pWin?.toPlainString() ?: ""),
            "modelSide" to (signal?.modelSide?.toString() ?: ""),
            "safeRatio" to (signal?.safeRatio?.toPlainString() ?: ""),
            "remainingSeconds" to remainingSeconds.toLong().toString(),
            "bestBid" to bestBid.toPlainString(),
            "bestAsk" to (bestAsk?.toPlainString() ?: ""),
            "outcomeBestBid" to bestBid.toPlainString(),
            "outcomeBestAsk" to (bestAsk?.toPlainString() ?: ""),
            "spread" to (bestAsk?.subtract(bestBid)?.toPlainString() ?: ""),
            "mid" to mid.toPlainString(),
            "orderType" to "FAK",
            "targetPrice" to targetPrice.toPlainString(),
            "scalpEntryMinPrice" to strategy.scalpEntryMinPrice.toPlainString(),
            "scalpEntryMaxPrice" to strategy.scalpEntryMaxPrice.toPlainString(),
            "scalpEntryMinPwin" to strategy.scalpEntryMinPwin.toPlainString(),
            "effectiveAmountUsdc" to strategy.amountValue.toPlainString()
        ).plus(pricingPayload(pricing, orderbookRefreshed))
            .plus(orderbookRefreshPayload(preRefreshOrderbook, refreshedOrderbook, orderbookRefreshed, restSkipped))
            .toJson()
        recordDecisionOncePerPeriod(
            strategy, periodStartUnix, "ORDER_SUBMITTED", outcomeIndex,
            eventType = "ORDER_SUBMITTED", gateName = null, passed = null,
            reason = "已提交快进快出入场单 目标价=${targetPrice.toPlainString()} 剩余=${remainingSeconds}s",
            payloadJson = payload, triggerId = null, tokenId = tokenId
        )
    }

    private fun recordEvSafeLimitRejected(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        outcomeIndex: Int,
        metrics: BarrierMetrics?,
        pricing: CryptoTailFakPricingPolicy.Result,
        orderbookRefreshed: Boolean,
        tokenId: String? = null
    ) {
        val payload = mapOf(
            "marketSlug" to strategy.marketSlugPrefix,
            "intervalSeconds" to strategy.intervalSeconds,
            "pWin" to (metrics?.pWin?.toPlainString() ?: ""),
            "requiredEdge" to requiredEntryEdge(strategy).toPlainString(),
            "takerFeeBps" to strategy.takerFeeBps,
            "mode" to strategy.mode.name
        ).plus(pricingPayload(pricing, orderbookRefreshed)).toJson()
        recordDecisionOncePerPeriod(
            strategy, periodStartUnix, "GATE_FAILED-EV_SAFE_LIMIT", outcomeIndex,
            eventType = "GATE_FAILED", gateName = "EV_SAFE_LIMIT", passed = false,
            reason = "EV安全最高价=${pricing.evSafeLimit.toPlainString()}低于可成交ask=${pricing.executableAsk.toPlainString()}，放弃FAK进场",
            payloadJson = payload, triggerId = null, tokenId = tokenId
        )
    }

    private fun validateFinalLimitInvariant(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        outcomeIndex: Int,
        metrics: BarrierMetrics?,
        pricing: CryptoTailFakPricingPolicy.Result?,
        finalLimitPrice: BigDecimal,
        orderbookRefreshed: Boolean,
        tokenId: String? = null
    ): Boolean {
        if (strategy.mode == TradingMode.LEGACY_SPREAD || pricing == null) return true
        val maxAllowed = pricing.priceCap.min(pricing.evSafeLimit)
        if (finalLimitPrice <= maxAllowed) return true
        val payload = mapOf(
            "marketSlug" to strategy.marketSlugPrefix,
            "intervalSeconds" to strategy.intervalSeconds,
            "pWin" to (metrics?.pWin?.toPlainString() ?: ""),
            "mode" to strategy.mode.name,
            "maxAllowedFinalLimit" to maxAllowed.toPlainString()
        ).plus(pricingPayload(pricing, orderbookRefreshed)).toJson()
        recordDecisionOncePerPeriod(
            strategy,
            periodStartUnix,
            "GATE_FAILED-FINAL_LIMIT_ABOVE_EV_SAFE_LIMIT",
            outcomeIndex,
            eventType = "GATE_FAILED",
            gateName = "FINAL_LIMIT_ABOVE_EV_SAFE_LIMIT",
            passed = false,
            reason = "finalLimitPrice=${finalLimitPrice.toPlainString()} > min(priceCap=${pricing.priceCap.toPlainString()}, evSafeLimit=${pricing.evSafeLimit.toPlainString()})=${maxAllowed.toPlainString()}",
            payloadJson = payload,
            triggerId = null,
            tokenId = tokenId
        )
        return false
    }

    private fun recordRefreshedAskDriftDiagnostic(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        outcomeIndex: Int,
        preRefreshOrderbook: OrderbookQualitySnapshot?,
        refreshedOrderbook: OrderbookQualitySnapshot,
        tokenId: String? = null
    ) {
        val preAsk = preRefreshOrderbook?.bestAsk ?: return
        val refreshedAsk = refreshedOrderbook.bestAsk ?: return
        if (strategy.maxEntrySpread <= BigDecimal.ZERO) return
        val diff = refreshedAsk.subtract(preAsk).abs()
        if (diff <= strategy.maxEntrySpread) return
        val payload = mapOf(
            "marketSlug" to strategy.marketSlugPrefix,
            "mode" to strategy.mode.name,
            "askDiff" to diff.toPlainString(),
            "maxEntrySpread" to strategy.maxEntrySpread.toPlainString()
        ).plus(orderbookRefreshPayload(preRefreshOrderbook, refreshedOrderbook, true)).toJson()
        recordDecisionOncePerPeriod(
            strategy,
            periodStartUnix,
            "ORDERBOOK_REFRESH-REFRESHED_ASK_CHANGED_TOO_MUCH",
            outcomeIndex,
            eventType = "ORDERBOOK_REFRESH_DIAGNOSTIC",
            gateName = "REFRESHED_ASK_CHANGED_TOO_MUCH",
            passed = null,
            reason = "REFRESHED_ASK_CHANGED_TOO_MUCH",
            payloadJson = payload,
            triggerId = null,
            tokenId = tokenId
        )
    }

    /** 决策日志：同一周期同一 key 只记一次（内存去重，热路径友好），通过解耦 recorder 异步落库/推送 */
    private fun recordDecisionOncePerPeriod(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        dedupeKey: String,
        outcomeIndex: Int?,
        eventType: String,
        gateName: String?,
        passed: Boolean?,
        reason: String?,
        payloadJson: String?,
        triggerId: Long?,
        tokenId: String? = null
    ) {
        val strategyId = strategy.id ?: return
        val key = "$strategyId-$periodStartUnix-$dedupeKey"
        if (decisionLoggedCache.getIfPresent(key) != null) return
        decisionLoggedCache.put(key, true)
        val payload = enrichDecisionPayload(strategy, periodStartUnix, outcomeIndex, triggerId, tokenId, payloadJson)
        decisionRecorder.record(
            CryptoTailDecisionEvent(
                strategyId = strategyId,
                periodStartUnix = periodStartUnix,
                correlationId = "$strategyId-$periodStartUnix",
                eventType = eventType,
                gateName = gateName,
                passed = passed,
                reason = reason,
                payloadJson = payload,
                outcomeIndex = outcomeIndex,
                triggerId = triggerId
            )
        )
    }

    private fun enrichDecisionPayload(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        outcomeIndex: Int?,
        triggerId: Long?,
        tokenId: String?,
        payloadJson: String?
    ): String {
        val base = payloadJson?.fromJson<Map<String, Any?>>()?.toMutableMap() ?: mutableMapOf()
        base.putIfAbsent("strategyId", strategy.id ?: "")
        base.putIfAbsent("strategyName", strategy.name ?: "")
        base.putIfAbsent("coin", CryptoTailCoinResolver.coinOfSlug(strategy.marketSlugPrefix) ?: "")
        base.putIfAbsent("marketSlug", strategy.marketSlugPrefix)
        base.putIfAbsent("periodStartUnix", periodStartUnix)
        base.putIfAbsent("tokenId", tokenId ?: base["tokenId"] ?: "")
        base.putIfAbsent("outcomeIndex", outcomeIndex ?: "")
        base.putIfAbsent("triggerId", triggerId ?: "")
        return base.toJson()
    }

    private fun passSpreadCheck(strategy: CryptoTailStrategy, periodStartUnix: Long, outcomeIndex: Int): Boolean {
        if (strategy.spreadMode == SpreadMode.NONE) return true
        val oc = binanceKlineService.getCurrentOpenClose(
            strategy.marketSlugPrefix,
            strategy.intervalSeconds,
            periodStartUnix
        )
            ?: return false
        val (openP, closeP) = oc
        val spreadAbs = closeP.subtract(openP).abs()

        // 获取有效价差
        val effectiveSpread = when (strategy.spreadMode) {
            SpreadMode.FIXED -> {
                strategy.spreadValue?.takeIf { it > BigDecimal.ZERO } ?: return true
            }

            SpreadMode.AUTO -> {
                val result = computeAutoEffectiveSpread(strategy, periodStartUnix, outcomeIndex) ?: return true
                result.effectiveSpread.takeIf { it > BigDecimal.ZERO } ?: return true
            }

            SpreadMode.NONE -> return true
        }

        // 根据价差方向判断
        return if (strategy.spreadDirection == SpreadDirection.MAX) {
            // 最大价差模式：价差 <= 配置值时触发
            spreadAbs <= effectiveSpread
        } else {
            // 最小价差模式：价差 >= 配置值时触发
            spreadAbs >= effectiveSpread
        }
    }

    /**
     * AUTO 模式：取 100% 基准价差，按窗口内毫秒进度计算动态系数（100%→50%）得到有效价差。
     */
    private data class AutoSpreadResult(
        val baseSpread: BigDecimal,
        val coefficient: BigDecimal,
        val effectiveSpread: BigDecimal
    )

    private fun computeAutoEffectiveSpread(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        outcomeIndex: Int
    ): AutoSpreadResult? {
        val baseSpread = binanceKlineAutoSpreadService.getAutoMinSpreadBase(
            strategy.marketSlugPrefix,
            strategy.intervalSeconds,
            periodStartUnix,
            outcomeIndex
        )
            ?: binanceKlineAutoSpreadService.computeAndCache(
                strategy.marketSlugPrefix,
                strategy.intervalSeconds,
                periodStartUnix
            )?.let { if (outcomeIndex == 0) it.first else it.second }
            ?: return null
        if (baseSpread <= BigDecimal.ZERO) return null
        val windowStartMs = (periodStartUnix + strategy.windowStartSeconds) * 1000L
        val windowEndMs = (periodStartUnix + strategy.windowEndSeconds) * 1000L
        val windowLenMs = windowEndMs - windowStartMs
        val coefficient = if (windowLenMs <= 0) {
            BigDecimal.ONE
        } else {
            val nowMs = System.currentTimeMillis()
            val elapsedMs = (nowMs - windowStartMs).toBigDecimal()
            val progress = elapsedMs.div(windowLenMs.toBigDecimal(), 18, RoundingMode.HALF_UP)
                .let { p -> maxOf(BigDecimal.ZERO, minOf(BigDecimal.ONE, p)) }
            BigDecimal.ONE.subtract(progress.multi("0.5"))
        }
        val effectiveSpread = baseSpread.multi(coefficient).setScale(8, RoundingMode.HALF_UP)
        return AutoSpreadResult(baseSpread, coefficient, effectiveSpread)
    }

    /**
     * 计算旧语义买入限价：
     * - 障碍模式：有效成本 = bestAsk（缺失则 bestBid+costBuffer），加 entryFakSlippage（V53）后向上取整到 4 位，封顶 maxEntryPrice。
     * - 阶梯模式：同障碍但封顶用 bracketMaxEntryPrice（独立阈值，避免与障碍互相影响）。
     * - 旧模式：最大价差 = 触发价+0.02；否则固定 0.99（保持原行为）。
     *
     * 自动 BARRIER/BRACKET FAK 进场使用 CryptoTailFakPricingPolicy 计算 EV 安全限价；
     * 本函数保留给旧价差、手动缺上下文路径与 maker fallback，避免不相关行为被本次改造牵连。
     */
    private fun computeBuyPrice(strategy: CryptoTailStrategy, triggerPrice: BigDecimal, bestAsk: BigDecimal?): BigDecimal {
        return when (strategy.mode) {
            TradingMode.BARRIER_HOLD -> {
                val effectiveCost = bestAsk ?: triggerPrice.add(strategy.costBuffer)
                effectiveCost.add(strategy.entryFakSlippage)
                    .setScale(4, RoundingMode.UP)
                    .min(strategy.maxEntryPrice)
            }
            TradingMode.BRACKET_DYNAMIC -> {
                val effectiveCost = bestAsk ?: triggerPrice.add(strategy.costBuffer)
                effectiveCost.add(strategy.entryFakSlippage)
                    .setScale(4, RoundingMode.UP)
                    .min(strategy.bracketMaxEntryPrice)
            }
            TradingMode.TAIL_DIFF -> {
                // TAIL_DIFF FAK 进场：cost = bestAsk(缺则 bestBid+costBuffer) + entryFakSlippage，封顶 hardMaxPrice
                val effectiveCost = bestAsk ?: triggerPrice.add(strategy.tailDiffCostBuffer)
                effectiveCost.add(strategy.entryFakSlippage)
                    .setScale(4, RoundingMode.UP)
                    .min(strategy.tailDiffHardMaxPrice)
            }
            TradingMode.SCALP_FLIP -> {
                // 快进快出 FAK 进场：cost = bestAsk(缺则 triggerPrice+小缓冲) + entryFakSlippage，封顶 scalpMaxFillPrice
                val effectiveCost = bestAsk ?: triggerPrice.add(strategy.costBuffer)
                effectiveCost.add(strategy.entryFakSlippage)
                    .setScale(4, RoundingMode.UP)
                    .min(strategy.scalpMaxFillPrice)
            }
            TradingMode.LEGACY_SPREAD -> {
                if (strategy.spreadDirection == SpreadDirection.MAX) {
                    triggerPrice.add(BigDecimal(SPREAD_MAX_PRICE_ADJUSTMENT)).setScale(8, RoundingMode.HALF_UP)
                } else {
                    BigDecimal(TRIGGER_FIXED_PRICE)
                }
            }
        }
    }

    /**
     * 发单前"WS 主 / REST 兜底"取数（仅 SCALP_FLIP，WS3 根因修复）：
     * 在执行点实时取 [orderbookCache] 的最新 WS 帧（持续随 WS 消息更新，~ms 级新鲜），
     * 而非复用判定时冻结、透传下来的旧快照（旧实现量错对象：透传帧经 ~0.5-2s 前置工作后已老化，几乎永远超阈值）。
     *
     * 返回非空表示"可用 WS 实时帧跳过发单前 REST"，条件全部满足：
     *  - mode==SCALP_FLIP 且 scalpWsFreshnessSkipRestMs>0
     *  - WS 帧存在、含 bestAsk、quoteAgeMs<=阈值、且 ask 本身在新鲜窗口内确有更新(askAgeMs<=阈值)
     *  - 若退出深度门(scalpMinExitBidDepthUsdc)有效，则额外要求深度不陈旧（depthStale=false 且 bidDepthUsd 非空），
     *    否则回退 REST，避免用 price_change 复用的陈旧 bidDepthUsd 误判深度门。
     * 任一不满足返回 null（照常 REST 重拉，零回归）。
     */
    /**
     * WS 主取数结果：[snapshot] 非空表示可用 WS 实时帧跳过 REST；为空时 [reason] 记录回退 REST 的原因（观测用）。
     * [acceptPath]：命中 WS 时的接受路径——"fast"=单腿 quote/ask 够新；"feedAlive"=单腿稍旧但行情源整体存活。
     * [feedAgeMs]：行情源最近收帧距今毫秒（feedAlive/feedDead 时有值），供日志定位"安静腿误杀 vs 连接真失活"。
     */
    private data class WsPick(
        val snapshot: OrderbookQualitySnapshot?,
        val reason: String,
        val acceptPath: String = "",
        val feedAgeMs: Long? = null
    )

    private fun pickLiveWsOrderbook(
        strategy: CryptoTailStrategy,
        tokenId: String
    ): WsPick {
        if (strategy.mode != TradingMode.SCALP_FLIP) return WsPick(null, "disabled")
        if (strategy.scalpWsFreshnessSkipRestMs <= 0) return WsPick(null, "disabled")
        val ws = orderbookCache.latestSnapshot(tokenId) ?: return WsPick(null, "noWsFrame")
        val ask = ws.bestAsk ?: return WsPick(null, "noAsk")
        // 真交叉(bestAsk < bestBid)为坏数据，回退 REST 防止据此定出错误限价；bid==ask 是最小 tick 锁定盘(可买)，不算交叉。
        if (ask < ws.bestBid) return WsPick(null, "crossed")
        val nowMs = System.currentTimeMillis()
        // 深度门优先于新鲜度判定：深度不足直接回退 REST（与原行为一致，且对 fast/feedAlive 两路统一适用）。
        // L2 重建后 depthStale 恒为 false（深度随增量持续维护）；若未播种(冷启动)则上游 latestSnapshot 为空，已在 noWsFrame 兜底。
        val minExitDepth = strategy.scalpMinExitBidDepthUsdc
        if (minExitDepth != null && minExitDepth > BigDecimal.ZERO) {
            if (ws.depthStale || ws.bidDepthUsd == null) return WsPick(null, "depthStale")
        }
        // 快路径（原行为，零回归）：单腿 quote 与 ask 均在 scalpWsFreshnessSkipRestMs 新鲜窗口内。
        val quoteFresh = ws.quoteAgeMs(nowMs) <= strategy.scalpWsFreshnessSkipRestMs
        val askAge = ws.askAgeMs(nowMs)
        val askFresh = askAge != null && askAge <= strategy.scalpWsFreshnessSkipRestMs
        if (quoteFresh && askFresh) return WsPick(ws, "WS_LIVE", acceptPath = "fast")
        // 放宽路径（纯追加）：单腿稍旧但行情源整体存活时仍采用 WS——L2 盘口权威常驻，安静腿没新帧≠数据陈旧，
        // 远胜回退 ~700ms+ 的陈旧 REST。真半死连接(全局长时间无帧)走下方 feedDead 回退 REST。
        if (scalpWsFeedAliveBoundMs > 0) {
            val feedAge = orderbookCache.feedAgeMs(nowMs)
            if (feedAge != null && feedAge <= scalpWsFeedAliveBoundMs) {
                return WsPick(ws, "WS_LIVE", acceptPath = "feedAlive", feedAgeMs = feedAge)
            }
            return WsPick(null, "feedDead", feedAgeMs = feedAge)
        }
        // 放宽关闭(=0)：保持旧行为，按具体陈旧原因回退 REST。
        return WsPick(null, if (!quoteFresh) "quoteStale" else "askStale")
    }

    /**
     * 进场最终限价（WS3，fast/slow 两路共用）：
     *  - 概率/EV 路径（pricing 非空，BARRIER/BRACKET/TAIL_DIFF）→ 直接用 pricing.finalLimit（行为不变）。
     *  - SCALP_FLIP → 在原 computeBuyPrice(min(ask+滑点, scalpMaxFillPrice)) 基础上，叠加"EV 安全上沿"作*追价上限*：
     *    finalLimit = base.min(max(evSafeLimit, executableAsk))。即始终允许以已通过进场闸的当前 ask 成交（不否决），
     *    但向上追价不超过 EV 安全价，避免盘口上跳时追到 -EV。信号/价源不可用 → 回退 base（与旧行为一致，绝不臆造）。
     *  - 其余（LEGACY）→ computeBuyPrice。
     */
    private fun computeEntryLimitPrice(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        outcomeIndex: Int,
        pricing: CryptoTailFakPricingPolicy.Result?,
        triggerPrice: BigDecimal,
        effectiveBestBid: BigDecimal,
        effectiveBestAsk: BigDecimal?
    ): BigDecimal {
        pricing?.let { return it.finalLimit }
        val base = computeBuyPrice(strategy, triggerPrice, effectiveBestAsk)
        if (strategy.mode != TradingMode.SCALP_FLIP) return base
        val nowSeconds = System.currentTimeMillis() / 1000
        val remainingSeconds = (periodStartUnix + strategy.intervalSeconds - nowSeconds).toInt()
        val signal = computeScalpEntrySignal(strategy, periodStartUnix, outcomeIndex, remainingSeconds) ?: return base
        val ev = computeFakPricing(strategy, effectiveBestBid, effectiveBestAsk, signal.pWin)
        val chaseCeil = ev.evSafeLimit.max(ev.executableAsk)
        return base.min(chaseCeil)
    }

    private suspend fun buildRetryFakOrderAttempt(
        strategy: CryptoTailStrategy,
        clobApi: PolymarketClobApi,
        tokenId: String,
        amountUsdc: BigDecimal,
        periodStartUnix: Long,
        outcomeIndex: Int,
        privateKey: String,
        makerAddress: String,
        owner: String,
        signatureType: Int,
        settleAtUnix: Long
    ): FakOrderAttempt? {
        val nowSeconds = System.currentTimeMillis() / 1000
        if (nowSeconds >= settleAtUnix) return null
        val refreshedOrderbook = orderbookSnapshotFetcher.fetch(clobApi, tokenId) ?: return null
        val eval = evaluateProbabilityEntryGates(strategy, periodStartUnix, outcomeIndex, refreshedOrderbook)
        if (!eval.pass || eval.metrics == null) {
            recordBarrierDecision(strategy, periodStartUnix, outcomeIndex, eval)
            return null
        }
        val pricing = computeFakPricing(strategy, refreshedOrderbook.bestBid, refreshedOrderbook.bestAsk, eval.metrics.pWin)
        if (!pricing.canSubmit) {
            logger.warn("FAK一跳救单被EV安全价拦截: strategyId=${strategy.id}, tokenId=$tokenId, evSafeLimit=${pricing.evSafeLimit.toPlainString()}, executableAsk=${pricing.executableAsk.toPlainString()}")
            recordEvSafeLimitRejected(strategy, periodStartUnix, outcomeIndex, eval.metrics, pricing, true, tokenId)
            return null
        }
        if (!validateFinalLimitInvariant(strategy, periodStartUnix, outcomeIndex, eval.metrics, pricing, pricing.finalLimit, true, tokenId)) {
            return null
        }
        val size = computeSize(amountUsdc, pricing.finalLimit)
        val signedOrder = orderSigningService.createAndSignOrder(
            privateKey = privateKey,
            makerAddress = makerAddress,
            tokenId = tokenId,
            side = "BUY",
            price = pricing.finalLimit.toPlainString(),
            size = size,
            signatureType = signatureType
        )
        return FakOrderAttempt(
            orderRequest = NewOrderRequest(order = signedOrder, owner = owner, orderType = "FAK"),
            limitPrice = pricing.finalLimit,
            metrics = eval.metrics,
            pricing = pricing,
            orderbook = refreshedOrderbook,
            orderbookRefreshed = true
        )
    }

    /**
     * SCALP_FLIP 有界 re-quote 单次重试（WS3）：FAK 零成交后重拉盘口 + 复检 SCALP 进场闸 + 按 EV 安全上沿重定价 + 重签。
     * 与 [buildRetryFakOrderAttempt] 并列（SCALP 不走概率闸/EV 否决，故独立）；闸口不过/越窗/越期则放弃重试。
     * FAK 成交即止无挂单，重试不会重复成交。
     */
    private suspend fun buildScalpRetryFakOrderAttempt(
        strategy: CryptoTailStrategy,
        clobApi: PolymarketClobApi,
        tokenId: String,
        amountUsdc: BigDecimal,
        periodStartUnix: Long,
        outcomeIndex: Int,
        triggerPrice: BigDecimal,
        bestAsk: BigDecimal?,
        privateKey: String,
        makerAddress: String,
        owner: String,
        signatureType: Int,
        settleAtUnix: Long
    ): FakOrderAttempt? {
        val nowSeconds = System.currentTimeMillis() / 1000
        if (nowSeconds >= settleAtUnix) return null
        // re-quote 同样 WS 主 / REST 兜底：被 kill 后用 ms 级最新 WS 帧重定价，抢回秒级盘口上跳；WS 不够新才走 REST
        val retryWsPick = pickLiveWsOrderbook(strategy, tokenId)
        val retryRestSkipped = retryWsPick.snapshot != null
        val retryFallbackReason = if (retryRestSkipped) null else retryWsPick.reason
        val refreshedOrderbook = retryWsPick.snapshot
            ?: orderbookSnapshotFetcher.fetch(clobApi, tokenId)
            ?: return null
        val eval = evaluateScalpEntryGates(
            strategy, periodStartUnix, outcomeIndex,
            refreshedOrderbook.bestBid ?: triggerPrice, refreshedOrderbook.bestAsk ?: bestAsk, refreshedOrderbook
        )
        if (!eval.pass) {
            recordBarrierDecision(strategy, periodStartUnix, outcomeIndex, eval, tokenId, stage = "REQUOTE")
            return null
        }
        val limit = computeEntryLimitPrice(
            strategy, periodStartUnix, outcomeIndex, null,
            triggerPrice, refreshedOrderbook.bestBid ?: triggerPrice, refreshedOrderbook.bestAsk ?: bestAsk
        )
        val size = computeSize(amountUsdc, limit)
        val signedOrder = orderSigningService.createAndSignOrder(
            privateKey = privateKey,
            makerAddress = makerAddress,
            tokenId = tokenId,
            side = "BUY",
            price = limit.toPlainString(),
            size = size,
            signatureType = signatureType
        )
        return FakOrderAttempt(
            orderRequest = NewOrderRequest(order = signedOrder, owner = owner, orderType = "FAK"),
            limitPrice = limit,
            metrics = null,
            pricing = null,
            orderbook = refreshedOrderbook,
            orderbookRefreshed = !retryRestSkipped,
            restSkipped = retryRestSkipped,
            restFallbackReason = retryFallbackReason
        )
    }

    /**
     * 二元市场分数 Kelly 下注额（USDC，未做下限钳制，调用方负责钳到 [MIN_ORDER_USDC, bankroll]）。
     *
     * 收益结构：胜则每份 (1−c) 利润，负则每份 c 损失（c=有效成本，含费）。最优 Kelly 比例：
     *   f* = pWin − (1−pWin)·c/(1−c)
     * 实际投入 = bankroll × kellyFraction × clamp(f*, 0, 1)。分数 Kelly（默认 ¼）抑制估计误差与尾部风险。
     * c∉(0,1) 视为无效赔率，返回 0（交由下限/EV 闸处理）。
     */
    private fun computeKellyAmount(
        pWin: BigDecimal,
        effectiveCost: BigDecimal,
        bankroll: BigDecimal,
        kellyFraction: BigDecimal
    ): BigDecimal {
        if (bankroll <= BigDecimal.ZERO || kellyFraction <= BigDecimal.ZERO) return BigDecimal.ZERO
        val c = effectiveCost
        if (c <= BigDecimal.ZERO || c >= BigDecimal.ONE) return BigDecimal.ZERO
        val oneMinusC = BigDecimal.ONE.subtract(c)
        val fStar = pWin.subtract(
            BigDecimal.ONE.subtract(pWin).multiply(c).divide(oneMinusC, 18, RoundingMode.HALF_UP)
        )
        val fClamped = fStar.max(BigDecimal.ZERO).min(BigDecimal.ONE)
        return bankroll.multiply(kellyFraction).multiply(fClamped)
    }

    private suspend fun placeOrderForTrigger(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        marketTitle: String?,
        tokenIds: List<String>,
        outcomeIndex: Int,
        triggerPrice: BigDecimal,
        bestAsk: BigDecimal?,
        amountOverrideUsdc: BigDecimal? = null,
        metrics: BarrierMetrics? = null,
        scaling: CryptoTailCalibrationService.ScalingDecision? = null,
        preRefreshOrderbook: OrderbookQualitySnapshot? = null
    ) {
        val entryStartMs = System.currentTimeMillis()
        val cid = "${strategy.id}-$periodStartUnix"
        val ctx = getOrInvalidatePeriodContext(strategy, periodStartUnix)

        if (ctx != null) {
            // TRIGGER 仅在确实走快路径时记录；ctx==null 落到下方慢路径，由慢路径自行记 TRIGGER，避免重复
            wsDiag.event(
                "TRIGGER",
                "cid" to cid,
                "mode" to strategy.mode,
                "outcome" to outcomeIndex,
                "path" to "fast",
                "triggerPrice" to triggerPrice.toPlainString(),
                "bestAsk" to (bestAsk?.toPlainString() ?: "")
            )
            val balanceSnapshot = entryGuardService.loadEntryBalanceSnapshot(strategy.accountId)
            var spendableBalanceForRatio = BigDecimal.ZERO
            // 优先级：放量闸 probe（安全优先） > 分数 Kelly（仅 BARRIER 模式适用） > 原 amountMode
            // V53 注：Kelly 仅对 BARRIER 二元收益结构有效；BRACKET 阶梯收益非二元，公式不适用，强制走原 amountMode。
            val useKelly = amountOverrideUsdc == null &&
                    strategy.mode == TradingMode.BARRIER_HOLD &&
                    strategy.kellyEnabled &&
                    metrics != null
            var kellyBankroll = BigDecimal.ZERO
            var amountUsdc = when {
                // 放量闸钳制：直接用小额覆盖（仍受 MIN_ORDER_USDC 下限保护）
                amountOverrideUsdc != null -> amountOverrideUsdc
                useKelly -> {
                    // bankroll：RATIO=可用余额，FIXED=amountValue（视为本金/上限）
                    val bankroll = if (strategy.amountMode.uppercase() == "RATIO") {
                        spendableBalanceForRatio = balanceSnapshot.spendable
                        balanceSnapshot.spendable
                    } else {
                        strategy.amountValue
                    }
                    kellyBankroll = bankroll
                    // 下注 = bankroll × kellyFraction × clamp(f*,0,1)，上限钳到 bankroll
                    computeKellyAmount(metrics!!.pWin, metrics.effectiveCost, bankroll, strategy.kellyFraction).min(bankroll)
                }
                strategy.amountMode.uppercase() == "RATIO" -> {
                    spendableBalanceForRatio = balanceSnapshot.spendable
                    balanceSnapshot.spendable.multiply(strategy.amountValue).divide(BigDecimal("100"), 18, RoundingMode.DOWN)
                }

                else -> strategy.amountValue
            }
            if (amountUsdc < MIN_ORDER_USDC) {
                val amountMode = strategy.amountMode.uppercase()
                if (useKelly && kellyBankroll >= MIN_ORDER_USDC) {
                    // Kelly 下限钳制：本金够最小下单额则补到 MIN（f*≤0 等极小情形）
                    amountUsdc = MIN_ORDER_USDC
                } else if (amountMode == "RATIO" && spendableBalanceForRatio >= MIN_ORDER_USDC) {
                    amountUsdc = MIN_ORDER_USDC
                } else {
                    saveTriggerRecord(
                        strategy,
                        periodStartUnix,
                        marketTitle,
                        outcomeIndex,
                        triggerPrice,
                        amountUsdc,
                        null,
                        "fail",
                        "投入金额不足"
                    )
                    return
                }
            }
            if (amountUsdc > balanceSnapshot.spendable) {
                saveTriggerRecord(
                    strategy,
                    periodStartUnix,
                    marketTitle,
                    outcomeIndex,
                    triggerPrice,
                    amountUsdc,
                    null,
                    "fail",
                    entryGuardService.insufficientBalanceReason(amountUsdc, balanceSnapshot)
                )
                return
            }

            // Strong Gap Boost：主闸全通过、金额已定后，按高置信放大下注（默认 shadow 不改实盘）。
            // 仅对 BARRIER/BRACKET（有 metrics）且非放量闸 probe 小额覆盖场景生效；只改 amount，不改方向/限价/风控。
            if (strategy.enableStrongGapBoost && metrics != null && amountOverrideUsdc == null) {
                val boost = boostService.evaluate(
                    strategy = strategy,
                    pWin = metrics.pWin,
                    safeRatio = metrics.safeRatio,
                    baseAmount = amountUsdc,
                    spendable = balanceSnapshot.spendable,
                    usedKelly = useKelly
                )
                recordBoostDecision(strategy, periodStartUnix, outcomeIndex, boost)
                if (boost.applied) {
                    amountUsdc = boost.effectiveAmount
                }
            }

            val tokenId = tokenIds.getOrNull(outcomeIndex) ?: run {
                saveTriggerRecord(
                    strategy,
                    periodStartUnix,
                    marketTitle,
                    outcomeIndex,
                    triggerPrice,
                    amountUsdc,
                    null,
                    "fail",
                    "tokenIds 越界"
                )
                return
            }

            // 障碍模式 MAKER：GTC + postOnly 挂单进场（@bestBid+offset，postOnly 兜底保证 maker），生命周期由对账服务管理
            // 注：阶梯模式 BRACKET_DYNAMIC 强制 FAK 进场，避免"边买边判断卖出"竞态，因此不进入 maker 分支
            if (strategy.mode == TradingMode.BARRIER_HOLD && strategy.entryOrderType.uppercase() == "MAKER") {
                recordOrderSubmitted(strategy, periodStartUnix, outcomeIndex, metrics, triggerPrice, bestAsk, scaling, amountOverrideUsdc, tokenId = tokenId)
                val makerPrice = computeMakerPrice(strategy, triggerPrice, bestAsk)
                val makerSize = computeSize(amountUsdc, makerPrice)
                val makerSignedOrder = orderSigningService.createAndSignOrder(
                    privateKey = ctx.decryptedPrivateKey,
                    makerAddress = ctx.account.proxyAddress,
                    tokenId = tokenId,
                    side = "BUY",
                    price = makerPrice.toPlainString(),
                    size = makerSize,
                    signatureType = ctx.signatureType
                )
                val makerRequest = NewOrderRequest(
                    order = makerSignedOrder,
                    owner = ctx.account.apiKey!!,
                    orderType = "GTC",
                    postOnly = true
                )
                submitMakerOrderAndSaveRecord(
                    ctx.clobApi,
                    strategy,
                    periodStartUnix,
                    marketTitle,
                    outcomeIndex,
                    makerPrice,
                    amountUsdc,
                    makerRequest,
                    tokenId,
                    triggerType = "AUTO"
                )
                return
            }

            // 根据模式确定下单价格（FAK 吃单）
            // LEGACY 仍走极简进场（不做发单前 REST 复核）。SCALP_FLIP 与概率/尾盘模式一致：发单前刷新 REST 盘口并复检进场闸，
            // 杜绝"门槛判定→实际成交"1~3s 内盘口塌陷导致跌破价格下限的接飞刀成交（根因：进场判定与执行用了两个时点的盘口）。
            // WS3（WS 主 / REST 兜底）：SCALP 在执行点实时取最新 WS 帧（quoteAgeMs<=scalpWsFreshnessSkipRestMs）跳过 REST 重拉，
            // 用这帧最新报价复检进场闸 + 定价，削减 REST 往返延迟、消除双源漂移；WS 不够新/缺 ask/深度门陈旧则走 REST 兜底，
            // REST 也缺失则 ORDERBOOK_STALE 禁止入场，零成交后由有界 re-quote 重拉。
            val wsPick = pickLiveWsOrderbook(strategy, tokenId)
            val liveWsOrderbook = wsPick.snapshot
            val restSkipped = liveWsOrderbook != null
            val restFallbackReason = if (restSkipped) null else wsPick.reason
            val refreshedOrderbook = if (strategy.mode != TradingMode.LEGACY_SPREAD) {
                liveWsOrderbook ?: orderbookSnapshotFetcher.fetch(ctx.clobApi, tokenId) ?: run {
                    recordBarrierDecision(
                        strategy,
                        periodStartUnix,
                        outcomeIndex,
                        BarrierEval(false, "ORDERBOOK_STALE", "发单前 REST 订单簿快照缺失，禁止入场", orderbookPayload(null).toJson())
                    )
                    return
                }
            } else {
                null
            }
            val preSubmit = resolvePreSubmitEval(strategy, periodStartUnix, outcomeIndex, triggerPrice, bestAsk, preRefreshOrderbook, refreshedOrderbook, tokenId)
            if (preSubmit is PreSubmitOutcome.Abort) return
            val preSubmitEval = (preSubmit as PreSubmitOutcome.Proceed).metricsEval
            // orderbookRefreshed 仅在真做了发单前 REST 重拉时为 true；WS3 跳过 REST(复用够新 WS 快照)时为 false，
            // 由 restSkipped 单独标记，避免把"复用 WS 快照"误标成"REST 重拉"（纯诊断字段，无逻辑分支依赖）。
            val orderbookRefreshed = refreshedOrderbook != null && !restSkipped
            val effectiveBestBid = refreshedOrderbook?.bestBid ?: triggerPrice
            val effectiveBestAsk = refreshedOrderbook?.bestAsk ?: bestAsk
            val pickNow = System.currentTimeMillis()
            wsDiag.event(
                "ORDERBOOK_PICK",
                "cid" to cid,
                "tk" to wsDiag.shortToken(tokenId),
                "src" to (if (restSkipped) "WS_LIVE" else if (refreshedOrderbook != null) "REST" else "NONE"),
                "reason" to (restFallbackReason ?: ""),
                "acceptPath" to wsPick.acceptPath,
                "feedAgeMs" to (wsPick.feedAgeMs ?: ""),
                "bestBid" to effectiveBestBid.toPlainString(),
                "bestAsk" to (effectiveBestAsk?.toPlainString() ?: ""),
                "bidDepth" to (refreshedOrderbook?.bidDepthUsd?.toPlainString() ?: ""),
                "askDepth" to (refreshedOrderbook?.askDepthUsd?.toPlainString() ?: ""),
                "quoteAge" to (refreshedOrderbook?.quoteAgeMs(pickNow) ?: ""),
                "askAge" to (refreshedOrderbook?.askAgeMs(pickNow) ?: ""),
                "depthAge" to (refreshedOrderbook?.depthAgeMs(pickNow) ?: ""),
                "dtMs" to (pickNow - entryStartMs)
            )
            val effectiveMetrics = preSubmitEval?.metrics ?: metrics
            val pricing = if (strategy.mode != TradingMode.LEGACY_SPREAD && effectiveMetrics != null) {
                computeFakPricing(strategy, effectiveBestBid, effectiveBestAsk, effectiveMetrics.pWin)
            } else {
                null
            }
            if (pricing != null && !pricing.canSubmit) {
                recordEvSafeLimitRejected(strategy, periodStartUnix, outcomeIndex, effectiveMetrics, pricing, orderbookRefreshed, tokenId)
                return
            }
            val price = computeEntryLimitPrice(strategy, periodStartUnix, outcomeIndex, pricing, triggerPrice, effectiveBestBid, effectiveBestAsk)
            wsDiag.event(
                "PRICE",
                "cid" to cid,
                "finalLimit" to price.toPlainString(),
                "evSafeLimit" to (pricing?.evSafeLimit?.toPlainString() ?: ""),
                "pWin" to (effectiveMetrics?.pWin?.toPlainString() ?: ""),
                "bestAsk" to (effectiveBestAsk?.toPlainString() ?: ""),
                "dtMs" to (System.currentTimeMillis() - entryStartMs)
            )
            if (!validateFinalLimitInvariant(strategy, periodStartUnix, outcomeIndex, effectiveMetrics, pricing, price, orderbookRefreshed, tokenId)) {
                return
            }
            if (strategy.mode == TradingMode.BARRIER_HOLD) {
                recordOrderSubmitted(strategy, periodStartUnix, outcomeIndex, effectiveMetrics, effectiveBestBid, effectiveBestAsk, scaling, amountOverrideUsdc, pricing, orderbookRefreshed, preRefreshOrderbook, refreshedOrderbook, restSkipped, tokenId)
            } else if (strategy.mode == TradingMode.BRACKET_DYNAMIC) {
                recordBracketOrderSubmitted(strategy, periodStartUnix, outcomeIndex, effectiveMetrics, effectiveBestBid, effectiveBestAsk, pricing, orderbookRefreshed, preRefreshOrderbook, refreshedOrderbook, restSkipped, tokenId)
            } else if (strategy.mode == TradingMode.SCALP_FLIP) {
                recordScalpOrderSubmitted(strategy, periodStartUnix, outcomeIndex, effectiveBestBid, effectiveBestAsk, price, pricing, orderbookRefreshed, preRefreshOrderbook, refreshedOrderbook, restSkipped, tokenId)
            }
            val priceStr = price.toPlainString()
            val size = computeSize(amountUsdc, price)
            val signedOrder = orderSigningService.createAndSignOrder(
                privateKey = ctx.decryptedPrivateKey,
                makerAddress = ctx.account.proxyAddress,
                tokenId = tokenId,
                side = "BUY",
                price = priceStr,
                size = size,
                signatureType = ctx.signatureType
            )
            val orderRequest = NewOrderRequest(
                order = signedOrder,
                owner = ctx.account.apiKey!!,
                orderType = "FAK"
            )
            val retryMetrics = effectiveMetrics
            val settleAtUnix = periodStartUnix + strategy.intervalSeconds
            val retryBuilder: (suspend () -> FakOrderAttempt?)? = when {
                strategy.mode == TradingMode.SCALP_FLIP && strategy.scalpEntryRequoteMax > 0 -> {
                    {
                        buildScalpRetryFakOrderAttempt(
                            strategy = strategy,
                            clobApi = ctx.clobApi,
                            tokenId = tokenId,
                            amountUsdc = amountUsdc,
                            periodStartUnix = periodStartUnix,
                            outcomeIndex = outcomeIndex,
                            triggerPrice = triggerPrice,
                            bestAsk = bestAsk,
                            privateKey = ctx.decryptedPrivateKey,
                            makerAddress = ctx.account.proxyAddress,
                            owner = ctx.account.apiKey!!,
                            signatureType = ctx.signatureType,
                            settleAtUnix = settleAtUnix
                        )
                    }
                }
                strategy.mode != TradingMode.LEGACY_SPREAD && retryMetrics != null -> {
                    {
                        buildRetryFakOrderAttempt(
                            strategy = strategy,
                            clobApi = ctx.clobApi,
                            tokenId = tokenId,
                            amountUsdc = amountUsdc,
                            periodStartUnix = periodStartUnix,
                            outcomeIndex = outcomeIndex,
                            privateKey = ctx.decryptedPrivateKey,
                            makerAddress = ctx.account.proxyAddress,
                            owner = ctx.account.apiKey!!,
                            signatureType = ctx.signatureType,
                            settleAtUnix = settleAtUnix
                        )
                    }
                }
                else -> null
            }
            submitOrderAndSaveRecord(
                ctx.clobApi,
                strategy,
                periodStartUnix,
                marketTitle,
                outcomeIndex,
                triggerPrice,
                amountUsdc,
                orderRequest,
                triggerType = "AUTO",
                tokenId = tokenId,
                finalLimitPrice = price,
                retryOrderBuilder = retryBuilder,
                metrics = effectiveMetrics,
                orderResultContext = OrderResultContext(preRefreshOrderbook, refreshedOrderbook, pricing, orderbookRefreshed = orderbookRefreshed, restSkipped = restSkipped, restFallbackReason = restFallbackReason),
                maxRetries = if (strategy.mode == TradingMode.SCALP_FLIP) strategy.scalpEntryRequoteMax else 1,
                entryStartMs = entryStartMs
            )
            return
        }

        placeOrderForTriggerSlowPath(strategy, periodStartUnix, marketTitle, tokenIds, outcomeIndex, triggerPrice, bestAsk, metrics, preRefreshOrderbook)
    }

    private suspend fun submitOrderAndSaveRecord(
        clobApi: PolymarketClobApi,
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        marketTitle: String?,
        outcomeIndex: Int,
        triggerPrice: BigDecimal,
        amountUsdc: BigDecimal,
        orderRequest: NewOrderRequest,
        triggerType: String = "AUTO",
        tokenId: String? = null,
        finalLimitPrice: BigDecimal? = null,
        retryOrderBuilder: (suspend () -> FakOrderAttempt?)? = null,
        metrics: BarrierMetrics? = null,
        orderResultContext: OrderResultContext = OrderResultContext(),
        maxRetries: Int = 1,
        entryStartMs: Long? = null
    ) {
        val cid = "${strategy.id}-$periodStartUnix"
        wsDiag.event(
            "SUBMIT",
            "cid" to cid,
            "tk" to (tokenId?.let { wsDiag.shortToken(it) } ?: ""),
            "limit" to (finalLimitPrice?.toPlainString() ?: ""),
            "orderType" to orderRequest.orderType,
            "maxRetries" to maxRetries,
            "dtMs" to (if (entryStartMs != null) System.currentTimeMillis() - entryStartMs else "")
        )
        val firstSubmitStarted = System.currentTimeMillis()
        var result = submitFakAttempt(clobApi, orderRequest)
        var activeContext = orderResultContext.copy(submitLatencyMs = System.currentTimeMillis() - firstSubmitStarted)
        var usedLimitPrice = finalLimitPrice
        var usedMetrics = metrics
        var retryCount = 0
        // 有界 re-quote：零成交(可重试)时重拉盘口+复检闸+重定价+重签提交，最多 maxRetries 次。
        // 默认 maxRetries=1 与旧单次重试行为一致；SCALP 传 scalpEntryRequoteMax 放开多次以捕捉秒级盘口上跳。
        // FAK 成交即止无挂单，builder 返回 null（越期/闸不过）即停，绝不会重复成交。
        while (result.retryable && retryOrderBuilder != null && retryCount < maxRetries) {
            val retryAttempt = retryOrderBuilder() ?: break
            retryCount++
            usedLimitPrice = retryAttempt.limitPrice
            usedMetrics = retryAttempt.metrics ?: usedMetrics
            val retryStarted = System.currentTimeMillis()
            result = submitFakAttempt(clobApi, retryAttempt.orderRequest)
            activeContext = OrderResultContext(
                preRefreshOrderbook = orderResultContext.preRefreshOrderbook,
                preSubmitOrderbook = retryAttempt.orderbook,
                pricing = retryAttempt.pricing,
                submitLatencyMs = System.currentTimeMillis() - retryStarted,
                orderbookRefreshed = retryAttempt.orderbookRefreshed,
                restSkipped = retryAttempt.restSkipped,
                restFallbackReason = retryAttempt.restFallbackReason
            )
        }
        val postFailBestAsk = if (result.status != "success" && tokenId != null) fetchBestAsk(clobApi, tokenId) else null
        activeContext = activeContext.copy(postFailBestAsk = postFailBestAsk)
        val filledSize = result.filledSize
        val filledAmount = result.filledAmount
        val realFill = filledSize != null && filledAmount != null &&
                filledSize > BigDecimal.ZERO && filledAmount > BigDecimal.ZERO
        // FAK 成交立即记 'success'，sumPendingEntryAmountByAccountId 查不到；登记账户级短时预留，
        // 防止同账户另一策略在链上余额 API 滞后期间误判资金可用而超额下单。
        if (realFill) {
            entryGuardService.reserveRecentFill(strategy.accountId, filledAmount!!)
        }
        val isManagedFilled = realFill && strategy.mode != TradingMode.LEGACY_SPREAD && strategy.enableExitManager
        val entryFillPrice = avgFillPrice(filledSize, filledAmount)
        saveTriggerRecord(
            strategy,
            periodStartUnix,
            marketTitle,
            outcomeIndex,
            triggerPrice,
            amountUsdc,
            result.orderId,
            result.status,
            result.failReason,
            triggerType = triggerType,
            orderType = orderRequest.orderType,
            tokenId = tokenId,
            filledSize = result.filledSize,
            filledAmount = result.filledAmount,
            remainingSize = if (isManagedFilled) filledSize else null,
            exitStatus = if (isManagedFilled) ExitStatus.OPEN.name else ExitStatus.NONE.name,
            finalLimitPrice = usedLimitPrice,
            retryCount = retryCount,
            entryFillPrice = entryFillPrice,
            entryModelSide = usedMetrics?.side,
            entryPWin = usedMetrics?.pWin,
            entrySafeRatio = usedMetrics?.safeRatio,
            entryGap = usedMetrics?.gap,
            entryRemainingSeconds = usedMetrics?.remaining?.toInt(),
            peakBid = triggerPrice,
            orderResultContext = activeContext
        )
        wsDiag.event(
            "RESULT",
            "cid" to cid,
            "status" to result.status,
            "filled" to (filledSize?.toPlainString() ?: ""),
            "retry" to retryCount,
            "src" to (if (activeContext.restSkipped) "WS_LIVE" else "REST"),
            "fbReason" to (activeContext.restFallbackReason ?: ""),
            "submitMs" to (activeContext.submitLatencyMs ?: ""),
            "totalMs" to (if (entryStartMs != null) System.currentTimeMillis() - entryStartMs else ""),
            "reason" to (result.failReason ?: "")
        )
        when (result.status) {
            "success" -> logger.info("加密价差策略下单成交: strategyId=${strategy.id}, periodStartUnix=$periodStartUnix, outcomeIndex=$outcomeIndex, orderId=${result.orderId}, filledSize=${result.filledSize?.toPlainString()}, filledAmount=${result.filledAmount?.toPlainString()}, retryCount=$retryCount, triggerType=$triggerType, mode=${strategy.mode.name}, exitStatus=${if (isManagedFilled) "OPEN" else "NONE"}")
            "unfilled" -> logger.warn("加密价差策略下单零成交(unfilled): strategyId=${strategy.id}, periodStartUnix=$periodStartUnix, orderId=${result.orderId}, retryCount=$retryCount, reason=${result.failReason}")
            else -> logger.error("加密价差策略下单失败: strategyId=${strategy.id}, periodStartUnix=$periodStartUnix, retryCount=$retryCount, reason=${result.failReason}")
        }
    }

    private suspend fun submitFakAttempt(clobApi: PolymarketClobApi, orderRequest: NewOrderRequest): FakSubmitResult {
        return try {
            val response = clobApi.createOrder(orderRequest)
            if (response.isSuccessful && response.body() != null) {
                submitResultFromBody(response.body()!!)
            } else {
                val errorBody = response.errorBody()?.string().orEmpty()
                val reason = errorBody.ifEmpty { "请求失败" }
                FakSubmitResult("fail", null, null, null, reason, isRetryableFakMiss(reason))
            }
        } catch (e: Exception) {
            val reason = e.message ?: e.toString()
            logger.error("加密价差策略下单异常: ${e.message}", e)
            FakSubmitResult("fail", null, null, null, reason, isRetryableFakMiss(reason))
        }
    }

    private fun submitResultFromBody(body: NewOrderResponse): FakSubmitResult {
        if (body.success && body.orderId != null) {
            val filledSize = body.takingAmount?.toSafeBigDecimal() ?: BigDecimal.ZERO
            val filledAmount = body.makingAmount?.toSafeBigDecimal() ?: BigDecimal.ZERO
            val realFill = filledSize > BigDecimal.ZERO && filledAmount > BigDecimal.ZERO
            return if (realFill) {
                FakSubmitResult("success", body.orderId, filledSize, filledAmount, null, false)
            } else {
                val reason = "FAK未成交(零成交/无对手盘) status=${body.status ?: ""}"
                FakSubmitResult("unfilled", body.orderId, null, null, reason, true)
            }
        }
        val reason = body.getErrorMessage()
        return FakSubmitResult("fail", body.orderId, null, null, reason, isRetryableFakMiss(reason))
    }

    /**
     * 计算 maker 挂单限价：bestBid + makerPriceOffset（可负），封顶 maxEntryPrice，并尽量不越过 bestAsk 以保持 maker。
     * postOnly=true 为最终兜底（越价会被交易所拒单），此处仅尽量定价为 maker。结果向下取整到 4 位，落在 (0,1)。
     */
    private fun computeMakerPrice(strategy: CryptoTailStrategy, bestBid: BigDecimal, bestAsk: BigDecimal?): BigDecimal {
        var p = bestBid.add(strategy.makerPriceOffset)
        if (p <= BigDecimal.ZERO) p = bestBid
        p = p.min(strategy.maxEntryPrice)
        if (bestAsk != null && p >= bestAsk) {
            // 越过 bestAsk 会成 taker，回退为平 bestBid（仍封顶 maxEntryPrice）
            p = bestBid.min(strategy.maxEntryPrice)
        }
        return p.setScale(4, RoundingMode.DOWN).max(BigDecimal("0.0001")).min(strategy.maxEntryPrice)
    }

    /**
     * 提交 maker（GTC+postOnly）挂单并落库：成功受理即记为 pending（可能含即时部分成交），最终成交由对账服务判定；
     * postOnly 被拒或下单失败记为 fail。
     */
    private suspend fun submitMakerOrderAndSaveRecord(
        clobApi: PolymarketClobApi,
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        marketTitle: String?,
        outcomeIndex: Int,
        makerPrice: BigDecimal,
        amountUsdc: BigDecimal,
        orderRequest: NewOrderRequest,
        tokenId: String,
        triggerType: String = "AUTO"
    ) {
        var failReason: String? = null
        try {
            val response = clobApi.createOrder(orderRequest)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                if (body.success && body.orderId != null) {
                    val immediateSize = body.takingAmount?.toSafeBigDecimal() ?: BigDecimal.ZERO
                    val immediateAmount = body.makingAmount?.toSafeBigDecimal() ?: BigDecimal.ZERO
                    val hasPartial = immediateSize > BigDecimal.ZERO && immediateAmount > BigDecimal.ZERO
                    saveTriggerRecord(
                        strategy,
                        periodStartUnix,
                        marketTitle,
                        outcomeIndex,
                        makerPrice,
                        amountUsdc,
                        body.orderId,
                        "pending",
                        null,
                        triggerType = triggerType,
                        filledSize = if (hasPartial) immediateSize else null,
                        filledAmount = if (hasPartial) immediateAmount else null,
                        orderType = "GTC_POST_ONLY",
                        tokenId = tokenId
                    )
                    logger.info("加密价差策略maker挂单已受理(pending): strategyId=${strategy.id}, periodStartUnix=$periodStartUnix, outcomeIndex=$outcomeIndex, orderId=${body.orderId}, price=${makerPrice.toPlainString()}, immediateFill=$hasPartial")
                    return
                }
                failReason = body.getErrorMessage()
            } else {
                val errorBody = response.errorBody()?.string().orEmpty()
                failReason = errorBody.ifEmpty { "请求失败" }
            }
        } catch (e: Exception) {
            failReason = e.message ?: e.toString()
            logger.error("加密价差策略maker挂单异常: strategyId=${strategy.id}, periodStartUnix=$periodStartUnix", e)
        }
        saveTriggerRecord(
            strategy,
            periodStartUnix,
            marketTitle,
            outcomeIndex,
            makerPrice,
            amountUsdc,
            null,
            "fail",
            failReason,
            triggerType = triggerType,
            orderType = "GTC_POST_ONLY",
            tokenId = tokenId
        )
        logger.error("加密价差策略maker挂单失败: strategyId=${strategy.id}, periodStartUnix=$periodStartUnix, reason=$failReason")
    }

    /** 无预置上下文时的完整流程：固定价格 0.99，账户/解密/费率/签名在触发时执行 */
    private suspend fun placeOrderForTriggerSlowPath(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        marketTitle: String?,
        tokenIds: List<String>,
        outcomeIndex: Int,
        triggerPrice: BigDecimal,
        bestAsk: BigDecimal?,
        metrics: BarrierMetrics? = null,
        preRefreshOrderbook: OrderbookQualitySnapshot? = null
    ) {
        val entryStartMs = System.currentTimeMillis()
        val cid = "${strategy.id}-$periodStartUnix"
        wsDiag.event(
            "TRIGGER",
            "cid" to cid,
            "mode" to strategy.mode,
            "outcome" to outcomeIndex,
            "path" to "slow",
            "triggerPrice" to triggerPrice.toPlainString(),
            "bestAsk" to (bestAsk?.toPlainString() ?: "")
        )
        val account = accountRepository.findById(strategy.accountId).orElse(null) ?: run {
            logger.warn("账户不存在: accountId=${strategy.accountId}")
            saveTriggerRecord(
                strategy,
                periodStartUnix,
                marketTitle,
                outcomeIndex,
                triggerPrice,
                BigDecimal.ZERO,
                null,
                "fail",
                "账户不存在"
            )
            return
        }
        if (account.apiKey == null || account.apiSecret == null || account.apiPassphrase == null) {
            logger.warn("账户未配置 API 凭证: accountId=${account.id}")
            saveTriggerRecord(
                strategy,
                periodStartUnix,
                marketTitle,
                outcomeIndex,
                triggerPrice,
                BigDecimal.ZERO,
                null,
                "fail",
                "账户未配置API凭证"
            )
            return
        }

        val accountId = account.id ?: strategy.accountId
        val balanceSnapshot = entryGuardService.loadEntryBalanceSnapshot(accountId)
        var amountUsdc = when (strategy.amountMode.uppercase()) {
            "RATIO" -> balanceSnapshot.spendable.multiply(strategy.amountValue).divide(BigDecimal("100"), 18, RoundingMode.DOWN)
            else -> strategy.amountValue
        }
        if (amountUsdc < MIN_ORDER_USDC) {
            val amountMode = strategy.amountMode.uppercase()
            if (amountMode == "RATIO" && balanceSnapshot.spendable >= MIN_ORDER_USDC) {
                amountUsdc = MIN_ORDER_USDC
            } else {
                saveTriggerRecord(
                    strategy,
                    periodStartUnix,
                    marketTitle,
                    outcomeIndex,
                    triggerPrice,
                    amountUsdc,
                    null,
                    "fail",
                    "投入金额不足"
                )
                return
            }
        }
        if (amountUsdc > balanceSnapshot.spendable) {
            saveTriggerRecord(
                strategy,
                periodStartUnix,
                marketTitle,
                outcomeIndex,
                triggerPrice,
                amountUsdc,
                null,
                "fail",
                entryGuardService.insufficientBalanceReason(amountUsdc, balanceSnapshot)
            )
            return
        }

        val tokenId = tokenIds.getOrNull(outcomeIndex) ?: run {
            saveTriggerRecord(
                strategy,
                periodStartUnix,
                marketTitle,
                outcomeIndex,
                triggerPrice,
                amountUsdc,
                null,
                "fail",
                "tokenIds 越界"
            )
            return
        }

        val apiSecret = try {
            account.apiSecret.let { cryptoUtils.decrypt(it) }
        } catch (e: Exception) {
            ""
        }
        val apiPassphrase = try {
            account.apiPassphrase.let { cryptoUtils.decrypt(it) }
        } catch (e: Exception) {
            ""
        }
        val clobApi = retrofitFactory.createClobApi(account.apiKey, apiSecret, apiPassphrase, account.walletAddress)
        val signatureType = orderSigningService.getSignatureTypeForWalletType(account.walletType)

        val wsPick = pickLiveWsOrderbook(strategy, tokenId)
        val liveWsOrderbook = wsPick.snapshot
        val restSkipped = liveWsOrderbook != null
        val restFallbackReason = if (restSkipped) null else wsPick.reason
        val refreshedOrderbook = if (strategy.mode != TradingMode.LEGACY_SPREAD) {
            liveWsOrderbook ?: orderbookSnapshotFetcher.fetch(clobApi, tokenId) ?: run {
                recordBarrierDecision(
                    strategy,
                    periodStartUnix,
                    outcomeIndex,
                    BarrierEval(false, "ORDERBOOK_STALE", "发单前 REST 订单簿快照缺失，禁止入场", orderbookPayload(null).toJson())
                )
                return
            }
        } else {
            null
        }
        val preSubmit = resolvePreSubmitEval(strategy, periodStartUnix, outcomeIndex, triggerPrice, bestAsk, preRefreshOrderbook, refreshedOrderbook, tokenId)
        if (preSubmit is PreSubmitOutcome.Abort) return
        val preSubmitEval = (preSubmit as PreSubmitOutcome.Proceed).metricsEval
        // orderbookRefreshed 仅在真做了发单前 REST 重拉时为 true；WS3 跳过 REST(复用够新 WS 快照)时为 false，由 restSkipped 单独标记。
        val orderbookRefreshed = refreshedOrderbook != null && !restSkipped
        val effectiveBestBid = refreshedOrderbook?.bestBid ?: triggerPrice
        val effectiveBestAsk = refreshedOrderbook?.bestAsk ?: bestAsk
        val pickNow = System.currentTimeMillis()
        wsDiag.event(
            "ORDERBOOK_PICK",
            "cid" to cid,
            "tk" to wsDiag.shortToken(tokenId),
            "path" to "slow",
            "src" to (if (restSkipped) "WS_LIVE" else if (refreshedOrderbook != null) "REST" else "NONE"),
            "reason" to (restFallbackReason ?: ""),
            "bestBid" to effectiveBestBid.toPlainString(),
            "bestAsk" to (effectiveBestAsk?.toPlainString() ?: ""),
            "bidDepth" to (refreshedOrderbook?.bidDepthUsd?.toPlainString() ?: ""),
            "askDepth" to (refreshedOrderbook?.askDepthUsd?.toPlainString() ?: ""),
            "dtMs" to (pickNow - entryStartMs)
        )
        val effectiveMetrics = preSubmitEval?.metrics ?: metrics
        val pricing = if (strategy.mode != TradingMode.LEGACY_SPREAD && effectiveMetrics != null) {
            computeFakPricing(strategy, effectiveBestBid, effectiveBestAsk, effectiveMetrics.pWin)
        } else {
            null
        }
        if (pricing != null && !pricing.canSubmit) {
            recordEvSafeLimitRejected(strategy, periodStartUnix, outcomeIndex, effectiveMetrics, pricing, orderbookRefreshed, tokenId)
            return
        }
        val price = computeEntryLimitPrice(strategy, periodStartUnix, outcomeIndex, pricing, triggerPrice, effectiveBestBid, effectiveBestAsk)
        wsDiag.event(
            "PRICE",
            "cid" to cid,
            "path" to "slow",
            "finalLimit" to price.toPlainString(),
            "evSafeLimit" to (pricing?.evSafeLimit?.toPlainString() ?: ""),
            "pWin" to (effectiveMetrics?.pWin?.toPlainString() ?: ""),
            "bestAsk" to (effectiveBestAsk?.toPlainString() ?: ""),
            "dtMs" to (System.currentTimeMillis() - entryStartMs)
        )
        if (!validateFinalLimitInvariant(strategy, periodStartUnix, outcomeIndex, effectiveMetrics, pricing, price, orderbookRefreshed, tokenId)) {
            return
        }
        if (strategy.mode == TradingMode.BARRIER_HOLD) {
            recordOrderSubmitted(strategy, periodStartUnix, outcomeIndex, effectiveMetrics, effectiveBestBid, effectiveBestAsk, null, null, pricing, orderbookRefreshed, preRefreshOrderbook, refreshedOrderbook, restSkipped, tokenId)
        } else if (strategy.mode == TradingMode.BRACKET_DYNAMIC) {
            recordBracketOrderSubmitted(strategy, periodStartUnix, outcomeIndex, effectiveMetrics, effectiveBestBid, effectiveBestAsk, pricing, orderbookRefreshed, preRefreshOrderbook, refreshedOrderbook, restSkipped, tokenId)
        } else if (strategy.mode == TradingMode.SCALP_FLIP) {
            recordScalpOrderSubmitted(strategy, periodStartUnix, outcomeIndex, effectiveBestBid, effectiveBestAsk, price, pricing, orderbookRefreshed, preRefreshOrderbook, refreshedOrderbook, restSkipped, tokenId)
        }
        // 根据模式确定下单价格
        val priceStr = price.toPlainString()
        val size = computeSize(amountUsdc, price)

        val decryptedKey = try {
            cryptoUtils.decrypt(account.privateKey) ?: ""
        } catch (e: Exception) {
            logger.error("解密私钥失败: accountId=${account.id}", e)
            saveTriggerRecord(
                strategy,
                periodStartUnix,
                marketTitle,
                outcomeIndex,
                triggerPrice,
                amountUsdc,
                null,
                "fail",
                "解密私钥失败"
            )
            return
        }

        val signedOrder = orderSigningService.createAndSignOrder(
            privateKey = decryptedKey,
            makerAddress = account.proxyAddress,
            tokenId = tokenId,
            side = "BUY",
            price = priceStr,
            size = size,
            signatureType = signatureType
        )
        val orderRequest = NewOrderRequest(
            order = signedOrder,
            owner = account.apiKey!!,
            orderType = "FAK"
        )
        val retryMetrics = effectiveMetrics
        val settleAtUnix = periodStartUnix + strategy.intervalSeconds
        val retryBuilder: (suspend () -> FakOrderAttempt?)? = when {
            strategy.mode == TradingMode.SCALP_FLIP && strategy.scalpEntryRequoteMax > 0 -> {
                {
                    buildScalpRetryFakOrderAttempt(
                        strategy = strategy,
                        clobApi = clobApi,
                        tokenId = tokenId,
                        amountUsdc = amountUsdc,
                        periodStartUnix = periodStartUnix,
                        outcomeIndex = outcomeIndex,
                        triggerPrice = triggerPrice,
                        bestAsk = bestAsk,
                        privateKey = decryptedKey,
                        makerAddress = account.proxyAddress,
                        owner = account.apiKey!!,
                        signatureType = signatureType,
                        settleAtUnix = settleAtUnix
                    )
                }
            }
            strategy.mode != TradingMode.LEGACY_SPREAD && retryMetrics != null -> {
                {
                    buildRetryFakOrderAttempt(
                        strategy = strategy,
                        clobApi = clobApi,
                        tokenId = tokenId,
                        amountUsdc = amountUsdc,
                        periodStartUnix = periodStartUnix,
                        outcomeIndex = outcomeIndex,
                        privateKey = decryptedKey,
                        makerAddress = account.proxyAddress,
                        owner = account.apiKey!!,
                        signatureType = signatureType,
                        settleAtUnix = settleAtUnix
                    )
                }
            }
            else -> null
        }
        submitOrderAndSaveRecord(
            clobApi,
            strategy,
            periodStartUnix,
            marketTitle,
            outcomeIndex,
            triggerPrice,
            amountUsdc,
            orderRequest,
            finalLimitPrice = price,
            retryOrderBuilder = retryBuilder,
            metrics = effectiveMetrics,
            orderResultContext = OrderResultContext(preRefreshOrderbook, refreshedOrderbook, pricing, orderbookRefreshed = orderbookRefreshed, restSkipped = restSkipped, restFallbackReason = restFallbackReason),
            maxRetries = if (strategy.mode == TradingMode.SCALP_FLIP) strategy.scalpEntryRequoteMax else 1,
            entryStartMs = entryStartMs
        )
    }

    private suspend fun fetchEventBySlug(slug: String): Result<GammaEventBySlugResponse> {
        return try {
            val gammaApi = retrofitFactory.createGammaApi()
            val response = gammaApi.getEventBySlug(slug)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val msg = if (response.code() == 404) "404" else "code=${response.code()}"
                Result.failure(Exception(msg))
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

    private fun saveTriggerRecord(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        marketTitle: String?,
        outcomeIndex: Int,
        triggerPrice: BigDecimal,
        amountUsdc: BigDecimal,
        orderId: String?,
        status: String,
        failReason: String?,
        triggerType: String = "AUTO",
        filledSize: BigDecimal? = null,
        filledAmount: BigDecimal? = null,
        orderType: String? = null,
        tokenId: String? = null,
        /** 启用退出管理的概率模式入场成交后由调用方传入：=filledSize；其他模式保持 null */
        remainingSize: BigDecimal? = null,
        /** 启用退出管理的概率模式入场成交后由调用方传入：=OPEN；其他模式保持默认 NONE */
        exitStatus: String = ExitStatus.NONE.name,
        finalLimitPrice: BigDecimal? = null,
        retryCount: Int = 0,
        entryFillPrice: BigDecimal? = null,
        entryModelSide: Int? = null,
        entryPWin: BigDecimal? = null,
        entrySafeRatio: BigDecimal? = null,
        entryGap: BigDecimal? = null,
        entryRemainingSeconds: Int? = null,
        peakBid: BigDecimal? = null,
        orderResultContext: OrderResultContext = OrderResultContext()
    ): CryptoTailStrategyTrigger {
        // TAIL_DIFF：从决策快照取出 score/tier/exit_preset_json/diff_sigma 等字段；其他模式留 NULL
        val tailDiffSnap = if (strategy.mode == TradingMode.TAIL_DIFF) {
            tailDiffEntrySnapshotCache.getIfPresent(tailDiffSnapshotKey(strategy.id!!, periodStartUnix, outcomeIndex))
        } else null
        // SCALP_FLIP：从独立快照取出入场冻结的退出预设 + 入场信号（写入 trigger，供退出引擎/监控读取）
        val scalpSnap = if (strategy.mode == TradingMode.SCALP_FLIP) {
            scalpEntrySnapshotCache.getIfPresent(tailDiffSnapshotKey(strategy.id!!, periodStartUnix, outcomeIndex))
        } else null
        val scalpExitPresetJson = scalpSnap?.exitPresetJson?.takeIf { it.isNotBlank() }
        // SCALP 入场信号优先用调用方传入（恒为 null），回退到冻结快照，使方向翻转止损生效 + 填充监控列
        val effEntryModelSide = entryModelSide ?: scalpSnap?.modelSide
        val effEntryPWin = entryPWin ?: scalpSnap?.pWin
        val effEntrySafeRatio = entrySafeRatio ?: scalpSnap?.safeRatio
        val effEntryGap = entryGap ?: scalpSnap?.gap
        val effEntryRemainingSeconds = entryRemainingSeconds ?: scalpSnap?.remainingSeconds
        val record = CryptoTailStrategyTrigger(
            strategyId = strategy.id!!,
            periodStartUnix = periodStartUnix,
            marketTitle = marketTitle,
            outcomeIndex = outcomeIndex,
            triggerPrice = triggerPrice,
            amountUsdc = amountUsdc,
            filledSize = filledSize,
            filledAmount = filledAmount,
            orderType = orderType,
            tokenId = tokenId,
            orderId = orderId,
            status = status,
            failReason = failReason,
            triggerType = triggerType,
            // 冗余冻结当时模式：避免后续修改策略表 mode 字段污染历史触发的语义
            mode = strategy.mode,
            remainingSize = remainingSize,
            exitStatus = exitStatus,
            entryFillPrice = entryFillPrice,
            entryModelSide = effEntryModelSide,
            entryPWin = effEntryPWin,
            entrySafeRatio = effEntrySafeRatio,
            entryGap = effEntryGap,
            entryRemainingSeconds = effEntryRemainingSeconds,
            peakBid = peakBid,
            score = tailDiffSnap?.score,
            tier = tailDiffSnap?.tier?.label,
            exitPresetJson = tailDiffSnap?.exitPresetJson?.takeIf { it.isNotBlank() } ?: scalpExitPresetJson,
            rawDiff = tailDiffSnap?.rawDiff,
            diffPct = tailDiffSnap?.diffPct,
            // SCALP 用冻结的 safeRatio 作为 diff_sigma（监控/复盘一致；其退出预设 maxDiffRetracePct=0，不改变退出行为）
            diffSigma = tailDiffSnap?.diffSigma ?: scalpSnap?.safeRatio,
            modelProbSource = tailDiffSnap?.modelProbSource
        )
        val saved = triggerRepository.save(record)
        // 落库成功后清理 TAIL_DIFF 决策快照（每周期入场一次即用即清）
        if (tailDiffSnap != null) {
            tailDiffEntrySnapshotCache.invalidate(tailDiffSnapshotKey(strategy.id!!, periodStartUnix, outcomeIndex))
        }
        // 落库成功后清理 SCALP_FLIP 入场冻结快照
        if (scalpSnap != null) {
            scalpEntrySnapshotCache.invalidate(tailDiffSnapshotKey(strategy.id!!, periodStartUnix, outcomeIndex))
        }
        // 非旧价差模式（BARRIER/BRACKET）记录下单结果到决策日志（链路终点锚点）
        if (strategy.mode != TradingMode.LEGACY_SPREAD) {
            recordDecisionOncePerPeriod(
                strategy, periodStartUnix, "ORDER_RESULT-$status", outcomeIndex,
                eventType = "ORDER_RESULT", gateName = null, passed = status == "success",
                reason = failReason ?: if (status == "success") "下单成功 orderId=$orderId" else null,
                payloadJson = mapOf(
                    "status" to status,
                    "orderId" to (orderId ?: ""),
                    "triggerPrice" to triggerPrice.toPlainString(),
                    "amountUsdc" to amountUsdc.toPlainString(),
                    "filledSize" to (filledSize?.toPlainString() ?: ""),
                    "filledAmount" to (filledAmount?.toPlainString() ?: ""),
                    "avgFillPrice" to (avgFillPrice(filledSize, filledAmount)?.toPlainString() ?: ""),
                    "finalLimitPrice" to (finalLimitPrice?.toPlainString() ?: ""),
                    "retryCount" to retryCount,
                    "orderType" to (orderType ?: ""),
                    "triggerType" to triggerType,
                    "preSubmitBestBid" to (orderResultContext.preSubmitOrderbook?.bestBid?.toPlainString() ?: ""),
                    "preSubmitBestAsk" to (orderResultContext.preSubmitOrderbook?.bestAsk?.toPlainString() ?: ""),
                    "preSubmitAskSize" to (orderResultContext.preSubmitOrderbook?.askSize?.toPlainString() ?: ""),
                    "preRefreshBestBid" to (orderResultContext.preRefreshOrderbook?.bestBid?.toPlainString() ?: ""),
                    "preRefreshBestAsk" to (orderResultContext.preRefreshOrderbook?.bestAsk?.toPlainString() ?: ""),
                    "refreshedBestBid" to (orderResultContext.preSubmitOrderbook?.bestBid?.toPlainString() ?: ""),
                    "refreshedBestAsk" to (orderResultContext.preSubmitOrderbook?.bestAsk?.toPlainString() ?: ""),
                    "refreshedSpread" to (orderResultContext.preSubmitOrderbook?.spread?.toPlainString() ?: ""),
                    "orderbookRefreshed" to orderResultContext.orderbookRefreshed,
                    "restSkipped" to orderResultContext.restSkipped,
                    // orderbookSource=本次发单前盘口取数来源：WS_LIVE=实时 WS 帧(WS 主)、REST=REST 兜底重拉、NONE=未刷新(LEGACY)
                    "orderbookSource" to when {
                        orderResultContext.restSkipped -> "WS_LIVE"
                        orderResultContext.orderbookRefreshed -> "REST"
                        else -> "NONE"
                    },
                    // restFallbackReason=未走 WS 主、回退 REST 的原因(noWsFrame/quoteStale/askStale/depthStale/disabled)，WS 主命中时为空
                    "restFallbackReason" to (orderResultContext.restFallbackReason ?: ""),
                    "orderBookAgeMs" to (orderResultContext.preSubmitOrderbook?.quoteAgeMs() ?: ""),
                    "evSafeMaxPrice" to (orderResultContext.pricing?.evSafeLimit?.toPlainString() ?: ""),
                    "submitLatencyMs" to (orderResultContext.submitLatencyMs ?: ""),
                    "postFailBestAsk" to (orderResultContext.postFailBestAsk?.toPlainString() ?: "")
                ).toJson(),
                triggerId = saved.id,
                tokenId = tokenId
            )
        }
        return saved
    }

    /**
     * 手动下单：用户主动触发下单，不检查任何条件，仅检查当前周期是否已下单
     */
    suspend fun manualOrder(request: CryptoTailManualOrderRequest): Result<CryptoTailManualOrderResponse> {
        return try {
            val strategy = strategyRepository.findById(request.strategyId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("策略不存在"))

            val outcomeIndex = if (request.direction.uppercase() == "UP") 0 else 1

            if (outcomeIndex < 0 || outcomeIndex >= request.tokenIds.size) {
                return Result.failure(IllegalArgumentException("outcomeIndex 越界"))
            }

            val price = request.price.toSafeBigDecimal()
            if (price <= BigDecimal.ZERO || price > BigDecimal.ONE) {
                return Result.failure(IllegalArgumentException("价格必须在 0~1 之间"))
            }
            val priceRounded = price.setScale(4, RoundingMode.UP)

            val size = request.size.toSafeBigDecimal()
            if (size < BigDecimal.ONE) {
                return Result.failure(IllegalArgumentException("数量不能少于 1"))
            }

            val amountUsdc = priceRounded.multi(size).setScale(2, RoundingMode.HALF_UP)
            if (amountUsdc < BigDecimal.ONE) {
                return Result.failure(IllegalArgumentException("总金额不能少于 \$1"))
            }

            // 手动单也纳入账户级串行：与自动入场共用 accountEntryMutex，避免同账户多策略/手动单并发抢余额；
            // 重复持仓与余额预留必须生效（风控日亏/并发对手动单保持放行，手动属用户主动行为）。
            val accountMutex = getAccountEntryMutex(strategy.accountId)
            val mutex = getTriggerMutex(strategy.id!!, request.periodStartUnix)
            accountMutex.withLock {
              mutex.withLock {
                if (triggerRepository.findByStrategyIdAndPeriodStartUnix(
                        strategy.id!!,
                        request.periodStartUnix
                    ) != null
                ) {
                    return@withLock Result.failure(IllegalArgumentException("当前周期已下单"))
                }

                if (entryGuardService.hasDuplicateMarketPosition(strategy, request.periodStartUnix, outcomeIndex)) {
                    return@withLock Result.failure(IllegalArgumentException("同账户同 market+period+outcome 已有持仓或挂单，禁止重复开仓"))
                }

                val balanceSnapshot = entryGuardService.loadEntryBalanceSnapshot(strategy.accountId)
                if (amountUsdc > balanceSnapshot.spendable) {
                    return@withLock Result.failure(IllegalArgumentException(entryGuardService.insufficientBalanceReason(amountUsdc, balanceSnapshot)))
                }

                var ctx = getOrInvalidatePeriodContext(strategy, request.periodStartUnix)
                if (ctx == null) {
                    ctx = ensurePeriodContext(
                        strategy,
                        request.periodStartUnix,
                        request.tokenIds,
                        request.marketTitle.ifBlank { null }
                    )
                }
                if (ctx != null) {
                    val tokenId = request.tokenIds.getOrNull(outcomeIndex)
                        ?: return@withLock Result.failure(IllegalArgumentException("tokenIds 越界"))

                    val priceStr = priceRounded.toPlainString()
                    val sizeStr = size.toPlainString()

                    val signedOrder = orderSigningService.createAndSignOrder(
                        privateKey = ctx.decryptedPrivateKey,
                        makerAddress = ctx.account.proxyAddress,
                        tokenId = tokenId,
                        side = "BUY",
                        price = priceStr,
                        size = sizeStr,
                        signatureType = ctx.signatureType
                    )

                    val orderRequest = NewOrderRequest(
                        order = signedOrder,
                        owner = ctx.account.apiKey!!,
                        orderType = "FAK"
                    )

                    val orderResult = submitOrderForManualOrder(
                        ctx.clobApi,
                        strategy,
                        request.periodStartUnix,
                        request.marketTitle,
                        outcomeIndex,
                        priceRounded,
                        amountUsdc,
                        orderRequest
                    )

                    orderResult.fold(
                        onSuccess = { orderId ->
                            Result.success(
                                CryptoTailManualOrderResponse(
                                    success = true,
                                    orderId = orderId,
                                    message = "下单成功",
                                    orderDetails = ManualOrderDetails(
                                        strategyId = strategy.id!!,
                                        direction = request.direction,
                                        price = priceStr,
                                        size = sizeStr,
                                        totalAmount = amountUsdc.toPlainString()
                                    )
                                )
                            )
                        },
                        onFailure = { e ->
                            Result.failure(e)
                        }
                    )
                } else {
                    Result.failure(IllegalArgumentException("账户未配置或凭证不足"))
                }
              }
            }
        } catch (e: Exception) {
            logger.error("手动下单异常: strategyId=${request.strategyId}, ${e.message}", e)
            Result.failure(e)
        }
    }

    private suspend fun submitOrderForManualOrder(
        clobApi: PolymarketClobApi,
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        marketTitle: String?,
        outcomeIndex: Int,
        price: BigDecimal,
        amountUsdc: BigDecimal,
        orderRequest: NewOrderRequest
    ): Result<String> {
        return try {
            val response = clobApi.createOrder(orderRequest)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                if (body.success && body.orderId != null) {
                    val filledSize = body.takingAmount?.toSafeBigDecimal() ?: BigDecimal.ZERO
                    val filledAmount = body.makingAmount?.toSafeBigDecimal() ?: BigDecimal.ZERO
                    val realFill = filledSize > BigDecimal.ZERO && filledAmount > BigDecimal.ZERO
                    val finalStatus = if (realFill) "success" else "unfilled"
                    // 概率模式手动下单成交后也进入统一持仓状态机（旧价差模式不接入）
                    val isManagedFilled = realFill && strategy.mode != TradingMode.LEGACY_SPREAD && strategy.enableExitManager
                    val manualEntryFillPrice = avgFillPrice(if (realFill) filledSize else null, if (realFill) filledAmount else null)
                    saveTriggerRecord(
                        strategy,
                        periodStartUnix,
                        marketTitle,
                        outcomeIndex,
                        price,
                        amountUsdc,
                        body.orderId,
                        finalStatus,
                        if (realFill) null else "FAK未成交(零成交/无对手盘) status=${body.status ?: ""}",
                        triggerType = "MANUAL",
                        filledSize = if (realFill) filledSize else null,
                        filledAmount = if (realFill) filledAmount else null,
                        orderType = orderRequest.orderType,
                        remainingSize = if (isManagedFilled) filledSize else null,
                        exitStatus = if (isManagedFilled) ExitStatus.OPEN.name else ExitStatus.NONE.name,
                        entryFillPrice = manualEntryFillPrice,
                        peakBid = price
                    )
                    if (realFill) {
                        // 手动成交同样登记账户级短时预留，防止链上余额滞后期间其他策略超额下单
                        entryGuardService.reserveRecentFill(strategy.accountId, filledAmount)
                        logger.info("手动下单成交: strategyId=${strategy.id}, periodStartUnix=$periodStartUnix, outcomeIndex=$outcomeIndex, orderId=${body.orderId}, filledSize=${filledSize.toPlainString()}, filledAmount=${filledAmount.toPlainString()}, mode=${strategy.mode.name}")
                        Result.success(body.orderId)
                    } else {
                        logger.warn("手动下单被接受但零成交(unfilled): strategyId=${strategy.id}, orderId=${body.orderId}, status=${body.status}")
                        Result.failure(Exception("FAK未成交(零成交/无对手盘)"))
                    }
                } else {
                    Result.failure(Exception(body.errorMsg ?: "unknown"))
                }
            } else {
                val errorBody = response.errorBody()?.string().orEmpty()
                Result.failure(Exception(errorBody.ifEmpty { "请求失败" }))
            }
        } catch (e: Exception) {
            logger.error("手动下单异常: strategyId=${strategy.id}, periodStartUnix=$periodStartUnix", e)
            Result.failure(e)
        }
    }

    /** 账户下单上下文：对账/回退 FAK 时按账户重建，不依赖周期内存上下文，保证重启后仍可对账 */
    private data class AccountOrderCtx(
        val clobApi: PolymarketClobApi,
        val apiKey: String,
        val decryptedPrivateKey: String,
        val proxyAddress: String,
        val signatureType: Int
    )

    /** 按策略账户重建下单上下文（解密私钥/凭证 + L2 CLOB 客户端 + 签名类型）；凭证缺失返回 null */
    private fun buildAccountOrderCtx(strategy: CryptoTailStrategy): AccountOrderCtx? {
        val account = accountRepository.findById(strategy.accountId).orElse(null) ?: return null
        if (account.apiKey == null || account.apiSecret == null || account.apiPassphrase == null) return null
        val decryptedKey = try {
            cryptoUtils.decrypt(account.privateKey) ?: ""
        } catch (e: Exception) {
            logger.error("maker对账解密私钥失败: accountId=${account.id}", e)
            return null
        }
        if (decryptedKey.isBlank()) return null
        val apiSecret = try { cryptoUtils.decrypt(account.apiSecret) ?: "" } catch (e: Exception) { "" }
        val apiPassphrase = try { cryptoUtils.decrypt(account.apiPassphrase) ?: "" } catch (e: Exception) { "" }
        val clobApi = retrofitFactory.createClobApi(account.apiKey, apiSecret, apiPassphrase, account.walletAddress)
        val signatureType = orderSigningService.getSignatureTypeForWalletType(account.walletType)
        return AccountOrderCtx(clobApi, account.apiKey!!, decryptedKey, account.proxyAddress, signatureType)
    }

    /**
     * maker 挂单生命周期对账：扫描所有 status=pending 的触发记录，逐条查单定夺：
     *  - 完全成交 → success（真实成交量）
     *  - 已撤单/过期：有部分成交→success(部分)，否则→unfilled
     *  - 到达撤单时点(距结算 makerCancelBeforeSettleSeconds 秒)仍 LIVE → 撤单；
     *      有部分成交→success(部分)；否则按 makerFallbackTaker 决定 回退FAK吃单 / unfilled
     *  - 仍在窗口内 → 保持 pending（刷新部分成交快照）
     * 由 CryptoTailMakerOrderService 定时驱动。
     */
    suspend fun reconcilePendingMakerOrders() {
        val pendings = triggerRepository.findByStatusAndOrderIdIsNotNullOrderByCreatedAtAsc("pending")
        if (pendings.isEmpty()) return
        for (trigger in pendings) {
            try {
                reconcileOnePendingMaker(trigger)
            } catch (e: Exception) {
                logger.error("maker订单对账异常: triggerId=${trigger.id}, orderId=${trigger.orderId}", e)
            }
        }
    }

    private suspend fun reconcileOnePendingMaker(trigger: CryptoTailStrategyTrigger) {
        val orderId = trigger.orderId ?: return
        val strategy = strategyRepository.findById(trigger.strategyId).orElse(null) ?: return
        val ctx = buildAccountOrderCtx(strategy) ?: run {
            logger.warn("maker对账无法重建账户上下文: triggerId=${trigger.id}, accountId=${strategy.accountId}")
            return
        }
        val nowSec = System.currentTimeMillis() / 1000
        val settleAt = trigger.periodStartUnix + strategy.intervalSeconds
        val deadline = settleAt - strategy.makerCancelBeforeSettleSeconds

        val order = try {
            val resp = ctx.clobApi.getOrder(orderId)
            if (resp.isSuccessful) resp.body() else null
        } catch (e: Exception) {
            logger.warn("maker对账查单失败: triggerId=${trigger.id}, orderId=$orderId, ${e.message}")
            null
        }

        if (order == null) {
            // 查单失败：仅在已过结算时放弃，避免索引延迟导致误判
            if (nowSec >= settleAt) {
                finalizePendingTrigger(trigger, strategy, "unfilled", null, null, "maker订单状态查询失败且已过结算")
            }
            return
        }

        val originalSize = order.originalSize.toSafeBigDecimal()
        val sizeMatched = order.sizeMatched.toSafeBigDecimal()
        val price = order.price.toSafeBigDecimal()
        val st = order.status.uppercase()
        val fullyFilled = sizeMatched > BigDecimal.ZERO && originalSize > BigDecimal.ZERO && sizeMatched >= originalSize

        if (fullyFilled || st == "FILLED" || st == "MATCHED") {
            val fillAmount = sizeMatched.multiply(price).setScale(8, RoundingMode.HALF_UP)
            finalizePendingTrigger(trigger, strategy, "success", sizeMatched, fillAmount, null)
            return
        }
        if (st.contains("CANCEL") || st == "EXPIRED") {
            if (sizeMatched > BigDecimal.ZERO) {
                val fillAmount = sizeMatched.multiply(price).setScale(8, RoundingMode.HALF_UP)
                finalizePendingTrigger(trigger, strategy, "success", sizeMatched, fillAmount, "maker订单已撤/过期，按部分成交结算")
            } else {
                finalizePendingTrigger(trigger, strategy, "unfilled", null, null, "maker订单已撤/过期且零成交")
            }
            return
        }

        // 仍存活（LIVE/DELAYED 等）
        if (nowSec >= deadline) {
            // 到撤单时点：撤单并复查最后成交
            try { ctx.clobApi.cancelOrder(orderId) } catch (e: Exception) {
                logger.warn("maker对账撤单失败: triggerId=${trigger.id}, orderId=$orderId, ${e.message}")
            }
            val afterMatched = try {
                val resp = ctx.clobApi.getOrder(orderId)
                if (resp.isSuccessful) resp.body()?.sizeMatched?.toSafeBigDecimal() ?: sizeMatched else sizeMatched
            } catch (e: Exception) { sizeMatched }
            if (afterMatched > BigDecimal.ZERO) {
                val fillAmount = afterMatched.multiply(price).setScale(8, RoundingMode.HALF_UP)
                finalizePendingTrigger(trigger, strategy, "success", afterMatched, fillAmount, "maker到期撤单，按部分成交结算")
                return
            }
            if (strategy.makerFallbackTaker && trigger.tokenId != null && nowSec < settleAt) {
                fallbackToTaker(strategy, ctx, trigger)
                return
            }
            finalizePendingTrigger(trigger, strategy, "unfilled", null, null, "maker到期未成交，未启用回退或已过结算")
            return
        }

        // 仍在窗口内：保持 pending，刷新部分成交快照
        if (sizeMatched > BigDecimal.ZERO && (trigger.filledSize ?: BigDecimal.ZERO).compareTo(sizeMatched) != 0) {
            val fillAmount = sizeMatched.multiply(price).setScale(8, RoundingMode.HALF_UP)
            triggerRepository.save(trigger.copy(filledSize = sizeMatched, filledAmount = fillAmount))
        }
    }

    /** maker 到期未成交回退为 FAK 吃单（@bestAsk，封顶 maxEntryPrice），就地更新原触发记录 */
    private suspend fun fallbackToTaker(strategy: CryptoTailStrategy, ctx: AccountOrderCtx, trigger: CryptoTailStrategyTrigger) {
        val tokenId = trigger.tokenId
        if (tokenId == null) {
            finalizePendingTrigger(trigger, strategy, "unfilled", null, null, "maker回退FAK失败：缺tokenId")
            return
        }
        val bestAsk = fetchBestAsk(ctx.clobApi, tokenId)
        val price = computeBuyPrice(strategy, trigger.triggerPrice, bestAsk)
        val size = computeSize(trigger.amountUsdc, price)
        try {
            val signedOrder = orderSigningService.createAndSignOrder(
                privateKey = ctx.decryptedPrivateKey,
                makerAddress = ctx.proxyAddress,
                tokenId = tokenId,
                side = "BUY",
                price = price.toPlainString(),
                size = size,
                signatureType = ctx.signatureType
            )
            val req = NewOrderRequest(order = signedOrder, owner = ctx.apiKey, orderType = "FAK")
            val resp = ctx.clobApi.createOrder(req)
            if (resp.isSuccessful && resp.body() != null) {
                val body = resp.body()!!
                if (body.success && body.orderId != null) {
                    val filledSize = body.takingAmount?.toSafeBigDecimal() ?: BigDecimal.ZERO
                    val filledAmount = body.makingAmount?.toSafeBigDecimal() ?: BigDecimal.ZERO
                    val realFill = filledSize > BigDecimal.ZERO && filledAmount > BigDecimal.ZERO
                    if (realFill) {
                        finalizePendingTrigger(trigger, strategy, "success", filledSize, filledAmount, "maker到期回退FAK成交", newOrderId = body.orderId, newOrderType = "FAK")
                    } else {
                        finalizePendingTrigger(trigger, strategy, "unfilled", null, null, "maker回退FAK零成交 status=${body.status ?: ""}", newOrderId = body.orderId, newOrderType = "FAK")
                    }
                    return
                }
                finalizePendingTrigger(trigger, strategy, "unfilled", null, null, "maker回退FAK下单失败:${body.getErrorMessage()}", newOrderType = "FAK")
                return
            }
            val errorBody = resp.errorBody()?.string().orEmpty()
            finalizePendingTrigger(trigger, strategy, "unfilled", null, null, "maker回退FAK请求失败:${errorBody.ifEmpty { "请求失败" }}", newOrderType = "FAK")
        } catch (e: Exception) {
            logger.error("maker回退FAK异常: triggerId=${trigger.id}, ${e.message}", e)
            finalizePendingTrigger(trigger, strategy, "unfilled", null, null, "maker回退FAK异常:${e.message}", newOrderType = "FAK")
        }
    }

    /** 通过 CLOB 订单簿查询某 token 的 bestAsk（最低卖价），失败返回 null */
    private suspend fun fetchBestAsk(clobApi: PolymarketClobApi, tokenId: String): BigDecimal? {
        return orderbookSnapshotFetcher.fetch(clobApi, tokenId)?.bestAsk
    }

    /** 就地更新 pending 触发记录为终态，并记录 ORDER_RESULT 决策日志（障碍模式） */
    private fun finalizePendingTrigger(
        trigger: CryptoTailStrategyTrigger,
        strategy: CryptoTailStrategy,
        status: String,
        filledSize: BigDecimal?,
        filledAmount: BigDecimal?,
        reason: String?,
        newOrderId: String? = null,
        newOrderType: String? = null
    ) {
        val managedFill = status == "success" &&
                strategy.mode != TradingMode.LEGACY_SPREAD &&
                strategy.enableExitManager &&
                (filledSize ?: trigger.filledSize ?: BigDecimal.ZERO) > BigDecimal.ZERO
        val finalFilledSize = filledSize ?: trigger.filledSize
        val finalFilledAmount = filledAmount ?: trigger.filledAmount
        val updated = trigger.copy(
            status = status,
            filledSize = finalFilledSize,
            filledAmount = finalFilledAmount,
            failReason = reason ?: trigger.failReason,
            orderId = newOrderId ?: trigger.orderId,
            orderType = newOrderType ?: trigger.orderType,
            remainingSize = if (managedFill) finalFilledSize else trigger.remainingSize,
            exitStatus = if (managedFill && trigger.exitStatus == ExitStatus.NONE.name) ExitStatus.OPEN.name else trigger.exitStatus,
            entryFillPrice = if (managedFill) avgFillPrice(finalFilledSize, finalFilledAmount) else trigger.entryFillPrice,
            peakBid = if (managedFill) trigger.triggerPrice else trigger.peakBid
        )
        triggerRepository.save(updated)
        if (strategy.mode != TradingMode.LEGACY_SPREAD) {
            recordDecisionOncePerPeriod(
                strategy, trigger.periodStartUnix, "ORDER_RESULT-$status", trigger.outcomeIndex,
                eventType = "ORDER_RESULT", gateName = null, passed = status == "success",
                reason = reason ?: if (status == "success") "maker成交 orderId=${updated.orderId}" else null,
                payloadJson = mapOf(
                    "status" to status,
                    "orderId" to (updated.orderId ?: ""),
                    "filledSize" to (updated.filledSize?.toPlainString() ?: ""),
                    "filledAmount" to (updated.filledAmount?.toPlainString() ?: ""),
                    "orderType" to (updated.orderType ?: ""),
                    "lifecycle" to "MAKER_RECONCILE"
                ).toJson(),
                triggerId = updated.id,
                tokenId = updated.tokenId
            )
        }
        logger.info("maker订单对账定夺: triggerId=${trigger.id}, orderId=${updated.orderId}, status=$status, filledSize=${updated.filledSize?.toPlainString()}, reason=$reason")
    }

    @PreDestroy
    fun destroy() {
        // 清理所有周期上下文缓存，避免敏感信息（明文私钥、API Secret）在内存中保留
        periodContextCache.clear()
        // 清理所有锁，避免内存泄漏
        triggerMutexMap.clear()
        logger.debug("加密价差策略执行服务已清理缓存和锁")
    }
}
