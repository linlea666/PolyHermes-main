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
    val dynamicExit: DynamicExit = DynamicExit(),
    /** 退出执行参数：FAK 退出滑点（按 TP/STOP 分别覆盖全局 exitFakSlippage）与最差成交价底线 */
    val execution: Execution = Execution()
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
        val maxReverseVelocitySigma: BigDecimal = BigDecimal("0.40"),
        /**
         * 真熔断·灾难绝对线：bestBid 跌破此值（经 exitConfirmTicks 确认）→ 无 worstPrice 地板的真实市价止损。
         * 优先于 minOdds 评估，封住"模型仍自信但价格崩盘"且 minOdds 卖单被地板挡死成交不了的尾部风险。
         * 默认 0 = 关闭（零回归）。应设在 worstPrice 之下，确保只在确认性崩盘时才放弃地板。
         */
        val catastropheBidFloor: BigDecimal = BigDecimal.ZERO,
        /**
         * 真熔断·相对回撤：(entryFillPrice - bestBid)/entryFillPrice >= 此值 → 无地板真实市价止损。
         * 与 catastropheBidFloor 取"任一触发"，覆盖入场价不同导致绝对线不适配的情况。默认 0 = 关闭。
         */
        val maxDrawdownPct: BigDecimal = BigDecimal.ZERO,
        /**
         * 真熔断是否即时放行：true=灾难线/回撤触发时跳过 exitConfirmTicks 确认，立即砍仓（最快止血，牺牲防插针）；
         * false（默认）=与现有 HARD_STOP 一致仍走确认。仅在 catastropheBidFloor/maxDrawdownPct 至少一项启用时有意义。
         *
         * 注意（WS2）：SCALP_FLIP 引入"模型门控"后，catastropheImmediate 仅在"模型已翻转/转弱(真反转)"时生效；
         * 当模型仍强挺（判为盘口插针）时一律改走 exitConfirmTicks 短确认，给反弹机会，不再即时砍仓。
         */
        val catastropheImmediate: Boolean = false,
        /**
         * 真熔断·相对地板比例（WS2，SCALP_FLIP 专用）：>0 时灾难地板 = entryFillPrice × 此比例，
         * 替代绝对线 catastropheBidFloor，使不同入场价下熔断阈值口径一致（避免绝对线在低入场价时形同虚设）。
         * 默认 0 = 关闭（沿用绝对线 catastropheBidFloor，TAIL_DIFF 行为不变）。应设在深底线 scalpHardFloorRatio 之上。
         */
        val catastropheFloorRatio: BigDecimal = BigDecimal.ZERO
    )

    /**
     * 退出执行参数（TAIL_DIFF 专属覆盖，均为可选，null=继承全局 exitFakSlippage / 不启用）：
     *  - tpSlippage：止盈(TP1/TP2) FAK 卖出滑点覆盖；
     *  - stopSlippage：止损(STOP/HARD_STOP/MODEL_xxx 等) FAK 卖出滑点覆盖；
     *  - worstPrice：FAK 卖出限价绝对底线（exitPrice = max(bestBid - slippage, worstPrice)），防滑点把限价压到不合理低位。
     */
    data class Execution(
        val tpSlippage: BigDecimal? = null,
        val stopSlippage: BigDecimal? = null,
        val worstPrice: BigDecimal? = null
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
            "max_reverse_velocity_sigma" to dynamicExit.maxReverseVelocitySigma.toPlainString(),
            "catastrophe_bid_floor" to dynamicExit.catastropheBidFloor.toPlainString(),
            "max_drawdown_pct" to dynamicExit.maxDrawdownPct.toPlainString(),
            "catastrophe_immediate" to dynamicExit.catastropheImmediate,
            "catastrophe_floor_ratio" to dynamicExit.catastropheFloorRatio.toPlainString()
        ),
        "execution" to buildMap {
            execution.tpSlippage?.let { put("tp_slippage", it.toPlainString()) }
            execution.stopSlippage?.let { put("stop_slippage", it.toPlainString()) }
            execution.worstPrice?.let { put("worst_price", it.toPlainString()) }
        }
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

    private fun parsePreset(raw: Map<String, Any?>, default: TailDiffExitPreset): TailDiffExitPreset {
        val tpLimitRaw = raw.pickMap("tp_limit", "tpLimit")
        val stopLossRaw = raw.pickMap("stop_loss", "stopLoss")
        val dynamicRaw = raw.pickMap("dynamic_exit", "dynamicExit")
        val executionRaw = raw.pickMap("execution", "execution")
        return TailDiffExitPreset(
            holdToExpiry = raw.pick("hold_to_expiry", "holdToExpiry").asBool(default.holdToExpiry),
            tpLimit = TailDiffExitPreset.TpLimit(
                enabled = tpLimitRaw["enabled"].asBool(default.tpLimit.enabled),
                price = tpLimitRaw["price"].asBigDecimal(default.tpLimit.price),
                ratio = tpLimitRaw["ratio"].asBigDecimal(default.tpLimit.ratio)
            ),
            stopLoss = TailDiffExitPreset.StopLoss(
                enabled = stopLossRaw["enabled"].asBool(default.stopLoss.enabled),
                offset = stopLossRaw["offset"].asBigDecimal(default.stopLoss.offset),
                minPrice = stopLossRaw.pick("min_price", "minPrice").asBigDecimal(default.stopLoss.minPrice),
                ratio = stopLossRaw["ratio"].asBigDecimal(default.stopLoss.ratio)
            ),
            dynamicExit = TailDiffExitPreset.DynamicExit(
                enabled = dynamicRaw["enabled"].asBool(default.dynamicExit.enabled),
                minDiffSigmaAfterEntry = dynamicRaw.pick("min_diff_sigma_after_entry", "minDiffSigmaAfterEntry").asBigDecimal(default.dynamicExit.minDiffSigmaAfterEntry),
                maxDiffRetracePct = dynamicRaw.pick("max_diff_retrace_pct", "maxDiffRetracePct").asBigDecimal(default.dynamicExit.maxDiffRetracePct),
                minModelProbAfterEntry = dynamicRaw.pick("min_model_prob_after_entry", "minModelProbAfterEntry").asBigDecimal(default.dynamicExit.minModelProbAfterEntry),
                minOddsAfterEntry = dynamicRaw.pick("min_odds_after_entry", "minOddsAfterEntry").asBigDecimal(default.dynamicExit.minOddsAfterEntry),
                maxReverseVelocitySigma = dynamicRaw.pick("max_reverse_velocity_sigma", "maxReverseVelocitySigma").asBigDecimal(default.dynamicExit.maxReverseVelocitySigma),
                catastropheBidFloor = dynamicRaw.pick("catastrophe_bid_floor", "catastropheBidFloor").asBigDecimal(default.dynamicExit.catastropheBidFloor),
                maxDrawdownPct = dynamicRaw.pick("max_drawdown_pct", "maxDrawdownPct").asBigDecimal(default.dynamicExit.maxDrawdownPct),
                catastropheImmediate = dynamicRaw.pick("catastrophe_immediate", "catastropheImmediate").asBool(default.dynamicExit.catastropheImmediate),
                catastropheFloorRatio = dynamicRaw.pick("catastrophe_floor_ratio", "catastropheFloorRatio").asBigDecimal(default.dynamicExit.catastropheFloorRatio)
            ),
            execution = TailDiffExitPreset.Execution(
                tpSlippage = executionRaw.pick("tp_slippage", "tpSlippage").asBigDecimalOrNull() ?: default.execution.tpSlippage,
                stopSlippage = executionRaw.pick("stop_slippage", "stopSlippage").asBigDecimalOrNull() ?: default.execution.stopSlippage,
                worstPrice = executionRaw.pick("worst_price", "worstPrice").asBigDecimalOrNull() ?: default.execution.worstPrice
            )
        )
    }

    private fun Any?.asBigDecimalOrNull(): BigDecimal? = when (this) {
        is BigDecimal -> this
        is Number -> BigDecimal(this.toString())
        is String -> if (this.isBlank()) null else this.toSafeBigDecimal()
        else -> null
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

    /**
     * 校验单档退出预设 JSON 合法性（供 create/update 校验复用）：
     *  - 空串/NULL 视为合法（走该档默认）。
     *  - 非空：必须能解析为 JSON 对象；若提供子块（tp_limit/stop_loss/dynamic_exit/execution，兼容 camelCase）必须为对象；
     *    提供的数值/比例需在合理范围（price/min_price/ratio/min_*_after_entry ∈ [0,1]，offset/retrace ∈ [0,1]，slippage>=0）。
     * 仅做"结构 + 边界"校验，缺省字段走默认，不强制全部字段存在。
     */
    fun isValid(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return true
        val map = try {
            raw.fromJson<Map<String, Any?>>() ?: return false
        } catch (e: Exception) {
            return false
        }
        // 子块若存在必须为对象
        for ((s, c) in listOf("tp_limit" to "tpLimit", "stop_loss" to "stopLoss", "dynamic_exit" to "dynamicExit", "execution" to "execution")) {
            val v = map.pick(s, c)
            if (v != null && v !is Map<*, *>) return false
        }
        val tp = map.pickMap("tp_limit", "tpLimit")
        val sl = map.pickMap("stop_loss", "stopLoss")
        val dyn = map.pickMap("dynamic_exit", "dynamicExit")
        val exec = map.pickMap("execution", "execution")
        fun unit(v: Any?): Boolean {
            val bd = v.asBigDecimalOrNull() ?: return true
            return bd >= BigDecimal.ZERO && bd <= BigDecimal.ONE
        }
        fun nonNeg(v: Any?): Boolean {
            val bd = v.asBigDecimalOrNull() ?: return true
            return bd >= BigDecimal.ZERO
        }
        if (!unit(tp["price"]) || !unit(tp["ratio"])) return false
        if (!unit(sl["offset"]) || !unit(sl.pick("min_price", "minPrice")) || !unit(sl["ratio"])) return false
        if (!unit(dyn.pick("max_diff_retrace_pct", "maxDiffRetracePct"))) return false
        if (!unit(dyn.pick("min_model_prob_after_entry", "minModelProbAfterEntry"))) return false
        if (!unit(dyn.pick("min_odds_after_entry", "minOddsAfterEntry"))) return false
        if (!nonNeg(dyn.pick("min_diff_sigma_after_entry", "minDiffSigmaAfterEntry"))) return false
        if (!nonNeg(dyn.pick("max_reverse_velocity_sigma", "maxReverseVelocitySigma"))) return false
        if (!unit(dyn.pick("catastrophe_bid_floor", "catastropheBidFloor"))) return false
        if (!unit(dyn.pick("max_drawdown_pct", "maxDrawdownPct"))) return false
        if (!unit(dyn.pick("catastrophe_floor_ratio", "catastropheFloorRatio"))) return false
        if (!nonNeg(exec.pick("tp_slippage", "tpSlippage")) || !nonNeg(exec.pick("stop_slippage", "stopSlippage"))) return false
        if (!unit(exec.pick("worst_price", "worstPrice"))) return false
        return true
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
            // 持有到结算为主，但开启动态退出作为"硬危险兜底"：退出评估在 holdToExpiry 下以 hardOnly 模式
            // 仅触发 minOdds/价差坍缩/反抽/方向翻转，剧烈反转时仍能止损，避免最大仓位零保护。
            holdToExpiry = true,
            tpLimit = TailDiffExitPreset.TpLimit(enabled = false, price = BigDecimal.ONE, ratio = BigDecimal.ONE),
            stopLoss = TailDiffExitPreset.StopLoss(enabled = false, offset = BigDecimal("0.40"), minPrice = BigDecimal("0.50"), ratio = BigDecimal.ONE),
            dynamicExit = TailDiffExitPreset.DynamicExit(
                enabled = true,
                minDiffSigmaAfterEntry = BigDecimal("0.5"),
                maxDiffRetracePct = BigDecimal("0.80"),
                minModelProbAfterEntry = BigDecimal("0.80"),
                minOddsAfterEntry = BigDecimal("0.70"),
                maxReverseVelocitySigma = BigDecimal("0.70")
            )
        )
    }
}

/**
 * 从 Map 中按 snake_case 优先、camelCase 兜底取值（兼容前端 placeholder 与用户两种命名习惯）。
 * 解析层统一别名，避免 camelCase 配置被静默忽略而回退默认档。
 */
internal fun Map<String, Any?>.pick(snake: String, camel: String): Any? =
    if (this.containsKey(snake)) this[snake] else this[camel]

/** 按 snake/camel 别名取嵌套 Map；缺失返回空 Map。 */
@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.pickMap(snake: String, camel: String): Map<String, Any?> =
    (pick(snake, camel) as? Map<String, Any?>) ?: emptyMap()

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
