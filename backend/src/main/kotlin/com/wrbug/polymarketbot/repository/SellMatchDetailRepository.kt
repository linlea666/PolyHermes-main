package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.SellMatchDetail
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

/**
 * 匹配明细Repository
 */
@Repository
interface SellMatchDetailRepository : JpaRepository<SellMatchDetail, Long> {
    
    /**
     * 根据匹配记录ID查询所有明细
     */
    fun findByMatchRecordId(matchRecordId: Long): List<SellMatchDetail>
    
    /**
     * 根据跟踪ID查询所有明细
     */
    fun findByTrackingId(trackingId: Long): List<SellMatchDetail>
    
    /**
     * 根据买入订单ID查询所有明细
     */
    fun findByBuyOrderId(buyOrderId: String): List<SellMatchDetail>
    
    /**
     * 根据卖出订单ID查询所有明细（通过匹配记录关联）
     */
    @Query("SELECT d FROM SellMatchDetail d JOIN SellMatchRecord r ON d.matchRecordId = r.id WHERE r.sellOrderId = :sellOrderId")
    fun findBySellOrderId(sellOrderId: String): List<SellMatchDetail>
    
    /**
     * 根据跟单关系ID查询所有明细（通过匹配记录关联）
     */
    @Query("SELECT d FROM SellMatchDetail d JOIN SellMatchRecord r ON d.matchRecordId = r.id WHERE r.copyTradingId = :copyTradingId")
    fun findByCopyTradingId(copyTradingId: Long): List<SellMatchDetail>
}

