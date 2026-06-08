-- V89: 修正 crypto_tail_trade_forensics.mode 列类型 TINYINT -> INT
--
-- 背景：
--   V86 误将 mode 建为 TINYINT，而实体 CryptoTailTradeForensics.mode 为 Int?（Hibernate 校验为 INTEGER）。
--   prod 下 hibernate.ddl-auto=validate 时报：
--     Schema-validation: wrong column type in column [mode]; found [tinyint], but expecting [integer]
--   导致应用启动失败。该列也是本表内唯一与其余整型列(INT)不一致之处。
--
-- 变更：
--   仅把 mode 列由 TINYINT 拓宽为 INT，与实体 Int? 对齐；不动 V86（已应用，改其内容会破坏 Flyway checksum）。
--   TINYINT -> INT 为安全加宽，存量数据(0..4)不受影响。
ALTER TABLE crypto_tail_trade_forensics
    MODIFY COLUMN mode INT DEFAULT NULL COMMENT '交易模式: 0=LEGACY,1=BARRIER,2=BRACKET,3=TAIL_DIFF,4=SCALP_FLIP';
