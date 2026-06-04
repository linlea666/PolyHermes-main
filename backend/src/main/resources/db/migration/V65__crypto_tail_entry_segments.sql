-- V65: TAIL_DIFF 入场分段（entry segments）
--
-- 设计要点：
--   - 新增单列 tail_diff_entry_segments_json（TEXT, NULL）；为空/NULL 时行为与单窗口完全一致（向后兼容）。
--   - JSON 为分段数组，每段定义一个剩余时间窗口 [remaining_lo, remaining_hi] 及该段的阈值覆盖：
--       min_score / min_diff_sigma / min_edge / max_ask（缺省字段回退策略全局值）
--     以及可选 exit_tier_bias（NORMAL/PREMIUM/TOP），用于早窗优先 0.98/0.99 止盈+动态止损而非持有到结算。
--   - 命中某段后：用该段窗口与阈值覆盖策略对应字段，再走原 ScoreEngine/分层/退出冻结链路（copy-overlay，零回归）。
--   - 配置了 segments 但 remaining 不落在任何段内时 → SKIP(WINDOW_NO_SEGMENT)，属 opt-in 新行为。
--
-- 示例：
-- [
--   {"name":"early","remaining_hi":300,"remaining_lo":150,"min_score":90,"min_diff_sigma":2.2,"min_edge":0.04,"max_ask":0.92,"exit_tier_bias":"PREMIUM"},
--   {"name":"normal","remaining_hi":150,"remaining_lo":60},
--   {"name":"late","remaining_hi":60,"remaining_lo":50,"min_score":75}
-- ]

ALTER TABLE crypto_tail_strategy
    ADD COLUMN tail_diff_entry_segments_json TEXT NULL;
