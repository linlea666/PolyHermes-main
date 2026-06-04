package com.wrbug.polymarketbot.service.cryptotail.reversal

import com.wrbug.polymarketbot.repository.CryptoTailReversalStatRepository
import com.wrbug.polymarketbot.service.cryptotail.taildiff.TailReversalStatsLookup
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 历史反转统计的真实实现（数据源 BINANCE）。
 *
 * 作为 [@Component] 注册后，凭借 [com.wrbug.polymarketbot.service.cryptotail.taildiff.TailReversalStatsLookupConfig]
 * 的 `@ConditionalOnMissingBean` 自动取代 Noop 实现，ScoreEngine 无需改动即可拿到历史 modelProb（完成 P3 接线）。
 *
 * 查询策略：
 *  - 精确命中 (coin, interval, outcome, diffSigmaBucket, oddsBucket, remainingBucket, lookbackDays)；
 *  - BINANCE 数据源 oddsBucket 恒为 ANY，故优先用 ANY 兜底匹配；
 *  - 样本数 < 调用方要求时，仍返回结果但由 ScoreEngine 决定是否回退（本类只负责给得出/给不出）。
 */
@Component
class RealTailReversalStatsLookup(
    private val reversalStatRepository: CryptoTailReversalStatRepository
) : TailReversalStatsLookup {

    private val logger = LoggerFactory.getLogger(RealTailReversalStatsLookup::class.java)

    override fun queryReversalProb(query: TailReversalStatsLookup.Query): TailReversalStatsLookup.Result {
        val coin = query.coin?.trim()?.uppercase()
        if (coin.isNullOrBlank()) {
            return TailReversalStatsLookup.Result(null, 0, "FALLBACK", "COIN_UNKNOWN")
        }
        // 计算 diffSigma 落入的分桶（与回填时一致）
        val diffSigmaBucket = com.wrbug.polymarketbot.service.cryptotail.taildiff.TailDiffBuckets.diffSigmaBucket(query.diffSigma)

        // BINANCE 源 oddsBucket=ANY；先按精确 oddsBucket，再回退 ANY
        val row = findBucket(coin, query, diffSigmaBucket, query.oddsBucket)
            ?: findBucket(coin, query, diffSigmaBucket, "ANY")

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
        .findFirstByCoinAndIntervalSecondsAndOutcomeIndexAndDiffSigmaBucketAndOddsBucketAndRemainingBucketAndLookbackDaysAndDataSource(
            coin,
            query.intervalSeconds,
            query.outcomeIndex,
            diffSigmaBucket,
            oddsBucket,
            query.remainingBucket,
            query.lookbackDays,
            CryptoTailReversalHarvestService.DATA_SOURCE
        )
}
