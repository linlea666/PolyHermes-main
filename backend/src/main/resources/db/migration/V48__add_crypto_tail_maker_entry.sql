-- crypto-tail 障碍模式 maker 挂单进场（GTC+postOnly@bid+offset）+ 订单生命周期状态机
-- 默认 entry_order_type='FAK' 保持原吃单行为，向后兼容
ALTER TABLE crypto_tail_strategy
    ADD COLUMN entry_order_type VARCHAR(8) NOT NULL DEFAULT 'FAK' COMMENT '进场订单类型: FAK吃单 / MAKER挂单' AFTER gas_cost_usdc,
    ADD COLUMN maker_price_offset DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT 'maker 挂单相对bestBid价格偏移(可负)' AFTER entry_order_type,
    ADD COLUMN maker_cancel_before_settle_seconds INT NOT NULL DEFAULT 5 COMMENT '距结算多少秒未成交触发撤单决策' AFTER maker_price_offset,
    ADD COLUMN maker_fallback_taker TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'maker到期未成交是否回退FAK吃单' AFTER maker_cancel_before_settle_seconds;

-- 触发记录新增 token_id，maker 生命周期对账/回退 FAK 时复用，避免依赖内存上下文
ALTER TABLE crypto_tail_strategy_trigger
    ADD COLUMN token_id VARCHAR(128) NULL COMMENT '进场outcome对应tokenId，maker对账/回退用' AFTER order_type;
