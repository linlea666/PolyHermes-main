package com.wrbug.polymarketbot.entity

import com.wrbug.polymarketbot.enums.ExitStatus
import com.wrbug.polymarketbot.enums.TradingMode
import com.wrbug.polymarketbot.enums.TradingModeConverter
import jakarta.persistence.*
import java.math.BigDecimal
import com.wrbug.polymarketbot.util.toSafeBigDecimal

/**
 * 加密价差策略触发记录
 */
@Entity
@Table(name = "crypto_tail_strategy_trigger")
data class CryptoTailStrategyTrigger(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "strategy_id", nullable = false)
    val strategyId: Long = 0L,

    @Column(name = "period_start_unix", nullable = false)
    val periodStartUnix: Long = 0L,

    @Column(name = "market_title", length = 500)
    val marketTitle: String? = null,

    @Column(name = "outcome_index", nullable = false)
    val outcomeIndex: Int = 0,

    @Column(name = "trigger_price", nullable = false, precision = 20, scale = 8)
    val triggerPrice: BigDecimal = BigDecimal.ZERO,

    @Column(name = "amount_usdc", nullable = false, precision = 20, scale = 8)
    val amountUsdc: BigDecimal = BigDecimal.ZERO,

    /** 实际成交份额（shares），来自下单响应 takingAmount；null=未知/未成交 */
    @Column(name = "filled_size", precision = 20, scale = 8)
    val filledSize: BigDecimal? = null,

    /** 实际成交金额（USDC），来自下单响应 makingAmount；null=未知/未成交 */
    @Column(name = "filled_amount", precision = 20, scale = 8)
    val filledAmount: BigDecimal? = null,

    /** 订单类型：FAK / GTC_POST_ONLY 等 */
    @Column(name = "order_type", length = 20)
    val orderType: String? = null,

    /** 进场 outcome 对应的 tokenId(assetId)，maker 生命周期对账/回退 FAK 时复用，避免依赖内存上下文 */
    @Column(name = "token_id", length = 128)
    val tokenId: String? = null,

    @Column(name = "order_id", length = 128)
    val orderId: String? = null,

    @Column(name = "condition_id", length = 66)
    val conditionId: String? = null,

    @Column(name = "resolved", nullable = false)
    val resolved: Boolean = false,

    @Column(name = "winner_outcome_index")
    val winnerOutcomeIndex: Int? = null,

    @Column(name = "realized_pnl", precision = 20, scale = 8)
    val realizedPnl: BigDecimal? = null,

    @Column(name = "settled_at")
    val settledAt: Long? = null,

    @Column(name = "status", nullable = false, length = 20)
    val status: String = "success",

    @Column(name = "fail_reason", length = 500)
    val failReason: String? = null,

    @Column(name = "trigger_type", nullable = false, length = 20)
    val triggerType: String = "AUTO",

    /** 冗余冻结当时模式（V52 引入）：避免后续修改策略表 mode 字段污染历史触发的语义 */
    @Convert(converter = TradingModeConverter::class)
    @Column(name = "mode", nullable = false, columnDefinition = "TINYINT")
    val mode: TradingMode = TradingMode.LEGACY_SPREAD,

    /** 部分卖出后剩余 shares（仅 BRACKET_DYNAMIC 使用）；NULL=未填充, 0=已全部退出 */
    @Column(name = "remaining_size", precision = 20, scale = 8)
    val remainingSize: BigDecimal? = null,

    /** 退出状态机（仅 BRACKET_DYNAMIC 使用，其他模式恒为 NONE） */
    @Column(name = "exit_status", nullable = false, length = 20)
    val exitStatus: String = ExitStatus.NONE.name,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),

    @Column(name = "notification_sent", nullable = false)
    var notificationSent: Boolean = false
)
