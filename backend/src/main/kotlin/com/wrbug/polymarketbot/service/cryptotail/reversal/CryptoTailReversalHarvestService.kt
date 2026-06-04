package com.wrbug.polymarketbot.service.cryptotail.reversal

import com.wrbug.polymarketbot.entity.CryptoTailReversalStat
import com.wrbug.polymarketbot.repository.CryptoTailReversalStatRepository
import com.wrbug.polymarketbot.service.cryptotail.taildiff.TailDiffBuckets
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.sqrt

/**
 * 历史反转统计回填服务（数据源 = BINANCE）。
 *
 * 思路：拉取币种 1 分钟 K 线（回溯 lookbackDays），按 interval 对齐切分为周期；
 * 对每个完整周期，以 openP=周期首根 open、finalClose=周期末根 close 确定结算方向；
 * 再对周期内每个 1 分钟边界作为"观测点"，按当时 close 计算领先方向与 diff_sigma，
 * 判断领先方向是否被最终结算反转，按分桶累计，最终 model_prob = 1 - 反转率。
 *
 * σ 估计：用滑动窗口内最近的 1 分钟收盘价绝对变动标准差换算到 per-√s。
 */
@Service
class CryptoTailReversalHarvestService(
    private val retrofitFactory: RetrofitFactory,
    private val reversalStatRepository: CryptoTailReversalStatRepository
) {
    private val logger = LoggerFactory.getLogger(CryptoTailReversalHarvestService::class.java)

    companion object {
        const val DATA_SOURCE = "BINANCE"
        private const val ODDS_BUCKET_ANY = "ANY"
        private const val ONE_MINUTE_MS = 60_000L
        private const val KLINE_PAGE_LIMIT = 1000
        private const val SIGMA_WINDOW = 60
        private val coinToSymbol = mapOf("BTC" to "BTCUSDC", "ETH" to "ETHUSDC")
    }

    data class BackfillSummary(
        val coin: String,
        val intervalSeconds: Int,
        val lookbackDays: Int,
        val periodsProcessed: Int,
        val observations: Int,
        val bucketsWritten: Int
    )

    private data class Bucket(var sample: Int = 0, var reversed: Int = 0)

    /**
     * 回填一个 (coin, interval, lookbackDays) 维度。幂等：先删旧分桶再写新分桶。
     * @return null 表示币种不支持或无可用 K 线
     */
    @Transactional
    fun backfill(coin: String, intervalSeconds: Int, lookbackDays: Int): BackfillSummary? {
        val coinUpper = coin.trim().uppercase()
        val symbol = coinToSymbol[coinUpper] ?: run {
            logger.warn("反转回填：不支持的币种 $coin")
            return null
        }
        if (intervalSeconds != 300 && intervalSeconds != 900) return null
        if (lookbackDays <= 0) return null

        val nowMs = System.currentTimeMillis()
        val startMs = nowMs - lookbackDays.toLong() * 24L * 3600L * 1000L
        val oneMinCandles = fetchAllOneMinuteKlines(symbol, startMs, nowMs)
        if (oneMinCandles.isEmpty()) {
            logger.warn("反转回填：$symbol 无可用 1m K 线")
            return null
        }

        val periodMs = intervalSeconds.toLong() * 1000L
        val candlesPerPeriod = intervalSeconds / 60
        // 按对齐周期分组
        val grouped = LinkedHashMap<Long, MutableList<OneMin>>()
        for (c in oneMinCandles) {
            val periodStart = (c.openTimeMs / periodMs) * periodMs
            grouped.getOrPut(periodStart) { mutableListOf() }.add(c)
        }

        // 滑动 σ 窗口：最近 SIGMA_WINDOW 根 1m 的收盘价绝对变动
        val recentAbsReturns = ArrayDeque<Double>()
        var prevClose: Double? = null

        val buckets = HashMap<BucketKey, Bucket>()
        var periodsProcessed = 0
        var observations = 0

        for ((_, candles) in grouped) {
            // 维护 σ 滑动窗口（无论周期是否完整都喂入，提升 σ 稳定性）
            for (c in candles) {
                val close = c.close.toDouble()
                prevClose?.let { pc ->
                    recentAbsReturns.addLast(kotlin.math.abs(close - pc))
                    while (recentAbsReturns.size > SIGMA_WINDOW) recentAbsReturns.removeFirst()
                }
                prevClose = close
            }
            if (candles.size < candlesPerPeriod) continue
            candles.sortBy { it.openTimeMs }

            val openP = candles.first().open
            val finalClose = candles.last().close
            val settledOutcome = if (finalClose >= openP) 0 else 1
            val sigmaPerSqrtS = currentSigmaPerSqrtS(recentAbsReturns)
            if (sigmaPerSqrtS <= 0.0) continue

            periodsProcessed++
            // 对周期内每个 1m 边界作为观测点（i=1..n-1），remaining = interval - i*60
            for (i in 1 until candles.size) {
                val elapsedSeconds = i * 60
                val remaining = intervalSeconds - elapsedSeconds
                if (remaining <= 0) continue
                val obsClose = candles[i - 1].close
                val rawDiff = obsClose.subtract(openP)
                val leadOutcome = if (rawDiff.signum() >= 0) 0 else 1
                val expected = sigmaPerSqrtS * sqrt(remaining.toDouble())
                if (expected <= 0.0) continue
                val diffSigma = BigDecimal(rawDiff.abs().toDouble() / expected)
                val key = BucketKey(
                    outcomeIndex = leadOutcome,
                    diffSigmaBucket = TailDiffBuckets.diffSigmaBucket(diffSigma),
                    remainingBucket = TailDiffBuckets.remainingBucket(remaining)
                )
                val b = buckets.getOrPut(key) { Bucket() }
                b.sample++
                if (leadOutcome != settledOutcome) b.reversed++
                observations++
            }
        }

        // 幂等：删旧写新
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
                diffSigmaBucket = k.diffSigmaBucket,
                oddsBucket = ODDS_BUCKET_ANY,
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
        logger.info("反转回填完成: coin=$coinUpper interval=$intervalSeconds lookback=$lookbackDays periods=$periodsProcessed obs=$observations buckets=${rows.size}")
        return BackfillSummary(coinUpper, intervalSeconds, lookbackDays, periodsProcessed, observations, rows.size)
    }

    private fun currentSigmaPerSqrtS(recentAbsReturns: Collection<Double>): Double {
        if (recentAbsReturns.size < 5) return 0.0
        val mean = recentAbsReturns.average()
        val variance = recentAbsReturns.sumOf { (it - mean) * (it - mean) } / recentAbsReturns.size
        val sigmaPerMin = sqrt(variance)
        // 1 分钟变动 ~ √60s，换算到 per-√s
        return sigmaPerMin / sqrt(60.0)
    }

    private data class OneMin(val openTimeMs: Long, val open: BigDecimal, val close: BigDecimal)
    private data class BucketKey(val outcomeIndex: Int, val diffSigmaBucket: String, val remainingBucket: String)

    /** 分页拉取 [startMs, endMs] 区间内所有 1m K 线（按 openTime 升序、去重） */
    private fun fetchAllOneMinuteKlines(symbol: String, startMs: Long, endMs: Long): List<OneMin> {
        val api = retrofitFactory.createBinanceApi()
        val result = ArrayList<OneMin>()
        var cursor = startMs
        var safety = 0
        val maxRequests = 2000
        while (cursor < endMs && safety < maxRequests) {
            safety++
            val batch = try {
                val resp = api.getKlines(symbol = symbol, interval = "1m", limit = KLINE_PAGE_LIMIT, startTime = cursor, endTime = endMs).execute()
                if (resp.isSuccessful) resp.body() ?: emptyList() else emptyList()
            } catch (e: Exception) {
                logger.warn("拉取 $symbol 1m K 线失败 cursor=$cursor: ${e.message}")
                emptyList()
            }
            if (batch.isEmpty()) break
            var lastOpenTime = cursor
            for (k in batch) {
                if (k.size < 5) continue
                val openTime = (k.getOrNull(0) as? Number)?.toLong()
                    ?: k.getOrNull(0)?.toString()?.toDoubleOrNull()?.toLong() ?: continue
                val open = k.getOrNull(1)?.toString()?.toSafeBigDecimal() ?: continue
                val close = k.getOrNull(4)?.toString()?.toSafeBigDecimal() ?: continue
                if (open <= BigDecimal.ZERO || close <= BigDecimal.ZERO) continue
                result.add(OneMin(openTime, open, close))
                lastOpenTime = openTime
            }
            // 下一页从最后一根之后开始
            val next = lastOpenTime + ONE_MINUTE_MS
            if (next <= cursor) break
            cursor = next
            if (batch.size < KLINE_PAGE_LIMIT) break
        }
        return result.distinctBy { it.openTimeMs }.sortedBy { it.openTimeMs }
    }
}
