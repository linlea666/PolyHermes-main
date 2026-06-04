package com.wrbug.polymarketbot.service.cryptotail.reversal

import com.wrbug.polymarketbot.entity.CryptoTailReversalStat
import com.wrbug.polymarketbot.repository.CryptoTailReversalStatRepository
import com.wrbug.polymarketbot.service.cryptotail.taildiff.TailDiffBuckets
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Polymarket 历史反转统计回填（数据源 = POLYMARKET，PoC）。
 *
 * 与 Binance 版本互补：Binance 版按 (diff_sigma, remaining) 分桶（无真实赔率），
 * 本版按 (真实赔率桶, remaining) 分桶——回答"赔率到了 0.9x 后还有多大概率被反转"。
 * 因此 POLYMARKET 行 diff_sigma_bucket 固定为 'ANY'，odds_bucket 为真实赔率桶。
 *
 * 反转定义：以周期内每个观测点的领先方向（Up 赔率≥0.5 则领先 Up）对照该周期最终结算方向，
 * 不一致即记一次反转；model_prob = 1 - 反转率。
 *
 * PoC 性质：为控制请求量，仅处理最近 maxPeriods 个已结算周期；任意周期失败直接跳过，不阻塞。
 */
@Service
class CryptoTailPolymarketReversalHarvestService(
    private val historicalPriceSource: PolymarketHistoricalPriceSource,
    private val reversalStatRepository: CryptoTailReversalStatRepository
) {
    private val logger = LoggerFactory.getLogger(CryptoTailPolymarketReversalHarvestService::class.java)

    companion object {
        const val DATA_SOURCE = "POLYMARKET"
        private const val DIFF_SIGMA_BUCKET_ANY = "ANY"
        private const val DEFAULT_MAX_PERIODS = 300
        private val coinToSlug = mapOf("BTC" to "btc", "ETH" to "eth")
        private val intervalToLabel = mapOf(300 to "5m", 900 to "15m")
    }

    data class BackfillSummary(
        val coin: String,
        val intervalSeconds: Int,
        val lookbackDays: Int,
        val periodsRequested: Int,
        val periodsResolved: Int,
        val observations: Int,
        val bucketsWritten: Int,
        val dataSource: String = DATA_SOURCE
    )

    private data class Bucket(var sample: Int = 0, var reversed: Int = 0)
    private data class BucketKey(val outcomeIndex: Int, val oddsBucket: String, val remainingBucket: String)

    /**
     * 回填一个 (coin, interval, lookbackDays) 维度（POLYMARKET）。幂等：先删旧分桶再写新分桶。
     * @return null 表示币种/周期不支持；其余情况即使 0 命中也返回 summary（PoC 不阻塞）。
     */
    @Transactional
    fun backfill(coin: String, intervalSeconds: Int, lookbackDays: Int, maxPeriods: Int = DEFAULT_MAX_PERIODS): BackfillSummary? {
        val coinUpper = coin.trim().uppercase()
        val coinSlug = coinToSlug[coinUpper] ?: run {
            logger.warn("Polymarket 反转回填：不支持的币种 $coin")
            return null
        }
        val label = intervalToLabel[intervalSeconds] ?: return null
        if (lookbackDays <= 0) return null
        val cap = maxPeriods.coerceIn(1, 2000)

        val fullSlugPrefix = "$coinSlug-updown-$label"
        val nowUnix = System.currentTimeMillis() / 1000L
        // 最近一个已完全结算周期的起点
        val latestClosedStart = ((nowUnix - intervalSeconds) / intervalSeconds) * intervalSeconds
        val lookbackStart = nowUnix - lookbackDays.toLong() * 24L * 3600L
        // 候选周期起点（从最近向更早，最多 cap 个）
        val periodStarts = ArrayList<Long>()
        var ps = latestClosedStart
        while (ps >= lookbackStart && periodStarts.size < cap) {
            periodStarts.add(ps)
            ps -= intervalSeconds
        }

        val buckets = HashMap<BucketKey, Bucket>()
        var periodsResolved = 0
        var observations = 0

        for (periodStart in periodStarts) {
            val path = try {
                historicalPriceSource.fetchPeriodPath(fullSlugPrefix, intervalSeconds, periodStart)
            } catch (e: Exception) {
                logger.debug("Polymarket 周期采集异常 start=$periodStart: ${e.message}")
                null
            } ?: continue
            if (path.points.size < 2) continue

            periodsResolved++
            val periodEnd = periodStart + intervalSeconds
            val finalUp = path.points.last().second
            val settledOutcome = if (finalUp >= HALF) 0 else 1

            for ((t, upPrice) in path.points) {
                val remaining = (periodEnd - t).toInt()
                if (remaining <= 0) continue
                if (upPrice < BigDecimal.ZERO || upPrice > BigDecimal.ONE) continue
                val leadOutcome = if (upPrice >= HALF) 0 else 1
                val favoriteOdds = if (upPrice >= HALF) upPrice else BigDecimal.ONE.subtract(upPrice)
                val key = BucketKey(
                    outcomeIndex = leadOutcome,
                    oddsBucket = TailDiffBuckets.oddsBucket(favoriteOdds),
                    remainingBucket = TailDiffBuckets.remainingBucket(remaining)
                )
                val b = buckets.getOrPut(key) { Bucket() }
                b.sample++
                if (leadOutcome != settledOutcome) b.reversed++
                observations++
            }
        }

        reversalStatRepository.deleteByCoinAndIntervalSecondsAndLookbackDaysAndDataSource(
            coinUpper, intervalSeconds, lookbackDays, DATA_SOURCE
        )
        val now = System.currentTimeMillis()
        val rows = buckets.map { (k, b) ->
            val modelProb = if (b.sample > 0)
                BigDecimal(1.0 - b.reversed.toDouble() / b.sample).setScale(8, RoundingMode.HALF_UP)
            else BigDecimal.ZERO
            CryptoTailReversalStat(
                coin = coinUpper,
                intervalSeconds = intervalSeconds,
                outcomeIndex = k.outcomeIndex,
                diffSigmaBucket = DIFF_SIGMA_BUCKET_ANY,
                oddsBucket = k.oddsBucket,
                remainingBucket = k.remainingBucket,
                lookbackDays = lookbackDays,
                dataSource = DATA_SOURCE,
                sampleCount = b.sample,
                reversedCount = b.reversed,
                modelProb = modelProb,
                computedAt = now,
                createdAt = now,
                updatedAt = now
            )
        }
        reversalStatRepository.saveAll(rows)
        logger.info("Polymarket 反转回填完成: coin=$coinUpper interval=$intervalSeconds lookback=$lookbackDays requested=${periodStarts.size} resolved=$periodsResolved obs=$observations buckets=${rows.size}")
        return BackfillSummary(
            coin = coinUpper,
            intervalSeconds = intervalSeconds,
            lookbackDays = lookbackDays,
            periodsRequested = periodStarts.size,
            periodsResolved = periodsResolved,
            observations = observations,
            bucketsWritten = rows.size
        )
    }

    private val HALF = BigDecimal("0.5")
}
