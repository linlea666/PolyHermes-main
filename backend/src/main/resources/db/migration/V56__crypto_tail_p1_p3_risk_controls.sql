-- ============================================
-- V56: 加密尾盘策略 - P1/P2/P3 盘口质量、移动止损、日内风控
-- ============================================

ALTER TABLE crypto_tail_strategy
    ADD COLUMN max_entry_spread DECIMAL(20, 8) NOT NULL DEFAULT 0.03 COMMENT '入场允许最大盘口spread(bestAsk-bestBid)',
    ADD COLUMN max_orderbook_age_ms INT NOT NULL DEFAULT 3000 COMMENT '订单簿报价最大年龄(ms)',
    ADD COLUMN max_price_age_ms INT NOT NULL DEFAULT 3000 COMMENT '结算同源价源最大年龄(ms)',
    ADD COLUMN min_exit_bid_depth_usdc DECIMAL(20, 8) NOT NULL DEFAULT 2.00 COMMENT 'TP类退出最小bid深度USDC',
    ADD COLUMN max_exit_spread DECIMAL(20, 8) NOT NULL DEFAULT 0.05 COMMENT 'TP类退出最大盘口spread',
    ADD COLUMN enable_trailing_stop BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否启用移动止损',
    ADD COLUMN trailing_start_delta DECIMAL(20, 8) NOT NULL DEFAULT 0.08 COMMENT '移动止损启动浮盈价差',
    ADD COLUMN trailing_drawdown DECIMAL(20, 8) NOT NULL DEFAULT 0.06 COMMENT '移动止损从peakBid回撤价差',
    ADD COLUMN trailing_sell_pct DECIMAL(20, 8) NOT NULL DEFAULT 1.00 COMMENT '移动止损卖出剩余仓位比例',
    ADD COLUMN max_orders_per_day INT NULL COMMENT '单日最大成功入场订单数',
    ADD COLUMN max_consecutive_losses INT NULL COMMENT '最大连续已结算亏损笔数',
    ADD COLUMN pause_after_loss_minutes INT NOT NULL DEFAULT 0 COMMENT '亏损结算后暂停入场分钟数';
