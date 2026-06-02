-- ============================================
-- V53: 加密尾盘策略 - 进场 FAK 滑点字段
-- 解决 BARRIER/BRACKET 模式下 limit 紧贴 bestAsk 导致 stale-quote KILL 的根因
-- limit = effectiveCost + entry_fak_slippage（封顶 maxEntryPrice/bracketMaxEntryPrice）
-- EV 闸口径不变（仍按 effectiveCost = bestAsk + fee 计算 edge）
-- ============================================

ALTER TABLE crypto_tail_strategy
    ADD COLUMN entry_fak_slippage DECIMAL(20, 8) NOT NULL DEFAULT 0.02
        COMMENT 'FAK 进场限价滑点：limit = effectiveCost + 此值, 封顶 maxEntryPrice/bracketMaxEntryPrice. 取值 [0, 0.10], 默认 0.02';
