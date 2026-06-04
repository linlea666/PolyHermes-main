package com.wrbug.polymarketbot.service.cryptotail.taildiff

import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 尾盘价差模式分层下注策略：
 *   amount = baseAmount × tierMultiplier，clamp 到 [MIN_ORDER_USDC, maxAmountPerOrder, spendableBalance]
 *
 * 规则：
 *  - 仅按评分分层决定倍率，不做 Kelly，不做 strongGapBoost（独立模式，行为简单可解释）
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
        /** 钳制原因（可读字符串，用于决策日志） */
        val clampReason: String? = null
    )

    fun computeAmount(
        strategy: CryptoTailStrategy,
        tier: TailDiffTier,
        spendableBalance: BigDecimal
    ): Result {
        val baseAmount = strategy.tailDiffBaseAmount.max(MIN_ORDER_USDC)
        val multiplier = when (tier) {
            TailDiffTier.NORMAL -> strategy.tailDiffTierNormalMult
            TailDiffTier.PREMIUM -> strategy.tailDiffTierPremiumMult
            TailDiffTier.TOP -> strategy.tailDiffTierTopMult
        }.max(BigDecimal.ZERO)
        val raw = baseAmount.multiply(multiplier).setScale(8, RoundingMode.DOWN)
        val maxCap = strategy.tailDiffMaxAmountPerOrder.max(MIN_ORDER_USDC)
        var capped = raw
        var cappedByMaxAmount = false
        var cappedByBalance = false
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
            if (cappedByMaxAmount) append("MAX_AMOUNT")
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
            clampReason = clampReason
        )
    }
}
