package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.CopyTrading
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/**
 * 跟单关系 Repository
 */
@Repository
interface CopyTradingRepository : JpaRepository<CopyTrading, Long> {
    
    /**
     * 根据账户ID查找跟单列表
     */
    fun findByAccountId(accountId: Long): List<CopyTrading>
    
    /**
     * 根据 Leader ID 查找跟单列表
     */
    fun findByLeaderId(leaderId: Long): List<CopyTrading>

    /**
     * 根据 Leader ID 批量查找跟单列表，用于聚合页面避免 N+1 查询。
     */
    fun findByLeaderIdIn(leaderIds: Collection<Long>): List<CopyTrading>
    
    /**
     * 根据账户ID和Leader ID查找跟单列表
     */
    fun findByAccountIdAndLeaderId(
        accountId: Long,
        leaderId: Long
    ): List<CopyTrading>
    
    /**
     * 查找所有启用的跟单
     */
    fun findByEnabledTrue(): List<CopyTrading>
    
    /**
     * 根据账户ID查找启用的跟单
     */
    fun findByAccountIdAndEnabledTrue(accountId: Long): List<CopyTrading>
    
    /**
     * 根据Leader ID查找启用的跟单
     */
    fun findByLeaderIdAndEnabledTrue(leaderId: Long): List<CopyTrading>
    
    /**
     * 统计指定 Leader 的跟单数量
     */
    fun countByLeaderId(leaderId: Long): Long
}
