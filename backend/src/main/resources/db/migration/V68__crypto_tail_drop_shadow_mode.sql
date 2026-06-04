-- V68: 删除 TAIL_DIFF 影子模式列（tail_diff_shadow_mode）
--
-- 背景：影子模式（只记决策日志不真正下单）是上线初期灰度空跑开关，现已不再需要。
--   preview 已统一走实盘 evaluate 决策链，影子空跑能力由 preview/决策日志覆盖。
--   保守起见用独立迁移 DROP COLUMN，不回改已发布的 V62。

ALTER TABLE crypto_tail_strategy
    DROP COLUMN tail_diff_shadow_mode;
