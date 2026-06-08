-- V81: SCALP_FLIP 智能硬止损"插针容忍 pWin"（解耦于 holdToSettlePwin）
--
-- 背景（实盘日志 decision-log_20260608_123739 分析发现）：
--   两笔"方向对 + 模型强挺我方"的单被机械硬止损误杀（trigger 171: -0.0375；trigger 169: -0.3267）。
--   trigger 171 是教科书插针：止损信号 bestBid=0.84，~575ms 后实际成交时 bid 已弹回 0.92。
--   两单触发时模型都强挺持仓方向：modelSide==持仓✓、gap 深度顺向✓、safeRatio 1.34/1.37≥1.30✓、价源新鲜✓，
--   仅 pWin(0.91/0.915) 卡在智能硬止损旁路的 pWin 门槛上——该门槛复用了 holdToSettlePwin(0.96)。
--   根因：旁路把"忽略一次盘口插针"和"承诺持有到结算"用了同一最严门槛(0.96)，导致旁路形同虚设、
--   机械止损单 tick 把赢家反复砍。"忽略插针"只需模型仍明显站我方，不需 0.96 的持有到结算级信心。
--
-- 新增一列：
--   - scalp_smart_stop_min_pwin：SCALP 智能硬止损旁路的 pWin 下限（与 holdToSettlePwin 解耦）。
--     旁路其余条件（方向/gap/safeRatio>=max(exitSafeRatio,1.30)/价源新鲜）保持不变，绝非"无脑硬扛"。
--
-- 保守修改：ADD COLUMN 以 0.96 回填，存量 SCALP 策略零回归（旁路 pWin 门槛仍等于原 holdToSettlePwin 口径）；
--   随后 SET DEFAULT 0.70 仅影响后续新建策略（与 V78/V79/V80 同口径）。存量策略由用户在前端重新保存以采用。
--   仅 SCALP_FLIP 退出链路消费该列；BARRIER 仍用 holdToSettlePwin，TAIL_DIFF 行为不变。

ALTER TABLE crypto_tail_strategy
    ADD COLUMN scalp_smart_stop_min_pwin DECIMAL(20, 8) NOT NULL DEFAULT 0.96;

-- 新建策略默认放宽到 0.70（插针容忍）
ALTER TABLE crypto_tail_strategy ALTER COLUMN scalp_smart_stop_min_pwin SET DEFAULT 0.70;
