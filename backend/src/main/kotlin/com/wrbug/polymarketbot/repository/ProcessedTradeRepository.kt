package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.ProcessedTrade
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

/**
 * 已处理交易Repository
 */
@Repository
interface ProcessedTradeRepository : JpaRepository<ProcessedTrade, Long> {
    
    /**
     * 检查交易是否已处理
     */
    fun existsByLeaderIdAndLeaderTradeId(leaderId: Long, leaderTradeId: String): Boolean
    
    /**
     * 根据Leader ID和交易ID查询
     */
    fun findByLeaderIdAndLeaderTradeId(leaderId: Long, leaderTradeId: String): ProcessedTrade?
    
    /**
     * 删除过期记录
     */
    @Modifying
    @Query("DELETE FROM ProcessedTrade p WHERE p.processedAt < :expireTime")
    fun deleteByProcessedAtBefore(expireTime: Long): Int
}

