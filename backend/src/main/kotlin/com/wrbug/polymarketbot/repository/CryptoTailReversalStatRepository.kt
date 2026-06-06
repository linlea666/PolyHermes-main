package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.CryptoTailReversalStat
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface CryptoTailReversalStatRepository : JpaRepository<CryptoTailReversalStat, Long> {

    /**
     * 命中一个分桶并返回样本最充足的一行。
     *
     * 同一桶在唯一键纳入 sampling_seconds 后可能并存多行（如 1m@180d 与 1s@14d）：
     * 按 sample_count 降序取首行 = 深度优先（1m 粗桶天然样本多 → 选 1m；1m 缺失的尾盘细桶 → 自动回退 1s；
     * POLYMARKET 源仅有 sampling=0 一行 → 直接返回）。无需在调用方按 dataSource/采样精度分支。
     */
    fun findFirstByCoinAndIntervalSecondsAndOutcomeIndexAndDiffSigmaBucketAndOddsBucketAndRemainingBucketAndLookbackDaysAndDataSourceOrderBySampleCountDesc(
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

    /**
     * 回填前清空"同一采样精度"的旧数据，保证幂等重算且不误删其它精度。
     *
     * - 维度含 sampling_seconds：1s 回填只删旧 1s 行，1m@180d 原封不动（反之亦然），实现多精度共存。
     * - 必须用批量 [Modifying] DELETE 立即下发 SQL：派生删除（先 SELECT 再逐个 remove）只是把删除排进
     *   持久化上下文，而 Hibernate flush 固定先执行 INSERT 再执行 DELETE，会导致随后 saveAll 的新行与尚未删除的
     *   旧行在 uk_ct_reversal_bucket 上撞键（Duplicate entry）。批量 DELETE 在调用点立即执行，旧行先删净再插入。
     */
    @Modifying
    @Query(
        "DELETE FROM CryptoTailReversalStat e WHERE e.coin = :coin AND e.intervalSeconds = :intervalSeconds " +
            "AND e.lookbackDays = :lookbackDays AND e.dataSource = :dataSource AND e.samplingSeconds = :samplingSeconds"
    )
    fun deleteByCoinAndIntervalSecondsAndLookbackDaysAndDataSourceAndSamplingSeconds(
        @Param("coin") coin: String,
        @Param("intervalSeconds") intervalSeconds: Int,
        @Param("lookbackDays") lookbackDays: Int,
        @Param("dataSource") dataSource: String,
        @Param("samplingSeconds") samplingSeconds: Int
    )
}
