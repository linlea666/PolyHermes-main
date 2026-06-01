package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.FilteredOrder
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface FilteredOrderRepository : JpaRepository<FilteredOrder, Long> {
    
    /**
     * 根据跟单配置ID查询被过滤的订单（分页）
     */
    fun findByCopyTradingIdOrderByCreatedAtDesc(
        copyTradingId: Long,
        pageable: Pageable
    ): Page<FilteredOrder>
    
    /**
     * 根据跟单配置ID和过滤类型查询被过滤的订单（分页）
     */
    fun findByCopyTradingIdAndFilterTypeOrderByCreatedAtDesc(
        copyTradingId: Long,
        filterType: String,
        pageable: Pageable
    ): Page<FilteredOrder>
    
    /**
     * 根据跟单配置ID和时间范围查询被过滤的订单（分页）
     */
    @Query("SELECT f FROM FilteredOrder f WHERE f.copyTradingId = :copyTradingId AND f.createdAt >= :startTime AND f.createdAt <= :endTime ORDER BY f.createdAt DESC")
    fun findByCopyTradingIdAndTimeRange(
        @Param("copyTradingId") copyTradingId: Long,
        @Param("startTime") startTime: Long,
        @Param("endTime") endTime: Long,
        pageable: Pageable
    ): Page<FilteredOrder>
    
    /**
     * 统计某个跟单配置的被过滤订单数量
     */
    fun countByCopyTradingId(copyTradingId: Long): Long
    
    /**
     * 统计某个跟单配置的某个过滤类型的被过滤订单数量
     */
    fun countByCopyTradingIdAndFilterType(copyTradingId: Long, filterType: String): Long
}

