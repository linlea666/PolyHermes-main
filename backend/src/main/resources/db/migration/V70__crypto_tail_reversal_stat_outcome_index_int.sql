-- V70: 修正 crypto_tail_reversal_stat.outcome_index 列类型（TINYINT -> INT）
--
-- 背景：V63 将 outcome_index 建为 TINYINT，但实体 CryptoTailReversalStat.outcomeIndex 为 Int，
--   Hibernate schema 校验期望 INTEGER，导致启动时 SchemaManagementException：
--   "wrong column type encountered in column [outcome_index] ... found [tinyint], but expecting [integer]"。
--   本迁移将列类型对齐为 INT；取值仍为 0/1，无数据丢失风险。

ALTER TABLE crypto_tail_reversal_stat
    MODIFY COLUMN outcome_index INT NOT NULL;
