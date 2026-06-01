package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.Leader
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/**
 * Leader Repository
 */
@Repository
interface LeaderRepository : JpaRepository<Leader, Long> {
    
    /**
     * 根据钱包地址查找 Leader
     */
    fun findByLeaderAddress(leaderAddress: String): Leader?
    
    /**
     * 检查钱包地址是否存在
     */
    fun existsByLeaderAddress(leaderAddress: String): Boolean
    
    /**
     * 根据分类查找 Leader 列表
     */
    fun findByCategory(category: String?): List<Leader>
    
    /**
     * 查找所有 Leader，按创建时间排序
     */
    fun findAllByOrderByCreatedAtAsc(): List<Leader>

    fun findByIdIn(ids: Collection<Long>): List<Leader>
}
