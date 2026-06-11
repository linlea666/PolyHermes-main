-- V96: SCALP_FLIP 终场闪针防御（预挂止盈 + 盘口不稳定熔断 + 主止损盘口确认旁路 + 终场反向对冲）
--
-- 背景（decision-log 20260611 复盘）：trigger 在结算前最后 4 秒遭遇"终场闪针"——
-- 对手盘整层蒸发，bid 0.96→0.02，现货主止损与深底线均触发但市价单已无对手盘可吃，
-- 单笔近归零回吐全日利润。响应式退出在簿面蒸发面前物理性失效，须前置防御：
--   A. 预挂止盈（scalp_tp_resting_enabled）：成交后立即 GTC 限价挂 scalp_tp_price，
--      价格先到先成交，闪针发生前止盈单已在簿上排队；
--   C. 盘口不稳定熔断（scalp_book_instability_*）：刚闪过针的盘口冷却期内拒绝新进场；
--   D. 主止损盘口确认旁路（scalp_spot_lead_primary_stop_book_confirm_drawdown）：
--      现货危险 + 盘口已大幅塌陷双确认时跳过 persist 等待立即砍；
--   B. 终场反向对冲（scalp_hedge_*）：主仓深度获利近结算时按风险特征库评分
--      条件性买入对侧极廉价 token（彩票式保险），翻转时对侧结算 $1 对冲归零损失。
--
-- 设计哲学：全部默认关/零回归；对冲单 trigger_type='HEDGE' 独立入账，
-- 风控连亏/并发/胜率统计排除，日亏合计包含（权利金是真实支出）。
ALTER TABLE crypto_tail_strategy
    ADD COLUMN scalp_tp_resting_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN scalp_book_instability_cooldown_sec INT NOT NULL DEFAULT 0,
    ADD COLUMN scalp_book_instability_ask_jump DECIMAL(20,8) NOT NULL DEFAULT 0.30,
    ADD COLUMN scalp_spot_lead_primary_stop_book_confirm_drawdown DECIMAL(20,8) NOT NULL DEFAULT 0,
    ADD COLUMN scalp_hedge_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN scalp_hedge_arm_seconds INT NOT NULL DEFAULT 25,
    ADD COLUMN scalp_hedge_min_own_bid DECIMAL(20,8) NOT NULL DEFAULT 0.95,
    ADD COLUMN scalp_hedge_max_price DECIMAL(20,8) NOT NULL DEFAULT 0.05,
    ADD COLUMN scalp_hedge_budget_usdc DECIMAL(20,8) NOT NULL DEFAULT 1,
    ADD COLUMN scalp_hedge_min_feature_score INT NOT NULL DEFAULT 1,
    ADD COLUMN scalp_hedge_feature_instability_lookback_sec INT NOT NULL DEFAULT 120,
    ADD COLUMN scalp_hedge_feature_spot_cushion_usd DECIMAL(20,8) NOT NULL DEFAULT 0,
    ADD COLUMN scalp_hedge_feature_gap_shrink_ratio DECIMAL(20,8) NOT NULL DEFAULT 0,
    ADD COLUMN scalp_hedge_feature_recent_flip_lookback INT NOT NULL DEFAULT 0,
    ADD COLUMN scalp_hedge_feature_opp_ask_floor DECIMAL(20,8) NOT NULL DEFAULT 0;
