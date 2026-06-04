package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import com.wrbug.polymarketbot.entity.CryptoTailStrategyTrigger
import com.wrbug.polymarketbot.enums.TradingMode
import com.wrbug.polymarketbot.repository.CryptoTailStrategyRepository
import com.wrbug.polymarketbot.repository.CryptoTailStrategyTriggerRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/**
 * crypto-tail 自有最小风控闸（复用现有触发记录数据源，不碰跟单风控）。
 * 仅在障碍模式下、下单前调用：
 *  - 日亏熔断：当日已实现亏损达到 dailyLossLimitUsdc → 拦截
 *  - 并发敞口：已成功下单未结算笔数达到 maxConcurrentPositions → 拦截
 * 阈值为 null/<=0 时该闸关闭。
 */
@Service
class CryptoTailRiskService(
    private val triggerRepository: CryptoTailStrategyTriggerRepository,
    private val strategyRepository: CryptoTailStrategyRepository
) {

    private val logger = LoggerFactory.getLogger(CryptoTailRiskService::class.java)

    /** 风控结果：通过或带拦截原因 */
    data class RiskResult(
        val passed: Boolean,
        val gateName: String? = null,
        val reason: String? = null
    ) {
        companion object {
            val PASS = RiskResult(true)
        }
    }

    /**
     * 校验风控闸。返回 PASS 表示放行；否则带 gateName 与原因。
     */
    fun checkRiskGate(strategy: CryptoTailStrategy): RiskResult {
        val strategyId = strategy.id ?: return RiskResult.PASS

        // 日亏熔断（TAIL_DIFF 优先用专属阈值 tailDiffDailyLossLimitUsdc，为 null 时回退全局 dailyLossLimitUsdc）
        val lossLimit = if (strategy.mode == TradingMode.TAIL_DIFF) {
            strategy.tailDiffDailyLossLimitUsdc ?: strategy.dailyLossLimitUsdc
        } else {
            strategy.dailyLossLimitUsdc
        }
        if (lossLimit != null && lossLimit > BigDecimal.ZERO) {
            val startOfDayMs = LocalDate.now(ZoneId.systemDefault())
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli()
            val todayPnl = triggerRepository.sumRealizedPnlByStrategyIdAndSettledAtAfter(strategyId, startOfDayMs)
                ?: BigDecimal.ZERO
            // todayPnl 为负表示亏损；亏损绝对值达到阈值则拦截
            if (todayPnl < BigDecimal.ZERO && todayPnl.negate() >= lossLimit) {
                val reason = "当日已实现亏损 ${todayPnl.toPlainString()} 达到熔断阈值 ${lossLimit.toPlainString()}"
                logger.info("crypto-tail 风控拦截(日亏): strategyId=$strategyId, $reason")
                return RiskResult(false, "RISK_DAILY_LOSS", reason)
            }
        }

        // 并发敞口
        val maxConcurrent = strategy.maxConcurrentPositions
        if (maxConcurrent != null && maxConcurrent > 0) {
            val openCount = triggerRepository.countByStrategyIdAndStatusAndResolvedFalse(strategyId, "success")
            if (openCount >= maxConcurrent) {
                val reason = "未结算敞口 $openCount 达到上限 $maxConcurrent"
                logger.info("crypto-tail 风控拦截(并发敞口): strategyId=$strategyId, $reason")
                return RiskResult(false, "RISK_CONCURRENCY", reason)
            }
        }

        val startOfDayMs = LocalDate.now(ZoneId.systemDefault())
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        val nowMs = System.currentTimeMillis()

        val maxOrdersPerDay = strategy.maxOrdersPerDay
        if (maxOrdersPerDay != null && maxOrdersPerDay > 0) {
            val count = triggerRepository.countByStrategyIdAndStatusAndCreatedAtBetween(
                strategyId,
                "success",
                startOfDayMs,
                nowMs
            )
            if (count >= maxOrdersPerDay) {
                val reason = "当日成功入场 $count 达到上限 $maxOrdersPerDay"
                logger.info("crypto-tail 风控拦截(日订单上限): strategyId=$strategyId, $reason")
                return RiskResult(false, "RISK_MAX_ORDERS_PER_DAY", reason)
            }
        }

        checkAccountRiskGate(strategy, startOfDayMs, nowMs)?.let { return it }

        val latestResolved = triggerRepository.findLatestResolvedByStrategyId(strategyId, PageRequest.of(0, 20))

        // TAIL_DIFF 用专属连亏暂停/当日停策略阈值，替代下方通用 pauseAfterLossMinutes/maxConsecutiveLosses 逻辑，
        // 避免双重门控与语义冲突；日亏/并发/日订单/账户级风控仍复用上方通用闸。
        if (strategy.mode == TradingMode.TAIL_DIFF) {
            checkTailDiffConsecutiveLossGate(strategy, latestResolved, startOfDayMs, nowMs)?.let { return it }
            return RiskResult.PASS
        }

        val pauseAfterLossMinutes = strategy.pauseAfterLossMinutes
        if (pauseAfterLossMinutes > 0) {
            val latest = latestResolved.firstOrNull()
            val latestPnl = latest?.realizedPnl
            val latestTs = latest?.settledAt ?: latest?.createdAt
            if (latestPnl != null && latestPnl < BigDecimal.ZERO && latestTs != null) {
                val until = latestTs + pauseAfterLossMinutes * 60_000L
                if (nowMs < until) {
                    val reason = "最近一笔亏损后暂停入场，剩余 ${(until - nowMs) / 1000}s"
                    logger.info("crypto-tail 风控拦截(亏损冷却): strategyId=$strategyId, $reason")
                    return RiskResult(false, "RISK_LOSS_COOLDOWN", reason)
                }
            }
        }

        val maxConsecutiveLosses = strategy.maxConsecutiveLosses
        if (maxConsecutiveLosses != null && maxConsecutiveLosses > 0) {
            val consecutiveLosses = latestResolved
                .take(maxConsecutiveLosses)
                .takeWhile { (it.realizedPnl ?: BigDecimal.ZERO) < BigDecimal.ZERO }
                .size
            if (consecutiveLosses >= maxConsecutiveLosses) {
                val reason = "连续已结算亏损 $consecutiveLosses 达到上限 $maxConsecutiveLosses"
                logger.info("crypto-tail 风控拦截(连续亏损): strategyId=$strategyId, $reason")
                return RiskResult(false, "RISK_CONSECUTIVE_LOSSES", reason)
            }
        }

        return RiskResult.PASS
    }

    /**
     * TAIL_DIFF 专属连亏闸：
     *  - 连亏达 tailDiffConsecLossStopCount → 当日停策略（拦截后无法再下单，等同当天熔断到日终）。
     *  - 连亏达 tailDiffConsecLossPauseCount（但未达 stop）→ 在 pauseAfterLossMinutes 冷却窗内暂停，冷却到期自动恢复。
     * 连亏 streak 以"今日已结算触发记录、按结算时间倒序的最近连续亏损笔数"计；一旦出现盈利则归零。
     */
    private fun checkTailDiffConsecutiveLossGate(
        strategy: CryptoTailStrategy,
        latestResolved: List<CryptoTailStrategyTrigger>,
        startOfDayMs: Long,
        nowMs: Long
    ): RiskResult? {
        val strategyId = strategy.id
        // 仅统计今日已结算的记录（新的一天自动重置；latestResolved 已按结算时间倒序，最新在前）
        val todayResolved = latestResolved.filter { (it.settledAt ?: it.createdAt) >= startOfDayMs }
        val todayLossStreak = todayResolved
            .takeWhile { (it.realizedPnl ?: BigDecimal.ZERO) < BigDecimal.ZERO }
            .size

        val stopCount = strategy.tailDiffConsecLossStopCount
        if (stopCount > 0 && todayLossStreak >= stopCount) {
            val reason = "TAIL_DIFF 当日连续亏损 $todayLossStreak 达到当日停策略阈值 $stopCount"
            logger.info("crypto-tail TAIL_DIFF 风控拦截(连亏当日停): strategyId=$strategyId, $reason")
            return RiskResult(false, "RISK_TAIL_DIFF_CONSEC_STOP", reason)
        }

        val pauseCount = strategy.tailDiffConsecLossPauseCount
        if (pauseCount > 0 && todayLossStreak >= pauseCount) {
            val pauseMinutes = strategy.pauseAfterLossMinutes
            if (pauseMinutes > 0) {
                val lastLoss = todayResolved.firstOrNull()
                val lastLossTs = lastLoss?.settledAt ?: lastLoss?.createdAt
                if (lastLossTs != null) {
                    val until = lastLossTs + pauseMinutes * 60_000L
                    if (nowMs < until) {
                        val reason = "TAIL_DIFF 连续亏损 $todayLossStreak 达到暂停阈值 $pauseCount，冷却剩余 ${(until - nowMs) / 1000}s"
                        logger.info("crypto-tail TAIL_DIFF 风控拦截(连亏暂停): strategyId=$strategyId, $reason")
                        return RiskResult(false, "RISK_TAIL_DIFF_CONSEC_PAUSE", reason)
                    }
                }
            }
        }
        return null
    }

    private fun checkAccountRiskGate(
        strategy: CryptoTailStrategy,
        startOfDayMs: Long,
        nowMs: Long
    ): RiskResult? {
        val accountId = strategy.accountId
        val enabledStrategies = try {
            strategyRepository.findByAccountIdAndEnabled(accountId, true)
        } catch (e: Exception) {
            logger.warn("crypto-tail 账户级风控读取策略失败: accountId=$accountId, ${e.message}")
            return RiskResult(false, "RISK_ACCOUNT_QUERY_FAILED", "账户级风控读取策略失败")
        }
        if (enabledStrategies.isEmpty()) return null

        val accountDailyLossLimit = enabledStrategies
            .mapNotNull { it.dailyLossLimitUsdc?.takeIf { v -> v > BigDecimal.ZERO } }
            .fold(BigDecimal.ZERO, BigDecimal::add)
        if (accountDailyLossLimit > BigDecimal.ZERO) {
            val todayPnl = triggerRepository.sumRealizedPnlByAccountIdAndSettledAtAfter(accountId, startOfDayMs)
                ?: BigDecimal.ZERO
            if (todayPnl < BigDecimal.ZERO && todayPnl.negate() >= accountDailyLossLimit) {
                val reason = "账户当日已实现亏损 ${todayPnl.toPlainString()} 达到账户级合计熔断阈值 ${accountDailyLossLimit.toPlainString()}"
                logger.info("crypto-tail 账户级风控拦截(日亏): accountId=$accountId, $reason")
                return RiskResult(false, "RISK_ACCOUNT_DAILY_LOSS", reason)
            }
        }

        val accountMaxConcurrent = enabledStrategies
            .mapNotNull { it.maxConcurrentPositions?.takeIf { v -> v > 0 } }
            .sum()
        if (accountMaxConcurrent > 0) {
            val openCount = triggerRepository.countByAccountIdAndStatusAndResolvedFalse(accountId, "success")
            if (openCount >= accountMaxConcurrent) {
                val reason = "账户未结算敞口 $openCount 达到账户级合计上限 $accountMaxConcurrent"
                logger.info("crypto-tail 账户级风控拦截(并发敞口): accountId=$accountId, $reason")
                return RiskResult(false, "RISK_ACCOUNT_CONCURRENCY", reason)
            }
        }

        val accountMaxOrdersPerDay = enabledStrategies
            .mapNotNull { it.maxOrdersPerDay?.takeIf { v -> v > 0 } }
            .sum()
        if (accountMaxOrdersPerDay > 0) {
            val count = triggerRepository.countByAccountIdAndStatusAndCreatedAtBetween(
                accountId,
                "success",
                startOfDayMs,
                nowMs
            )
            if (count >= accountMaxOrdersPerDay) {
                val reason = "账户当日成功入场 $count 达到账户级合计上限 $accountMaxOrdersPerDay"
                logger.info("crypto-tail 账户级风控拦截(日订单上限): accountId=$accountId, $reason")
                return RiskResult(false, "RISK_ACCOUNT_MAX_ORDERS_PER_DAY", reason)
            }
        }

        return null
    }
}
