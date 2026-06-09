package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import com.wrbug.polymarketbot.entity.CryptoTailStrategyTrigger
import com.wrbug.polymarketbot.service.binance.BinanceKlineService
import com.wrbug.polymarketbot.service.binance.BinanceSpotTickerService
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
 * 复用决策：
 *  - 复用 [BinanceKlineService.getCurrentOpenClose] 取同周期 (open=strike, close) 及 [BinanceKlineService.getLastUpdateMs] 的 age；
 *  - 复用 [BinanceSpotTickerService] 的实时 mid + age 作为更新鲜的 current 候选；
 *  - 复用 [CryptoTailHoldToSettlePolicy.gapSupportsHolding] 判方向（与 Chainlink gap 同号口径）；
 *  - 复用 [BarrierProbability.winProbTerminal] 的 safeRatio(=z=|gap|/(σ√t)) 作为"距翻转 σ 数"，避免重写统计。
 */
@Service
class CryptoTailSpotLeadService(
    private val binanceKlineService: BinanceKlineService,
    private val binanceSpotTickerService: BinanceSpotTickerService,
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
     */
    data class SpotLeadState(
        val fresh: Boolean,
        val spotGap: BigDecimal,
        val supportsHolding: Boolean,
        val distanceToFlipSigma: BigDecimal?,
        val ageMs: Long?,
        val source: SpotPriceSource? = null
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
    ): SpotLeadState? {
        // 第一期仅实现 BINANCE 价源；OKX/CONSENSUS 为二期预留，未实现时返回 null（fail-safe，不返回假数据）。
        if (strategy.scalpSpotLeadSource.uppercase() != "BINANCE") return null

        // strike(周期开盘价) 固定取币安 K 线 open；无 K 线则无法构造同场基准 → fail-safe 返回 null。
        val oc = binanceKlineService.getCurrentOpenClose(
            strategy.marketSlugPrefix, strategy.intervalSeconds, trigger.periodStartUnix
        ) ?: return null
        val open = oc.first
        val klineClose = oc.second

        val now = System.currentTimeMillis()

        // current 候选 1：K 线 running close（age 来自该周期 kline 最近推送时间）
        val klineLastMs = binanceKlineService.getLastUpdateMs(
            strategy.marketSlugPrefix, strategy.intervalSeconds, trigger.periodStartUnix
        )
        val klineAge = klineLastMs?.let { now - it }

        // current 候选 2：实时 tick mid（与周期无关，age 来自最近 bookTicker 推送时间）
        val tickMid = binanceSpotTickerService.getLatestMid(strategy.marketSlugPrefix)
        val tickLastMs = binanceSpotTickerService.getLastUpdateMs(strategy.marketSlugPrefix)
        val tickAge = tickLastMs?.let { now - it }

        // 取 age 更小者作为 current；两者皆缺 → fail-safe（current/age 为 null，fresh=false）。
        val tickCandidate = if (tickMid != null && tickAge != null)
            Triple(SpotPriceSource.TICK, tickMid, tickAge) else null
        val klineCandidate = if (klineAge != null)
            Triple(SpotPriceSource.KLINE, klineClose, klineAge) else null
        val chosen = listOfNotNull(tickCandidate, klineCandidate).minByOrNull { it.third }

        val source = chosen?.first
        val current = chosen?.second
        val ageMs = chosen?.third

        val maxAge = strategy.scalpSpotLeadMaxAgeMs
        val fresh = when {
            ageMs == null -> false
            maxAge <= 0 -> true
            else -> ageMs <= maxAge
        }

        // binGap = current - strike；无 current 时退化为 K 线 close - open（与旧行为一致，但此时 fresh=false 不会触发动作）。
        val binGap = (current ?: klineClose).subtract(open)
        val supports = CryptoTailHoldToSettlePolicy.gapSupportsHolding(trigger.outcomeIndex, binGap)

        // 距翻转 σ 数：复用 BarrierProbability.safeRatio(=z=|gap|/(σ√剩余))。σ 与结算同源价一致（与价格水平解耦，仅取波动率量级）。
        // 仅在需要时（remaining>0）计算；σ 不可用时为 null，danger() 在 flipDistanceSigma>0 路径下会安全跳过。
        val distance: BigDecimal? = if (remainingSeconds > 0) {
            val sigma = periodPriceProvider.getSigmaPerSqrtS(
                strategy.marketSlugPrefix, strategy.intervalSeconds, trigger.periodStartUnix,
                trigger.outcomeIndex, strategy.sigmaScale, strategy.sigmaMethod, strategy.ewmaLambda
            )
            if (sigma != null) {
                BarrierProbability.winProbTerminal(binGap, sigma, remainingSeconds.toDouble())?.safeRatio
            } else null
        } else null

        return SpotLeadState(
            fresh = fresh,
            spotGap = binGap,
            supportsHolding = supports,
            distanceToFlipSigma = distance,
            ageMs = ageMs,
            source = source
        )
    }
}
