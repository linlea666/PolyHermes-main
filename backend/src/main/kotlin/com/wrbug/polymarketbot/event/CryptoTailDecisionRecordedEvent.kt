package com.wrbug.polymarketbot.event

import com.wrbug.polymarketbot.entity.CryptoTailDecisionEvent
import org.springframework.context.ApplicationEvent

/**
 * 加密尾盘策略决策事件被记录后发布，用于异步落库与推送前端，解耦下单/结算热路径。
 */
class CryptoTailDecisionRecordedEvent(
    source: Any,
    val decision: CryptoTailDecisionEvent
) : ApplicationEvent(source)
