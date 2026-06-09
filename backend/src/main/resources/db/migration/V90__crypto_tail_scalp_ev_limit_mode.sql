-- V90: SCALP_FLIP 进场限价的 EV 钳价模式（解决"因素②：EV 安全价把 entryFakSlippage 抹平"）
--
-- 背景（实盘 decision-log 分析）：
--   SCALP 进场限价 = base.min(max(evSafeLimit, ask))，其中 base = ask + entryFakSlippage（封顶 scalpMaxFillPrice）。
--   evSafeLimit ≈ pWin（障碍模型胜率，requiredEdge=scalpMinEdge 默认 0），尾盘赢家腿 ask(0.93)常 >= pWin(0.88)，
--   于是 max(evSafeLimit, ask)=ask → 限价恰好钳到 ask，entryFakSlippage 完全失效（死参数），对盘口上跳零容忍，
--   FAK 屡因 ask 上跳一个 tick 被 kill。且障碍 pWin 用静态 sigma + 无漂移假设，临近结算不如市场 ask 校准，
--   用更差的模型价否决更好的市场价并不合理。
--
-- 新增两列（仅作用于 SCALP 进场限价上限，绝不否决进场；进场仍由 scalp_entry_min_pwin 等闸口把关）：
--   - scalp_ev_limit_mode：CLAMP（现状，钳到 EV 安全价）/ GUARD（仅 ask 显著高于 EV 安全价时才钳）/ OFF（完全不钳）。
--   - scalp_ev_guard_margin：GUARD 安全阀差额，仅当 (ask - evSafeLimit) > 此值才钳；<=0 退化为 CLAMP。
--
-- 保守修改：存量策略回填 CLAMP（= 现状行为，零回归）；新建策略默认同样 CLAMP（不悄悄改变默认风控，
--   由用户在前端按策略显式切到 GUARD/OFF 以复活 entryFakSlippage）。

ALTER TABLE crypto_tail_strategy
    ADD COLUMN scalp_ev_limit_mode VARCHAR(16) NOT NULL DEFAULT 'CLAMP',
    ADD COLUMN scalp_ev_guard_margin DECIMAL(20, 8) NOT NULL DEFAULT 0.10;
