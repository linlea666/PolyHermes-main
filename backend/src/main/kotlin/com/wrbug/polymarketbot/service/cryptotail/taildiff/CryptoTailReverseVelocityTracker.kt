package com.wrbug.polymarketbot.service.cryptotail.taildiff

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.util.ArrayDeque

/**
 * 反抽速度计算器：基于价源最近 N 秒的价格序列，估算"向目标价回抽"的 σ/s 速度。
 *
 * 用途：
 *  - 入场前否决（PRICE_RETRACING_FAST）：若现货价反向回抽过快，意味着 diff 正在收窄，禁止下注
 *  - 入场后退出（dynamic_exit.max_reverse_velocity_sigma）：持仓中反抽过快应主动退出
 *
 * 实现：
 *  - 每个 marketSlugPrefix 维护一条环形序列（最多 60 个样本，覆盖默认 10s 窗口）
 *  - 计算口径：取窗口内 (priceLatest - priceWindowStart) 的方向系数 × 价差 / σ，再除以经过秒数
 *  - 若样本不足或窗口期未到，返回 null（调用方按"不可用"处理，不作为否决依据）
 *
 * 由 [com.wrbug.polymarketbot.service.cryptotail.taildiff.CryptoTailTailDiffDecisionService] 在每次 tick 调用
 * [observe] 喂入最新价源；评估时 [computeReverseVelocity] 拉取窗口快照。
 *
 * 复用决策：不依赖外部 priceProvider 的 OHLC 采样（频率不够），直接在内存维护 1-2Hz 的快照。
 */
@Component
class CryptoTailReverseVelocityTracker {

    private val logger = LoggerFactory.getLogger(CryptoTailReverseVelocityTracker::class.java)

    /** 每 slug 一条价格序列；总容量保护：最多 200 个 slug，30s 不更新自动驱逐 */
    private val trackers: Cache<String, SlugSeries> = Caffeine.newBuilder()
        .maximumSize(200)
        .expireAfterAccess(Duration.ofSeconds(60))
        .build()

    /**
     * 喂入最新观测值（建议每次 evaluateForEntry 入口调用一次，频率不要高于 5Hz）。
     */
    fun observe(marketSlugPrefix: String, price: BigDecimal, nowMs: Long = System.currentTimeMillis()) {
        if (price <= BigDecimal.ZERO) return
        trackers.get(marketSlugPrefix) { SlugSeries() }!!.push(price, nowMs)
    }

    data class Result(
        /** σ/s 反抽速度（绝对值）；正值=反向回抽，负值=顺向延伸（不应触发否决） */
        val velocitySigmaPerSec: BigDecimal,
        /** 是否反向（向 open 回抽）；不反向时速度无意义，调用方应跳过否决 */
        val isReversing: Boolean,
        /** 窗口经过秒数 */
        val windowElapsedSeconds: Double,
        /** 窗口内样本数 */
        val sampleCount: Int,
        /** 不可用原因（样本不足/窗口未到/σ无效） */
        val reason: String? = null
    )

    /**
     * 计算"价格反抽速度"：σ/s 单位。
     *
     * @param marketSlugPrefix 市场前缀
     * @param outcomeIndex 持仓 outcome：0=Up（反抽=价格下行），1=Down（反抽=价格上行）
     * @param sigmaPerSqrtS 当前周期 σ_per_√s（与 BarrierProbability 同源）
     * @param windowSeconds 窗口秒数（默认 10s）
     * @param nowMs 现在 ms
     */
    fun computeReverseVelocity(
        marketSlugPrefix: String,
        outcomeIndex: Int,
        sigmaPerSqrtS: BigDecimal,
        windowSeconds: Int = 10,
        nowMs: Long = System.currentTimeMillis()
    ): Result {
        val tracker = trackers.getIfPresent(marketSlugPrefix) ?: return Result(
            BigDecimal.ZERO, false, 0.0, 0, "NO_SAMPLES"
        )
        val snapshot = tracker.snapshot(windowSeconds, nowMs)
        if (snapshot.size < 2) return Result(BigDecimal.ZERO, false, 0.0, snapshot.size, "INSUFFICIENT_SAMPLES")
        if (sigmaPerSqrtS <= BigDecimal.ZERO) return Result(BigDecimal.ZERO, false, 0.0, snapshot.size, "SIGMA_INVALID")
        val first = snapshot.first()
        val last = snapshot.last()
        val elapsedMs = (last.ts - first.ts).coerceAtLeast(1L)
        val elapsedSec = elapsedMs / 1000.0
        if (elapsedSec < 1.0) return Result(BigDecimal.ZERO, false, elapsedSec, snapshot.size, "WINDOW_TOO_SHORT")
        val priceDelta = last.price.subtract(first.price) // last - first
        // outcome=0 Up：反抽方向 = 价格下行（priceDelta 为负 → 反向延伸；正 → 反抽）
        //  反抽幅度 = max(0, -priceDelta) for Up, max(0, priceDelta) for Down
        // 注：这里的"反抽"含义是"价格回到 open 方向"，与 outcome 的盈利方向相反。
        //  outcomeIndex=0 持仓 Up → 反抽=向下；outcomeIndex=1 持仓 Down → 反抽=向上。
        val reverseAmount = if (outcomeIndex == 0) priceDelta.negate() else priceDelta
        val isReversing = reverseAmount.signum() > 0
        if (!isReversing) {
            return Result(BigDecimal.ZERO, false, elapsedSec, snapshot.size)
        }
        // σ/s = reverseAmount / (sigmaPerSqrtS * sqrt(elapsedSec)) / elapsedSec
        val expectedMove = sigmaPerSqrtS.toDouble() * Math.sqrt(elapsedSec)
        if (expectedMove <= 0.0) return Result(BigDecimal.ZERO, false, elapsedSec, snapshot.size, "EXPECTED_MOVE_ZERO")
        val sigmaUnits = reverseAmount.toDouble() / expectedMove
        val velocity = sigmaUnits / elapsedSec
        return Result(
            velocitySigmaPerSec = BigDecimal(velocity).setScale(6, RoundingMode.HALF_UP),
            isReversing = true,
            windowElapsedSeconds = elapsedSec,
            sampleCount = snapshot.size
        )
    }

    private data class Sample(val price: BigDecimal, val ts: Long)

    private class SlugSeries {
        private val deque = ArrayDeque<Sample>()
        private val lock = Object()
        private val maxSize = 120

        fun push(price: BigDecimal, nowMs: Long) {
            synchronized(lock) {
                deque.addLast(Sample(price, nowMs))
                while (deque.size > maxSize) deque.removeFirst()
            }
        }

        fun snapshot(windowSeconds: Int, nowMs: Long): List<Sample> {
            val cutoff = nowMs - windowSeconds * 1000L
            synchronized(lock) {
                return deque.filter { it.ts >= cutoff }
            }
        }
    }

    /** 测试/诊断辅助：清空指定 slug 的序列 */
    fun reset(marketSlugPrefix: String) {
        trackers.invalidate(marketSlugPrefix)
    }
}
