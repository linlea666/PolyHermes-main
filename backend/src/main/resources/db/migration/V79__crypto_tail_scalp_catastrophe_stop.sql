-- V79: SCALP_FLIP 熔断即时砍（可配地板 + 跳过确认 tick）
--
-- 背景（实盘日志 decision-log_20260607_212933 分析发现）：
--   急跌行情下 minOdds 软止损走"连续确认(exitConfirmTicks)防插针"路径，确认期间 bestBid 继续崩跌
--   （如 0.91→0.75 数秒内），FAK 市价单成交时已远低于止损线，单笔亏损被显著放大（最差 -0.467）。
--   退出主流程中真熔断(catastropheExitDecision)已支持 forceImmediate 跳过确认 tick，且优先级最高
--   （先于 minOdds 评估），但 SCALP 的 buildScalpExitPresetJson 把 catastropheBidFloor/immediate 写死为
--   0/false，导致该兜底对 SCALP 永久失效。本次将其暴露为可配字段接通该能力。
--
-- 新增两列：
--   - scalp_catastrophe_bid_floor：熔断绝对地板，bestBid 跌破即发无地板市价止损（0=关闭）；
--   - scalp_catastrophe_immediate：是否跳过 exitConfirmTicks 确认立即市价砍（仅在地板>0 时有意义）。
--
-- 保守修改：ADD COLUMN 以"关闭/false"回填，存量 SCALP 策略零回归（地板=0 → 熔断不触发）；
--   随后 SET DEFAULT 0.88/true，仅影响后续新建策略（与 V78 同口径）。存量策略由用户在前端重新保存以采用。

ALTER TABLE crypto_tail_strategy
    ADD COLUMN scalp_catastrophe_bid_floor DECIMAL(20, 8) NOT NULL DEFAULT 0,
    ADD COLUMN scalp_catastrophe_immediate BOOLEAN NOT NULL DEFAULT FALSE;

-- 新建策略默认开启熔断（地板 0.88，低于止损地板 0.90；即时砍）
ALTER TABLE crypto_tail_strategy ALTER COLUMN scalp_catastrophe_bid_floor SET DEFAULT 0.88;
ALTER TABLE crypto_tail_strategy ALTER COLUMN scalp_catastrophe_immediate SET DEFAULT TRUE;
