-- crypto-tail σ 估计方法可配：MAD(默认,原口径) / EWMA / GARMAN_KLASS，向后兼容
ALTER TABLE crypto_tail_strategy
    ADD COLUMN sigma_method VARCHAR(16) NOT NULL DEFAULT 'MAD' COMMENT 'σ估计方法: MAD/EWMA/GARMAN_KLASS' AFTER calibration_max_error,
    ADD COLUMN ewma_lambda DECIMAL(20, 8) NOT NULL DEFAULT 0.94 COMMENT 'EWMA衰减系数λ(0~1)，仅EWMA生效' AFTER sigma_method;
