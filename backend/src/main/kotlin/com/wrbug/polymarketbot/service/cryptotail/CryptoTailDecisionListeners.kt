package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.dto.CryptoTailDecisionEventDto
import com.wrbug.polymarketbot.entity.CryptoTailDecisionEvent
import com.wrbug.polymarketbot.event.CryptoTailDecisionRecordedEvent
import com.wrbug.polymarketbot.repository.CryptoTailDecisionEventRepository
import com.wrbug.polymarketbot.service.common.WebSocketSubscriptionService
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

/** 实体 → DTO 映射（REST 与 WS 推送共用） */
fun CryptoTailDecisionEvent.toDto(): CryptoTailDecisionEventDto = CryptoTailDecisionEventDto(
    id = id ?: 0L,
    strategyId = strategyId,
    periodStartUnix = periodStartUnix,
    correlationId = correlationId,
    eventType = eventType,
    gateName = gateName,
    passed = passed,
    reason = reason,
    payloadJson = payloadJson,
    outcomeIndex = outcomeIndex,
    triggerId = triggerId,
    createdAt = createdAt
)

/**
 * 决策日志异步落库监听：与下单/结算热路径解耦，DB 慢不影响主流程。
 */
@Component
class CryptoTailDecisionPersistenceListener(
    private val decisionEventRepository: CryptoTailDecisionEventRepository
) {
    private val logger = LoggerFactory.getLogger(CryptoTailDecisionPersistenceListener::class.java)

    @Async
    @EventListener
    fun onDecisionRecorded(event: CryptoTailDecisionRecordedEvent) {
        try {
            decisionEventRepository.save(event.decision)
        } catch (e: Exception) {
            logger.warn("决策日志落库失败: strategyId=${event.decision.strategyId}, ${e.message}")
        }
    }
}

/**
 * 决策日志异步推送监听：推到 crypto_tail_decision_{strategyId} 频道，WS 断连不影响主流程。
 */
@Component
class CryptoTailDecisionPushListener(
    private val webSocketSubscriptionService: WebSocketSubscriptionService
) {
    private val logger = LoggerFactory.getLogger(CryptoTailDecisionPushListener::class.java)

    @Async
    @EventListener
    fun onDecisionRecorded(event: CryptoTailDecisionRecordedEvent) {
        try {
            webSocketSubscriptionService.pushDecisionEvent(event.decision.strategyId, event.decision.toDto())
        } catch (e: Exception) {
            logger.debug("决策日志推送失败: strategyId=${event.decision.strategyId}, ${e.message}")
        }
    }
}
