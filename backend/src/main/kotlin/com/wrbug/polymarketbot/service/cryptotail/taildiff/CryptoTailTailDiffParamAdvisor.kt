package com.wrbug.polymarketbot.service.cryptotail.taildiff

import com.wrbug.polymarketbot.dto.TailDiffAdvisorBucket
import com.wrbug.polymarketbot.dto.TailDiffAdvisorRecommendation
import com.wrbug.polymarketbot.dto.TailDiffAdvisorResponse
import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import com.wrbug.polymarketbot.entity.CryptoTailStrategyTrigger
import com.wrbug.polymarketbot.enums.TradingMode
import com.wrbug.polymarketbot.repository.CryptoTailStrategyRepository
import com.wrbug.polymarketbot.repository.CryptoTailStrategyTriggerRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * TAIL_DIFF 自动参数建议（阶段四）。
 *
 * 思路：以"已结算且带入场快照（score/tier/diff_sigma/进场价/剩余秒）"的真实成交为唯一 ground truth，
 * 按 score / 价格 / diff_sigma / 剩余时间 / tier 多维分桶，统计胜率与单笔平均盈亏（EV 近似），
 * 据此反推更优的入场阈值（最低评分、价格区间、最低 diff_sigma、入场窗口）。
 *
 * 强约束：**仅推荐，不自动写入策略**。样本不足时只给分桶、不出建议。
 */
@Service
class CryptoTailTailDiffParamAdvisor(
    private val strategyRepository: CryptoTailStrategyRepository,
    private val triggerRepository: CryptoTailStrategyTriggerRepository
) {
    private val logger = LoggerFactory.getLogger(CryptoTailTailDiffParamAdvisor::class.java)

    private data class Sample(
        val score: Int,
        val tier: String,
        val price: BigDecimal,
        val diffSigma: BigDecimal?,
        val remaining: Int?,
        val win: Boolean,
        val pnl: BigDecimal
    )

    private class Agg {
        var n = 0
        var wins = 0
        var pnl = BigDecimal.ZERO
        fun add(s: Sample) {
            n++
            if (s.win) wins++
            pnl = pnl.add(s.pnl)
        }
        fun winRate(): BigDecimal = if (n > 0) BigDecimal(wins.toDouble() / n).setScale(6, RoundingMode.HALF_UP) else BigDecimal.ZERO
        fun avgPnl(): BigDecimal = if (n > 0) pnl.divide(BigDecimal(n), 8, RoundingMode.HALF_UP) else BigDecimal.ZERO
    }

    fun advise(strategyId: Long, minSamples: Int): TailDiffAdvisorResponse? {
        val strategy = strategyRepository.findById(strategyId).orElse(null) ?: return null
        val effectiveMinSamples = minSamples.coerceIn(1, 100000)

        val rows = triggerRepository.findResolvedTailDiffByStrategyId(strategyId, TradingMode.TAIL_DIFF)
        val samples = rows.mapNotNull { it.toSample() }

        val total = Agg()
        samples.forEach { total.add(it) }

        if (samples.isEmpty()) {
            return TailDiffAdvisorResponse(
                strategyId = strategyId,
                totalSettled = 0,
                winCount = 0,
                winRate = "0",
                totalPnl = "0",
                avgPnl = "0",
                sufficientSamples = false,
                minSamples = effectiveMinSamples
            )
        }

        val scoreBuckets = bucketBy(samples) { scoreBucketLabel(it.score) }
        val priceBuckets = bucketBy(samples) { TailDiffBuckets.oddsBucket(it.price) }
        val sigmaBuckets = bucketBy(samples.filter { it.diffSigma != null }) {
            TailDiffBuckets.diffSigmaBucket(it.diffSigma ?: BigDecimal.ZERO)
        }
        val remainingBuckets = bucketBy(samples.filter { it.remaining != null }) {
            TailDiffBuckets.remainingBucket(it.remaining ?: 0)
        }
        val tierBuckets = bucketBy(samples) { it.tier }

        val sufficient = samples.size >= effectiveMinSamples
        val recommendations = if (sufficient) {
            buildRecommendations(strategy, samples, effectiveMinSamples)
        } else {
            emptyList()
        }

        logger.info("TailDiff 参数建议: strategy=$strategyId settled=${samples.size} winRate=${total.winRate()} avgPnl=${total.avgPnl()} sufficient=$sufficient recs=${recommendations.size}")

        return TailDiffAdvisorResponse(
            strategyId = strategyId,
            totalSettled = samples.size,
            winCount = total.wins,
            winRate = total.winRate().toPlainString(),
            totalPnl = total.pnl.setScale(8, RoundingMode.HALF_UP).toPlainString(),
            avgPnl = total.avgPnl().toPlainString(),
            sufficientSamples = sufficient,
            minSamples = effectiveMinSamples,
            scoreBuckets = scoreBuckets,
            priceBuckets = priceBuckets,
            diffSigmaBuckets = sigmaBuckets,
            remainingBuckets = remainingBuckets,
            tierBuckets = tierBuckets,
            recommendations = recommendations
        )
    }

    private fun CryptoTailStrategyTrigger.toSample(): Sample? {
        val sc = score ?: return null
        val win = winnerOutcomeIndex != null && winnerOutcomeIndex == outcomeIndex
        return Sample(
            score = sc,
            tier = (tier ?: "UNKNOWN").uppercase(),
            price = triggerPrice,
            diffSigma = diffSigma,
            remaining = entryRemainingSeconds,
            win = win,
            pnl = realizedPnl ?: BigDecimal.ZERO
        )
    }

    private fun bucketBy(samples: List<Sample>, keyOf: (Sample) -> String): List<TailDiffAdvisorBucket> {
        val map = LinkedHashMap<String, Agg>()
        for (s in samples) {
            map.getOrPut(keyOf(s)) { Agg() }.add(s)
        }
        return map.entries
            .sortedBy { it.key }
            .map { (k, a) ->
                TailDiffAdvisorBucket(
                    bucket = k,
                    sampleCount = a.n,
                    winCount = a.wins,
                    winRate = a.winRate().toPlainString(),
                    totalPnl = a.pnl.setScale(8, RoundingMode.HALF_UP).toPlainString(),
                    avgPnl = a.avgPnl().toPlainString()
                )
            }
    }

    /** score 固定 5 分宽分桶，便于反推阈值 */
    private fun scoreBucketLabel(score: Int): String {
        val lo = (score / 5) * 5
        return "${lo}_${lo + 5}"
    }

    private fun buildRecommendations(
        strategy: CryptoTailStrategy,
        samples: List<Sample>,
        minSamples: Int
    ): List<TailDiffAdvisorRecommendation> {
        val recs = ArrayList<TailDiffAdvisorRecommendation>()

        // 1) 最低入场评分：在所有候选阈值中，选 avgPnl>0 的最低阈值（且尾部样本量充足）
        recommendMinEntryScore(strategy, samples, minSamples)?.let { recs.add(it) }

        // 2) 价格区间：取 avgPnl>0 的连续价格桶范围
        recommendPriceRange(strategy, samples, minSamples).let { recs.addAll(it) }

        // 3) 最低 diff_sigma
        recommendMinDiffSigma(strategy, samples, minSamples)?.let { recs.add(it) }

        return recs
    }

    private fun confidenceOf(n: Int): String = when {
        n >= 100 -> "HIGH"
        n >= 50 -> "MEDIUM"
        else -> "LOW"
    }

    private fun recommendMinEntryScore(
        strategy: CryptoTailStrategy,
        samples: List<Sample>,
        minSamples: Int
    ): TailDiffAdvisorRecommendation? {
        // 候选阈值：观察到的 score 分桶下界
        val candidates = samples.map { (it.score / 5) * 5 }.distinct().sorted()
        if (candidates.isEmpty()) return null
        var best: Pair<Int, BigDecimal>? = null // (threshold, avgPnl)
        for (th in candidates) {
            val subset = samples.filter { it.score >= th }
            if (subset.size < minSamples) continue
            val avg = subset.sumOf { it.pnl.toDouble() } / subset.size
            // 选满足 avgPnl>0 的最低阈值；并在同样为正中偏好更高 avgPnl
            if (avg > 0.0) {
                if (best == null) {
                    best = th to BigDecimal(avg)
                    break
                }
            }
        }
        // 若没有任何阈值使 avgPnl>0，则建议提高到 avgPnl 最大的阈值
        if (best == null) {
            var bestAvg = Double.NEGATIVE_INFINITY
            var bestTh = candidates.last()
            for (th in candidates) {
                val subset = samples.filter { it.score >= th }
                if (subset.size < minSamples) continue
                val avg = subset.sumOf { it.pnl.toDouble() } / subset.size
                if (avg > bestAvg) {
                    bestAvg = avg
                    bestTh = th
                }
            }
            best = bestTh to BigDecimal(if (bestAvg.isFinite()) bestAvg else 0.0)
        }
        val suggested = best.first
        val subsetSize = samples.count { it.score >= suggested }
        val current = strategy.tailDiffMinEntryScore
        return TailDiffAdvisorRecommendation(
            param = "tailDiffMinEntryScore",
            labelKey = "cryptoTailStrategy.advisor.param.minEntryScore",
            currentValue = current.toString(),
            suggestedValue = suggested.toString(),
            changed = suggested != current,
            sampleCount = subsetSize,
            confidence = confidenceOf(subsetSize),
            rationale = "score>=$suggested 子集平均单笔盈亏=${best.second.setScale(6, RoundingMode.HALF_UP).toPlainString()}，样本=$subsetSize"
        )
    }

    private fun recommendPriceRange(
        strategy: CryptoTailStrategy,
        samples: List<Sample>,
        minSamples: Int
    ): List<TailDiffAdvisorRecommendation> {
        // 按价格升序分组到 odds 桶，找 avgPnl>0 的连续区间
        data class PB(val lo: BigDecimal, val hi: BigDecimal, val agg: Agg)
        val byBucket = LinkedHashMap<String, Agg>()
        val bucketBounds = HashMap<String, Pair<BigDecimal, BigDecimal>>()
        for (s in samples) {
            val key = TailDiffBuckets.oddsBucket(s.price)
            byBucket.getOrPut(key) { Agg() }.add(s)
            bucketBounds.getOrPut(key) {
                val parts = key.split("_")
                val lo = parts.getOrNull(0)?.toBigDecimalOrNull() ?: s.price
                val hi = parts.getOrNull(1)?.toBigDecimalOrNull() ?: s.price
                lo to hi
            }
        }
        val ordered = byBucket.entries
            .mapNotNull { (k, a) ->
                val b = bucketBounds[k] ?: return@mapNotNull null
                PB(b.first, b.second, a)
            }
            .sortedBy { it.lo }
        // 取 avgPnl>0 且样本量达标的桶
        val positive = ordered.filter { it.agg.avgPnl() > BigDecimal.ZERO && it.agg.n >= (minSamples / 3).coerceAtLeast(5) }
        if (positive.isEmpty()) return emptyList()
        val minLo = positive.minOf { it.lo }
        val maxHi = positive.maxOf { it.hi }.min(BigDecimal.ONE)
        val totalN = positive.sumOf { it.agg.n }

        val recs = ArrayList<TailDiffAdvisorRecommendation>()
        if (minLo.compareTo(strategy.tailDiffMinPrice) != 0) {
            recs.add(
                TailDiffAdvisorRecommendation(
                    param = "tailDiffMinPrice",
                    labelKey = "cryptoTailStrategy.advisor.param.minPrice",
                    currentValue = strategy.tailDiffMinPrice.toPlainString(),
                    suggestedValue = minLo.toPlainString(),
                    changed = true,
                    sampleCount = totalN,
                    confidence = confidenceOf(totalN),
                    rationale = "盈利价格桶下界=${minLo.toPlainString()}"
                )
            )
        }
        if (maxHi.compareTo(strategy.tailDiffMaxPrice) != 0) {
            recs.add(
                TailDiffAdvisorRecommendation(
                    param = "tailDiffMaxPrice",
                    labelKey = "cryptoTailStrategy.advisor.param.maxPrice",
                    currentValue = strategy.tailDiffMaxPrice.toPlainString(),
                    suggestedValue = maxHi.toPlainString(),
                    changed = true,
                    sampleCount = totalN,
                    confidence = confidenceOf(totalN),
                    rationale = "盈利价格桶上界=${maxHi.toPlainString()}"
                )
            )
        }
        return recs
    }

    private fun recommendMinDiffSigma(
        strategy: CryptoTailStrategy,
        samples: List<Sample>,
        minSamples: Int
    ): TailDiffAdvisorRecommendation? {
        val withSigma = samples.filter { it.diffSigma != null }
        if (withSigma.size < minSamples) return null
        // 候选阈值取 diff_sigma 桶下界
        val candidates = listOf("0", "1.0", "1.5", "2.0", "2.5", "3.0").map { it.toBigDecimal() }
        var chosen: BigDecimal? = null
        var chosenAvg = BigDecimal.ZERO
        for (th in candidates) {
            val subset = withSigma.filter { (it.diffSigma ?: BigDecimal.ZERO) >= th }
            if (subset.size < (minSamples / 2).coerceAtLeast(10)) continue
            val avg = subset.sumOf { it.pnl.toDouble() } / subset.size
            if (avg > 0.0) {
                chosen = th
                chosenAvg = BigDecimal(avg)
                break
            }
        }
        if (chosen == null) return null
        val subsetSize = withSigma.count { (it.diffSigma ?: BigDecimal.ZERO) >= chosen }
        val current = strategy.tailDiffMinDiffSigma
        return TailDiffAdvisorRecommendation(
            param = "tailDiffMinDiffSigma",
            labelKey = "cryptoTailStrategy.advisor.param.minDiffSigma",
            currentValue = current.toPlainString(),
            suggestedValue = chosen.toPlainString(),
            changed = chosen.compareTo(current) != 0,
            sampleCount = subsetSize,
            confidence = confidenceOf(subsetSize),
            rationale = "diff_sigma>=${chosen.toPlainString()} 子集平均盈亏=${chosenAvg.setScale(6, RoundingMode.HALF_UP).toPlainString()}，样本=$subsetSize"
        )
    }

    private fun String.toBigDecimalOrNull(): BigDecimal? = try {
        BigDecimal(this)
    } catch (_: Exception) {
        null
    }
}
