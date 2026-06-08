-- V84: SCALP_FLIP 熔断相对地板比例 + 熔断模型门控（WS2）
--
-- 背景：
--   此前 SCALP 熔断仅靠绝对地板 scalp_catastrophe_bid_floor + catastrophe_immediate 立即砸出，
--   纯盘口触发、不看模型，且抗插针总闸 WICK_GUARD 依赖默认关闭的 enable_smart_hard_stop，
--   导致盘口瞬时插针被当真反转秒砸（trig 195/180 类）。
--
-- 新增一列：
--   - scalp_catastrophe_floor_ratio：熔断相对地板比例，>0 时熔断地板=入场价×此比例，替代绝对线，
--     使不同入场价下熔断口径一致；须高于深底线 scalp_hard_floor_ratio(0.50)。0=关闭(沿用绝对线)。
--   配套代码逻辑（无需建表）：熔断触发后用底层模型复核——模型仍强挺判为插针、走 exit_confirm_ticks 短确认；
--   模型翻转/转弱判为真反转、按 catastrophe_immediate 即时砍。该门控默认即生效，不依赖 enable_smart_hard_stop。
--
-- 保守修改：ADD COLUMN 以 0 回填 → 存量 SCALP 策略相对地板默认关、仍用原绝对线，零回归；
--   随后 SET DEFAULT 0.85 仅影响后续新建策略（与 V62/V80/V83 同口径）。存量策略由用户在前端重新保存采用。

ALTER TABLE crypto_tail_strategy
    ADD COLUMN scalp_catastrophe_floor_ratio DECIMAL(20, 8) NOT NULL DEFAULT 0;

-- 新建策略默认相对地板 0.85（≈入场 -15%，高于深底线 0.50）
ALTER TABLE crypto_tail_strategy ALTER COLUMN scalp_catastrophe_floor_ratio SET DEFAULT 0.85;
