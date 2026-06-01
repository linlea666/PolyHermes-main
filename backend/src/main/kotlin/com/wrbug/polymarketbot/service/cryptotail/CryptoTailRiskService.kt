package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import com.wrbug.polymarketbot.repository.CryptoTailStrategyTriggerRepository
import org.slf4j.LoggerFactory
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
    private val triggerRepository: CryptoTailStrategyTriggerRepository
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

        // 日亏熔断
        val lossLimit = strategy.dailyLossLimitUsdc
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

        return RiskResult.PASS
    }
}
