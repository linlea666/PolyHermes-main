package com.wrbug.polymarketbot.enums

/**
 * 阶梯止盈模式退出类型
 *  - TP1   : 第一档止盈触发（bestBid >= tp1Price 且 pWin < tp1HoldPwin）
 *  - TP2   : 第二档止盈触发（bestBid >= tp2Price 且 pWin < tp2HoldPwin）
 *  - STOP  : 止损触发（pWin <= stopProb 或 bestBid <= stopPrice）
 *  - FORCE : 距结算剩余时间 <= forceExitBeforeSettleSeconds 且未满足持有到结算条件，强制平仓
 *  - SETTLE: 预留枚举值。当前未在 exit 表写入路径使用：持有到结算路径由 SettlementService 直接更新
 *           trigger.exitStatus=HELD_TO_SETTLE + 链上 condition 结算，不写 crypto_tail_strategy_exit 行。
 */
enum class ExitKind {
    TP1,
    TP2,
    STOP,
    HARD_STOP,
    MODEL_INVALID,
    MODEL_FLIP,
    GAP_FLIP,
    WICK_REVERSAL,
    FORCE,
    SETTLE
}
