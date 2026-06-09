-- V93: SCALP_FLIP 现货价"领先早警"信号层（币安/欧意现货领先于 Chainlink 聚合价 → 提前感知穿价）
--
-- 背景（承接 V92 复盘）：
--   SCALP 的 gap/pWin/safeRatio/modelSide 均来自 Chainlink（Polymarket 结算同源价，默认走 RTDS WS 转发）。
--   Chainlink 聚合多源、有更新节奏/聚合延迟；而币安/欧意现货是流动性最大的现货场，价格变化通常领先聚合价源
--   数百毫秒~数秒。本期把"币安现货同周期 gap"接入为领先早警信号，用于在 Chainlink gap 翻转、Polymarket
--   bid 崩塌、盘口抽干（V92 文档 B 类终局塌缩）之前提前感知穿价风险。
--
-- 设计哲学（强约束）：
--   - 现货价只做"领先早警层"，不做结算真相：绝不进入 pWin/结算口径（沿用"绝不回退币安"原则）。
--   - 信号只允许两件事：(1) 让出场更早/更快；(2) 给 WICK_GUARD 加否决（更安全，绝不放松持有）。
--   - 全部默认关、零回归；现货价不新鲜则完全回退旧行为（fail-safe，绝不因缺数据误触发或阻塞）。
--
-- 三个集成点（仅 SCALP_FLIP 消费）：
--   A WICK_GUARD 防误扛：scalp_spot_lead_wick_veto_enabled=TRUE 时，若现货信号新鲜且已穿价/近翻转，
--     否决 WICK_GUARD 的"判插针继续持有"，让原有止损照走（仅增加安全，绝不放松）。
--   B1 杠杆4 联动：scalp_late_scale_out_require_spot_danger=TRUE 时，V92 尾盘按时间减仓改为"现货确认危险才减仓"
--     （保住赢单尾部收益）。fail-safe：现货不新鲜/不可用时仍按原 V92 无条件减仓（不削弱最后兜底）。
--   B2 现货穿价早警：remaining<=scalp_spot_lead_early_stop_seconds 且现货危险时，按 scalp_spot_lead_scale_out_ratio
--     提前一次保护性减仓（复用 FORCE，全局仅一次）。两参任一为 0 即关。
--
-- 危险判定：现货新鲜 且（现货 gap 已翻到错误一侧 或 现货距翻转 <= scalp_spot_lead_flip_distance_sigma σ）。
--   flip_distance_sigma=0 时仅"实际穿价"算危险（不启用近翻转预警）。
--
-- 保守修改：ADD COLUMN 全部带默认值，开关默认关 → 存量及新建策略行为零回归。
ALTER TABLE crypto_tail_strategy
    ADD COLUMN scalp_spot_lead_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN scalp_spot_lead_source VARCHAR(16) NOT NULL DEFAULT 'BINANCE',
    ADD COLUMN scalp_spot_lead_max_age_ms INT NOT NULL DEFAULT 3000,
    ADD COLUMN scalp_spot_lead_flip_distance_sigma DECIMAL(20, 8) NOT NULL DEFAULT 0.00000000,
    ADD COLUMN scalp_spot_lead_wick_veto_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN scalp_spot_lead_early_stop_seconds INT NOT NULL DEFAULT 0,
    ADD COLUMN scalp_spot_lead_scale_out_ratio DECIMAL(20, 8) NOT NULL DEFAULT 0.00000000,
    ADD COLUMN scalp_late_scale_out_require_spot_danger BOOLEAN NOT NULL DEFAULT FALSE;
