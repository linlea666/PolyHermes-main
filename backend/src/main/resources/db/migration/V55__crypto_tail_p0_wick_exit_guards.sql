-- ============================================
-- V55: 加密尾盘策略 - P0 影线质量与 TP1 暂缓保护
-- ============================================

ALTER TABLE crypto_tail_strategy
    ADD COLUMN wick_min_ticks_per_candle INT NOT NULL DEFAULT 5 COMMENT '影线信号单根1m K最小RTDS/Chainlink样本数',
    ADD COLUMN wick_min_range_sigma_ratio DECIMAL(20, 8) NOT NULL DEFAULT 0.25 COMMENT '影线信号最小K线range/1m sigma比例',
    ADD COLUMN wick_close_position_up_max DECIMAL(20, 8) NOT NULL DEFAULT 0.35 COMMENT 'UP反转长上影收盘位置上限(0=低点,1=高点)',
    ADD COLUMN wick_close_position_down_min DECIMAL(20, 8) NOT NULL DEFAULT 0.65 COMMENT 'DOWN反转长下影收盘位置下限(0=低点,1=高点)',
    ADD COLUMN max_hold_tp1_delay_seconds INT NOT NULL DEFAULT 45 COMMENT 'TP1顺势暂缓最长秒数',
    ADD COLUMN hold_tp1_peak_drawdown DECIMAL(20, 8) NOT NULL DEFAULT 0.03 COMMENT 'TP1暂缓期间从最高bestBid回撤触发执行';

ALTER TABLE crypto_tail_strategy_trigger
    ADD COLUMN tp1_hold_started_at BIGINT NULL COMMENT 'TP1顺势暂缓开始时间(ms)';
