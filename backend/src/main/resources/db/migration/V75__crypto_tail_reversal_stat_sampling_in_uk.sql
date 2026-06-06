-- V75: 将 sampling_seconds 纳入 crypto_tail_reversal_stat 唯一键，使不同取样精度的数据共存
--
-- 背景：uk_ct_reversal_bucket 原本不含 sampling_seconds，导致同一
--   (coin, interval, outcome, diff_sigma_bucket, odds_bucket, remaining_bucket, lookback_days, data_source)
--   桶下，1m 细采样(sampling_seconds=60)与 1s 细采样(sampling_seconds=1)占用同一把唯一键，
--   后写者会与先写者撞键；而回填的"先删后插"按 (coin,interval,lookback,source) 维度删除，
--   会把另一精度的数据一并删除/覆盖：1s 回填(实际仅 14 天)替换掉 1m@180d，粗桶深度从 180 天退化为 14 天。
--
-- 方案：将 sampling_seconds 纳入唯一键，使 1m(深、长回溯)与 1s(细、短回溯)两套数据并存。
--   - 回填删除改为按 (coin,interval,lookback,source,sampling) 维度，仅清理同精度旧行，不动另一精度；
--   - 查询侧 RealTailReversalStatsLookup 对同一桶按 sample_count 取最优行（深桶天然样本多→优先 1m，
--     1m 缺失的尾盘细桶→回退 1s；POLYMARKET 源 sampling=0 同样被覆盖）。
--
-- 安全性：给唯一键追加列属于"放宽"约束，现有行在新键下仍然唯一，无数据冲突或丢失风险。

ALTER TABLE crypto_tail_reversal_stat
    DROP INDEX uk_ct_reversal_bucket;

ALTER TABLE crypto_tail_reversal_stat
    ADD CONSTRAINT uk_ct_reversal_bucket UNIQUE (
        coin, interval_seconds, outcome_index, diff_sigma_bucket,
        odds_bucket, remaining_bucket, lookback_days, data_source, sampling_seconds
    );
