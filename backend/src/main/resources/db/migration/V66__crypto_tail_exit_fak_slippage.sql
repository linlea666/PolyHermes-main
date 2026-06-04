-- V66: 全局退出 FAK 滑点（exit_fak_slippage）
--
-- 设计要点：
--   - 镜像 entry_fak_slippage：FAK 卖出限价 = bestBid - exit_fak_slippage（向下取整到 tick）。
--   - 对所有交易模式（LEGACY_SPREAD/BARRIER_HOLD/BRACKET_DYNAMIC/TAIL_DIFF）统一生效。
--   - 带默认值 0.02，对历史记录无行为影响（与此前硬编码常量一致）。
--   - TAIL_DIFF 可在 exit_preset JSON 的 execution 块内按 TP/STOP 进一步覆盖（见后续迁移）。

ALTER TABLE crypto_tail_strategy
    ADD COLUMN exit_fak_slippage DECIMAL(20, 8) NOT NULL DEFAULT 0.02;
