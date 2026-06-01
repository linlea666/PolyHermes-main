package com.wrbug.polymarketbot.entity

import jakarta.persistence.*
import java.math.BigDecimal

/**
 * 被过滤订单实体
 * 记录因筛选条件不满足而被过滤的订单信息
 */
@Entity
@Table(name = "filtered_order")
data class FilteredOrder(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "copy_trading_id", nullable = false)
    val copyTradingId: Long,
    
    @Column(name = "account_id", nullable = false)
    val accountId: Long,
    
    @Column(name = "leader_id", nullable = false)
    val leaderId: Long,
    
    @Column(name = "leader_trade_id", nullable = false, length = 100)
    val leaderTradeId: String,  // Leader 的交易ID
    
    @Column(name = "market_id", nullable = false, length = 100)
    val marketId: String,
    
    @Column(name = "market_title", length = 500)
    val marketTitle: String? = null,  // 市场标题（从 API 获取）
    
    @Column(name = "market_slug", length = 200)
    val marketSlug: String? = null,  // 市场 slug（用于生成链接）
    
    @Column(name = "side", nullable = false, length = 10)
    val side: String,  // BUY 或 SELL
    
    @Column(name = "outcome_index", nullable = true)
    val outcomeIndex: Int? = null,  // 结果索引（0, 1, 2, ...），支持多元市场
    
    @Column(name = "outcome", length = 50)
    val outcome: String? = null,  // 市场方向（如 YES, NO 等）
    
    @Column(name = "price", nullable = false, precision = 20, scale = 8)
    val price: BigDecimal,  // Leader 交易价格
    
    @Column(name = "size", nullable = false, precision = 20, scale = 8)
    val size: BigDecimal,  // Leader 交易数量
    
    @Column(name = "calculated_quantity", precision = 20, scale = 8)
    val calculatedQuantity: BigDecimal? = null,  // 计算出的跟单数量（如果已计算）
    
    @Column(name = "filter_reason", nullable = false, columnDefinition = "TEXT")
    val filterReason: String,  // 过滤原因（详细说明）
    
    @Column(name = "filter_type", nullable = false, length = 50)
    val filterType: String,  // 过滤类型（如 ORDER_DEPTH, SPREAD, ORDERBOOK_DEPTH 等）
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis()
)

