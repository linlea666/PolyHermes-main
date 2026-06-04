package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.CryptoTailPolymarketPriceHistory
import org.springframework.data.jpa.repository.JpaRepository

interface CryptoTailPolymarketPriceHistoryRepository : JpaRepository<CryptoTailPolymarketPriceHistory, Long> {

    /** 按 token 取已缓存的采样点（升序），用于命中缓存避免重复请求 */
    fun findByTokenIdOrderByTUnixAsc(tokenId: String): List<CryptoTailPolymarketPriceHistory>

    fun existsByTokenId(tokenId: String): Boolean
}
