-- ============================================
-- V44: 加密尾盘策略 - 全链路决策日志（append-only）
-- 记录从策略评估(gap/σ/pWin/EV/各闸)到下单、结算的完整链条；与下单/结算主流程解耦
-- ============================================
CREATE TABLE IF NOT EXISTS crypto_tail_decision_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '决策事件ID',
    strategy_id BIGINT NOT NULL COMMENT '策略ID',
    period_start_unix BIGINT NOT NULL COMMENT '周期起点 Unix 秒',
    correlation_id VARCHAR(64) NOT NULL COMMENT '一次评估会话关联ID',
    event_type VARCHAR(40) NOT NULL COMMENT '事件类型: EVAL_STARTED/GATE_PASSED/GATE_FAILED/ORDER_SUBMITTED/ORDER_RESULT/SETTLED',
    gate_name VARCHAR(40) DEFAULT NULL COMMENT '闸名: PRICE_RANGE/TIME_WINDOW/SPREAD/DIRECTION/PWIN/MARKET_PROB/EV/RISK_DAILY_LOSS/RISK_CONCURRENCY',
    passed TINYINT(1) DEFAULT NULL COMMENT '该闸是否通过',
    reason TEXT DEFAULT NULL COMMENT '原因/说明',
    payload_json TEXT DEFAULT NULL COMMENT '决策快照 JSON(gap/sigma/pWin/edge/bestBid/bestAsk 等)',
    outcome_index INT DEFAULT NULL COMMENT '方向: 0=Up, 1=Down',
    trigger_id BIGINT DEFAULT NULL COMMENT '关联触发记录ID(下单/结算阶段有值)',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    INDEX idx_ctde_strategy_period (strategy_id, period_start_unix, created_at),
    INDEX idx_ctde_correlation (correlation_id),
    INDEX idx_ctde_created (created_at),
    INDEX idx_ctde_trigger (trigger_id),
    CONSTRAINT fk_ctde_strategy FOREIGN KEY (strategy_id) REFERENCES crypto_tail_strategy(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='加密尾盘策略全链路决策日志';
