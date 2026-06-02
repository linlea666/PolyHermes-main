package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.CryptoTailStrategyExit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.math.BigDecimal

interface CryptoTailStrategyExitRepository : JpaRepository<CryptoTailStrategyExit, Long> {

    /** 按 trigger 查所有退出明细（前端展开行用） */
    fun findByTriggerIdOrderByCreatedAtAsc(triggerId: Long): List<CryptoTailStrategyExit>

    /** Reconciler 扫描：查所有 pending 的退出单，按创建时间正序处理 */
    fun findByStatusOrderByCreatedAtAsc(status: String): List<CryptoTailStrategyExit>

    /** 该 trigger 是否还有未完成（pending）的退出单——用于 BracketExitService 防重复挂单 */
    fun countByTriggerIdAndStatus(triggerId: Long, status: String): Long

    /** 该 trigger 已成功的退出单（用于 SettlementService 按笔遍历计算 maker/taker 费用 + 每笔 gas） */
    fun findByTriggerIdAndStatus(triggerId: Long, status: String): List<CryptoTailStrategyExit>

    /** 该 trigger 已成功退出的 USDC 总额（卖单收回的 makingAmount） */
    @Query("SELECT COALESCE(SUM(e.filledAmount), 0) FROM CryptoTailStrategyExit e " +
        "WHERE e.triggerId = :triggerId AND e.status = 'success'")
    fun sumFilledAmountByTriggerId(@Param("triggerId") triggerId: Long): BigDecimal?

    /** 该 trigger 已成功退出的 shares 总数 */
    @Query("SELECT COALESCE(SUM(e.filledSize), 0) FROM CryptoTailStrategyExit e " +
        "WHERE e.triggerId = :triggerId AND e.status = 'success'")
    fun sumFilledSizeByTriggerId(@Param("triggerId") triggerId: Long): BigDecimal?
}
