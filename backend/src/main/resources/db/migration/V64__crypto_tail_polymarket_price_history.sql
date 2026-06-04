-- V64: Polymarket 历史价格采集（PoC）原始缓存
--
-- 设计要点：
--   - 缓存从 Polymarket CLOB /prices-history 拉取的历史赔率点，避免重复请求；
--   - 反转统计聚合表 crypto_tail_reversal_stat 已支持 data_source 维度，POLYMARKET 行 diff_sigma_bucket='ANY'、odds_bucket 为真实赔率桶；
--   - 本表仅服务 PoC 复盘/排查，PoC 失败不影响交易主链路。

CREATE TABLE IF NOT EXISTS crypto_tail_polymarket_price_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    -- CLOB token id（Up/Yes 代币）
    token_id VARCHAR(80) NOT NULL,
    -- 所属周期起点 unix 秒（对齐 5m/15m）
    period_start_unix BIGINT NOT NULL,
    -- 采样点时间 unix 秒
    t_unix BIGINT NOT NULL,
    -- 该点 Up 代币价格 0~1
    price DECIMAL(20, 8) NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_ct_pm_price UNIQUE (token_id, t_unix)
);

CREATE INDEX idx_ct_pm_price_period
    ON crypto_tail_polymarket_price_history (period_start_unix);
