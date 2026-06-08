-- V87: SCALP_FLIP 进场价差闸（"目标价与当前价过近则拒单"防反转）
--
-- 背景：
--   复盘发现"越临近结算、当前价与目标价越接近(gap/diff_sigma 越小)，越容易反转割肉"。
--   现有 SCALP 进场闸基于价格区间 + 历史反转率门槛，但缺少"进场时刻领先优势是否足够"的直接闸。
--   本闸在方向闸之后增加一道：领先优势(diff_sigma 或 |gap|)不足时直接拒单(SCALP_GAP_TOO_SMALL)。
--
-- 语义：
--   - scalp_gap_gate_enabled=FALSE 时整闸不生效（默认关，零回归，待数据驱动调参后再开）；
--   - 闸生效窗口由 remaining_lo/hi 限定（均为距结算剩余秒）：
--       lo=0 且 hi=0 → 全周期生效；否则仅当 remaining ∈ [lo, hi] 时生效（聚焦尾盘）；
--   - 生效时拒单条件(满足其一即拒)：
--       min_entry_diff_sigma>0 且 当前 diff_sigma < min_entry_diff_sigma；
--       min_entry_gap_abs>0   且 当前 |gap|     < min_entry_gap_abs；
--     两阈值=0 表示该维度不检查。
--
-- 保守修改：ADD COLUMN 全部带默认值，gate 默认关 → 存量及新建策略行为零回归。
ALTER TABLE crypto_tail_strategy
    ADD COLUMN scalp_gap_gate_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN scalp_min_entry_diff_sigma DECIMAL(20, 8) NOT NULL DEFAULT 0,
    ADD COLUMN scalp_min_entry_gap_abs DECIMAL(30, 8) NOT NULL DEFAULT 0,
    ADD COLUMN scalp_gap_gate_remaining_lo INT NOT NULL DEFAULT 0,
    ADD COLUMN scalp_gap_gate_remaining_hi INT NOT NULL DEFAULT 0;
