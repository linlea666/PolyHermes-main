-- crypto-tail 障碍模式分数 Kelly 动态仓位：默认关闭，向后兼容（关闭时行为同原固定金额）
ALTER TABLE crypto_tail_strategy
    ADD COLUMN kelly_enabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT '分数Kelly动态仓位开关(0=固定金额)' AFTER ewma_lambda,
    ADD COLUMN kelly_fraction DECIMAL(20, 8) NOT NULL DEFAULT 0.25 COMMENT 'Kelly分数(0~1)，下注=本金×kellyFraction×f*' AFTER kelly_enabled;
