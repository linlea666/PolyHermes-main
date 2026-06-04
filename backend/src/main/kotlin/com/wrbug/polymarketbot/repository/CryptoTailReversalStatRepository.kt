package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.CryptoTailReversalStat
import org.springframework.data.jpa.repository.JpaRepository

interface CryptoTailReversalStatRepository : JpaRepository<CryptoTailReversalStat, Long> {

    /** 精确命中一个分桶（用于 upsert 时判断是否已存在 + 查询） */
    fun findFirstByCoinAndIntervalSecondsAndOutcomeIndexAndDiffSigmaBucketAndOddsBucketAndRemainingBucketAndLookbackDaysAndDataSource(
        coin: String,
        intervalSeconds: Int,
        outcomeIndex: Int,
        diffSigmaBucket: String,
        oddsBucket: String,
        remainingBucket: String,
        lookbackDays: Int,
        dataSource: String
    ): CryptoTailReversalStat?

    /** 研究页列表 / 导出：按 coin + interval + 回溯天数 + 数据源筛选 */
    fun findByCoinAndIntervalSecondsAndLookbackDaysAndDataSourceOrderByOutcomeIndexAscDiffSigmaBucketAscRemainingBucketAsc(
        coin: String,
        intervalSeconds: Int,
        lookbackDays: Int,
        dataSource: String
    ): List<CryptoTailReversalStat>

    /** 回填前清空对应维度的旧数据，保证幂等重算 */
    fun deleteByCoinAndIntervalSecondsAndLookbackDaysAndDataSource(
        coin: String,
        intervalSeconds: Int,
        lookbackDays: Int,
        dataSource: String
    )
}
