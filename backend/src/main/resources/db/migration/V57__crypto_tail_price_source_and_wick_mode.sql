ALTER TABLE crypto_tail_strategy
    ADD COLUMN min_remaining_seconds INT NOT NULL DEFAULT 90 COMMENT '概率模式最小入场剩余秒数',
    ADD COLUMN max_remaining_seconds INT NOT NULL DEFAULT 420 COMMENT '概率模式最大入场剩余秒数',
    ADD COLUMN wick_filter_mode VARCHAR(8) NOT NULL DEFAULT 'SHADOW' COMMENT '影线过滤模式 OFF/SHADOW/ENFORCE';
