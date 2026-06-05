package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.CryptoTailPeriodSummary
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface CryptoTailPeriodSummaryRepository : JpaRepository<CryptoTailPeriodSummary, Long> {

    fun findByStrategyIdAndPeriodStartUnix(strategyId: Long, periodStartUnix: Long): CryptoTailPeriodSummary?

    fun findAllByStrategyIdOrderByPeriodStartUnixDesc(strategyId: Long, pageable: Pageable): Page<CryptoTailPeriodSummary>

    fun findAllByOrderByPeriodStartUnixDesc(pageable: Pageable): Page<CryptoTailPeriodSummary>

    fun findAllByStrategyIdAndPeriodStartUnixBetweenOrderByPeriodStartUnixDesc(
        strategyId: Long,
        startInclusive: Long,
        endInclusive: Long,
        pageable: Pageable
    ): Page<CryptoTailPeriodSummary>

    fun findAllByPeriodStartUnixBetweenOrderByPeriodStartUnixDesc(
        startInclusive: Long,
        endInclusive: Long,
        pageable: Pageable
    ): Page<CryptoTailPeriodSummary>

    /** 取仍处 OPEN 且周期已结束的记录，供结算回填（限量，避免一次拉太多） */
    fun findTop200ByStatusAndPeriodEndUnixLessThanEqualOrderByPeriodStartUnixAsc(
        status: String,
        nowUnix: Long
    ): List<CryptoTailPeriodSummary>

    /** 方向准确率汇总：已结算且首选方向非空的总数与命中数（按策略，可选时间范围） */
    @Query(
        """
        SELECT
            COUNT(p) AS total,
            SUM(CASE WHEN p.directionCorrect = true THEN 1 ELSE 0 END) AS correct,
            SUM(CASE WHEN p.traded = true THEN 1 ELSE 0 END) AS traded
        FROM CryptoTailPeriodSummary p
        WHERE p.status = 'SETTLED'
          AND p.firstChosenOutcomeIndex IS NOT NULL
          AND (:strategyId = 0 OR p.strategyId = :strategyId)
          AND (:start IS NULL OR p.periodStartUnix >= :start)
          AND (:end IS NULL OR p.periodStartUnix <= :end)
        """
    )
    fun summarizeAccuracy(
        @Param("strategyId") strategyId: Long,
        @Param("start") startUnix: Long?,
        @Param("end") endUnix: Long?
    ): Array<Any>
}
