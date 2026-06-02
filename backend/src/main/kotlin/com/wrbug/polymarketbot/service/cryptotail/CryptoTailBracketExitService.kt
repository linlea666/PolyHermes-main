package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.api.NewOrderRequest
import com.wrbug.polymarketbot.entity.CryptoTailDecisionEvent
import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import com.wrbug.polymarketbot.entity.CryptoTailStrategyExit
import com.wrbug.polymarketbot.entity.CryptoTailStrategyTrigger
import com.wrbug.polymarketbot.enums.ExitKind
import com.wrbug.polymarketbot.enums.ExitStatus
import com.wrbug.polymarketbot.enums.TradingMode
import com.wrbug.polymarketbot.repository.CryptoTailStrategyExitRepository
import com.wrbug.polymarketbot.repository.CryptoTailStrategyTriggerRepository
import com.wrbug.polymarketbot.service.copytrading.orders.OrderSigningService
import com.wrbug.polymarketbot.util.toJson
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap

/**
 * 加密尾盘概率模式退出决策与执行服务。
 *
 * 设计：
 *  1) 由 [CryptoTailOrderbookWsService.onBestBid] 在每次盘口变化时驱动；
 *     也可由 [CryptoTailExitOrderReconciler] 在订单状态变化后回调（兜底）。
 *  2) 基于实时 pWin（与进场同源 BarrierProbability）+ bestBid + 剩余时间，
 *     按以下优先级决策（与 [decideExit] 实际代码顺序一致）：
 *
 *      STOP（pWin<=stopProb 或 bestBid<=stopPrice，FAK 全清）
 *      > 周期结束兜底（remainingSeconds<=0 → 不动作，由 SettlementService 走 HELD_TO_SETTLE）
 *      > HOLD（pWin>=holdToSettlePwin 且剩余<=holdToSettleSeconds，不动作等链上 condition）
 *      > FORCE（剩余 <= forceExitBeforeSettleSeconds 且未达 hold 条件，FAK 全清）
 *      > TP2（bestBid>=tp2Price 且 pWin<tp2HoldPwin）
 *      > TP1（bestBid>=tp1Price 且 pWin<tp1HoldPwin）
 *      > 否则继续持有
 *
 *  3) 串行化：每个 triggerId 一把 Mutex，避免 WS 高频回调下并发挂单。
 *  4) 任何卖出决策前先撤销该 trigger 的所有 pending exit 单（防超卖竞态）。
 *  5) 决策与下单结果异步推送 [CryptoTailDecisionRecorder] 全链路审计。
 *  6) V53 引入失败抑制窗：同 (triggerId, exitKind) 失败后 60s 内不再尝试，避免拒签/拒单死循环。
 *
 * 复用决策（按"复用决策"原则）：直接复用 [OrderSigningService] / [PolymarketClobApi]，
 * 不抽象 OrderExecutor，下单样板代码与 [CryptoTailStrategyExecutionService.fallbackToTaker] 同构。
 */
@Service
class CryptoTailBracketExitService(
    private val triggerRepository: CryptoTailStrategyTriggerRepository,
    private val exitRepository: CryptoTailStrategyExitRepository,
    private val orderSigningService: OrderSigningService,
    private val accountContextFactory: CryptoTailAccountContextFactory,
    private val periodPriceProvider: PeriodPriceProvider,
    private val decisionRecorder: CryptoTailDecisionRecorder,
    private val wickSignalService: CryptoTailWickSignalService
) {

    private val logger = LoggerFactory.getLogger(CryptoTailBracketExitService::class.java)

    /** 数量小数位数，与 OrderSigningService 的 roundConfig.size 一致 */
    private val sizeDecimalScale = 2

    /** 每个 triggerId 一把 Mutex，避免 WS 多 tick 并发挂卖单导致超卖 */
    private val triggerMutexMap = ConcurrentHashMap<Long, Mutex>()

    /** 决策日志去重：每 (triggerId-periodStartUnix-key) 一次窗口内重复决策仅记一次（避免 WS 高频污染日志） */
    private val decisionLoggedCache: Cache<String, Boolean> = Caffeine.newBuilder()
        .maximumSize(1000)
        .expireAfterWrite(java.time.Duration.ofMinutes(10))
        .build()

    /**
     * V53 (triggerId, exitKind) failed 抑制窗：MAKER/FAK 失败后 60s 内不再尝试同 kind，
     * 避免"挂单被拒/拒签 → 下次 tick 又重挂 → 又拒"死循环（与 hasExitOfKind=true 的成功/挂中守卫互补，
     * 此窗专门处理 failed 状态短期重试问题）。
     */
    private val failBackoffCache: Cache<String, Boolean> = Caffeine.newBuilder()
        .maximumSize(1000)
        .expireAfterWrite(java.time.Duration.ofSeconds(60))
        .build()

    private fun mutexFor(triggerId: Long): Mutex =
        triggerMutexMap.getOrPut(triggerId) { Mutex() }

    /**
     * WS 高层入口：当某 outcome 的 bestBid 变化时，扫描该策略当前周期下持仓中、且 outcomeIndex 匹配的
     * 概率模式 trigger 并评估退出。封装了 trigger 查询，调用方不必关心数据访问。
     *
     * @param strategy 策略（LEGACY_SPREAD 不接入退出管理，内部也会校验）
     * @param periodStartUnix 当前周期起点
     * @param outcomeIndex 此 bestBid 对应的 outcome（持仓方向匹配才评估）
     * @param bestBid 当前 outcome 的最优买价
     * @param nowSeconds 当前 unix 秒
     */
    suspend fun evaluatePeriodOutcome(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        outcomeIndex: Int,
        bestBid: BigDecimal,
        nowSeconds: Long,
        orderbook: OrderbookQualitySnapshot? = null
    ) {
        if (strategy.mode == TradingMode.LEGACY_SPREAD || !strategy.enableExitManager) return
        val strategyId = strategy.id ?: return
        val openTriggers = try {
            triggerRepository.findOpenForBracket(strategyId, periodStartUnix)
        } catch (e: Exception) {
            logger.warn("查询概率模式持仓 trigger 失败 strategyId=$strategyId period=$periodStartUnix, ${e.message}")
            return
        }
        if (openTriggers.isEmpty()) return
        for (t in openTriggers) {
            // 仅评估与 bestBid 同方向的 trigger（卖出价取该 outcome 的 bestBid）
            if (t.outcomeIndex != outcomeIndex) continue
            evaluateAndExit(t, strategy, bestBid, nowSeconds, orderbook)
        }
    }

    /**
     * 主入口：评估退出条件并在必要时挂卖单。
     * 调用方需保证 trigger.exitStatus ∈ {OPEN, PARTIAL_EXIT}、trigger.mode != LEGACY_SPREAD。
     * 失败/异常不向调用方抛出，仅记录日志，避免污染热路径。
     *
     * @param trigger 当前持仓触发记录（必须包含 tokenId / remainingSize）
     * @param strategy 策略（提供阶梯阈值）
     * @param bestBid 当前 outcome 的最优买价（卖出对手盘价）
     * @param nowSeconds 当前 unix 秒
     */
    suspend fun evaluateAndExit(
        trigger: CryptoTailStrategyTrigger,
        strategy: CryptoTailStrategy,
        bestBid: BigDecimal,
        nowSeconds: Long,
        orderbook: OrderbookQualitySnapshot? = null
    ) {
        if (strategy.mode == TradingMode.LEGACY_SPREAD || !strategy.enableExitManager) return
        val triggerId = trigger.id ?: return
        val tokenId = trigger.tokenId
        if (tokenId.isNullOrBlank()) {
            logger.debug("阶梯退出跳过：trigger 无 tokenId triggerId=$triggerId")
            return
        }
        val remaining = trigger.remainingSize ?: return
        if (remaining <= BigDecimal.ZERO) return

        val mutex = mutexFor(triggerId)
        if (!mutex.tryLock()) return  // 已有并发评估在跑，让它处理
        try {
            // 重读 trigger 确保 remainingSize/exitStatus 是最新（Reconciler 可能刚回填）
            val fresh = triggerRepository.findById(triggerId).orElse(null) ?: return
            val freshRemaining = fresh.remainingSize ?: return
            // V53 dust 容差：残余 <= DUST_THRESHOLD 视为已全部退出，不再尝试卖单
            // （配合 Reconciler.syncTriggerAfterExitFinalized 与 SettlementService.BRACKET_DUST_THRESHOLD 三处对齐）
            if (freshRemaining <= DUST_THRESHOLD) return
            if (fresh.exitStatus != ExitStatus.OPEN.name && fresh.exitStatus != ExitStatus.PARTIAL_EXIT.name) return
            val nowMs = System.currentTimeMillis()
            val intervalMs = strategy.exitPollIntervalMs.coerceAtLeast(500).toLong()
            if (fresh.lastExitCheckAt != null && nowMs - fresh.lastExitCheckAt < intervalMs) return

            // 计算 pWin（与进场同源；持仓方向与模型方向不同时取 1-pWin）
            val holding = computeHoldingState(strategy, fresh, nowSeconds)
            val pwinHolding: BigDecimal? = holding?.pWinHolding
            val remainingSeconds = (fresh.periodStartUnix + strategy.intervalSeconds - nowSeconds).toInt()
            val peakBid = (fresh.peakBid ?: bestBid).max(bestBid)
            val checked = triggerRepository.save(
                fresh.copy(
                    peakBid = peakBid,
                    lastExitCheckAt = nowMs
                )
            )

            val decision = decideExit(
                strategy = strategy,
                trigger = checked,
                holding = holding,
                bestBid = bestBid,
                remainingSeconds = remainingSeconds,
                nowMs = nowMs,
                orderbook = orderbook
            )

            recordExitCheck(checked, strategy, holding, bestBid, remainingSeconds, decision, orderbook)

            if (decision.kind == null || decision.ratio <= BigDecimal.ZERO) {
                persistTp1HoldStateIfNeeded(checked, decision)
                return
            }
            persistTp1HoldStateIfNeeded(checked, decision)
            if (!applyExitConfirmation(checked, strategy, decision, nowMs)) return

            // V53 失败抑制窗：同 (triggerId, exitKind) 60s 内 placeExitOrder 失败过则跳过本次评估，避免死循环重试
            if (failBackoffCache.getIfPresent(failBackoffKey(triggerId, decision.kind)) != null) {
                logger.debug("阶梯退出跳过：60s 失败抑制窗内 triggerId=$triggerId kind=${decision.kind}")
                return
            }

            // 已有 pending exit 单：不再挂新单（先让 Reconciler 处理；MAKER 的撤改由 Reconciler 负责）
            if (exitRepository.countByTriggerIdAndStatus(triggerId, "pending") > 0) {
                logger.debug("阶梯退出跳过：已有 pending 出场单 triggerId=$triggerId")
                return
            }

            // 计算本次目标 size（DOWN 取整避免超卖；至少 0.01；不超过 remainingSize）
            val targetSize = freshRemaining.multiply(decision.ratio)
                .setScale(sizeDecimalScale, RoundingMode.DOWN)
                .min(freshRemaining)
            if (targetSize <= BigDecimal.ZERO) {
                logger.warn("阶梯退出跳过：targetSize=0 triggerId=$triggerId remaining=${freshRemaining.toPlainString()} ratio=${decision.ratio}")
                return
            }

            placeExitOrder(
                strategy = strategy,
                trigger = checked,
                kind = decision.kind,
                targetSize = targetSize,
                pwinHolding = pwinHolding,
                bestBid = bestBid,
                remainingSeconds = remainingSeconds,
                reason = decision.reason,
                orderbook = orderbook
            )
        } catch (e: Exception) {
            logger.error("阶梯退出评估异常 triggerId=$triggerId, ${e.message}", e)
        } finally {
            mutex.unlock()
        }
    }

    private data class HoldingState(
        val gap: BigDecimal,
        val modelSide: Int,
        val pWinModel: BigDecimal,
        val pWinHolding: BigDecimal,
        val safeRatio: BigDecimal
    )

    private fun computeHoldingState(
        strategy: CryptoTailStrategy,
        trigger: CryptoTailStrategyTrigger,
        nowSeconds: Long
    ): HoldingState? {
        if (!periodPriceProvider.isAvailable(strategy.marketSlugPrefix)) return null
        val oc = periodPriceProvider.getCurrentOpenClose(
            strategy.marketSlugPrefix, strategy.intervalSeconds, trigger.periodStartUnix
        ) ?: return null
        val (openP, closeP) = oc
        val gap = closeP.subtract(openP)
        val remaining = (trigger.periodStartUnix + strategy.intervalSeconds - nowSeconds).toDouble()
        if (remaining <= 0.0) return null
        val sigma = periodPriceProvider.getSigmaPerSqrtS(
            strategy.marketSlugPrefix, strategy.intervalSeconds, trigger.periodStartUnix,
            trigger.outcomeIndex, strategy.sigmaScale, strategy.sigmaMethod, strategy.ewmaLambda
        ) ?: return null
        val r = BarrierProbability.winProbTerminal(gap, sigma, remaining) ?: return null
        val holdingPWin = if (r.side == trigger.outcomeIndex) r.pWin else BigDecimal.ONE.subtract(r.pWin)
        return HoldingState(
            gap = gap,
            modelSide = r.side,
            pWinModel = r.pWin,
            pWinHolding = holdingPWin,
            safeRatio = r.safeRatio
        )
    }

    /** 退出决策结果 */
    private data class Decision(
        /** null = 继续持有；否则触发对应类型的卖出 */
        val kind: ExitKind?,
        /** 卖出比例（占 remainingSize 的比例），仅 kind != null 时有效 */
        val ratio: BigDecimal,
        val reason: String,
        val tp1HoldStartedAt: Long? = null,
        val clearTp1HoldStartedAt: Boolean = false
    )

    private fun decideExit(
        strategy: CryptoTailStrategy,
        trigger: CryptoTailStrategyTrigger,
        holding: HoldingState?,
        bestBid: BigDecimal,
        remainingSeconds: Int,
        nowMs: Long,
        orderbook: OrderbookQualitySnapshot? = null
    ): Decision {
        if (remainingSeconds <= 0) {
            return Decision(null, BigDecimal.ZERO, "周期已结束，等待结算")
        }
        val entryFillPrice = trigger.entryFillPrice ?: run {
            val fs = trigger.filledSize
            val fa = trigger.filledAmount
            if (fs != null && fa != null && fs > BigDecimal.ZERO) fa.divide(fs, 8, RoundingMode.HALF_UP) else null
        }
        if (entryFillPrice != null) {
            val stopLine = entryFillPrice.multiply(BigDecimal.ONE.subtract(strategy.maxLossPct))
            if (bestBid <= stopLine) {
                return Decision(ExitKind.HARD_STOP, BigDecimal.ONE, "硬止损: bestBid=${bestBid.toPlainString()}<=${stopLine.toPlainString()}")
            }
        }
        if (holding != null && strategy.emergencyExitOnModelFlip && trigger.entryModelSide != null && holding.modelSide != trigger.entryModelSide) {
            return Decision(ExitKind.MODEL_FLIP, BigDecimal.ONE, "模型方向反转: entry=${trigger.entryModelSide}, current=${holding.modelSide}")
        }
        if (holding != null && strategy.emergencyExitOnGapFlip) {
            if (trigger.outcomeIndex == 0 && holding.gap <= BigDecimal.ZERO) {
                return Decision(ExitKind.GAP_FLIP, BigDecimal.ONE, "UP 持仓 gap 反转: gap=${holding.gap.toPlainString()}")
            }
            if (trigger.outcomeIndex == 1 && holding.gap >= BigDecimal.ZERO) {
                return Decision(ExitKind.GAP_FLIP, BigDecimal.ONE, "DOWN 持仓 gap 反转: gap=${holding.gap.toPlainString()}")
            }
        }
        if (holding != null && holding.pWinHolding < strategy.exitPWin && holding.safeRatio < strategy.exitSafeRatio) {
            return Decision(ExitKind.MODEL_INVALID, BigDecimal.ONE, "模型失效: pWin=${holding.pWinHolding.toPlainString()} safeRatio=${holding.safeRatio.toPlainString()}")
        }
        if (strategy.enableTrailingStop && entryFillPrice != null) {
            val peakBid = trigger.peakBid ?: bestBid
            val trailingStart = entryFillPrice.add(strategy.trailingStartDelta)
            val trailingStop = peakBid.subtract(strategy.trailingDrawdown)
            if (peakBid >= trailingStart && bestBid <= trailingStop) {
                val ratio = strategy.trailingSellPct.max(BigDecimal.ZERO).min(BigDecimal.ONE)
                return Decision(
                    ExitKind.TRAILING_STOP,
                    ratio,
                    "移动止损: peakBid=${peakBid.toPlainString()}>=${trailingStart.toPlainString()} 且 bestBid=${bestBid.toPlainString()}<=${trailingStop.toPlainString()}",
                    clearTp1HoldStartedAt = true
                )
            }
        }
        val wick = wickSignalService.evaluate(strategy, trigger.outcomeIndex)
        if (wick.available && wick.reversalScore >= strategy.wickExitScore) {
            return Decision(ExitKind.WICK_REVERSAL, BigDecimal.ONE, "影线反转止损: score=${wick.reversalScore}>=${strategy.wickExitScore}", clearTp1HoldStartedAt = true)
        }
        if (holding != null &&
            remainingSeconds <= strategy.holdToSettleSeconds &&
            holding.pWinHolding >= strategy.holdToSettlePwin &&
            gapSupportsHolding(trigger, holding)
        ) {
            return Decision(
                null,
                BigDecimal.ZERO,
                "HOLD_TO_SETTLE: pWin=${holding.pWinHolding.toPlainString()}>=${strategy.holdToSettlePwin.toPlainString()} 剩余=${remainingSeconds}s<=${strategy.holdToSettleSeconds}s",
                clearTp1HoldStartedAt = true
            )
        }
        if (remainingSeconds <= strategy.forceExitBeforeSettleSeconds) {
            return Decision(
                ExitKind.FORCE,
                BigDecimal.ONE,
                "强制平仓: 剩余=${remainingSeconds}s<=${strategy.forceExitBeforeSettleSeconds}s 且未满足持有到结算条件",
                clearTp1HoldStartedAt = true
            )
        }
        if (bestBid >= strategy.takeProfitBid2
            && !hasExitOfKind(trigger.id!!, ExitKind.TP2)
        ) {
            checkTakeProfitLiquidity(strategy, orderbook, bestBid)?.let { return it }
            val ratio = strategy.takeProfitSellPct2.max(BigDecimal.ZERO).min(BigDecimal.ONE)
            return Decision(
                ExitKind.TP2,
                ratio,
                "止盈2: bestBid=${bestBid.toPlainString()}>=${strategy.takeProfitBid2.toPlainString()}"
            )
        }
        val tp1Line = entryFillPrice?.add(strategy.takeProfitDelta1)
        if (tp1Line != null && bestBid >= tp1Line && !hasExitOfKind(trigger.id!!, ExitKind.TP1) && !hasExitOfKind(trigger.id!!, ExitKind.TP2)) {
            checkTakeProfitLiquidity(strategy, orderbook, bestBid)?.let { return it }
            if (wick.available && wick.continuationScore >= strategy.wickHoldProfitScore && remainingSeconds > strategy.forceExitBeforeSettleSeconds) {
                val holdStart = trigger.tp1HoldStartedAt ?: nowMs
                val heldSeconds = ((nowMs - holdStart) / 1000L).coerceAtLeast(0L)
                val peakBid = trigger.peakBid ?: bestBid
                val drawdown = peakBid.subtract(bestBid).max(BigDecimal.ZERO)
                if (strategy.maxHoldTp1DelaySeconds <= 0 || heldSeconds >= strategy.maxHoldTp1DelaySeconds) {
                    val ratio = strategy.takeProfitSellPct1.max(BigDecimal.ZERO).min(BigDecimal.ONE)
                    return Decision(
                        ExitKind.TP1,
                        ratio,
                        "止盈1: TP1暂缓超时 held=${heldSeconds}s>=${strategy.maxHoldTp1DelaySeconds}s",
                        clearTp1HoldStartedAt = true
                    )
                }
                if (drawdown >= strategy.holdTp1PeakDrawdown) {
                    val ratio = strategy.takeProfitSellPct1.max(BigDecimal.ZERO).min(BigDecimal.ONE)
                    return Decision(
                        ExitKind.TP1,
                        ratio,
                        "止盈1: TP1暂缓期间回撤=${drawdown.toPlainString()}>=${strategy.holdTp1PeakDrawdown.toPlainString()}",
                        clearTp1HoldStartedAt = true
                    )
                }
                return Decision(
                    null,
                    BigDecimal.ZERO,
                    "WICK_HOLD_TP: 顺势评分=${wick.continuationScore}>=${strategy.wickHoldProfitScore}, 暂缓TP1 held=${heldSeconds}s",
                    tp1HoldStartedAt = holdStart
                )
            }
            val ratio = strategy.takeProfitSellPct1.max(BigDecimal.ZERO).min(BigDecimal.ONE)
            return Decision(
                ExitKind.TP1,
                ratio,
                "止盈1: bestBid=${bestBid.toPlainString()}>=entry+delta=${tp1Line.toPlainString()}",
                clearTp1HoldStartedAt = true
            )
        }

        return Decision(null, BigDecimal.ZERO, "继续持有", clearTp1HoldStartedAt = true)
    }

    private fun checkTakeProfitLiquidity(
        strategy: CryptoTailStrategy,
        orderbook: OrderbookQualitySnapshot?,
        bestBid: BigDecimal
    ): Decision? {
        val nowMs = System.currentTimeMillis()
        if (orderbook == null) {
            return Decision(null, BigDecimal.ZERO, "TP流动性等待: 订单簿快照缺失")
        }
        val quoteAge = orderbook.quoteAgeMs(nowMs)
        if (quoteAge > strategy.maxOrderbookAgeMs) {
            return Decision(null, BigDecimal.ZERO, "TP流动性等待: quoteAgeMs=$quoteAge>${strategy.maxOrderbookAgeMs}")
        }
        val depthAge = orderbook.depthAgeMs(nowMs)
        if (orderbook.depthStale || depthAge == null || depthAge > strategy.maxOrderbookAgeMs) {
            return Decision(null, BigDecimal.ZERO, "TP流动性等待: depthAgeMs=${depthAge ?: ""}>${strategy.maxOrderbookAgeMs} 或深度缺失")
        }
        val spread = orderbook.spread
        if (spread != null && spread > strategy.maxExitSpread) {
            return Decision(null, BigDecimal.ZERO, "TP流动性等待: exitSpread=${spread.toPlainString()}>${strategy.maxExitSpread.toPlainString()}")
        }
        val bidDepthUsd = orderbook.bidDepthUsd
        if (bidDepthUsd != null && bidDepthUsd < strategy.minExitBidDepthUsdc) {
            return Decision(null, BigDecimal.ZERO, "TP流动性等待: bidDepthUsd=${bidDepthUsd.toPlainString()}<${strategy.minExitBidDepthUsdc.toPlainString()}")
        }
        if (orderbook.bestBid.compareTo(bestBid) != 0) {
            return Decision(null, BigDecimal.ZERO, "TP流动性等待: 快照bestBid与当前bestBid不一致")
        }
        return null
    }

    private fun gapSupportsHolding(trigger: CryptoTailStrategyTrigger, holding: HoldingState): Boolean {
        return when (trigger.outcomeIndex) {
            0 -> holding.gap > BigDecimal.ZERO
            1 -> holding.gap < BigDecimal.ZERO
            else -> false
        }
    }

    private fun persistTp1HoldStateIfNeeded(trigger: CryptoTailStrategyTrigger, decision: Decision) {
        val triggerId = trigger.id ?: return
        val next = when {
            decision.clearTp1HoldStartedAt -> null
            decision.tp1HoldStartedAt != null -> decision.tp1HoldStartedAt
            else -> trigger.tp1HoldStartedAt
        }
        if (next == trigger.tp1HoldStartedAt) return
        triggerRepository.save(trigger.copy(id = triggerId, tp1HoldStartedAt = next))
    }

    private fun applyExitConfirmation(
        trigger: CryptoTailStrategyTrigger,
        strategy: CryptoTailStrategy,
        decision: Decision,
        nowMs: Long
    ): Boolean {
        val kind = decision.kind ?: return false
        val immediate = kind == ExitKind.MODEL_FLIP || kind == ExitKind.GAP_FLIP || kind == ExitKind.TP1 || kind == ExitKind.TP2 || kind == ExitKind.FORCE
        val nextCount = if (trigger.exitConfirmReason == kind.name) trigger.exitConfirmCount + 1 else 1
        triggerRepository.save(
            trigger.copy(
                exitConfirmReason = kind.name,
                exitConfirmCount = nextCount,
                lastExitAttemptAt = if (immediate || nextCount >= strategy.exitConfirmTicks) nowMs else trigger.lastExitAttemptAt
            )
        )
        return immediate || nextCount >= strategy.exitConfirmTicks
    }

    private fun recordExitCheck(
        trigger: CryptoTailStrategyTrigger,
        strategy: CryptoTailStrategy,
        holding: HoldingState?,
        bestBid: BigDecimal,
        remainingSeconds: Int,
        decision: Decision,
        orderbook: OrderbookQualitySnapshot? = null
    ) {
        val entryFillPrice = trigger.entryFillPrice ?: BigDecimal.ZERO
        val peakBid = trigger.peakBid ?: bestBid
        val drawdown = peakBid.subtract(bestBid).max(BigDecimal.ZERO)
        val remaining = trigger.remainingSize ?: BigDecimal.ZERO
        val realizedIfExit = bestBid.subtract(entryFillPrice).multiply(remaining).setScale(8, RoundingMode.HALF_UP)
        val payload = mapOf(
            "positionId" to (trigger.id?.toString() ?: ""),
            "marketSlug" to strategy.marketSlugPrefix,
            "periodStartUnix" to trigger.periodStartUnix,
            "outcomeIndex" to trigger.outcomeIndex,
            "entryFillPrice" to entryFillPrice.toPlainString(),
            "currentBestBid" to bestBid.toPlainString(),
            "currentBestAsk" to "",
            "entryPWin" to (trigger.entryPWin?.toPlainString() ?: ""),
            "currentPWin" to (holding?.pWinHolding?.toPlainString() ?: ""),
            "entrySafeRatio" to (trigger.entrySafeRatio?.toPlainString() ?: ""),
            "currentSafeRatio" to (holding?.safeRatio?.toPlainString() ?: ""),
            "entryModelSide" to (trigger.entryModelSide ?: ""),
            "currentModelSide" to (holding?.modelSide ?: ""),
            "entryGap" to (trigger.entryGap?.toPlainString() ?: ""),
            "currentGap" to (holding?.gap?.toPlainString() ?: ""),
            "remainingSeconds" to remainingSeconds,
            "peakBid" to peakBid.toPlainString(),
            "drawdownFromPeak" to drawdown.toPlainString(),
            "exitReason" to (decision.kind?.name ?: ""),
            "remainingSize" to remaining.toPlainString(),
            "realizedPnlIfExit" to realizedIfExit.toPlainString(),
            "tp1HoldStartedAt" to (decision.tp1HoldStartedAt ?: trigger.tp1HoldStartedAt ?: ""),
            "tp1HoldCleared" to decision.clearTp1HoldStartedAt,
            "reason" to decision.reason,
            "bidSize" to (orderbook?.bidSize?.toPlainString() ?: ""),
            "bidDepthUsd" to (orderbook?.bidDepthUsd?.toPlainString() ?: ""),
            "spread" to (orderbook?.spread?.toPlainString() ?: ""),
            "quoteAgeMs" to (orderbook?.quoteAgeMs() ?: ""),
            "depthAgeMs" to (orderbook?.depthAgeMs() ?: ""),
            "depthStale" to (orderbook?.depthStale ?: true),
            "expectedExitPrice" to bestBid.toPlainString()
        )
        decisionRecorder.record(
            CryptoTailDecisionEvent(
                strategyId = trigger.strategyId,
                periodStartUnix = trigger.periodStartUnix,
                correlationId = "${trigger.strategyId}-${trigger.periodStartUnix}-exit-${trigger.id}",
                eventType = "EXIT_CHECK",
                gateName = decision.kind?.name,
                passed = decision.kind != null,
                reason = decision.reason,
                payloadJson = payload.toJson(),
                outcomeIndex = trigger.outcomeIndex,
                triggerId = trigger.id
            )
        )
        if (decision.kind != null) {
            decisionRecorder.record(
                CryptoTailDecisionEvent(
                    strategyId = trigger.strategyId,
                    periodStartUnix = trigger.periodStartUnix,
                    correlationId = "${trigger.strategyId}-${trigger.periodStartUnix}-exit-${trigger.id}",
                    eventType = "EXIT_SIGNAL",
                    gateName = decision.kind.name,
                    passed = true,
                    reason = decision.reason,
                    payloadJson = payload.toJson(),
                    outcomeIndex = trigger.outcomeIndex,
                    triggerId = trigger.id
                )
            )
        }
    }

    /**
     * 该 trigger 是否已发出过指定类型的退出单。
     * 计入：success(已成/部分成)、pending(挂单中)、cancelled(已撤,含 MAKER 超时撤)、unfilled。
     * 排除：failed（签名/请求异常，可重试）。
     * 这样可避免"MAKER 撤单 → TP 闸再次满足 → 又挂 MAKER → 又超时撤单"的死循环；
     * 用户撤了之后想重挂可走 manualOrder 入口或重启策略。
     */
    private fun hasExitOfKind(triggerId: Long, kind: ExitKind): Boolean {
        return exitRepository.findByTriggerIdOrderByCreatedAtAsc(triggerId)
            .any { it.exitKind == kind.name && it.status != "failed" }
    }

    /**
     * 计算卖出限价（V53 修正：精度对齐 Polymarket CLOB tickSize=0.01）：
     *  - FAK：用 bestBid - EXIT_FAK_SLIPPAGE(0.02)，向下取整 2 位（不低于 MIN_PRICE=0.01），
     *         滑点保证立即成交（与 AccountService.sellPosition 同款，BUY/SELL 滑点对称）。
     *  - MAKER/GTC：用 bestBid + 0.01 一档（向下取整 2 位、封顶 MAX_PRICE=0.99），
     *         避免直接挂 bestBid 立即被对手 taker 吃掉成 taker（postOnly 会拒绝触发死循环）。
     *         此处 +0.01 是 1 个 tick，让 sell 单稳定停在卖盘队尾，等待价格上行被吃。
     */
    private fun computeExitPrice(orderType: String, bestBid: BigDecimal): BigDecimal {
        return when (orderType.uppercase()) {
            "FAK" -> bestBid.subtract(EXIT_FAK_SLIPPAGE)
                .setScale(PRICE_SCALE, RoundingMode.DOWN)
                .max(MIN_PRICE)
            "MAKER", "GTC", "GTC_POST_ONLY" -> bestBid.add(MAKER_OFFSET)
                .setScale(PRICE_SCALE, RoundingMode.DOWN)
                .min(MAX_PRICE)
                .max(MIN_PRICE)
            else -> bestBid.subtract(EXIT_FAK_SLIPPAGE)
                .setScale(PRICE_SCALE, RoundingMode.DOWN)
                .max(MIN_PRICE)
        }
    }

    /** 失败抑制窗 cache key */
    private fun failBackoffKey(triggerId: Long, kind: ExitKind): String = "$triggerId-${kind.name}"

    /**
     * 提交退出单：签名 → 提交 CLOB → 写 exit 表（pending）。
     * 实际成交由 [CryptoTailExitOrderReconciler] 异步对账回填 filled_size/filled_amount。
     * FAK 立即成交场景下也仅记 pending；Reconciler 下次扫到 sizeMatched=originalSize 即转 success（语义统一）。
     */
    private suspend fun placeExitOrder(
        strategy: CryptoTailStrategy,
        trigger: CryptoTailStrategyTrigger,
        kind: ExitKind,
        targetSize: BigDecimal,
        pwinHolding: BigDecimal?,
        bestBid: BigDecimal,
        remainingSeconds: Int,
        reason: String,
        orderbook: OrderbookQualitySnapshot? = null
    ) {
        val triggerId = trigger.id ?: return
        val tokenId = trigger.tokenId ?: return

        val ctx = accountContextFactory.build(strategy) ?: run {
            logger.warn("阶梯退出无法构建账户上下文: strategyId=${strategy.id}, accountId=${strategy.accountId}")
            return
        }

        // STOP/FORCE 强制 FAK；TP1/TP2 按策略配置（FAK 默认；MAKER 由 Reconciler 在超时未成交时撤回退）
        val orderType = when (kind) {
            ExitKind.STOP, ExitKind.HARD_STOP, ExitKind.MODEL_INVALID, ExitKind.MODEL_FLIP,
            ExitKind.GAP_FLIP, ExitKind.TRAILING_STOP, ExitKind.WICK_REVERSAL, ExitKind.FORCE -> "FAK"
            ExitKind.TP1, ExitKind.TP2 -> if (strategy.exitOrderType.uppercase() == "MAKER") "GTC" else "FAK"
            ExitKind.SETTLE -> "FAK"  // 不应到这（SETTLE 由 SettlementService 处理）
        }
        val exitPrice = computeExitPrice(orderType, bestBid)
        val sizeStr = targetSize.toPlainString()

        val signedOrder = try {
            orderSigningService.createAndSignOrder(
                privateKey = ctx.decryptedPrivateKey,
                makerAddress = ctx.proxyAddress,
                tokenId = tokenId,
                side = "SELL",
                price = exitPrice.toPlainString(),
                size = sizeStr,
                signatureType = ctx.signatureType
            )
        } catch (e: Exception) {
            logger.error("阶梯退出签名失败 triggerId=$triggerId kind=$kind, ${e.message}", e)
            saveExit(
                strategy, trigger, kind, targetSize, exitPrice, orderType, "failed",
                pwinHolding, bestBid, remainingSeconds, reason, "签名失败:${e.message}", null
            )
            failBackoffCache.put(failBackoffKey(triggerId, kind), true)
            return
        }

        val isPostOnly = orderType == "GTC"
        val orderRequest = NewOrderRequest(
            order = signedOrder,
            owner = ctx.apiKey,
            orderType = orderType,
            postOnly = isPostOnly
        )

        var orderId: String? = null
        var failReason: String? = null
        try {
            val resp = ctx.clobApi.createOrder(orderRequest)
            if (resp.isSuccessful && resp.body() != null) {
                val body = resp.body()!!
                if (body.success && body.orderId != null) {
                    orderId = body.orderId
                } else {
                    failReason = body.errorMsg ?: body.getErrorMessage()
                }
            } else {
                val errBody = resp.errorBody()?.string().orEmpty()
                failReason = errBody.ifEmpty { "请求失败" }
            }
        } catch (e: Exception) {
            failReason = e.message ?: e.toString()
            logger.error("阶梯退出下单异常 triggerId=$triggerId kind=$kind, ${e.message}", e)
        }

        val recordOrderType = if (orderType == "GTC" && isPostOnly) "GTC_POST_ONLY" else orderType
        if (orderId != null) {
            saveExit(
                strategy, trigger, kind, targetSize, exitPrice, recordOrderType, "pending",
                pwinHolding, bestBid, remainingSeconds, reason, null, orderId
            )
            val submittedType = when (kind) {
                ExitKind.TP1, ExitKind.TP2 -> "TAKE_PROFIT_SUBMITTED"
                ExitKind.HARD_STOP, ExitKind.MODEL_INVALID, ExitKind.MODEL_FLIP, ExitKind.GAP_FLIP,
                ExitKind.TRAILING_STOP, ExitKind.WICK_REVERSAL, ExitKind.STOP -> "STOP_LOSS_SUBMITTED"
                else -> "EXIT_SUBMITTED"
            }
            recordEvent(
                trigger,
                eventType = submittedType,
                gateName = kind.name,
                passed = true,
                reason = "$reason → 已提交卖单 orderId=$orderId 价=${exitPrice.toPlainString()} 量=${sizeStr} 类型=$recordOrderType",
                pwinHolding = pwinHolding,
                bestBid = bestBid,
                remainingSeconds = remainingSeconds,
                extra = mapOf(
                    "exitKind" to kind.name,
                    "orderId" to orderId,
                    "exitPrice" to exitPrice.toPlainString(),
                    "targetSize" to sizeStr,
                    "orderType" to recordOrderType,
                    "bidDepthUsd" to (orderbook?.bidDepthUsd?.toPlainString() ?: ""),
                    "spread" to (orderbook?.spread?.toPlainString() ?: ""),
                    "quoteAgeMs" to (orderbook?.quoteAgeMs() ?: "")
                )
            )
            logger.info("阶梯退出已提交 triggerId=$triggerId kind=$kind orderId=$orderId price=${exitPrice.toPlainString()} size=$sizeStr type=$recordOrderType reason=$reason")
        } else {
            saveExit(
                strategy, trigger, kind, targetSize, exitPrice, recordOrderType, "failed",
                pwinHolding, bestBid, remainingSeconds, reason, failReason ?: "unknown", null
            )
            failBackoffCache.put(failBackoffKey(triggerId, kind), true)
            recordEvent(
                trigger,
                eventType = "EXIT_FAILED",
                gateName = kind.name,
                passed = false,
                reason = "$reason → 提交失败:${failReason ?: "unknown"}",
                pwinHolding = pwinHolding,
                bestBid = bestBid,
                remainingSeconds = remainingSeconds,
                extra = mapOf("exitKind" to kind.name, "failReason" to (failReason ?: ""))
            )
            logger.error("阶梯退出下单失败 triggerId=$triggerId kind=$kind reason=$failReason")
        }
    }

    private fun saveExit(
        strategy: CryptoTailStrategy,
        trigger: CryptoTailStrategyTrigger,
        kind: ExitKind,
        targetSize: BigDecimal,
        exitPrice: BigDecimal,
        orderType: String,
        status: String,
        pwinHolding: BigDecimal?,
        bestBid: BigDecimal,
        remainingSeconds: Int,
        decisionReason: String,
        failReason: String?,
        orderId: String?
    ): CryptoTailStrategyExit {
        val now = System.currentTimeMillis()
        val record = CryptoTailStrategyExit(
            triggerId = trigger.id!!,
            strategyId = strategy.id!!,
            exitKind = kind.name,
            targetSize = targetSize,
            filledSize = null,
            filledAmount = null,
            exitPrice = exitPrice,
            orderId = orderId,
            orderType = orderType,
            status = status,
            pwinAtDecision = pwinHolding,
            bestBidAtDecision = bestBid,
            remainingSeconds = remainingSeconds,
            decisionReason = decisionReason,
            failReason = failReason,
            createdAt = now,
            settledAt = if (status != "pending") now else null
        )
        return exitRepository.save(record)
    }

    /** 记录决策日志事件（异步发布） */
    private fun recordEvent(
        trigger: CryptoTailStrategyTrigger,
        eventType: String,
        gateName: String?,
        passed: Boolean?,
        reason: String?,
        pwinHolding: BigDecimal?,
        bestBid: BigDecimal,
        remainingSeconds: Int,
        extra: Map<String, Any?> = emptyMap()
    ) {
        val payload = buildMap<String, Any?> {
            put("mode", trigger.mode.name)
            put("triggerId", trigger.id)
            put("outcomeIndex", trigger.outcomeIndex)
            put("remainingSize", trigger.remainingSize?.toPlainString() ?: "")
            put("pWinHolding", pwinHolding?.toPlainString() ?: "")
            put("bestBid", bestBid.toPlainString())
            put("remainingSeconds", remainingSeconds)
            putAll(extra)
        }.toJson()
        decisionRecorder.record(
            CryptoTailDecisionEvent(
                strategyId = trigger.strategyId,
                periodStartUnix = trigger.periodStartUnix,
                correlationId = "${trigger.strategyId}-${trigger.periodStartUnix}-bracket-${trigger.id}",
                eventType = eventType,
                gateName = gateName,
                passed = passed,
                reason = reason,
                payloadJson = payload,
                outcomeIndex = trigger.outcomeIndex,
                triggerId = trigger.id
            )
        )
    }

    companion object {
        /** FAK 卖单滑点（与 AccountService.sellPosition 的 SELL_PRICE_ADJUSTMENT 保持一致） */
        private val EXIT_FAK_SLIPPAGE: BigDecimal = BigDecimal("0.02")
        /** MAKER/GTC 卖单价格相对 bestBid 的正向偏移（1 个 tick，避免立即 cross） */
        private val MAKER_OFFSET: BigDecimal = BigDecimal("0.01")
        /** Polymarket CLOB 价格精度（tickSize=0.01） */
        private const val PRICE_SCALE: Int = 2
        /** 价格下限（与 OrderSigningService.MIN_PRICE 一致） */
        private val MIN_PRICE: BigDecimal = BigDecimal("0.01")
        /** 价格上限（与 OrderSigningService.MAX_PRICE 一致） */
        private val MAX_PRICE: BigDecimal = BigDecimal("0.99")
        /**
         * V53 dust 容差：与 Reconciler.DUST_THRESHOLD、SettlementService.BRACKET_DUST_THRESHOLD 对齐。
         * 残余 size <= 0.01 视为已全部退出，避免对 dust 仓再下卖单。
         */
        private val DUST_THRESHOLD: BigDecimal = BigDecimal("0.01")
    }
}
