package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.entity.CryptoTailDecisionEvent
import com.wrbug.polymarketbot.entity.CryptoTailStrategyExit
import com.wrbug.polymarketbot.entity.CryptoTailStrategyTrigger
import com.wrbug.polymarketbot.enums.ExitStatus
import com.wrbug.polymarketbot.repository.CryptoTailStrategyExitRepository
import com.wrbug.polymarketbot.repository.CryptoTailStrategyRepository
import com.wrbug.polymarketbot.repository.CryptoTailStrategyTriggerRepository
import com.wrbug.polymarketbot.util.toJson
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import jakarta.annotation.PreDestroy
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 阶梯模式退出订单对账器（与 [CryptoTailMakerOrderService] 同款定时机制）。
 *
 * 职责：
 *  1) 扫描 [CryptoTailStrategyExit] 中 status='pending' 的所有退出单；
 *  2) 调 CLOB getOrder 查实际状态：
 *      FILLED/MATCHED → 全部成交，转 success；
 *      CANCELLED/EXPIRED + sizeMatched>0 → 部分成交，转 success；
 *      CANCELLED/EXPIRED + sizeMatched=0 → 转 cancelled；
 *      LIVE/DELAYED：
 *          GTC_POST_ONLY 且 距结算 <= makerCancelBeforeSettleSeconds → 撤单；
 *          否则保持 pending；
 *  3) exit 进入终态后同步对应 trigger 的 remainingSize / exitStatus（OPEN/PARTIAL_EXIT/FULLY_EXITED）；
 *  4) 推 BRACKET_EXIT_FILLED / BRACKET_EXIT_CANCELED 决策日志。
 *
 * 设计：FAK 退出的 MAKER 回退（cancel→重挂 FAK）不在此处实现，避免与 BracketExitService 决策权冲突；
 * MAKER 撤单后该 trigger 由 BracketExitService 在下次 onBestBid 重新评估，并由 hasExitOfKind 防死循环。
 */
@Service
class CryptoTailExitOrderReconciler(
    private val exitRepository: CryptoTailStrategyExitRepository,
    private val triggerRepository: CryptoTailStrategyTriggerRepository,
    private val strategyRepository: CryptoTailStrategyRepository,
    private val accountContextFactory: CryptoTailAccountContextFactory,
    private val decisionRecorder: CryptoTailDecisionRecorder
) {

    private val logger = LoggerFactory.getLogger(CryptoTailExitOrderReconciler::class.java)

    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + scopeJob)

    @Volatile
    private var reconcileJob: Job? = null

    /** 与进场 maker 同节奏 3s；上一轮未结束则跳过 */
    @Scheduled(fixedDelay = 3_000)
    fun scheduledReconcile() {
        val previous = reconcileJob
        if (previous != null && previous.isActive) {
            logger.debug("上一轮阶梯退出对账仍在执行，跳过本次调度")
            return
        }
        reconcileJob = scope.launch {
            try {
                reconcileAllPending()
            } catch (e: Exception) {
                logger.error("阶梯退出对账定时任务异常: ${e.message}", e)
            } finally {
                reconcileJob = null
            }
        }
    }

    private suspend fun reconcileAllPending() {
        val pendings = try {
            exitRepository.findByStatusOrderByCreatedAtAsc("pending")
        } catch (e: Exception) {
            logger.warn("查询 pending 退出单失败: ${e.message}")
            return
        }
        if (pendings.isEmpty()) return
        for (exit in pendings) {
            try {
                reconcileOne(exit)
            } catch (e: Exception) {
                logger.error("阶梯退出对账单条异常: exitId=${exit.id} orderId=${exit.orderId}, ${e.message}", e)
            }
        }
    }

    private suspend fun reconcileOne(exit: CryptoTailStrategyExit) {
        val orderId = exit.orderId
        // V53 修正：finalizeExit 决策事件需带 periodStartUnix 用于关联到具体周期；优先取 trigger 的，缺失时回退 0L
        val trigger = triggerRepository.findById(exit.triggerId).orElse(null)
        val periodStartUnix = trigger?.periodStartUnix ?: 0L
        if (orderId.isNullOrBlank()) {
            // 没有 orderId 的 pending 是异常状态，直接转 failed 防止永远 pending
            finalizeExit(exit, "failed", null, null, "缺少 orderId，无法对账", periodStartUnix)
            return
        }
        if (trigger == null) {
            finalizeExit(exit, "failed", null, null, "关联 trigger 不存在", periodStartUnix)
            return
        }
        val strategy = strategyRepository.findById(exit.strategyId).orElse(null)
        if (strategy == null) {
            finalizeExit(exit, "failed", null, null, "关联 strategy 不存在", periodStartUnix)
            return
        }
        val ctx = accountContextFactory.build(strategy)
        if (ctx == null) {
            logger.warn("阶梯退出对账无法构建账户上下文: exitId=${exit.id} accountId=${strategy.accountId}")
            return  // 暂不转终态，下次重试
        }

        val nowSec = System.currentTimeMillis() / 1000
        val settleAt = trigger.periodStartUnix + strategy.intervalSeconds
        val makerCancelDeadline = settleAt - strategy.makerCancelBeforeSettleSeconds

        val resp = try {
            ctx.clobApi.getOrder(orderId)
        } catch (e: Exception) {
            logger.warn("阶梯退出对账查单失败: exitId=${exit.id} orderId=$orderId, ${e.message}")
            null
        }
        val order = if (resp?.isSuccessful == true) resp.body() else null
        if (order == null) {
            // 查单失败：仅在已过结算时才放弃
            if (nowSec >= settleAt) {
                finalizeExit(exit, "failed", null, null, "订单状态查询失败且已过结算", periodStartUnix)
                syncTriggerAfterExitFinalized(trigger.id!!)
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
            finalizeExit(exit, "success", sizeMatched, fillAmount, null, periodStartUnix)
            syncTriggerAfterExitFinalized(trigger.id!!)
            return
        }
        if (st.contains("CANCEL") || st == "EXPIRED") {
            if (sizeMatched > BigDecimal.ZERO) {
                val fillAmount = sizeMatched.multiply(price).setScale(8, RoundingMode.HALF_UP)
                finalizeExit(exit, "success", sizeMatched, fillAmount, "已撤/过期，按部分成交结算", periodStartUnix)
            } else {
                finalizeExit(exit, "cancelled", null, null, "已撤/过期且零成交", periodStartUnix)
            }
            syncTriggerAfterExitFinalized(trigger.id!!)
            return
        }

        // 仍存活（LIVE/DELAYED 等）：MAKER 到期撤单
        val isMaker = (exit.orderType ?: "").uppercase() == "GTC_POST_ONLY" || (exit.orderType ?: "").uppercase() == "GTC"
        if (isMaker && nowSec >= makerCancelDeadline) {
            try {
                ctx.clobApi.cancelOrder(orderId)
            } catch (e: Exception) {
                logger.warn("阶梯退出对账撤单失败: exitId=${exit.id} orderId=$orderId, ${e.message}")
            }
            // 撤单后复查最后成交
            val afterMatched = try {
                val after = ctx.clobApi.getOrder(orderId)
                if (after.isSuccessful) after.body()?.sizeMatched?.toSafeBigDecimal() ?: sizeMatched else sizeMatched
            } catch (e: Exception) { sizeMatched }
            if (afterMatched > BigDecimal.ZERO) {
                val fillAmount = afterMatched.multiply(price).setScale(8, RoundingMode.HALF_UP)
                finalizeExit(exit, "success", afterMatched, fillAmount, "MAKER到期撤单，按部分成交结算", periodStartUnix)
            } else {
                finalizeExit(exit, "cancelled", null, null, "MAKER到期撤单且零成交", periodStartUnix)
            }
            syncTriggerAfterExitFinalized(trigger.id!!)
            return
        }

        // 未达撤单时点：保持 pending；如果有部分成交，刷新快照（不写终态）
        if (sizeMatched > BigDecimal.ZERO && (exit.filledSize ?: BigDecimal.ZERO).compareTo(sizeMatched) != 0) {
            val fillAmount = sizeMatched.multiply(price).setScale(8, RoundingMode.HALF_UP)
            exitRepository.save(exit.copy(filledSize = sizeMatched, filledAmount = fillAmount))
        }
    }

    /**
     * 把 exit 行写入终态（success/cancelled/failed），并写 settledAt。
     * V53 修正：periodStartUnix 由调用方传入（来自 trigger.periodStartUnix），
     * 之前固定写 0L 会让该决策事件无法按周期定位，与 BARRIER 链路 SETTLED 不一致。
     */
    private fun finalizeExit(
        exit: CryptoTailStrategyExit,
        status: String,
        filledSize: BigDecimal?,
        filledAmount: BigDecimal?,
        reason: String?,
        periodStartUnix: Long
    ) {
        val updated = exit.copy(
            status = status,
            filledSize = filledSize ?: exit.filledSize,
            filledAmount = filledAmount ?: exit.filledAmount,
            failReason = reason ?: exit.failReason,
            settledAt = System.currentTimeMillis()
        )
        exitRepository.save(updated)
        val resultType = when {
            status == "success" && (exit.exitKind == "TP1" || exit.exitKind == "TP2") -> "TAKE_PROFIT_RESULT"
            status == "success" && isStopLossKind(exit.exitKind) -> "STOP_LOSS_RESULT"
            status == "success" -> "EXIT_RESULT"
            else -> "EXIT_FAILED"
        }
        decisionRecorder.record(
            CryptoTailDecisionEvent(
                strategyId = exit.strategyId,
                periodStartUnix = periodStartUnix,
                correlationId = "${exit.strategyId}-$periodStartUnix-bracket-exit-${exit.id}",
                eventType = resultType,
                gateName = exit.exitKind,
                passed = status == "success",
                reason = reason ?: "exitId=${exit.id} status=$status",
                payloadJson = mapOf(
                    "exitId" to exit.id,
                    "exitKind" to exit.exitKind,
                    "orderId" to (exit.orderId ?: ""),
                    "orderType" to (exit.orderType ?: ""),
                    "targetSize" to exit.targetSize.toPlainString(),
                    "filledSize" to (filledSize?.toPlainString() ?: ""),
                    "filledAmount" to (filledAmount?.toPlainString() ?: ""),
                    "exitPrice" to (exit.exitPrice?.toPlainString() ?: ""),
                    "status" to status
                ).toJson(),
                outcomeIndex = null,
                triggerId = exit.triggerId
            )
        )
        logger.info("阶梯退出对账定夺: exitId=${exit.id} kind=${exit.exitKind} orderId=${exit.orderId} status=$status filled=${filledSize?.toPlainString()} reason=$reason")
    }

    private fun isStopLossKind(kind: String): Boolean =
        kind == "STOP" ||
            kind == "HARD_STOP" ||
            kind == "MODEL_INVALID" ||
            kind == "MODEL_FLIP" ||
            kind == "GAP_FLIP" ||
            kind == "TRAILING_STOP" ||
            kind == "WICK_REVERSAL"

    /**
     * 抢占式撤单（供 [CryptoTailBracketExitService] 急跌止损抢占调用）：
     * 立即撤掉一张 pending 退出单，复查实际成交后落终态（部分成交→success，零成交→cancelled），
     * 并同步 trigger.remainingSize/exitStatus。复用对账同款 [finalizeExit] / [syncTriggerAfterExitFinalized]，
     * 撤单后以链上/CLOB 实际 sizeMatched 为准回写 remaining，杜绝抢占后重复挂单导致的超卖。
     *
     * @return true=已落终态并同步（调用方可据最新 remaining 继续抢占下单）；false=上下文缺失，调用方应放弃本次抢占
     */
    suspend fun cancelPendingExitForPreempt(exit: CryptoTailStrategyExit): Boolean {
        val trigger = triggerRepository.findById(exit.triggerId).orElse(null) ?: return false
        val periodStartUnix = trigger.periodStartUnix
        val orderId = exit.orderId
        if (orderId.isNullOrBlank()) {
            finalizeExit(exit, "cancelled", null, null, "抢占撤单：缺少 orderId 视为未成交", periodStartUnix)
            syncTriggerAfterExitFinalized(trigger.id!!)
            return true
        }
        val strategy = strategyRepository.findById(exit.strategyId).orElse(null) ?: return false
        val ctx = accountContextFactory.build(strategy) ?: return false
        try {
            ctx.clobApi.cancelOrder(orderId)
        } catch (e: Exception) {
            logger.warn("抢占撤单失败: exitId=${exit.id} orderId=$orderId, ${e.message}")
        }
        // 撤单后复查最后成交（与 reconcileOne 同口径）
        val (matched, price) = try {
            val after = ctx.clobApi.getOrder(orderId)
            if (after.isSuccessful) {
                val b = after.body()
                (b?.sizeMatched?.toSafeBigDecimal() ?: BigDecimal.ZERO) to
                    (b?.price?.toSafeBigDecimal() ?: (exit.exitPrice ?: BigDecimal.ZERO))
            } else {
                BigDecimal.ZERO to (exit.exitPrice ?: BigDecimal.ZERO)
            }
        } catch (e: Exception) {
            (exit.filledSize ?: BigDecimal.ZERO) to (exit.exitPrice ?: BigDecimal.ZERO)
        }
        if (matched > BigDecimal.ZERO) {
            val fillAmount = matched.multiply(price).setScale(8, RoundingMode.HALF_UP)
            finalizeExit(exit, "success", matched, fillAmount, "抢占撤单：按部分成交结算", periodStartUnix)
        } else {
            finalizeExit(exit, "cancelled", null, null, "抢占撤单：零成交", periodStartUnix)
        }
        syncTriggerAfterExitFinalized(trigger.id!!)
        logger.info("急跌止损抢占撤单完成: exitId=${exit.id} triggerId=${trigger.id} orderId=$orderId matched=${matched.toPlainString()}")
        return true
    }

    /**
     * 同步 trigger 的 remainingSize 与 exitStatus：
     *  - sumFilledSize >= 入场 filledSize（或残差 <= DUST_THRESHOLD） → FULLY_EXITED, remainingSize=0
     *  - 0 < sumFilledSize < 入场 filledSize 且残差 > DUST_THRESHOLD → PARTIAL_EXIT
     *  - sumFilledSize == 0 → 保持原 exitStatus（OPEN，重试后续退出）
     *
     * V53 dust 容差：入场 filledSize 8 位精度 vs 退出 targetSize setScale(2, DOWN) 的精度差，
     * 残差永远凑不齐 0；增加 DUST_THRESHOLD=0.01（与 size 精度对齐）保证 FULLY_EXITED 可达。
     */
    private fun syncTriggerAfterExitFinalized(triggerId: Long) {
        val trigger = triggerRepository.findById(triggerId).orElse(null) ?: return
        val totalFilled = exitRepository.sumFilledSizeByTriggerId(triggerId) ?: BigDecimal.ZERO
        val originalSize = trigger.filledSize ?: return
        val rawRemaining = originalSize.subtract(totalFilled).max(BigDecimal.ZERO)
        val isDust = rawRemaining <= DUST_THRESHOLD
        val newRemaining = if (isDust) BigDecimal.ZERO else rawRemaining
        val newStatus = when {
            isDust -> ExitStatus.FULLY_EXITED.name
            totalFilled > BigDecimal.ZERO -> ExitStatus.PARTIAL_EXIT.name
            else -> trigger.exitStatus
        }
        val curRemaining = trigger.remainingSize ?: BigDecimal.ZERO
        if (newRemaining.compareTo(curRemaining) != 0 || newStatus != trigger.exitStatus) {
            triggerRepository.save(trigger.copy(remainingSize = newRemaining, exitStatus = newStatus))
            if (newStatus == ExitStatus.FULLY_EXITED.name) {
                decisionRecorder.record(
                    CryptoTailDecisionEvent(
                        strategyId = trigger.strategyId,
                        periodStartUnix = trigger.periodStartUnix,
                        correlationId = "${trigger.strategyId}-${trigger.periodStartUnix}-position-${trigger.id}",
                        eventType = "POSITION_CLOSED",
                        gateName = null,
                        passed = true,
                        reason = "仓位已全部退出",
                        payloadJson = mapOf(
                            "positionId" to trigger.id,
                            "remainingSize" to newRemaining.toPlainString(),
                            "totalFilledSize" to totalFilled.toPlainString()
                        ).toJson(),
                        outcomeIndex = trigger.outcomeIndex,
                        triggerId = trigger.id
                    )
                )
            }
            logger.info(
                "阶梯持仓状态同步: triggerId=$triggerId remaining=${newRemaining.toPlainString()} " +
                    "exitStatus=$newStatus (totalFilled=${totalFilled.toPlainString()}, rawRemaining=${rawRemaining.toPlainString()}, dust=$isDust)"
            )
        }
    }

    @PreDestroy
    fun destroy() {
        scopeJob.cancel()
    }

    companion object {
        /**
         * V53 dust 容差：与 BracketExitService 的 sizeDecimalScale=2、SettlementService.BRACKET_DUST_THRESHOLD 对齐。
         * 当残余 size <= 0.01 时视为已全部退出，避免 8 位入场精度 vs 2 位退出精度永远凑不齐 0。
         */
        private val DUST_THRESHOLD: BigDecimal = BigDecimal("0.01")
    }
}
