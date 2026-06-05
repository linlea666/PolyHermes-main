-- V74: TAIL_DIFF 周期生命周期汇总表
--
-- 目的：把分散在 crypto_tail_decision_event 的每周期全链路决策聚合成一条可读记录，并在周期结束后
--       并入官方结算结果（Chainlink open/close，与 Polymarket 结算同源），用于方向准确率回测、
--       是否成交、是否中途翻转方向等复盘。仅作观测/回测，主交易链路不依赖本表。
--
-- 兼容：纯新增表，对既有数据与所有模式零影响。

CREATE TABLE crypto_tail_period_summary (
    id BIGINT NOT NULL AUTO_INCREMENT,
    strategy_id BIGINT NOT NULL,
    period_start_unix BIGINT NOT NULL,
    period_end_unix BIGINT NOT NULL,
    market_slug VARCHAR(128) NULL,
    first_chosen_outcome_index INT NULL,
    last_chosen_outcome_index INT NULL,
    direction_flip_count INT NOT NULL DEFAULT 0,
    best_score INT NOT NULL DEFAULT 0,
    dominant_veto VARCHAR(64) NULL,
    score_event_count INT NOT NULL DEFAULT 0,
    skip_event_count INT NOT NULL DEFAULT 0,
    buy_event_count INT NOT NULL DEFAULT 0,
    traded TINYINT(1) NOT NULL DEFAULT 0,
    trigger_id BIGINT NULL,
    official_open DECIMAL(30, 8) NULL,
    official_close DECIMAL(30, 8) NULL,
    official_gap DECIMAL(30, 8) NULL,
    settled_winner_outcome_index INT NULL,
    direction_correct TINYINT(1) NULL,
    realized_pnl DECIMAL(30, 8) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    settled_at BIGINT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ct_period_summary (strategy_id, period_start_unix),
    KEY idx_ct_period_summary_status (status, period_end_unix),
    KEY idx_ct_period_summary_strategy_period (strategy_id, period_start_unix)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
