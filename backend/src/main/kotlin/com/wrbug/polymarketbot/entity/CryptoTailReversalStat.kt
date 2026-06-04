package com.wrbug.polymarketbot.entity

import jakarta.persistence.*
import java.math.BigDecimal

/**
 * 历史反转统计聚合实体（Tail Diff 模型概率来源之一）。
 * 每行 = 一个分桶的反转统计，model_prob = 领先方向维持到结算的历史概率。
 */
@Entity
@Table(name = "crypto_tail_reversal_stat")
data class CryptoTailReversalStat(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "coin", nullable = false, length = 16)
    val coin: String = "",

    @Column(name = "interval_seconds", nullable = false)
    val intervalSeconds: Int = 300,

    @Column(name = "outcome_index", nullable = false)
    val outcomeIndex: Int = 0,

    @Column(name = "diff_sigma_bucket", nullable = false, length = 32)
    val diffSigmaBucket: String = "",

    @Column(name = "odds_bucket", nullable = false, length = 32)
    val oddsBucket: String = "ANY",

    @Column(name = "remaining_bucket", nullable = false, length = 32)
    val remainingBucket: String = "",

    @Column(name = "lookback_days", nullable = false)
    val lookbackDays: Int = 180,

    @Column(name = "data_source", nullable = false, length = 16)
    val dataSource: String = "BINANCE",

    @Column(name = "sample_count", nullable = false)
    val sampleCount: Int = 0,

    @Column(name = "reversed_count", nullable = false)
    val reversedCount: Int = 0,

    @Column(name = "model_prob", nullable = false, precision = 20, scale = 8)
    val modelProb: BigDecimal = BigDecimal.ZERO,

    @Column(name = "computed_at", nullable = false)
    val computedAt: Long = 0L,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),

    @Column(name = "updated_at", nullable = false)
    val updatedAt: Long = System.currentTimeMillis()
)
