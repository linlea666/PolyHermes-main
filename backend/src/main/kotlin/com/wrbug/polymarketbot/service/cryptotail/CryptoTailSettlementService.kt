package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.api.GammaEventBySlugResponse
import com.wrbug.polymarketbot.api.PolymarketDataApi
import com.wrbug.polymarketbot.entity.CryptoTailDecisionEvent
import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import com.wrbug.polymarketbot.entity.CryptoTailStrategyTrigger
import com.wrbug.polymarketbot.enums.ExitStatus
import com.wrbug.polymarketbot.enums.TradingMode
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CryptoTailStrategyExitRepository
import com.wrbug.polymarketbot.repository.CryptoTailStrategyRepository
import com.wrbug.polymarketbot.repository.CryptoTailStrategyTriggerRepository
import com.wrbug.polymarketbot.service.binance.BinanceKlineAutoSpreadService
import com.wrbug.polymarketbot.service.binance.BinanceKlineService
import com.wrbug.polymarketbot.service.common.BlockchainService
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.gt
import com.wrbug.polymarketbot.util.multi
import com.wrbug.polymarketbot.util.toJson
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import jakarta.annotation.PreDestroy
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 加密价差策略结算轮询服务
 * 定时扫描「状态成功但未结算」的触发记录，通过 Gamma 获取 conditionId、链上查询结算结果，计算收益并回写。
 * 实际成交价与成交量使用 Data API 的 activity 接口获取（getUserActivity），比 CLOB getOrder 更准确；失败时回退为触发时的 amountUsdc + 固定价 0.99。
 */
@Service
class CryptoTailSettlementService(
    private val triggerRepository: CryptoTailStrategyTriggerRepository,
    private val strategyRepository: CryptoTailStrategyRepository,
    private val accountRepository: AccountRepository,
    private val retrofitFactory: RetrofitFactory,
    private val blockchainService: BlockchainService,
    private val decisionRecorder: CryptoTailDecisionRecorder,
    private val binanceKlineService: BinanceKlineService,
    private val binanceKlineAutoSpreadService: BinanceKlineAutoSpreadService,
    private val periodPriceProvider: PeriodPriceProvider,
    private val exitRepository: CryptoTailStrategyExitRepository
) {

    private val logger = LoggerFactory.getLogger(CryptoTailSettlementService::class.java)

    private val triggerFixedPrice = BigDecimal("0.99")
    private val pnlScale = 8

    /**
     * V53 BRACKET dust 容差：当剩余 size <= 0.01（与 Polymarket size 精度 setScale(2) 对齐）时
     * 视为已全部退出，走情况 A（无须链上 condition 兜底）。避免入场 8 位精度 vs 退出 2 位精度
     * 残差导致 FULLY_EXITED 永远不可达 + 强行走链上结算的损耗。
     */
    private val BRACKET_DUST_THRESHOLD: BigDecimal = BigDecimal("0.01")

    private val settlementScopeJob = SupervisorJob()
    private val settlementScope = CoroutineScope(Dispatchers.IO + settlementScopeJob)

    /** 跟踪上一轮结算任务的 Job，防止并发执行（与 OrderStatusUpdateService 一致） */
    @Volatile
    private var settlementJob: Job? = null

    /**
     * 定时轮询：每 10 秒执行一次。
     * 若上一轮任务仍在执行则跳过本次，避免并发重叠。
     */
    @Scheduled(fixedDelay = 10_000)
    fun scheduledPollAndSettle() {
        val previousJob = settlementJob
        if (previousJob != null && previousJob.isActive) {
            logger.debug("上一轮加密价差策略结算任务仍在执行，跳过本次调度")
            return
        }
        settlementJob = settlementScope.launch {
            try {
                doPollAndSettle()
            } catch (e: Exception) {
                logger.error("加密价差策略结算定时任务异常: ${e.message}", e)
            } finally {
                settlementJob = null
            }
        }
    }

    /**
     * 轮询入口：拉取所有 status=success 且 resolved=false 的触发记录，逐条尝试结算并更新。
     * Controller/定时任务调用此方法（内部对 suspend 使用 runBlocking）。
     */
    @Transactional
    fun pollAndSettle(): Int = runBlocking {
        doPollAndSettle()
    }

    private suspend fun doPollAndSettle(): Int {
        val pending = triggerRepository.findByStatusAndResolvedAndOrderIdIsNotNullOrderByCreatedAtAsc("success", false)
        if (pending.isEmpty()) return 0
        var settledCount = 0
        for (trigger in pending) {
            try {
                if (settleOne(trigger)) settledCount++
            } catch (e: Exception) {
                logger.warn("加密价差策略结算单条失败: triggerId=${trigger.id}, ${e.message}", e)
            }
        }
        if (settledCount > 0) {
            logger.info("加密价差策略结算轮询完成: 处理=${pending.size}, 新结算=$settledCount")
        }
        return settledCount
    }

    /**
     * 处理单条触发记录：解析 conditionId -> 查链上结算 -> 若已结算则计算 pnl 并更新。
     * 通过 copy() 生成新实体再 save，不直接修改原实体；实际成交价与投入金额从 Data API activity 获取并更新 triggerPrice、amountUsdc。
     * @return true 表示本条已结算并更新
     */
    private suspend fun settleOne(trigger: CryptoTailStrategyTrigger): Boolean {
        if (trigger.resolved) return false
        val strategy = strategyRepository.findById(trigger.strategyId).orElse(null) ?: return false
        // 阶梯模式独立结算路径：FULLY_EXITED 直接根据 exits 求和；
        // 部分平仓 / HELD_TO_SETTLE 等到周期结束后取 condition + exits 合并结算
        if (trigger.mode == TradingMode.BRACKET_DYNAMIC) {
            return settleBracketTrigger(trigger, strategy)
        }
        val conditionId = resolveConditionId(strategy, trigger) ?: return false
        // 成交量/成本优先用下单响应的真实成交（最权威、即时、不依赖 Activity WS）；其次 Activity；都无则回退
        val activityFill = fetchActivityFill(trigger, strategy, conditionId)
        val fs = trigger.filledSize
        val fa = trigger.filledAmount
        val resolved: ResolvedFill? = when {
            fs != null && fa != null && fs.gt(BigDecimal.ZERO) && fa.gt(BigDecimal.ZERO) ->
                ResolvedFill(fs, fa, fa.divide(fs, pnlScale, RoundingMode.HALF_UP), "ORDER_RESPONSE")
            activityFill != null && activityFill.price.gt(BigDecimal.ZERO) && activityFill.size.gt(BigDecimal.ZERO) -> {
                val cost = activityFill.usdcSize?.takeIf { it.gt(BigDecimal.ZERO) }
                    ?: activityFill.price.multi(activityFill.size).setScale(pnlScale, RoundingMode.HALF_UP)
                ResolvedFill(activityFill.size, cost, activityFill.price, "ACTIVITY_API")
            }
            else -> null
        }
        val newTriggerPrice = resolved?.price ?: trigger.triggerPrice
        val newAmountUsdc = resolved?.cost ?: trigger.amountUsdc

        val (_, payouts) = blockchainService.getCondition(conditionId).getOrNull() ?: run {
            if (resolved != null) {
                val updated = trigger.copy(triggerPrice = newTriggerPrice, amountUsdc = newAmountUsdc)
                triggerRepository.save(updated)
            }
            return false
        }
        if (payouts.isEmpty()) {
            if (resolved != null) {
                val updated = trigger.copy(triggerPrice = newTriggerPrice, amountUsdc = newAmountUsdc)
                triggerRepository.save(updated)
            }
            return false
        }
        val winnerIndex = payouts.indexOfFirst { it == java.math.BigInteger.ONE }
        if (winnerIndex < 0) return false

        val won = trigger.outcomeIndex == winnerIndex
        // 归因以实际 payout 为准并净额化(C-5)：
        //  毛盈亏：胜=份额×$1−成本(份额−成本)；负=−成本
        //  净额化：扣 gas（每笔 gasCostUsdc）；taker 单扣手续费(成本×takerFeeBps/10000)，maker 单加返佣(成本×makerRebateBps/10000)
        val grossPnl = if (resolved != null) {
            (if (won) resolved.size.subtract(resolved.cost) else resolved.cost.negate()).setScale(pnlScale, RoundingMode.HALF_UP)
        } else {
            computePnlFallback(trigger.amountUsdc, won)
        }
        val feeAdj = if (resolved != null) feeAdjustment(strategy, trigger, resolved.cost) else BigDecimal.ZERO
        val pnl = grossPnl.add(feeAdj).setScale(pnlScale, RoundingMode.HALF_UP)
        val now = System.currentTimeMillis()

        val updated = trigger.copy(
            triggerPrice = newTriggerPrice,
            amountUsdc = newAmountUsdc,
            conditionId = conditionId,
            resolved = true,
            winnerOutcomeIndex = winnerIndex,
            realizedPnl = pnl,
            settledAt = now
        )
        triggerRepository.save(updated)
        logger.debug("加密价差策略结算已更新: triggerId=${trigger.id}, winnerOutcomeIndex=$winnerIndex, won=$won, pnl=$pnl")

        // V53 修正：BARRIER + BRACKET（任意非 LEGACY 模式）都记录结算结果到全链路决策日志（链路终点），
        // 并携带最终 K 线用于反转判定与复盘。此前用 strategy.barrierEnabled 会让 BRACKET 完全丢失 SETTLED 日志。
        if (strategy.mode != TradingMode.LEGACY_SPREAD) {
            val finalOc = getFinalOpenClose(strategy, trigger.periodStartUnix)
            val settleSource = resolved?.source ?: "FALLBACK"
            val payload = mutableMapOf<String, Any?>(
                "won" to won,
                "winnerOutcomeIndex" to winnerIndex,
                "outcomeIndex" to trigger.outcomeIndex,
                "realizedPnl" to pnl.toPlainString(),
                "grossPnl" to grossPnl.toPlainString(),
                "feeAdjustment" to feeAdj.toPlainString(),
                "orderType" to (trigger.orderType ?: ""),
                "amountUsdc" to newAmountUsdc.toPlainString(),
                "fillPrice" to newTriggerPrice.toPlainString(),
                "fillSize" to (resolved?.size?.toPlainString() ?: ""),
                "settleTs" to now.toString(),
                "settleSource" to settleSource,
                "mode" to strategy.mode.name
            )
            if (finalOc != null) {
                val (fOpen, fClose) = finalOc
                payload["finalOpen"] = fOpen.toPlainString()
                payload["finalClose"] = fClose.toPlainString()
                payload["finalGap"] = fClose.subtract(fOpen).toPlainString()
                payload["finalPriceSource"] = if (strategy.mode != TradingMode.LEGACY_SPREAD) "CHAINLINK" else "BINANCE"
            }
            decisionRecorder.record(
                CryptoTailDecisionEvent(
                    strategyId = trigger.strategyId,
                    periodStartUnix = trigger.periodStartUnix,
                    correlationId = "${trigger.strategyId}-${trigger.periodStartUnix}",
                    eventType = "SETTLED",
                    gateName = null,
                    passed = won,
                    reason = if (won) "结算获胜" else "结算失败",
                    payloadJson = payload.toJson(),
                    outcomeIndex = trigger.outcomeIndex,
                    triggerId = trigger.id
                )
            )
        }
        return true
    }

    /** 退出费用按笔细分（V53 修正口径） */
    private data class ExitFeeBreakdown(
        val exitCount: Int,
        val makerCount: Int,
        val takerCount: Int,
        /** maker 返佣总额（正数），未发生为 0 */
        val makerRebateTotal: BigDecimal,
        /** taker 手续费总额（正数），未发生为 0 */
        val takerFeeTotal: BigDecimal,
        /** 累计 gas（正数 = exitCount × gasCostUsdc） */
        val gasTotal: BigDecimal
    ) {
        /** 净费用调整：maker 返佣 - taker 手续费 - gas（与 PnL 直接相加） */
        val totalAdjustment: BigDecimal
            get() = makerRebateTotal.subtract(takerFeeTotal).subtract(gasTotal)
    }

    /**
     * 按笔遍历 success exits，区分 maker(GTC*) / taker(FAK 或其它) 计费，并累计每笔 gas。
     * 修正 V53 之前 settleBracketTrigger 对所有 exit 强制按 takerFeeBps 一次性扣 + 漏算 N−1 次 gas 的口径错误。
     */
    private fun computeExitFeeBreakdown(
        strategy: CryptoTailStrategy,
        exits: List<com.wrbug.polymarketbot.entity.CryptoTailStrategyExit>
    ): ExitFeeBreakdown {
        var makerCount = 0
        var takerCount = 0
        var makerRebate = BigDecimal.ZERO
        var takerFee = BigDecimal.ZERO
        for (e in exits) {
            val amount = e.filledAmount ?: BigDecimal.ZERO
            if (amount <= BigDecimal.ZERO) continue
            // 统一以 orderType.startsWith("GTC") 判定 maker（含 GTC_POST_ONLY 等变体），与 feeAdjustment 一致
            val isMaker = (e.orderType ?: "").uppercase().startsWith("GTC")
            if (isMaker) {
                makerCount++
                makerRebate = makerRebate.add(
                    amount.multiply(BigDecimal(strategy.makerRebateBps))
                        .divide(BigDecimal(10000), pnlScale, RoundingMode.HALF_UP)
                )
            } else {
                takerCount++
                takerFee = takerFee.add(
                    amount.multiply(BigDecimal(strategy.takerFeeBps))
                        .divide(BigDecimal(10000), pnlScale, RoundingMode.HALF_UP)
                )
            }
        }
        val exitCount = makerCount + takerCount
        // 每笔 exit 都触发一次 gas（与 BARRIER 单笔结算同口径），累计 N 笔
        val gasTotal = strategy.gasCostUsdc.multiply(BigDecimal(exitCount))
            .setScale(pnlScale, RoundingMode.HALF_UP)
        return ExitFeeBreakdown(exitCount, makerCount, takerCount, makerRebate, takerFee, gasTotal)
    }

    /**
     * 阶梯模式结算：
     *  - 入场 cost = trigger.filledAmount（USDC，必有，因为入场成交才会走到此路径）
     *  - exits 合计 cost = sum(filled_amount of success exits)，盈亏即 exits - 入场对应的 cost 部分
     *  - 三种情况：
     *      A. FULLY_EXITED：全部通过盘口卖出，无需链上 condition；realizedPnl = sumExits - cost + 费用调整
     *      B. PARTIAL_EXIT/OPEN/HELD_TO_SETTLE 且周期已结束：剩余 remainingSize 走 condition 结算，
     *         合并 exits 部分；同时把 exitStatus 写为 HELD_TO_SETTLE
     *      C. PARTIAL_EXIT/OPEN 且周期未结束：暂不结算（等 BracketExitService/对账继续推进）
     *
     * V53 费用口径修正：exits 按笔遍历区分 maker 返佣 / taker 手续费 + 每笔单独一次 gas。
     */
    private suspend fun settleBracketTrigger(trigger: CryptoTailStrategyTrigger, strategy: CryptoTailStrategy): Boolean {
        val triggerId = trigger.id ?: return false
        val originalSize = trigger.filledSize ?: return false
        val originalCost = trigger.filledAmount ?: return false
        if (originalSize <= BigDecimal.ZERO || originalCost <= BigDecimal.ZERO) return false

        val now = System.currentTimeMillis()
        val periodEndMs = (trigger.periodStartUnix + strategy.intervalSeconds) * 1000L

        val successExits = exitRepository.findByTriggerIdAndStatus(triggerId, "success")
        val sumExitFilledSize = successExits.fold(BigDecimal.ZERO) { acc, e ->
            acc.add(e.filledSize ?: BigDecimal.ZERO)
        }
        val sumExitFilledAmount = successExits.fold(BigDecimal.ZERO) { acc, e ->
            acc.add(e.filledAmount ?: BigDecimal.ZERO)
        }
        val effectiveRemaining = originalSize.subtract(sumExitFilledSize).max(BigDecimal.ZERO)
        val exitBreakdown = computeExitFeeBreakdown(strategy, successExits)

        // 情况 A：全部从盘口退出（含 dust 容差，由 dust-fully-exited 任务统一处理）
        if (effectiveRemaining <= BRACKET_DUST_THRESHOLD || trigger.exitStatus == ExitStatus.FULLY_EXITED.name) {
            val grossPnl = sumExitFilledAmount.subtract(originalCost).setScale(pnlScale, RoundingMode.HALF_UP)
            val entryFeeAdj = feeAdjustment(strategy, trigger, originalCost)
            val exitFeeAdj = exitBreakdown.totalAdjustment
            val pnl = grossPnl.add(entryFeeAdj).add(exitFeeAdj).setScale(pnlScale, RoundingMode.HALF_UP)
            val updated = trigger.copy(
                resolved = true,
                winnerOutcomeIndex = null,
                realizedPnl = pnl,
                settledAt = now,
                remainingSize = BigDecimal.ZERO,
                exitStatus = ExitStatus.FULLY_EXITED.name
            )
            triggerRepository.save(updated)
            recordBracketSettled(strategy, updated, won = null, grossPnl, pnl, entryFeeAdj.add(exitFeeAdj),
                source = "EXITS_ONLY", sumExitFilledSize, sumExitFilledAmount, effectiveRemaining,
                exitBreakdown = exitBreakdown, entryFeeAdj = entryFeeAdj)
            logger.info(
                "阶梯结算(全部盘口退出): triggerId=$triggerId pnl=${pnl.toPlainString()} " +
                    "sumExits=${sumExitFilledAmount.toPlainString()} cost=${originalCost.toPlainString()} " +
                    "exitCount=${exitBreakdown.exitCount} maker=${exitBreakdown.makerCount} taker=${exitBreakdown.takerCount}"
            )
            return true
        }

        // 情况 C：周期未结束，等待
        if (now < periodEndMs) {
            return false
        }

        // 情况 B：周期已结束 + 还有 remainingSize → 链上 condition 结算 + 合并 exits
        val conditionId = resolveConditionId(strategy, trigger) ?: return false
        val (_, payouts) = blockchainService.getCondition(conditionId).getOrNull() ?: return false
        if (payouts.isEmpty()) return false
        val winnerIndex = payouts.indexOfFirst { it == java.math.BigInteger.ONE }
        if (winnerIndex < 0) return false
        val won = trigger.outcomeIndex == winnerIndex

        // 残余成本按入场均价比例分摊
        val avgEntryPrice = originalCost.divide(originalSize, pnlScale, RoundingMode.HALF_UP)
        val remainingCost = effectiveRemaining.multiply(avgEntryPrice).setScale(pnlScale, RoundingMode.HALF_UP)
        val onChainGross = if (won) effectiveRemaining.subtract(remainingCost) else remainingCost.negate()

        // exits 部分 PnL：sumExitFilledAmount(USDC收入) - exits 对应的入场成本
        val exitsCost = originalCost.subtract(remainingCost).max(BigDecimal.ZERO)
        val exitsGross = sumExitFilledAmount.subtract(exitsCost)
        val grossPnl = onChainGross.add(exitsGross).setScale(pnlScale, RoundingMode.HALF_UP)

        // 入场费 + exits 按笔费用（区分 maker/taker + 每笔 gas）
        val entryFeeAdj = feeAdjustment(strategy, trigger, originalCost)
        val exitFeeAdj = exitBreakdown.totalAdjustment
        val pnl = grossPnl.add(entryFeeAdj).add(exitFeeAdj).setScale(pnlScale, RoundingMode.HALF_UP)

        val updated = trigger.copy(
            conditionId = conditionId,
            resolved = true,
            winnerOutcomeIndex = winnerIndex,
            realizedPnl = pnl,
            settledAt = now,
            remainingSize = effectiveRemaining,
            exitStatus = ExitStatus.HELD_TO_SETTLE.name
        )
        triggerRepository.save(updated)
        recordBracketSettled(strategy, updated, won, grossPnl, pnl, entryFeeAdj.add(exitFeeAdj),
            source = "EXITS+ONCHAIN", sumExitFilledSize, sumExitFilledAmount, effectiveRemaining,
            exitBreakdown = exitBreakdown, entryFeeAdj = entryFeeAdj)
        logger.info(
            "阶梯结算(残余链上+盘口): triggerId=$triggerId pnl=${pnl.toPlainString()} won=$won " +
                "remaining=${effectiveRemaining.toPlainString()} sumExits=${sumExitFilledAmount.toPlainString()} " +
                "exitCount=${exitBreakdown.exitCount} maker=${exitBreakdown.makerCount} taker=${exitBreakdown.takerCount}"
        )
        return true
    }

    /** 阶梯模式结算决策日志（与 BARRIER 的 SETTLED 同 type，payload 携带阶梯特有字段 + V53 exitFeeBreakdown） */
    private fun recordBracketSettled(
        strategy: CryptoTailStrategy,
        trigger: CryptoTailStrategyTrigger,
        won: Boolean?,
        grossPnl: BigDecimal,
        pnl: BigDecimal,
        feeAdj: BigDecimal,
        source: String,
        sumExitFilledSize: BigDecimal,
        sumExitFilledAmount: BigDecimal,
        remainingSize: BigDecimal,
        exitBreakdown: ExitFeeBreakdown,
        entryFeeAdj: BigDecimal
    ) {
        val payload = mutableMapOf<String, Any?>(
            "mode" to "BRACKET_DYNAMIC",
            "won" to (won?.toString() ?: "n/a"),
            "winnerOutcomeIndex" to (trigger.winnerOutcomeIndex?.toString() ?: ""),
            "outcomeIndex" to trigger.outcomeIndex,
            "realizedPnl" to pnl.toPlainString(),
            "grossPnl" to grossPnl.toPlainString(),
            "feeAdjustment" to feeAdj.toPlainString(),
            "originalCost" to (trigger.filledAmount?.toPlainString() ?: ""),
            "originalSize" to (trigger.filledSize?.toPlainString() ?: ""),
            "sumExitFilledSize" to sumExitFilledSize.toPlainString(),
            "sumExitFilledAmount" to sumExitFilledAmount.toPlainString(),
            "remainingSize" to remainingSize.toPlainString(),
            "exitStatus" to trigger.exitStatus,
            "settleSource" to source,
            // V53 新增：按笔细分的费用结构，便于事后复盘各档位 maker/taker 占比与 gas 影响
            "exitFeeBreakdown" to mapOf(
                "exitCount" to exitBreakdown.exitCount,
                "makerCount" to exitBreakdown.makerCount,
                "takerCount" to exitBreakdown.takerCount,
                "makerRebateTotal" to exitBreakdown.makerRebateTotal.toPlainString(),
                "takerFeeTotal" to exitBreakdown.takerFeeTotal.toPlainString(),
                "gasTotal" to exitBreakdown.gasTotal.toPlainString(),
                "totalAdjustment" to exitBreakdown.totalAdjustment.toPlainString(),
                "entryFeeAdjustment" to entryFeeAdj.toPlainString()
            )
        )
        decisionRecorder.record(
            CryptoTailDecisionEvent(
                strategyId = trigger.strategyId,
                periodStartUnix = trigger.periodStartUnix,
                correlationId = "${trigger.strategyId}-${trigger.periodStartUnix}",
                eventType = "SETTLED",
                gateName = null,
                passed = won,
                reason = when {
                    won == true -> "阶梯结算获胜"
                    won == false -> "阶梯结算失败"
                    else -> "阶梯结算(全部盘口退出)"
                },
                payloadJson = payload.toJson(),
                outcomeIndex = trigger.outcomeIndex,
                triggerId = trigger.id
            )
        )
    }

    /**
     * 取结算周期的最终 (open, close)。
     * V53 修正：障碍/阶梯模式（任意非 LEGACY）都用 Chainlink（与 Polymarket 结算源一致，按精确时间戳取窗口期初/期末），
     * 不可用则返回 null（不造假、不回退币安）。LEGACY 模式沿用币安进程内缓存/REST 兜底。
     */
    private fun getFinalOpenClose(strategy: CryptoTailStrategy, periodStartUnix: Long): Pair<BigDecimal, BigDecimal>? {
        if (strategy.mode != TradingMode.LEGACY_SPREAD) {
            return periodPriceProvider.getFinalOpenClose(strategy.marketSlugPrefix, strategy.intervalSeconds, periodStartUnix)
        }
        return binanceKlineService.getCurrentOpenClose(strategy.marketSlugPrefix, strategy.intervalSeconds, periodStartUnix)
            ?: binanceKlineAutoSpreadService.fetchPeriodOpenClose(strategy.marketSlugPrefix, strategy.intervalSeconds, periodStartUnix)
    }

    private suspend fun resolveConditionId(strategy: CryptoTailStrategy, trigger: CryptoTailStrategyTrigger): String? {
        if (!trigger.conditionId.isNullOrBlank()) return trigger.conditionId
        val slug = "${strategy.marketSlugPrefix}-${trigger.periodStartUnix}"
        val event = fetchEventBySlug(slug).getOrNull() ?: return null
        val markets = event.markets ?: return null
        val first = markets.firstOrNull() ?: return null
        return first.conditionId?.takeIf { it.isNotBlank() }
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

    /**
     * Activity 匹配到的一条 TRADE 的成交数据：价格、数量、实际投入 USDC（接口 usdcSize）。
     */
    private data class ActivityFill(
        val price: BigDecimal,
        val size: BigDecimal,
        val usdcSize: BigDecimal?
    )

    /** 统一后的成交结论：份额、成本(USDC)、均价、来源（ORDER_RESPONSE 最权威 > ACTIVITY_API） */
    private data class ResolvedFill(
        val size: BigDecimal,
        val cost: BigDecimal,
        val price: BigDecimal,
        val source: String
    )

    /**
     * 通过 Data API activity 接口获取该触发对应的实际成交价、成交量与投入金额（比 CLOB getOrder 更准确）。
     * 只有此接口返回匹配的 TRADE 且 price/size 有效时，结算才会更新 triggerPrice、amountUsdc（表现）；投入金额优先用 activity 的 usdcSize。
     */
    private suspend fun fetchActivityFill(
        trigger: CryptoTailStrategyTrigger,
        strategy: CryptoTailStrategy,
        conditionId: String
    ): ActivityFill? {
        val account = accountRepository.findById(strategy.accountId).orElse(null) ?: run {
            logger.warn("加密价差策略结算未拉取 activity: 账户不存在, triggerId=${trigger.id}, accountId=${strategy.accountId}")
            return null
        }
        val user = account.proxyAddress
        val triggerTimeSeconds = trigger.createdAt / 1000
        val start = triggerTimeSeconds - 120
        val end = triggerTimeSeconds + 600
        return try {
            val dataApi = retrofitFactory.createDataApi()
            val response = dataApi.getUserActivity(
                user = user,
                type = listOf("TRADE"),
                start = start,
                end = end,
                limit = 50,
                sortBy = "TIMESTAMP",
                sortDirection = "DESC"
            )
            if (!response.isSuccessful || response.body() == null) {
                logger.warn("加密价差策略结算拉取 activity 失败: triggerId=${trigger.id}, code=${response.code()}")
                return null
            }
            val activities = response.body()!!
            // 只匹配 TRADE：返回里可能混有 REDEEM（outcomeIndex=999、price=0）等，需排除
            val match = activities.firstOrNull { a ->
                a.type == "TRADE" &&
                    a.conditionId == conditionId &&
                    a.outcomeIndex != null && a.outcomeIndex in 0..1 &&
                    a.outcomeIndex == trigger.outcomeIndex &&
                    a.side?.uppercase() == "BUY" &&
                    a.price != null && a.price > 0 &&
                    a.size != null && a.size > 0
            } ?: run {
                logger.debug("加密价差策略结算 activity 无匹配成交: triggerId=${trigger.id}, conditionId=$conditionId, outcomeIndex=${trigger.outcomeIndex}, 条数=${activities.size}")
                return null
            }
            val price = match.price!!.toSafeBigDecimal()
            val size = match.size!!.toSafeBigDecimal()
            val usdcSize = match.usdcSize?.toSafeBigDecimal()?.takeIf { it.gt(BigDecimal.ZERO) }
            if (price.gt(BigDecimal.ZERO) && size.gt(BigDecimal.ZERO)) {
                ActivityFill(price = price, size = size, usdcSize = usdcSize)
            } else {
                logger.debug("加密价差策略结算 activity 成交数据无效: triggerId=${trigger.id}, price=$price, size=$size")
                null
            }
        } catch (e: Exception) {
            logger.warn("加密价差策略结算拉取 activity 异常，触发价/投入金额不会更新: triggerId=${trigger.id}, error=${e.message}")
            null
        }
    }

    /**
     * 按实际成交价与成交量计算收益：成本 = sizeMatched * price；赢则赎回 sizeMatched * 1，输则 0。
     */
    private fun computePnlFromFill(price: BigDecimal, sizeMatched: BigDecimal, won: Boolean): BigDecimal {
        val cost = sizeMatched.multi(price).setScale(pnlScale, RoundingMode.HALF_UP)
        return if (won) {
            sizeMatched.subtract(cost).setScale(pnlScale, RoundingMode.HALF_UP)
        } else {
            cost.negate()
        }
    }

    /**
     * 回退收益计算：无 API 数据时用触发时的 amountUsdc 与固定价 0.99。
     * 赢: pnl = amountUsdc/0.99 - amountUsdc；输: pnl = -amountUsdc
     */
    private fun computePnlFallback(amountUsdc: BigDecimal, won: Boolean): BigDecimal {
        return if (won) {
            amountUsdc.divide(triggerFixedPrice, pnlScale, RoundingMode.HALF_UP).subtract(amountUsdc)
        } else {
            amountUsdc.negate()
        }
    }

    /**
     * 净额化费用调整（与 EV 闸口径一致，C-5）：
     *  - maker 单(GTC_POST_ONLY)：+返佣 cost×makerRebateBps/10000
     *  - taker 单(FAK/其它)：−手续费 cost×takerFeeBps/10000
     *  - 统一再扣每笔 gas（gasCostUsdc）
     */
    private fun feeAdjustment(strategy: CryptoTailStrategy, trigger: CryptoTailStrategyTrigger, cost: BigDecimal): BigDecimal {
        val isMaker = (trigger.orderType ?: "").uppercase().startsWith("GTC")
        val feeOrRebate = if (isMaker) {
            cost.multiply(BigDecimal(strategy.makerRebateBps)).divide(BigDecimal(10000), pnlScale, RoundingMode.HALF_UP)
        } else {
            cost.multiply(BigDecimal(strategy.takerFeeBps)).divide(BigDecimal(10000), pnlScale, RoundingMode.HALF_UP).negate()
        }
        return feeOrRebate.subtract(strategy.gasCostUsdc)
    }

    @PreDestroy
    fun destroy() {
        settlementJob?.cancel()
        settlementJob = null
        settlementScopeJob.cancel()
    }
}
