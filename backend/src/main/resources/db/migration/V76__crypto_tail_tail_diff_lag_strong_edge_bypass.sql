-- V76: Tail Diff 「聪明 lag 闸」强等级低估逃生口开关
--
-- 设计要点：
--   - DYNAMIC/HYBRID 模式下 lag<=0 时，若 edge>=edgeFullScale 且模型为统计型(STATS/HYBRID_STATS)
--     且样本足量，则放行 ODDS_LAG_INSUFFICIENT 否决（视为确有等级错价而非纯 Φ 同构定价）。
--   - 默认 FALSE = 旧行为完全一致（零回归）；仅在显式开启后生效，且 STATIC 模式恒不受影响。
--   - 延续 V62/V72 原则：开关存表，前端可改，不写死。

ALTER TABLE crypto_tail_strategy
    ADD COLUMN tail_diff_odds_lag_strong_edge_bypass BOOLEAN NOT NULL DEFAULT FALSE;
