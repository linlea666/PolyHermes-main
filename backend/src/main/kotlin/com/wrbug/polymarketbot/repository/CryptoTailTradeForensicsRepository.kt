package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.CryptoTailTradeForensics
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * 成交复盘因子仓储。
 * - 幂等 upsert 用 [findByStrategyIdAndPeriodStartUnix] 定位既有行；
 * - 明细分页用 [search]（多条件可空过滤）；
 * - 多维分组聚合在 [com.wrbug.polymarketbot.service.cryptotail.CryptoTailTradeForensicsService]
 *   用 EntityManager + 维度白名单动态构造原生 SQL（维度自由组合，防注入）。
 */
interface CryptoTailTradeForensicsRepository : JpaRepository<CryptoTailTradeForensics, Long> {

    fun findByStrategyIdAndPeriodStartUnix(strategyId: Long, periodStartUnix: Long): CryptoTailTradeForensics?

    /**
     * 明细分页（多条件可空过滤）。
     * 用显式 JPQL：字段 entryPwin 等含大小写混合不影响（这里不按这些字段派生查询）。
     */
    @Query(
        "SELECT f FROM CryptoTailTradeForensics f WHERE " +
            "(:strategyId IS NULL OR f.strategyId = :strategyId) AND " +
            "(:marketSlug IS NULL OR f.marketSlug = :marketSlug) AND " +
            "(:intervalSeconds IS NULL OR f.intervalSeconds = :intervalSeconds) AND " +
            "(:outcomeCategory IS NULL OR f.outcomeCategory = :outcomeCategory) AND " +
            "(:onlySettled = false OR f.settled = true) AND " +
            "(:startTs IS NULL OR f.entryTs >= :startTs) AND " +
            "(:endTs IS NULL OR f.entryTs <= :endTs) " +
            "ORDER BY f.periodStartUnix DESC"
    )
    fun search(
        @Param("strategyId") strategyId: Long?,
        @Param("marketSlug") marketSlug: String?,
        @Param("intervalSeconds") intervalSeconds: Int?,
        @Param("outcomeCategory") outcomeCategory: String?,
        @Param("onlySettled") onlySettled: Boolean,
        @Param("startTs") startTs: Long?,
        @Param("endTs") endTs: Long?,
        pageable: Pageable
    ): Page<CryptoTailTradeForensics>
}
