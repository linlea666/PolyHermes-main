-- V83: SCALP_FLIP 复用尾盘风控——当日连亏暂停/停 + 日亏熔断（专属字段）
--
-- 背景：
--   SCALP_FLIP 进场前已走 CryptoTailRiskService.checkRiskGate，但此前仅命中通用字段
--   （dailyLossLimitUsdc + maxConsecutiveLosses「全时段连亏」+ pauseAfterLossMinutes），
--   缺少 TAIL_DIFF 那套「当日维度」连亏暂停/当日停 + 专属日亏熔断的语义。
--   现把该风控复用到 SCALP：新增 SCALP 专属字段，提取共享「当日连亏闸」供两模式共用。
--
-- 新增三列：
--   - scalp_daily_loss_limit_usdc：SCALP 专属当日已实现亏损熔断阈值，null=回退全局 daily_loss_limit_usdc。
--   - scalp_consec_loss_pause_count：当日连续亏损达 N 笔后在 pause_after_loss_minutes 冷却窗内暂停（0=关）。
--   - scalp_consec_loss_stop_count：当日连续亏损达 N 笔后熔断到日终（0=关）。
--   暂停时长复用全局 pause_after_loss_minutes（与 TAIL_DIFF 一致）。
--
-- 保守修改：ADD COLUMN 以 0/NULL 回填 → 存量 SCALP 策略风控默认关，零回归；随后 SET DEFAULT
--   2/3 仅影响后续新建策略（与 V62/V80 同口径）。存量策略由用户在前端重新保存以采用。

ALTER TABLE crypto_tail_strategy
    ADD COLUMN scalp_daily_loss_limit_usdc DECIMAL(20, 8) NULL,
    ADD COLUMN scalp_consec_loss_pause_count INT NOT NULL DEFAULT 0,
    ADD COLUMN scalp_consec_loss_stop_count INT NOT NULL DEFAULT 0;

-- 新建策略默认连亏 2 笔暂停、3 笔当日停（与 TAIL_DIFF 默认一致）
ALTER TABLE crypto_tail_strategy ALTER COLUMN scalp_consec_loss_pause_count SET DEFAULT 2;
ALTER TABLE crypto_tail_strategy ALTER COLUMN scalp_consec_loss_stop_count SET DEFAULT 3;
