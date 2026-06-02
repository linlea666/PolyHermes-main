package com.wrbug.polymarketbot.enums

/**
 * 加密价差策略交易模式枚举
 *
 * 三种模式语义对照：
 *  - LEGACY_SPREAD: 旧价差模式，按价格区间 + 价差触发，买入后持有到结算
 *  - BARRIER_HOLD : 障碍模式（终值概率），pWin + EV 通过后买入并持有到结算
 *  - BRACKET_DYNAMIC: 概率阶梯止盈模式，pWin + EV + 价格上限通过后买入，
 *                    入场后实时根据 pWin / bestBid / 剩余时间决策止盈/止损/持有到结算
 *
 * 历史迁移：barrier_enabled=1 → BARRIER_HOLD，否则 LEGACY_SPREAD
 */
enum class TradingMode(val value: Int, val description: String) {
    LEGACY_SPREAD(0, "旧价差"),
    BARRIER_HOLD(1, "障碍"),
    BRACKET_DYNAMIC(2, "概率阶梯止盈");

    companion object {
        fun fromValue(value: Int?): TradingMode {
            if (value == null) return LEGACY_SPREAD
            return values().find { it.value == value }
                ?: throw IllegalArgumentException("未知的交易模式: $value")
        }

        fun fromValueOrDefault(value: Int?, default: TradingMode = LEGACY_SPREAD): TradingMode {
            if (value == null) return default
            return values().find { it.value == value } ?: default
        }

        fun fromString(value: String?): TradingMode {
            if (value.isNullOrBlank()) return LEGACY_SPREAD
            return values().find { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("未知的交易模式: $value")
        }
    }
}
