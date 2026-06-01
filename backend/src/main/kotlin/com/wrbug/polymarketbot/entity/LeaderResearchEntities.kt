package com.wrbug.polymarketbot.entity

import com.wrbug.polymarketbot.enums.*
import jakarta.persistence.*
import java.math.BigDecimal

@Entity
@Table(name = "leader_research_run")
data class LeaderResearchRun(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30, columnDefinition = "VARCHAR(30)")
    val status: LeaderResearchRunStatus = LeaderResearchRunStatus.RUNNING,

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 30, columnDefinition = "VARCHAR(30)")
    val triggerType: LeaderResearchTriggerType = LeaderResearchTriggerType.MANUAL,

    @Column(name = "dry_run", nullable = false)
    val dryRun: Boolean = false,

    @Column(name = "started_at", nullable = false)
    val startedAt: Long = System.currentTimeMillis(),

    @Column(name = "finished_at")
    val finishedAt: Long? = null,

    @Column(name = "duration_ms")
    val durationMs: Long? = null,

    @Column(name = "source_counts_json", columnDefinition = "TEXT")
    val sourceCountsJson: String? = null,

    @Column(name = "candidate_counts_json", columnDefinition = "TEXT")
    val candidateCountsJson: String? = null,

    @Column(name = "error_class")
    val errorClass: String? = null,

    @Column(name = "error_message", columnDefinition = "TEXT")
    val errorMessage: String? = null,

    @Column(name = "partial_failure", nullable = false)
    val partialFailure: Boolean = false,

    @Column(name = "skipped_reason")
    val skippedReason: String? = null,

    @Column(name = "last_event_cursor")
    val lastEventCursor: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),

    @Column(name = "updated_at", nullable = false)
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity
@Table(name = "leader_research_candidate")
data class LeaderResearchCandidate(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "normalized_wallet", nullable = false, length = 42, unique = true)
    val normalizedWallet: String,

    @Column(name = "leader_id")
    val leaderId: Long? = null,

    @Column(name = "pool_id")
    val poolId: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "research_state", nullable = false, length = 30, columnDefinition = "VARCHAR(30)")
    val researchState: LeaderResearchState = LeaderResearchState.DISCOVERED,

    @Column(name = "source", nullable = false, length = 50)
    val source: String = LeaderResearchSourceType.ACTIVITY_DERIVED.name,

    @Column(name = "source_rank")
    val sourceRank: Int? = null,

    @Column(name = "score", precision = 20, scale = 8)
    val score: BigDecimal? = null,

    @Column(name = "score_version", length = 100)
    val scoreVersion: String? = null,

    @Column(name = "reason", columnDefinition = "TEXT")
    val reason: String? = null,

    @Column(name = "risk_flags", columnDefinition = "TEXT")
    val riskFlags: String? = null,

    @Column(name = "locked", nullable = false)
    val locked: Boolean = false,

    @Column(name = "agent_owned", nullable = false)
    val agentOwned: Boolean = true,

    @Enumerated(EnumType.STRING)
    @Column(name = "provenance", nullable = false, length = 50, columnDefinition = "VARCHAR(50)")
    val provenance: LeaderCandidateProvenance = LeaderCandidateProvenance.AGENT_CREATED,

    @Column(name = "source_evidence", columnDefinition = "TEXT")
    val sourceEvidence: String? = null,

    @Column(name = "first_seen_at", nullable = false)
    val firstSeenAt: Long = System.currentTimeMillis(),

    @Column(name = "last_source_seen_at")
    val lastSourceSeenAt: Long? = null,

    @Column(name = "last_scored_at")
    val lastScoredAt: Long? = null,

    @Column(name = "cooldown_until")
    val cooldownUntil: Long? = null,

    @Column(name = "cooldown_count", nullable = false)
    val cooldownCount: Int = 0,

    @Column(name = "last_transition_at")
    val lastTransitionAt: Long? = null,

    @Column(name = "trial_ready_at")
    val trialReadyAt: Long? = null,

    @Column(name = "retired_at")
    val retiredAt: Long? = null,

    @Column(name = "last_paper_session_id")
    val lastPaperSessionId: Long? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),

    @Column(name = "updated_at", nullable = false)
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity
@Table(name = "leader_research_score")
data class LeaderResearchScore(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "candidate_id", nullable = false)
    val candidateId: Long,

    @Column(name = "run_id")
    val runId: Long? = null,

    @Column(name = "score_version", nullable = false, length = 100)
    val scoreVersion: String,

    @Column(name = "total_score", nullable = false, precision = 20, scale = 8)
    val totalScore: BigDecimal = BigDecimal.ZERO,

    @Column(name = "profit_signal", nullable = false, precision = 20, scale = 8)
    val profitSignal: BigDecimal = BigDecimal.ZERO,

    @Column(name = "repeatability", nullable = false, precision = 20, scale = 8)
    val repeatability: BigDecimal = BigDecimal.ZERO,

    @Column(name = "liquidity_fit", nullable = false, precision = 20, scale = 8)
    val liquidityFit: BigDecimal = BigDecimal.ZERO,

    @Column(name = "entry_price_fit", nullable = false, precision = 20, scale = 8)
    val entryPriceFit: BigDecimal = BigDecimal.ZERO,

    @Column(name = "slippage_risk", nullable = false, precision = 20, scale = 8)
    val slippageRisk: BigDecimal = BigDecimal.ZERO,

    @Column(name = "holding_period_fit", nullable = false, precision = 20, scale = 8)
    val holdingPeriodFit: BigDecimal = BigDecimal.ZERO,

    @Column(name = "market_type_risk", nullable = false, precision = 20, scale = 8)
    val marketTypeRisk: BigDecimal = BigDecimal.ZERO,

    @Column(name = "drawdown_risk", nullable = false, precision = 20, scale = 8)
    val drawdownRisk: BigDecimal = BigDecimal.ZERO,

    @Column(name = "exit_liquidity_risk", nullable = false, precision = 20, scale = 8)
    val exitLiquidityRisk: BigDecimal = BigDecimal.ZERO,

    @Column(name = "data_freshness", nullable = false, precision = 20, scale = 8)
    val dataFreshness: BigDecimal = BigDecimal.ZERO,

    @Column(name = "filter_pass_rate", nullable = false, precision = 20, scale = 8)
    val filterPassRate: BigDecimal = BigDecimal.ZERO,

    @Column(name = "sample_trade_count", nullable = false)
    val sampleTradeCount: Int = 0,

    @Column(name = "reason", columnDefinition = "TEXT")
    val reason: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis()
)

@Entity
@Table(name = "leader_research_event")
data class LeaderResearchEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "candidate_id")
    val candidateId: Long? = null,

    @Column(name = "run_id")
    val runId: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 50, columnDefinition = "VARCHAR(50)")
    val eventType: LeaderResearchEventType,

    @Column(name = "reason", columnDefinition = "TEXT")
    val reason: String? = null,

    @Column(name = "payload_summary", columnDefinition = "TEXT")
    val payloadSummary: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_status", nullable = false, length = 30, columnDefinition = "VARCHAR(30)")
    val notificationStatus: LeaderResearchNotificationStatus = LeaderResearchNotificationStatus.PENDING,

    @Column(name = "notification_error", columnDefinition = "TEXT")
    val notificationError: String? = null,

    @Column(name = "dedupe_key")
    val dedupeKey: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),

    @Column(name = "notified_at")
    val notifiedAt: Long? = null
)

@Entity
@Table(name = "leader_research_source_state")
data class LeaderResearchSourceState(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 50, unique = true, columnDefinition = "VARCHAR(50)")
    val sourceType: LeaderResearchSourceType,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30, columnDefinition = "VARCHAR(30)")
    val status: LeaderResearchSourceStatus = LeaderResearchSourceStatus.DISABLED,

    @Column(name = "last_success_at")
    val lastSuccessAt: Long? = null,

    @Column(name = "last_failure_at")
    val lastFailureAt: Long? = null,

    @Column(name = "last_run_at")
    val lastRunAt: Long? = null,

    @Column(name = "last_candidate_count", nullable = false)
    val lastCandidateCount: Int = 0,

    @Column(name = "error_class")
    val errorClass: String? = null,

    @Column(name = "error_message", columnDefinition = "TEXT")
    val errorMessage: String? = null,

    @Column(name = "stale", nullable = false)
    val stale: Boolean = false,

    @Column(name = "disabled_reason")
    val disabledReason: String? = null,

    @Column(name = "last_cursor")
    val lastCursor: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),

    @Column(name = "updated_at", nullable = false)
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity
@Table(name = "leader_activity_event")
data class LeaderActivityEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "source", nullable = false, length = 50)
    val source: String,

    @Column(name = "source_event_id")
    val sourceEventId: String? = null,

    @Column(name = "stable_event_key", nullable = false, unique = true)
    val stableEventKey: String,

    @Column(name = "normalized_wallet", length = 42)
    val normalizedWallet: String? = null,

    @Column(name = "market_id")
    val marketId: String? = null,

    @Column(name = "market_title")
    val marketTitle: String? = null,

    @Column(name = "market_slug")
    val marketSlug: String? = null,

    @Column(name = "asset")
    val asset: String? = null,

    @Column(name = "side", length = 20)
    val side: String? = null,

    @Column(name = "outcome")
    val outcome: String? = null,

    @Column(name = "outcome_index")
    val outcomeIndex: Int? = null,

    @Column(name = "price", precision = 20, scale = 8)
    val price: BigDecimal? = null,

    @Column(name = "size", precision = 20, scale = 8)
    val size: BigDecimal? = null,

    @Column(name = "amount", precision = 20, scale = 8)
    val amount: BigDecimal? = null,

    @Column(name = "event_time", nullable = false)
    val eventTime: Long,

    @Column(name = "raw_payload_hash", nullable = false, length = 128)
    val rawPayloadHash: String = "",

    @Column(name = "payload_summary", columnDefinition = "TEXT")
    val payloadSummary: String? = null,

    @Column(name = "usable_for_discovery", nullable = false)
    val usableForDiscovery: Boolean = false,

    @Column(name = "usable_for_paper", nullable = false)
    val usableForPaper: Boolean = false,

    @Column(name = "unusable_reason")
    val unusableReason: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "paper_processing_status", nullable = false, length = 30, columnDefinition = "VARCHAR(30)")
    val paperProcessingStatus: LeaderPaperProcessingStatus = LeaderPaperProcessingStatus.NEW,

    @Column(name = "processing_attempts", nullable = false)
    val processingAttempts: Int = 0,

    @Column(name = "paper_processing_started_at")
    val paperProcessingStartedAt: Long? = null,

    @Column(name = "paper_processed_at")
    val paperProcessedAt: Long? = null,

    @Column(name = "last_processing_error", columnDefinition = "TEXT")
    val lastProcessingError: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),

    @Column(name = "updated_at", nullable = false)
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity
@Table(name = "leader_paper_session")
data class LeaderPaperSession(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "candidate_id", nullable = false)
    val candidateId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30, columnDefinition = "VARCHAR(30)")
    val status: LeaderPaperSessionStatus = LeaderPaperSessionStatus.ACTIVE,

    @Column(name = "started_at", nullable = false)
    val startedAt: Long = System.currentTimeMillis(),

    @Column(name = "ended_at")
    val endedAt: Long? = null,

    @Column(name = "trade_count", nullable = false)
    val tradeCount: Int = 0,

    @Column(name = "filtered_count", nullable = false)
    val filteredCount: Int = 0,

    @Column(name = "open_exposure", nullable = false, precision = 20, scale = 8)
    val openExposure: BigDecimal = BigDecimal.ZERO,

    @Column(name = "total_realized_pnl", nullable = false, precision = 20, scale = 8)
    val totalRealizedPnl: BigDecimal = BigDecimal.ZERO,

    @Column(name = "total_unrealized_pnl", nullable = false, precision = 20, scale = 8)
    val totalUnrealizedPnl: BigDecimal = BigDecimal.ZERO,

    @Column(name = "copyable_pnl", nullable = false, precision = 20, scale = 8)
    val copyablePnl: BigDecimal = BigDecimal.ZERO,

    @Column(name = "max_drawdown", nullable = false, precision = 20, scale = 8)
    val maxDrawdown: BigDecimal = BigDecimal.ZERO,

    @Column(name = "unknown_valuation_exposure", nullable = false, precision = 20, scale = 8)
    val unknownValuationExposure: BigDecimal = BigDecimal.ZERO,

    @Column(name = "confirmed_zero_exposure", nullable = false, precision = 20, scale = 8)
    val confirmedZeroExposure: BigDecimal = BigDecimal.ZERO,

    @Column(name = "filtered_ratio", nullable = false, precision = 20, scale = 8)
    val filteredRatio: BigDecimal = BigDecimal.ZERO,

    @Column(name = "last_processed_event_time")
    val lastProcessedEventTime: Long? = null,

    @Column(name = "score_snapshot", precision = 20, scale = 8)
    val scoreSnapshot: BigDecimal? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),

    @Column(name = "updated_at", nullable = false)
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity
@Table(name = "leader_paper_trade")
data class LeaderPaperTrade(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "session_id", nullable = false)
    val sessionId: Long,

    @Column(name = "candidate_id", nullable = false)
    val candidateId: Long,

    @Column(name = "activity_event_id")
    val activityEventId: Long? = null,

    @Column(name = "leader_trade_id", nullable = false)
    val leaderTradeId: String,

    @Column(name = "market_id", nullable = false)
    val marketId: String,

    @Column(name = "market_title")
    val marketTitle: String? = null,

    @Column(name = "market_slug")
    val marketSlug: String? = null,

    @Column(name = "side", nullable = false, length = 20)
    val side: String,

    @Column(name = "outcome")
    val outcome: String? = null,

    @Column(name = "outcome_index")
    val outcomeIndex: Int? = null,

    @Column(name = "leader_price", precision = 20, scale = 8)
    val leaderPrice: BigDecimal? = null,

    @Column(name = "leader_size", precision = 20, scale = 8)
    val leaderSize: BigDecimal? = null,

    @Column(name = "simulated_price", precision = 20, scale = 8)
    val simulatedPrice: BigDecimal? = null,

    @Column(name = "simulated_size", precision = 20, scale = 8)
    val simulatedSize: BigDecimal? = null,

    @Column(name = "simulated_amount", precision = 20, scale = 8)
    val simulatedAmount: BigDecimal? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "fill_assumption", nullable = false, length = 30, columnDefinition = "VARCHAR(30)")
    val fillAssumption: LeaderPaperFillAssumption = LeaderPaperFillAssumption.LEADER_PRICE,

    @Enumerated(EnumType.STRING)
    @Column(name = "quote_confidence", nullable = false, length = 30, columnDefinition = "VARCHAR(30)")
    val quoteConfidence: LeaderResearchQuoteConfidence = LeaderResearchQuoteConfidence.UNKNOWN,

    @Column(name = "quote_source", length = 50)
    val quoteSource: String? = null,

    @Column(name = "quote_timestamp")
    val quoteTimestamp: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "filter_result", nullable = false, length = 30, columnDefinition = "VARCHAR(30)")
    val filterResult: LeaderPaperFilterResult = LeaderPaperFilterResult.PASSED,

    @Column(name = "filter_reason", columnDefinition = "TEXT")
    val filterReason: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "valuation_status", nullable = false, length = 30, columnDefinition = "VARCHAR(30)")
    val valuationStatus: LeaderResearchValuationStatus = LeaderResearchValuationStatus.UNKNOWN,

    @Column(name = "realized_pnl", precision = 20, scale = 8)
    val realizedPnl: BigDecimal? = null,

    @Column(name = "event_time", nullable = false)
    val eventTime: Long,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis()
)

@Entity
@Table(name = "leader_paper_position")
data class LeaderPaperPosition(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "session_id", nullable = false)
    val sessionId: Long,

    @Column(name = "candidate_id", nullable = false)
    val candidateId: Long,

    @Column(name = "market_id", nullable = false)
    val marketId: String,

    @Column(name = "outcome")
    val outcome: String? = null,

    @Column(name = "outcome_index")
    val outcomeIndex: Int? = null,

    @Column(name = "quantity", nullable = false, precision = 20, scale = 8)
    val quantity: BigDecimal = BigDecimal.ZERO,

    @Column(name = "cost", nullable = false, precision = 20, scale = 8)
    val cost: BigDecimal = BigDecimal.ZERO,

    @Column(name = "avg_price", nullable = false, precision = 20, scale = 8)
    val avgPrice: BigDecimal = BigDecimal.ZERO,

    @Column(name = "current_price", precision = 20, scale = 8)
    val currentPrice: BigDecimal? = null,

    @Column(name = "current_value", nullable = false, precision = 20, scale = 8)
    val currentValue: BigDecimal = BigDecimal.ZERO,

    @Column(name = "realized_pnl", nullable = false, precision = 20, scale = 8)
    val realizedPnl: BigDecimal = BigDecimal.ZERO,

    @Column(name = "unrealized_pnl", nullable = false, precision = 20, scale = 8)
    val unrealizedPnl: BigDecimal = BigDecimal.ZERO,

    @Enumerated(EnumType.STRING)
    @Column(name = "valuation_status", nullable = false, length = 30, columnDefinition = "VARCHAR(30)")
    val valuationStatus: LeaderResearchValuationStatus = LeaderResearchValuationStatus.UNKNOWN,

    @Enumerated(EnumType.STRING)
    @Column(name = "quote_confidence", nullable = false, length = 30, columnDefinition = "VARCHAR(30)")
    val quoteConfidence: LeaderResearchQuoteConfidence = LeaderResearchQuoteConfidence.UNKNOWN,

    @Column(name = "quote_source", length = 50)
    val quoteSource: String? = null,

    @Column(name = "quote_timestamp")
    val quoteTimestamp: Long? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),

    @Column(name = "updated_at", nullable = false)
    val updatedAt: Long = System.currentTimeMillis()
)
