package com.wrbug.polymarketbot.entity

import jakarta.persistence.*
import java.math.BigDecimal

/**
 * Polymarket 历史赔率采样点缓存（PoC）。仅用于复盘/排查，不参与交易主链路。
 */
@Entity
@Table(name = "crypto_tail_polymarket_price_history")
data class CryptoTailPolymarketPriceHistory(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "token_id", nullable = false, length = 80)
    val tokenId: String = "",

    @Column(name = "period_start_unix", nullable = false)
    val periodStartUnix: Long = 0L,

    @Column(name = "t_unix", nullable = false)
    val tUnix: Long = 0L,

    @Column(name = "price", nullable = false, precision = 20, scale = 8)
    val price: BigDecimal = BigDecimal.ZERO,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis()
)
