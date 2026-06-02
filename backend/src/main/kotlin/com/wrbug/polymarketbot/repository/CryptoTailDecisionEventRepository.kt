package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.CryptoTailDecisionEvent
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface CryptoTailDecisionEventRepository : JpaRepository<CryptoTailDecisionEvent, Long> {

    fun findAllByStrategyIdOrderByCreatedAtDesc(strategyId: Long, pageable: Pageable): Page<CryptoTailDecisionEvent>

    fun findAllByStrategyIdAndCreatedAtBetweenOrderByCreatedAtDesc(
        strategyId: Long,
        startInclusive: Long,
        endInclusive: Long,
        pageable: Pageable
    ): Page<CryptoTailDecisionEvent>

    fun findAllByStrategyIdAndPeriodStartUnixOrderByCreatedAtAsc(
        strategyId: Long,
        periodStartUnix: Long
    ): List<CryptoTailDecisionEvent>

    // 跨策略汇总查询（决策日志独立页用）
    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): Page<CryptoTailDecisionEvent>

    fun findAllByCreatedAtBetweenOrderByCreatedAtDesc(
        startInclusive: Long,
        endInclusive: Long,
        pageable: Pageable
    ): Page<CryptoTailDecisionEvent>
}
