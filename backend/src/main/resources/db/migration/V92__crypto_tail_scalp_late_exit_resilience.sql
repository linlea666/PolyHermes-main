-- V92: SCALP_FLIP 尾盘退出韧性（FAK 失败快速重试 + 尾盘提速 + 尾盘 marketable + 尾盘减仓）
--
-- 背景（decision-log 复盘 -5.00 那单）：
--   入场质量正常，bid 一直稳在 ~0.97，最后约 2 秒标的穿越行权价 → 二元期权急速归零，
--   bid 从 0.97 gap 到 0.25 再到空盘。尾盘止损评估命中并发 FAK，但盘口已抽干（无对手盘）→ 无法成交 → 骑到结算亏掉全部权利金。
--
-- 根因分两类：
--   A 可恢复：瞬时薄盘/竞速/盘口刷新慢，窗口内会回补 → 快速重试 + 尾盘提速有效；
--   B 终局塌缩：尾盘穿价→二元归零→盘口空，至结算无对手 → 重试无效，只能"提前减仓"换掉尾部归零风险。
--
-- 本次 4 杠杆（全部可配，默认关/默认沿用现状 → 存量及新建策略零回归）：
--   杠杆1 尾盘提速：remaining<=scalp_late_fast_poll_seconds 时把退出评估节流降到 scalp_late_fast_poll_ms（0=关）；
--   杠杆2 FAK失败快速重试：紧急 marketable FAK 提交硬失败(orderId 为空)时，同次内重试 scalp_emergency_retry_count 次、间隔 scalp_emergency_retry_interval_ms（0=关，仅下一 tick 重试，行为不变）；
--   杠杆3 尾盘 marketable：remaining<=scalp_late_ignore_worst_price_seconds 时 softPriceExit 也忽略 worstPrice 地板直发市价扫单（0=关）；
--   杠杆4 尾盘减仓：remaining<=scalp_late_scale_out_seconds 时按 scalp_late_scale_out_ratio 主动减仓一次（任一为 0=关）。
--
-- 保守修改：ADD COLUMN 全部带默认值；杠杆1/3/4 默认 0=关、杠杆2 默认 0=关 → 行为零回归。
ALTER TABLE crypto_tail_strategy
    ADD COLUMN scalp_late_fast_poll_seconds INT NOT NULL DEFAULT 0,
    ADD COLUMN scalp_late_fast_poll_ms INT NOT NULL DEFAULT 300,
    ADD COLUMN scalp_emergency_retry_count INT NOT NULL DEFAULT 0,
    ADD COLUMN scalp_emergency_retry_interval_ms INT NOT NULL DEFAULT 150,
    ADD COLUMN scalp_late_ignore_worst_price_seconds INT NOT NULL DEFAULT 0,
    ADD COLUMN scalp_late_scale_out_seconds INT NOT NULL DEFAULT 0,
    ADD COLUMN scalp_late_scale_out_ratio DECIMAL(20, 8) NOT NULL DEFAULT 0.00000000;
