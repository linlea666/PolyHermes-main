-- V78: SCALP_FLIP 退出参数调优 + 新增可配退出软阈值
--
-- 背景（审计发现）：
--   F1) scalp_stop_offset 默认 0.08 在 0.96+ 高价进场下恒被绝对地板 scalp_stop_min_price=0.90 覆盖
--       （entry*(1-0.08) < 0.90），相对回撤止损参数实际无效。下调默认到 0.05，使 entry 0.96 时相对止损
--       线 ≈0.912 高于地板，成为有效的中间硬止损线（地板 0.90 作为更深兜底）。
--   F2) scalp_min_odds_after_entry 默认 0.90 == scalp_stop_min_price，软止损(连续确认+worstPrice 地板)永远
--       被先评估的硬止损影子化。上调默认到 0.93，使软止损在 (硬止损线, 0.93] 区间先触发，硬止损作兜底。
--   F6) 新增两个可配退出软阈值（默认 0=关闭，对现有行为零影响）：
--       - scalp_min_model_prob_after_entry：持仓 pWin 跌破即退出（模型衰减软止损）；
--       - scalp_max_diff_retrace_pct：持仓 diff_sigma 相对入场回撤超过比例即退出（领先优势回撤）。
--
-- 注：仅调整列 DEFAULT（影响后续新建策略）。已存在的 SCALP 策略保留各自已存值，由用户在前端重新保存以采用新默认，
--     避免静默改写在用策略行为（遵循「保守修改」，与 V73 同口径）。

ALTER TABLE crypto_tail_strategy
    ADD COLUMN scalp_min_model_prob_after_entry DECIMAL(20, 8) NOT NULL DEFAULT 0,
    ADD COLUMN scalp_max_diff_retrace_pct DECIMAL(20, 8) NOT NULL DEFAULT 0;

-- F1/F2：更新列默认值（仅影响后续未显式赋值的插入）
ALTER TABLE crypto_tail_strategy ALTER COLUMN scalp_stop_offset SET DEFAULT 0.05;
ALTER TABLE crypto_tail_strategy ALTER COLUMN scalp_min_odds_after_entry SET DEFAULT 0.93;
