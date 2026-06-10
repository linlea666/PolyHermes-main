-- V95: SCALP_FLIP 现货共识主止损（现货大脑双层止损架构第 1 层）
--
-- 背景（承接 V94 + 两日决策日志复盘）：
--   两天 6 笔止损样本中，现货共识在"止损触发瞬间"的 danger 状态 6/6 正确区分了真反转与插针误伤，
--   但现货只有"喊"的权利（仅尾盘窗口 50% 减仓一次），Catastrophe/HARD_FLOOR 等盘口止损不受其管：
--   盘口闪崩到 0.4~0.5 才触发 → 成交价锁死大额亏损；插针误伤与真反转在盘口维度无法区分。
--   本期赋予现货共识完整执行权：持仓全程，现货新鲜且危险并"持续确认"后，无视盘口价立即市价全清，
--   砍在盘口塌陷之前；瞬时假穿（亚秒收回）由持续确认窗口过滤。
--
-- 设计哲学（沿用 V93/V94 强约束）：
--   - 现货价只做决策信号，绝不进入 pWin/结算口径；
--   - 默认关、零回归；现货不新鲜/缺失则完全回退旧行为（fail-safe，由深底线 HARD_FLOOR 兜底）。
--
-- 新增列（仅 SCALP_FLIP 消费）：
--   scalp_spot_lead_primary_stop_enabled       现货主止损总开关（danger 持续确认后市价全清）
--   scalp_spot_lead_primary_stop_persist_ms    危险持续确认毫秒（0=瞬时即砍；过滤亚秒假穿）
--   scalp_spot_lead_primary_stop_min_gap_usd   穿价深度下限（USD，0=不限；二级过滤浅穿）
ALTER TABLE crypto_tail_strategy
    ADD COLUMN scalp_spot_lead_primary_stop_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN scalp_spot_lead_primary_stop_persist_ms INT NOT NULL DEFAULT 600,
    ADD COLUMN scalp_spot_lead_primary_stop_min_gap_usd DECIMAL(20,8) NOT NULL DEFAULT 0;
