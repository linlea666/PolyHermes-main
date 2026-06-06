package com.wrbug.polymarketbot.service.cryptotail.reversal

import com.wrbug.polymarketbot.entity.CryptoTailReversalStat
import com.wrbug.polymarketbot.repository.CryptoTailReversalStatRepository
import com.wrbug.polymarketbot.service.cryptotail.BarrierProbability
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
 * 思路：拉取币种 K 线（间隔 = samplingSeconds，回溯 lookbackDays），按 interval 对齐切分为周期；
 * 对每个完整周期，以 openP=周期首根 open、finalClose=周期末根 close 确定结算方向；
 * 再对周期内每个取样边界作为"观测点"，按当时 close 计算领先方向与 diff_sigma，
 * 判断领先方向是否被最终结算反转，按分桶累计，最终 model_prob = 1 - 反转率。
 *
 * 精度增强（V67）：
 *  - 细采样：samplingSeconds=1 时拉 1s K 线（近结算更细），默认 60（1m，向后兼容旧行为）。
 *    1s 数据量巨大，自动将回溯天数收敛到 [MAX_1S_LOOKBACK_DAYS] 以内，避免请求量失控。
 *  - first-satisfy 去重：同一周期对同一桶最多计一次（取最早命中的观测点），消除相邻观测点强相关导致的样本虚高。
 *  - MAE/MFE + 虚拟括号退出：以"领先方向胜率 p"（BarrierProbability.winProbTerminal 推导）口径，
 *    统计入场后最大不利/有利偏移与虚拟 TP/STOP/结算结清的胜率与盈亏（见 [TailReversalMetrics]）。
 *
 * σ 估计：用滑动窗口内最近的取样收盘价绝对变动标准差换算到 per-√s。
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
        private const val KLINE_PAGE_LIMIT = 1000
        /** σ 滑动窗口时间跨度（秒），换算成步数后随取样间隔自适应（1m→60 步，1s→3600 步） */
        private const val SIGMA_WINDOW_SECONDS = 3600
        /** 1s 细采样的回溯天数上限：14d × 86400 ≈ 121 万根，约 1210 页 < 拉取上限 */
        private const val MAX_1S_LOOKBACK_DAYS = 14
        private val coinToSymbol = mapOf("BTC" to "BTCUSDC", "ETH" to "ETHUSDC")
    }

    data class BackfillSummary(
        val coin: String,
        val intervalSeconds: Int,
        val lookbackDays: Int,
        val samplingSeconds: Int,
        val periodsProcessed: Int,
        val observations: Int,
        val bucketsWritten: Int
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

    /**
     * 回填一个 (coin, interval, lookbackDays, samplingSeconds) 维度。幂等：先删旧分桶再写新分桶。
     * @param samplingSeconds 取样间隔：1（1s 细采样）或 60（1m，默认）。其余值按 60 处理。
     * @return null 表示币种不支持或无可用 K 线
     */
    @Transactional
    fun backfill(coin: String, intervalSeconds: Int, lookbackDays: Int, samplingSeconds: Int = 60): BackfillSummary? {
        val coinUpper = coin.trim().uppercase()
        val symbol = coinToSymbol[coinUpper] ?: run {
            logger.warn("反转回填：不支持的币种 $coin")
            return null
        }
        if (intervalSeconds != 300 && intervalSeconds != 900) return null
        if (lookbackDays <= 0) return null

        // 取样间隔归一：仅支持 Binance 原生的 1s / 1m
        val step = if (samplingSeconds == 1) 1 else 60
        val klineInterval = if (step == 1) "1s" else "1m"
        // 1s 细采样数据量巨大，收敛回溯天数避免请求失控
        val effLookbackDays = if (step == 1) lookbackDays.coerceAtMost(MAX_1S_LOOKBACK_DAYS) else lookbackDays
        if (effLookbackDays != lookbackDays) {
            logger.warn("反转回填：1s 细采样将回溯天数从 $lookbackDays 收敛到 $effLookbackDays（控制请求量）")
        }

        val nowMs = System.currentTimeMillis()
        val startMs = nowMs - effLookbackDays.toLong() * 24L * 3600L * 1000L
        val stepMs = step.toLong() * 1000L
        val candlesData = fetchAllKlines(symbol, klineInterval, stepMs, startMs, nowMs)
        if (candlesData.isEmpty()) {
            logger.warn("反转回填：$symbol 无可用 $klineInterval K 线")
            return null
        }

        val periodMs = intervalSeconds.toLong() * 1000L
        val candlesPerPeriod = intervalSeconds / step
        // 按对齐周期分组
        val grouped = LinkedHashMap<Long, MutableList<Candle>>()
        for (c in candlesData) {
            val periodStart = (c.openTimeMs / periodMs) * periodMs
            grouped.getOrPut(periodStart) { mutableListOf() }.add(c)
        }

        // 滑动 σ 窗口：最近 sigmaWindowSteps 根的收盘价绝对变动
        val sigmaWindowSteps = (SIGMA_WINDOW_SECONDS / step).coerceAtLeast(5)
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
                    while (recentAbsReturns.size > sigmaWindowSteps) recentAbsReturns.removeFirst()
                }
                prevClose = close
            }
            if (candles.size < candlesPerPeriod) continue
            candles.sortBy { it.openTimeMs }

            val openP = candles.first().open
            val finalClose = candles.last().close
            val settledOutcome = if (finalClose >= openP) 0 else 1
            val sigmaPerSqrtS = currentSigmaPerSqrtS(recentAbsReturns, step)
            if (sigmaPerSqrtS <= 0.0) continue
            val sigmaBd = BigDecimal(sigmaPerSqrtS)

            periodsProcessed++

            // 预计算该周期所有观测点：观测点 i 取 candles[i-1].close，elapsed=i*step，remaining=interval-elapsed
            val obsList = ArrayList<ObsPoint>(candles.size)
            for (i in 1 until candles.size) {
                val remaining = intervalSeconds - i * step
                if (remaining <= 0) continue
                val obsClose = candles[i - 1].close
                val gap = obsClose.subtract(openP)
                val result = BarrierProbability.winProbTerminal(gap, sigmaBd, remaining.toDouble()) ?: continue
                val leadOutcome = result.side
                obsList.add(
                    ObsPoint(
                        remaining = remaining,
                        leadOutcome = leadOutcome,
                        diffSigma = result.safeRatio,
                        pWinSide = result.pWin.toDouble()
                    )
                )
            }
            if (obsList.isEmpty()) continue

            // first-satisfy 去重：同周期同桶仅记最早命中的观测点
            val seenKeysThisPeriod = HashSet<BucketKey>()
            for (k in obsList.indices) {
                val obs = obsList[k]
                val key = BucketKey(
                    outcomeIndex = obs.leadOutcome,
                    diffSigmaBucket = TailDiffBuckets.diffSigmaBucket(obs.diffSigma),
                    remainingBucket = TailDiffBuckets.remainingBucket(obs.remaining)
                )
                if (!seenKeysThisPeriod.add(key)) continue

                val entryPLead = obs.pWinSide // 入场点 side==leadOutcome，pWinSide 即领先方向胜率
                val forwardP = ArrayList<Double>(obsList.size - k - 1)
                for (j in k + 1 until obsList.size) {
                    val fj = obsList[j]
                    val pLead = if (fj.leadOutcome == obs.leadOutcome) fj.pWinSide else 1.0 - fj.pWinSide
                    forwardP.add(pLead)
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

        // 幂等：删旧写新（按 coin+interval+lookback+source+sampling 维度；仅清同精度旧行，1m 与 1s 共存互不覆盖）
        reversalStatRepository.deleteByCoinAndIntervalSecondsAndLookbackDaysAndDataSourceAndSamplingSeconds(
            coinUpper, intervalSeconds, lookbackDays, DATA_SOURCE, step
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
                samplingSeconds = step,
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
        logger.info("反转回填完成: coin=$coinUpper interval=$intervalSeconds lookback=$lookbackDays sampling=${step}s periods=$periodsProcessed obs=$observations buckets=${rows.size}")
        return BackfillSummary(coinUpper, intervalSeconds, lookbackDays, step, periodsProcessed, observations, rows.size)
    }

    private fun avg(sum: Double, count: Int): BigDecimal? =
        if (count > 0) BigDecimal(sum / count).setScale(8, RoundingMode.HALF_UP) else null

    private fun rate(hit: Int, count: Int): BigDecimal? =
        if (count > 0) BigDecimal(hit.toDouble() / count).setScale(8, RoundingMode.HALF_UP) else null

    private fun currentSigmaPerSqrtS(recentAbsReturns: Collection<Double>, stepSeconds: Int): Double {
        if (recentAbsReturns.size < 5) return 0.0
        val mean = recentAbsReturns.average()
        val variance = recentAbsReturns.sumOf { (it - mean) * (it - mean) } / recentAbsReturns.size
        val sigmaPerStep = sqrt(variance)
        // 单步变动 ~ √(stepSeconds)，换算到 per-√s
        return sigmaPerStep / sqrt(stepSeconds.toDouble())
    }

    private data class Candle(val openTimeMs: Long, val open: BigDecimal, val close: BigDecimal)
    private data class BucketKey(val outcomeIndex: Int, val diffSigmaBucket: String, val remainingBucket: String)
    private data class ObsPoint(val remaining: Int, val leadOutcome: Int, val diffSigma: BigDecimal, val pWinSide: Double)

    /** 分页拉取 [startMs, endMs] 区间内所有指定间隔 K 线（按 openTime 升序、去重） */
    private fun fetchAllKlines(symbol: String, interval: String, stepMs: Long, startMs: Long, endMs: Long): List<Candle> {
        val api = retrofitFactory.createBinanceApi()
        val result = ArrayList<Candle>()
        var cursor = startMs
        var safety = 0
        val maxRequests = 2000
        while (cursor < endMs && safety < maxRequests) {
            safety++
            val batch = try {
                val resp = api.getKlines(symbol = symbol, interval = interval, limit = KLINE_PAGE_LIMIT, startTime = cursor, endTime = endMs).execute()
                if (resp.isSuccessful) resp.body() ?: emptyList() else emptyList()
            } catch (e: Exception) {
                logger.warn("拉取 $symbol $interval K 线失败 cursor=$cursor: ${e.message}")
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
                result.add(Candle(openTime, open, close))
                lastOpenTime = openTime
            }
            // 下一页从最后一根之后开始
            val next = lastOpenTime + stepMs
            if (next <= cursor) break
            cursor = next
            if (batch.size < KLINE_PAGE_LIMIT) break
        }
        return result.distinctBy { it.openTimeMs }.sortedBy { it.openTimeMs }
    }
}
