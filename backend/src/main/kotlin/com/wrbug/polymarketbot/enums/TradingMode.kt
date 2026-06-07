package com.wrbug.polymarketbot.enums

/**
 * 加密价差策略交易模式枚举
 *
 * 四种模式语义对照：
 *  - LEGACY_SPREAD : 旧价差模式，按价格区间 + 价差触发，买入后持有到结算
 *  - BARRIER_HOLD  : 障碍模式（终值概率），pWin + EV 通过后买入并持有到结算
 *  - BRACKET_DYNAMIC: 概率阶梯止盈模式，pWin + EV + 价格上限通过后买入，
 *                    入场后实时根据 pWin / bestBid / 剩余时间决策止盈/止损/持有到结算
 *  - TAIL_DIFF     : 尾盘价差模式（V62 引入），用 0-100 加权机会评分（价差/时间/赔率/历史反转率/盘口/数据可靠性）
 *                    + 13 项硬否决，按分数分层（普通/优质/顶级）决定金额倍率与退出预设；
 *                    复用 BARRIER 的 gap/σ/pWin 内核与 BRACKET 的退出引擎，不重写下单/退出/风控/日志。
 *  - SCALP_FLIP    : 快进快出模式（V77 引入），极简价格区间进场（如 0.96~0.97 买入favorite）+ 可选历史反转率门槛筛选；
 *                    退出可切换"赢单持有到结算(拿 1.0)"或"挂止盈单(如 0.99)锁利"，并以多源可配置止损（标的方向反转/反抽速度/价位兜底，
 *                    按剩余时间分段）砍左尾。复用 LEGACY 的极简进场骨架与 BRACKET/TAIL_DIFF 的退出引擎，物理隔离不影响其他模式。
 *
 * 历史迁移：barrier_enabled=1 → BARRIER_HOLD，否则 LEGACY_SPREAD（TAIL_DIFF/SCALP_FLIP 仅由新建/更新写入）
 */
enum class TradingMode(val value: Int, val description: String) {
    LEGACY_SPREAD(0, "旧价差"),
    BARRIER_HOLD(1, "障碍"),
    BRACKET_DYNAMIC(2, "概率阶梯止盈"),
    TAIL_DIFF(3, "尾盘价差"),
    SCALP_FLIP(4, "快进快出");

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
