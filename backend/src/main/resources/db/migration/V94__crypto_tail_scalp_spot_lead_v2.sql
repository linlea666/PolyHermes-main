-- V94: SCALP_FLIP 现货领先早警 v2（OKX 第二数据源 + 混合推送 + 入场/尾盘门控）
--
-- 背景（承接 V93 + 决策日志复盘）：
--   V93 证明现货领先信号(spotLeadDanger)能精确区分"真反转"与"假插针/误伤"，但当时仅:
--     - 单一币安数据源、硬锁 BINANCE；
--     - 退出评估仍依赖 ~300ms 尾盘轮询(pull)，未把实时 tick 推送成评估；
--     - 未接入入场闸与尾盘硬止损门控。
--   本期升级为可插拔多源(币安/欧意/共识) + 混合推送(尾盘 tick→退出重评估，零新增 REST) +
--   入场现货闸 + 尾盘硬止损现货门控。
--
-- 设计哲学（强约束，沿用 V93）：
--   - 现货价只做"领先早警层"，绝不进入 pWin/结算口径；
--   - 全部默认关、零回归；现货不新鲜/缺失则完全回退旧行为（fail-safe）。
--
-- 新增列（仅 SCALP_FLIP 消费）：
--   scalp_spot_lead_push_enabled            混合推送总开关（尾盘 tick→退出重评估）
--   scalp_spot_lead_push_tail_seconds       推送尾盘窗口（距结算剩余秒，<=此值才推送）
--   scalp_spot_lead_push_min_interval_ms    推送最小间隔（按订阅防抖，防 tick 风暴）
--   scalp_spot_lead_entry_gate_enabled      入场现货闸（现货已穿价/逆向则否决进场 SPOT_LEAD_ENTRY_VETO）
--   scalp_spot_lead_late_stop_gate_enabled  尾盘硬止损现货门控（模型强挺且现货非危险时抑制硬止损改减仓）
--
-- 注：source 列已在 V93 创建（VARCHAR(16) DEFAULT 'BINANCE'），本期复用，可取值 BINANCE/OKX/CONSENSUS。
-- 保守修改：ADD COLUMN 全部带默认值，开关默认关 → 存量及新建策略行为零回归。
ALTER TABLE crypto_tail_strategy
    ADD COLUMN scalp_spot_lead_push_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN scalp_spot_lead_push_tail_seconds INT NOT NULL DEFAULT 20,
    ADD COLUMN scalp_spot_lead_push_min_interval_ms INT NOT NULL DEFAULT 80,
    ADD COLUMN scalp_spot_lead_entry_gate_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN scalp_spot_lead_late_stop_gate_enabled BOOLEAN NOT NULL DEFAULT FALSE;
