package com.wrbug.polymarketbot.dto

data class LeaderResearchRunRequest(
    val dryRun: Boolean = false,
    val triggerType: String = "MANUAL"
)

data class LeaderResearchRunDto(
    val id: Long,
    val status: String,
    val triggerType: String,
    val dryRun: Boolean,
    val startedAt: Long,
    val finishedAt: Long?,
    val durationMs: Long?,
    val sourceCountsJson: String?,
    val candidateCountsJson: String?,
    val partialFailure: Boolean,
    val skippedReason: String?,
    val errorClass: String?,
    val errorMessage: String?
)

data class LeaderResearchSummaryDto(
    val discoveredCount: Long,
    val candidateCount: Long,
    val paperCount: Long,
    val trialReadyCount: Long,
    val cooldownCount: Long,
    val retiredCount: Long,
    val activePaperSessions: Long,
    val pendingRiskCount: Long,
    val lastRun: LeaderResearchRunDto?,
    val sourceLimitations: List<String>
)

data class LeaderResearchCandidateListRequest(
    val page: Int = 0,
    val size: Int = 20,
    val state: String? = null,
    val query: String? = null
)

data class LeaderResearchCandidateListResponse(
    val list: List<LeaderResearchCandidateDto>,
    val total: Long,
    val summary: LeaderResearchSummaryDto
)

data class LeaderResearchCandidateDto(
    val id: Long,
    val normalizedWallet: String,
    val leaderId: Long?,
    val leaderName: String?,
    val poolId: Long?,
    val poolStatus: String?,
    val suggestedFixedAmount: String?,
    val suggestedMaxDailyLoss: String?,
    val suggestedMaxDailyOrders: Int?,
    val suggestedMinPrice: String?,
    val suggestedMaxPrice: String?,
    val suggestedMaxPositionValue: String?,
    val researchState: String,
    val source: String,
    val sourceRank: Int?,
    val score: String?,
    val scoreVersion: String?,
    val reason: String?,
    val riskFlags: List<String>,
    val locked: Boolean,
    val agentOwned: Boolean,
    val provenance: String,
    val sourceEvidence: String?,
    val firstSeenAt: Long,
    val lastSourceSeenAt: Long?,
    val lastScoredAt: Long?,
    val cooldownUntil: Long?,
    val cooldownCount: Int,
    val trialReadyAt: Long?,
    val retiredAt: Long?,
    val lastPaperSessionId: Long?,
    val latestPaperSession: LeaderPaperSessionDto?
)

data class LeaderResearchCandidateDetailDto(
    val candidate: LeaderResearchCandidateDto,
    val latestScore: LeaderResearchScoreDto?,
    val paperSessions: List<LeaderPaperSessionDto>,
    val paperTrades: List<LeaderPaperTradeDto>,
    val paperPositions: List<LeaderPaperPositionDto>,
    val events: List<LeaderResearchEventDto>
)

data class LeaderResearchScoreDto(
    val id: Long,
    val candidateId: Long,
    val runId: Long?,
    val scoreVersion: String,
    val totalScore: String,
    val profitSignal: String,
    val repeatability: String,
    val liquidityFit: String,
    val entryPriceFit: String,
    val slippageRisk: String,
    val holdingPeriodFit: String,
    val marketTypeRisk: String,
    val drawdownRisk: String,
    val exitLiquidityRisk: String,
    val dataFreshness: String,
    val filterPassRate: String,
    val sampleTradeCount: Int,
    val reason: String?,
    val createdAt: Long
)

data class LeaderPaperSessionDto(
    val id: Long,
    val candidateId: Long,
    val status: String,
    val startedAt: Long,
    val endedAt: Long?,
    val tradeCount: Int,
    val filteredCount: Int,
    val openExposure: String,
    val totalRealizedPnl: String,
    val totalUnrealizedPnl: String,
    val copyablePnl: String,
    val maxDrawdown: String,
    val unknownValuationExposure: String,
    val confirmedZeroExposure: String,
    val filteredRatio: String,
    val lastProcessedEventTime: Long?,
    val scoreSnapshot: String?
)

data class LeaderPaperTradeDto(
    val id: Long,
    val sessionId: Long,
    val candidateId: Long,
    val activityEventId: Long?,
    val leaderTradeId: String,
    val marketId: String,
    val marketTitle: String?,
    val marketSlug: String?,
    val side: String,
    val outcome: String?,
    val outcomeIndex: Int?,
    val leaderPrice: String?,
    val leaderSize: String?,
    val simulatedPrice: String?,
    val simulatedSize: String?,
    val simulatedAmount: String?,
    val fillAssumption: String,
    val quoteConfidence: String,
    val quoteSource: String?,
    val quoteTimestamp: Long?,
    val filterResult: String,
    val filterReason: String?,
    val valuationStatus: String,
    val realizedPnl: String?,
    val eventTime: Long,
    val createdAt: Long
)

data class LeaderPaperPositionDto(
    val id: Long,
    val sessionId: Long,
    val candidateId: Long,
    val marketId: String,
    val outcome: String?,
    val outcomeIndex: Int?,
    val quantity: String,
    val cost: String,
    val avgPrice: String,
    val currentPrice: String?,
    val currentValue: String,
    val realizedPnl: String,
    val unrealizedPnl: String,
    val valuationStatus: String,
    val quoteConfidence: String,
    val quoteSource: String?,
    val quoteTimestamp: Long?,
    val updatedAt: Long
)

data class LeaderResearchSourceStateDto(
    val sourceType: String,
    val status: String,
    val lastSuccessAt: Long?,
    val lastFailureAt: Long?,
    val lastRunAt: Long?,
    val lastCandidateCount: Int,
    val errorClass: String?,
    val errorMessage: String?,
    val stale: Boolean,
    val disabledReason: String?,
    val lastCursor: String?,
    val updatedAt: Long
)

data class LeaderResearchEventDto(
    val id: Long,
    val candidateId: Long?,
    val runId: Long?,
    val eventType: String,
    val reason: String?,
    val payloadSummary: String?,
    val notificationStatus: String,
    val notificationError: String?,
    val dedupeKey: String?,
    val createdAt: Long,
    val notifiedAt: Long?
)

data class LeaderResearchApprovalRequest(
    val candidateId: Long,
    val accountId: Long,
    val confirm: Boolean = false
)

data class LeaderResearchApprovalResponse(
    val copyTrading: CopyTradingDto,
    val warning: String = "已创建禁用状态的试跟配置；需要你手动启用后才会真钱跟单。"
)
