package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.entity.CryptoTailDecisionEvent
import com.wrbug.polymarketbot.event.CryptoTailDecisionRecordedEvent
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

/**
 * 决策日志记录器（解耦插件式）。
 * 主流程仅在 skip/return/下单/结算等节点调用 record(...)，绝不影响 if/return 语义与热路径性能。
 * 通过开关 crypto-tail.decision-log.enabled 选择 Db 实现或 NoOp 实现。
 */
interface CryptoTailDecisionRecorder {
    /** 记录一条决策事件；实现需保证不向调用方抛异常 */
    fun record(event: CryptoTailDecisionEvent)
}

/**
 * 默认实现：发布 Spring 事件，由 @Async 监听器异步落库与推送，不阻塞下单热路径。
 * 开关缺省（matchIfMissing=true）即启用。
 */
@Component
@ConditionalOnProperty(name = ["crypto-tail.decision-log.enabled"], havingValue = "true", matchIfMissing = true)
class DbCryptoTailDecisionRecorder(
    private val eventPublisher: ApplicationEventPublisher
) : CryptoTailDecisionRecorder {

    private val logger = LoggerFactory.getLogger(DbCryptoTailDecisionRecorder::class.java)

    override fun record(event: CryptoTailDecisionEvent) {
        try {
            eventPublisher.publishEvent(CryptoTailDecisionRecordedEvent(this, event))
        } catch (e: Exception) {
            // 决策日志失败不得影响主流程
            logger.debug("发布决策日志事件失败: strategyId=${event.strategyId}, ${e.message}")
        }
    }
}

/**
 * 关闭开关时的空实现，零开销。
 */
@Component
@ConditionalOnProperty(name = ["crypto-tail.decision-log.enabled"], havingValue = "false")
class NoOpCryptoTailDecisionRecorder : CryptoTailDecisionRecorder {
    override fun record(event: CryptoTailDecisionEvent) {
        // no-op
    }
}
