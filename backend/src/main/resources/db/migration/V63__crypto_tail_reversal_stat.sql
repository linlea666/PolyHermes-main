-- V63: 历史反转统计插件（Tail Diff 模型概率来源之一）
--
-- 设计要点：
--   - 聚合表，每行 = 一个分桶（coin × interval × 领先方向 × diff_sigma 桶 × 赔率桶 × 剩余时间桶 × 回溯天数 × 数据源）的反转统计；
--   - model_prob = 领先方向"维持到结算"的历史概率 = 1 - reversed_count / sample_count；
--   - 数据源 data_source：BINANCE（基于 1m K 线重建路径，odds_bucket 恒为 ANY）；P4 可追加 POLYMARKET 维度；
--   - RealTailReversalStatsLookup 查询本表为 ScoreEngine 提供 modelProb；样本不足时由 ScoreEngine 回退 BarrierProbability；
--   - 回填任务幂等：按唯一键 upsert，重复回填覆盖最新统计。

CREATE TABLE IF NOT EXISTS crypto_tail_reversal_stat (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    -- 币种：BTC / ETH（由 slug 解析）
    coin VARCHAR(16) NOT NULL,
    -- 周期：300 / 900
    interval_seconds INT NOT NULL,
    -- 观测点领先方向：0=Up 领先，1=Down 领先
    outcome_index TINYINT NOT NULL,
    -- diff_sigma 分桶名（与 TailDiffBuckets.diffSigmaBucket 一致），如 "1.5_2.0"
    diff_sigma_bucket VARCHAR(32) NOT NULL,
    -- 赔率分桶名；BINANCE 数据源无市场赔率，恒为 'ANY'
    odds_bucket VARCHAR(32) NOT NULL DEFAULT 'ANY',
    -- 剩余时间分桶名（秒），如 "60_120"
    remaining_bucket VARCHAR(32) NOT NULL,
    -- 回溯天数：180 / 365 等
    lookback_days INT NOT NULL,
    -- 数据源：BINANCE / POLYMARKET
    data_source VARCHAR(16) NOT NULL DEFAULT 'BINANCE',
    -- 样本数（落入该桶的观测点总数）
    sample_count INT NOT NULL DEFAULT 0,
    -- 反转数（领先方向最终被结算反转的观测点数）
    reversed_count INT NOT NULL DEFAULT 0,
    -- 维持到结算概率 0~1 = 1 - reversed_count/sample_count
    model_prob DECIMAL(20, 8) NOT NULL DEFAULT 0,
    -- 最近一次聚合计算时间（毫秒）
    computed_at BIGINT NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL DEFAULT 0,
    updated_at BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_ct_reversal_bucket UNIQUE (
        coin, interval_seconds, outcome_index, diff_sigma_bucket,
        odds_bucket, remaining_bucket, lookback_days, data_source
    )
);

CREATE INDEX idx_ct_reversal_lookup
    ON crypto_tail_reversal_stat (coin, interval_seconds, outcome_index, lookback_days, data_source);
