-- V62: Tail Diff Mode（尾盘价差模式，第 4 个交易模式 TAIL_DIFF=3）
--
-- 设计要点：
--   - 所有新列带默认值，对历史 LEGACY_SPREAD/BARRIER_HOLD/BRACKET_DYNAMIC 记录无任何行为影响；
--   - TAIL_DIFF 模式复用：BARRIER 的 gap/σ/pWin 内核 + BRACKET 的退出引擎 + 现有风控/订单/日志；
--   - 新增评分大脑（CryptoTailScoreEngine）+ 分层金额 + 退出预设 JSON + 反转统计 modelProb 接入；
--   - 所有阈值/权重/分桶/否决/预设均存表，前端可改，不写死；
--   - 触发表追加 7 个 TAIL_DIFF 字段（其他模式留 NULL），供回放/统计。
--
-- 注：BRACKET 模式行为完全不变，仅当 mode=TAIL_DIFF 且 exit_preset_json 非空时，
--     CryptoTailBracketExitService 会在评估退出前用 trigger 上的预设快照覆盖策略默认阈值。

ALTER TABLE crypto_tail_strategy
    -- ===== 顶层开关 / 影子 / 方向 =====
    -- SHADOW=true 仅记决策日志不真正下单，便于上线初期空跑观察 1-3 天
    ADD COLUMN tail_diff_shadow_mode BOOLEAN NOT NULL DEFAULT FALSE,
    -- 0=自动选领先方向（UP/DOWN 都做），1=只做 Up，2=只做 Down
    ADD COLUMN tail_diff_direction TINYINT NOT NULL DEFAULT 0,

    -- ===== 入场窗口与连续确认 =====
    -- 默认入场窗口 150s~60s（即距离结算 60-150s 区间），<50s 禁止新开
    ADD COLUMN tail_diff_window_start_seconds INT NOT NULL DEFAULT 150,
    ADD COLUMN tail_diff_window_end_seconds INT NOT NULL DEFAULT 60,
    ADD COLUMN tail_diff_min_remaining_seconds INT NOT NULL DEFAULT 50,
    -- 连续 N 次 tick 命中候选才真正下单（防瞬时抖动）
    ADD COLUMN tail_diff_confirm_ticks INT NOT NULL DEFAULT 2,

    -- ===== 价格区间（ask 上限） =====
    ADD COLUMN tail_diff_min_price DECIMAL(20, 8) NOT NULL DEFAULT 0.88,
    ADD COLUMN tail_diff_max_price DECIMAL(20, 8) NOT NULL DEFAULT 0.93,
    -- 极限最高价：触发 ASK_TOO_HIGH 硬否决
    ADD COLUMN tail_diff_hard_max_price DECIMAL(20, 8) NOT NULL DEFAULT 0.94,

    -- ===== 模型与 EV =====
    ADD COLUMN tail_diff_min_model_prob DECIMAL(20, 8) NOT NULL DEFAULT 0.95,
    ADD COLUMN tail_diff_min_edge DECIMAL(20, 8) NOT NULL DEFAULT 0.025,
    ADD COLUMN tail_diff_cost_buffer DECIMAL(20, 8) NOT NULL DEFAULT 0.01,
    -- diff_sigma = |raw_diff| / (σ × √remaining)，等价于现有 BarrierProbability.safeRatio
    ADD COLUMN tail_diff_min_diff_sigma DECIMAL(20, 8) NOT NULL DEFAULT 1.8,
    -- modelProb 来源：STATS=优先用历史反转统计；FALLBACK=只用 BarrierProbability；HYBRID=统计样本足够时用统计，否则回退
    ADD COLUMN tail_diff_model_prob_source VARCHAR(16) NOT NULL DEFAULT 'HYBRID',
    -- 统计样本阈值：低于此值视为不可信，HYBRID 模式回退 BarrierProbability
    ADD COLUMN tail_diff_stats_min_samples INT NOT NULL DEFAULT 50,
    -- 历史反转统计回看天数（180/365）
    ADD COLUMN tail_diff_stats_lookback_days INT NOT NULL DEFAULT 180,

    -- ===== 盘口质量 =====
    ADD COLUMN tail_diff_max_spread DECIMAL(20, 8) NOT NULL DEFAULT 0.02,
    -- 深度 >= 下单金额 × 倍数；默认 3 倍
    ADD COLUMN tail_diff_depth_multiplier DECIMAL(20, 8) NOT NULL DEFAULT 3.0,
    ADD COLUMN tail_diff_max_orderbook_age_ms INT NOT NULL DEFAULT 2000,
    ADD COLUMN tail_diff_max_price_age_ms INT NOT NULL DEFAULT 2000,

    -- ===== 反抽风险 =====
    -- 取最近 N 秒价格序列，估算"向目标价反抽"的 sigma/s 速度
    ADD COLUMN tail_diff_reverse_velocity_window_seconds INT NOT NULL DEFAULT 10,
    -- 超过此速度（每秒 sigma 数）触发 PRICE_RETRACING_FAST 硬否决
    ADD COLUMN tail_diff_max_reverse_velocity_sigma DECIMAL(20, 8) NOT NULL DEFAULT 0.30,

    -- ===== 评分权重（7 项加权，总和应=100） =====
    -- 价差优势分（safeRatio/diff_sigma 强度）
    ADD COLUMN tail_diff_weight_diff INT NOT NULL DEFAULT 25,
    -- 时间优势分（越接近 window_end 得分越高）
    ADD COLUMN tail_diff_weight_time INT NOT NULL DEFAULT 15,
    -- 赔率低估分（edge / 10% 比例归一化）
    ADD COLUMN tail_diff_weight_odds_underprice INT NOT NULL DEFAULT 20,
    -- 赔率滞后分（model_prob - mid_implied_prob）
    ADD COLUMN tail_diff_weight_odds_lag INT NOT NULL DEFAULT 10,
    -- 历史胜率分（统计 model_prob 强度；样本不足扣减）
    ADD COLUMN tail_diff_weight_history INT NOT NULL DEFAULT 15,
    -- 盘口质量分（spread + 深度 USDC）
    ADD COLUMN tail_diff_weight_book INT NOT NULL DEFAULT 10,
    -- 数据可靠性分（priceAge / orderbookAge 新鲜度）
    ADD COLUMN tail_diff_weight_data INT NOT NULL DEFAULT 5,

    -- ===== 评分分层阈值 =====
    ADD COLUMN tail_diff_min_entry_score INT NOT NULL DEFAULT 70,
    ADD COLUMN tail_diff_premium_score INT NOT NULL DEFAULT 80,
    ADD COLUMN tail_diff_top_score INT NOT NULL DEFAULT 90,

    -- ===== 仓位分层 =====
    ADD COLUMN tail_diff_base_amount DECIMAL(20, 8) NOT NULL DEFAULT 1,
    ADD COLUMN tail_diff_tier_normal_mult DECIMAL(20, 8) NOT NULL DEFAULT 1.0,
    ADD COLUMN tail_diff_tier_premium_mult DECIMAL(20, 8) NOT NULL DEFAULT 1.5,
    ADD COLUMN tail_diff_tier_top_mult DECIMAL(20, 8) NOT NULL DEFAULT 2.0,
    ADD COLUMN tail_diff_max_amount_per_order DECIMAL(20, 8) NOT NULL DEFAULT 5,

    -- ===== 退出预设 JSON（按分层独立） =====
    -- 结构: {"hold_to_expiry": false,
    --        "tp_limit": {"enabled": true, "price": "0.98", "ratio": "1.0"},
    --        "stop_loss": {"enabled": true, "offset": "0.20", "min_price": "0.70", "ratio": "1.0"},
    --        "dynamic_exit": {"enabled": true,
    --                         "min_diff_sigma_after_entry": "1.3",
    --                         "max_diff_retrace_pct": "0.50",
    --                         "min_model_prob_after_entry": "0.88",
    --                         "min_odds_after_entry": "0.80",
    --                         "max_reverse_velocity_sigma": "0.40"}}
    -- 默认值即 GPT 计划书"§六 默认建议"中三档预设
    ADD COLUMN tail_diff_exit_preset_normal_json TEXT,
    ADD COLUMN tail_diff_exit_preset_premium_json TEXT,
    ADD COLUMN tail_diff_exit_preset_top_json TEXT,

    -- ===== 风控（仅 TAIL_DIFF 专属，可独立于全局风控） =====
    -- NULL = 复用全局 daily_loss_limit_usdc
    ADD COLUMN tail_diff_daily_loss_limit_usdc DECIMAL(20, 8) NULL,
    ADD COLUMN tail_diff_consec_loss_pause_count INT NOT NULL DEFAULT 2,
    ADD COLUMN tail_diff_consec_loss_stop_count INT NOT NULL DEFAULT 3;

-- 触发记录追加 TAIL_DIFF 7 字段（其他模式 NULL；回放/统计/退出预设快照）
ALTER TABLE crypto_tail_strategy_trigger
    -- 入场时机会评分（0-100，未评分=NULL）
    ADD COLUMN score INT NULL,
    -- 入场分层：NORMAL / PREMIUM / TOP / NULL
    ADD COLUMN tier VARCHAR(8) NULL,
    -- 入场时冻结的退出预设 JSON（CryptoTailBracketExitService 优先用此覆盖策略默认阈值）
    ADD COLUMN exit_preset_json TEXT NULL,
    -- raw_diff = close - open（带符号）
    ADD COLUMN raw_diff DECIMAL(20, 8) NULL,
    -- diff_pct = raw_diff / open（带符号，便于跨币种对比）
    ADD COLUMN diff_pct DECIMAL(20, 8) NULL,
    -- diff_sigma = |raw_diff| / (σ × √remaining)（即 safeRatio）
    ADD COLUMN diff_sigma DECIMAL(20, 8) NULL,
    -- modelProb 来源：STATS / FALLBACK / HYBRID_STATS / HYBRID_FALLBACK
    ADD COLUMN model_prob_source VARCHAR(16) NULL;

-- 索引：TAIL_DIFF 决策日志按 tier 检索，便于复盘
CREATE INDEX idx_ct_trigger_tier ON crypto_tail_strategy_trigger (tier);
