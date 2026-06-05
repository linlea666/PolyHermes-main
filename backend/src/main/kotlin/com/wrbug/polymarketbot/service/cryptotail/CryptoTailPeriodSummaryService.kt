package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.entity.CryptoTailPeriodSummary
import com.wrbug.polymarketbot.enums.TradingMode
import com.wrbug.polymarketbot.repository.CryptoTailDecisionEventRepository
import com.wrbug.polymarketbot.repository.CryptoTailPeriodSummaryRepository
import com.wrbug.polymarketbot.repository.CryptoTailStrategyRepository
import com.wrbug.polymarketbot.repository.CryptoTailStrategyTriggerRepository
import com.wrbug.polymarketbot.util.fromJson
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.math.BigDecimal

/**
 * TAIL_DIFF 周期生命周期汇总服务。
 *
 * 把分散在 [com.wrbug.polymarketbot.entity.CryptoTailDecisionEvent] 的每周期决策聚合成 [CryptoTailPeriodSummary]，
 * 并在周期结束后并入官方结算结果（复用 [PeriodPriceProvider.getFinalOpenClose]，与 Polymarket 结算同源的 Chainlink），
 * 用于「策略首选方向 vs 官方实际结果」的方向准确率回测。
 *
 * 解耦：仅观测/回测，失败安全（任一周期异常只记日志，不影响其它）。不触碰交易主链路。
 */
@Service
class CryptoTailPeriodSummaryService(
    private val periodSummaryRepository: CryptoTailPeriodSummaryRepository,
    private val decisionEventRepository: CryptoTailDecisionEventRepository,
    private val strategyRepository: CryptoTailStrategyRepository,
    private val triggerRepository: CryptoTailStrategyTriggerRepository,
    private val periodPriceProvider: PeriodPriceProvider
) {

    private val logger = LoggerFactory.getLogger(CryptoTailPeriodSummaryService::class.java)

    /** 聚合回看窗口：最近 6 小时出现过决策的周期都纳入刷新（覆盖最深 15m 周期与少量重连缺口） */
    private val aggregateLookbackMs = 6L * 3600 * 1000

    /** 结算宽限期：周期结束后超过此时长仍无法获取官方价/成交结果，则放弃结算（标记 ABANDONED） */
    private val settleGraceMs = 2L * 3600 * 1000

    /**
     * 定时聚合 + 结算回填：每 30 秒一次。
     * 1) 聚合最近窗口内每个 (strategyId, periodStartUnix) 的决策事件 → upsert 汇总；
     * 2) 周期已结束且官方价可用 → 并入官方结算结果并标记 SETTLED。
     */
    @Scheduled(fixedDelay = 30_000)
    fun scheduledAggregateAndSettle() {
        try {
            aggregateRecentPeriods()
        } catch (e: Exception) {
            logger.warn("周期汇总聚合异常: ${e.message}", e)
        }
        try {
            settleEndedPeriods()
        } catch (e: Exception) {
            logger.warn("周期汇总结算回填异常: ${e.message}", e)
        }
    }

    fun aggregateRecentPeriods() {
        val since = System.currentTimeMillis() - aggregateLookbackMs
        val periods = decisionEventRepository.findDistinctStrategyPeriodsSince(since)
        for (row in periods) {
            val strategyId = (row[0] as Number).toLong()
            val periodStartUnix = (row[1] as Number).toLong()
            try {
                aggregateOnePeriod(strategyId, periodStartUnix)
            } catch (e: Exception) {
                logger.debug("周期汇总单周期聚合失败: strategyId=$strategyId, period=$periodStartUnix, ${e.message}")
            }
        }
    }

    private fun aggregateOnePeriod(strategyId: Long, periodStartUnix: Long) {
        val strategy = strategyRepository.findById(strategyId).orElse(null) ?: return
        // 仅 TAIL_DIFF 周期纳入回测汇总；其它模式有独立的 trigger/结算视图
        if (strategy.mode != TradingMode.TAIL_DIFF) return

        val existing = periodSummaryRepository.findByStrategyIdAndPeriodStartUnix(strategyId, periodStartUnix)
        // 已结算的周期不再重复聚合（官方结果已并入，决策事件不会再变）
        if (existing != null && existing.status == "SETTLED") return

        val events = decisionEventRepository.findAllByStrategyIdAndPeriodStartUnixOrderByCreatedAtAsc(strategyId, periodStartUnix)
        if (events.isEmpty()) return

        val trigger = triggerRepository.findByStrategyIdAndPeriodStartUnix(strategyId, periodStartUnix)
        // 写放大优化：聚合每 30s 跑一次，若该周期无新决策事件且成交状态无变化，则跳过重写（避免反复 UPDATE 同一行）
        val latestEventAt = events.last().createdAt
        val triggerChanged = trigger?.id != existing?.triggerId ||
            (trigger != null && trigger.realizedPnl != existing?.realizedPnl)
        if (existing != null && latestEventAt <= existing.updatedAt && !triggerChanged) return

        var scoreCount = 0
        var skipCount = 0
        var buyCount = 0
        var firstChosen: Int? = null
        var lastChosen: Int? = null
        var flips = 0
        var bestScore = 0
        var triggerId: Long? = null
        val vetoTally = HashMap<String, Int>()

        for (e in events) {
            when (e.eventType) {
                "TAIL_DIFF_SCORE_COMPUTED", "TAIL_DIFF_BUY" -> {
                    if (e.eventType == "TAIL_DIFF_SCORE_COMPUTED") scoreCount++ else buyCount++
                    val payload = e.payloadJson.fromJson<com.wrbug.polymarketbot.service.cryptotail.TailDiffScorePayload>()
                    val modelSide = payload?.modelSide?.toIntOrNull()
                    if (modelSide != null) {
                        if (firstChosen == null) firstChosen = modelSide
                        if (lastChosen != null && lastChosen != modelSide) flips++
                        lastChosen = modelSide
                    }
                    // 最高分仅取领先方向自身 token 的评分（outcomeIndex==modelSide），更贴近真实可入场分
                    val score = payload?.score?.toIntOrNull()
                    if (score != null && modelSide != null && e.outcomeIndex == modelSide && score > bestScore) {
                        bestScore = score
                    }
                    val veto = payload?.vetoReasons?.split(",")?.firstOrNull { it.isNotBlank() }
                    if (veto != null) vetoTally[veto] = (vetoTally[veto] ?: 0) + 1
                }
                "TAIL_DIFF_SKIP" -> {
                    skipCount++
                    val gate = e.gateName
                    if (!gate.isNullOrBlank()) vetoTally[gate] = (vetoTally[gate] ?: 0) + 1
                }
            }
            if (e.triggerId != null && triggerId == null) triggerId = e.triggerId
        }

        val dominantVeto = vetoTally.maxByOrNull { it.value }?.key
        val traded = triggerId != null || trigger != null
        val now = System.currentTimeMillis()

        val merged = (existing ?: CryptoTailPeriodSummary(
            strategyId = strategyId,
            periodStartUnix = periodStartUnix,
            periodEndUnix = periodStartUnix + strategy.intervalSeconds,
            marketSlug = strategy.marketSlugPrefix,
            createdAt = now
        )).copy(
            periodEndUnix = periodStartUnix + strategy.intervalSeconds,
            marketSlug = strategy.marketSlugPrefix,
            firstChosenOutcomeIndex = firstChosen,
            lastChosenOutcomeIndex = lastChosen,
            directionFlipCount = flips,
            bestScore = bestScore,
            dominantVeto = dominantVeto,
            scoreEventCount = scoreCount,
            skipEventCount = skipCount,
            buyEventCount = buyCount,
            traded = traded,
            triggerId = triggerId ?: trigger?.id,
            realizedPnl = trigger?.realizedPnl ?: (existing?.realizedPnl),
            status = "OPEN",
            updatedAt = now
        )
        periodSummaryRepository.save(merged)
    }

    fun settleEndedPeriods() {
        val nowUnix = System.currentTimeMillis() / 1000
        val candidates = periodSummaryRepository
            .findTop200ByStatusAndPeriodEndUnixLessThanEqualOrderByPeriodStartUnixAsc("OPEN", nowUnix)
        for (summary in candidates) {
            try {
                settleOne(summary)
            } catch (e: Exception) {
                logger.debug("周期汇总结算单周期失败: strategyId=${summary.strategyId}, period=${summary.periodStartUnix}, ${e.message}")
            }
        }
    }

    private fun settleOne(summary: CryptoTailPeriodSummary) {
        val strategy = strategyRepository.findById(summary.strategyId).orElse(null) ?: return
        val now = System.currentTimeMillis()
        // 超过宽限期仍无法结算（官方价/成交结果长期不可用）则放弃为 ABANDONED，避免老周期长期占据 OPEN 队列头部、
        // 阻塞较新周期回填（结算按 periodStart 升序取前 200）。
        val expired = now - summary.periodEndUnix * 1000 > settleGraceMs
        val finalOc = getFinalOpenClose(strategy.marketSlugPrefix, strategy.intervalSeconds, summary.periodStartUnix)
        if (finalOc == null) {
            if (expired) {
                periodSummaryRepository.save(summary.copy(status = "ABANDONED", settledAt = now, updatedAt = now))
            }
            return // 官方价暂不可用，未超期则保持 OPEN，下轮重试
        }
        val (open, close) = finalOc
        val gap = close.subtract(open)

        val trigger = triggerRepository.findByStrategyIdAndPeriodStartUnix(summary.strategyId, summary.periodStartUnix)
        // 赢家方向口径：有成交时以链上 condition 结算结果为权威（与 CryptoTailSettlementService 一致），
        // 避免与真实成交盈亏矛盾；成交但尚未结算则保持 OPEN 等待下轮，绝不用代理值覆盖权威结果。
        // 无成交周期没有 conditionId，退化用官方 open/close 代理（close≥open → Up(0)，否则 Down(1)）。
        val winner: Int = when {
            trigger?.winnerOutcomeIndex != null -> trigger.winnerOutcomeIndex!!
            trigger != null -> {
                // 有成交但未结算：超期则放弃，否则等待权威结果
                if (expired) periodSummaryRepository.save(summary.copy(status = "ABANDONED", settledAt = now, updatedAt = now))
                return
            }
            else -> if (close >= open) 0 else 1
        }
        val first = summary.firstChosenOutcomeIndex
        val directionCorrect = if (first != null) first == winner else null

        val updated = summary.copy(
            officialOpen = open,
            officialClose = close,
            officialGap = gap,
            settledWinnerOutcomeIndex = winner,
            directionCorrect = directionCorrect,
            traded = summary.traded || trigger != null,
            triggerId = summary.triggerId ?: trigger?.id,
            realizedPnl = trigger?.realizedPnl ?: summary.realizedPnl,
            status = "SETTLED",
            settledAt = now,
            updatedAt = now
        )
        periodSummaryRepository.save(updated)
    }

    /** 取结算周期最终 (open, close)：TAIL_DIFF 走与结算同源的 Chainlink，不可用返回 null（不造假）。 */
    private fun getFinalOpenClose(marketSlugPrefix: String, intervalSeconds: Int, periodStartUnix: Long): Pair<BigDecimal, BigDecimal>? {
        return periodPriceProvider.getFinalOpenClose(marketSlugPrefix, intervalSeconds, periodStartUnix)
    }
}
