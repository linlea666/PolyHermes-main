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

    /** 本行回填所用最细取样间隔（秒）：60=旧 1m；1=尾盘 1s 细采样 */
    @Column(name = "sampling_seconds", nullable = false)
    val samplingSeconds: Int = 60,

    /** first-satisfy 去重后贡献该桶的不同周期数（每周期对同桶最多计一次） */
    @Column(name = "distinct_period_count", nullable = false)
    val distinctPeriodCount: Int = 0,

    /** 平均最大不利偏移（领先方向胜率 p 口径，0~1）；不可用为 null */
    @Column(name = "mae_avg", precision = 20, scale = 8)
    val maeAvg: BigDecimal? = null,

    /** 平均最大有利偏移（领先方向胜率 p 口径，0~1）；不可用为 null */
    @Column(name = "mfe_avg", precision = 20, scale = 8)
    val mfeAvg: BigDecimal? = null,

    /** 虚拟括号退出：结算前先触达 TP(p>=0.99) 的比例；不可用为 null */
    @Column(name = "virtual_tp_rate", precision = 20, scale = 8)
    val virtualTpRate: BigDecimal? = null,

    /** 虚拟括号退出：结算前先触达 STOP(p<=0.70) 的比例；不可用为 null */
    @Column(name = "virtual_stop_rate", precision = 20, scale = 8)
    val virtualStopRate: BigDecimal? = null,

    /** 虚拟括号退出净盈利（pnl>0）的比例；不可用为 null */
    @Column(name = "virtual_win_rate", precision = 20, scale = 8)
    val virtualWinRate: BigDecimal? = null,

    /** 虚拟括号退出每单位平均盈亏（proceeds - cost）；不可用为 null */
    @Column(name = "virtual_pnl_avg", precision = 20, scale = 8)
    val virtualPnlAvg: BigDecimal? = null,

    @Column(name = "computed_at", nullable = false)
    val computedAt: Long = 0L,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),

    @Column(name = "updated_at", nullable = false)
    val updatedAt: Long = System.currentTimeMillis()
)
