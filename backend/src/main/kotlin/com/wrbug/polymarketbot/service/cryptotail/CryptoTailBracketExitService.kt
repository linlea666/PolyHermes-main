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
import com.wrbug.polymarketbot.service.cryptotail.taildiff.TailDiffExitPreset
import com.wrbug.polymarketbot.service.cryptotail.taildiff.TailDiffExitPresetResolver
import com.wrbug.polymarketbot.service.cryptotail.taildiff.TailDiffTier
import com.wrbug.polymarketbot.service.cryptotail.taildiff.pick
import com.wrbug.polymarketbot.service.cryptotail.taildiff.pickMap
import com.wrbug.polymarketbot.util.fromJson
import com.wrbug.polymarketbot.util.toJson
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.delay
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
    private val wickSignalService: CryptoTailWickSignalService,
    private val tailDiffExitPresetResolver: TailDiffExitPresetResolver,
    private val reverseVelocityTracker: com.wrbug.polymarketbot.service.cryptotail.taildiff.CryptoTailReverseVelocityTracker,
    private val exitOrderReconciler: CryptoTailExitOrderReconciler
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
        orderbook: OrderbookQualitySnapshot? = null,
        triggerSource: String = "WS"
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
            evaluateAndExit(t, strategy, bestBid, nowSeconds, orderbook, triggerSource)
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
        orderbook: OrderbookQualitySnapshot? = null,
        triggerSource: String = "WS"
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
            // 进场成交后 grace 期：刚成交时链上余额/持仓可能尚未就绪，立即发退出单会报 balance:0 废单（trigger 160）。
            // grace 期内跳过退出评估（真急跌由 grace 结束后下一 tick 接管；grace 远短于周期，不影响兜底）。
            val sinceEntryMs = nowMs - fresh.createdAt
            if (sinceEntryMs in 0 until ENTRY_SETTLE_GRACE_MS) {
                logger.debug("阶梯退出跳过：进场 grace 期内 triggerId=$triggerId sinceEntry=${sinceEntryMs}ms")
                return
            }
            // 危险区跳过常规节流：bestBid 已明显跌破入场价（<=entry×DANGER_ZONE_RATIO）时价格可能正在崩塌，
            // 必须立即评估，不被 exitPollIntervalMs 拖到下一个 tick（trigger 150 曾 3s 内从 0.86 跳到 0.70）；
            // 仍保留 DANGER_ZONE_POLL_MS 最小间隔，防止崩盘期 WS 高频 tick 触发下单风暴。
            val entryFillForDanger = resolveEntryFillPrice(fresh)
            val inDangerZone = entryFillForDanger != null && entryFillForDanger > BigDecimal.ZERO &&
                bestBid <= entryFillForDanger.multiply(DANGER_ZONE_RATIO)
            // V92 杠杆1 尾盘提速：remaining<=scalpLateFastPollSeconds 时把节流降到 scalpLateFastPollMs，
            // 与危险区间隔取更小者，避免常规 exitPollIntervalMs(默认3000) 把尾盘评估拖到下一 tick。0=关（零回归）。
            val remainingForThrottle = (fresh.periodStartUnix + strategy.intervalSeconds - nowSeconds).toInt()
            val lateFastPoll = strategy.mode == TradingMode.SCALP_FLIP &&
                strategy.scalpLateFastPollSeconds > 0 && remainingForThrottle <= strategy.scalpLateFastPollSeconds
            var intervalMs = strategy.exitPollIntervalMs.coerceAtLeast(500).toLong()
            if (inDangerZone) intervalMs = minOf(intervalMs, DANGER_ZONE_POLL_MS)
            if (lateFastPoll) intervalMs = minOf(intervalMs, strategy.scalpLateFastPollMs.coerceAtLeast(100).toLong())
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

            recordExitCheck(checked, strategy, holding, bestBid, remainingSeconds, decision, orderbook, triggerSource)

            if (decision.kind == null || decision.ratio <= BigDecimal.ZERO) {
                val afterTp1 = persistTp1HoldStateIfNeeded(checked, decision)
                resetExitConfirmIfNeeded(afterTp1)
                return
            }
            persistTp1HoldStateIfNeeded(checked, decision)
            if (!applyExitConfirmation(checked, strategy, decision, nowMs)) return

            // V53 失败抑制窗：同 (triggerId, exitKind) 60s 内 placeExitOrder 失败过则跳过本次评估，避免死循环重试。
            // 但紧急退出（硬止损/熔断/模型反转/深底线）绝不可被抑制——一次 FAK 落空后必须下一 tick 立即重试，
            // 否则真反转时止损被冻结 60s 骑到归零（trigger 150）。紧急类的成交保证由 placeExitOrder 的市价扫单提供。
            if (!isEmergencyExit(decision.kind) && failBackoffCache.getIfPresent(failBackoffKey(triggerId, decision.kind)) != null) {
                logger.debug("阶梯退出跳过：60s 失败抑制窗内 triggerId=$triggerId kind=${decision.kind}")
                return
            }

            // 已有 pending exit 单：
            //  - 普通退出（TP/TRAILING 等）：不再挂新单，先让 Reconciler 处理（MAKER 的撤改由 Reconciler 负责）。
            //  - 紧急退出（急跌硬止损/模型失效/方向反转）：抢占——先撤掉未成交退出单并按实际成交回写 remaining，
            //    再以最新 remaining 直接发 FAK，避免止损被止盈挂单长时间阻塞而错过卖点。
            val emergency = isEmergencyExit(decision.kind)
            val pendingExits = exitRepository.findByTriggerIdAndStatus(triggerId, "pending")
            var effectiveRemaining = freshRemaining
            if (pendingExits.isNotEmpty()) {
                if (!emergency) {
                    logger.debug("阶梯退出跳过：已有 pending 出场单 triggerId=$triggerId")
                    return
                }
                logger.info("急跌止损抢占：撤未成交退出单后再发 ${decision.kind} triggerId=$triggerId pendingCount=${pendingExits.size}")
                for (pend in pendingExits) {
                    exitOrderReconciler.cancelPendingExitForPreempt(pend)
                }
                // 抢占撤单后重读 remaining（防超卖）
                val reRead = triggerRepository.findById(triggerId).orElse(null)
                effectiveRemaining = reRead?.remainingSize ?: freshRemaining
                if (effectiveRemaining <= DUST_THRESHOLD) {
                    logger.info("急跌止损抢占：撤单后剩余 <= dust，已无需再卖 triggerId=$triggerId")
                    return
                }
            }

            // 计算本次目标 size（DOWN 取整避免超卖；至少 0.01；不超过 remainingSize）
            val targetSize = effectiveRemaining.multiply(decision.ratio)
                .setScale(sizeDecimalScale, RoundingMode.DOWN)
                .min(effectiveRemaining)
            if (targetSize <= BigDecimal.ZERO) {
                logger.warn("阶梯退出跳过：targetSize=0 triggerId=$triggerId remaining=${effectiveRemaining.toPlainString()} ratio=${decision.ratio}")
                return
            }
            val sizing = sizeExitForLiquidity(strategy, orderbook, bestBid, targetSize, decision.kind, emergency)
            if (sizing.waitReason != null) {
                recordEvent(
                    strategy,
                    checked,
                    eventType = "EXIT_CHECK",
                    gateName = decision.kind.name,
                    passed = false,
                    reason = sizing.waitReason,
                    pwinHolding = pwinHolding,
                    bestBid = bestBid,
                    remainingSeconds = remainingSeconds,
                    extra = exitLiquidityPayload(orderbook, targetSize, sizing)
                        .plus("triggerSource" to triggerSource)
                )
                return
            }

            placeExitOrder(
                strategy = strategy,
                trigger = checked,
                kind = decision.kind,
                targetSize = sizing.targetSize,
                pwinHolding = pwinHolding,
                bestBid = bestBid,
                remainingSeconds = remainingSeconds,
                reason = decision.reason,
                orderbook = orderbook,
                applyWorstPriceFloor = decision.softPriceExit
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
        val safeRatio: BigDecimal,
        /** 当前价缓存年龄（毫秒）；价源无法提供时为 null。用于 Smart Hard Stop 价源新鲜度复核。 */
        val priceAgeMs: Long?,
        /** 持仓中"向 open 方向反抽"的速度（σ/s）；非反抽或样本不足时为 0。仅 TAIL_DIFF 动态退出消费。 */
        val reverseVelocitySigmaPerSec: BigDecimal = BigDecimal.ZERO
    ) {
        /**
         * 价源是否新鲜（用于智能硬止损豁免）：年龄非空且 <= maxPriceAgeMs（maxPriceAgeMs<=0 视为关闭限制=新鲜）。
         * 年龄缺失（null）一律视为不新鲜，绝不允许用过期/不可知数据豁免硬止损。
         */
        fun priceReady(maxPriceAgeMs: Int): Boolean {
            if (maxPriceAgeMs <= 0) return priceAgeMs != null
            val age = priceAgeMs ?: return false
            return age <= maxPriceAgeMs
        }
    }

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
        // 持仓阶段也持续喂入反抽速度采样：入场 evaluate() 在重复持仓后不再被调用（见 ExecutionService 重复持仓守卫），
        // 故反抽序列必须在退出评估热路径上补喂，否则 TAIL_DIFF 动态退出的 maxReverseVelocitySigma 永远拿不到样本。
        val nowMs = System.currentTimeMillis()
        reverseVelocityTracker.observe(strategy.marketSlugPrefix, closeP, nowMs)
        // 反抽速度采样窗口按模式取：SCALP_FLIP 用其专属窗口，其余模式沿用 TAIL_DIFF 窗口（行为不变）。
        val reverseVelocityWindow = if (strategy.mode == TradingMode.SCALP_FLIP) {
            strategy.scalpReverseVelocityWindowSeconds
        } else {
            strategy.tailDiffReverseVelocityWindowSeconds
        }
        val reverseVelocity = reverseVelocityTracker.computeReverseVelocity(
            marketSlugPrefix = strategy.marketSlugPrefix,
            outcomeIndex = trigger.outcomeIndex,
            sigmaPerSqrtS = sigma,
            windowSeconds = reverseVelocityWindow,
            nowMs = nowMs
        )
        val reverseVelocitySigmaPerSec = if (reverseVelocity.isReversing) reverseVelocity.velocitySigmaPerSec else BigDecimal.ZERO
        return HoldingState(
            gap = gap,
            modelSide = r.side,
            pWinModel = r.pWin,
            pWinHolding = holdingPWin,
            safeRatio = r.safeRatio,
            priceAgeMs = periodPriceProvider.getCurrentPriceAgeMs(strategy.marketSlugPrefix),
            reverseVelocitySigmaPerSec = reverseVelocitySigmaPerSec
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
        val clearTp1HoldStartedAt: Boolean = false,
        /** true = 本次 HARD_STOP 被 Smart Hard Stop 复核豁免、改为持有到结算（kind 为 null）。仅用于审计日志。 */
        val bypassedHardStop: Boolean = false,
        /**
         * true = 本退出仅由"裸盘口 bestBid 跌破 minOdds"这类纯价格噪声触发（最易被瞬时插针/假报价误伤）。
         * 用于两处差异化处理：
         *  1) [applyExitConfirmation] 不再视为 immediate，必须连续 exitConfirmTicks 次确认才放行；
         *  2) [placeExitOrder] 仅对此类退出套用退出预设 worstPrice 地板（防贱卖），真模型恶化/反转则照常市价割肉。
         */
        val softPriceExit: Boolean = false,
        /**
         * true = 强制即时放行（跳过 exitConfirmTicks 确认）。仅供"真熔断即时"使用：
         * 灾难线/回撤触发时若开启 catastrophe_immediate，则不等确认直接砍仓，换取最快止血（牺牲防插针）。
         * 默认 false → 与现有 HARD_STOP 一致，仍走确认。
         */
        val forceImmediate: Boolean = false,
        /**
         * true = 本退出由 SCALP 尾盘动态止损（LATE_DYNAMIC_STOP）触发。仅用于审计/日志 payload，
         * 配合 [scalpDisableWickGuardOnLateStop] 标记 wickGuardDisabledByLateStop。
         */
        val lateDynamicStop: Boolean = false
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
        // 无条件深底线（全模式最高优先级，先于一切模型/预设判定）：
        // bestBid 跌破 entryFillPrice×HARD_FLOOR_RATIO 时，无视模型/确认/抑制窗，立即市价全清止血。
        // 仅做绝对价格兜底，比 Smart Hard Stop 的 bypass 阈值更低且不可豁免（forceImmediate + HARD_STOP 走市价扫单）。
        resolveEntryFillPrice(trigger)?.let { entryFill ->
            if (entryFill > BigDecimal.ZERO) {
                // SCALP 深底线比例可配（scalpHardFloorRatio，默认 0.50）；其余模式仍用常量 HARD_FLOOR_RATIO。
                val floorRatio = if (strategy.mode == TradingMode.SCALP_FLIP) strategy.scalpHardFloorRatio else HARD_FLOOR_RATIO
                val hardFloor = entryFill.multiply(floorRatio)
                if (bestBid <= hardFloor) {
                    return Decision(
                        ExitKind.HARD_STOP,
                        BigDecimal.ONE,
                        "HARD_FLOOR: bestBid=${bestBid.toPlainString()}<=entry×${floorRatio.toPlainString()}=${hardFloor.toPlainString()} (entry=${entryFill.toPlainString()}) → 无条件市价全清",
                        clearTp1HoldStartedAt = true,
                        forceImmediate = true
                    )
                }
            }
        }
        // TAIL_DIFF / SCALP_FLIP：使用入场时冻结的退出预设做独立决策；不复用 BRACKET 的 TP/SL/动态退出。
        // 入场快照存于 trigger.exitPresetJson；策略表中途修改预设不影响在途持仓。
        // SCALP_FLIP 复用同一退出引擎：入场按 scalp_* 列冻结一份 preset（hold_to_expiry 恒为 false，
        // 用 tp_limit.enabled 切换"持有到结算(不挂TP)"与"挂止盈"，stop_loss + dynamic_exit 提供价位/标的/反抽止损）。
        if (strategy.mode == TradingMode.TAIL_DIFF || strategy.mode == TradingMode.SCALP_FLIP) {
            return decideTailDiffExit(strategy, trigger, holding, bestBid, remainingSeconds)
        }
        val entryFillPrice = trigger.entryFillPrice ?: run {
            val fs = trigger.filledSize
            val fa = trigger.filledAmount
            if (fs != null && fa != null && fs > BigDecimal.ZERO) fa.divide(fs, 8, RoundingMode.HALF_UP) else null
        }
        if (entryFillPrice != null) {
            val stopLine = entryFillPrice.multiply(BigDecimal.ONE.subtract(strategy.maxLossPct))
            if (bestBid <= stopLine) {
                // Smart Hard Stop 复核：HARD_STOP 命中后先判断是否满足"强势持有到结算"豁免条件。
                // 仅当开关开启 + 价源新鲜 + 模型方向未反 + gap 仍顺 + 临近结算 + pWin/safeRatio 达标时，
                // 才放弃机械硬止损、继续持有到结算（kind=null）；任一不满足都强制 HARD_STOP。
                if (strategy.enableSmartHardStop && holding != null) {
                    val bypass = CryptoTailHoldToSettlePolicy.evaluateHardStopBypass(
                        enabled = true,
                        priceReady = holding.priceReady(strategy.maxPriceAgeMs),
                        outcomeIndex = trigger.outcomeIndex,
                        modelSide = holding.modelSide,
                        gap = holding.gap,
                        pWinHolding = holding.pWinHolding,
                        safeRatio = holding.safeRatio,
                        remainingSeconds = remainingSeconds,
                        bypassMinPwin = strategy.holdToSettlePwin,
                        holdToSettleSeconds = strategy.holdToSettleSeconds,
                        exitSafeRatio = strategy.exitSafeRatio
                    )
                    if (bypass.bypass) {
                        return Decision(
                            null,
                            BigDecimal.ZERO,
                            "HARD_STOP_BYPASSED_BY_HOLD_TO_SETTLE: bestBid=${bestBid.toPlainString()}<=hardStopLine=${stopLine.toPlainString()} 但 currentPWin=${holding.pWinHolding.toPlainString()}>=bypassMinPwin=${strategy.holdToSettlePwin.toPlainString()} safeRatio=${holding.safeRatio.toPlainString()} modelSide=${holding.modelSide} outcomeIndex=${trigger.outcomeIndex} gap=${holding.gap.toPlainString()} remainingSeconds=$remainingSeconds priceReady=true",
                            clearTp1HoldStartedAt = true,
                            bypassedHardStop = true
                        )
                    }
                }
                return Decision(ExitKind.HARD_STOP, BigDecimal.ONE, "硬止损: bestBid=${bestBid.toPlainString()}<=${stopLine.toPlainString()}")
            }
        }
        if (holding != null && strategy.stopProb > BigDecimal.ZERO && holding.pWinHolding <= strategy.stopProb) {
            return Decision(ExitKind.STOP, BigDecimal.ONE, "止损: pWin=${holding.pWinHolding.toPlainString()}<=stopProb=${strategy.stopProb.toPlainString()}")
        }
        if (strategy.stopPrice > BigDecimal.ZERO && bestBid <= strategy.stopPrice) {
            return Decision(ExitKind.STOP, BigDecimal.ONE, "止损: bestBid=${bestBid.toPlainString()}<=stopPrice=${strategy.stopPrice.toPlainString()}")
        }
        if (holding != null && strategy.emergencyExitOnModelFlip && holding.modelSide != trigger.outcomeIndex) {
            return Decision(ExitKind.MODEL_FLIP, BigDecimal.ONE, "模型方向反转: current=${holding.modelSide} 背离持仓 outcomeIndex=${trigger.outcomeIndex}")
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
        if (strategy.wickFilterMode.uppercase() == "ENFORCE" && wick.available && wick.reversalScore >= strategy.wickExitScore) {
            return Decision(ExitKind.WICK_REVERSAL, BigDecimal.ONE, "影线反转止损: score=${wick.reversalScore}>=${strategy.wickExitScore}", clearTp1HoldStartedAt = true)
        }
        if (holding != null &&
            CryptoTailHoldToSettlePolicy.canHoldToSettle(
                outcomeIndex = trigger.outcomeIndex,
                gap = holding.gap,
                pWinHolding = holding.pWinHolding,
                remainingSeconds = remainingSeconds,
                holdToSettlePwin = strategy.holdToSettlePwin,
                holdToSettleSeconds = strategy.holdToSettleSeconds
            )
        ) {
            return Decision(
                null,
                BigDecimal.ZERO,
                "HOLD_TO_SETTLE: currentPWin=${holding.pWinHolding.toPlainString()} pWinModel=${holding.pWinModel.toPlainString()} modelSide=${holding.modelSide} outcomeIndex=${trigger.outcomeIndex} gap=${holding.gap.toPlainString()} remainingSeconds=$remainingSeconds holdToSettlePwin=${strategy.holdToSettlePwin.toPlainString()} holdToSettleSeconds=${strategy.holdToSettleSeconds}",
                clearTp1HoldStartedAt = true
            )
        }
        if (remainingSeconds <= strategy.forceExitBeforeSettleSeconds) {
            return Decision(
                ExitKind.FORCE,
                BigDecimal.ONE,
                "FORCE_EXIT: 剩余=${remainingSeconds}s<=${strategy.forceExitBeforeSettleSeconds}s 且未满足持有到结算条件",
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
            if (strategy.wickFilterMode.uppercase() == "ENFORCE" && wick.available && wick.continuationScore >= strategy.wickHoldProfitScore && remainingSeconds > strategy.forceExitBeforeSettleSeconds) {
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

    /**
     * TAIL_DIFF 退出决策：仅依据入场时冻结的 exitPresetJson + 入场分层（TOP=持有到结算）决策。
     *
     * 决策优先级：
     *  1. holdToExpiry=true → 持有到结算（kind=null）
     *  2. stopLoss：bestBid <= entryFillPrice * (1 - offset) 或 bestBid <= minPrice → STOP（全清/按 ratio）
     *  3. dynamicExit：modelProb 跌破 / diffSigma 跌破 / 反向 / bestBid 跌破 → STOP
     *  4. tpLimit：bestBid >= price → TP1（按 ratio）
     *  5. 其他 → 继续持有
     *
     * 不依赖 BARRIER 的 maxLossPct/stopProb/stopPrice/holdToSettlePwin 等；预设是独立的退出契约。
     */
    private fun decideTailDiffExit(
        strategy: CryptoTailStrategy,
        trigger: CryptoTailStrategyTrigger,
        holding: HoldingState?,
        bestBid: BigDecimal,
        remainingSeconds: Int
    ): Decision {
        val tier = TailDiffTier.fromLabel(trigger.tier)
        val modeLabel = if (trigger.mode == TradingMode.SCALP_FLIP) "SCALP" else "TAIL_DIFF"
        val preset = resolveTailDiffPresetForTrigger(strategy, trigger, tier)
        // SCALP 尾盘动态止损（V91）：时间分层风控。仅 SCALP_FLIP + scalpLateStopEnabled 生效。
        // 在尾盘窗口(remaining<=scalpLateStopSeconds)内，若 bid 从持仓峰值大回撤或跌破地板 → 立即 HARD_STOP 退出。
        // 与 WICK_GUARD 关系：scalpDisableWickGuardOnLateStop=true（默认）→ 在 WICK_GUARD 之前返回，永不被插针豁免；
        // =false → 允许 WICK_GUARD 先豁免、未豁免才在其后兜底退出。深底线(HARD_FLOOR)仍最高优先（decideExit 顶部）。
        val lateStop: Decision? = if (trigger.mode == TradingMode.SCALP_FLIP && strategy.scalpLateStopEnabled) {
            decideScalpLateStop(strategy, trigger, holding, bestBid, remainingSeconds, tier)
        } else null
        if (lateStop != null && strategy.scalpDisableWickGuardOnLateStop) return lateStop
        // V92 杠杆4 尾盘主动减仓：remaining<=scalpLateScaleOutSeconds 时，无视模型强挺(WICK_GUARD)按 scalpLateScaleOutRatio
        // 主动减仓一次（复用 FORCE：FAK marketable + 紧急抢占；hasExitOfKind 守卫全局仅一次），用确定性小滑点换掉尾盘穿价归零的尾部风险。
        // 仅当尾盘全清止损未命中(lateStop==null)时才减仓——命中大回撤/地板时应走全清而非部分。两参任一为 0 即关（零回归）。
        if (lateStop == null && trigger.mode == TradingMode.SCALP_FLIP &&
            strategy.scalpLateScaleOutSeconds > 0 && strategy.scalpLateScaleOutRatio > BigDecimal.ZERO &&
            remainingSeconds in 0..strategy.scalpLateScaleOutSeconds
        ) {
            val tid = trigger.id
            val ratio = strategy.scalpLateScaleOutRatio.max(BigDecimal.ZERO).min(BigDecimal.ONE)
            // 减仓量护栏：按 size 精度(2 位)向下取整须 >0（即 >=0.01）才减仓，否则跳过让常规逻辑接管——
            // 避免 dust 残尾上 targetSize 取整为 0、每 tick 空判 FORCE 不下单又遮蔽 TP/软退出（不创建退出行→hasExitOfKind 恒 false→死遮蔽）。
            val scaleOutSize = (trigger.remainingSize ?: BigDecimal.ZERO).multiply(ratio).setScale(sizeDecimalScale, RoundingMode.DOWN)
            if (tid != null && scaleOutSize > BigDecimal.ZERO && !hasExitOfKind(tid, ExitKind.FORCE)) {
                return Decision(
                    ExitKind.FORCE,
                    ratio,
                    "$modeLabel[${tier?.label ?: "?"}] LATE_SCALE_OUT: remaining=${remainingSeconds}s<=${strategy.scalpLateScaleOutSeconds}s 主动减仓 ratio=${ratio.toPlainString()} size≈${scaleOutSize.toPlainString()} bestBid=${bestBid.toPlainString()}",
                    clearTp1HoldStartedAt = true
                )
            }
        }
        // SCALP 抗插针总闸（V82）：进入此处时 bestBid 必 > 入场×深底线（深底线在 decideExit 顶部已无条件拦截）。
        // 模型强挺（方向未反 + gap 顺 + pWin>=插针容忍 + safeRatio>=可配下限 + 价源新鲜）→ 判为盘口瞬时插针，
        // 跳过所有亏损类退出（机械止损线/熔断地板/minOdds/反抽），继续持有；止盈(tpLimit)优先照常落袋。
        // 每 tick 复核：模型一旦真转弱（方向翻/pWin 掉/safeRatio 掉/gap 反）下一 tick 立即按原逻辑止损。
        // 仅 SCALP_FLIP + enableSmartHardStop 生效；TAIL_DIFF/BARRIER 路径与开关关闭时行为完全不变。
        if (trigger.mode == TradingMode.SCALP_FLIP && strategy.enableSmartHardStop && holding != null) {
            val wickBypass = CryptoTailHoldToSettlePolicy.evaluateHardStopBypass(
                enabled = true,
                priceReady = holding.priceReady(strategy.maxPriceAgeMs),
                outcomeIndex = trigger.outcomeIndex,
                modelSide = holding.modelSide,
                gap = holding.gap,
                pWinHolding = holding.pWinHolding,
                safeRatio = holding.safeRatio,
                remainingSeconds = remainingSeconds,
                bypassMinPwin = strategy.scalpSmartStopMinPwin,
                holdToSettleSeconds = strategy.intervalSeconds,
                exitSafeRatio = strategy.exitSafeRatio,
                minSafeRatioFloor = strategy.scalpSmartStopMinSafeRatio
            )
            if (wickBypass.bypass) {
                // 止盈优先：模型强挺但已到止盈线仍应落袋，不被总闸压住。
                tpLimitDecision(preset, trigger, bestBid, tier)?.let { return it }
                return Decision(
                    null,
                    BigDecimal.ZERO,
                    "$modeLabel[${tier?.label ?: "?"}] WICK_GUARD: 模型强挺(pWin=${holding.pWinHolding.toPlainString()}>=${strategy.scalpSmartStopMinPwin.toPlainString()} safeRatio=${holding.safeRatio.toPlainString()}>=max(${strategy.exitSafeRatio.toPlainString()},${strategy.scalpSmartStopMinSafeRatio.toPlainString()}) modelSide=${holding.modelSide}==oi=${trigger.outcomeIndex} gap=${holding.gap.toPlainString()}) bestBid=${bestBid.toPlainString()} → 判盘口插针,跳过价格类退出继续持有(深底线 入场×${strategy.scalpHardFloorRatio.toPlainString()} 无条件即时不可豁免)",
                    clearTp1HoldStartedAt = true
                )
            }
        }
        // 尾盘止损兜底（scalpDisableWickGuardOnLateStop=false 路径）：WICK_GUARD 未豁免时仍按尾盘止损退出。
        lateStop?.let { return it }
        if (preset.holdToExpiry) {
            // TOP 不再"无脑持有到结算"：即便 holdToExpiry，仍保留硬危险兜底（minOdds/反抽/价差坍缩/方向翻转），
            // 仅跳过 modelProb/diffSigma 这类较软的模型衰减退出，避免最大仓位在剧烈反转下零保护。
            dynamicExitDecision(strategy, preset, trigger, holding, bestBid, remainingSeconds, tier, hardOnly = true)?.let { return it }
            // 高信念单上行封顶：升到 tp_limit（如 0.99）即落袋，避免为贪最后 1 分而承担尾盘反转风险。
            // tp_limit.enabled=false（TOP 默认）时返回 null → 行为不变，仍持有到结算。
            tpLimitDecision(preset, trigger, bestBid, tier)?.let { return it }
            return Decision(null, BigDecimal.ZERO, "$modeLabel[${tier?.label ?: "TOP"}] holdToExpiry=true，持有到结算（已通过硬危险兜底）remaining=${remainingSeconds}s", clearTp1HoldStartedAt = true)
        }
        val entryFillPrice = resolveEntryFillPrice(trigger)
        // 2) StopLoss
        if (preset.stopLoss.enabled && entryFillPrice != null) {
            val stopLine = entryFillPrice.multiply(BigDecimal.ONE.subtract(preset.stopLoss.offset))
            val effectiveStop = stopLine.max(preset.stopLoss.minPrice)
            if (bestBid <= effectiveStop) {
                // 防误杀（底层确认门）：bestBid 跌破止损线可能只是 Polymarket 盘口在尾盘薄流动性下的瞬时下插针。
                // 命中后先用底层模型(gap/pWin/safeRatio，源自结算价源)复核：方向仍站我方 + pWin/safeRatio 达标 +
                // 价源新鲜 → 判为插针，继续持有（实测 139/140/158/161 被插针误杀但持有必赢）；任一不满足才真止损。
                // 复用 BARRIER 的 Smart Hard Stop 策略；SCALP/TAIL_DIFF 整笔仅约 1 分钟，全程视为"临近结算"，
                // 故 holdToSettleSeconds 传 intervalSeconds 放开 NOT_NEAR_SETTLE 闸（否则只覆盖剩余<=holdToSettleSeconds 的插针）。
                // 【WS2-A 根因修复】机械止损线(入场×0.95/最低0.90)远高于熔断地板(入场×0.85)，会先于熔断无门控砸出，
                // 使 WS2 熔断模型门控在默认配置下形同虚设。故 SCALP 在已冻结的 catastropheFloorRatio>0(新建默认0.85)时，
                // 机械止损同样启用抗插针模型门控、不再依赖 enableSmartHardStop；ratio=0(存量)仍仅 enableSmartHardStop 生效，零回归。
                val scalpModelGateOn = trigger.mode == TradingMode.SCALP_FLIP && preset.dynamicExit.catastropheFloorRatio > BigDecimal.ZERO
                if ((strategy.enableSmartHardStop || scalpModelGateOn) && holding != null) {
                    // 插针容忍 pWin 下限：SCALP_FLIP 用 scalpSmartStopMinPwin（默认 0.70，与持有到结算 pWin 解耦）；
                    // TAIL_DIFF 沿用 holdToSettlePwin（行为不变）。其余复核（方向/gap/safeRatio>=max(exitSafeRatio,1.30)/价源新鲜）一致。
                    val bypassMinPwin = if (trigger.mode == TradingMode.SCALP_FLIP) strategy.scalpSmartStopMinPwin else strategy.holdToSettlePwin
                    val bypassMinSafeRatio = if (trigger.mode == TradingMode.SCALP_FLIP) strategy.scalpSmartStopMinSafeRatio else CryptoTailHoldToSettlePolicy.SMART_HARD_STOP_MIN_SAFE_RATIO
                    val bypass = CryptoTailHoldToSettlePolicy.evaluateHardStopBypass(
                        enabled = true,
                        priceReady = holding.priceReady(strategy.maxPriceAgeMs),
                        outcomeIndex = trigger.outcomeIndex,
                        modelSide = holding.modelSide,
                        gap = holding.gap,
                        pWinHolding = holding.pWinHolding,
                        safeRatio = holding.safeRatio,
                        remainingSeconds = remainingSeconds,
                        bypassMinPwin = bypassMinPwin,
                        holdToSettleSeconds = strategy.intervalSeconds,
                        exitSafeRatio = strategy.exitSafeRatio,
                        minSafeRatioFloor = bypassMinSafeRatio
                    )
                    if (bypass.bypass) {
                        return Decision(
                            null,
                            BigDecimal.ZERO,
                            "$modeLabel[${tier?.label ?: "?"}] StopLoss_BYPASSED_BY_HOLD_TO_SETTLE: bestBid=${bestBid.toPlainString()}<=${effectiveStop.toPlainString()} 但 pWin=${holding.pWinHolding.toPlainString()}>=bypassMinPwin=${bypassMinPwin.toPlainString()} modelSide=${holding.modelSide}==oi=${trigger.outcomeIndex} gap=${holding.gap.toPlainString()} safeRatio=${holding.safeRatio.toPlainString()} → 判为插针继续持有",
                            clearTp1HoldStartedAt = true,
                            bypassedHardStop = true
                        )
                    }
                }
                val ratio = preset.stopLoss.ratio.max(BigDecimal.ZERO).min(BigDecimal.ONE)
                return Decision(
                    ExitKind.HARD_STOP,
                    ratio,
                    "$modeLabel[${tier?.label ?: "?"}] StopLoss: bestBid=${bestBid.toPlainString()}<=${effectiveStop.toPlainString()} (entry=${entryFillPrice.toPlainString()}, offset=${preset.stopLoss.offset.toPlainString()}, minPrice=${preset.stopLoss.minPrice.toPlainString()})",
                    clearTp1HoldStartedAt = true
                )
            }
        }
        // 3) DynamicExit：modelProb / diffSigma / bestBid / 价差坍缩 / 反抽 / 方向翻转 任一触发即退出
        dynamicExitDecision(strategy, preset, trigger, holding, bestBid, remainingSeconds, tier, hardOnly = false)?.let { return it }
        // 4) TpLimit
        tpLimitDecision(preset, trigger, bestBid, tier)?.let { return it }
        return Decision(null, BigDecimal.ZERO, "$modeLabel[${tier?.label ?: "?"}] 继续持有 remaining=${remainingSeconds}s", clearTp1HoldStartedAt = true)
    }

    /**
     * SCALP 尾盘动态止损（V91）：时间分层风控，仅在尾盘窗口内对"从持仓峰值大回撤/跌破地板"立即止损。
     * 调用方已保证 trigger.mode==SCALP_FLIP 且 strategy.scalpLateStopEnabled==true。
     *
     * 触发条件（满足其一即退出，均要求处于尾盘窗口 remaining<=scalpLateStopSeconds）：
     *  - 回撤触发：peakBid 有效(>0) 且 (peakBid - bestBid) >= scalpLatePeakDrawdown（绝对价差，非百分比）；
     *  - 地板触发：bestBid <= scalpLateBidFloor（peakBid 缺失也能触发，不要求模型转弱）。
     * 若 scalpLateStopRequireWeakModel=true，则仅当模型转弱（evaluateHardStopBypass 不豁免/holding 缺失）才放行。
     *
     * @return 命中返回 HARD_STOP（reason 含 LATE_DYNAMIC_STOP；forceImmediate=scalpDisableWickGuardOnLateStop）；未命中返回 null。
     */
    private fun decideScalpLateStop(
        strategy: CryptoTailStrategy,
        trigger: CryptoTailStrategyTrigger,
        holding: HoldingState?,
        bestBid: BigDecimal,
        remainingSeconds: Int,
        tier: TailDiffTier?
    ): Decision? {
        if (remainingSeconds > strategy.scalpLateStopSeconds) return null
        if (bestBid <= BigDecimal.ZERO) return null

        val peakBid = trigger.peakBid?.takeIf { it > BigDecimal.ZERO }
        val peakDrawdown = peakBid?.subtract(bestBid)?.max(BigDecimal.ZERO)
        val lateDrawdownHit = peakBid != null && peakDrawdown != null &&
            strategy.scalpLatePeakDrawdown > BigDecimal.ZERO && peakDrawdown >= strategy.scalpLatePeakDrawdown
        val lateBidFloorHit = strategy.scalpLateBidFloor > BigDecimal.ZERO && bestBid <= strategy.scalpLateBidFloor

        if (!lateDrawdownHit && !lateBidFloorHit) return null

        // 模型转弱门控（默认关）：开启时仅当模型不再强挺才放行，避免在模型仍强挺时误杀普通插针。
        if (strategy.scalpLateStopRequireWeakModel) {
            val modelStrong = holding != null && CryptoTailHoldToSettlePolicy.evaluateHardStopBypass(
                enabled = true,
                priceReady = holding.priceReady(strategy.maxPriceAgeMs),
                outcomeIndex = trigger.outcomeIndex,
                modelSide = holding.modelSide,
                gap = holding.gap,
                pWinHolding = holding.pWinHolding,
                safeRatio = holding.safeRatio,
                remainingSeconds = remainingSeconds,
                bypassMinPwin = strategy.scalpSmartStopMinPwin,
                holdToSettleSeconds = strategy.intervalSeconds,
                exitSafeRatio = strategy.exitSafeRatio,
                minSafeRatioFloor = strategy.scalpSmartStopMinSafeRatio
            ).bypass
            if (modelStrong) return null
        }

        val label = tier?.label ?: "?"
        val wickTag = if (strategy.scalpDisableWickGuardOnLateStop) "禁止 WICK_GUARD 豁免,立即退出" else "允许 WICK_GUARD 优先,未豁免则退出"
        val reason = if (lateDrawdownHit) {
            "SCALP[$label] LATE_DYNAMIC_STOP: remaining=${remainingSeconds}s peakBid=${peakBid?.toPlainString()} currentBestBid=${bestBid.toPlainString()} drawdown=${peakDrawdown?.toPlainString()}>=${strategy.scalpLatePeakDrawdown.toPlainString()} -> $wickTag"
        } else {
            "SCALP[$label] LATE_DYNAMIC_STOP: remaining=${remainingSeconds}s currentBestBid=${bestBid.toPlainString()}<=lateBidFloor=${strategy.scalpLateBidFloor.toPlainString()} -> $wickTag"
        }
        return Decision(
            ExitKind.HARD_STOP,
            BigDecimal.ONE,
            reason,
            clearTp1HoldStartedAt = true,
            forceImmediate = strategy.scalpDisableWickGuardOnLateStop,
            lateDynamicStop = true
        )
    }

    /**
     * TAIL_DIFF 固定止盈（tp_limit）评估：bestBid >= tp_limit.price 时按 ratio 触发 TP1。
     * 从 decideTailDiffExit 提取，供常规流程与 holdToExpiry（高信念封顶）分支共用，确保两路口径一致。
     *
     * @return 命中止盈线返回 TP1 Decision；已触发过 TP1 返回持有 Decision（去重）；tp_limit 关闭或未达线返回 null。
     */
    private fun tpLimitDecision(
        preset: TailDiffExitPreset,
        trigger: CryptoTailStrategyTrigger,
        bestBid: BigDecimal,
        tier: TailDiffTier?
    ): Decision? {
        if (!preset.tpLimit.enabled || bestBid < preset.tpLimit.price) return null
        val modeLabel = if (trigger.mode == TradingMode.SCALP_FLIP) "SCALP" else "TAIL_DIFF"
        // 去重守卫：部分止盈(ratio<1)时价格维持在止盈线上会反复命中本分支，
        // 复用 hasExitOfKind(TP1) 确保 TP1 仅触发一次（与 BRACKET 同口径，避免重复挂卖单）。
        val triggerId = trigger.id
        if (triggerId != null && hasExitOfKind(triggerId, ExitKind.TP1)) {
            return Decision(null, BigDecimal.ZERO, "$modeLabel[${tier?.label ?: "?"}] TP1_ALREADY_TRIGGERED，不重复止盈", clearTp1HoldStartedAt = true)
        }
        val ratio = preset.tpLimit.ratio.max(BigDecimal.ZERO).min(BigDecimal.ONE)
        return Decision(
            ExitKind.TP1,
            ratio,
            "$modeLabel[${tier?.label ?: "?"}] TP: bestBid=${bestBid.toPlainString()}>=${preset.tpLimit.price.toPlainString()}",
            clearTp1HoldStartedAt = true
        )
    }

    /**
     * TAIL_DIFF 动态退出评估（从 decideTailDiffExit 提取，供普通流程与 TOP 持有兜底共用）。
     *
     * @param hardOnly true=仅评估"硬危险"条件（minOdds / 价差坍缩 / 反抽 / 方向翻转），跳过 modelProb/diffSigma
     *                 这类较软的模型衰减退出；用于 holdToExpiry（TOP）档的最小兜底，避免大仓位零保护。
     *                 false=评估全部动态退出条件（NORMAL/PREMIUM 常规流程）。
     * @return 命中则返回对应 Decision；未命中或 dynamicExit 关闭/holding 不可用时返回 null。
     */
    /**
     * 入场成交价解析：优先 trigger.entryFillPrice；缺失时用 filledAmount/filledSize 推导；都不可用返回 null。
     * 从 decideTailDiffExit 提取，供止损线与真熔断回撤计算共用，确保两路口径一致。
     */
    private fun resolveEntryFillPrice(trigger: CryptoTailStrategyTrigger): BigDecimal? {
        trigger.entryFillPrice?.let { return it }
        val fs = trigger.filledSize
        val fa = trigger.filledAmount
        return if (fs != null && fa != null && fs > BigDecimal.ZERO) fa.divide(fs, 8, RoundingMode.HALF_UP) else null
    }

    /**
     * 真熔断评估：灾难绝对线（bestBid<=catastropheBidFloor）或相对回撤（>=maxDrawdownPct）任一触发，
     * 返回全清 HARD_STOP（softPriceExit=false → 无 worstPrice 地板 + 经 exitConfirmTicks 确认）。
     * 两个阈值默认 0=关闭 → 旧配置零回归。仅做"价格/回撤"硬危险判定，与模型信念无关，专封尾部风险。
     */
    private fun catastropheExitDecision(
        strategy: CryptoTailStrategy,
        cfg: TailDiffExitPreset.DynamicExit,
        trigger: CryptoTailStrategyTrigger,
        holding: HoldingState?,
        bestBid: BigDecimal,
        remainingSeconds: Int,
        label: String
    ): Decision? {
        val modeLabel = if (trigger.mode == TradingMode.SCALP_FLIP) "SCALP" else "TAIL_DIFF"
        val isScalp = trigger.mode == TradingMode.SCALP_FLIP
        val entry = resolveEntryFillPrice(trigger)

        // 地板判定：SCALP 且 catastropheFloorRatio>0 → 相对地板=入场价×比例；否则沿用绝对线 catastropheBidFloor。
        // 比例取自入场冻结的退出预设(cfg)，与 catastropheBidFloor/catastropheImmediate 同口径，确保策略表中途修改不影响在途持仓。
        val relativeFloor: BigDecimal? = if (isScalp && cfg.catastropheFloorRatio > BigDecimal.ZERO &&
            entry != null && entry > BigDecimal.ZERO
        ) {
            entry.multiply(cfg.catastropheFloorRatio)
        } else null
        val effectiveFloor = relativeFloor ?: cfg.catastropheBidFloor
        val floorTriggered = effectiveFloor > BigDecimal.ZERO && bestBid <= effectiveFloor

        // 相对回撤触发（SCALP 冻结为 0，主要服务 TAIL_DIFF）
        var drawdown: BigDecimal? = null
        if (!floorTriggered && cfg.maxDrawdownPct > BigDecimal.ZERO && entry != null && entry > BigDecimal.ZERO) {
            drawdown = entry.subtract(bestBid).divide(entry, 8, RoundingMode.HALF_UP)
        }
        val drawdownTriggered = drawdown != null && drawdown >= cfg.maxDrawdownPct

        if (!floorTriggered && !drawdownTriggered) return null

        // 【WS2 熔断模型门控】仅 SCALP_FLIP 且处于模型门控制度(enableSmartHardStop 开 或 已冻结 catastropheFloorRatio>0)：
        //  跌破熔断地板后先用底层模型复核——
        //  - 模型仍强挺(方向未反+pWin/safeRatio 达标+价源新鲜) → 判为盘口瞬时插针 → 不即时，改走 exitConfirmTicks 短确认，给反弹机会；
        //  - 模型已翻转/转弱 → 判为真反转 → 按 catastropheImmediate 配置即时砍仓止血。
        //  ratio>0(新建默认0.85)即生效，不依赖 enableSmartHardStop；ratio=0 且未开 enableSmartHardStop(存量) → 沿用旧"立即砸出"，零回归。
        //  与机械止损旁路同制度同口径，避免一处门控一处裸砸。TAIL_DIFF 行为完全不变。
        val modelGateOn = isScalp && (strategy.enableSmartHardStop || cfg.catastropheFloorRatio > BigDecimal.ZERO)
        var modelHolds = false
        if (modelGateOn && holding != null) {
            val bypass = CryptoTailHoldToSettlePolicy.evaluateHardStopBypass(
                enabled = true,
                priceReady = holding.priceReady(strategy.maxPriceAgeMs),
                outcomeIndex = trigger.outcomeIndex,
                modelSide = holding.modelSide,
                gap = holding.gap,
                pWinHolding = holding.pWinHolding,
                safeRatio = holding.safeRatio,
                remainingSeconds = remainingSeconds,
                bypassMinPwin = strategy.scalpSmartStopMinPwin,
                holdToSettleSeconds = strategy.intervalSeconds,
                exitSafeRatio = strategy.exitSafeRatio,
                minSafeRatioFloor = strategy.scalpSmartStopMinSafeRatio
            )
            modelHolds = bypass.bypass
        }
        // 模型强挺=插针 → 短确认(forceImmediate=false)；否则真反转 → 按配置即时
        val immediate = if (modelHolds) false else cfg.catastropheImmediate
        val gateTag = if (modelGateOn && holding != null) {
            if (modelHolds) "模型强挺→插针,走${strategy.exitConfirmTicks}tick短确认" else "模型转弱/翻转→真反转"
        } else "无模型门控"
        val floorDesc = if (relativeFloor != null) {
            "relFloor=入场×${cfg.catastropheFloorRatio.toPlainString()}=${effectiveFloor.toPlainString()}"
        } else {
            "catastropheBidFloor=${effectiveFloor.toPlainString()}"
        }
        val reason = if (floorTriggered) {
            "$modeLabel[$label] Catastrophe: bestBid=${bestBid.toPlainString()}<=$floorDesc ($gateTag) → 无地板市价止损${if (immediate) "(即时)" else ""}"
        } else {
            "$modeLabel[$label] Catastrophe: drawdown=${drawdown?.toPlainString()}>=maxDrawdownPct=${cfg.maxDrawdownPct.toPlainString()} (entry=${entry?.toPlainString()}, bid=${bestBid.toPlainString()}, $gateTag) → 无地板市价止损${if (immediate) "(即时)" else ""}"
        }
        return Decision(
            ExitKind.HARD_STOP,
            BigDecimal.ONE,
            reason,
            clearTp1HoldStartedAt = true,
            forceImmediate = immediate
        )
    }

    private fun dynamicExitDecision(
        strategy: CryptoTailStrategy,
        preset: TailDiffExitPreset,
        trigger: CryptoTailStrategyTrigger,
        holding: HoldingState?,
        bestBid: BigDecimal,
        remainingSeconds: Int,
        tier: TailDiffTier?,
        hardOnly: Boolean
    ): Decision? {
        if (!preset.dynamicExit.enabled || holding == null) return null
        val label = tier?.label ?: "?"
        val modeLabel = if (trigger.mode == TradingMode.SCALP_FLIP) "SCALP" else "TAIL_DIFF"
        // 真熔断（最高优先级，先于 minOdds 评估）：minOdds 分支是 softPriceExit（套 worstPrice 地板），
        // 崩盘 bestBid<worstPrice 时那条卖单根本成交不了且会短路后续检查；holdToExpiry 又跳过 stop_loss 块，
        // 形成"模型仍自信 + 价格崩破地板"下大仓位零有效止损的裸奔缺口。此处灾难线/回撤任一触发即发
        // 无地板 HARD_STOP（softPriceExit=false → 经 exitConfirmTicks 确认防插针后以 FAK 市价扫单真实止损）。
        // WS2：SCALP 熔断已内建模型门控（模型强挺=插针走短确认，模型翻转=真反转即时），不再纯盘口裸触发。
        catastropheExitDecision(strategy, preset.dynamicExit, trigger, holding, bestBid, remainingSeconds, label)?.let { return it }
        // 软退出（模型衰减）：仅在非 hardOnly 时评估
        if (!hardOnly && holding.pWinHolding < preset.dynamicExit.minModelProbAfterEntry) {
            return Decision(
                ExitKind.MODEL_INVALID,
                BigDecimal.ONE,
                "$modeLabel[$label] DynamicExit: pWin=${holding.pWinHolding.toPlainString()}<${preset.dynamicExit.minModelProbAfterEntry.toPlainString()}",
                clearTp1HoldStartedAt = true
            )
        }
        if (!hardOnly && holding.safeRatio < preset.dynamicExit.minDiffSigmaAfterEntry) {
            return Decision(
                ExitKind.MODEL_INVALID,
                BigDecimal.ONE,
                "$modeLabel[$label] DynamicExit: diffSigma=${holding.safeRatio.toPlainString()}<${preset.dynamicExit.minDiffSigmaAfterEntry.toPlainString()}",
                clearTp1HoldStartedAt = true
            )
        }
        // 硬危险：bestBid 跌破 minOdds（纯盘口价格触发，最易被瞬时插针误伤）
        // → 标记 softPriceExit：需连续 exitConfirmTicks 次确认 + 退出时套 worstPrice 地板防贱卖。
        if (bestBid < preset.dynamicExit.minOddsAfterEntry) {
            return Decision(
                ExitKind.STOP,
                BigDecimal.ONE,
                "$modeLabel[$label] DynamicExit: bestBid=${bestBid.toPlainString()}<${preset.dynamicExit.minOddsAfterEntry.toPlainString()}",
                clearTp1HoldStartedAt = true,
                softPriceExit = true
            )
        }
        // 价差坍缩：相对入场 diffSigma 回撤比例超过 maxDiffRetracePct（入场快照 trigger.diffSigma 为 ground truth）
        val entryDiffSigma = trigger.diffSigma
        if (entryDiffSigma != null && entryDiffSigma > BigDecimal.ZERO && preset.dynamicExit.maxDiffRetracePct > BigDecimal.ZERO) {
            val retrace = entryDiffSigma.subtract(holding.safeRatio)
                .divide(entryDiffSigma, 8, RoundingMode.HALF_UP)
            if (retrace > preset.dynamicExit.maxDiffRetracePct) {
                return Decision(
                    ExitKind.MODEL_INVALID,
                    BigDecimal.ONE,
                    "$modeLabel[$label] DynamicExit: diffRetrace=${retrace.toPlainString()}>${preset.dynamicExit.maxDiffRetracePct.toPlainString()} (entryDiffSigma=${entryDiffSigma.toPlainString()}, current=${holding.safeRatio.toPlainString()})",
                    clearTp1HoldStartedAt = true
                )
            }
        }
        // 快速反抽：持仓中向 open 方向反抽速度超过 maxReverseVelocitySigma（σ/s）
        if (preset.dynamicExit.maxReverseVelocitySigma > BigDecimal.ZERO &&
            holding.reverseVelocitySigmaPerSec > preset.dynamicExit.maxReverseVelocitySigma
        ) {
            return Decision(
                ExitKind.STOP,
                BigDecimal.ONE,
                "$modeLabel[$label] DynamicExit: reverseVelocity=${holding.reverseVelocitySigmaPerSec.toPlainString()}σ/s>${preset.dynamicExit.maxReverseVelocitySigma.toPlainString()}",
                clearTp1HoldStartedAt = true
            )
        }
        // 方向反转视为反抽：仅当模型转向"我方对立面"(modelSide != 持仓 outcomeIndex)才退出。
        // 修正：此前对比 trigger.entryModelSide，当入场快照方向与持仓方向不一致时（如进场瞬间模型恰好翻动），
        // 会把"模型翻向我方"误判为反转而错误退出（trigger 160：持仓 Down=1，模型 0→1 翻向我方却被卖）。
        // 改为对比 outcomeIndex（我们真正持有的方向），语义上即"模型现在认为我方会输"才退出。
        if (holding.modelSide != trigger.outcomeIndex) {
            return Decision(
                ExitKind.MODEL_FLIP,
                BigDecimal.ONE,
                "$modeLabel[$label] DynamicExit: modelSide=${holding.modelSide} 背离持仓 outcomeIndex=${trigger.outcomeIndex}",
                clearTp1HoldStartedAt = true
            )
        }
        return null
    }

    /**
     * 优先用 trigger 入场时冻结的 exit_preset_json；不可用时回退到当前策略表配置（按 tier 解析）。
     * 这避免了"策略表中途改了预设导致在途持仓退出条件变化"。
     */
    private fun resolveTailDiffPresetForTrigger(
        strategy: CryptoTailStrategy,
        trigger: CryptoTailStrategyTrigger,
        tier: TailDiffTier?
    ): TailDiffExitPreset {
        val snapshotJson = trigger.exitPresetJson
        if (!snapshotJson.isNullOrBlank()) {
            try {
                val raw = snapshotJson.fromJson<Map<String, Any?>>() ?: emptyMap()
                return parseTailDiffPresetFromMap(raw, tier?.let { tailDiffExitPresetResolver.defaultForTier(it) } ?: TailDiffExitPreset())
            } catch (e: Exception) {
                logger.warn("TAIL_DIFF trigger.exitPresetJson 解析失败，回退到策略表: triggerId=${trigger.id}, ${e.message}")
            }
        }
        return tier?.let { tailDiffExitPresetResolver.resolveForTier(strategy, it) } ?: TailDiffExitPreset()
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseTailDiffPresetFromMap(raw: Map<String, Any?>, default: TailDiffExitPreset): TailDiffExitPreset {
        fun anyBool(v: Any?, d: Boolean): Boolean = when (v) {
            is Boolean -> v
            is String -> v.equals("true", true)
            is Number -> v.toInt() != 0
            else -> d
        }
        fun anyBd(v: Any?, d: BigDecimal): BigDecimal = when (v) {
            is BigDecimal -> v
            is Number -> BigDecimal(v.toString())
            is String -> if (v.isBlank()) d else v.toSafeBigDecimal()
            else -> d
        }
        val tpRaw = raw.pickMap("tp_limit", "tpLimit")
        val slRaw = raw.pickMap("stop_loss", "stopLoss")
        val dynRaw = raw.pickMap("dynamic_exit", "dynamicExit")
        val execRaw = raw.pickMap("execution", "execution")
        return TailDiffExitPreset(
            holdToExpiry = anyBool(raw.pick("hold_to_expiry", "holdToExpiry"), default.holdToExpiry),
            tpLimit = TailDiffExitPreset.TpLimit(
                enabled = anyBool(tpRaw["enabled"], default.tpLimit.enabled),
                price = anyBd(tpRaw["price"], default.tpLimit.price),
                ratio = anyBd(tpRaw["ratio"], default.tpLimit.ratio)
            ),
            stopLoss = TailDiffExitPreset.StopLoss(
                enabled = anyBool(slRaw["enabled"], default.stopLoss.enabled),
                offset = anyBd(slRaw["offset"], default.stopLoss.offset),
                minPrice = anyBd(slRaw.pick("min_price", "minPrice"), default.stopLoss.minPrice),
                ratio = anyBd(slRaw["ratio"], default.stopLoss.ratio)
            ),
            dynamicExit = TailDiffExitPreset.DynamicExit(
                enabled = anyBool(dynRaw["enabled"], default.dynamicExit.enabled),
                minDiffSigmaAfterEntry = anyBd(dynRaw.pick("min_diff_sigma_after_entry", "minDiffSigmaAfterEntry"), default.dynamicExit.minDiffSigmaAfterEntry),
                maxDiffRetracePct = anyBd(dynRaw.pick("max_diff_retrace_pct", "maxDiffRetracePct"), default.dynamicExit.maxDiffRetracePct),
                minModelProbAfterEntry = anyBd(dynRaw.pick("min_model_prob_after_entry", "minModelProbAfterEntry"), default.dynamicExit.minModelProbAfterEntry),
                minOddsAfterEntry = anyBd(dynRaw.pick("min_odds_after_entry", "minOddsAfterEntry"), default.dynamicExit.minOddsAfterEntry),
                maxReverseVelocitySigma = anyBd(dynRaw.pick("max_reverse_velocity_sigma", "maxReverseVelocitySigma"), default.dynamicExit.maxReverseVelocitySigma),
                catastropheBidFloor = anyBd(dynRaw.pick("catastrophe_bid_floor", "catastropheBidFloor"), default.dynamicExit.catastropheBidFloor),
                maxDrawdownPct = anyBd(dynRaw.pick("max_drawdown_pct", "maxDrawdownPct"), default.dynamicExit.maxDrawdownPct),
                catastropheImmediate = anyBool(dynRaw.pick("catastrophe_immediate", "catastropheImmediate"), default.dynamicExit.catastropheImmediate)
            ),
            execution = TailDiffExitPreset.Execution(
                tpSlippage = anyBdOrNull(execRaw.pick("tp_slippage", "tpSlippage")) ?: default.execution.tpSlippage,
                stopSlippage = anyBdOrNull(execRaw.pick("stop_slippage", "stopSlippage")) ?: default.execution.stopSlippage,
                worstPrice = anyBdOrNull(execRaw.pick("worst_price", "worstPrice")) ?: default.execution.worstPrice
            )
        )
    }

    private fun anyBdOrNull(v: Any?): BigDecimal? = when (v) {
        is BigDecimal -> v
        is Number -> BigDecimal(v.toString())
        is String -> if (v.isBlank()) null else v.toSafeBigDecimal()
        else -> null
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
        if (strategy.maxOrderbookAgeMs > 0 && quoteAge > strategy.maxOrderbookAgeMs) {
            return Decision(null, BigDecimal.ZERO, "TP流动性等待: quoteAgeMs=$quoteAge>${strategy.maxOrderbookAgeMs}")
        }
        val depthAge = orderbook.depthAgeMs(nowMs)
        if (strategy.maxOrderbookAgeMs > 0 && (orderbook.depthStale || depthAge == null || depthAge > strategy.maxOrderbookAgeMs)) {
            return Decision(null, BigDecimal.ZERO, "TP流动性等待: depthAgeMs=${depthAge ?: ""}>${strategy.maxOrderbookAgeMs} 或深度缺失")
        }
        val spread = orderbook.spread
        if (strategy.maxExitSpread > BigDecimal.ZERO && spread != null && spread > strategy.maxExitSpread) {
            return Decision(null, BigDecimal.ZERO, "TP流动性等待: exitSpread=${spread.toPlainString()}>${strategy.maxExitSpread.toPlainString()}")
        }
        val bidDepthUsd = orderbook.bidDepthUsd
        if (strategy.minExitBidDepthUsdc > BigDecimal.ZERO && bidDepthUsd != null && bidDepthUsd < strategy.minExitBidDepthUsdc) {
            return Decision(null, BigDecimal.ZERO, "TP流动性等待: bidDepthUsd=${bidDepthUsd.toPlainString()}<${strategy.minExitBidDepthUsdc.toPlainString()}")
        }
        if (orderbook.bestBid.compareTo(bestBid) != 0) {
            return Decision(null, BigDecimal.ZERO, "TP流动性等待: 快照bestBid与当前bestBid不一致")
        }
        return null
    }

    private data class ExitSizing(
        val targetSize: BigDecimal,
        val plannedSize: BigDecimal,
        val executableSize: BigDecimal?,
        val splitByLiquidity: Boolean = false,
        val waitReason: String? = null
    )

    private fun sizeExitForLiquidity(
        strategy: CryptoTailStrategy,
        orderbook: OrderbookQualitySnapshot?,
        bestBid: BigDecimal,
        targetSize: BigDecimal,
        kind: ExitKind,
        emergency: Boolean = false
    ): ExitSizing {
        if (isDepthSplitExit(kind)) {
            // 紧急退出（急跌硬止损等）：放宽深度门禁——盘口陈旧/深度不足时不再 waitReason 排队，
            // 直接以 targetSize 全量发 FAK（限价由 placeExitOrder 用退出预设 worstPrice 兜底，避免贱卖）。
            // 宁可承担一定滑点也要尽快止损，符合"急跌不卖"是更大风险的取舍。
            if (emergency) {
                return ExitSizing(targetSize, targetSize, targetSize)
            }
            val snapshot = orderbook ?: return ExitSizing(
                targetSize = BigDecimal.ZERO,
                plannedSize = targetSize,
                executableSize = BigDecimal.ZERO,
                waitReason = "ORDERBOOK_REFRESH_RECHECK_FAILED"
            )
            val nowMs = System.currentTimeMillis()
            val quoteAge = snapshot.quoteAgeMs(nowMs)
            val depthAge = snapshot.depthAgeMs(nowMs)
            if (strategy.maxOrderbookAgeMs > 0 && quoteAge > strategy.maxOrderbookAgeMs) {
                return ExitSizing(
                    targetSize = BigDecimal.ZERO,
                    plannedSize = targetSize,
                    executableSize = BigDecimal.ZERO,
                    waitReason = "EXIT_QUOTE_STALE"
                )
            }
            if (snapshot.depthStale || snapshot.depthUpdatedAtMs == null ||
                (strategy.maxOrderbookAgeMs > 0 && (depthAge == null || depthAge > strategy.maxOrderbookAgeMs))
            ) {
                return ExitSizing(
                    targetSize = BigDecimal.ZERO,
                    plannedSize = targetSize,
                    executableSize = BigDecimal.ZERO,
                    waitReason = "EXIT_QUOTE_STALE"
                )
            }
            val exitPrice = computeExitPrice("FAK", bestBid, strategy.exitFakSlippage)
            val executable = snapshot.executableBidSizeAtBestOrBetter(exitPrice, targetSize)
                .setScale(sizeDecimalScale, RoundingMode.DOWN)
                .min(targetSize)
            if (executable <= BigDecimal.ZERO) {
                return ExitSizing(
                    targetSize = BigDecimal.ZERO,
                    plannedSize = targetSize,
                    executableSize = executable,
                    waitReason = "退出流动性等待: 硬退出限价=${exitPrice.toPlainString()} 下无可执行 bid 深度"
                )
            }
            return ExitSizing(
                targetSize = executable,
                plannedSize = targetSize,
                executableSize = executable,
                splitByLiquidity = executable < targetSize
            )
        }
        val base = checkTakeProfitLiquidity(strategy, orderbook, bestBid)?.reason
        if (base != null) return ExitSizing(targetSize, targetSize, null, waitReason = base)
        val expectedDepth = orderbook?.executableBidDepthUsd(targetSize) ?: BigDecimal.ZERO
        if (strategy.minExitBidDepthUsdc > BigDecimal.ZERO && expectedDepth < strategy.minExitBidDepthUsdc) {
            return ExitSizing(
                targetSize,
                targetSize,
                null,
                waitReason = "退出流动性等待: executableExitDepthUsd=${expectedDepth.toPlainString()}<${strategy.minExitBidDepthUsdc.toPlainString()}"
            )
        }
        return ExitSizing(targetSize, targetSize, null)
    }

    /**
     * 紧急退出判定：急跌硬止损 / 机械止损 / 模型失效 / 模型方向反转 / gap 反转。
     * 这些场景"不卖"的风险远大于滑点，故允许抢占已有 pending 退出单并放宽深度门禁直发 FAK。
     * 不含 FORCE（结算前强平已有独立兜底）、TP/TRAILING（止盈类无需抢占）。
     */
    private fun isEmergencyExit(kind: ExitKind?): Boolean =
        kind == ExitKind.HARD_STOP ||
                kind == ExitKind.STOP ||
                kind == ExitKind.MODEL_INVALID ||
                kind == ExitKind.MODEL_FLIP ||
                kind == ExitKind.GAP_FLIP

    private fun isDepthSplitExit(kind: ExitKind): Boolean =
        kind == ExitKind.HARD_STOP ||
                kind == ExitKind.MODEL_INVALID ||
                kind == ExitKind.MODEL_FLIP ||
                kind == ExitKind.GAP_FLIP ||
                kind == ExitKind.FORCE ||
                kind == ExitKind.STOP ||
                kind == ExitKind.TRAILING_STOP ||
                kind == ExitKind.WICK_REVERSAL

    private fun exitLiquidityPayload(
        orderbook: OrderbookQualitySnapshot?,
        targetSize: BigDecimal,
        sizing: ExitSizing? = null
    ): Map<String, Any> {
        val expectedDepth = orderbook?.executableBidDepthUsd(targetSize)
        val expectedPrice = orderbook?.expectedExitPrice(targetSize)
        return mapOf(
            "targetSize" to targetSize.toPlainString(),
            "finalTargetSize" to (sizing?.targetSize?.toPlainString() ?: targetSize.toPlainString()),
            "executableExitSize" to (sizing?.executableSize?.toPlainString() ?: ""),
            "liquiditySplit" to (sizing?.splitByLiquidity ?: false),
            "executableExitDepthUsd" to (expectedDepth?.toPlainString() ?: ""),
            "expectedExitPrice" to (expectedPrice?.toPlainString() ?: ""),
            "expectedExitSlippage" to (expectedPrice?.let { orderbook?.bestBid?.subtract(it)?.max(BigDecimal.ZERO)?.toPlainString() } ?: ""),
            "bidDepthUsd" to (orderbook?.bidDepthUsd?.toPlainString() ?: ""),
            "bidLevelCount" to (orderbook?.bidLevels?.size ?: 0),
            "bestBid" to (orderbook?.bestBid?.toPlainString() ?: ""),
            "spread" to (orderbook?.spread?.toPlainString() ?: ""),
            "quoteAgeMs" to (orderbook?.quoteAgeMs() ?: ""),
            "depthAgeMs" to (orderbook?.depthAgeMs() ?: ""),
            "depthStale" to (orderbook?.depthStale ?: true)
        )
    }

    private fun persistTp1HoldStateIfNeeded(trigger: CryptoTailStrategyTrigger, decision: Decision): CryptoTailStrategyTrigger {
        val triggerId = trigger.id ?: return trigger
        val next = when {
            decision.clearTp1HoldStartedAt -> null
            decision.tp1HoldStartedAt != null -> decision.tp1HoldStartedAt
            else -> trigger.tp1HoldStartedAt
        }
        if (next == trigger.tp1HoldStartedAt) return trigger
        return triggerRepository.save(trigger.copy(id = triggerId, tp1HoldStartedAt = next))
    }

    /**
     * 本 tick 判定为继续持有（kind=null）时复位退出确认计数，确保 softPriceExit 要求的是
     * "连续 N tick 跌破"而非"累计 N tick"——否则插针→恢复→再插针会提前满足确认。
     * 仅在计数/原因非空时写库，避免持有热路径产生无谓 UPDATE。
     */
    private fun resetExitConfirmIfNeeded(trigger: CryptoTailStrategyTrigger) {
        if (trigger.exitConfirmCount == 0 && trigger.exitConfirmReason == null) return
        val triggerId = trigger.id ?: return
        triggerRepository.save(trigger.copy(id = triggerId, exitConfirmCount = 0, exitConfirmReason = null))
    }

    private fun applyExitConfirmation(
        trigger: CryptoTailStrategyTrigger,
        strategy: CryptoTailStrategy,
        decision: Decision,
        nowMs: Long
    ): Boolean {
        val kind = decision.kind ?: return false
        // softPriceExit（裸盘口跌破 minOdds）不享受 immediate 直通，必须连续 exitConfirmTicks 次确认，
        // 过滤瞬时插针/假报价；其余急退（HARD_STOP/模型反转/坍缩/止盈）保持即时。
        val immediate = ((isDepthSplitExit(kind) || kind == ExitKind.TP1 || kind == ExitKind.TP2) && !decision.softPriceExit) ||
            decision.forceImmediate
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
        orderbook: OrderbookQualitySnapshot? = null,
        triggerSource: String = "WS"
    ) {
        val entryFillPrice = trigger.entryFillPrice ?: BigDecimal.ZERO
        val peakBid = trigger.peakBid ?: bestBid
        val drawdown = peakBid.subtract(bestBid).max(BigDecimal.ZERO)
        val remaining = trigger.remainingSize ?: BigDecimal.ZERO
        val realizedIfExit = bestBid.subtract(entryFillPrice).multiply(remaining).setScale(8, RoundingMode.HALF_UP)
        val expectedExitPrice = orderbook?.expectedExitPrice(remaining)
        val executableDepth = orderbook?.executableBidDepthUsd(remaining)
        val payload = mapOf(
            "strategyId" to (strategy.id ?: ""),
            "strategyName" to (strategy.name ?: ""),
            "coin" to (CryptoTailCoinResolver.coinOfSlug(strategy.marketSlugPrefix) ?: ""),
            "positionId" to (trigger.id?.toString() ?: ""),
            "triggerId" to (trigger.id ?: ""),
            "marketSlug" to strategy.marketSlugPrefix,
            "periodStartUnix" to trigger.periodStartUnix,
            "tokenId" to (trigger.tokenId ?: ""),
            "outcomeIndex" to trigger.outcomeIndex,
            "entryFillPrice" to entryFillPrice.toPlainString(),
            "currentBestBid" to bestBid.toPlainString(),
            "currentBestAsk" to (orderbook?.bestAsk?.toPlainString() ?: ""),
            "entryPWin" to (trigger.entryPWin?.toPlainString() ?: ""),
            "currentPWin" to (holding?.pWinHolding?.toPlainString() ?: ""),
            "entrySafeRatio" to (trigger.entrySafeRatio?.toPlainString() ?: ""),
            "currentSafeRatio" to (holding?.safeRatio?.toPlainString() ?: ""),
            "entryModelSide" to (trigger.entryModelSide ?: ""),
            "currentModelSide" to (holding?.modelSide ?: ""),
            "pWinModel" to (holding?.pWinModel?.toPlainString() ?: ""),
            "modelSide" to (holding?.modelSide ?: ""),
            "triggerSource" to triggerSource,
            "holdToSettlePwin" to strategy.holdToSettlePwin.toPlainString(),
            "holdToSettleSeconds" to strategy.holdToSettleSeconds,
            "entryGap" to (trigger.entryGap?.toPlainString() ?: ""),
            "currentGap" to (holding?.gap?.toPlainString() ?: ""),
            "remainingSeconds" to remainingSeconds,
            "peakBid" to peakBid.toPlainString(),
            "drawdownFromPeak" to drawdown.toPlainString(),
            "exitReason" to (decision.kind?.name ?: ""),
            "scalpLateStopEnabled" to strategy.scalpLateStopEnabled,
            "scalpLateStopSeconds" to strategy.scalpLateStopSeconds,
            "scalpLatePeakDrawdown" to strategy.scalpLatePeakDrawdown.toPlainString(),
            "scalpLateBidFloor" to strategy.scalpLateBidFloor.toPlainString(),
            "wickGuardDisabledByLateStop" to (decision.lateDynamicStop && strategy.scalpDisableWickGuardOnLateStop),
            "scalpLateFastPollSeconds" to strategy.scalpLateFastPollSeconds,
            "scalpLateFastPollMs" to strategy.scalpLateFastPollMs,
            "scalpEmergencyRetryCount" to strategy.scalpEmergencyRetryCount,
            "scalpLateIgnoreWorstPriceSeconds" to strategy.scalpLateIgnoreWorstPriceSeconds,
            "scalpLateScaleOutSeconds" to strategy.scalpLateScaleOutSeconds,
            "scalpLateScaleOutRatio" to strategy.scalpLateScaleOutRatio.toPlainString(),
            "remainingSize" to remaining.toPlainString(),
            "realizedPnlIfExit" to realizedIfExit.toPlainString(),
            "tp1HoldStartedAt" to (decision.tp1HoldStartedAt ?: trigger.tp1HoldStartedAt ?: ""),
            "tp1HoldCleared" to decision.clearTp1HoldStartedAt,
            "reason" to decision.reason,
            "bidSize" to (orderbook?.bidSize?.toPlainString() ?: ""),
            "bidDepthUsd" to (orderbook?.bidDepthUsd?.toPlainString() ?: ""),
            "executableExitDepthUsd" to (executableDepth?.toPlainString() ?: ""),
            "spread" to (orderbook?.spread?.toPlainString() ?: ""),
            "quoteAgeMs" to (orderbook?.quoteAgeMs() ?: ""),
            "depthAgeMs" to (orderbook?.depthAgeMs() ?: ""),
            "depthStale" to (orderbook?.depthStale ?: true),
            "expectedExitPrice" to (expectedExitPrice?.toPlainString() ?: bestBid.toPlainString()),
            "expectedExitSlippage" to (expectedExitPrice?.let { bestBid.subtract(it).max(BigDecimal.ZERO).toPlainString() } ?: "")
        )
        // EXIT_CHECK 去重：持仓平稳（continue hold，kind==null）时，每 interval 都会进来一条几乎相同的诊断行。
        // 用 (triggerId + 决策类型 + 模型方向 + gap 符号 + 2% pWin 桶 + 30s 剩余时间桶) 作签名，
        // 同签名窗口内只落库一次；真正的退出信号（kind != null）始终落库，不被去重抑制。
        val isExitSignal = decision.kind != null
        val exitCheckDedupKey = buildExitCheckDedupKey(trigger, holding, decision, remainingSeconds)
        val shouldLogExitCheck = isExitSignal || decisionLoggedCache.getIfPresent(exitCheckDedupKey) == null
        if (shouldLogExitCheck) {
            if (!isExitSignal) decisionLoggedCache.put(exitCheckDedupKey, true)
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
        }
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
        // Smart Hard Stop 豁免审计：HARD_STOP 命中但被强势持有覆盖时，始终落一条专用诊断行（不被去重抑制），
        // 便于事后复盘"到底是正常止损，还是模型强势下选择持有到结算"。
        if (decision.bypassedHardStop) {
            val hardStopLine = (trigger.entryFillPrice ?: BigDecimal.ZERO)
                .multiply(BigDecimal.ONE.subtract(strategy.maxLossPct))
            val bypassPayload = payload.plus(
                mapOf(
                    "positionSide" to if (trigger.outcomeIndex == 0) "UP" else "DOWN",
                    "hardStopLine" to hardStopLine.toPlainString(),
                    "currentPWinModel" to (holding?.pWinModel?.toPlainString() ?: ""),
                    "pWinHolding" to (holding?.pWinHolding?.toPlainString() ?: ""),
                    "gap" to (holding?.gap?.toPlainString() ?: ""),
                    "gapSupportsHolding" to (holding != null && CryptoTailHoldToSettlePolicy.gapSupportsHolding(trigger.outcomeIndex, holding.gap)),
                    "priceReadyReason" to (if (holding != null && holding.priceReady(strategy.maxPriceAgeMs)) "OK" else "STALE_OR_MISSING"),
                    "priceAgeMs" to (holding?.priceAgeMs ?: ""),
                    "maxPriceAgeMs" to strategy.maxPriceAgeMs
                )
            )
            decisionRecorder.record(
                CryptoTailDecisionEvent(
                    strategyId = trigger.strategyId,
                    periodStartUnix = trigger.periodStartUnix,
                    correlationId = "${trigger.strategyId}-${trigger.periodStartUnix}-exit-${trigger.id}",
                    eventType = "EXIT_CHECK",
                    gateName = "HARD_STOP_BYPASSED_BY_HOLD_TO_SETTLE",
                    passed = false,
                    reason = decision.reason,
                    payloadJson = bypassPayload.toJson(),
                    outcomeIndex = trigger.outcomeIndex,
                    triggerId = trigger.id
                )
            )
            logger.info(
                "Smart Hard Stop 豁免: triggerId=${trigger.id} bestBid=${bestBid.toPlainString()} " +
                    "hardStopLine=${hardStopLine.toPlainString()} pWin=${holding?.pWinHolding?.toPlainString()} " +
                    "safeRatio=${holding?.safeRatio?.toPlainString()} remainingSeconds=$remainingSeconds → 持有到结算"
            )
        }
    }

    /** EXIT_CHECK 去重签名：仅在决策类型/模型方向/gap 符号/pWin(2%桶)/剩余时间(30s桶)变化时才视为新行 */
    private fun buildExitCheckDedupKey(
        trigger: CryptoTailStrategyTrigger,
        holding: HoldingState?,
        decision: Decision,
        remainingSeconds: Int
    ): String {
        val kind = decision.kind?.name ?: "HOLD"
        val modelSide = holding?.modelSide ?: -1
        val gapSign = holding?.gap?.signum() ?: 0
        val pwinBucket = holding?.pWinHolding
            ?.multiply(BigDecimal(50))?.setScale(0, RoundingMode.FLOOR)?.toInt() ?: -1
        val remainingBucket = remainingSeconds / 30
        return "exitcheck-${trigger.id}-$kind-$modelSide-$gapSign-$pwinBucket-$remainingBucket"
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
    private fun computeExitPrice(
        orderType: String,
        bestBid: BigDecimal,
        slippage: BigDecimal = EXIT_FAK_SLIPPAGE
    ): BigDecimal {
        val fakSlippage = slippage.max(BigDecimal.ZERO)
        return when (orderType.uppercase()) {
            "FAK" -> bestBid.subtract(fakSlippage)
                .setScale(PRICE_SCALE, RoundingMode.DOWN)
                .max(MIN_PRICE)
            "MAKER", "GTC", "GTC_POST_ONLY" -> bestBid.add(MAKER_OFFSET)
                .setScale(PRICE_SCALE, RoundingMode.DOWN)
                .min(MAX_PRICE)
                .max(MIN_PRICE)
            else -> bestBid.subtract(fakSlippage)
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
        orderbook: OrderbookQualitySnapshot? = null,
        /**
         * true = 仅由裸盘口跌破 minOdds 这类价格噪声触发的退出（softPriceExit），套用退出预设 worstPrice 地板防贱卖；
         * false（默认）= 真模型恶化/反转/止损/止盈等，照常按 slippage 市价成交，不被地板挡住（避免该卖却卖不出而骑到归零）。
         */
        applyWorstPriceFloor: Boolean = false
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
            // TAIL_DIFF 退出一律 FAK：避免止盈走 GTC 挂单后，后续止损被 pending 守卫阻塞无法抢占（OCO 缺口）。
            // 其他模式维持原行为（MAKER 配置走 GTC，由 Reconciler 负责超时撤改）。
            ExitKind.TP1, ExitKind.TP2 ->
                if (strategy.mode != TradingMode.TAIL_DIFF && strategy.exitOrderType.uppercase() == "MAKER") "GTC" else "FAK"
            ExitKind.SETTLE -> "FAK"  // 不应到这（SETTLE 由 SettlementService 处理）
        }
        // TAIL_DIFF / SCALP_FLIP：用入场冻结预设的 execution 块覆盖退出滑点（按 TP/STOP 分别取），并应用 worstPrice 绝对底线；
        // 其他模式与未配置时回退全局 strategy.exitFakSlippage（行为不变）。
        val (effExitSlippage, worstPriceFloor) = if (strategy.mode == TradingMode.TAIL_DIFF || strategy.mode == TradingMode.SCALP_FLIP) {
            val preset = resolveTailDiffPresetForTrigger(strategy, trigger, TailDiffTier.fromLabel(trigger.tier))
            val isTp = kind == ExitKind.TP1 || kind == ExitKind.TP2
            val slip = (if (isTp) preset.execution.tpSlippage else preset.execution.stopSlippage) ?: strategy.exitFakSlippage
            slip to preset.execution.worstPrice
        } else {
            strategy.exitFakSlippage to null
        }
        var exitPrice = computeExitPrice(orderType, bestBid, effExitSlippage)
        val isFak = orderType.uppercase() == "FAK"
        // V92 杠杆3 尾盘 marketable：remaining<=scalpLateIgnoreWorstPriceSeconds 时，连 softPriceExit(裸盘口跌破 minOdds)
        // 也忽略 worstPrice 地板、按 MIN_PRICE 直发市价扫单——尾盘已无修复时间，宁可吃滑点也要成交，避免被地板挡住骑到归零。0=关（零回归）。
        val lateMarketable = strategy.mode == TradingMode.SCALP_FLIP &&
            strategy.scalpLateIgnoreWorstPriceSeconds > 0 &&
            remainingSeconds in 0..strategy.scalpLateIgnoreWorstPriceSeconds
        // 紧急退出（硬止损/熔断/模型反转/深底线）且非 softPriceExit 时：FAK 限价取 MIN_PRICE 作市价扫单，
        // 保证只要盘口有买单就成交（FAK 按盘口挂单价、价格-时间优先撮合，限价 0.01 仅定"最差可接受"，
        // 实际成交价仍是当前最优买价，绝不劣于现状）。这是 trigger 150 在崩盘中 bid-0.02 限价被反复杀单的直接修复。
        // softPriceExit（裸盘口跌破 minOdds，最易被插针误伤）平时不走此分支，仍由下方 worstPrice 地板保护；尾盘 lateMarketable 时一并放行。
        val forceMarketable = isFak && ((isEmergencyExit(kind) && !applyWorstPriceFloor) || lateMarketable)
        if (forceMarketable) {
            exitPrice = MIN_PRICE
        } else if (applyWorstPriceFloor && isFak && worstPriceFloor != null && worstPriceFloor > BigDecimal.ZERO) {
            // worstPrice：FAK 卖出限价不得低于该底线（限价被抬高 → 低于底线时不成交，避免滑点把仓位贱卖）。
            // 仅对 softPriceExit（裸盘口跌破 minOdds）套用——假报价/插针时宁可不成交＝继续持有赢家；
            // 真模型恶化/反转/止损不套地板，确保该割肉时能在市价附近成交，不被地板挡住而骑到归零。
            exitPrice = exitPrice.max(worstPriceFloor.setScale(PRICE_SCALE, RoundingMode.DOWN))
        }
        val sizeStr = targetSize.toPlainString()

        val isPostOnly = orderType == "GTC"
        // V92 杠杆2 紧急 FAK 失败快速重试：仅当本单为 marketable(限价被压到 MIN_PRICE=0.01 的市价扫单) 且配置了重试次数时，
        // 同次评估内对"硬失败(orderId 为空：零成交/被拒/异常)"重新签名(换 salt 防重放)并重试，以抓住瞬时回补的对手盘。
        // 仅在 orderId 为空时重试 → 部分成交会返回 orderId 并立即 break，绝不会重复卖出已成交部分。0=关（仅沿用下一 tick 重试，行为不变）。
        val emergencyRetryEnabled = forceMarketable && strategy.scalpEmergencyRetryCount > 0
        val maxAttempts = if (emergencyRetryEnabled) 1 + strategy.scalpEmergencyRetryCount else 1
        val retryDelayMs = strategy.scalpEmergencyRetryIntervalMs.coerceAtLeast(50).toLong()

        var orderId: String? = null
        var failReason: String? = null
        // FAK 即时成交回执：吃单成交价＝对手盘价（≠我方激进限价，紧急退出限价被 Fix4 压到 MIN_PRICE=0.01），
        // 必须以撮合返回的真实成交量入账，否则对账阶段用 0.01 限价估值会把市价扫单错记成 1¢ 贱卖（phantom 巨亏）。
        var immediateFilledSize: BigDecimal? = null
        var immediateFilledAmount: BigDecimal? = null
        var attempt = 0
        while (attempt < maxAttempts) {
            if (attempt > 0) {
                delay(retryDelayMs)
                logger.warn("紧急退出 FAK 第${attempt}/${maxAttempts - 1}次重试 triggerId=$triggerId kind=$kind 上次失败=$failReason")
                failReason = null
            }
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
                failReason = "签名失败:${e.message}"
                logger.error("阶梯退出签名失败 triggerId=$triggerId kind=$kind, ${e.message}", e)
                attempt++
                continue
            }
            val orderRequest = NewOrderRequest(
                order = signedOrder,
                owner = ctx.apiKey,
                orderType = orderType,
                postOnly = isPostOnly
            )
            try {
                val resp = ctx.clobApi.createOrder(orderRequest)
                if (resp.isSuccessful && resp.body() != null) {
                    val body = resp.body()!!
                    if (body.success && body.orderId != null) {
                        orderId = body.orderId
                        val a = body.makingAmount?.toSafeBigDecimal()
                        val b = body.takingAmount?.toSafeBigDecimal()
                        if (a != null && b != null && a > BigDecimal.ZERO && b > BigDecimal.ZERO) {
                            // 二元市场价格∈(0,1)，故 USDC=份额×价格<份额：较大者为成交份额(filledSize)，较小者为成交USDC(filledAmount)。
                            // 该不变式对买卖两侧 making/taking 字段映射均成立，规避字段语义歧义。
                            immediateFilledSize = a.max(b)
                            immediateFilledAmount = a.min(b)
                        }
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
            if (orderId != null) break
            attempt++
        }

        val recordOrderType = if (orderType == "GTC" && isPostOnly) "GTC_POST_ONLY" else orderType
        if (orderId != null) {
            saveExit(
                strategy, trigger, kind, targetSize, exitPrice, recordOrderType, "pending",
                pwinHolding, bestBid, remainingSeconds, reason, null, orderId,
                immediateFilledSize, immediateFilledAmount
            )
            val submittedType = when (kind) {
                ExitKind.TP1, ExitKind.TP2 -> "TAKE_PROFIT_SUBMITTED"
                ExitKind.HARD_STOP, ExitKind.MODEL_INVALID, ExitKind.MODEL_FLIP, ExitKind.GAP_FLIP,
                ExitKind.TRAILING_STOP, ExitKind.WICK_REVERSAL, ExitKind.STOP -> "STOP_LOSS_SUBMITTED"
                else -> "EXIT_SUBMITTED"
            }
            recordEvent(
                strategy,
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
                    "executableExitDepthUsd" to (orderbook?.executableBidDepthUsd(targetSize)?.toPlainString() ?: ""),
                    "expectedExitPrice" to (orderbook?.expectedExitPrice(targetSize)?.toPlainString() ?: ""),
                    "expectedExitSlippage" to (orderbook?.expectedExitPrice(targetSize)?.let { bestBid.subtract(it).max(BigDecimal.ZERO).toPlainString() } ?: ""),
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
            // 紧急退出失败不写抑制窗（否则会冻结后续重试，正是 trigger 150 骑到归零的直接原因）；
            // 仅非紧急（TP/MAKER 挂单被拒等）才写，防"下次 tick 又重挂又被拒"死循环。
            if (!isEmergencyExit(kind)) {
                failBackoffCache.put(failBackoffKey(triggerId, kind), true)
            }
            recordEvent(
                strategy,
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
        orderId: String?,
        filledSize: BigDecimal? = null,
        filledAmount: BigDecimal? = null
    ): CryptoTailStrategyExit {
        val now = System.currentTimeMillis()
        val record = CryptoTailStrategyExit(
            triggerId = trigger.id!!,
            strategyId = strategy.id!!,
            exitKind = kind.name,
            targetSize = targetSize,
            filledSize = filledSize,
            filledAmount = filledAmount,
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
        strategy: CryptoTailStrategy,
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
            put("strategyId", strategy.id ?: "")
            put("strategyName", strategy.name ?: "")
            put("coin", CryptoTailCoinResolver.coinOfSlug(strategy.marketSlugPrefix) ?: "")
            put("marketSlug", strategy.marketSlugPrefix)
            put("periodStartUnix", trigger.periodStartUnix)
            put("tokenId", trigger.tokenId ?: "")
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

        /**
         * 无条件深底线比例：bestBid <= entryFillPrice × 此值时，无视模型/确认/抑制窗，立即市价全清止血。
         * 比 Smart Hard Stop 的 bypass 阈值更低且不可豁免；取 0.50 以避免误杀深插针(如 bid 插到 0.62 但底层仍站我方)。
         */
        private val HARD_FLOOR_RATIO: BigDecimal = BigDecimal("0.50")

        /**
         * 危险区比例：bestBid <= entryFillPrice × 此值时视为价格可能正在崩塌，跳过常规巡检节流、改用更短的危险区节流，
         * 以免 exitPollIntervalMs 把退出评估拖到下一个 tick（trigger 150 曾在 3s 内从 0.86 跳到 0.70）。
         */
        private val DANGER_ZONE_RATIO: BigDecimal = BigDecimal("0.95")

        /** 危险区最小评估间隔（毫秒）：跳过常规节流后仍保留的最小间隔，防止崩盘期 WS 高频 tick 触发下单风暴。 */
        private const val DANGER_ZONE_POLL_MS: Long = 500L

        /**
         * 进场成交后退出评估 grace 期（毫秒）：刚成交时链上余额/持仓可能尚未就绪，立即发退出单会报 balance:0 废单。
         * grace 期内跳过退出评估；真急跌由 grace 结束后下一 tick 接管（grace 远短于周期，不影响兜底）。
         */
        private const val ENTRY_SETTLE_GRACE_MS: Long = 2000L
    }
}
