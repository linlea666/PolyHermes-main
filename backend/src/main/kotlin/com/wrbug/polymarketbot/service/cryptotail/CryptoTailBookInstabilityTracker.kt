package com.wrbug.polymarketbot.service.cryptotail

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Duration
import java.util.ArrayDeque

/**
 * 盘口不稳定追踪器（V96 终场闪针防御 Phase 1 之 C + Phase 2 特征 F1）。
 *
 * 背景（decision-log 20260611 复盘）：终场闪针前盘口常有"前兆余震"——ask 整层闪跳（17:40 案例
 * 入场评估时 ask=0.26 又瞬间恢复）、价差爆宽、ask 整侧消失。刚闪过针的盘口短时间内更可能再闪。
 *
 * 职责（纯内存，零持久化、零 REST）：
 *  1. [observe]：由 [CryptoTailOrderbookWsService.onBestBid] 在每条盘口消息上喂入快照，
 *     与上一观察对比分类异常（ASK_JUMP / ASK_VANISH / SPREAD_WIDE），按 tokenId 记录事件环。
 *  2. [recentAnomaly]：查询某 token 最近 lookback 秒内是否有"达阈值"的异常，供：
 *     - C 入场闸：scalpBookInstabilityCooldownSec 冷却窗内拒绝新进场（SCALP_BOOK_UNSTABLE）；
 *     - F1 对冲特征：hedgeFeatureInstabilityLookbackSec 回看窗内命中则 +1 风险评分。
 *
 * 记录床与阈值解耦：事件按"全局记录地板"（RECORD_FLOOR）入环并携带 magnitude，
 * 查询时再按各策略自己的阈值过滤——同一 token 可被多个不同阈值的策略共享，无需各存一份。
 *
 * 线程模型：observe 由 WS 解析线程串行调用（同一 token 的消息有序），事件环以自身为锁做防御性同步；
 * 查询来自入场/对冲协程，读多写少，开销可忽略。
 */
@Service
class CryptoTailBookInstabilityTracker {

    enum class AnomalyType { ASK_JUMP, ASK_VANISH, SPREAD_WIDE }

    data class AnomalyEvent(
        val type: AnomalyType,
        /** 异常量级：ASK_JUMP=|Δask|；SPREAD_WIDE=spread；ASK_VANISH=消失前的 ask（仅诊断用，命中不看量级） */
        val magnitude: BigDecimal,
        val atMs: Long,
        /** 诊断明细（落决策日志 payload 用） */
        val detail: String
    )

    private class TokenState {
        var lastAsk: BigDecimal? = null
        var lastAskAtMs: Long = 0L
        val events = ArrayDeque<AnomalyEvent>()
    }

    /** tokenId → 观察态。10 分钟过期（周期切换后 token 不再被订阅，自动清理） */
    private val stateCache: Cache<String, TokenState> = Caffeine.newBuilder()
        .maximumSize(512)
        .expireAfterAccess(Duration.ofMinutes(10))
        .build()

    /**
     * 喂入一条盘口快照（每 WS 消息一次）。与上一观察对比分类异常：
     *  - ASK_JUMP：两次观察间隔 <= [MAX_OBS_GAP_MS] 且 |Δask| >= [RECORD_FLOOR]；
     *  - ASK_VANISH：上次有 ask、本次 ask 消失而 bid 仍在（整侧蒸发）；
     *  - SPREAD_WIDE：spread >= [RECORD_FLOOR]（盘口爆宽本身即异常，无需对比）。
     */
    fun observe(orderbook: OrderbookQualitySnapshot, nowMs: Long = System.currentTimeMillis()) {
        val state = stateCache.get(orderbook.tokenId) { TokenState() }
        val ask = orderbook.bestAsk
        synchronized(state) {
            val lastAsk = state.lastAsk
            // obsGapMs=0 也是有效对比：闪针时连续两条 WS 消息可落在同一毫秒，排除 0 会漏掉最快的跳变。
            // 首次观察 lastAskAtMs=0 → obsGapMs=nowMs(巨大) 仍被 MAX_OBS_GAP_MS 上界自然排除。
            val obsGapMs = nowMs - state.lastAskAtMs
            if (ask != null) {
                if (lastAsk != null && obsGapMs in 0..MAX_OBS_GAP_MS) {
                    val jump = ask.subtract(lastAsk).abs()
                    if (jump >= RECORD_FLOOR) {
                        pushEvent(state, AnomalyEvent(
                            AnomalyType.ASK_JUMP, jump, nowMs,
                            "ask ${lastAsk.toPlainString()}→${ask.toPlainString()} (Δ=${jump.toPlainString()}, gapMs=$obsGapMs)"
                        ))
                    }
                }
                state.lastAsk = ask
                state.lastAskAtMs = nowMs
            } else if (lastAsk != null && obsGapMs in 0..MAX_OBS_GAP_MS) {
                // ask 整侧消失而 bid 仍在：闪针中对手盘蒸发的直接形态
                pushEvent(state, AnomalyEvent(
                    AnomalyType.ASK_VANISH, lastAsk, nowMs,
                    "ask 消失(此前=${lastAsk.toPlainString()}) bid=${orderbook.bestBid.toPlainString()}"
                ))
                state.lastAsk = null
                state.lastAskAtMs = nowMs
            }
            val spread = orderbook.spread
            if (spread != null && spread >= RECORD_FLOOR) {
                // 同一爆宽态每 tick 都满足，以 SPREAD_DEDUP_MS 防抖避免事件环被同一异常刷满
                val lastSpreadEvent = state.events.lastOrNull { it.type == AnomalyType.SPREAD_WIDE }
                if (lastSpreadEvent == null || nowMs - lastSpreadEvent.atMs >= SPREAD_DEDUP_MS) {
                    pushEvent(state, AnomalyEvent(
                        AnomalyType.SPREAD_WIDE, spread, nowMs,
                        "spread=${spread.toPlainString()} (bid=${orderbook.bestBid.toPlainString()} ask=${ask?.toPlainString() ?: "-"})"
                    ))
                }
            }
        }
    }

    /**
     * 查询某 token 最近 [lookbackSec] 秒内最近一次"达阈值"的异常；无则 null。
     * 命中口径：ASK_VANISH 恒命中（整侧蒸发无量级可言）；ASK_JUMP / SPREAD_WIDE 须 magnitude >= [minMagnitude]。
     * minMagnitude <= 0 时量级维度不过滤（仅 ASK_VANISH 与记录地板生效）。
     */
    fun recentAnomaly(tokenId: String, lookbackSec: Int, minMagnitude: BigDecimal, nowMs: Long = System.currentTimeMillis()): AnomalyEvent? {
        if (lookbackSec <= 0) return null
        val state = stateCache.getIfPresent(tokenId) ?: return null
        val cutoff = nowMs - lookbackSec * 1000L
        synchronized(state) {
            return state.events.lastOrNull { e ->
                e.atMs >= cutoff && (
                    e.type == AnomalyType.ASK_VANISH ||
                        minMagnitude <= BigDecimal.ZERO || e.magnitude >= minMagnitude
                    )
            }
        }
    }

    private fun pushEvent(state: TokenState, event: AnomalyEvent) {
        state.events.addLast(event)
        while (state.events.size > MAX_EVENTS_PER_TOKEN) state.events.removeFirst()
    }

    companion object {
        /** 全局记录地板：|Δask|/spread 低于此值不入环（限噪声、限内存）；策略阈值在查询侧过滤，不得配低于此值 */
        val RECORD_FLOOR: BigDecimal = BigDecimal("0.10")

        /** 相邻两次观察的最大有效间隔：超过视为基线断裂（断流/重订阅），只重置基线不判跳变 */
        const val MAX_OBS_GAP_MS: Long = 10_000L

        /** SPREAD_WIDE 同态防抖：爆宽持续期间每 tick 都满足条件，按此间隔去重记录 */
        const val SPREAD_DEDUP_MS: Long = 3_000L

        /** 每 token 事件环上限（一个周期内的异常远少于此；防极端行情刷爆） */
        const val MAX_EVENTS_PER_TOKEN: Int = 64
    }
}
