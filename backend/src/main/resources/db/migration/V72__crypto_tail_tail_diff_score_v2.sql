-- V72: Tail Diff 评分系统增强（对标 gtp「机会评分系统」后吸收 3 个增量点）
--
-- 设计要点：
--   - 所有新列带默认值，且默认值 = 现有硬编码行为，对已有 TAIL_DIFF / 其他模式记录零行为影响（零回归）；
--   - 增量点一：动态赔率滞后因子（替代/增强静态 modelProb-midImplied），默认 STATIC 即旧行为；
--   - 增量点二：下注上限叠加 1/10 Kelly + 盘口可成交深度，默认开关 FALSE / 比例 0 即旧行为；
--   - 增量点三：评分归一化锚点配置化（原先写死在 CryptoTailScoreEngine 的 0.10 / 0.15 / 0.90-1.00 / 3×minSigma）。
--   - 延续 V62 原则：所有阈值/锚点/开关均存表，前端可改，不写死。

ALTER TABLE crypto_tail_strategy
    -- ===== 增量点一：动态赔率滞后因子 =====
    -- 赔率滞后分计算模式：STATIC=现状(modelProb-midImplied) / DYNAMIC=标的领先动量-赔率动量 / HYBRID=两者均值
    ADD COLUMN tail_diff_odds_lag_mode VARCHAR(16) NOT NULL DEFAULT 'STATIC',
    -- 动态滞后观测窗口秒数（gtp 建议 5-10s）
    ADD COLUMN tail_diff_odds_lag_window_seconds INT NOT NULL DEFAULT 5,
    -- 标的朝领先方向移动满分锚点（σ 单位，窗口内移动达此值得满分）
    ADD COLUMN tail_diff_lag_price_move_full_scale_sigma DECIMAL(20, 8) NOT NULL DEFAULT 0.5,
    -- 同期赔率上行满分锚点（概率单位，窗口内赔率上行达此值则视为已跟上）
    ADD COLUMN tail_diff_lag_odds_move_full_scale DECIMAL(20, 8) NOT NULL DEFAULT 0.05,

    -- ===== 增量点三：评分归一化锚点配置化（默认值 = 原硬编码常量） =====
    -- 赔率低估分满分锚点：edge / 此值 归一化（原写死 0.10）
    ADD COLUMN tail_diff_edge_full_scale DECIMAL(20, 8) NOT NULL DEFAULT 0.10,
    -- 静态赔率滞后分满分锚点：(modelProb-midImplied)/此值（原写死 0.15）
    ADD COLUMN tail_diff_lag_full_scale DECIMAL(20, 8) NOT NULL DEFAULT 0.15,
    -- 历史胜率分归一化下限（原写死 0.90）
    ADD COLUMN tail_diff_history_prob_floor DECIMAL(20, 8) NOT NULL DEFAULT 0.90,
    -- 历史胜率分归一化上限（原写死 1.00）
    ADD COLUMN tail_diff_history_prob_ceil DECIMAL(20, 8) NOT NULL DEFAULT 1.00,
    -- 价差优势分满分锚点倍数：满分 sigma = minDiffSigma × 此倍数（原写死 3）
    ADD COLUMN tail_diff_sigma_score_multiple DECIMAL(20, 8) NOT NULL DEFAULT 3.0,

    -- ===== 增量点二：下注上限叠加 Kelly + 盘口可成交深度 =====
    -- 是否启用分数 Kelly 下注上限（默认 FALSE = 仅按 tier 倍率，旧行为不变）
    ADD COLUMN tail_diff_enable_kelly_cap BOOLEAN NOT NULL DEFAULT FALSE,
    -- Kelly 折扣系数（实盘建议 1/10~1/20），最终 Kelly 上限 = kellyFull × 此系数 × 可支配余额
    ADD COLUMN tail_diff_kelly_fraction DECIMAL(20, 8) NOT NULL DEFAULT 0.10,
    -- 盘口可成交深度下注比例：上限 = min(bidDepth, askDepth) × 此比例；0 = 不启用深度上限（旧行为）
    ADD COLUMN tail_diff_depth_fill_ratio DECIMAL(20, 8) NOT NULL DEFAULT 0;
