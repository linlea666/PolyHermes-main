package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.CryptoTailDecisionEvent
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

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

    /** 最近窗口内出现过决策的不同 (strategyId, periodStartUnix)，供周期汇总聚合 */
    @Query(
        "SELECT DISTINCT e.strategyId, e.periodStartUnix FROM CryptoTailDecisionEvent e WHERE e.createdAt >= :since"
    )
    fun findDistinctStrategyPeriodsSince(@Param("since") since: Long): List<Array<Any>>

    // 跨策略汇总查询（决策日志独立页用）
    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): Page<CryptoTailDecisionEvent>

    fun findAllByCreatedAtBetweenOrderByCreatedAtDesc(
        startInclusive: Long,
        endInclusive: Long,
        pageable: Pageable
    ): Page<CryptoTailDecisionEvent>

    // 批量删除（按 id 集合），返回删除条数
    @Modifying
    @Query("DELETE FROM CryptoTailDecisionEvent e WHERE e.id IN :ids")
    fun deleteByIdInBulk(@Param("ids") ids: List<Long>): Int

    // 按时间清理（全部策略，createdAt < before），返回删除条数
    @Modifying
    @Query("DELETE FROM CryptoTailDecisionEvent e WHERE e.createdAt < :before")
    fun deleteByCreatedAtBeforeBulk(@Param("before") before: Long): Int

    // 按时间清理（指定策略，createdAt < before），返回删除条数
    @Modifying
    @Query("DELETE FROM CryptoTailDecisionEvent e WHERE e.strategyId = :strategyId AND e.createdAt < :before")
    fun deleteByStrategyIdAndCreatedAtBeforeBulk(
        @Param("strategyId") strategyId: Long,
        @Param("before") before: Long
    ): Int
}
