-- V85: SCALP_FLIP 进场执行优化——条件化 REST 跳过 + 有界 re-quote（WS3）
--
-- 背景：
--   SCALP 进场走 computeBuyPrice，发单前无条件 REST 重拉盘口（+100~300ms 延迟），且零成交后无重试，
--   导致"门槛判定→撮合到达"秒级盘口上跳时 FAK 零成交、错失进场（trig 167/184/200 类）。
--
-- 新增两列：
--   - scalp_ws_freshness_skip_rest_ms：WS 盘口 quoteAgeMs<=此值时跳过发单前 REST 重拉（直接用 WS 快照复检进场闸），
--     降低决策→执行延迟。0=关闭（始终 REST 重拉，旧行为）。
--   - scalp_entry_requote_max：FAK 零成交后有界 re-quote 重试次数（重拉盘口+复检闸+EV 安全上沿重定价+重签）。0=关闭。
--   配套代码（无需建表）：SCALP 进场限价改为"EV 安全上沿作追价上限"——始终允许以已过闸的当前 ask 成交、向上追价不超 EV 安全价。
--
-- 保守修改：ADD COLUMN 以 0 回填 → 存量 SCALP 策略两项默认关、行为零回归；
--   随后 SET DEFAULT 仅影响后续新建策略（与 V62/V80/V83/V84 同口径）。存量策略由用户在前端重新保存采用。

ALTER TABLE crypto_tail_strategy
    ADD COLUMN scalp_ws_freshness_skip_rest_ms INT NOT NULL DEFAULT 0,
    ADD COLUMN scalp_entry_requote_max INT NOT NULL DEFAULT 0;

-- 新建策略默认：WS<=500ms 跳过 REST；零成交最多 re-quote 2 次
ALTER TABLE crypto_tail_strategy ALTER COLUMN scalp_ws_freshness_skip_rest_ms SET DEFAULT 500;
ALTER TABLE crypto_tail_strategy ALTER COLUMN scalp_entry_requote_max SET DEFAULT 2;
