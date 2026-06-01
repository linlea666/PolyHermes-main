package com.wrbug.polymarketbot.dto

data class LeaderPoolListRequest(
    val status: String? = null
)

data class LeaderPoolAddRequest(
    val leaderId: Long,
    val source: String? = null,
    val reason: String? = null,
    val notes: String? = null
)

data class LeaderPoolUpdateStatusRequest(
    val poolId: Long,
    val status: String,
    val cooldownUntil: Long? = null,
    val locked: Boolean? = null
)

data class LeaderPoolUpdatePlanRequest(
    val poolId: Long,
    val suggestedFixedAmount: String? = null,
    val suggestedMaxDailyOrders: Int? = null,
    val suggestedMaxDailyLoss: String? = null,
    val suggestedMinPrice: String? = null,
    val suggestedMaxPrice: String? = null,
    val suggestedMaxPositionValue: String? = null,
    val reason: String? = null,
    val notes: String? = null
)

data class LeaderPoolCreateTrialConfigRequest(
    val poolId: Long,
    val accountId: Long,
    val enableImmediately: Boolean = false,
    val confirm: Boolean = false
)

data class LeaderPoolRemoveRequest(
    val poolId: Long
)

data class LeaderPoolSummaryDto(
    val totalCount: Int,
    val trialCount: Int,
    val estimatedWorstExposure: String,
    val pendingRiskCount: Int,
    val defaultExperimentBudget: String = "50"
)

data class LeaderPoolItemDto(
    val id: Long,
    val leaderId: Long,
    val leaderName: String?,
    val leaderAddress: String,
    val category: String?,
    val profileUrl: String,
    val status: String,
    val source: String,
    val sourceRank: Int?,
    val score: String?,
    val reason: String?,
    val notes: String?,
    val suggestedFixedAmount: String,
    val suggestedMaxDailyOrders: Int,
    val suggestedMaxDailyLoss: String,
    val suggestedMinPrice: String?,
    val suggestedMaxPrice: String?,
    val suggestedMaxPositionValue: String?,
    val copyTradingCount: Int,
    val hasEnabledCopyTrading: Boolean,
    val estimatedWorstExposure: String,
    val lastReviewedAt: Long?,
    val lastPromotedAt: Long?,
    val cooldownUntil: Long?,
    val locked: Boolean,
    val researchCandidateId: Long?,
    val researchState: String?,
    val researchBadge: String?,
    val researchSummary: String?,
    val researchScore: String?,
    val researchUpdatedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long
)

data class LeaderPoolListResponse(
    val summary: LeaderPoolSummaryDto,
    val list: List<LeaderPoolItemDto>,
    val total: Int
)
