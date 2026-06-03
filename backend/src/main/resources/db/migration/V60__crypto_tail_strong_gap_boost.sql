-- V60: Strong Gap Boost（强价差放量）
-- 高置信（pWin/safeRatio 远超入场门槛）时按倍数放大下注；只改 amount，不改方向、不放宽风控/限价。
-- 默认关闭且 shadow=true（仅记日志、不真正放大），先观察再开实盘。
ALTER TABLE crypto_tail_strategy
    ADD COLUMN enable_strong_gap_boost BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN strong_gap_boost_shadow BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN strong_gap_min_pwin DECIMAL(20, 8) NOT NULL DEFAULT 0.90,
    ADD COLUMN strong_gap_min_safe_ratio DECIMAL(20, 8) NOT NULL DEFAULT 1.50,
    ADD COLUMN strong_gap_stake_multiplier DECIMAL(20, 8) NOT NULL DEFAULT 1.50,
    ADD COLUMN ultra_gap_min_pwin DECIMAL(20, 8) NOT NULL DEFAULT 0.95,
    ADD COLUMN ultra_gap_min_safe_ratio DECIMAL(20, 8) NOT NULL DEFAULT 2.00,
    ADD COLUMN ultra_gap_stake_multiplier DECIMAL(20, 8) NOT NULL DEFAULT 2.00,
    ADD COLUMN max_strong_gap_stake_multiplier DECIMAL(20, 8) NOT NULL DEFAULT 2.00,
    ADD COLUMN max_boosted_amount_usdc DECIMAL(20, 8) NULL,
    ADD COLUMN max_boosted_period_exposure_usdc DECIMAL(20, 8) NULL,
    ADD COLUMN allow_boost_with_kelly BOOLEAN NOT NULL DEFAULT FALSE;
