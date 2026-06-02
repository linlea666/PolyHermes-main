package com.wrbug.polymarketbot.entity

import jakarta.persistence.*
import java.math.BigDecimal

/**
 * 概率阶梯止盈模式（BRACKET_DYNAMIC）退出明细记录
 *
 * 一笔入场 trigger 对应 0~N 笔退出（多档止盈/止损/强制平仓），每笔卖单独立记录用于：
 *  - 仓位状态机的真相来源（与 trigger.remainingSize 配合）
 *  - 部分成交追踪（filledSize/filledAmount 由 Reconciler 回填）
 *  - 复盘分析（pwinAtDecision/bestBidAtDecision/decisionReason 决策快照）
 *  - PnL 计算（结算时按 sum(filled_amount) - buyAmount 求差）
 */
@Entity
@Table(
    name = "crypto_tail_strategy_exit",
    indexes = [
        Index(name = "idx_trigger_id", columnList = "trigger_id"),
        Index(name = "idx_strategy_status", columnList = "strategy_id,status"),
        Index(name = "idx_status_created", columnList = "status,created_at")
    ]
)
data class CryptoTailStrategyExit(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "trigger_id", nullable = false)
    val triggerId: Long = 0L,

    @Column(name = "strategy_id", nullable = false)
    val strategyId: Long = 0L,

    /** 退出类型: TP1/TP2/STOP/TRAILING_STOP/FORCE/SETTLE，对应 [com.wrbug.polymarketbot.enums.ExitKind] */
    @Column(name = "exit_kind", nullable = false, length = 16)
    val exitKind: String = "",

    /** 本次目标卖出 shares */
    @Column(name = "target_size", nullable = false, precision = 20, scale = 8)
    val targetSize: BigDecimal = BigDecimal.ZERO,

    /** 实际成交 shares；NULL=未知/未成交 */
    @Column(name = "filled_size", precision = 20, scale = 8)
    val filledSize: BigDecimal? = null,

    /** 实际成交 USDC（卖单 makingAmount）；NULL=未知/未成交 */
    @Column(name = "filled_amount", precision = 20, scale = 8)
    val filledAmount: BigDecimal? = null,

    /** 挂单价/期望成交价 */
    @Column(name = "exit_price", precision = 20, scale = 8)
    val exitPrice: BigDecimal? = null,

    @Column(name = "order_id", length = 128)
    val orderId: String? = null,

    /** 订单类型: FAK/GTC/GTC_POST_ONLY */
    @Column(name = "order_type", length = 20)
    val orderType: String? = null,

    /** 状态: pending/success/failed/cancelled/unfilled */
    @Column(name = "status", nullable = false, length = 20)
    val status: String = "pending",

    /** 决策时 pWin 快照 */
    @Column(name = "pwin_at_decision", precision = 20, scale = 8)
    val pwinAtDecision: BigDecimal? = null,

    /** 决策时 bestBid 快照 */
    @Column(name = "best_bid_at_decision", precision = 20, scale = 8)
    val bestBidAtDecision: BigDecimal? = null,

    /** 决策时剩余秒数快照 */
    @Column(name = "remaining_seconds")
    val remainingSeconds: Int? = null,

    /** 决策原因（便于复盘） */
    @Column(name = "decision_reason", length = 500)
    val decisionReason: String? = null,

    /** 失败原因 */
    @Column(name = "fail_reason", length = 500)
    val failReason: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),

    /** 终态写入时间（success/failed/cancelled/unfilled 时填充） */
    @Column(name = "settled_at")
    val settledAt: Long? = null
)
