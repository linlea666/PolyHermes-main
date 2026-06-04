package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.CryptoTailPolymarketPriceHistory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface CryptoTailPolymarketPriceHistoryRepository : JpaRepository<CryptoTailPolymarketPriceHistory, Long> {

    /**
     * 按 token 取已缓存的采样点（升序），用于命中缓存避免重复请求。
     * 使用显式 JPQL 而非派生查询：字段 tUnix 经 JavaBeans 内省会被解析为 TUnix，
     * 与 Hibernate 字段访问的元模型属性 tUnix 大小写不一致，派生查询会解析失败。
     */
    @Query("SELECT h FROM CryptoTailPolymarketPriceHistory h WHERE h.tokenId = :tokenId ORDER BY h.tUnix ASC")
    fun findByTokenIdOrderByTUnixAsc(@Param("tokenId") tokenId: String): List<CryptoTailPolymarketPriceHistory>
}
