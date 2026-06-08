-- ============================================
-- V86: 加密尾盘策略 - 成交复盘因子表（reversal / 方向错误 多维归因）
-- 一笔成交一行，由 CryptoTailTradeForensicsService 定时聚合：
--   复用 crypto_tail_trade_snapshot（进场/成交/结算派生，已 durable）
--   + 扫描 crypto_tail_decision_event 的 EXIT_CHECK/退出事件派生"反转动态/出场归因/成交质量"。
-- 本表为 durable 派生表：派生量算好写入后，即使 decision_event 被清理也不影响后续统计。
-- 与 V45(trade_snapshot)、V71(period_summary) 互补：本表面向"调参用的多维因子分析"。
-- 唯一键 (strategy_id, period_start_unix)：每策略每周期最多进场一次。
-- ============================================
CREATE TABLE IF NOT EXISTS crypto_tail_trade_forensics (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '复盘因子ID',

    -- ===== 身份 =====
    strategy_id BIGINT NOT NULL COMMENT '策略ID',
    account_id BIGINT DEFAULT NULL COMMENT '账户ID',
    market_slug VARCHAR(64) DEFAULT NULL COMMENT '市场 slug 前缀',
    interval_seconds INT NOT NULL DEFAULT 0 COMMENT '周期长度(秒): 300=5m, 900=15m',
    period_start_unix BIGINT NOT NULL COMMENT '周期起点 Unix 秒',
    trigger_id BIGINT DEFAULT NULL COMMENT '关联触发记录ID',
    correlation_id VARCHAR(64) NOT NULL DEFAULT '' COMMENT '关联ID(strategyId-period)',
    mode TINYINT DEFAULT NULL COMMENT '交易模式: 0=LEGACY,1=BARRIER,2=BRACKET,3=TAIL_DIFF,4=SCALP_FLIP',
    outcome_index INT DEFAULT NULL COMMENT '买入侧方向: 0=Up, 1=Down',

    -- ===== 进场(官方口径) =====
    entry_ts BIGINT DEFAULT NULL COMMENT '进场(下单)时间毫秒',
    entry_remaining_seconds INT DEFAULT NULL COMMENT '进场时距结算剩余秒',
    entry_official_target DECIMAL(30,8) DEFAULT NULL COMMENT '官方目标价(周期开盘价)',
    entry_current_price DECIMAL(30,8) DEFAULT NULL COMMENT '进场时当前价(标记价)',
    entry_gap DECIMAL(30,8) DEFAULT NULL COMMENT '进场价差=当前价-目标价',
    entry_gap_abs DECIMAL(30,8) DEFAULT NULL COMMENT '进场价差绝对值',
    entry_gap_pct DECIMAL(20,8) DEFAULT NULL COMMENT '进场价差占目标价比例',
    entry_diff_sigma DECIMAL(20,8) DEFAULT NULL COMMENT '进场 diff_sigma(safeRatio)=|gap|/(σ√t)',
    entry_pwin DECIMAL(12,8) DEFAULT NULL COMMENT '进场模型胜率',
    entry_model_side INT DEFAULT NULL COMMENT '进场模型方向',
    entry_best_bid DECIMAL(12,8) DEFAULT NULL COMMENT '进场 bestBid',
    entry_best_ask DECIMAL(12,8) DEFAULT NULL COMMENT '进场 bestAsk(赔率)',
    entry_fill_price DECIMAL(12,8) DEFAULT NULL COMMENT '进场成交均价',
    entry_wall_hour INT DEFAULT NULL COMMENT '进场 UTC 时段(0-23)',
    entry_dow INT DEFAULT NULL COMMENT '进场星期(1=周一..7=周日, UTC)',

    -- ===== 分桶(对齐 TailDiffBuckets) =====
    entry_diff_sigma_bucket VARCHAR(32) DEFAULT NULL COMMENT 'diff_sigma 桶',
    entry_odds_bucket VARCHAR(32) DEFAULT NULL COMMENT '赔率桶',
    entry_remaining_bucket VARCHAR(32) DEFAULT NULL COMMENT '剩余时间桶',

    -- ===== 进场成交质量 =====
    fill_vs_band_dev DECIMAL(12,8) DEFAULT NULL COMMENT '成交价相对进场区间偏离(成交价-区间上限,负=买在区间下方)',
    requote_count INT DEFAULT NULL COMMENT 'FAK 零成交重定价次数',
    submit_latency_ms BIGINT DEFAULT NULL COMMENT '下单提交延迟ms',
    entry_slippage DECIMAL(12,8) DEFAULT NULL COMMENT '进场滑点=成交价-目标(限价)价',

    -- ===== 反转动态(派生自 EXIT_CHECK 轨迹) =====
    lead_reversed TINYINT(1) DEFAULT NULL COMMENT '持仓中领先方向是否被反转(gap符号翻转)',
    first_reversal_remaining_seconds INT DEFAULT NULL COMMENT '首次反转时的剩余秒',
    trough_safe_ratio DECIMAL(20,8) DEFAULT NULL COMMENT '持仓中最低 safeRatio',
    trough_gap DECIMAL(30,8) DEFAULT NULL COMMENT '最低 safeRatio 对应的 gap',
    max_diff_retrace_pct DECIMAL(20,8) DEFAULT NULL COMMENT '领先优势最大回撤比例=(进场-最低)/进场',
    min_best_bid DECIMAL(12,8) DEFAULT NULL COMMENT '持仓中最低 bestBid',
    peak_best_bid DECIMAL(12,8) DEFAULT NULL COMMENT '持仓中最高 bestBid',
    reversal_sample_count INT DEFAULT NULL COMMENT '持仓中 EXIT_CHECK 采样点数',
    recovered_after_reversal TINYINT(1) DEFAULT NULL COMMENT '反转后又回到我方(伪反转/插针, lead_reversed且方向最终正确)',

    -- ===== MAE/MFE(最大不利/有利偏移) =====
    mae_odds DECIMAL(12,8) DEFAULT NULL COMMENT '赔率口径最大不利偏移=成交价-最低bid',
    mfe_odds DECIMAL(12,8) DEFAULT NULL COMMENT '赔率口径最大有利偏移=最高bid-成交价',
    mae_sigma DECIMAL(20,8) DEFAULT NULL COMMENT 'σ口径最大不利偏移=进场diffSigma-最低diffSigma',
    mfe_sigma DECIMAL(20,8) DEFAULT NULL COMMENT 'σ口径最大有利偏移=最高diffSigma-进场diffSigma',

    -- ===== 出场/结算 =====
    exit_kind VARCHAR(40) DEFAULT NULL COMMENT '出场类型: HARD_STOP/TAKE_PROFIT/HOLD_TO_SETTLE 等',
    exit_reason TEXT DEFAULT NULL COMMENT '出场原因文本',
    was_cut TINYINT(1) DEFAULT NULL COMMENT '是否在结算前主动平仓(割肉/止盈)',
    exit_price DECIMAL(12,8) DEFAULT NULL COMMENT '出场成交均价',
    exit_slippage DECIMAL(12,8) DEFAULT NULL COMMENT '出场滑点',
    exit_executable_depth_usd DECIMAL(20,8) DEFAULT NULL COMMENT '出场时可成交bid深度USDC',
    hold_seconds BIGINT DEFAULT NULL COMMENT '持仓时长(秒)',
    settled TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已结算',
    won TINYINT(1) DEFAULT NULL COMMENT '是否获胜',
    winner_outcome_index INT DEFAULT NULL COMMENT '赢家方向',
    final_official_target DECIMAL(30,8) DEFAULT NULL COMMENT '结算最终官方目标价(开盘)',
    final_current_price DECIMAL(30,8) DEFAULT NULL COMMENT '结算最终价(收盘)',
    final_gap DECIMAL(30,8) DEFAULT NULL COMMENT '最终价差',
    realized_pnl DECIMAL(20,8) DEFAULT NULL COMMENT '已实现盈亏',

    -- ===== 反事实(若持有到结算) =====
    would_have_won_if_held TINYINT(1) DEFAULT NULL COMMENT '若持有到结算是否会赢(被割但方向最终正确)',
    counterfactual_hold_pnl DECIMAL(20,8) DEFAULT NULL COMMENT '若持有到结算的估算PnL',
    cut_vs_hold_delta DECIMAL(20,8) DEFAULT NULL COMMENT '反事实-实际(>0=被错杀的成本)',

    -- ===== 配置指纹(进场时关键阈值, 用于配置版本A/B) =====
    cfg_entry_min_pwin DECIMAL(12,8) DEFAULT NULL COMMENT '配置:进场pWin下限',
    cfg_min_model_prob DECIMAL(12,8) DEFAULT NULL COMMENT '配置:反转门槛最小模型概率',
    cfg_reversal_gate_enabled TINYINT(1) DEFAULT NULL COMMENT '配置:反转门槛是否开启',
    cfg_max_diff_retrace_pct DECIMAL(20,8) DEFAULT NULL COMMENT '配置:领先回撤软止损阈值',
    cfg_min_model_prob_after_entry DECIMAL(12,8) DEFAULT NULL COMMENT '配置:持仓模型胜率软止损阈值',
    cfg_gap_gate_enabled TINYINT(1) DEFAULT NULL COMMENT '配置:进场价差闸是否开启',
    cfg_fingerprint VARCHAR(64) DEFAULT NULL COMMENT '关键阈值指纹(用于按配置版本聚合对比)',

    -- ===== 分类 =====
    direction_correct TINYINT(1) DEFAULT NULL COMMENT '买入侧是否==赢家(方向是否正确)',
    outcome_category VARCHAR(32) DEFAULT NULL COMMENT '分类: WON_HELD/WON_TP/CUT_BUT_WOULD_WIN/LOST_REVERSED/LOST_WRONG_FROM_START/UNFILLED',
    source_max_event_id BIGINT DEFAULT NULL COMMENT '聚合时已消费的最大决策事件ID(增量/幂等)',

    created_at BIGINT NOT NULL COMMENT '创建时间',
    updated_at BIGINT NOT NULL COMMENT '更新时间',

    UNIQUE KEY uk_ctf_strategy_period (strategy_id, period_start_unix),
    INDEX idx_ctf_market_interval (market_slug, interval_seconds, outcome_category),
    INDEX idx_ctf_strategy_settled (strategy_id, settled),
    INDEX idx_ctf_category (outcome_category),
    INDEX idx_ctf_fingerprint (cfg_fingerprint),
    INDEX idx_ctf_entry_ts (entry_ts),
    CONSTRAINT fk_ctf_strategy FOREIGN KEY (strategy_id) REFERENCES crypto_tail_strategy(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='加密尾盘策略成交复盘因子(reversal/方向错误多维归因)';
