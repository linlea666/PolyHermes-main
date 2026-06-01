-- ============================================
-- V43: 加密尾盘策略 - 障碍（终值概率）模式配置
-- 全部带默认值，旧策略 barrier_enabled=0 行为不变
-- ============================================
ALTER TABLE crypto_tail_strategy
    ADD COLUMN barrier_enabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT '障碍模式开关: 0=关(走旧逻辑), 1=开',
    ADD COLUMN entry_prob DECIMAL(20, 8) NOT NULL DEFAULT 0.95 COMMENT '进场胜率阈值 pWin>=此值, 0~1',
    ADD COLUMN entry_edge DECIMAL(20, 8) NOT NULL DEFAULT 0.02 COMMENT '扣费 EV 边际阈值 edge=pWin-有效成本>=此值',
    ADD COLUMN max_entry_price DECIMAL(20, 8) NOT NULL DEFAULT 0.99 COMMENT '障碍模式最高买入限价 0~1',
    ADD COLUMN cost_buffer DECIMAL(20, 8) NOT NULL DEFAULT 0.02 COMMENT 'bestAsk 缺失回退成本缓冲',
    ADD COLUMN barrier_min_market_prob DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '市场隐含概率下限 bestBid>=此值, 0=关',
    ADD COLUMN sigma_scale DECIMAL(20, 8) NOT NULL DEFAULT 1.2533 COMMENT 'σ 校准系数, 默认 √(π/2)',
    ADD COLUMN daily_loss_limit_usdc DECIMAL(20, 8) NULL COMMENT '当日已实现亏损熔断阈值 USDC, NULL/<=0=关',
    ADD COLUMN max_concurrent_positions INT NULL COMMENT '最大并发未结算敞口笔数, NULL/<=0=关';
