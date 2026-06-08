-- V88: SCALP_FLIP 持仓中反转防线（防线A）保守默认值
--
-- 背景：
--   复盘显示"方向对却被反转割肉"的单子，多因持仓中领先优势(diff_sigma)快速坍缩 / 模型胜率衰减，
--   而 scalp_min_model_prob_after_entry 与 scalp_max_diff_retrace_pct 历史默认 0（关闭），缺少这道动态止损。
--
-- 变更：仅调整两列的 DB 默认值（与 V62/V80/V83/V84/V85 同口径——SET DEFAULT 仅影响后续新建策略）：
--   scalp_min_model_prob_after_entry: 0 -> 0.80（持仓 pWin 跌破即退出）
--   scalp_max_diff_retrace_pct:       0 -> 0.35（领先优势相对入场回撤超 35% 即退出）
--   退出算法不变（CryptoTailBracketExitService 仍以 >0 才启用、入场冻结 diff_sigma 为基准）。
--
-- 保守修改：不回填存量行（存量仍为各自存值，零回归）；存量策略由用户在前端重新保存采用新默认。
ALTER TABLE crypto_tail_strategy ALTER COLUMN scalp_min_model_prob_after_entry SET DEFAULT 0.80;
ALTER TABLE crypto_tail_strategy ALTER COLUMN scalp_max_diff_retrace_pct SET DEFAULT 0.35;
