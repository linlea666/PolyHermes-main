-- V71: Polymarket 周期采集状态（增量回填 / 负缓存）
--
-- 设计要点：
--   - 解决 Polymarket 反转回填"重复采集"根因：原价格缓存键为 token_id，而拿 token_id 必须先调 Gamma，
--     导致即使价格已缓存，每个周期仍会重复打一次 Gamma；本表按周期记录解析状态，实现真正的周期级跳过。
--   - 已结算周期数据不可变：OK 永久缓存（零网络复用）；SLUG_NOT_FOUND/HISTORY_EMPTY 写永久负缓存（零网络跳过）；
--     FETCH_ERROR（网络/限流）不落库，下次自动重试。
--   - slug 全局唯一（"{slug_prefix}-{period_start_unix}"）；slug_prefix + period_start_unix 支持按窗口范围批量预载。
--   - 仅服务反转研究 PoC，失败不影响交易主链路。

CREATE TABLE IF NOT EXISTS crypto_tail_pm_period_status (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    -- 全局唯一 slug："{slug_prefix}-{period_start_unix}"
    slug VARCHAR(160) NOT NULL,
    -- 市场 slug 前缀，如 "btc-updown-5m"（用于按窗口范围批量预载）
    slug_prefix VARCHAR(80) NOT NULL,
    -- 周期粒度秒（300/900）
    interval_seconds INT NOT NULL,
    -- 周期起点 unix 秒（对齐周期）
    period_start_unix BIGINT NOT NULL,
    -- 采集结果状态：RESOLVED / SLUG_NOT_FOUND / HISTORY_EMPTY
    status VARCHAR(24) NOT NULL,
    -- RESOLVED 时记录 Up/Yes 代币 id，复用时据此从价格缓存读回（零网络）
    up_token_id VARCHAR(80) NULL,
    created_at BIGINT NOT NULL DEFAULT 0,
    updated_at BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_ct_pm_period_slug UNIQUE (slug)
);

CREATE INDEX idx_ct_pm_period_window
    ON crypto_tail_pm_period_status (slug_prefix, period_start_unix);
