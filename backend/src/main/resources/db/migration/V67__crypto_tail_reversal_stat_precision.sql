-- V67: 历史反转统计精度增强（细采样 + 去相关去重 + MAE/MFE + 虚拟括号退出）
--
-- 背景：原 crypto_tail_reversal_stat 仅记录"反转率 → model_prob"，且 BINANCE 源在每个 1m 边界逐点取样，
--   同一周期相邻观测点高度相关，导致 sample_count 虚高、model_prob 估计有偏。
--
-- 本次增强（全部为可加列，旧行/旧查询不受影响；新列默认 0/NULL）：
--   - sampling_seconds：本行回填所用的最细取样间隔（60=旧 1m 行为；1=尾盘 1s 细采样）。
--   - distinct_period_count：first-satisfy 去重后贡献该桶的不同周期数（每个周期对同一桶最多计一次）。
--   - mae_avg / mfe_avg：以"领先方向胜率 p"为口径的平均最大不利/有利偏移
--       （BINANCE 用 BarrierProbability.winProbTerminal 推导 p，POLYMARKET 用真实赔率 p）。
--   - virtual_tp_rate / virtual_stop_rate：虚拟括号退出在结算前先触达 TP(p>=0.99) / STOP(p<=0.70) 的比例。
--   - virtual_win_rate / virtual_pnl_avg：虚拟括号退出（成本=入场 p，按 TP/STOP/结算结清）的胜率与每单位平均盈亏。

ALTER TABLE crypto_tail_reversal_stat
    ADD COLUMN sampling_seconds INT NOT NULL DEFAULT 60,
    ADD COLUMN distinct_period_count INT NOT NULL DEFAULT 0,
    ADD COLUMN mae_avg DECIMAL(20, 8) NULL,
    ADD COLUMN mfe_avg DECIMAL(20, 8) NULL,
    ADD COLUMN virtual_tp_rate DECIMAL(20, 8) NULL,
    ADD COLUMN virtual_stop_rate DECIMAL(20, 8) NULL,
    ADD COLUMN virtual_win_rate DECIMAL(20, 8) NULL,
    ADD COLUMN virtual_pnl_avg DECIMAL(20, 8) NULL;
