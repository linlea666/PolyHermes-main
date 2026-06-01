package com.wrbug.polymarketbot.entity

import jakarta.persistence.*

/**
 * 已处理交易实体
 * 用于去重，确保同一笔交易只处理一次
 */
@Entity
@Table(
    name = "processed_trade",
    uniqueConstraints = [
        UniqueConstraint(columnNames = ["leader_id", "leader_trade_id"])
    ]
)
data class ProcessedTrade(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "leader_id", nullable = false)
    val leaderId: Long,
    
    @Column(name = "leader_trade_id", nullable = false, length = 100)
    val leaderTradeId: String,  // Leader 的交易ID（trade.id，唯一标识）
    
    @Column(name = "trade_type", nullable = false, length = 10)
    val tradeType: String,  // BUY 或 SELL
    
    @Column(name = "source", nullable = false, length = 20)
    val source: String,  // websocket 或 polling
    
    @Column(name = "status", nullable = false, length = 20)
    val status: String = "SUCCESS",  // SUCCESS（成功）或 FAILED（失败）
    
    @Column(name = "processed_at", nullable = false)
    val processedAt: Long = System.currentTimeMillis(),
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis()
)

