-- V80: SCALP_FLIP 进场实时方向确认门槛
--
-- 背景（实盘日志 decision-log_20260607_235810 分析发现）：
--   胜率 62.5% 但赔率仅 0.33（平均赢 +0.09 / 平均亏 -0.28），盈亏平衡需 75.5% 胜率 → 净亏。
--   三笔亏损全是"方向反转"：买入侧市场价从 0.95+ 崩向 0.76~0.90。根因：进场闸只看
--   静态价格区间 + 历史分桶反转率，缺少"本周期实时标的方向确认"。系统其实已算 computeScalpEntrySignal
--   (modelSide/pWin/safeRatio)，但仅用于反转率分桶，未用于判断"买的这一侧标的方向是否支持"，
--   导致 BTC 在行权价附近震荡时，某侧市场价瞬时冲到 0.95-0.97（噪声/超调）即被买入，随后反转崩盘。
--   历史反转率门槛显示 modelProb=1.0（"100% 安全"）也照样反转，证明历史分桶不具实时方向保护力。
--
-- 新增两列：
--   - scalp_require_underlying_agreement：进场要求标的模型方向(modelSide)==买入侧 且 pWin 达标；
--   - scalp_entry_min_pwin：买入侧标的模型胜率下限。
--   价源不可用时按现有 graceful 口径降级放行并记可观测事件，不阻断（与反转率门槛降级一致）。
--
-- 保守修改：ADD COLUMN 以"关闭/0"回填，存量 SCALP 策略零回归（门槛不生效）；随后 SET DEFAULT
--   true/0.90 仅影响后续新建策略（与 V78/V79 同口径）。存量策略由用户在前端重新保存以采用。

ALTER TABLE crypto_tail_strategy
    ADD COLUMN scalp_require_underlying_agreement BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN scalp_entry_min_pwin DECIMAL(20, 8) NOT NULL DEFAULT 0;

-- 新建策略默认开启实时方向确认（pWin>=0.90）
ALTER TABLE crypto_tail_strategy ALTER COLUMN scalp_require_underlying_agreement SET DEFAULT TRUE;
ALTER TABLE crypto_tail_strategy ALTER COLUMN scalp_entry_min_pwin SET DEFAULT 0.90;
