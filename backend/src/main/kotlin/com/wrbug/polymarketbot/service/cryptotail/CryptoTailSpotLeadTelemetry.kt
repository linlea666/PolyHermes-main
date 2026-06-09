package com.wrbug.polymarketbot.service.cryptotail

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * 现货领先早警遥测（回测用）：按 (strategyId, periodStartUnix, outcomeIndex) 记录本周期内
 * **币安首次穿价 ts** 与 **Chainlink 首次穿价 ts**，据此算 `leadMs = clCrossTs - binCrossTs`
 * （正=币安领先 Chainlink），并标记早警动作是否真正触发并改变了决策。
 *
 * 仅用于决策可解释性与后续回测分析（落 decision-log），不参与任何控制路径。
 * 用 Caffeine 有界缓存按 key 过期自动清理（周期级生命周期，免手动维护），内存上界可控。
 */
@Service
class CryptoTailSpotLeadTelemetry {

    /**
     * @param binFirstCrossTs 币安现货首次穿价（站到持仓错误一侧）的本地时间戳ms；未发生为 null
     * @param clFirstCrossTs Chainlink 首次穿价的本地时间戳ms；未发生为 null
     * @param leadMs clFirstCrossTs - binFirstCrossTs（正=币安领先）；任一缺失为 null
     * @param earlyWarningActed 本周期早警是否真正触发并改变了决策（否决/早警减仓/即时砍）
     */
    data class CrossSnapshot(
        val binFirstCrossTs: Long? = null,
        val clFirstCrossTs: Long? = null,
        val leadMs: Long? = null,
        val earlyWarningActed: Boolean = false
    )

    private class Record {
        @Volatile var binFirstCrossTs: Long? = null
        @Volatile var clFirstCrossTs: Long? = null
        @Volatile var earlyWarningActed: Boolean = false
    }

    private val cache: Cache<String, Record> = Caffeine.newBuilder()
        .maximumSize(2000)
        .expireAfterWrite(Duration.ofMinutes(30))
        .build()

    private fun key(strategyId: Long, periodStartUnix: Long, outcomeIndex: Int): String =
        "$strategyId-$periodStartUnix-$outcomeIndex"

    /**
     * 观测一次穿价状态；首次穿价时间戳一旦记录不再覆盖（取首穿）。
     * @param binCrossed 币安现货是否已穿价（须现货新鲜）
     * @param clCrossed Chainlink 是否已穿价
     */
    fun observe(
        strategyId: Long,
        periodStartUnix: Long,
        outcomeIndex: Int,
        nowMs: Long,
        binCrossed: Boolean,
        clCrossed: Boolean
    ) {
        if (!binCrossed && !clCrossed) return
        val rec = cache.get(key(strategyId, periodStartUnix, outcomeIndex)) { Record() }
        if (binCrossed && rec.binFirstCrossTs == null) rec.binFirstCrossTs = nowMs
        if (clCrossed && rec.clFirstCrossTs == null) rec.clFirstCrossTs = nowMs
    }

    /** 标记本周期早警动作已真正触发并改变决策（sticky）。 */
    fun markEarlyWarningActed(strategyId: Long, periodStartUnix: Long, outcomeIndex: Int) {
        val rec = cache.get(key(strategyId, periodStartUnix, outcomeIndex)) { Record() }
        rec.earlyWarningActed = true
    }

    fun snapshot(strategyId: Long, periodStartUnix: Long, outcomeIndex: Int): CrossSnapshot {
        val rec = cache.getIfPresent(key(strategyId, periodStartUnix, outcomeIndex)) ?: return CrossSnapshot()
        val bin = rec.binFirstCrossTs
        val cl = rec.clFirstCrossTs
        val lead = if (bin != null && cl != null) cl - bin else null
        return CrossSnapshot(bin, cl, lead, rec.earlyWarningActed)
    }
}
