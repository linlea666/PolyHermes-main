package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Strong Gap Boost（强价差放量）评估器。
 *
 * 设计原则（严格遵守）：
 *  - 只在普通主闸（方向/EV/价源/盘口/高价保护…）全部通过、且金额已定后运行。
 *  - **只放大下注金额（amount/stake），不改方向、不放宽任何风控、不改 finalLimitPrice。**
 *  - 放大后仍受余额、单笔上限、同周期敞口上限、倍数总上限共同约束（绝不超额）。
 *  - 默认 shadow：仅产出 BOOST_* 决策日志，不真正放大实盘仓位；观察满意后再关 shadow 开实盘。
 *
 * 放量档位以模型已算出的归一化置信度（pWin / safeRatio）判定，天然币种无关，复用现有信号，不重复造轮子。
 */
@Service
class CryptoTailBoostService {

    private val logger = LoggerFactory.getLogger(CryptoTailBoostService::class.java)

    enum class Tier { NONE, STRONG, ULTRA }

    data class BoostDecision(
        val tier: Tier,
        val multiplier: BigDecimal,
        val baseAmount: BigDecimal,
        /** 实际应使用的下注金额：applied=true 时为放大后金额，否则等于 baseAmount */
        val effectiveAmount: BigDecimal,
        /** 命中档位后、受 caps 约束后的"理论放大金额"（shadow 下用于展示本应放大到多少） */
        val wouldBoostAmount: BigDecimal,
        /** 是否真正改变了实盘金额（仅 enable && !shadow && 放大后 > base 时为 true） */
        val applied: Boolean,
        val shadow: Boolean,
        /** 决策事件类型：BOOST_APPLIED / BOOST_ELIGIBLE_SHADOW / BOOST_NOT_ELIGIBLE / BOOST_REJECTED_KELLY / BOOST_CAPPED */
        val eventType: String,
        val reason: String,
        val payload: Map<String, Any>
    )

    /**
     * @param pWin 模型胜率（0~1）
     * @param safeRatio 安全比
     * @param baseAmount 主流程定好的基础下注金额（USDC）
     * @param spendable 当前可用余额（已扣 pending/recentFill 预留）
     * @param usedKelly 本单是否走了 Kelly 动态仓位
     */
    fun evaluate(
        strategy: CryptoTailStrategy,
        pWin: BigDecimal,
        safeRatio: BigDecimal,
        baseAmount: BigDecimal,
        spendable: BigDecimal,
        usedKelly: Boolean
    ): BoostDecision {
        val shadow = strategy.strongGapBoostShadow

        // Kelly 已含仓位管理，默认不叠加放量
        if (usedKelly && !strategy.allowBoostWithKelly) {
            return noBoost(
                eventType = "BOOST_REJECTED_KELLY",
                reason = "Kelly 动态仓位已启用且未允许叠加放量(allowBoostWithKelly=false)",
                tier = Tier.NONE, baseAmount = baseAmount, shadow = shadow, pWin = pWin, safeRatio = safeRatio
            )
        }

        val tier = computeTier(strategy, pWin, safeRatio)
        if (tier == Tier.NONE) {
            return noBoost(
                eventType = "BOOST_NOT_ELIGIBLE",
                reason = "未达放量门槛: pWin=${pWin.toPlainString()} safeRatio=${safeRatio.toPlainString()}",
                tier = tier, baseAmount = baseAmount, shadow = shadow, pWin = pWin, safeRatio = safeRatio
            )
        }

        val rawMultiplier = when (tier) {
            Tier.ULTRA -> strategy.ultraGapStakeMultiplier
            Tier.STRONG -> strategy.strongGapStakeMultiplier
            Tier.NONE -> BigDecimal.ONE
        }
        // 倍数受总上限约束，且不低于 1.0（放量只增不减）
        val multiplier = rawMultiplier.min(strategy.maxStrongGapStakeMultiplier).max(BigDecimal.ONE)

        val boostedRaw = baseAmount.multiply(multiplier).setScale(2, RoundingMode.DOWN)
        // caps：单笔上限、同周期敞口上限、可用余额，取最小后不低于 base
        var capped = boostedRaw
        strategy.maxBoostedAmountUsdc?.let { capped = capped.min(it) }
        strategy.maxBoostedPeriodExposureUsdc?.let { capped = capped.min(it) }
        capped = capped.min(spendable).max(baseAmount)

        val basePayload = boostPayload(strategy, tier, multiplier, baseAmount, boostedRaw, capped, pWin, safeRatio, shadow)

        return when {
            shadow -> BoostDecision(
                tier = tier, multiplier = multiplier, baseAmount = baseAmount,
                effectiveAmount = baseAmount, wouldBoostAmount = capped, applied = false, shadow = true,
                eventType = "BOOST_ELIGIBLE_SHADOW",
                reason = "命中${tier.name}档(shadow，未真正放大): base=${baseAmount.toPlainString()} → would=${capped.toPlainString()} ×${multiplier.toPlainString()}",
                payload = basePayload
            )
            capped > baseAmount -> BoostDecision(
                tier = tier, multiplier = multiplier, baseAmount = baseAmount,
                effectiveAmount = capped, wouldBoostAmount = capped, applied = true, shadow = false,
                eventType = "BOOST_APPLIED",
                reason = "命中${tier.name}档，放大: base=${baseAmount.toPlainString()} → ${capped.toPlainString()} ×${multiplier.toPlainString()}",
                payload = basePayload
            )
            else -> BoostDecision(
                tier = tier, multiplier = multiplier, baseAmount = baseAmount,
                effectiveAmount = baseAmount, wouldBoostAmount = capped, applied = false, shadow = false,
                eventType = "BOOST_CAPPED",
                reason = "命中${tier.name}档但被 caps/余额钳制至 base，不放大: base=${baseAmount.toPlainString()}",
                payload = basePayload
            )
        }
    }

    private fun computeTier(strategy: CryptoTailStrategy, pWin: BigDecimal, safeRatio: BigDecimal): Tier = when {
        pWin >= strategy.ultraGapMinPwin && safeRatio >= strategy.ultraGapMinSafeRatio -> Tier.ULTRA
        pWin >= strategy.strongGapMinPwin && safeRatio >= strategy.strongGapMinSafeRatio -> Tier.STRONG
        else -> Tier.NONE
    }

    private fun noBoost(
        eventType: String, reason: String, tier: Tier,
        baseAmount: BigDecimal, shadow: Boolean, pWin: BigDecimal, safeRatio: BigDecimal
    ): BoostDecision = BoostDecision(
        tier = tier, multiplier = BigDecimal.ONE, baseAmount = baseAmount,
        effectiveAmount = baseAmount, wouldBoostAmount = baseAmount, applied = false, shadow = shadow,
        eventType = eventType, reason = reason,
        payload = mapOf(
            "tier" to tier.name,
            "pWin" to pWin.toPlainString(),
            "safeRatio" to safeRatio.toPlainString(),
            "baseAmount" to baseAmount.toPlainString(),
            "shadow" to shadow
        )
    )

    private fun boostPayload(
        strategy: CryptoTailStrategy, tier: Tier, multiplier: BigDecimal,
        baseAmount: BigDecimal, boostedRaw: BigDecimal, capped: BigDecimal,
        pWin: BigDecimal, safeRatio: BigDecimal, shadow: Boolean
    ): Map<String, Any> = mapOf(
        "tier" to tier.name,
        "multiplier" to multiplier.toPlainString(),
        "pWin" to pWin.toPlainString(),
        "safeRatio" to safeRatio.toPlainString(),
        "baseAmount" to baseAmount.toPlainString(),
        "boostedRaw" to boostedRaw.toPlainString(),
        "boostedCapped" to capped.toPlainString(),
        "shadow" to shadow,
        "strongGapMinPwin" to strategy.strongGapMinPwin.toPlainString(),
        "strongGapMinSafeRatio" to strategy.strongGapMinSafeRatio.toPlainString(),
        "ultraGapMinPwin" to strategy.ultraGapMinPwin.toPlainString(),
        "ultraGapMinSafeRatio" to strategy.ultraGapMinSafeRatio.toPlainString(),
        "maxStrongGapStakeMultiplier" to strategy.maxStrongGapStakeMultiplier.toPlainString(),
        "maxBoostedAmountUsdc" to (strategy.maxBoostedAmountUsdc?.toPlainString() ?: ""),
        "maxBoostedPeriodExposureUsdc" to (strategy.maxBoostedPeriodExposureUsdc?.toPlainString() ?: "")
    )
}
