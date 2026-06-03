-- V58: crypto-tail 多策略并发保护
ALTER TABLE crypto_tail_strategy
    ADD COLUMN allow_duplicate_market_position BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_cts_account_market_enabled
    ON crypto_tail_strategy (account_id, market_slug_prefix, enabled);

CREATE INDEX idx_ctst_period_outcome_status
    ON crypto_tail_strategy_trigger (period_start_unix, outcome_index, status, resolved);
