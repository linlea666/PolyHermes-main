package com.wrbug.polymarketbot.enums

/**
 * 触发记录的退出状态机（仅 BRACKET_DYNAMIC 模式使用，其他模式恒为 NONE）
 *  - NONE          : 不适用（非阶梯模式或买入未成交）
 *  - OPEN          : 买入已成交，持仓中，等待退出决策
 *  - PARTIAL_EXIT  : 已触发部分止盈或止损，仍有 remainingSize > 0
 *  - FULLY_EXITED  : 全部仓位已通过 exit 单卖出（remainingSize = 0）
 *  - HELD_TO_SETTLE: 周期结束未触发任何退出，剩余仓位将由 SettlementService 走链上 condition 结算
 */
enum class ExitStatus {
    NONE,
    OPEN,
    PARTIAL_EXIT,
    FULLY_EXITED,
    HELD_TO_SETTLE;

    companion object {
        fun fromString(value: String?): ExitStatus {
            if (value.isNullOrBlank()) return NONE
            return values().find { it.name.equals(value, ignoreCase = true) } ?: NONE
        }
    }
}
