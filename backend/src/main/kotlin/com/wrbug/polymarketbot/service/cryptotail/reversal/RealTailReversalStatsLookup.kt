package com.wrbug.polymarketbot.service.cryptotail.reversal

import com.wrbug.polymarketbot.repository.CryptoTailReversalStatRepository
import com.wrbug.polymarketbot.service.cryptotail.taildiff.TailReversalStatsLookup
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 历史反转统计的真实实现（数据源由 [TailReversalStatsLookup.Query.dataSource] 驱动：BINANCE / POLYMARKET）。
 *
 * 作为 [@Component] 注册后，凭借 [com.wrbug.polymarketbot.service.cryptotail.taildiff.TailReversalStatsLookupConfig]
 * 的 `@ConditionalOnMissingBean` 自动取代 Noop 实现，ScoreEngine 无需改动即可拿到历史 modelProb（完成 P3 接线）。
 *
 * 查询策略：
 *  - 桶以"领先方向"（leadOutcome=modelSide）为键，故 [TailReversalStatsLookup.Query.leadOutcome] 必须传 modelSide；
 *  - 命中 (coin, interval, leadOutcome, diffSigmaBucket, oddsBucket, remainingBucket, lookbackDays, dataSource)；
 *  - 同一桶可能并存多种采样精度（1m@180d / 1s@14d / POLYMARKET sampling=0），按 sample_count 降序取最优：
 *    深桶天然样本多→选 1m，1m 缺失的尾盘细桶→自动回退 1s，无需按 dataSource/采样精度分支；
 *  - oddsBucket / diffSigmaBucket 先按精确值再回退 ANY（BINANCE 按 sigma 细分 odds=ANY，POLYMARKET 反之）；
 *  - 样本数 < 调用方要求时，仍返回结果但由 ScoreEngine 决定是否回退（本类只负责给得出/给不出）。
 */
@Component
class RealTailReversalStatsLookup(
    private val reversalStatRepository: CryptoTailReversalStatRepository
) : TailReversalStatsLookup {

    private val logger = LoggerFactory.getLogger(RealTailReversalStatsLookup::class.java)

    private companion object {
        const val BUCKET_ANY = "ANY"
    }

    override fun queryReversalProb(query: TailReversalStatsLookup.Query): TailReversalStatsLookup.Result {
        val coin = query.coin?.trim()?.uppercase()
        if (coin.isNullOrBlank()) {
            return TailReversalStatsLookup.Result(null, 0, "FALLBACK", "COIN_UNKNOWN")
        }
        // 计算 diffSigma 落入的分桶（与回填时一致）
        val diffSigmaBucket = com.wrbug.polymarketbot.service.cryptotail.taildiff.TailDiffBuckets.diffSigmaBucket(query.diffSigma)

        // 两种数据源的桶维度不同：BINANCE 按 diffSigma 细分而 oddsBucket=ANY；POLYMARKET 按 odds 细分而 diffSigmaBucket=ANY。
        // 故按"最细 → 最粗"顺序在 (diffSigma, odds) 两轴上各自回退 ANY，覆盖两种回填口径，无需按 dataSource 分支。
        val row = findBucket(coin, query, diffSigmaBucket, query.oddsBucket)
            ?: findBucket(coin, query, diffSigmaBucket, BUCKET_ANY)
            ?: findBucket(coin, query, BUCKET_ANY, query.oddsBucket)
            ?: findBucket(coin, query, BUCKET_ANY, BUCKET_ANY)

        if (row == null) {
            return TailReversalStatsLookup.Result(null, 0, "FALLBACK", "REVERSAL_STATS_BUCKET_EMPTY")
        }
        return TailReversalStatsLookup.Result(
            modelProb = row.modelProb,
            sampleCount = row.sampleCount,
            source = "STATS",
            reason = null
        )
    }

    private fun findBucket(
        coin: String,
        query: TailReversalStatsLookup.Query,
        diffSigmaBucket: String,
        oddsBucket: String
    ) = reversalStatRepository
        .findFirstByCoinAndIntervalSecondsAndOutcomeIndexAndDiffSigmaBucketAndOddsBucketAndRemainingBucketAndLookbackDaysAndDataSourceOrderBySampleCountDesc(
            coin,
            query.intervalSeconds,
            query.leadOutcome,
            diffSigmaBucket,
            oddsBucket,
            query.remainingBucket,
            query.lookbackDays,
            query.dataSource
        )
}
