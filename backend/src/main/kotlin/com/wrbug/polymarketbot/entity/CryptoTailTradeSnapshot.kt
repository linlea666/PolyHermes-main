package com.wrbug.polymarketbot.entity

import jakarta.persistence.*
import java.math.BigDecimal

/**
 * 加密尾盘策略单笔成交全链路分析快照（障碍模式）。
 * 一笔进场一行，随生命周期（下单→成交→结算）由异步投影监听器 upsert，强类型列便于回测/复盘聚合，
 * 与下单/结算主流程解耦（投影只消费决策事件，不影响交易热路径）。
 * 唯一键 (strategy_id, period_start_unix)：每策略每周期最多进场一次。
 */
@Entity
@Table(
    name = "crypto_tail_trade_snapshot",
    uniqueConstraints = [UniqueConstraint(name = "uk_ctts_strategy_period", columnNames = ["strategy_id", "period_start_unix"])]
)
data class CryptoTailTradeSnapshot(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "strategy_id", nullable = false)
    val strategyId: Long = 0L,

    @Column(name = "trigger_id")
    val triggerId: Long? = null,

    @Column(name = "period_start_unix", nullable = false)
    val periodStartUnix: Long = 0L,

    @Column(name = "correlation_id", nullable = false, length = 64)
    val correlationId: String = "",

    @Column(name = "market_slug", length = 64)
    val marketSlug: String? = null,

    @Column(name = "condition_id", length = 66)
    val conditionId: String? = null,

    @Column(name = "outcome_index")
    val outcomeIndex: Int? = null,

    @Column(name = "interval_seconds", nullable = false)
    val intervalSeconds: Int = 0,

    // ===== 进场信号 =====
    @Column(name = "open_price", precision = 30, scale = 8)
    val openPrice: BigDecimal? = null,

    /** 进场时的标记价（币安当前收盘价） */
    @Column(name = "entry_mark_price", precision = 30, scale = 8)
    val entryMarkPrice: BigDecimal? = null,

    @Column(name = "entry_gap", precision = 30, scale = 8)
    val entryGap: BigDecimal? = null,

    @Column(name = "sigma_per_sqrt_s", precision = 30, scale = 12)
    val sigmaPerSqrtS: BigDecimal? = null,

    @Column(name = "p_win", precision = 12, scale = 8)
    val pWin: BigDecimal? = null,

    @Column(name = "safe_ratio", precision = 20, scale = 8)
    val safeRatio: BigDecimal? = null,

    @Column(name = "model_side")
    val modelSide: Int? = null,

    @Column(name = "remaining_seconds_at_entry")
    val remainingSecondsAtEntry: Long? = null,

    // ===== 进场盘口 =====
    @Column(name = "best_bid", precision = 12, scale = 8)
    val bestBid: BigDecimal? = null,

    @Column(name = "best_ask", precision = 12, scale = 8)
    val bestAsk: BigDecimal? = null,

    @Column(name = "mid_price", precision = 12, scale = 8)
    val midPrice: BigDecimal? = null,

    @Column(name = "effective_cost", precision = 12, scale = 8)
    val effectiveCost: BigDecimal? = null,

    @Column(name = "entry_edge", precision = 12, scale = 8)
    val entryEdge: BigDecimal? = null,

    // ===== 进场阈值快照（复现/防配置漂移） =====
    @Column(name = "entry_prob_threshold", precision = 12, scale = 8)
    val entryProbThreshold: BigDecimal? = null,

    @Column(name = "entry_edge_threshold", precision = 12, scale = 8)
    val entryEdgeThreshold: BigDecimal? = null,

    @Column(name = "barrier_min_market_prob", precision = 12, scale = 8)
    val barrierMinMarketProb: BigDecimal? = null,

    @Column(name = "sigma_scale", precision = 12, scale = 8)
    val sigmaScale: BigDecimal? = null,

    @Column(name = "max_entry_price", precision = 12, scale = 8)
    val maxEntryPrice: BigDecimal? = null,

    @Column(name = "cost_buffer", precision = 12, scale = 8)
    val costBuffer: BigDecimal? = null,

    // ===== 订单 =====
    @Column(name = "order_type", length = 20)
    val orderType: String? = null,

    @Column(name = "target_price", precision = 12, scale = 8)
    val targetPrice: BigDecimal? = null,

    @Column(name = "requested_amount", precision = 20, scale = 8)
    val requestedAmount: BigDecimal? = null,

    @Column(name = "submit_ts")
    val submitTs: Long? = null,

    // ===== 成交 =====
    @Column(name = "fill_status", length = 20)
    val fillStatus: String? = null,

    @Column(name = "fill_price", precision = 12, scale = 8)
    val fillPrice: BigDecimal? = null,

    @Column(name = "fill_size", precision = 30, scale = 8)
    val fillSize: BigDecimal? = null,

    @Column(name = "fill_amount", precision = 20, scale = 8)
    val fillAmount: BigDecimal? = null,

    @Column(name = "slippage", precision = 12, scale = 8)
    val slippage: BigDecimal? = null,

    @Column(name = "order_id", length = 128)
    val orderId: String? = null,

    @Column(name = "exec_error", columnDefinition = "TEXT")
    val execError: String? = null,

    // ===== 结算 =====
    @Column(name = "settled", nullable = false)
    val settled: Boolean = false,

    @Column(name = "winner_outcome_index")
    val winnerOutcomeIndex: Int? = null,

    @Column(name = "won")
    val won: Boolean? = null,

    @Column(name = "realized_pnl", precision = 20, scale = 8)
    val realizedPnl: BigDecimal? = null,

    @Column(name = "settle_ts")
    val settleTs: Long? = null,

    @Column(name = "hold_seconds")
    val holdSeconds: Long? = null,

    @Column(name = "final_open", precision = 30, scale = 8)
    val finalOpen: BigDecimal? = null,

    @Column(name = "final_close", precision = 30, scale = 8)
    val finalClose: BigDecimal? = null,

    @Column(name = "final_gap", precision = 30, scale = 8)
    val finalGap: BigDecimal? = null,

    @Column(name = "reversed")
    val reversed: Boolean? = null,

    @Column(name = "settle_source", length = 20)
    val settleSource: String? = null,

    // ===== 归因 =====
    /** 亏损归因: REVERSAL(市场反转)/SETTLE_MISMATCH(终值同号却判负,口径不一致)/UNFILLED(未成交)/UNKNOWN */
    @Column(name = "loss_reason", length = 20)
    val lossReason: String? = null,

    /** pWin 可靠性分箱（floor(pWin*20)，5% 一箱，0~19） */
    @Column(name = "pwin_bucket")
    val pwinBucket: Int? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),

    @Column(name = "updated_at", nullable = false)
    val updatedAt: Long = System.currentTimeMillis()
)
