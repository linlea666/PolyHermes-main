package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.entity.CryptoTailDecisionEvent
import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import com.wrbug.polymarketbot.entity.CryptoTailStrategyTrigger
import com.wrbug.polymarketbot.enums.TradingMode
import com.wrbug.polymarketbot.repository.CryptoTailStrategyRepository
import com.wrbug.polymarketbot.repository.CryptoTailStrategyTriggerRepository
import com.wrbug.polymarketbot.util.toJson
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.math.BigDecimal

/**
 * Exit-manager fallback driver. It refreshes CLOB /book snapshots and reuses
 * CryptoTailBracketExitService so exit decisions do not depend solely on WS price-change events.
 */
@Service
class CryptoTailExitPoller(
    private val triggerRepository: CryptoTailStrategyTriggerRepository,
    private val strategyRepository: CryptoTailStrategyRepository,
    private val accountContextFactory: CryptoTailAccountContextFactory,
    private val orderbookSnapshotFetcher: CryptoTailOrderbookSnapshotFetcher,
    private val bracketExitService: CryptoTailBracketExitService,
    private val decisionRecorder: CryptoTailDecisionRecorder
) {

    private val logger = LoggerFactory.getLogger(CryptoTailExitPoller::class.java)
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + scopeJob)

    @Volatile
    private var pollJob: Job? = null

    @Scheduled(fixedDelay = 500)
    fun scheduledPoll() {
        val previous = pollJob
        if (previous != null && previous.isActive) {
            logger.debug("上一轮 crypto-tail 退出 poll 仍在执行，跳过本次调度")
            return
        }
        pollJob = scope.launch {
            try {
                pollOpenTriggers()
            } catch (e: Exception) {
                logger.error("crypto-tail 退出 poll 异常: ${e.message}", e)
            } finally {
                pollJob = null
            }
        }
    }

    private suspend fun pollOpenTriggers() {
        val triggers = try {
            triggerRepository.findAllOpenForExitPolling()
        } catch (e: Exception) {
            logger.warn("查询 crypto-tail poll 持仓失败: ${e.message}")
            return
        }
        if (triggers.isEmpty()) return

        val strategies = strategyRepository.findAllByEnabledTrue()
            .mapNotNull { s -> s.id?.let { it to s } }
            .toMap()
        if (strategies.isEmpty()) return

        val nowMs = System.currentTimeMillis()
        val nowSeconds = nowMs / 1000
        for (trigger in triggers) {
            val strategy = strategies[trigger.strategyId] ?: continue
            if (!shouldPoll(trigger, strategy, nowMs, nowSeconds)) continue
            pollOne(trigger, strategy, nowMs, nowSeconds)
        }
    }

    private fun shouldPoll(
        trigger: CryptoTailStrategyTrigger,
        strategy: CryptoTailStrategy,
        nowMs: Long,
        nowSeconds: Long
    ): Boolean {
        if (strategy.mode == TradingMode.LEGACY_SPREAD || !strategy.enableExitManager) return false
        if (nowSeconds >= trigger.periodStartUnix + strategy.intervalSeconds) return false
        val remaining = trigger.remainingSize
        if (remaining == null || remaining <= BigDecimal.ZERO) return false
        var intervalMs = strategy.exitPollIntervalMs.coerceAtLeast(500).toLong()
        // V92 杠杆1 尾盘提速：remaining<=scalpLateFastPollSeconds 时降低巡检间隔，确保尾盘每个调度 tick 都不被跳过
        // （真正的评估节流由 evaluateAndExit 同源处理；实际频率仍受 500ms 调度上限约束）。0=关（零回归）。
        val remainingForPoll = (trigger.periodStartUnix + strategy.intervalSeconds - nowSeconds).toInt()
        if (strategy.mode == TradingMode.SCALP_FLIP && strategy.scalpLateFastPollSeconds > 0 &&
            remainingForPoll <= strategy.scalpLateFastPollSeconds
        ) {
            intervalMs = minOf(intervalMs, strategy.scalpLateFastPollMs.coerceAtLeast(100).toLong())
        }
        val last = trigger.lastExitCheckAt
        return last == null || nowMs - last >= intervalMs
    }

    private suspend fun pollOne(
        trigger: CryptoTailStrategyTrigger,
        strategy: CryptoTailStrategy,
        nowMs: Long,
        nowSeconds: Long
    ) {
        val tokenId = trigger.tokenId
        if (tokenId.isNullOrBlank()) return
        // EXIT_POLL_TICK 仅为巡检心跳，不再写决策表（每持仓每 interval 一条纯噪声）；
        // 真正的退出评估/退出信号由下方 evaluateAndExit → recordExitCheck/EXIT_SIGNAL 负责（已去重）。
        logger.debug("crypto-tail 退出巡检 triggerId={} strategyId={} period={}", trigger.id, trigger.strategyId, trigger.periodStartUnix)
        val ctx = accountContextFactory.build(strategy)
        if (ctx == null) {
            backoffAndRecord(trigger, strategy, nowMs, "账户上下文不可用")
            return
        }
        val orderbook = orderbookSnapshotFetcher.fetch(ctx.clobApi, tokenId)
        if (orderbook == null) {
            backoffAndRecord(trigger, strategy, nowMs, "CLOB /book 快照缺失")
            return
        }
        bracketExitService.evaluateAndExit(
            trigger = trigger,
            strategy = strategy,
            bestBid = orderbook.bestBid,
            nowSeconds = nowSeconds,
            orderbook = orderbook,
            triggerSource = "POLL"
        )
    }

    private fun backoffAndRecord(
        trigger: CryptoTailStrategyTrigger,
        strategy: CryptoTailStrategy,
        nowMs: Long,
        reason: String
    ) {
        val triggerId = trigger.id
        if (triggerId != null) {
            try {
                triggerRepository.updateLastExitCheckAt(triggerId, nowMs)
            } catch (e: Exception) {
                logger.warn("更新退出 poll backoff 失败: triggerId=$triggerId, ${e.message}")
            }
        }
        recordPollEvent(trigger, strategy, "EXIT_CHECK", false, "ORDERBOOK_REFRESH_RECHECK_FAILED", reason)
    }

    private fun recordPollEvent(
        trigger: CryptoTailStrategyTrigger,
        strategy: CryptoTailStrategy,
        eventType: String,
        passed: Boolean?,
        reason: String,
        detail: String?
    ) {
        val remainingSeconds = (trigger.periodStartUnix + strategy.intervalSeconds - System.currentTimeMillis() / 1000).toInt()
        val payload = mapOf(
            "strategyId" to trigger.strategyId,
            "strategyName" to (strategy.name ?: ""),
            "coin" to (CryptoTailCoinResolver.coinOfSlug(strategy.marketSlugPrefix) ?: ""),
            "marketSlug" to strategy.marketSlugPrefix,
            "periodStartUnix" to trigger.periodStartUnix,
            "triggerSource" to "POLL",
            "mode" to strategy.mode.name,
            "triggerId" to (trigger.id ?: ""),
            "tokenId" to (trigger.tokenId ?: ""),
            "outcomeIndex" to trigger.outcomeIndex,
            "remainingSize" to (trigger.remainingSize?.toPlainString() ?: ""),
            "remainingSeconds" to remainingSeconds,
            "exitPollIntervalMs" to strategy.exitPollIntervalMs,
            "detail" to (detail ?: "")
        ).toJson()
        decisionRecorder.record(
            CryptoTailDecisionEvent(
                strategyId = trigger.strategyId,
                periodStartUnix = trigger.periodStartUnix,
                correlationId = "${trigger.strategyId}-${trigger.periodStartUnix}-exit-poll-${trigger.id}",
                eventType = eventType,
                gateName = null,
                passed = passed,
                reason = reason,
                payloadJson = payload,
                outcomeIndex = trigger.outcomeIndex,
                triggerId = trigger.id
            )
        )
    }

    @PreDestroy
    fun destroy() {
        scopeJob.cancel()
    }
}
