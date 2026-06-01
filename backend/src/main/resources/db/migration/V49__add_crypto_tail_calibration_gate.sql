-- crypto-tail 校准放量闸：小额实盘校准达标后才放大下注（默认关闭，向后兼容）
ALTER TABLE crypto_tail_strategy
    ADD COLUMN calibration_gate_enabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT '放量闸开关：未达标钳制为小额' AFTER maker_fallback_taker,
    ADD COLUMN probe_amount_usdc DECIMAL(20, 8) NOT NULL DEFAULT 1 COMMENT '校准期每笔下注小额USDC' AFTER calibration_gate_enabled,
    ADD COLUMN calibration_min_samples INT NOT NULL DEFAULT 30 COMMENT '放量达标最少已结算样本数' AFTER probe_amount_usdc,
    ADD COLUMN calibration_max_error DECIMAL(20, 8) NOT NULL DEFAULT 0.10 COMMENT '放量达标最大校准误差' AFTER calibration_min_samples;
