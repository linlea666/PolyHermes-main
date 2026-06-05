package com.wrbug.polymarketbot.entity

import jakarta.persistence.*
import java.math.BigDecimal

/**
 * 加密尾盘策略「周期生命周期」汇总（TAIL_DIFF 回测用）。
 *
 * 每个 (strategyId, periodStartUnix) 一行，把该周期内分散在 [CryptoTailDecisionEvent] 的全链路决策
 * 聚合成一条可读记录，并在周期结束后并入官方结算结果（与 Polymarket 结算同源的 Chainlink open/close），
 * 用于评估「策略首选方向 vs 官方实际结果」的方向准确率，以及该周期是否成交、是否中途翻转方向。
 *
 * 与决策事件解耦：仅作观测/回测，由 [com.wrbug.polymarketbot.service.cryptotail.CryptoTailPeriodSummaryService]
 * 定时聚合+回填，主交易链路不依赖本表。
 */
@Entity
@Table(
    name = "crypto_tail_period_summary",
    uniqueConstraints = [UniqueConstraint(name = "uk_ct_period_summary", columnNames = ["strategy_id", "period_start_unix"])]
)
data class CryptoTailPeriodSummary(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "strategy_id", nullable = false)
    val strategyId: Long = 0L,

    @Column(name = "period_start_unix", nullable = false)
    val periodStartUnix: Long = 0L,

    @Column(name = "period_end_unix", nullable = false)
    val periodEndUnix: Long = 0L,

    @Column(name = "market_slug", length = 128)
    val marketSlug: String? = null,

    /** 该周期首条决策选定的领先方向（modelSide：0=Up,1=Down） */
    @Column(name = "first_chosen_outcome_index")
    val firstChosenOutcomeIndex: Int? = null,

    /** 该周期末条决策选定的领先方向 */
    @Column(name = "last_chosen_outcome_index")
    val lastChosenOutcomeIndex: Int? = null,

    /** 周期内方向翻转次数（modelSide 相邻变化计数） */
    @Column(name = "direction_flip_count", nullable = false)
    val directionFlipCount: Int = 0,

    /** 周期内（领先方向上）达到过的最高评分 */
    @Column(name = "best_score", nullable = false)
    val bestScore: Int = 0,

    /** 周期内出现最多的主导否决原因 */
    @Column(name = "dominant_veto", length = 64)
    val dominantVeto: String? = null,

    @Column(name = "score_event_count", nullable = false)
    val scoreEventCount: Int = 0,

    @Column(name = "skip_event_count", nullable = false)
    val skipEventCount: Int = 0,

    @Column(name = "buy_event_count", nullable = false)
    val buyEventCount: Int = 0,

    /** 该周期是否实际成交（关联到 trigger） */
    @Column(name = "traded", nullable = false)
    val traded: Boolean = false,

    @Column(name = "trigger_id")
    val triggerId: Long? = null,

    /** 官方结算期初价（Chainlink，与 Polymarket 结算同源） */
    @Column(name = "official_open", precision = 30, scale = 8)
    val officialOpen: BigDecimal? = null,

    @Column(name = "official_close", precision = 30, scale = 8)
    val officialClose: BigDecimal? = null,

    @Column(name = "official_gap", precision = 30, scale = 8)
    val officialGap: BigDecimal? = null,

    /** 官方结算赢家方向（0=Up,1=Down），由 close 与 open 比较推导（close≥open → Up 胜） */
    @Column(name = "settled_winner_outcome_index")
    val settledWinnerOutcomeIndex: Int? = null,

    /** 首选方向是否与官方结算一致 */
    @Column(name = "direction_correct")
    val directionCorrect: Boolean? = null,

    /** 若成交，该周期已实现盈亏（来自 trigger） */
    @Column(name = "realized_pnl", precision = 30, scale = 8)
    val realizedPnl: BigDecimal? = null,

    /** 生命周期状态：OPEN（未结算回填）/ SETTLED（官方结果已并入） */
    @Column(name = "status", nullable = false, length = 16)
    val status: String = "OPEN",

    @Column(name = "settled_at")
    val settledAt: Long? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),

    @Column(name = "updated_at", nullable = false)
    val updatedAt: Long = System.currentTimeMillis()
)
