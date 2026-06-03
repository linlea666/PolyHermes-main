-- V61: Smart Hard Stop（智能硬止损复核）
-- HARD_STOP 命中后先复核：若价源新鲜、模型方向未反、gap 仍顺、临近结算且 pWin/safeRatio 达标，
-- 则放弃机械硬止损、继续持有到结算（HARD_STOP_BYPASSED_BY_HOLD_TO_SETTLE）。
-- 复用现有 hold_to_settle_pwin / hold_to_settle_seconds / exit_safe_ratio 阈值，仅新增一个开关。
-- 默认 FALSE：历史策略行为完全不变，opt-in 开启。
ALTER TABLE crypto_tail_strategy
    ADD COLUMN enable_smart_hard_stop BOOLEAN NOT NULL DEFAULT FALSE;
