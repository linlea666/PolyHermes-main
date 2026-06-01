-- ============================================
-- V41: 创建 Leader 池表
-- ============================================
CREATE TABLE IF NOT EXISTS copy_trading_leader_pool (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'Leader 池项ID',
    leader_id BIGINT NOT NULL COMMENT 'Leader ID',
    status VARCHAR(20) NOT NULL DEFAULT 'CANDIDATE' COMMENT '池子状态',
    source VARCHAR(50) NOT NULL DEFAULT 'MANUAL' COMMENT '来源',
    source_rank INT DEFAULT NULL COMMENT '来源排名',
    score DECIMAL(20, 8) DEFAULT NULL COMMENT '来源评分',
    reason TEXT DEFAULT NULL COMMENT '加入原因',
    notes TEXT DEFAULT NULL COMMENT '备注',
    suggested_fixed_amount DECIMAL(20, 8) NOT NULL DEFAULT 1.00000000 COMMENT '建议固定跟单金额',
    suggested_max_daily_orders INT NOT NULL DEFAULT 10 COMMENT '建议每日最大订单数',
    suggested_max_daily_loss DECIMAL(20, 8) NOT NULL DEFAULT 5.00000000 COMMENT '建议每日最大亏损',
    suggested_min_price DECIMAL(20, 8) DEFAULT 0.10000000 COMMENT '建议最低价格',
    suggested_max_price DECIMAL(20, 8) DEFAULT 0.80000000 COMMENT '建议最高价格',
    suggested_max_position_value DECIMAL(20, 8) DEFAULT 5.00000000 COMMENT '建议最大持仓价值',
    last_reviewed_at BIGINT DEFAULT NULL COMMENT '最后复核时间',
    last_promoted_at BIGINT DEFAULT NULL COMMENT '最后晋升/试跟时间',
    cooldown_until BIGINT DEFAULT NULL COMMENT '冷却截止时间',
    locked TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否锁定，避免后续自动任务修改',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    updated_at BIGINT NOT NULL COMMENT '更新时间',
    UNIQUE KEY uk_leader_pool_leader_id (leader_id),
    INDEX idx_leader_pool_status (status),
    INDEX idx_leader_pool_source (source),
    FOREIGN KEY (leader_id) REFERENCES copy_trading_leaders(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Copy Trading Leader 池';
