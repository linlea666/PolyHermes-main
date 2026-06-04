package com.wrbug.polymarketbot.service.cryptotail.taildiff

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.util.ArrayDeque

/**
 * 赔率速度跟踪器（V72，动态赔率滞后因子专用）。
 *
 * 用途：
 *  - 配合 [CryptoTailReverseVelocityTracker.computeLeadMoveSigma]（标的朝领先方向的动量）计算
 *    gtp §12「赔率反应滞后」：标的已扩大领先、但 Polymarket 赔率 5-10s 内还没同步上行 → 滞后高 → 利润未被吃掉。
 *  - 仅服务评分（[CryptoTailScoreEngine] 赔率滞后分项 DYNAMIC/HYBRID 模式），不参与硬否决。
 *
 * 复用决策（独立新写）：
 *  - 与 [CryptoTailReverseVelocityTracker] 语义不同（这里跟踪「赔率/盘口价」而非「标的现货价」，且不归一化到 σ），
 *    强行复用会把两类序列耦合进同一接口；故独立维护一条轻量赔率序列。
 *  - 按 (marketSlugPrefix + outcomeIndex) 维度独立维护：Up/Down 两个 token 赔率不同，必须分开。
 *
 * 实现：每 key 一条环形序列；返回窗口内 (oddsLatest - oddsWindowStart) 的带符号变化与经过秒数。
 * 样本不足/窗口未到 → 返回 reason，调用方按「不可用」回退到静态滞后。
 */
@Component
class CryptoTailOddsVelocityTracker {

    /** 每 key 一条赔率序列；总容量保护：最多 400 个 key（200 市场 × 2 outcome），60s 不更新自动驱逐 */
    private val trackers: Cache<String, OddsSeries> = Caffeine.newBuilder()
        .maximumSize(400)
        .expireAfterAccess(Duration.ofSeconds(60))
        .build()

    /**
     * 喂入最新赔率观测（建议每次 evaluate 入口调用一次）。
     * @param key 建议使用 "${marketSlugPrefix}-$outcomeIndex"
     * @param odds 该 outcome 的最优买价（bestBid），代表市场对该方向的隐含概率
     */
    fun observe(key: String, odds: BigDecimal, nowMs: Long = System.currentTimeMillis()) {
        if (odds <= BigDecimal.ZERO) return
        trackers.get(key) { OddsSeries() }!!.push(odds, nowMs)
    }

    data class Result(
        /** 窗口内赔率带符号变化（last - first）；正值=赔率上行（市场在跟上） */
        val oddsDelta: BigDecimal,
        /** 窗口经过秒数 */
        val windowElapsedSeconds: Double,
        /** 窗口内样本数 */
        val sampleCount: Int,
        /** 不可用原因（样本不足/窗口未到）；非 null 时调用方应回退静态滞后 */
        val reason: String? = null
    )

    /**
     * 计算窗口内赔率上行幅度。
     */
    fun computeOddsMove(
        key: String,
        windowSeconds: Int,
        nowMs: Long = System.currentTimeMillis()
    ): Result {
        val tracker = trackers.getIfPresent(key)
            ?: return Result(BigDecimal.ZERO, 0.0, 0, "NO_SAMPLES")
        val snapshot = tracker.snapshot(windowSeconds, nowMs)
        if (snapshot.size < 2) return Result(BigDecimal.ZERO, 0.0, snapshot.size, "INSUFFICIENT_SAMPLES")
        val first = snapshot.first()
        val last = snapshot.last()
        val elapsedSec = (last.ts - first.ts).coerceAtLeast(1L) / 1000.0
        if (elapsedSec < 1.0) return Result(BigDecimal.ZERO, elapsedSec, snapshot.size, "WINDOW_TOO_SHORT")
        val delta = last.odds.subtract(first.odds).setScale(8, RoundingMode.HALF_UP)
        return Result(
            oddsDelta = delta,
            windowElapsedSeconds = elapsedSec,
            sampleCount = snapshot.size
        )
    }

    private data class Sample(val odds: BigDecimal, val ts: Long)

    private class OddsSeries {
        private val deque = ArrayDeque<Sample>()
        private val lock = Object()
        private val maxSize = 120

        fun push(odds: BigDecimal, nowMs: Long) {
            synchronized(lock) {
                deque.addLast(Sample(odds, nowMs))
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

    /** 测试/诊断辅助：清空指定 key 的序列 */
    fun reset(key: String) {
        trackers.invalidate(key)
    }
}
