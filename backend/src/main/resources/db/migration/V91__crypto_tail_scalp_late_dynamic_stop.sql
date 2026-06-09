-- V91: SCALP_FLIP 尾盘动态止损 + WICK_GUARD 大回撤失效（时间分层风控）
--
-- 背景：
--   实盘亏损单入场质量正常(pWin>=0.90、safeRatio/diff_sigma 达标)，但入场后盘口 bid 从 0.93/0.96
--   一路真反转跌到 0.50/0.38。现有 WICK_GUARD 只要"模型强挺"就把价格下跌判为插针继续持有；
--   模型在最后十几秒反应滞后，于是一路扛到深底线(入场×scalpHardFloorRatio)才砍，单笔大亏。
--   根因：WICK_GUARD 缺少"时间×回撤"维度——剩余 30-50s 容忍插针合理，但剩余 <=N 秒已无修复时间。
--
-- 语义：
--   - scalp_late_stop_enabled=FALSE 时整套尾盘止损不生效（默认关，零回归，仅 UI 手动开启后测试）；
--   - 仅在 remaining_seconds <= scalp_late_stop_seconds 的尾盘窗口内生效；
--   - 触发条件(满足其一即退出，HARD_STOP 全清)：
--       回撤触发：scalp_late_peak_drawdown>0 且 (peakBid - currentBestBid) >= scalp_late_peak_drawdown（绝对价差）；
--       地板触发：currentBestBid <= scalp_late_bid_floor（peakBid 缺失也能触发，不要求模型转弱）；
--   - scalp_disable_wick_guard_on_late_stop=TRUE 时，尾盘止损命中后禁止 WICK_GUARD 豁免，立即退出；
--   - scalp_late_stop_require_weak_model=TRUE 时，仅当模型转弱才触发尾盘止损（默认 FALSE，避免止损滞后）。
--
-- 保守修改：ADD COLUMN 全部带默认值，开关默认关 → 存量及新建策略行为零回归。
ALTER TABLE crypto_tail_strategy
    ADD COLUMN scalp_late_stop_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN scalp_late_stop_seconds INT NOT NULL DEFAULT 15,
    ADD COLUMN scalp_late_peak_drawdown DECIMAL(20, 8) NOT NULL DEFAULT 0.18000000,
    ADD COLUMN scalp_late_bid_floor DECIMAL(20, 8) NOT NULL DEFAULT 0.70000000,
    ADD COLUMN scalp_disable_wick_guard_on_late_stop BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN scalp_late_stop_require_weak_model BOOLEAN NOT NULL DEFAULT FALSE;
