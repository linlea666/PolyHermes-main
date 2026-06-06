package com.wrbug.polymarketbot.service.cryptotail.taildiff

import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import com.wrbug.polymarketbot.util.fromJson
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * 入场分段解析器：把 [CryptoTailStrategy.tailDiffEntrySegmentsJson] 解析为多个剩余时间窗口段，
 * 命中某段后用该段的窗口/阈值覆盖策略对应字段，再交给原 ScoreEngine/分层/退出冻结链路。
 *
 * 设计（copy-overlay）：
 *  - 不改 ScoreEngine 签名与内部读取；命中段后产出 strategy.copy(...) 覆盖
 *    {minEntryScore, minDiffSigma, minEdge, minModelProb, minPrice(min_ask), hardMaxPrice(max_ask), windowStart, windowEnd} 字段，
 *    ScoreEngine 照常读取这些字段即得到分段后的有效阈值，零回归风险。
 *  - segments 为空/NULL 时 [resolve] 始终返回"无覆盖默认段"，effStrategy == strategy，行为完全不变；
 *    窗口仍由 ScoreEngine 的 WINDOW_TOO_EARLY/LATE 否决把关（与旧逻辑一致）。
 *  - segments 非空但 remaining 不落在任何段内 → [resolve] 返回 null，调用方记 WINDOW_NO_SEGMENT 并 SKIP（opt-in）。
 *
 * JSON 结构（数组，每段）：
 *  { "name", "remaining_hi", "remaining_lo", "min_score?", "min_diff_sigma?", "min_edge?", "min_model_prob?", "min_ask?", "max_ask?", "exit_tier_bias?", "stake_mult?" }
 *  - remaining_hi >= remaining_lo；命中条件：remaining_lo <= remainingSeconds <= remaining_hi
 *  - 缺省的阈值字段回退策略全局值。
 *  - stake_mult：该段下注倍率（按时间窗精准降仓/加仓，缺省=1.0），最终金额=base×tier×stake_mult 再过 [MIN_ORDER_USDC, maxAmount, 余额] 钳制。
 */
@Component
class TailDiffEntrySegmentResolver {

    private val logger = LoggerFactory.getLogger(TailDiffEntrySegmentResolver::class.java)

    data class Segment(
        val name: String = "",
        /** 窗口上界：较大的剩余秒数（更早） */
        val remainingHiSeconds: Int = 0,
        /** 窗口下界：较小的剩余秒数（更接近结算） */
        val remainingLoSeconds: Int = 0,
        val minScore: Int? = null,
        val minDiffSigma: BigDecimal? = null,
        val minEdge: BigDecimal? = null,
        val minModelProb: BigDecimal? = null,
        /** 该段入场价格下限（bestAsk 下限），覆盖全局 tailDiffMinPrice；缺省回退全局值。 */
        val minAsk: BigDecimal? = null,
        val maxAsk: BigDecimal? = null,
        val exitTierBias: TailDiffTier? = null,
        /** 该段下注倍率（按时间窗精准降仓/加仓），缺省=null 表示用全局 1.0。 */
        val stakeMult: BigDecimal? = null,
        /** 是否为"无覆盖默认段"（segments 为空时合成，保持旧行为） */
        val isDefault: Boolean = false
    )

    /**
     * 解析并命中：
     *  - segments 空/NULL → 返回无覆盖默认段（effStrategy 不变，向后兼容）。
     *  - segments 非空 → 返回首个命中段；无命中返回 null（调用方 SKIP WINDOW_NO_SEGMENT）。
     */
    fun resolve(strategy: CryptoTailStrategy, remainingSeconds: Int): Segment? {
        val segments = parse(strategy)
        if (segments.isEmpty()) {
            return Segment(name = "default", isDefault = true)
        }
        return segments.firstOrNull { remainingSeconds in it.remainingLoSeconds..it.remainingHiSeconds }
    }

    /** 命中段后产出覆盖了有效阈值/窗口的 strategy 副本；默认段返回原 strategy。 */
    fun applyOverrides(strategy: CryptoTailStrategy, segment: Segment): CryptoTailStrategy {
        if (segment.isDefault) return strategy
        return strategy.copy(
            tailDiffWindowStartSeconds = segment.remainingHiSeconds,
            tailDiffWindowEndSeconds = segment.remainingLoSeconds,
            tailDiffMinEntryScore = segment.minScore ?: strategy.tailDiffMinEntryScore,
            tailDiffMinDiffSigma = segment.minDiffSigma ?: strategy.tailDiffMinDiffSigma,
            tailDiffMinEdge = segment.minEdge ?: strategy.tailDiffMinEdge,
            tailDiffMinModelProb = segment.minModelProb ?: strategy.tailDiffMinModelProb,
            tailDiffMinPrice = segment.minAsk ?: strategy.tailDiffMinPrice,
            tailDiffHardMaxPrice = segment.maxAsk ?: strategy.tailDiffHardMaxPrice
        )
    }

    /** 该段的退出分层偏置；无偏置时回退评分分层 [scoredTier]。 */
    fun resolveExitTier(segment: Segment?, scoredTier: TailDiffTier): TailDiffTier =
        segment?.exitTierBias ?: scoredTier

    /**
     * 入场预过滤窗口包络（供 checkEntryMarketQuality 用）：
     *  - segments 空 → (tailDiffMinRemainingSeconds, tailDiffWindowStartSeconds)（旧行为）。
     *  - segments 非空 → (min(所有 remaining_lo), max(所有 remaining_hi))，下界不低于 0。
     */
    fun windowEnvelope(strategy: CryptoTailStrategy): Pair<Int, Int> {
        val segments = parse(strategy)
        if (segments.isEmpty()) {
            return strategy.tailDiffMinRemainingSeconds to strategy.tailDiffWindowStartSeconds
        }
        val lo = segments.minOf { it.remainingLoSeconds }.coerceAtLeast(0)
        val hi = segments.maxOf { it.remainingHiSeconds }
        return lo to hi
    }

    fun parse(strategy: CryptoTailStrategy): List<Segment> {
        val raw = strategy.tailDiffEntrySegmentsJson
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val list = raw.fromJson<List<Map<String, Any?>>>() ?: emptyList()
            list.mapNotNull { parseSegment(it) }
        } catch (e: Exception) {
            logger.warn("解析 tailDiffEntrySegmentsJson 失败，按单窗口处理: strategyId=${strategy.id}, ${e.message}")
            emptyList()
        }
    }

    private fun parseSegment(m: Map<String, Any?>): Segment? {
        val hi = asInt(m["remaining_hi"]) ?: return null
        val lo = asInt(m["remaining_lo"]) ?: return null
        if (hi < lo) return null
        return Segment(
            name = (m["name"] as? String) ?: "",
            remainingHiSeconds = hi,
            remainingLoSeconds = lo,
            minScore = asInt(m["min_score"]),
            minDiffSigma = asBigDecimal(m["min_diff_sigma"]),
            minEdge = asBigDecimal(m["min_edge"]),
            minModelProb = asBigDecimal(m["min_model_prob"]),
            minAsk = asBigDecimal(m["min_ask"]),
            maxAsk = asBigDecimal(m["max_ask"]),
            exitTierBias = TailDiffTier.fromLabel(m["exit_tier_bias"] as? String),
            stakeMult = asBigDecimal(m["stake_mult"])
        )
    }

    private fun asInt(v: Any?): Int? = when (v) {
        is Number -> v.toInt()
        is String -> v.trim().toDoubleOrNull()?.toInt()
        else -> null
    }

    private fun asBigDecimal(v: Any?): BigDecimal? = when (v) {
        is Number -> BigDecimal(v.toString())
        is String -> v.trim().takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()
        else -> null
    }

    /**
     * 校验 segments JSON 合法性（供 create/update 校验复用）：每段 remaining_hi>=remaining_lo>=0，
     * 阈值范围合理，exit_tier_bias 合法；空串/NULL 视为合法（单窗口）。
     */
    fun isValid(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return true
        val list = try {
            raw.fromJson<List<Map<String, Any?>>>() ?: return false
        } catch (e: Exception) {
            return false
        }
        if (list.isEmpty()) return false
        for (m in list) {
            val hi = asInt(m["remaining_hi"]) ?: return false
            val lo = asInt(m["remaining_lo"]) ?: return false
            if (lo < 0 || hi < lo) return false
            asInt(m["min_score"])?.let { if (it < 0 || it > 100) return false }
            asBigDecimal(m["min_diff_sigma"])?.let { if (it < BigDecimal.ZERO) return false }
            asBigDecimal(m["min_edge"])?.let { if (it < BigDecimal.ZERO || it >= BigDecimal.ONE) return false }
            asBigDecimal(m["min_model_prob"])?.let { if (it <= BigDecimal.ZERO || it > BigDecimal.ONE) return false }
            asBigDecimal(m["min_ask"])?.let { if (it <= BigDecimal.ZERO || it > BigDecimal.ONE) return false }
            asBigDecimal(m["max_ask"])?.let { if (it <= BigDecimal.ZERO || it > BigDecimal.ONE) return false }
            // min_ask 与 max_ask 同时提供时，下界不得高于上界（否则该段恒不可入场）
            val minAskV = asBigDecimal(m["min_ask"])
            val maxAskV = asBigDecimal(m["max_ask"])
            if (minAskV != null && maxAskV != null && minAskV > maxAskV) return false
            asBigDecimal(m["stake_mult"])?.let { if (it <= BigDecimal.ZERO) return false }
            val biasRaw = m["exit_tier_bias"] as? String
            if (biasRaw != null && TailDiffTier.fromLabel(biasRaw) == null) return false
        }
        return true
    }
}
