-- V82: SCALP_FLIP 抗插针总闸——safeRatio 门槛可配 + 深底线比例可配
--
-- 背景（实盘日志 decision-log_20260608_141146 分析发现）：
--   trigger 180（UP，入场 0.92）方向全对却被割肉：gap=+32（BTC 现价远在行权价上方）、modelSide=UP==持仓、
--   pWin=0.90 全程强挺 UP，盘口 bid 从 0.90 一跳插针到 0.78→0.69 随后回弹、最终 Up 结算赢。
--   死因是熔断绝对地板（scalp_catastrophe_bid_floor=0.78）：它走 catastropheExitDecision、带 forceImmediate，
--   且完全不经过智能硬止损/插针容忍复核 → 跌破即时市价砍，无视模型方向。其后还有 minOdds(0.93) 软止损同样
--   只看盘口价。V81 的"插针容忍 pWin"只挂在机械止损那一条分支上，够不到熔断/minOdds，故对 180 无效。
--   此外该单 safeRatio=1.279，被写死的 SMART_HARD_STOP_MIN_SAFE_RATIO=1.30 以 0.02 之差卡掉旁路。
--
-- 修复（退出侧统一）：在 decideTailDiffExit 顶部新增 SCALP 抗插针总闸——模型强挺（方向未反 + gap 顺 +
--   pWin>=scalp_smart_stop_min_pwin + safeRatio>=门槛 + 价源新鲜）时跳过所有亏损类退出（机械止损/熔断/minOdds/
--   反抽），继续持有；止盈照常；入场×深底线（最顶层、无条件即时）不可豁免。每 tick 复核，模型真转弱即止损。
--
-- 新增两列：
--   - scalp_smart_stop_min_safe_ratio：智能硬止损/抗插针旁路的 safeRatio 下限（取代写死 1.30）。默认 1.30。
--   - scalp_hard_floor_ratio：无条件深底线比例（bestBid<=入场×此值即时市价全清，唯一不可豁免线）。默认 0.50。
--
-- 保守修改：ADD COLUMN 回填值=默认值（1.30 / 0.50）→ 存量策略零回归；总闸仅在 enable_smart_hard_stop=true 时
--   生效（关着=完全旧行为）。BARRIER/TAIL_DIFF 仍用写死 1.30 常量，行为不变。

ALTER TABLE crypto_tail_strategy
    ADD COLUMN scalp_smart_stop_min_safe_ratio DECIMAL(20, 8) NOT NULL DEFAULT 1.30,
    ADD COLUMN scalp_hard_floor_ratio DECIMAL(20, 8) NOT NULL DEFAULT 0.50;
