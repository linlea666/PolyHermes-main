package com.wrbug.polymarketbot.service.cryptotail.taildiff

import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import com.wrbug.polymarketbot.util.fromJson
import com.wrbug.polymarketbot.util.toJson
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * 尾盘价差模式的退出预设（按机会分层独立）。
 *
 * 结构对应计划书"§六 退出预设"：
 *  - 普通档（NORMAL）：固定止盈 + 固定止损 + 动态退出（保守）
 *  - 优质档（PREMIUM）：固定止盈 + 动态退出（中等）
 *  - 顶级档（TOP）：持有到结算（不主动卖）
 *
 * 三档对应三个独立 JSON：tailDiffExitPresetNormal/Premium/TopJson。
 * 入场时由 [TailDiffExitPresetResolver.resolveAndFreeze] 解析、合并默认值、并序列化进 trigger.exitPresetJson，
 * 后续退出评估直接读取该快照，避免策略表中途修改影响在途持仓。
 */
data class TailDiffExitPreset(
    /** 是否持有到结算（true → 完全跳过 TP/SL/动态退出，直接等结算） */
    val holdToExpiry: Boolean = false,
    /** 固定止盈：bestBid >= tpLimit.price 时按 ratio 卖出 */
    val tpLimit: TpLimit = TpLimit(),
    /** 固定止损：bestBid <= entryFillPrice * (1 - offset) 或 bestBid <= minPrice 时止损 */
    val stopLoss: StopLoss = StopLoss(),
    /** 动态条件退出：盘中 modelProb/diffSigma/赔率/反抽速度触发 */
    val dynamicExit: DynamicExit = DynamicExit()
) {
    data class TpLimit(
        val enabled: Boolean = true,
        /** bestBid 阈值（0~1） */
        val price: BigDecimal = BigDecimal("0.98"),
        /** 卖出比例（0~1） */
        val ratio: BigDecimal = BigDecimal.ONE
    )

    data class StopLoss(
        val enabled: Boolean = true,
        /** 相对入场价的最大亏损比例（如 0.20 = 跌 20% 止损） */
        val offset: BigDecimal = BigDecimal("0.20"),
        /** 绝对最低价（与 offset 取 max，越早触发越好） */
        val minPrice: BigDecimal = BigDecimal("0.70"),
        /** 止损时卖出比例（默认 1 全清） */
        val ratio: BigDecimal = BigDecimal.ONE
    )

    data class DynamicExit(
        val enabled: Boolean = true,
        /** 持仓中 diff_sigma 跌破此值（即反向 σ 数）→ 退出 */
        val minDiffSigmaAfterEntry: BigDecimal = BigDecimal("1.3"),
        /** 入场后 diff_sigma 回撤比例（peakDiffSigma 减少多少视为反抽过深） */
        val maxDiffRetracePct: BigDecimal = BigDecimal("0.50"),
        /** 持仓中 modelProb 跌破此值 → 退出 */
        val minModelProbAfterEntry: BigDecimal = BigDecimal("0.88"),
        /** 持仓中 bestBid 跌破此值 → 退出（独立于 stopLoss.minPrice，更宽） */
        val minOddsAfterEntry: BigDecimal = BigDecimal("0.80"),
        /** 反抽速度上限（σ/秒）；超过 → 退出（同入场闸的 PRICE_RETRACING_FAST 阈值，但口径在持仓中） */
        val maxReverseVelocitySigma: BigDecimal = BigDecimal("0.40")
    )

    /** 用于 trigger.exitPresetJson 落库的简洁 Map（保留全部精度） */
    fun toMap(): Map<String, Any> = mapOf(
        "hold_to_expiry" to holdToExpiry,
        "tp_limit" to mapOf(
            "enabled" to tpLimit.enabled,
            "price" to tpLimit.price.toPlainString(),
            "ratio" to tpLimit.ratio.toPlainString()
        ),
        "stop_loss" to mapOf(
            "enabled" to stopLoss.enabled,
            "offset" to stopLoss.offset.toPlainString(),
            "min_price" to stopLoss.minPrice.toPlainString(),
            "ratio" to stopLoss.ratio.toPlainString()
        ),
        "dynamic_exit" to mapOf(
            "enabled" to dynamicExit.enabled,
            "min_diff_sigma_after_entry" to dynamicExit.minDiffSigmaAfterEntry.toPlainString(),
            "max_diff_retrace_pct" to dynamicExit.maxDiffRetracePct.toPlainString(),
            "min_model_prob_after_entry" to dynamicExit.minModelProbAfterEntry.toPlainString(),
            "min_odds_after_entry" to dynamicExit.minOddsAfterEntry.toPlainString(),
            "max_reverse_velocity_sigma" to dynamicExit.maxReverseVelocitySigma.toPlainString()
        )
    )
}

/**
 * 退出预设解析器：将策略表中的 JSON 列解析成强类型对象，并提供三档默认值。
 *
 * 复用决策：解析失败时不 throw，记日志后回退默认；JSON 字段缺失走默认值，保证健壮。
 */
@Component
class TailDiffExitPresetResolver {

    private val logger = LoggerFactory.getLogger(TailDiffExitPresetResolver::class.java)

    /** 按分层解析配置的预设；解析失败回退该档默认 */
    fun resolveForTier(strategy: CryptoTailStrategy, tier: TailDiffTier): TailDiffExitPreset {
        val json = when (tier) {
            TailDiffTier.NORMAL -> strategy.tailDiffExitPresetNormalJson
            TailDiffTier.PREMIUM -> strategy.tailDiffExitPresetPremiumJson
            TailDiffTier.TOP -> strategy.tailDiffExitPresetTopJson
        }
        val default = defaultForTier(tier)
        if (json.isNullOrBlank()) return default
        return try {
            val raw = json.fromJson<Map<String, Any?>>() ?: return default
            parsePreset(raw, default)
        } catch (e: Exception) {
            logger.warn("TAIL_DIFF 退出预设 JSON 解析失败，回退默认: strategyId=${strategy.id}, tier=$tier, ${e.message}")
            default
        }
    }

    /**
     * 入场时解析 + 序列化进 trigger.exitPresetJson 的便捷封装。
     */
    fun resolveAndFreeze(strategy: CryptoTailStrategy, tier: TailDiffTier): Pair<TailDiffExitPreset, String> {
        val preset = resolveForTier(strategy, tier)
        return preset to preset.toMap().toJson()
    }

    @Suppress("UNCHECKED_CAST")
    private fun parsePreset(raw: Map<String, Any?>, default: TailDiffExitPreset): TailDiffExitPreset {
        val tpLimitRaw = raw["tp_limit"] as? Map<String, Any?> ?: emptyMap()
        val stopLossRaw = raw["stop_loss"] as? Map<String, Any?> ?: emptyMap()
        val dynamicRaw = raw["dynamic_exit"] as? Map<String, Any?> ?: emptyMap()
        return TailDiffExitPreset(
            holdToExpiry = raw["hold_to_expiry"].asBool(default.holdToExpiry),
            tpLimit = TailDiffExitPreset.TpLimit(
                enabled = tpLimitRaw["enabled"].asBool(default.tpLimit.enabled),
                price = tpLimitRaw["price"].asBigDecimal(default.tpLimit.price),
                ratio = tpLimitRaw["ratio"].asBigDecimal(default.tpLimit.ratio)
            ),
            stopLoss = TailDiffExitPreset.StopLoss(
                enabled = stopLossRaw["enabled"].asBool(default.stopLoss.enabled),
                offset = stopLossRaw["offset"].asBigDecimal(default.stopLoss.offset),
                minPrice = stopLossRaw["min_price"].asBigDecimal(default.stopLoss.minPrice),
                ratio = stopLossRaw["ratio"].asBigDecimal(default.stopLoss.ratio)
            ),
            dynamicExit = TailDiffExitPreset.DynamicExit(
                enabled = dynamicRaw["enabled"].asBool(default.dynamicExit.enabled),
                minDiffSigmaAfterEntry = dynamicRaw["min_diff_sigma_after_entry"].asBigDecimal(default.dynamicExit.minDiffSigmaAfterEntry),
                maxDiffRetracePct = dynamicRaw["max_diff_retrace_pct"].asBigDecimal(default.dynamicExit.maxDiffRetracePct),
                minModelProbAfterEntry = dynamicRaw["min_model_prob_after_entry"].asBigDecimal(default.dynamicExit.minModelProbAfterEntry),
                minOddsAfterEntry = dynamicRaw["min_odds_after_entry"].asBigDecimal(default.dynamicExit.minOddsAfterEntry),
                maxReverseVelocitySigma = dynamicRaw["max_reverse_velocity_sigma"].asBigDecimal(default.dynamicExit.maxReverseVelocitySigma)
            )
        )
    }

    private fun Any?.asBool(default: Boolean): Boolean = when (this) {
        is Boolean -> this
        is String -> this.equals("true", ignoreCase = true)
        is Number -> this.toInt() != 0
        else -> default
    }

    private fun Any?.asBigDecimal(default: BigDecimal): BigDecimal = when (this) {
        is BigDecimal -> this
        is Number -> BigDecimal(this.toString())
        is String -> if (this.isBlank()) default else this.toSafeBigDecimal()
        else -> default
    }

    fun defaultForTier(tier: TailDiffTier): TailDiffExitPreset = when (tier) {
        TailDiffTier.NORMAL -> TailDiffExitPreset(
            holdToExpiry = false,
            tpLimit = TailDiffExitPreset.TpLimit(enabled = true, price = BigDecimal("0.98"), ratio = BigDecimal.ONE),
            stopLoss = TailDiffExitPreset.StopLoss(enabled = true, offset = BigDecimal("0.20"), minPrice = BigDecimal("0.70"), ratio = BigDecimal.ONE),
            dynamicExit = TailDiffExitPreset.DynamicExit(
                enabled = true,
                minDiffSigmaAfterEntry = BigDecimal("1.3"),
                maxDiffRetracePct = BigDecimal("0.50"),
                minModelProbAfterEntry = BigDecimal("0.88"),
                minOddsAfterEntry = BigDecimal("0.80"),
                maxReverseVelocitySigma = BigDecimal("0.40")
            )
        )
        TailDiffTier.PREMIUM -> TailDiffExitPreset(
            holdToExpiry = false,
            tpLimit = TailDiffExitPreset.TpLimit(enabled = true, price = BigDecimal("0.99"), ratio = BigDecimal.ONE),
            stopLoss = TailDiffExitPreset.StopLoss(enabled = false, offset = BigDecimal("0.30"), minPrice = BigDecimal("0.60"), ratio = BigDecimal.ONE),
            dynamicExit = TailDiffExitPreset.DynamicExit(
                enabled = true,
                minDiffSigmaAfterEntry = BigDecimal("1.0"),
                maxDiffRetracePct = BigDecimal("0.60"),
                minModelProbAfterEntry = BigDecimal("0.85"),
                minOddsAfterEntry = BigDecimal("0.75"),
                maxReverseVelocitySigma = BigDecimal("0.50")
            )
        )
        TailDiffTier.TOP -> TailDiffExitPreset(
            holdToExpiry = true,
            tpLimit = TailDiffExitPreset.TpLimit(enabled = false, price = BigDecimal.ONE, ratio = BigDecimal.ONE),
            stopLoss = TailDiffExitPreset.StopLoss(enabled = false, offset = BigDecimal("0.40"), minPrice = BigDecimal("0.50"), ratio = BigDecimal.ONE),
            dynamicExit = TailDiffExitPreset.DynamicExit(
                enabled = false,
                minDiffSigmaAfterEntry = BigDecimal("0.5"),
                maxDiffRetracePct = BigDecimal("0.80"),
                minModelProbAfterEntry = BigDecimal("0.80"),
                minOddsAfterEntry = BigDecimal("0.70"),
                maxReverseVelocitySigma = BigDecimal("0.70")
            )
        )
    }
}

/** 入场分层标签（评分 → 分层 → 金额倍率 → 退出预设） */
enum class TailDiffTier(val label: String) {
    NORMAL("NORMAL"),
    PREMIUM("PREMIUM"),
    TOP("TOP");

    companion object {
        fun fromScore(score: Int, minEntry: Int, premium: Int, top: Int): TailDiffTier? {
            if (score < minEntry) return null
            if (score >= top) return TOP
            if (score >= premium) return PREMIUM
            return NORMAL
        }

        fun fromLabel(label: String?): TailDiffTier? = when (label?.uppercase()) {
            "NORMAL" -> NORMAL
            "PREMIUM" -> PREMIUM
            "TOP" -> TOP
            else -> null
        }
    }
}
