package com.wrbug.polymarketbot.entity

import jakarta.persistence.*
import java.math.BigDecimal

/**
 * 加密尾盘策略成交复盘因子（reversal / 方向错误 多维归因）。
 *
 * 一笔成交一行，由 [com.wrbug.polymarketbot.service.cryptotail.CryptoTailTradeForensicsService] 定时聚合：
 *   复用 [CryptoTailTradeSnapshot]（进场/成交/结算派生，已 durable）
 *   + 扫描 [CryptoTailDecisionEvent] 的 EXIT_CHECK/退出事件派生「反转动态/出场归因/成交质量」。
 *
 * 本表为 durable 派生表：派生量算好写入后，即使决策日志被清理也不影响后续统计。面向「调参用的多维因子分析」。
 * 唯一键 (strategy_id, period_start_unix)：每策略每周期最多进场一次。
 */
@Entity
@Table(
    name = "crypto_tail_trade_forensics",
    uniqueConstraints = [UniqueConstraint(name = "uk_ctf_strategy_period", columnNames = ["strategy_id", "period_start_unix"])]
)
data class CryptoTailTradeForensics(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    // ===== 身份 =====
    @Column(name = "strategy_id", nullable = false)
    val strategyId: Long = 0L,

    @Column(name = "account_id")
    val accountId: Long? = null,

    @Column(name = "market_slug", length = 64)
    val marketSlug: String? = null,

    @Column(name = "interval_seconds", nullable = false)
    val intervalSeconds: Int = 0,

    @Column(name = "period_start_unix", nullable = false)
    val periodStartUnix: Long = 0L,

    @Column(name = "trigger_id")
    val triggerId: Long? = null,

    @Column(name = "correlation_id", nullable = false, length = 64)
    val correlationId: String = "",

    @Column(name = "mode")
    val mode: Int? = null,

    @Column(name = "outcome_index")
    val outcomeIndex: Int? = null,

    // ===== 进场（官方口径） =====
    @Column(name = "entry_ts")
    val entryTs: Long? = null,

    @Column(name = "entry_remaining_seconds")
    val entryRemainingSeconds: Int? = null,

    @Column(name = "entry_official_target", precision = 30, scale = 8)
    val entryOfficialTarget: BigDecimal? = null,

    @Column(name = "entry_current_price", precision = 30, scale = 8)
    val entryCurrentPrice: BigDecimal? = null,

    @Column(name = "entry_gap", precision = 30, scale = 8)
    val entryGap: BigDecimal? = null,

    @Column(name = "entry_gap_abs", precision = 30, scale = 8)
    val entryGapAbs: BigDecimal? = null,

    @Column(name = "entry_gap_pct", precision = 20, scale = 8)
    val entryGapPct: BigDecimal? = null,

    @Column(name = "entry_diff_sigma", precision = 20, scale = 8)
    val entryDiffSigma: BigDecimal? = null,

    @Column(name = "entry_pwin", precision = 12, scale = 8)
    val entryPwin: BigDecimal? = null,

    @Column(name = "entry_model_side")
    val entryModelSide: Int? = null,

    @Column(name = "entry_best_bid", precision = 12, scale = 8)
    val entryBestBid: BigDecimal? = null,

    @Column(name = "entry_best_ask", precision = 12, scale = 8)
    val entryBestAsk: BigDecimal? = null,

    @Column(name = "entry_fill_price", precision = 12, scale = 8)
    val entryFillPrice: BigDecimal? = null,

    @Column(name = "entry_wall_hour")
    val entryWallHour: Int? = null,

    @Column(name = "entry_dow")
    val entryDow: Int? = null,

    // ===== 分桶（对齐 TailDiffBuckets） =====
    @Column(name = "entry_diff_sigma_bucket", length = 32)
    val entryDiffSigmaBucket: String? = null,

    @Column(name = "entry_odds_bucket", length = 32)
    val entryOddsBucket: String? = null,

    @Column(name = "entry_remaining_bucket", length = 32)
    val entryRemainingBucket: String? = null,

    // ===== 进场成交质量 =====
    @Column(name = "fill_vs_band_dev", precision = 12, scale = 8)
    val fillVsBandDev: BigDecimal? = null,

    @Column(name = "requote_count")
    val requoteCount: Int? = null,

    @Column(name = "submit_latency_ms")
    val submitLatencyMs: Long? = null,

    @Column(name = "entry_slippage", precision = 12, scale = 8)
    val entrySlippage: BigDecimal? = null,

    // ===== 反转动态（派生自 EXIT_CHECK 轨迹） =====
    @Column(name = "lead_reversed")
    val leadReversed: Boolean? = null,

    @Column(name = "first_reversal_remaining_seconds")
    val firstReversalRemainingSeconds: Int? = null,

    @Column(name = "trough_safe_ratio", precision = 20, scale = 8)
    val troughSafeRatio: BigDecimal? = null,

    @Column(name = "trough_gap", precision = 30, scale = 8)
    val troughGap: BigDecimal? = null,

    @Column(name = "max_diff_retrace_pct", precision = 20, scale = 8)
    val maxDiffRetracePct: BigDecimal? = null,

    @Column(name = "min_best_bid", precision = 12, scale = 8)
    val minBestBid: BigDecimal? = null,

    @Column(name = "peak_best_bid", precision = 12, scale = 8)
    val peakBestBid: BigDecimal? = null,

    @Column(name = "reversal_sample_count")
    val reversalSampleCount: Int? = null,

    @Column(name = "recovered_after_reversal")
    val recoveredAfterReversal: Boolean? = null,

    // ===== MAE/MFE =====
    @Column(name = "mae_odds", precision = 12, scale = 8)
    val maeOdds: BigDecimal? = null,

    @Column(name = "mfe_odds", precision = 12, scale = 8)
    val mfeOdds: BigDecimal? = null,

    @Column(name = "mae_sigma", precision = 20, scale = 8)
    val maeSigma: BigDecimal? = null,

    @Column(name = "mfe_sigma", precision = 20, scale = 8)
    val mfeSigma: BigDecimal? = null,

    // ===== 出场/结算 =====
    @Column(name = "exit_kind", length = 40)
    val exitKind: String? = null,

    @Column(name = "exit_reason", columnDefinition = "TEXT")
    val exitReason: String? = null,

    @Column(name = "was_cut")
    val wasCut: Boolean? = null,

    @Column(name = "exit_price", precision = 12, scale = 8)
    val exitPrice: BigDecimal? = null,

    @Column(name = "exit_slippage", precision = 12, scale = 8)
    val exitSlippage: BigDecimal? = null,

    @Column(name = "exit_executable_depth_usd", precision = 20, scale = 8)
    val exitExecutableDepthUsd: BigDecimal? = null,

    @Column(name = "hold_seconds")
    val holdSeconds: Long? = null,

    @Column(name = "settled", nullable = false)
    val settled: Boolean = false,

    @Column(name = "won")
    val won: Boolean? = null,

    @Column(name = "winner_outcome_index")
    val winnerOutcomeIndex: Int? = null,

    @Column(name = "final_official_target", precision = 30, scale = 8)
    val finalOfficialTarget: BigDecimal? = null,

    @Column(name = "final_current_price", precision = 30, scale = 8)
    val finalCurrentPrice: BigDecimal? = null,

    @Column(name = "final_gap", precision = 30, scale = 8)
    val finalGap: BigDecimal? = null,

    @Column(name = "realized_pnl", precision = 20, scale = 8)
    val realizedPnl: BigDecimal? = null,

    // ===== 反事实（若持有到结算） =====
    @Column(name = "would_have_won_if_held")
    val wouldHaveWonIfHeld: Boolean? = null,

    @Column(name = "counterfactual_hold_pnl", precision = 20, scale = 8)
    val counterfactualHoldPnl: BigDecimal? = null,

    @Column(name = "cut_vs_hold_delta", precision = 20, scale = 8)
    val cutVsHoldDelta: BigDecimal? = null,

    // ===== 配置指纹 =====
    @Column(name = "cfg_entry_min_pwin", precision = 12, scale = 8)
    val cfgEntryMinPwin: BigDecimal? = null,

    @Column(name = "cfg_min_model_prob", precision = 12, scale = 8)
    val cfgMinModelProb: BigDecimal? = null,

    @Column(name = "cfg_reversal_gate_enabled")
    val cfgReversalGateEnabled: Boolean? = null,

    @Column(name = "cfg_max_diff_retrace_pct", precision = 20, scale = 8)
    val cfgMaxDiffRetracePct: BigDecimal? = null,

    @Column(name = "cfg_min_model_prob_after_entry", precision = 12, scale = 8)
    val cfgMinModelProbAfterEntry: BigDecimal? = null,

    @Column(name = "cfg_gap_gate_enabled")
    val cfgGapGateEnabled: Boolean? = null,

    @Column(name = "cfg_fingerprint", length = 64)
    val cfgFingerprint: String? = null,

    // ===== 分类 =====
    @Column(name = "direction_correct")
    val directionCorrect: Boolean? = null,

    /** WON_HELD/WON_TP/CUT_BUT_WOULD_WIN/LOST_REVERSED/LOST_WRONG_FROM_START/UNFILLED */
    @Column(name = "outcome_category", length = 32)
    val outcomeCategory: String? = null,

    @Column(name = "source_max_event_id")
    val sourceMaxEventId: Long? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),

    @Column(name = "updated_at", nullable = false)
    val updatedAt: Long = System.currentTimeMillis()
)
