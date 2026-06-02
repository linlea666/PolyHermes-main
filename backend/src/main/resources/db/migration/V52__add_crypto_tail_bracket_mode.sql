-- ============================================
-- V52: 加密尾盘策略 - 概率阶梯止盈模式 (BRACKET_DYNAMIC)
-- 引入 mode 枚举字段统一承载三种模式（0=LEGACY_SPREAD, 1=BARRIER_HOLD, 2=BRACKET_DYNAMIC）
-- 新增 14 个 bracket 专属配置字段；trigger 表增加仓位状态机字段；新建 exit 子表记录多笔卖出
-- 历史数据迁移：barrier_enabled=1 → mode=1，否则 mode=0；旧 barrier_enabled 字段保留以兼容
-- ============================================

-- 1) 策略表新增模式字段与阶梯止盈配置
ALTER TABLE crypto_tail_strategy
    ADD COLUMN mode TINYINT NOT NULL DEFAULT 0 COMMENT '交易模式: 0=LEGACY_SPREAD旧价差, 1=BARRIER_HOLD障碍持有, 2=BRACKET_DYNAMIC概率阶梯止盈',
    ADD COLUMN bracket_entry_prob DECIMAL(20, 8) NOT NULL DEFAULT 0.80 COMMENT '阶梯模式进场pWin下限',
    ADD COLUMN bracket_entry_edge DECIMAL(20, 8) NOT NULL DEFAULT 0.04 COMMENT '阶梯模式进场扣费EV下限',
    ADD COLUMN bracket_max_entry_price DECIMAL(20, 8) NOT NULL DEFAULT 0.90 COMMENT '阶梯模式入场最高买价',
    ADD COLUMN tp1_price DECIMAL(20, 8) NOT NULL DEFAULT 0.90 COMMENT '止盈1: bestBid价格阈值',
    ADD COLUMN tp1_ratio DECIMAL(20, 8) NOT NULL DEFAULT 0.50 COMMENT '止盈1: 卖出剩余仓位比例 0~1',
    ADD COLUMN tp1_hold_pwin DECIMAL(20, 8) NOT NULL DEFAULT 0.95 COMMENT '止盈1跳过条件: pWin>=此值则不卖,继续持有',
    ADD COLUMN tp2_price DECIMAL(20, 8) NOT NULL DEFAULT 0.95 COMMENT '止盈2: bestBid价格阈值',
    ADD COLUMN tp2_ratio DECIMAL(20, 8) NOT NULL DEFAULT 1.00 COMMENT '止盈2: 卖出剩余仓位比例 0~1',
    ADD COLUMN tp2_hold_pwin DECIMAL(20, 8) NOT NULL DEFAULT 0.99 COMMENT '止盈2跳过条件: pWin>=此值则不卖',
    ADD COLUMN hold_to_settle_pwin DECIMAL(20, 8) NOT NULL DEFAULT 0.97 COMMENT '持有到结算的pWin阈值',
    ADD COLUMN hold_to_settle_seconds INT NOT NULL DEFAULT 30 COMMENT '允许持有到结算的剩余秒数阈值',
    ADD COLUMN stop_prob DECIMAL(20, 8) NOT NULL DEFAULT 0.55 COMMENT '止损pWin阈值: pWin<=此值触发FAK平仓',
    ADD COLUMN stop_price DECIMAL(20, 8) NOT NULL DEFAULT 0.70 COMMENT '止损价格阈值: bestBid<=此值触发FAK平仓',
    ADD COLUMN force_exit_before_settle_seconds INT NOT NULL DEFAULT 15 COMMENT '距结算N秒未触发任何exit则强制平仓',
    ADD COLUMN exit_order_type VARCHAR(8) NOT NULL DEFAULT 'FAK' COMMENT '退出订单类型: FAK吃单 / MAKER挂单';

-- 2) 数据迁移: 旧 barrier_enabled=1 映射为 mode=1; 其余保持 0
UPDATE crypto_tail_strategy
SET mode = CASE WHEN barrier_enabled = 1 THEN 1 ELSE 0 END;

-- 3) 触发记录表扩仓位状态机字段
ALTER TABLE crypto_tail_strategy_trigger
    ADD COLUMN mode TINYINT NOT NULL DEFAULT 0 COMMENT '冗余冻结当时模式: 0=LEGACY_SPREAD,1=BARRIER_HOLD,2=BRACKET_DYNAMIC',
    ADD COLUMN remaining_size DECIMAL(20, 8) NULL COMMENT '部分卖出后剩余shares; NULL=未填充, 0=已全部退出',
    ADD COLUMN exit_status VARCHAR(20) NOT NULL DEFAULT 'NONE' COMMENT '退出状态: NONE=不适用(非bracket), OPEN=持仓中, PARTIAL_EXIT=部分卖出, FULLY_EXITED=全部退出, HELD_TO_SETTLE=持有到结算';

-- 4) 退出明细子表
CREATE TABLE IF NOT EXISTS crypto_tail_strategy_exit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '退出明细ID',
    trigger_id BIGINT NOT NULL COMMENT '关联触发记录ID',
    strategy_id BIGINT NOT NULL COMMENT '策略ID(冗余,加速查询)',
    exit_kind VARCHAR(16) NOT NULL COMMENT '退出类型: TP1/TP2/STOP/FORCE/SETTLE',
    target_size DECIMAL(20, 8) NOT NULL COMMENT '本次目标卖出shares',
    filled_size DECIMAL(20, 8) NULL COMMENT '实际成交shares; NULL=未知/未成交',
    filled_amount DECIMAL(20, 8) NULL COMMENT '实际成交USDC(makingAmount of SELL)',
    exit_price DECIMAL(20, 8) NULL COMMENT '挂单价/期望成交价',
    order_id VARCHAR(128) NULL COMMENT 'CLOB订单ID',
    order_type VARCHAR(20) NULL COMMENT '订单类型: FAK/GTC/GTC_POST_ONLY',
    status VARCHAR(20) NOT NULL DEFAULT 'pending' COMMENT '状态: pending/success/failed/cancelled/unfilled',
    pwin_at_decision DECIMAL(20, 8) NULL COMMENT '决策时pWin快照',
    best_bid_at_decision DECIMAL(20, 8) NULL COMMENT '决策时bestBid快照',
    remaining_seconds INT NULL COMMENT '决策时剩余秒数快照',
    decision_reason VARCHAR(500) NULL COMMENT '决策原因(便于复盘)',
    fail_reason VARCHAR(500) NULL COMMENT '失败原因',
    created_at BIGINT NOT NULL COMMENT '创建时间(ms)',
    settled_at BIGINT NULL COMMENT '终态写入时间(ms)',
    UNIQUE KEY uk_order_id (order_id),
    INDEX idx_trigger_id (trigger_id),
    INDEX idx_strategy_status (strategy_id, status),
    INDEX idx_status_created (status, created_at),
    FOREIGN KEY (trigger_id) REFERENCES crypto_tail_strategy_trigger(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='加密尾盘策略-阶梯止盈退出明细表';

-- 5) trigger 表回填: 同步 mode 字段(从策略表), 旧记录保持 exit_status='NONE'
UPDATE crypto_tail_strategy_trigger t
JOIN crypto_tail_strategy s ON t.strategy_id = s.id
SET t.mode = s.mode;
