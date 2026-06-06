package com.wrbug.polymarketbot.service.cryptotail.taildiff

import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 尾盘价差模式分层下注策略：
 *   amount = baseAmount × tierMultiplier × stakeMultiplier，clamp 到 [MIN_ORDER_USDC, maxAmountPerOrder, spendableBalance]
 *   （V72 可选叠加）再取 min(分数 1/10 Kelly 上限, 盘口可成交深度上限)
 *
 * 规则：
 *  - 仅按评分分层决定倍率，不做 strongGapBoost（独立模式，行为简单可解释）
 *  - Kelly 仅作为「上限」而非「目标」，且强制折扣（默认 1/10），避免裸 Kelly 的过激仓位
 *  - 单市场仅入场一次（由 EntryGuardService 重复持仓检查保证）
 *  - 禁止马丁（不做"亏了下次加大"）
 */
@Component
class CryptoTailTailDiffSizingPolicy {

    private val MIN_ORDER_USDC: BigDecimal = BigDecimal.ONE

    data class Result(
        /** 最终下注金额 USDC */
        val amountUsdc: BigDecimal,
        /** 基础下注金额 */
        val baseAmount: BigDecimal,
        /** 实际生效的倍率（被 hard cap 钳制后） */
        val effectiveMultiplier: BigDecimal,
        /** 倍率是否被 maxAmountPerOrder 钳制 */
        val cappedByMaxAmount: Boolean,
        /** 倍率是否被余额钳制 */
        val cappedByBalance: Boolean,
        /** 是否被分数 Kelly 上限钳制 */
        val cappedByKelly: Boolean = false,
        /** 是否被盘口可成交深度上限钳制 */
        val cappedByDepth: Boolean = false,
        /** 钳制原因（可读字符串，用于决策日志） */
        val clampReason: String? = null
    )

    /**
     * @param modelProb 模型胜率（启用 Kelly 上限时必填）
     * @param effectiveCost 含费有效成本（启用 Kelly 上限时必填）
     * @param availableDepthUsd 盘口可成交深度 min(bidDepth, askDepth)（启用深度上限时必填）
     * @param stakeMultiplier 入场分段的下注倍率（按时间窗精准降仓/加仓，缺省 1.0），叠乘在 base×tier 上，再过统一钳制。
     */
    fun computeAmount(
        strategy: CryptoTailStrategy,
        tier: TailDiffTier,
        spendableBalance: BigDecimal,
        modelProb: BigDecimal? = null,
        effectiveCost: BigDecimal? = null,
        availableDepthUsd: BigDecimal? = null,
        stakeMultiplier: BigDecimal = BigDecimal.ONE
    ): Result {
        val baseAmount = strategy.tailDiffBaseAmount.max(MIN_ORDER_USDC)
        val multiplier = when (tier) {
            TailDiffTier.NORMAL -> strategy.tailDiffTierNormalMult
            TailDiffTier.PREMIUM -> strategy.tailDiffTierPremiumMult
            TailDiffTier.TOP -> strategy.tailDiffTierTopMult
        }.max(BigDecimal.ZERO)
        val raw = baseAmount.multiply(multiplier)
            .multiply(stakeMultiplier.max(BigDecimal.ZERO))
            .setScale(8, RoundingMode.DOWN)
        val maxCap = strategy.tailDiffMaxAmountPerOrder.max(MIN_ORDER_USDC)
        var capped = raw
        var cappedByMaxAmount = false
        var cappedByBalance = false
        var cappedByKelly = false
        var cappedByDepth = false

        // V72-① 分数 Kelly 上限（默认关闭）：kellyFull = (p - cost) / (1 - cost)，取 1/N Kelly × 可支配余额
        if (strategy.tailDiffEnableKellyCap && modelProb != null && effectiveCost != null) {
            val denom = BigDecimal.ONE.subtract(effectiveCost)
            if (denom > BigDecimal.ZERO) {
                val kellyFull = modelProb.subtract(effectiveCost).divide(denom, 8, RoundingMode.HALF_UP)
                if (kellyFull > BigDecimal.ZERO) {
                    val kellyCap = kellyFull
                        .multiply(strategy.tailDiffKellyFraction.max(BigDecimal.ZERO))
                        .multiply(spendableBalance.max(BigDecimal.ZERO))
                        .setScale(8, RoundingMode.DOWN)
                    if (kellyCap < capped) {
                        capped = kellyCap
                        cappedByKelly = true
                    }
                }
            }
        }

        // V72-② 盘口可成交深度上限（depthFillRatio=0 时关闭）：min(bidDepth, askDepth) × ratio
        if (strategy.tailDiffDepthFillRatio > BigDecimal.ZERO && availableDepthUsd != null) {
            val depthCap = availableDepthUsd.max(BigDecimal.ZERO)
                .multiply(strategy.tailDiffDepthFillRatio)
                .setScale(8, RoundingMode.DOWN)
            if (depthCap < capped) {
                capped = depthCap
                cappedByDepth = true
            }
        }

        if (capped > maxCap) {
            capped = maxCap
            cappedByMaxAmount = true
        }
        if (capped > spendableBalance) {
            capped = spendableBalance
            cappedByBalance = true
        }
        // 即使 multiplier=0 也至少给 MIN_ORDER_USDC（被分层挑中说明评分够，金额不能为 0）
        if (capped < MIN_ORDER_USDC) {
            // 余额不足时返回 0，调用方按"金额不足"处理；余额够则补到 MIN
            capped = if (spendableBalance >= MIN_ORDER_USDC) MIN_ORDER_USDC else BigDecimal.ZERO
        }
        val clampReason = buildString {
            if (cappedByKelly) append("KELLY")
            if (cappedByDepth) {
                if (isNotEmpty()) append(",")
                append("DEPTH")
            }
            if (cappedByMaxAmount) {
                if (isNotEmpty()) append(",")
                append("MAX_AMOUNT")
            }
            if (cappedByBalance) {
                if (isNotEmpty()) append(",")
                append("BALANCE")
            }
        }.ifBlank { null }
        val effectiveMultiplier = if (baseAmount > BigDecimal.ZERO) {
            capped.divide(baseAmount, 6, RoundingMode.HALF_UP)
        } else multiplier
        return Result(
            amountUsdc = capped,
            baseAmount = baseAmount,
            effectiveMultiplier = effectiveMultiplier,
            cappedByMaxAmount = cappedByMaxAmount,
            cappedByBalance = cappedByBalance,
            cappedByKelly = cappedByKelly,
            cappedByDepth = cappedByDepth,
            clampReason = clampReason
        )
    }
}
