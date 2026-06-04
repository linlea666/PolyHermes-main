-- V69: TAIL_DIFF 历史统计数据源可配置（tail_diff_stats_data_source）
--
-- 背景：历史反转统计回填同时支持 BINANCE 与 POLYMARKET 两套数据源，
--   但实时决策的 RealTailReversalStatsLookup 此前硬编码 BINANCE，POLYMARKET 回填数据永不被查询。
--   新增策略级数据源选择列，由 TailReversalStatsLookup.Query.dataSource 驱动桶查询；默认 BINANCE 保持原行为。

ALTER TABLE crypto_tail_strategy
    ADD COLUMN tail_diff_stats_data_source VARCHAR(16) NOT NULL DEFAULT 'BINANCE';
