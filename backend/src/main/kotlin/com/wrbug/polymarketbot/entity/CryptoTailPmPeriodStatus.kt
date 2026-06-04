package com.wrbug.polymarketbot.entity

import jakarta.persistence.*

/**
 * Polymarket 周期采集状态（增量回填 / 负缓存）。
 *
 * 用途：让反转回填具备真正的周期级跳过能力——
 *  - RESOLVED：已成功采集，记录 up_token_id，复用时据此从价格缓存读回（零网络）；
 *  - SLUG_NOT_FOUND/HISTORY_EMPTY：已结算但无数据，写永久负缓存（零网络跳过）；
 *  - FETCH_ERROR（网络/限流）不落库 → 下次自动重试。
 *
 * 仅服务反转研究 PoC，失败不影响交易主链路。
 */
@Entity
@Table(name = "crypto_tail_pm_period_status")
data class CryptoTailPmPeriodStatus(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "slug", nullable = false, length = 160)
    val slug: String = "",

    @Column(name = "slug_prefix", nullable = false, length = 80)
    val slugPrefix: String = "",

    @Column(name = "interval_seconds", nullable = false)
    val intervalSeconds: Int = 0,

    @Column(name = "period_start_unix", nullable = false)
    val periodStartUnix: Long = 0L,

    @Column(name = "status", nullable = false, length = 24)
    val status: String = "",

    @Column(name = "up_token_id", length = 80)
    val upTokenId: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),

    @Column(name = "updated_at", nullable = false)
    val updatedAt: Long = System.currentTimeMillis()
)
