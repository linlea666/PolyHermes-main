-- crypto-tail 障碍模式费用/返佣模型：扣费 EV 与净 EV 归因（默认 0，向后兼容；gasless 时 gas_cost_usdc=0）
ALTER TABLE crypto_tail_strategy
    ADD COLUMN taker_fee_bps INT NOT NULL DEFAULT 0 COMMENT 'taker 手续费(基点bps)，扣费EV用' AFTER max_concurrent_positions,
    ADD COLUMN maker_rebate_bps INT NOT NULL DEFAULT 0 COMMENT 'maker 返佣(基点bps)，降低有效成本' AFTER taker_fee_bps,
    ADD COLUMN gas_cost_usdc DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '单笔gas成本USDC，gasless时为0' AFTER maker_rebate_bps;
