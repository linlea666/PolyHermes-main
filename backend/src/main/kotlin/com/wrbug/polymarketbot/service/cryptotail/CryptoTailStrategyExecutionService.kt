package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.api.GammaEventBySlugResponse
import com.wrbug.polymarketbot.api.NewOrderRequest
import com.wrbug.polymarketbot.api.PolymarketClobApi
import com.wrbug.polymarketbot.dto.CryptoTailManualOrderRequest
import com.wrbug.polymarketbot.dto.CryptoTailManualOrderResponse
import com.wrbug.polymarketbot.dto.ManualOrderDetails
import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.entity.CryptoTailDecisionEvent
import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import com.wrbug.polymarketbot.entity.CryptoTailStrategyTrigger
import com.wrbug.polymarketbot.enums.SpreadMode
import com.wrbug.polymarketbot.enums.SpreadDirection
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CryptoTailStrategyRepository
import com.wrbug.polymarketbot.repository.CryptoTailStrategyTriggerRepository
import com.wrbug.polymarketbot.service.accounts.AccountService
import com.wrbug.polymarketbot.service.binance.BinanceKlineAutoSpreadService
import com.wrbug.polymarketbot.service.binance.BinanceKlineService
import com.wrbug.polymarketbot.service.common.PolymarketClobService
import com.wrbug.polymarketbot.service.copytrading.orders.OrderSigningService
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
    private val accountService: AccountService,
    private val retrofitFactory: RetrofitFactory,
    private val clobService: PolymarketClobService,
    private val orderSigningService: OrderSigningService,
    private val cryptoUtils: CryptoUtils,
    private val binanceKlineService: BinanceKlineService,
    private val binanceKlineAutoSpreadService: BinanceKlineAutoSpreadService,
    private val cryptoTailRiskService: CryptoTailRiskService,
    private val decisionRecorder: CryptoTailDecisionRecorder,
    private val periodPriceProvider: PeriodPriceProvider,
    private val calibrationService: CryptoTailCalibrationService
) {

    private val logger = LoggerFactory.getLogger(CryptoTailStrategyExecutionService::class.java)

    /** 按 (strategyId, periodStartUnix) 加锁，避免同一周期被调度器与 WebSocket 等多路并发重复下单 */
    private val triggerMutexMap = ConcurrentHashMap<String, Mutex>()

    /** 过期锁 key 保留时间（秒），超过则清理，防止 map 无界增长 */
    private val triggerMutexExpireSeconds = 3600L

    private fun triggerLockKey(strategyId: Long, periodStartUnix: Long): String = "$strategyId-$periodStartUnix"

    private fun getTriggerMutex(strategyId: Long, periodStartUnix: Long): Mutex {
        cleanExpiredTriggerMutexKeys()
        return triggerMutexMap.getOrPut(triggerLockKey(strategyId, periodStartUnix)) { Mutex() }
    }

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
        bestAsk: BigDecimal? = null
    ) {
        if (outcomeIndex < 0 || outcomeIndex >= tokenIds.size) return
        // 旧模式：价格区间过滤；障碍模式跳过此过滤，改由 barrierMinMarketProb 等闸把关
        if (!strategy.barrierEnabled && (bestBid < strategy.minPrice || bestBid > strategy.maxPrice)) return

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
                val oc = binanceKlineService.getCurrentOpenClose(
                    strategy.marketSlugPrefix,
                    strategy.intervalSeconds,
                    periodStartUnix
                )
                val openPrice = oc?.first?.toPlainString() ?: "-"
                val closePrice = oc?.second?.toPlainString() ?: "-"
                val strategyName = strategy.name?.takeIf { it.isNotBlank() } ?: "加密价差策略-${strategy.marketSlugPrefix}"
                val direction = if (outcomeIndex == 0) "Up" else "Down"
                val modeStr = if (strategy.barrierEnabled) "障碍模式" else if (strategy.spreadDirection == SpreadDirection.MAX) "最大价差" else "最小价差"
                logger.info(
                    "加密价差策略首次满足条件: strategyName=$strategyName, strategyId=${strategy.id}, " +
                            "openPrice=$openPrice, closePrice=$closePrice, marketPrice=${bestBid.toPlainString()}, " +
                            "direction=$direction, outcomeIndex=$outcomeIndex, mode=$modeStr"
                )
            }

            if (strategy.barrierEnabled) {
                val eval = evaluateBarrierGates(strategy, periodStartUnix, outcomeIndex, bestBid, bestAsk)
                recordBarrierDecision(strategy, periodStartUnix, outcomeIndex, eval)
                if (!eval.pass) return@withLock
                // 风控闸（日亏/并发敞口）
                val risk = cryptoTailRiskService.checkRiskGate(strategy)
                if (!risk.passed) {
                    recordDecisionOncePerPeriod(
                        strategy.id!!, periodStartUnix, "GATE_FAILED-${risk.gateName}", outcomeIndex,
                        eventType = "GATE_FAILED", gateName = risk.gateName, passed = false, reason = risk.reason,
                        payloadJson = eval.payloadJson, triggerId = null
                    )
                    return@withLock
                }
                // 放量闸：校准未达标期间钳制为小额(probeAmountUsdc)，达标后才放大到 amountValue
                val scaling = calibrationService.evaluateScalingGate(strategy)
                val amountOverride = if (scaling.useProbe) strategy.probeAmountUsdc else null
                recordOrderSubmitted(strategy, periodStartUnix, outcomeIndex, eval.metrics, bestBid, bestAsk, scaling, amountOverride)
                ensurePeriodContext(strategy, periodStartUnix, tokenIds, marketTitle)
                placeOrderForTrigger(strategy, periodStartUnix, marketTitle, tokenIds, outcomeIndex, bestBid, bestAsk, amountOverride)
            } else {
                if (!passSpreadCheck(strategy, periodStartUnix, outcomeIndex)) return@withLock
                ensurePeriodContext(strategy, periodStartUnix, tokenIds, marketTitle)
                placeOrderForTrigger(strategy, periodStartUnix, marketTitle, tokenIds, outcomeIndex, bestBid, null)
            }
        }
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
        val edge: BigDecimal
    )

    /**
     * 障碍（终值概率）模式闸：方向一致性 / pWin / 市场概率 / 扣费 EV。
     * 时间窗已由 WS onBestBid 复用现有 window 把关，此处不再重复校验。
     */
    private fun evaluateBarrierGates(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        outcomeIndex: Int,
        bestBid: BigDecimal,
        bestAsk: BigDecimal?
    ): BarrierEval {
        // 障碍模式价源必须与 Polymarket 结算源(Chainlink)一致；缺凭证/feedID 时安全跳过，绝不回退币安
        if (!periodPriceProvider.isAvailable(strategy.marketSlugPrefix)) {
            return BarrierEval(false, "PRICE_SOURCE", "Chainlink价源未配置(缺API凭证或该币种feedID)", null)
        }
        val oc = periodPriceProvider.getCurrentOpenClose(strategy.marketSlugPrefix, strategy.intervalSeconds, periodStartUnix)
            ?: return BarrierEval(false, "PRICE_SOURCE", "Chainlink价未就绪(期初/当前价取价失败)", null)
        val (openP, closeP) = oc
        val gap = closeP.subtract(openP)
        val nowSeconds = System.currentTimeMillis() / 1000
        val remaining = (periodStartUnix + strategy.intervalSeconds - nowSeconds).toDouble()
        val sigma = periodPriceProvider.getSigmaPerSqrtS(
            strategy.marketSlugPrefix, strategy.intervalSeconds, periodStartUnix, outcomeIndex, strategy.sigmaScale,
            strategy.sigmaMethod, strategy.ewmaLambda
        ) ?: return BarrierEval(false, "PWIN", "σ基准不可用(Chainlink历史样本不足)", null)
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
            edge = edge
        )
        val snapshot = mapOf(
            "gap" to gap.toPlainString(),
            "open" to openP.toPlainString(),
            "close" to closeP.toPlainString(),
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
            "barrierMinMarketProb" to strategy.barrierMinMarketProb.toPlainString()
        ).toJson()

        if (r.side != outcomeIndex) {
            return BarrierEval(false, "DIRECTION", "模型方向=${r.side}与当前outcome=$outcomeIndex不一致", snapshot, metrics)
        }
        if (r.pWin < strategy.entryProb) {
            return BarrierEval(false, "PWIN", "pWin=${r.pWin.toPlainString()}<entryProb=${strategy.entryProb.toPlainString()}", snapshot, metrics)
        }
        if (strategy.barrierMinMarketProb > BigDecimal.ZERO && bestBid < strategy.barrierMinMarketProb) {
            return BarrierEval(false, "MARKET_PROB", "市场概率=${bestBid.toPlainString()}<下限=${strategy.barrierMinMarketProb.toPlainString()}", snapshot, metrics)
        }
        if (edge < strategy.entryEdge) {
            return BarrierEval(false, "EV", "扣费edge=${edge.toPlainString()}<entryEdge=${strategy.entryEdge.toPlainString()}", snapshot, metrics)
        }
        return BarrierEval(true, "ALL", "通过全部障碍闸", snapshot, metrics)
    }

    /** 记录障碍闸结果（每周期每结果去重，避免每个 tick 刷库） */
    private fun recordBarrierDecision(strategy: CryptoTailStrategy, periodStartUnix: Long, outcomeIndex: Int, eval: BarrierEval) {
        if (eval.pass) {
            recordDecisionOncePerPeriod(
                strategy.id!!, periodStartUnix, "GATE_PASSED-ALL", outcomeIndex,
                eventType = "GATE_PASSED", gateName = "ALL", passed = true, reason = eval.reason,
                payloadJson = eval.payloadJson, triggerId = null
            )
        } else {
            recordDecisionOncePerPeriod(
                strategy.id!!, periodStartUnix, "GATE_FAILED-${eval.gateName}", outcomeIndex,
                eventType = "GATE_FAILED", gateName = eval.gateName, passed = false, reason = eval.reason,
                payloadJson = eval.payloadJson, triggerId = null
            )
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
        amountOverrideUsdc: BigDecimal? = null
    ) {
        if (metrics == null) return
        val isMaker = strategy.entryOrderType.uppercase() == "MAKER"
        val targetPrice = if (isMaker) computeMakerPrice(strategy, bestBid, bestAsk) else computeBuyPrice(strategy, bestBid, bestAsk)
        val mid = bestAsk?.let { bestBid.add(it).divide(BigDecimal(2), 8, RoundingMode.HALF_UP) } ?: bestBid
        val payload = mapOf(
            "marketSlug" to strategy.marketSlugPrefix,
            "intervalSeconds" to strategy.intervalSeconds,
            "gap" to metrics.gap.toPlainString(),
            "open" to metrics.open.toPlainString(),
            "close" to metrics.close.toPlainString(),
            "sigmaPerSqrtS" to metrics.sigma.toPlainString(),
            "remainingSeconds" to metrics.remaining.toLong().toString(),
            "pWin" to metrics.pWin.toPlainString(),
            "modelSide" to metrics.side.toString(),
            "safeRatio" to metrics.safeRatio.toPlainString(),
            "bestBid" to bestBid.toPlainString(),
            "bestAsk" to (bestAsk?.toPlainString() ?: ""),
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
        ).toJson()
        val scalingNote = if (scaling != null && scaling.useProbe) " [放量闸:小额 ${strategy.probeAmountUsdc.toPlainString()}]" else ""
        recordDecisionOncePerPeriod(
            strategy.id!!, periodStartUnix, "ORDER_SUBMITTED", outcomeIndex,
            eventType = "ORDER_SUBMITTED", gateName = null, passed = null,
            reason = "已提交下单 目标价=${targetPrice.toPlainString()} 剩余=${metrics.remaining.toLong()}s$scalingNote",
            payloadJson = payload, triggerId = null
        )
    }

    /** 决策日志：同一周期同一 key 只记一次（内存去重，热路径友好），通过解耦 recorder 异步落库/推送 */
    private fun recordDecisionOncePerPeriod(
        strategyId: Long,
        periodStartUnix: Long,
        dedupeKey: String,
        outcomeIndex: Int?,
        eventType: String,
        gateName: String?,
        passed: Boolean?,
        reason: String?,
        payloadJson: String?,
        triggerId: Long?
    ) {
        val key = "$strategyId-$periodStartUnix-$dedupeKey"
        if (decisionLoggedCache.getIfPresent(key) != null) return
        decisionLoggedCache.put(key, true)
        decisionRecorder.record(
            CryptoTailDecisionEvent(
                strategyId = strategyId,
                periodStartUnix = periodStartUnix,
                correlationId = "$strategyId-$periodStartUnix",
                eventType = eventType,
                gateName = gateName,
                passed = passed,
                reason = reason,
                payloadJson = payloadJson,
                outcomeIndex = outcomeIndex,
                triggerId = triggerId
            )
        )
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
     * 计算买入限价：
     * - 障碍模式：有效成本 = bestAsk（缺失则 bestBid+costBuffer），向上取整到 4 位后封顶 maxEntryPrice。
     * - 旧模式：最大价差 = 触发价+0.02；否则固定 0.99（保持原行为）。
     */
    private fun computeBuyPrice(strategy: CryptoTailStrategy, triggerPrice: BigDecimal, bestAsk: BigDecimal?): BigDecimal {
        if (strategy.barrierEnabled) {
            val effectiveCost = bestAsk ?: triggerPrice.add(strategy.costBuffer)
            return effectiveCost.setScale(4, RoundingMode.UP).min(strategy.maxEntryPrice)
        }
        return if (strategy.spreadDirection == SpreadDirection.MAX) {
            triggerPrice.add(BigDecimal(SPREAD_MAX_PRICE_ADJUSTMENT)).setScale(8, RoundingMode.HALF_UP)
        } else {
            BigDecimal(TRIGGER_FIXED_PRICE)
        }
    }

    private suspend fun placeOrderForTrigger(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        marketTitle: String?,
        tokenIds: List<String>,
        outcomeIndex: Int,
        triggerPrice: BigDecimal,
        bestAsk: BigDecimal?,
        amountOverrideUsdc: BigDecimal? = null
    ) {
        val ctx = getOrInvalidatePeriodContext(strategy, periodStartUnix)

        if (ctx != null) {
            var availableBalanceForRatio = BigDecimal.ZERO
            var amountUsdc = when {
                // 放量闸钳制：直接用小额覆盖（仍受 MIN_ORDER_USDC 下限保护）
                amountOverrideUsdc != null -> amountOverrideUsdc
                strategy.amountMode.uppercase() == "RATIO" -> {
                    val balanceResult = accountService.getAccountBalance(ctx.account.id)
                    val availableBalance =
                        balanceResult.getOrNull()?.availableBalance?.toSafeBigDecimal() ?: BigDecimal.ZERO
                    availableBalanceForRatio = availableBalance
                    availableBalance.multiply(strategy.amountValue).divide(BigDecimal("100"), 18, RoundingMode.DOWN)
                }

                else -> strategy.amountValue
            }
            if (amountUsdc < MIN_ORDER_USDC) {
                val amountMode = strategy.amountMode.uppercase()
                if (amountMode == "RATIO" && availableBalanceForRatio >= MIN_ORDER_USDC) {
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
            if (strategy.barrierEnabled && strategy.entryOrderType.uppercase() == "MAKER") {
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
            val price = computeBuyPrice(strategy, triggerPrice, bestAsk)
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
                tokenId = tokenId
            )
            return
        }

        placeOrderForTriggerSlowPath(strategy, periodStartUnix, marketTitle, tokenIds, outcomeIndex, triggerPrice, bestAsk)
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
        tokenId: String? = null
    ) {
        var failReason: String? = null
        try {
            val response = clobApi.createOrder(orderRequest)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                if (body.success && body.orderId != null) {
                    // 以下单响应的真实成交为准：BUY 单 makingAmount=支付USDC, takingAmount=获得份额
                    val filledSize = body.takingAmount?.toSafeBigDecimal() ?: BigDecimal.ZERO
                    val filledAmount = body.makingAmount?.toSafeBigDecimal() ?: BigDecimal.ZERO
                    // FAK 可能被接受但零成交（无对手盘）；零成交不可进结算，标记 unfilled
                    val realFill = filledSize > BigDecimal.ZERO && filledAmount > BigDecimal.ZERO
                    val finalStatus = if (realFill) "success" else "unfilled"
                    saveTriggerRecord(
                        strategy,
                        periodStartUnix,
                        marketTitle,
                        outcomeIndex,
                        triggerPrice,
                        amountUsdc,
                        body.orderId,
                        finalStatus,
                        if (realFill) null else "FAK未成交(零成交/无对手盘) status=${body.status ?: ""}",
                        triggerType = triggerType,
                        filledSize = if (realFill) filledSize else null,
                        filledAmount = if (realFill) filledAmount else null,
                        orderType = orderRequest.orderType,
                        tokenId = tokenId
                    )
                    if (realFill) {
                        logger.info("加密价差策略下单成交: strategyId=${strategy.id}, periodStartUnix=$periodStartUnix, outcomeIndex=$outcomeIndex, orderId=${body.orderId}, filledSize=${filledSize.toPlainString()}, filledAmount=${filledAmount.toPlainString()}, triggerType=$triggerType")
                    } else {
                        logger.warn("加密价差策略下单被接受但零成交(unfilled): strategyId=${strategy.id}, periodStartUnix=$periodStartUnix, orderId=${body.orderId}, status=${body.status}")
                    }
                    return
                }
                failReason = body.errorMsg ?: "unknown"
            } else {
                val errorBody = response.errorBody()?.string().orEmpty()
                failReason = errorBody.ifEmpty { "请求失败" }
            }
        } catch (e: Exception) {
            failReason = e.message ?: e.toString()
            logger.error("加密价差策略下单异常: strategyId=${strategy.id}, periodStartUnix=$periodStartUnix", e)
        }
        saveTriggerRecord(
            strategy,
            periodStartUnix,
            marketTitle,
            outcomeIndex,
            triggerPrice,
            amountUsdc,
            null,
            "fail",
            failReason,
            triggerType = triggerType,
            orderType = orderRequest.orderType,
            tokenId = tokenId
        )
        logger.error("加密价差策略下单失败: strategyId=${strategy.id}, periodStartUnix=$periodStartUnix, reason=$failReason")
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
        bestAsk: BigDecimal?
    ) {
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

        val balanceResult = accountService.getAccountBalance(account.id)
        val availableBalance = balanceResult.getOrNull()?.availableBalance?.toSafeBigDecimal() ?: BigDecimal.ZERO
        var amountUsdc = when (strategy.amountMode.uppercase()) {
            "RATIO" -> availableBalance.multiply(strategy.amountValue).divide(BigDecimal("100"), 18, RoundingMode.DOWN)
            else -> strategy.amountValue
        }
        if (amountUsdc < MIN_ORDER_USDC) {
            val amountMode = strategy.amountMode.uppercase()
            if (amountMode == "RATIO" && availableBalance >= MIN_ORDER_USDC) {
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

        // 根据模式确定下单价格
        val price = computeBuyPrice(strategy, triggerPrice, bestAsk)
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
        submitOrderAndSaveRecord(
            clobApi,
            strategy,
            periodStartUnix,
            marketTitle,
            outcomeIndex,
            triggerPrice,
            amountUsdc,
            orderRequest
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
        tokenId: String? = null
    ): CryptoTailStrategyTrigger {
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
            triggerType = triggerType
        )
        val saved = triggerRepository.save(record)
        // 障碍模式下记录下单结果到决策日志（链路终点锚点）
        if (strategy.barrierEnabled) {
            recordDecisionOncePerPeriod(
                strategy.id!!, periodStartUnix, "ORDER_RESULT-$status", outcomeIndex,
                eventType = "ORDER_RESULT", gateName = null, passed = status == "success",
                reason = failReason ?: if (status == "success") "下单成功 orderId=$orderId" else null,
                payloadJson = mapOf(
                    "status" to status,
                    "orderId" to (orderId ?: ""),
                    "triggerPrice" to triggerPrice.toPlainString(),
                    "amountUsdc" to amountUsdc.toPlainString(),
                    "filledSize" to (filledSize?.toPlainString() ?: ""),
                    "filledAmount" to (filledAmount?.toPlainString() ?: ""),
                    "orderType" to (orderType ?: ""),
                    "triggerType" to triggerType
                ).toJson(),
                triggerId = saved.id
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

            val mutex = getTriggerMutex(strategy.id!!, request.periodStartUnix)
            mutex.withLock {
                if (triggerRepository.findByStrategyIdAndPeriodStartUnix(
                        strategy.id!!,
                        request.periodStartUnix
                    ) != null
                ) {
                    return@withLock Result.failure(IllegalArgumentException("当前周期已下单"))
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
                        orderType = orderRequest.orderType
                    )
                    if (realFill) {
                        logger.info("手动下单成交: strategyId=${strategy.id}, periodStartUnix=$periodStartUnix, outcomeIndex=$outcomeIndex, orderId=${body.orderId}, filledSize=${filledSize.toPlainString()}, filledAmount=${filledAmount.toPlainString()}")
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
        return try {
            val resp = clobApi.getOrderbook(tokenId = tokenId)
            if (!resp.isSuccessful) return null
            val asks = resp.body()?.asks ?: return null
            asks.mapNotNull { it.price.toSafeBigDecimal().takeIf { p -> p > BigDecimal.ZERO } }.minOrNull()
        } catch (e: Exception) {
            logger.warn("查询bestAsk失败: tokenId=$tokenId, ${e.message}")
            null
        }
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
        val updated = trigger.copy(
            status = status,
            filledSize = filledSize ?: trigger.filledSize,
            filledAmount = filledAmount ?: trigger.filledAmount,
            failReason = reason ?: trigger.failReason,
            orderId = newOrderId ?: trigger.orderId,
            orderType = newOrderType ?: trigger.orderType
        )
        triggerRepository.save(updated)
        if (strategy.barrierEnabled) {
            recordDecisionOncePerPeriod(
                strategy.id!!, trigger.periodStartUnix, "ORDER_RESULT-$status", trigger.outcomeIndex,
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
                triggerId = updated.id
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
