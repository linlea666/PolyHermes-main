-- crypto-tail 触发记录新增真实成交字段（修复 FAK 成交会计：以下单响应的实际成交量/金额为准，避免幽灵成交与造假 PnL）
ALTER TABLE crypto_tail_strategy_trigger
    ADD COLUMN filled_size DECIMAL(20, 8) NULL COMMENT '实际成交份额(shares)，来自下单响应 takingAmount' AFTER amount_usdc,
    ADD COLUMN filled_amount DECIMAL(20, 8) NULL COMMENT '实际成交金额(USDC)，来自下单响应 makingAmount' AFTER filled_size,
    ADD COLUMN order_type VARCHAR(20) NULL COMMENT '订单类型：FAK/GTC/GTC_POST_ONLY' AFTER filled_amount;
