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
        val dataSource: String = DATA_SOURCE,
        // 诊断分类计数：定位"为什么没数据"
        val slugNotFound: Int = 0,
        val historyEmpty: Int = 0,
        val tooFewPoints: Int = 0,
        val fetchError: Int = 0,
        // 覆盖范围：受 maxPeriods 上限截断时为 true（实际仅覆盖最近 periodsRequested 个周期）
        val coverageCapped: Boolean = false,
        // 实际覆盖天数（periodsRequested * interval / 86400，向下保留两位由前端展示）
        val coverageDays: Double = 0.0
    )

    private class Bucket {
        var sample: Int = 0
        var reversed: Int = 0
        var maeSum: Double = 0.0
        var mfeSum: Double = 0.0
        var tpHit: Int = 0
        var stopHit: Int = 0
        var win: Int = 0
        var pnlSum: Double = 0.0
    }
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
        // 回溯窗口内的理论周期总数（不计 cap）；用于判断 maxPeriods 是否截断覆盖范围
        val theoreticalPeriods = ((latestClosedStart - lookbackStart) / intervalSeconds + 1).coerceAtLeast(0L)
        // 候选周期起点（从最近向更早，最多 cap 个）
        val periodStarts = ArrayList<Long>()
        var ps = latestClosedStart
        while (ps >= lookbackStart && periodStarts.size < cap) {
            periodStarts.add(ps)
            ps -= intervalSeconds
        }
        val coverageCapped = theoreticalPeriods > periodStarts.size
        val coverageDays = periodStarts.size.toLong() * intervalSeconds / 86400.0

        val buckets = HashMap<BucketKey, Bucket>()
        var periodsResolved = 0
        var observations = 0
        // 诊断分类计数
        var slugNotFound = 0
        var historyEmpty = 0
        var tooFewPoints = 0
        var fetchError = 0

        for (periodStart in periodStarts) {
            val result = try {
                historicalPriceSource.fetchPeriod(fullSlugPrefix, intervalSeconds, periodStart)
            } catch (e: Exception) {
                logger.debug("Polymarket 周期采集异常 start=$periodStart: ${e.message}")
                fetchError++
                continue
            }
            when (result.outcome) {
                PolymarketHistoricalPriceSource.FetchOutcome.SLUG_NOT_FOUND -> { slugNotFound++; continue }
                PolymarketHistoricalPriceSource.FetchOutcome.HISTORY_EMPTY -> { historyEmpty++; continue }
                PolymarketHistoricalPriceSource.FetchOutcome.FETCH_ERROR -> { fetchError++; continue }
                PolymarketHistoricalPriceSource.FetchOutcome.OK -> { /* 继续处理 */ }
            }
            val path = result.path
            if (path == null) {
                fetchError++
                continue
            }
            if (path.points.size < 2) {
                tooFewPoints++
                continue
            }

            periodsResolved++
            val periodEnd = periodStart + intervalSeconds
            val finalUp = path.points.last().second
            val settledOutcome = if (finalUp >= HALF) 0 else 1

            // 预计算该周期观测点（过滤越界赔率/越界剩余时间）
            val obsList = ArrayList<ObsPoint>(path.points.size)
            for ((t, upPrice) in path.points) {
                val remaining = (periodEnd - t).toInt()
                if (remaining <= 0) continue
                if (upPrice < BigDecimal.ZERO || upPrice > BigDecimal.ONE) continue
                val leadOutcome = if (upPrice >= HALF) 0 else 1
                obsList.add(ObsPoint(remaining = remaining, leadOutcome = leadOutcome, upPrice = upPrice.toDouble()))
            }
            if (obsList.isEmpty()) continue

            // first-satisfy 去重：同周期同桶仅记最早命中的观测点
            val seenKeysThisPeriod = HashSet<BucketKey>()
            for (k in obsList.indices) {
                val obs = obsList[k]
                val favoriteOdds = if (obs.leadOutcome == 0) BigDecimal(obs.upPrice) else BigDecimal.ONE.subtract(BigDecimal(obs.upPrice))
                val key = BucketKey(
                    outcomeIndex = obs.leadOutcome,
                    oddsBucket = TailDiffBuckets.oddsBucket(favoriteOdds),
                    remainingBucket = TailDiffBuckets.remainingBucket(obs.remaining)
                )
                if (!seenKeysThisPeriod.add(key)) continue

                // 领先方向胜率：Up 领先取 up，Down 领先取 1-up
                val entryPLead = if (obs.leadOutcome == 0) obs.upPrice else 1.0 - obs.upPrice
                val forwardP = ArrayList<Double>(obsList.size - k - 1)
                for (j in k + 1 until obsList.size) {
                    val upj = obsList[j].upPrice
                    forwardP.add(if (obs.leadOutcome == 0) upj else 1.0 - upj)
                }
                val settledLeadWin = obs.leadOutcome == settledOutcome
                val m = TailReversalMetrics.bracket(entryPLead, forwardP, settledLeadWin)

                val b = buckets.getOrPut(key) { Bucket() }
                b.sample++
                if (!settledLeadWin) b.reversed++
                b.maeSum += m.mae
                b.mfeSum += m.mfe
                if (m.tpHit) b.tpHit++
                if (m.stopHit) b.stopHit++
                if (m.win) b.win++
                b.pnlSum += m.pnl
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
                // POLYMARKET 用 CLOB 价格历史的原生不规则采样点，无固定取样间隔 → 0 标记
                samplingSeconds = 0,
                distinctPeriodCount = b.sample,
                maeAvg = avg(b.maeSum, b.sample),
                mfeAvg = avg(b.mfeSum, b.sample),
                virtualTpRate = rate(b.tpHit, b.sample),
                virtualStopRate = rate(b.stopHit, b.sample),
                virtualWinRate = rate(b.win, b.sample),
                virtualPnlAvg = avg(b.pnlSum, b.sample),
                computedAt = now,
                createdAt = now,
                updatedAt = now
            )
        }
        reversalStatRepository.saveAll(rows)
        val diag = "slugNotFound=$slugNotFound historyEmpty=$historyEmpty tooFewPoints=$tooFewPoints fetchError=$fetchError"
        val coverageMsg = if (coverageCapped) " [覆盖被 maxPeriods 截断: 仅最近 ${"%.2f".format(coverageDays)} 天/${periodStarts.size} 周期, 回溯窗口理论 $theoreticalPeriods 周期]" else ""
        if (periodsResolved == 0) {
            // 0 命中是最常见的"看似失败"：升级为 warn 并打印分类原因，便于定位
            logger.warn("Polymarket 反转回填 0 命中: coin=$coinUpper interval=$intervalSeconds lookback=$lookbackDays requested=${periodStarts.size} $diag$coverageMsg")
        } else {
            logger.info("Polymarket 反转回填完成: coin=$coinUpper interval=$intervalSeconds lookback=$lookbackDays requested=${periodStarts.size} resolved=$periodsResolved obs=$observations buckets=${rows.size} $diag$coverageMsg")
        }
        return BackfillSummary(
            coin = coinUpper,
            intervalSeconds = intervalSeconds,
            lookbackDays = lookbackDays,
            periodsRequested = periodStarts.size,
            periodsResolved = periodsResolved,
            observations = observations,
            bucketsWritten = rows.size,
            slugNotFound = slugNotFound,
            historyEmpty = historyEmpty,
            tooFewPoints = tooFewPoints,
            fetchError = fetchError,
            coverageCapped = coverageCapped,
            coverageDays = coverageDays
        )
    }

    private fun avg(sum: Double, count: Int): BigDecimal? =
        if (count > 0) BigDecimal(sum / count).setScale(8, RoundingMode.HALF_UP) else null

    private fun rate(hit: Int, count: Int): BigDecimal? =
        if (count > 0) BigDecimal(hit.toDouble() / count).setScale(8, RoundingMode.HALF_UP) else null

    private data class ObsPoint(val remaining: Int, val leadOutcome: Int, val upPrice: Double)

    private val HALF = BigDecimal("0.5")
}
