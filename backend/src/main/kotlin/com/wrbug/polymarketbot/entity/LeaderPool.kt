package com.wrbug.polymarketbot.entity

import com.wrbug.polymarketbot.enums.LeaderPoolStatus
import com.wrbug.polymarketbot.enums.LeaderResearchState
import jakarta.persistence.*
import java.math.BigDecimal

@Entity
@Table(name = "copy_trading_leader_pool")
data class LeaderPool(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "leader_id", nullable = false)
    val leaderId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20, columnDefinition = "VARCHAR(20)")
    val status: LeaderPoolStatus = LeaderPoolStatus.CANDIDATE,

    @Column(name = "source", nullable = false, length = 50)
    val source: String = "MANUAL",

    @Column(name = "source_rank")
    val sourceRank: Int? = null,

    @Column(name = "score", precision = 20, scale = 8)
    val score: BigDecimal? = null,

    @Column(name = "reason", columnDefinition = "TEXT")
    val reason: String? = null,

    @Column(name = "notes", columnDefinition = "TEXT")
    val notes: String? = null,

    @Column(name = "suggested_fixed_amount", nullable = false, precision = 20, scale = 8)
    val suggestedFixedAmount: BigDecimal = BigDecimal("1.00000000"),

    @Column(name = "suggested_max_daily_orders", nullable = false)
    val suggestedMaxDailyOrders: Int = 10,

    @Column(name = "suggested_max_daily_loss", nullable = false, precision = 20, scale = 8)
    val suggestedMaxDailyLoss: BigDecimal = BigDecimal("5.00000000"),

    @Column(name = "suggested_min_price", precision = 20, scale = 8)
    val suggestedMinPrice: BigDecimal? = BigDecimal("0.10000000"),

    @Column(name = "suggested_max_price", precision = 20, scale = 8)
    val suggestedMaxPrice: BigDecimal? = BigDecimal("0.80000000"),

    @Column(name = "suggested_max_position_value", precision = 20, scale = 8)
    val suggestedMaxPositionValue: BigDecimal? = BigDecimal("5.00000000"),

    @Column(name = "last_reviewed_at")
    val lastReviewedAt: Long? = null,

    @Column(name = "last_promoted_at")
    val lastPromotedAt: Long? = null,

    @Column(name = "cooldown_until")
    val cooldownUntil: Long? = null,

    @Column(name = "locked", nullable = false)
    val locked: Boolean = false,

    @Column(name = "research_candidate_id")
    val researchCandidateId: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "research_state", length = 30, columnDefinition = "VARCHAR(30)")
    val researchState: LeaderResearchState? = null,

    @Column(name = "research_badge", length = 50)
    val researchBadge: String? = null,

    @Column(name = "research_summary", columnDefinition = "TEXT")
    val researchSummary: String? = null,

    @Column(name = "research_score", precision = 20, scale = 8)
    val researchScore: BigDecimal? = null,

    @Column(name = "research_updated_at")
    val researchUpdatedAt: Long? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)
