-- V73: TAIL_DIFF 默认值调优（与 RTDS 价源节奏 / 评分曲线对齐）
--
-- 背景：实盘 74 个周期 0 成交，根因之一是默认阈值与价源/评分曲线不匹配：
--   - RTDS 价源新鲜度阈值为 30s、约每秒一推但有抖动；策略 tail_diff_max_price_age_ms 默认仅 2000ms，
--     叠加周期边界 WS 重连，导致 priceAge>2s 频繁触发 PRICE_STALE 硬否决。
--   - tail_diff_sigma_score_multiple=3.0 时满分 σ=5.4，2~2.7σ 真实可买机会评分偏低。
--
-- 处理：仅调整列 DEFAULT（影响后续新建策略）。已存在的策略保留各自已存值，由用户在前端按推荐配置自行调整，
--       避免静默改写在用策略行为（遵循「保守修改」）。

ALTER TABLE crypto_tail_strategy
    ALTER COLUMN tail_diff_max_price_age_ms SET DEFAULT 6000;

ALTER TABLE crypto_tail_strategy
    ALTER COLUMN tail_diff_max_orderbook_age_ms SET DEFAULT 5000;

ALTER TABLE crypto_tail_strategy
    ALTER COLUMN tail_diff_sigma_score_multiple SET DEFAULT 1.8;
