package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import com.wrbug.polymarketbot.entity.CryptoTailStrategyTrigger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal

/**
 * SCALP_FLIP 现货价"领先早警"信号层。
 *
 * 根因/动机：SCALP 的 gap/pWin/safeRatio/modelSide 全部来自 Chainlink（Polymarket 结算同源价，默认 RTDS WS 转发）。
 * Chainlink 聚合多源、有更新节奏/聚合延迟；币安/欧意现货是流动性最大的现货场，价格变化通常领先聚合价源数百毫秒~数秒。
 * 本服务把"币安同周期位移 = 现价 - 周期开盘价"接入为 Chainlink 走向的领先早警信号，用于在 Chainlink gap 翻转、
 * Polymarket bid 崩塌、盘口抽干（V92 文档 B 类终局塌缩）之前提前感知穿价风险。
 *
 * 现价来源（升级）：
 *  - strike(周期开盘价) 取币安 K 线 open（稳定，不需快）；
 *  - current(现价) 取 **实时 tick(@bookTicker mid) 与 K 线 running close 中 age 更小者**，亚秒级感知尾盘急动。
 *  - binGap = current - strike（同场自洽，与 Chainlink/币安基差抵消）。
 *
 * 强约束（设计哲学）：
 *  - 只做"领先早警层"，绝不进入 pWin/结算口径（与结算同源价物理隔离，沿用"绝不回退币安"原则）。
 *  - 仅用于：(1) 让出场更早/更快；(2) 给 WICK_GUARD 加否决。绝不放松/延长持有。
 *  - fail-safe：现货数据缺失/不新鲜一律返回 null 或 fresh=false，调用方据此回退旧行为，绝不用过期现货价误触发。
 *
 * 价源升级(v2)：现价来源不再硬锁币安，改由 [SpotLeadPriceProviderRouter] 按策略 source 路由：
 *  - BINANCE / OKX：单源（各自 K 线 open + 实时 tick）；
 *  - CONSENSUS：币安与 OKX 双源融合——两源均新鲜且方向一致才判危险（require both agree，抑制单源假信号），
 *    仅单源新鲜时退化为该源（fail-safe，不静默回退到"无信号"）。
 *
 * 复用决策：
 *  - 复用 [SpotLeadPriceProvider.getSpotSnapshot] 取同周期 (open=strike, current, age, kind)，各源同场自洽；
 *  - 复用 [CryptoTailHoldToSettlePolicy.gapSupportsHolding] 判方向（与 Chainlink gap 同号口径）；
 *  - 复用 [BarrierProbability.winProbTerminal] 的 safeRatio(=z=|gap|/(σ√t)) 作为"距翻转 σ 数"，避免重写统计；
 *  - σ 取自结算同源价 [PeriodPriceProvider]（与价格水平解耦，仅取波动率量级），与现价来源无关。
 */
@Service
class CryptoTailSpotLeadService(
    private val spotLeadRouter: SpotLeadPriceProviderRouter,
    private val periodPriceProvider: PeriodPriceProvider
) {

    private val logger = LoggerFactory.getLogger(CryptoTailSpotLeadService::class.java)

    /** 现价来源：实时 tick(@bookTicker) 或 K 线 running close。 */
    enum class SpotPriceSource { TICK, KLINE }

    /**
     * 现货领先信号快照。
     *
     * @param fresh 现货数据是否新鲜（有数据且 age <= scalpSpotLeadMaxAgeMs；不新鲜=false → 调用方回退旧行为）
     * @param spotGap 现货同周期位移 binGap = current - strike(开盘价)（同场自洽，基差抵消）
     * @param supportsHolding 现货 gap 是否仍支持持仓方向（UP: gap>0；DOWN: gap<0）
     * @param distanceToFlipSigma 现货距翻转的 σ 数 = |binGap|/(σ√剩余)；σ 不可用时为 null
     * @param ageMs 选用现价的 age（ms）；无数据时为 null
     * @param source 选用现价的来源（TICK/KLINE）；无数据时为 null
     * @param exchange 现价交易所归属（BINANCE、OKX、CONSENSUS、CONSENSUS_DEGRADED_BINANCE/OKX、CONSENSUS_STALE）；仅用于审计/日志
     */
    data class SpotLeadState(
        val fresh: Boolean,
        val spotGap: BigDecimal,
        val supportsHolding: Boolean,
        val distanceToFlipSigma: BigDecimal?,
        val ageMs: Long?,
        val source: SpotPriceSource? = null,
        val exchange: String? = null
    ) {
        /** 现货是否已实际穿价（站到持仓方向的错误一侧） */
        val crossed: Boolean get() = !supportsHolding

        /**
         * 现货是否判定为"危险"（用于 WICK_GUARD 否决 / 早警减仓）：
         *  - 必须新鲜（fresh=true，否则不可信）；
         *  - 已实际穿价（crossed）→ 危险；
         *  - 或 flipDistanceSigma>0 且距翻转 <= 阈值 → 近翻转预警（flipDistanceSigma=0 时不启用近翻转，仅认实际穿价）。
         */
        fun danger(flipDistanceSigma: BigDecimal): Boolean {
            if (!fresh) return false
            if (crossed) return true
            val d = distanceToFlipSigma
            return flipDistanceSigma > BigDecimal.ZERO && d != null && d <= flipDistanceSigma
        }
    }

    /**
     * 计算现货领先信号。仅在 SCALP_FLIP + scalpSpotLeadEnabled 时由调用方触发。
     * 任何缺失（价源未实现/无数据/σ 不可用）均以 fail-safe 方式降级，不抛异常、不返回假数据。
     *
     * @return SpotLeadState；当价源不可用（如 OKX/CONSENSUS 二期未实现或币安无该周期数据）返回 null → 调用方回退旧行为。
     */
    fun evaluate(
        strategy: CryptoTailStrategy,
        trigger: CryptoTailStrategyTrigger,
        remainingSeconds: Int
    ): SpotLeadState? = evaluateCore(strategy, trigger.outcomeIndex, trigger.periodStartUnix, remainingSeconds)

    /**
     * 入场期现货领先评估（集成点 B 入场闸专用）：进场时尚无 trigger，按"买入侧 outcomeIndex + 同周期 periodStartUnix"
     * 直接评估现货是否已穿价/逆向。与 [evaluate] 完全同口径（同源路由/共识融合/fail-safe），仅入参来源不同。
     */
    fun evaluateForEntry(
        strategy: CryptoTailStrategy,
        outcomeIndex: Int,
        periodStartUnix: Long,
        remainingSeconds: Int
    ): SpotLeadState? = evaluateCore(strategy, outcomeIndex, periodStartUnix, remainingSeconds)

    private fun evaluateCore(
        strategy: CryptoTailStrategy,
        outcomeIndex: Int,
        periodStartUnix: Long,
        remainingSeconds: Int
    ): SpotLeadState? {
        // σ 与结算同源价一致（与现价来源无关），整次评估只算一次供各源共用，避免重复采样。
        val sigma: BigDecimal? = if (remainingSeconds > 0) {
            periodPriceProvider.getSigmaPerSqrtS(
                strategy.marketSlugPrefix, strategy.intervalSeconds, periodStartUnix,
                outcomeIndex, strategy.sigmaScale, strategy.sigmaMethod, strategy.ewmaLambda
            )
        } else null

        return when (strategy.scalpSpotLeadSource.uppercase()) {
            "BINANCE" -> spotLeadRouter.provider("BINANCE")
                ?.let { buildState(it, strategy, outcomeIndex, periodStartUnix, remainingSeconds, sigma) }
            "OKX" -> spotLeadRouter.provider("OKX")
                ?.let { buildState(it, strategy, outcomeIndex, periodStartUnix, remainingSeconds, sigma) }
            "CONSENSUS" -> {
                val a = buildState(spotLeadRouter.binance, strategy, outcomeIndex, periodStartUnix, remainingSeconds, sigma)
                val b = buildState(spotLeadRouter.okx, strategy, outcomeIndex, periodStartUnix, remainingSeconds, sigma)
                mergeConsensus(a, b)
            }
            else -> null
        }
    }

    /** 单源构造信号：从某价源取同周期快照并算 binGap/方向/距翻转 σ 数；无快照返回 null（fail-safe）。 */
    private fun buildState(
        provider: SpotLeadPriceProvider,
        strategy: CryptoTailStrategy,
        outcomeIndex: Int,
        periodStartUnix: Long,
        remainingSeconds: Int,
        sigma: BigDecimal?
    ): SpotLeadState? {
        val snap = provider.getSpotSnapshot(
            strategy.marketSlugPrefix, strategy.intervalSeconds, periodStartUnix
        ) ?: return null

        val binGap = snap.current.subtract(snap.open)
        val supports = CryptoTailHoldToSettlePolicy.gapSupportsHolding(outcomeIndex, binGap)
        val maxAge = strategy.scalpSpotLeadMaxAgeMs
        val fresh = if (maxAge <= 0) true else snap.currentAgeMs <= maxAge
        val distance: BigDecimal? = if (remainingSeconds > 0 && sigma != null) {
            BarrierProbability.winProbTerminal(binGap, sigma, remainingSeconds.toDouble())?.safeRatio
        } else null
        val source = try {
            SpotPriceSource.valueOf(snap.currentSourceKind)
        } catch (e: IllegalArgumentException) {
            null
        }
        return SpotLeadState(
            fresh = fresh,
            spotGap = binGap,
            supportsHolding = supports,
            distanceToFlipSigma = distance,
            ageMs = snap.currentAgeMs,
            source = source,
            exchange = provider.source
        )
    }

    /**
     * 双源融合（CONSENSUS）：
     *  - 仅一源有数据 → 退化为该源（不静默丢信号）；
     *  - 两源皆新鲜 → 合成"需双源一致才危险"的状态：
     *      crossed(合) = 两源皆 crossed（supportsHolding = 非"皆穿价"），近翻转距离取两源较大者（两源都近才算近），
     *      age 取两源较大者（更保守的新鲜度）；
     *  - 否则退化为新鲜的那一源；皆不新鲜 → fresh=false（不触发动作）。
     */
    internal fun mergeConsensus(a: SpotLeadState?, b: SpotLeadState?): SpotLeadState? {
        if (a == null && b == null) return null
        if (a == null) return b?.copy(exchange = "CONSENSUS_DEGRADED_OKX")
        if (b == null) return a.copy(exchange = "CONSENSUS_DEGRADED_BINANCE")

        if (a.fresh && b.fresh) {
            val crossedBoth = a.crossed && b.crossed
            val mergedDistance = mergeDistanceRequireBoth(a.distanceToFlipSigma, b.distanceToFlipSigma)
            val mergedAge = maxOf(a.ageMs ?: Long.MAX_VALUE, b.ageMs ?: Long.MAX_VALUE)
            // spotGap 展示取"距翻转更近(较危险)"的一源，便于日志直观；精确双源明细由决策日志分别落库。
            val repr = if ((a.distanceToFlipSigma ?: BigDecimal.valueOf(Long.MAX_VALUE)) <=
                (b.distanceToFlipSigma ?: BigDecimal.valueOf(Long.MAX_VALUE))
            ) a else b
            return SpotLeadState(
                fresh = true,
                spotGap = repr.spotGap,
                supportsHolding = !crossedBoth,
                distanceToFlipSigma = mergedDistance,
                ageMs = mergedAge,
                source = repr.source,
                exchange = "CONSENSUS"
            )
        }
        if (a.fresh) return a.copy(exchange = "CONSENSUS_DEGRADED_BINANCE")
        if (b.fresh) return b.copy(exchange = "CONSENSUS_DEGRADED_OKX")
        return a.copy(fresh = false, exchange = "CONSENSUS_STALE")
    }

    /** 近翻转距离合并：两源都有 σ 距离时取较大者（要求两源都近才判近）；任一缺失则返回 null（不靠单源近翻转触发共识危险）。 */
    private fun mergeDistanceRequireBoth(a: BigDecimal?, b: BigDecimal?): BigDecimal? {
        if (a == null || b == null) return null
        return a.max(b)
    }
}
