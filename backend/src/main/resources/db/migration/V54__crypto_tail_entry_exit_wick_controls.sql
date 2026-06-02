-- ============================================
-- V54: 加密尾盘策略 - 入场过滤增强、统一持仓退出、影线辅助风控
-- ============================================

ALTER TABLE crypto_tail_strategy
    ADD COLUMN min_safe_ratio DECIMAL(20, 8) NOT NULL DEFAULT 1.20 COMMENT '普通入场 safeRatio 下限',
    ADD COLUMN min_safe_ratio_up DECIMAL(20, 8) NOT NULL DEFAULT 1.50 COMMENT 'UP 入场 safeRatio 下限',
    ADD COLUMN min_safe_ratio_down DECIMAL(20, 8) NOT NULL DEFAULT 1.20 COMMENT 'DOWN 入场 safeRatio 下限',
    ADD COLUMN high_price_threshold DECIMAL(20, 8) NOT NULL DEFAULT 0.90 COMMENT '高价入场额外保护触发阈值',
    ADD COLUMN high_price_min_pwin DECIMAL(20, 8) NOT NULL DEFAULT 0.97 COMMENT '高价入场 pWin 下限',
    ADD COLUMN high_price_min_safe_ratio DECIMAL(20, 8) NOT NULL DEFAULT 2.50 COMMENT '高价入场 safeRatio 下限',
    ADD COLUMN enable_exit_manager BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否启用统一持仓退出管理',
    ADD COLUMN max_loss_pct DECIMAL(20, 8) NOT NULL DEFAULT 0.20 COMMENT '硬止损最大亏损比例',
    ADD COLUMN exit_pwin DECIMAL(20, 8) NOT NULL DEFAULT 0.70 COMMENT '模型失效退出 pWin 阈值',
    ADD COLUMN exit_safe_ratio DECIMAL(20, 8) NOT NULL DEFAULT 0.80 COMMENT '模型失效退出 safeRatio 阈值',
    ADD COLUMN exit_confirm_ticks INT NOT NULL DEFAULT 2 COMMENT '退出连续确认 tick 数',
    ADD COLUMN take_profit_delta1 DECIMAL(20, 8) NOT NULL DEFAULT 0.08 COMMENT '第一档止盈相对入场成交价价差',
    ADD COLUMN take_profit_sell_pct1 DECIMAL(20, 8) NOT NULL DEFAULT 0.50 COMMENT '第一档止盈卖出剩余仓位比例',
    ADD COLUMN take_profit_bid2 DECIMAL(20, 8) NOT NULL DEFAULT 0.93 COMMENT '第二档止盈 bestBid 阈值',
    ADD COLUMN take_profit_sell_pct2 DECIMAL(20, 8) NOT NULL DEFAULT 0.80 COMMENT '第二档止盈卖出剩余仓位比例',
    ADD COLUMN emergency_exit_on_model_flip BOOLEAN NOT NULL DEFAULT TRUE COMMENT '模型方向反转是否立即退出',
    ADD COLUMN emergency_exit_on_gap_flip BOOLEAN NOT NULL DEFAULT TRUE COMMENT 'gap 反转是否立即退出',
    ADD COLUMN exit_poll_interval_ms INT NOT NULL DEFAULT 3000 COMMENT '退出检查最小间隔毫秒',
    ADD COLUMN enable_wick_filter BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否启用影线反转过滤',
    ADD COLUMN wick_lookback_minutes INT NOT NULL DEFAULT 2 COMMENT '影线回看最近 N 根 1m K',
    ADD COLUMN wick_min_body_ratio DECIMAL(20, 8) NOT NULL DEFAULT 0.20 COMMENT '影线信号最小实体比例',
    ADD COLUMN wick_rejection_ratio DECIMAL(20, 8) NOT NULL DEFAULT 0.55 COMMENT '长影线占整根 K 线比例阈值',
    ADD COLUMN wick_ma_window INT NOT NULL DEFAULT 3 COMMENT '短均线窗口分钟数',
    ADD COLUMN wick_entry_block_score INT NOT NULL DEFAULT 70 COMMENT '入场禁止影线反转分数',
    ADD COLUMN wick_exit_score INT NOT NULL DEFAULT 75 COMMENT '影线软止损分数',
    ADD COLUMN wick_hold_profit_score INT NOT NULL DEFAULT 65 COMMENT '止盈暂缓顺势分数',
    ADD COLUMN wick_use_binance_volume BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否使用 Binance 成交量尖峰作为辅助',
    ADD COLUMN wick_volume_spike_ratio DECIMAL(20, 8) NOT NULL DEFAULT 1.50 COMMENT '成交量尖峰倍数';

UPDATE crypto_tail_strategy
SET entry_prob = 0.88,
    entry_edge = 0.04,
    barrier_min_market_prob = 0.72,
    max_entry_price = 0.90,
    sigma_scale = 1.25,
    bracket_entry_prob = GREATEST(bracket_entry_prob, 0.88),
    bracket_entry_edge = GREATEST(bracket_entry_edge, 0.04),
    bracket_max_entry_price = LEAST(bracket_max_entry_price, 0.90),
    tp1_price = 0.90,
    tp1_ratio = 0.50,
    tp2_price = 0.93,
    tp2_ratio = 0.80,
    stop_prob = 0.70,
    stop_price = 0.70
WHERE mode IN (1, 2);

ALTER TABLE crypto_tail_strategy_trigger
    ADD COLUMN entry_fill_price DECIMAL(20, 8) NULL COMMENT '入场实际均价',
    ADD COLUMN entry_model_side INT NULL COMMENT '入场模型方向 0=UP,1=DOWN',
    ADD COLUMN entry_pwin DECIMAL(20, 8) NULL COMMENT '入场 pWin 快照',
    ADD COLUMN entry_safe_ratio DECIMAL(20, 8) NULL COMMENT '入场 safeRatio 快照',
    ADD COLUMN entry_gap DECIMAL(30, 8) NULL COMMENT '入场 gap 快照',
    ADD COLUMN entry_remaining_seconds INT NULL COMMENT '入场剩余秒数快照',
    ADD COLUMN peak_bid DECIMAL(20, 8) NULL COMMENT '持仓后最高可卖 bestBid',
    ADD COLUMN exit_confirm_reason VARCHAR(40) NULL COMMENT '当前退出确认原因',
    ADD COLUMN exit_confirm_count INT NOT NULL DEFAULT 0 COMMENT '退出连续确认次数',
    ADD COLUMN last_exit_check_at BIGINT NULL COMMENT '最近退出检查时间(ms)',
    ADD COLUMN last_exit_attempt_at BIGINT NULL COMMENT '最近退出下单尝试时间(ms)';

UPDATE crypto_tail_strategy_trigger
SET entry_fill_price = CASE
        WHEN filled_size IS NOT NULL AND filled_size > 0 AND filled_amount IS NOT NULL THEN filled_amount / filled_size
        ELSE NULL
    END,
    remaining_size = CASE
        WHEN mode IN (1, 2) AND status = 'success' AND filled_size IS NOT NULL AND filled_size > 0 THEN COALESCE(remaining_size, filled_size)
        ELSE remaining_size
    END,
    exit_status = CASE
        WHEN mode IN (1, 2) AND status = 'success' AND filled_size IS NOT NULL AND filled_size > 0 AND exit_status = 'NONE' THEN 'OPEN'
        ELSE exit_status
    END
WHERE mode IN (1, 2);
