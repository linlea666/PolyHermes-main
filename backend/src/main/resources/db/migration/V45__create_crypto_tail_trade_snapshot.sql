-- ============================================
-- V45: 加密尾盘策略 - 单笔成交全链路分析快照（障碍模式）
-- 一笔进场一行，随生命周期(下单→成交→结算)由异步投影监听器 upsert，强类型列便于回测/复盘聚合。
-- 与决策事件流(V44)互补：事件流给时间线，本表给回测单元。与交易主流程解耦。
-- ============================================
CREATE TABLE IF NOT EXISTS crypto_tail_trade_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '快照ID',
    strategy_id BIGINT NOT NULL COMMENT '策略ID',
    trigger_id BIGINT DEFAULT NULL COMMENT '关联触发记录ID',
    period_start_unix BIGINT NOT NULL COMMENT '周期起点 Unix 秒',
    correlation_id VARCHAR(64) NOT NULL COMMENT '关联ID(strategyId-period)',
    market_slug VARCHAR(64) DEFAULT NULL COMMENT '市场 slug 前缀',
    condition_id VARCHAR(66) DEFAULT NULL COMMENT 'Polymarket conditionId',
    outcome_index INT DEFAULT NULL COMMENT '方向: 0=Up, 1=Down',
    interval_seconds INT NOT NULL DEFAULT 0 COMMENT '周期长度(秒)',

    -- 进场信号
    open_price DECIMAL(30,8) DEFAULT NULL COMMENT '周期开盘价',
    entry_mark_price DECIMAL(30,8) DEFAULT NULL COMMENT '进场标记价(币安当前价)',
    entry_gap DECIMAL(30,8) DEFAULT NULL COMMENT '进场 gap=close-open',
    sigma_per_sqrt_s DECIMAL(30,12) DEFAULT NULL COMMENT '每√秒波动率',
    p_win DECIMAL(12,8) DEFAULT NULL COMMENT '终值胜率',
    safe_ratio DECIMAL(20,8) DEFAULT NULL COMMENT '安全比 |gap|/(σ√t)',
    model_side INT DEFAULT NULL COMMENT '模型方向',
    remaining_seconds_at_entry BIGINT DEFAULT NULL COMMENT '进场时距结算剩余秒',

    -- 进场盘口
    best_bid DECIMAL(12,8) DEFAULT NULL COMMENT '进场 bestBid',
    best_ask DECIMAL(12,8) DEFAULT NULL COMMENT '进场 bestAsk',
    mid_price DECIMAL(12,8) DEFAULT NULL COMMENT '盘口中值',
    effective_cost DECIMAL(12,8) DEFAULT NULL COMMENT '有效成本',
    entry_edge DECIMAL(12,8) DEFAULT NULL COMMENT '扣费 edge=pWin-成本',

    -- 进场阈值快照
    entry_prob_threshold DECIMAL(12,8) DEFAULT NULL COMMENT 'pWin 阈值',
    entry_edge_threshold DECIMAL(12,8) DEFAULT NULL COMMENT 'edge 阈值',
    barrier_min_market_prob DECIMAL(12,8) DEFAULT NULL COMMENT '市场概率下限',
    sigma_scale DECIMAL(12,8) DEFAULT NULL COMMENT 'σ 校准系数',
    max_entry_price DECIMAL(12,8) DEFAULT NULL COMMENT '最高进场价',
    cost_buffer DECIMAL(12,8) DEFAULT NULL COMMENT '成本缓冲',

    -- 订单
    order_type VARCHAR(20) DEFAULT NULL COMMENT '订单类型(FAK 限价IOC)',
    target_price DECIMAL(12,8) DEFAULT NULL COMMENT '目标(限价)价格',
    requested_amount DECIMAL(20,8) DEFAULT NULL COMMENT '下单金额USDC',
    submit_ts BIGINT DEFAULT NULL COMMENT '下单时间(进场时间)',

    -- 成交
    fill_status VARCHAR(20) DEFAULT NULL COMMENT '成交状态: success/fail',
    fill_price DECIMAL(12,8) DEFAULT NULL COMMENT '成交价',
    fill_size DECIMAL(30,8) DEFAULT NULL COMMENT '成交数量',
    fill_amount DECIMAL(20,8) DEFAULT NULL COMMENT '成交金额USDC',
    slippage DECIMAL(12,8) DEFAULT NULL COMMENT '滑点=成交价-目标价',
    order_id VARCHAR(128) DEFAULT NULL COMMENT '订单号',
    exec_error TEXT DEFAULT NULL COMMENT '执行错误',

    -- 结算
    settled TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已结算',
    winner_outcome_index INT DEFAULT NULL COMMENT '赢家方向',
    won TINYINT(1) DEFAULT NULL COMMENT '是否获胜',
    realized_pnl DECIMAL(20,8) DEFAULT NULL COMMENT '已实现盈亏',
    settle_ts BIGINT DEFAULT NULL COMMENT '结算时间',
    hold_seconds BIGINT DEFAULT NULL COMMENT '持仓时长(秒)',
    final_open DECIMAL(30,8) DEFAULT NULL COMMENT '结算周期最终开盘价',
    final_close DECIMAL(30,8) DEFAULT NULL COMMENT '结算周期最终收盘价',
    final_gap DECIMAL(30,8) DEFAULT NULL COMMENT '最终 gap',
    reversed TINYINT(1) DEFAULT NULL COMMENT '是否相对进场方向反转',
    settle_source VARCHAR(20) DEFAULT NULL COMMENT '结算来源: ACTIVITY_API/FALLBACK',

    -- 归因
    loss_reason VARCHAR(20) DEFAULT NULL COMMENT '亏损归因: REVERSAL/NEVER_FAVORED/UNFILLED/UNKNOWN',
    pwin_bucket INT DEFAULT NULL COMMENT 'pWin 可靠性分箱(0~19, 5%一箱)',

    created_at BIGINT NOT NULL COMMENT '创建时间',
    updated_at BIGINT NOT NULL COMMENT '更新时间',

    UNIQUE KEY uk_ctts_strategy_period (strategy_id, period_start_unix),
    INDEX idx_ctts_strategy (strategy_id, created_at),
    INDEX idx_ctts_settled (strategy_id, settled),
    INDEX idx_ctts_pwin_bucket (strategy_id, pwin_bucket),
    INDEX idx_ctts_trigger (trigger_id),
    CONSTRAINT fk_ctts_strategy FOREIGN KEY (strategy_id) REFERENCES crypto_tail_strategy(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='加密尾盘策略单笔成交全链路分析快照';
