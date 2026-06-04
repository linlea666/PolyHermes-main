package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.CryptoTailPmPeriodStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface CryptoTailPmPeriodStatusRepository : JpaRepository<CryptoTailPmPeriodStatus, Long> {

    /** 单周期状态查询（slug 全局唯一）。 */
    fun findBySlug(slug: String): CryptoTailPmPeriodStatus?

    /**
     * 按 slug 前缀 + 周期起点范围批量预载状态（一次查询），用于增量回填的预算判定，
     * 避免对大窗口逐周期发起 DB 查询或巨大的 IN 子句。
     */
    @Query(
        "SELECT s FROM CryptoTailPmPeriodStatus s " +
            "WHERE s.slugPrefix = :slugPrefix " +
            "AND s.periodStartUnix BETWEEN :fromUnix AND :toUnix"
    )
    fun findWindow(
        @Param("slugPrefix") slugPrefix: String,
        @Param("fromUnix") fromUnix: Long,
        @Param("toUnix") toUnix: Long
    ): List<CryptoTailPmPeriodStatus>
}
