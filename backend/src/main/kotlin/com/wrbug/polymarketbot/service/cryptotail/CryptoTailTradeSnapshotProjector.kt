package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.entity.CryptoTailDecisionEvent
import com.wrbug.polymarketbot.entity.CryptoTailTradeSnapshot
import com.wrbug.polymarketbot.event.CryptoTailDecisionRecordedEvent
import com.wrbug.polymarketbot.repository.CryptoTailTradeSnapshotRepository
import com.wrbug.polymarketbot.util.fromJson
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap

/**
 * 单笔成交全链路分析快照投影器（解耦插件式）。
 * 异步消费决策事件流（ORDER_SUBMITTED/ORDER_RESULT/SETTLED），按 (strategyId, periodStartUnix) upsert 强类型快照，
 * 仅作回测/复盘投影，绝不影响下单/结算热路径。仅在决策日志开关开启时生效。
 */
@Component
@ConditionalOnProperty(name = ["crypto-tail.decision-log.enabled"], havingValue = "true", matchIfMissing = true)
class CryptoTailTradeSnapshotProjector(
    private val snapshotRepository: CryptoTailTradeSnapshotRepository
) {
    private val logger = LoggerFactory.getLogger(CryptoTailTradeSnapshotProjector::class.java)

    /** 按 correlationId 串行化同周期事件，避免并发 upsert 丢更新（@Async 线程池下） */
    private val locks = ConcurrentHashMap<String, Any>()

    @Async
    @EventListener
    fun onDecisionRecorded(event: CryptoTailDecisionRecordedEvent) {
        val d = event.decision
        if (d.eventType !in PROJECTED_TYPES) return
        try {
            val lock = locks.computeIfAbsent(d.correlationId) { Any() }
            synchronized(lock) {
                val existing = snapshotRepository.findByStrategyIdAndPeriodStartUnix(d.strategyId, d.periodStartUnix)
                val base = existing ?: CryptoTailTradeSnapshot(
                    strategyId = d.strategyId,
                    periodStartUnix = d.periodStartUnix,
                    correlationId = d.correlationId,
                    outcomeIndex = d.outcomeIndex
                )
                val updated = when (d.eventType) {
                    "ORDER_SUBMITTED" -> applyOrderSubmitted(base, d, d.payloadJson?.fromJson<OrderSubmittedPayload>() ?: OrderSubmittedPayload())
                    "ORDER_RESULT" -> applyOrderResult(base, d, d.payloadJson?.fromJson<OrderResultPayload>() ?: OrderResultPayload())
                    "SETTLED" -> applySettled(base, d, d.payloadJson?.fromJson<SettledPayload>() ?: SettledPayload())
                    else -> base
                }.copy(updatedAt = System.currentTimeMillis())
                snapshotRepository.save(updated)
            }
        } catch (e: Exception) {
            logger.warn("单笔成交快照投影失败: strategyId=${d.strategyId}, period=${d.periodStartUnix}, type=${d.eventType}, ${e.message}")
        }
    }

    private fun applyOrderSubmitted(base: CryptoTailTradeSnapshot, d: CryptoTailDecisionEvent, p: OrderSubmittedPayload): CryptoTailTradeSnapshot {
        val pWin = p.pWin.toBd()
        return base.copy(
            outcomeIndex = d.outcomeIndex ?: base.outcomeIndex,
            marketSlug = p.marketSlug ?: base.marketSlug,
            intervalSeconds = p.intervalSeconds.toIntv() ?: base.intervalSeconds,
            openPrice = p.open.toBd() ?: base.openPrice,
            entryMarkPrice = p.close.toBd() ?: base.entryMarkPrice,
            entryGap = p.gap.toBd() ?: base.entryGap,
            sigmaPerSqrtS = p.sigmaPerSqrtS.toBd() ?: base.sigmaPerSqrtS,
            pWin = pWin ?: base.pWin,
            safeRatio = p.safeRatio.toBd() ?: base.safeRatio,
            modelSide = p.modelSide.toIntv() ?: base.modelSide,
            remainingSecondsAtEntry = p.remainingSeconds.toLng() ?: base.remainingSecondsAtEntry,
            bestBid = p.bestBid.toBd() ?: base.bestBid,
            bestAsk = p.bestAsk.toBd() ?: base.bestAsk,
            midPrice = p.mid.toBd() ?: base.midPrice,
            effectiveCost = p.effectiveCost.toBd() ?: base.effectiveCost,
            entryEdge = p.edge.toBd() ?: base.entryEdge,
            entryProbThreshold = p.entryProb.toBd() ?: base.entryProbThreshold,
            entryEdgeThreshold = p.entryEdge.toBd() ?: base.entryEdgeThreshold,
            barrierMinMarketProb = p.barrierMinMarketProb.toBd() ?: base.barrierMinMarketProb,
            sigmaScale = p.sigmaScale.toBd() ?: base.sigmaScale,
            maxEntryPrice = p.maxEntryPrice.toBd() ?: base.maxEntryPrice,
            costBuffer = p.costBuffer.toBd() ?: base.costBuffer,
            orderType = p.orderType ?: base.orderType,
            targetPrice = p.targetPrice.toBd() ?: base.targetPrice,
            submitTs = base.submitTs ?: d.createdAt,
            pwinBucket = pWin?.let { pwinBucket(it) } ?: base.pwinBucket
        )
    }

    private fun applyOrderResult(base: CryptoTailTradeSnapshot, d: CryptoTailDecisionEvent, p: OrderResultPayload): CryptoTailTradeSnapshot {
        val status = p.status?.takeIf { it.isNotBlank() }
        return base.copy(
            triggerId = d.triggerId ?: base.triggerId,
            fillStatus = status ?: base.fillStatus,
            orderId = (p.orderId?.takeIf { it.isNotBlank() }) ?: base.orderId,
            requestedAmount = p.amountUsdc.toBd() ?: base.requestedAmount,
            execError = if (status == "fail") d.reason ?: base.execError else base.execError
        )
    }

    private fun applySettled(base: CryptoTailTradeSnapshot, d: CryptoTailDecisionEvent, p: SettledPayload): CryptoTailTradeSnapshot {
        val won = p.won.toBoolv()
        val settleTs = p.settleTs.toLng()
        val fillPrice = p.fillPrice.toBd()
        val finalGap = p.finalGap.toBd()
        val entryGap = base.entryGap
        val submitTs = base.submitTs
        val targetPrice = base.targetPrice
        val reversed: Boolean? = if (finalGap != null && entryGap != null && entryGap.signum() != 0) {
            finalGap.signum() != entryGap.signum()
        } else null
        val holdSeconds: Long? = if (settleTs != null && submitTs != null) {
            ((settleTs - submitTs) / 1000).coerceAtLeast(0)
        } else base.holdSeconds
        val slippage: BigDecimal? = if (fillPrice != null && targetPrice != null) {
            fillPrice.subtract(targetPrice)
        } else base.slippage
        // 方向闸保证 outcomeIndex == sign(entryGap)：终值同号本应获胜。
        //  - reversed==true（终值反号）→ 市场反转 REVERSAL
        //  - reversed==false（终值同号）却判负 → 我方 finalGap 与链上结算口径不一致(边界/时间戳差异)，SETTLE_MISMATCH
        //  - reversed==null（finalGap 缺失/为 0）→ UNKNOWN
        val lossReason: String? = when {
            won == true -> null
            won == false && reversed == true -> "REVERSAL"
            won == false && reversed == false -> "SETTLE_MISMATCH"
            won == false -> "UNKNOWN"
            else -> base.lossReason
        }
        return base.copy(
            settled = true,
            won = won ?: base.won,
            winnerOutcomeIndex = p.winnerOutcomeIndex.toIntv() ?: base.winnerOutcomeIndex,
            realizedPnl = p.realizedPnl.toBd() ?: base.realizedPnl,
            settleTs = settleTs ?: base.settleTs,
            holdSeconds = holdSeconds,
            fillPrice = fillPrice ?: base.fillPrice,
            fillSize = p.fillSize.toBd() ?: base.fillSize,
            fillAmount = p.amountUsdc.toBd() ?: base.fillAmount,
            slippage = slippage,
            finalOpen = p.finalOpen.toBd() ?: base.finalOpen,
            finalClose = p.finalClose.toBd() ?: base.finalClose,
            finalGap = finalGap ?: base.finalGap,
            reversed = reversed ?: base.reversed,
            settleSource = p.settleSource ?: base.settleSource,
            lossReason = lossReason
        )
    }

    /** pWin 可靠性分箱：floor(pWin*20)，5% 一箱，限制 0~19 */
    private fun pwinBucket(pWin: BigDecimal): Int =
        pWin.multiply(BigDecimal(20)).setScale(0, RoundingMode.FLOOR).toInt().coerceIn(0, 19)

    // 强类型 payload 字段统一安全转换：空串/解析失败均 null（兼容字符串/数字混合编码）
    private fun String?.toBd(): BigDecimal? = this?.takeIf { it.isNotBlank() }?.let {
        try { BigDecimal(it) } catch (e: NumberFormatException) { null }
    }

    private fun String?.toIntv(): Int? = this?.takeIf { it.isNotBlank() }?.toDoubleOrNull()?.toInt()

    private fun String?.toLng(): Long? = this?.takeIf { it.isNotBlank() }?.toDoubleOrNull()?.toLong()

    private fun String?.toBoolv(): Boolean? = this?.takeIf { it.isNotBlank() }?.toBooleanStrictOrNull()

    companion object {
        private val PROJECTED_TYPES = setOf("ORDER_SUBMITTED", "ORDER_RESULT", "SETTLED")
    }
}
