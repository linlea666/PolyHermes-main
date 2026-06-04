package com.wrbug.polymarketbot.service.cryptotail.taildiff

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.math.BigDecimal

/**
 * 历史反转概率查询接口（P3 接入真实实现；P1 默认 [NoopTailReversalStatsLookup] 不可用，
 * ScoreEngine 在 HYBRID 模式自动回退 BarrierProbability.pWin）。
 *
 * 设计目标：
 *  - 让 [CryptoTailScoreEngine] 在不依赖统计插件的前提下可独立编译/运行；
 *  - P3 切换到真实实现时只需修改 Spring 注入，不动 ScoreEngine 一行；
 *  - 三档来源（STATS/FALLBACK/HYBRID）切换在 ScoreEngine 内部完成，本接口只负责"给得出/给不出 modelProb"。
 */
interface TailReversalStatsLookup {

    /** 单次查询参数 */
    data class Query(
        /** 币种 BTC/ETH（由调用方按 slug 解析） */
        val coin: String?,
        /** 周期 300/900 */
        val intervalSeconds: Int,
        /** 0=Up 1=Down */
        val outcomeIndex: Int,
        /** 价差 σ 倍数（按计划书"§二"分桶） */
        val diffSigma: BigDecimal,
        /** 赔率分桶名："0.85_0.89"/"0.90_0.92"/"0.93_0.95" 等 */
        val oddsBucket: String,
        /** 剩余秒数分桶名："60_120"/"30_60" 等 */
        val remainingBucket: String,
        /** 180 或 365 */
        val lookbackDays: Int
    )

    /** 单次查询结果 */
    data class Result(
        /** 历史反转概率（0~1）；不可用时为 null */
        val modelProb: BigDecimal?,
        /** 样本数；不可用时 0 */
        val sampleCount: Int,
        /** 数据来源标签：STATS / FALLBACK / HYBRID_STATS / HYBRID_FALLBACK */
        val source: String,
        /** 不可用原因（用于 SKIP 日志） */
        val reason: String? = null
    )

    /**
     * 根据当前快照查询"目标价方向继续维持到结算"的历史概率。
     * 不可用时返回 [Result] 携带 sampleCount=0 + modelProb=null，由调用方按 modelProbSource 策略回退。
     */
    fun queryReversalProb(query: Query): Result
}

/**
 * P1 默认实现：返回 source=FALLBACK 不可用，ScoreEngine 在 HYBRID 模式自动回退 BarrierProbability.pWin。
 * P3 引入真实实现后注入替换。
 */
class NoopTailReversalStatsLookup : TailReversalStatsLookup {
    override fun queryReversalProb(query: TailReversalStatsLookup.Query): TailReversalStatsLookup.Result =
        TailReversalStatsLookup.Result(
            modelProb = null,
            sampleCount = 0,
            source = "FALLBACK",
            reason = "REVERSAL_STATS_PLUGIN_NOT_LOADED"
        )
}

/**
 * Bean 配置：默认装配 Noop 实现；当 P3 引入 [com.wrbug.polymarketbot.service.cryptotail.reversal.RealTailReversalStatsLookup]
 * 时使用 [Lazy] + ConditionalOnMissingBean 自动让步给真实实现。
 */
@Configuration
class TailReversalStatsLookupConfig {
    @Bean
    @ConditionalOnMissingBean(TailReversalStatsLookup::class)
    fun noopTailReversalStatsLookup(): TailReversalStatsLookup = NoopTailReversalStatsLookup()
}
