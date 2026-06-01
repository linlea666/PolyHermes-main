package com.wrbug.polymarketbot.entity

import jakarta.persistence.*
import java.math.BigDecimal

/**
 * 匹配明细实体
 * 记录每笔卖出订单与买入订单的匹配明细
 */
@Entity
@Table(name = "sell_match_detail")
data class SellMatchDetail(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "match_record_id", nullable = false)
    val matchRecordId: Long,  // 关联 sell_match_record.id
    
    @Column(name = "tracking_id", nullable = false)
    val trackingId: Long,  // 关联 copy_order_tracking.id
    
    @Column(name = "buy_order_id", nullable = false, length = 100)
    val buyOrderId: String,  // 买入订单ID
    
    @Column(name = "matched_quantity", nullable = false, precision = 20, scale = 8)
    val matchedQuantity: BigDecimal,  // 匹配的数量
    
    @Column(name = "buy_price", nullable = false, precision = 20, scale = 8)
    val buyPrice: BigDecimal,  // 买入价格
    
    @Column(name = "sell_price", nullable = false, precision = 20, scale = 8)
    val sellPrice: BigDecimal,  // 卖出价格
    
    @Column(name = "realized_pnl", nullable = false, precision = 20, scale = 8)
    val realizedPnl: BigDecimal,  // 盈亏 = (sell_price - buy_price) * matched_quantity
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis()
)

