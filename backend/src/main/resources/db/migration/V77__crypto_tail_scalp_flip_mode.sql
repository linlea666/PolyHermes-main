-- V77: Scalp Flip Mode（快进快出模式，第 5 个交易模式 SCALP_FLIP=4）
--
-- 设计要点：
--   - 极简价格区间进场（默认 bestAsk ∈ [0.96, 0.97] 买入 favorite）+ 可选历史反转率门槛筛选；
--   - 退出复用 BRACKET/TAIL_DIFF 退出引擎：入场时按 scalp_* 列冻结一份退出预设到 trigger.exit_preset_json，
--     退出评估走 CryptoTailBracketExitService.decideTailDiffExit（价位止损 + 标的 diff_sigma/反抽速度/minOdds 动态止损）；
--   - 退出模式开关 scalp_hold_winner_to_settle：true=赢单持有到结算(1.0，不挂止盈)；false=挂 scalp_tp_price(默认0.99)止盈；
--     两种模式均保留 stop_loss 价位兜底 + 动态止损（预设 hold_to_expiry 恒为 false，仅用 tp_limit.enabled 切换是否挂止盈）；
--   - 所有阈值/开关/区间均存表，前端可改，不写死；市场选择（5m/15m）沿用现有 interval_seconds/market_slug_prefix，不变。
--
-- 注：所有新列带默认值，对历史 LEGACY_SPREAD/BARRIER_HOLD/BRACKET_DYNAMIC/TAIL_DIFF 记录无任何行为影响（仅 mode=4 时读取）。

ALTER TABLE crypto_tail_strategy
    -- ===== 进场价格区间与买入限价 =====
    ADD COLUMN scalp_entry_min_price DECIMAL(20, 8) NOT NULL DEFAULT 0.96,
    ADD COLUMN scalp_entry_max_price DECIMAL(20, 8) NOT NULL DEFAULT 0.97,
    ADD COLUMN scalp_max_fill_price DECIMAL(20, 8) NOT NULL DEFAULT 0.975,

    -- ===== 进场时间窗口 =====
    -- window_start/end 为距周期开始的秒数；window_end=0 表示不设上限，仅由 min_remaining 收口尾盘
    ADD COLUMN scalp_window_start_seconds INT NOT NULL DEFAULT 0,
    ADD COLUMN scalp_window_end_seconds INT NOT NULL DEFAULT 0,
    ADD COLUMN scalp_min_remaining_seconds INT NOT NULL DEFAULT 30,

    -- ===== 退出流动性闸（进场即检查可退出深度）=====
    -- NULL = 不检查
    ADD COLUMN scalp_min_exit_bid_depth_usdc DECIMAL(20, 8) DEFAULT NULL,

    -- ===== 历史反转率门槛（可选筛选）=====
    ADD COLUMN scalp_reversal_gate_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN scalp_min_model_prob DECIMAL(20, 8) NOT NULL DEFAULT 0.95,
    ADD COLUMN scalp_min_edge DECIMAL(20, 8) NOT NULL DEFAULT 0,
    -- 统计数据源：HYBRID（POLYMARKET 优先回退 BINANCE）/ POLYMARKET / BINANCE
    ADD COLUMN scalp_stats_source VARCHAR(16) NOT NULL DEFAULT 'HYBRID',
    ADD COLUMN scalp_stats_lookback_days INT NOT NULL DEFAULT 180,
    ADD COLUMN scalp_stats_min_samples INT NOT NULL DEFAULT 30,
    -- 统计不可用时：TRUE=拦截进场；FALSE=降级为纯价格区间放行
    ADD COLUMN scalp_require_stats BOOLEAN NOT NULL DEFAULT FALSE,

    -- ===== 同方向并发上限（NULL=不限制，仍受全局 max_concurrent_positions 约束）=====
    ADD COLUMN scalp_max_concurrent_same_direction INT DEFAULT NULL,

    -- ===== 退出模式 =====
    -- TRUE=赢单持有到结算(拿 1.0，不挂止盈)；FALSE=挂止盈单(scalp_tp_price)锁利
    ADD COLUMN scalp_hold_winner_to_settle BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN scalp_tp_price DECIMAL(20, 8) NOT NULL DEFAULT 0.99,

    -- ===== 价位止损（相对回撤 + 绝对地板）=====
    ADD COLUMN scalp_stop_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN scalp_stop_offset DECIMAL(20, 8) NOT NULL DEFAULT 0.08,
    ADD COLUMN scalp_stop_min_price DECIMAL(20, 8) NOT NULL DEFAULT 0.90,
    -- 持仓中 bestBid 跌破此值触发软止损（连续确认 + worstPrice 地板防插针）；0=不启用
    ADD COLUMN scalp_min_odds_after_entry DECIMAL(20, 8) NOT NULL DEFAULT 0.90,

    -- ===== 标的方向止损（diff_sigma 领先优势消失）=====
    ADD COLUMN scalp_underlying_stop_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN scalp_underlying_stop_sigma DECIMAL(20, 8) NOT NULL DEFAULT 0.30,

    -- ===== 反抽速度止损 =====
    ADD COLUMN scalp_reverse_velocity_stop_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN scalp_max_reverse_velocity_sigma DECIMAL(20, 8) NOT NULL DEFAULT 0.40,
    ADD COLUMN scalp_reverse_velocity_window_seconds INT NOT NULL DEFAULT 10;
