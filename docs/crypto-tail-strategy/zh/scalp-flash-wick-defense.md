# SCALP 终场闪针防御系统：预挂止盈 + 反向对冲 + 熔断 + 主止损旁路（V96）

## 一、问题背景

2026-06-11 决策日志复盘了一笔"终场归零"样本：持仓 bid 长期停留在 0.98~0.99，结算前最后 4 秒现货闪崩穿价，盘口买盘瞬间蒸发——现货早警与深底线均已触发，但市价单提交时订单簿已无对手盘，最终持仓归零。结论：

- **任何"事后反应式"退出在终场闪针面前都来不及**——从危险确认到订单上链的链路延迟 > 盘口蒸发速度；
- 防御必须前置：要么在闪崩前就把单挂进订单簿（预挂止盈），要么在闪崩前就买好保险（反向对冲）；
- 同时需要入场端过滤（盘口不稳定熔断）与持仓端加速（主止损盘口确认旁路）作为辅助层。

## 二、四层防御架构

| 层 | 阶段 | 机制 | 时机 | 角色 |
|---|---|---|---|---|
| A 预挂止盈 | 持仓全程 | 成交后立即在 `scalpTpPrice` 预挂 GTC 限价卖单（`GTC_TP_REST`），保留至结算 | 闪崩**前**已在簿排队 | 正常行情提前锁定止盈；闪针行情中先于市价单成交 |
| B 反向对冲 | 终场窗口 | 距结算 < `scalpHedgeArmSeconds` 且风险特征聚集时，以 ≤ `scalpHedgeMaxPrice` 买入对侧（FAK） | 闪崩**前**买好保险 | 归零行情中对侧 0.01~0.05 → 1.00，约 20~100 倍赔付覆盖主仓损失 |
| C 盘口不稳定熔断 | 入场前 | 检测到 ask 跳变 / 对手盘消失 / 点差异常后 `scalpBookInstabilityCooldownSec` 内禁止开仓 | 事前过滤 | 不进高危周期，从源头减少归零暴露 |
| D 主止损盘口确认旁路 | 持仓中 | 现货危险 + 盘口 bid 回撤 ≥ `scalpSpotLeadPrimaryStopBookConfirmDrawdown` 时跳过 `persistMs` 等待立即砍 | 事中加速 | 现货+盘口双确认 = 不再等持续确认窗口 |

四层互不依赖、可单独开关，推荐组合开启。

## 三、参数表

### A 预挂止盈

| 参数 | 推荐值 | 说明 |
|---|---|---|
| `scalpTpRestingEnabled` | `true` | 成交后预挂 GTC 限价卖单；订单类型 `GTC_TP_REST`，撤单豁免 `makerCancelBeforeSettleSeconds`，保留至结算 |
| `scalpTpPrice` | `0.99`（沿用） | 预挂价即原止盈价 |

行为细节：

- 预挂成功后抑制反应式 TP（避免重复卖出）；预挂失败自动回退原反应式止盈链路；
- 未成交的预挂单结算时由对账器记 `TP_RESTING_CANCELLED` 事件，不影响结算赔付。

### B 反向对冲

| 参数 | 推荐值 | 说明 |
|---|---|---|
| `scalpHedgeEnabled` | `true` | 总开关 |
| `scalpHedgeArmSeconds` | `25` | 终场评估窗口；太早保费高、太晚来不及成交 |
| `scalpHedgeMinOwnBid` | `0.95` | 持仓 bid ≥ 0.95 才考虑（此时对侧最便宜） |
| `scalpHedgeMaxPrice` | `0.05` | 对侧 ask 高于此价放弃（保费不划算） |
| `scalpHedgeBudgetUsdc` | 单笔仓位的 3%~5% | 单次保费上限；归零赔付 ≈ 预算/买入价 |
| `scalpHedgeMinFeatureScore` | `1`（激进）/ `2`（保守） | 五项特征命中数门槛 |

五项风险特征（各计 1 分，0 = 禁用该特征）：

| 特征 | 参数 | 含义 |
|---|---|---|
| F1 盘口异常记忆 | `scalpHedgeFeatureInstabilityLookbackSec`（120） | 回看窗口内发生过 ask 跳变/对手盘消失/点差异常 |
| F2 现货安全垫薄 | `scalpHedgeFeatureSpotCushionUsd` | \|spotGap\| 低于阈值（BTC 建议 15~30） |
| F3 gap 收敛 | `scalpHedgeFeatureGapShrinkRatio`（0.5） | 当前安全垫收敛到窗口峰值的一半以下 |
| F4 近期翻转 | `scalpHedgeFeatureRecentFlipLookback`（5） | 同市场最近 N 笔已结算记录出现过亏损 |
| F5 对侧 ask 抬升 | `scalpHedgeFeatureOppAskFloor`（0.02） | 对侧 ask 已高于阈值，市场开始为反转定价 |

行为细节：

- 对冲单为对侧 FAK 买单，写入独立 trigger 行（`triggerType=HEDGE`）；
- HEDGE 行**不计入**胜率/连亏/并发统计，但**计入**总盈亏与日亏限额；
- 每周期最多对冲一次（幂等），结算与主仓共用既有结算管线。

### C 盘口不稳定熔断

| 参数 | 推荐值 | 说明 |
|---|---|---|
| `scalpBookInstabilityCooldownSec` | `90` | 异常后冷却秒数；0 = 禁用 |
| `scalpBookInstabilityAskJump` | `0.30` | ask 短时跳变幅度阈值 |

异常分类（`AnomalyType`）：`ASK_JUMP`（对侧 ask 跳升）、`ASK_VANISH`（对手盘消失）、`SPREAD_WIDE`（点差异常）。命中时入场闸返回 `SCALP_BOOK_UNSTABLE` 拒绝开仓；事件同时供对冲特征 F1 复用。

### D 主止损盘口确认旁路

| 参数 | 推荐值 | 说明 |
|---|---|---|
| `scalpSpotLeadPrimaryStopBookConfirmDrawdown` | `0.10`~`0.15` | 现货危险期间盘口 bid 较入场价回撤达此比例 → 跳过 `persistMs` 立即砍；0 = 禁用 |

逻辑：插针的特征是"现货假穿但盘口不跌"；若现货危险**且**盘口已实跌，则双确认成立，等待只会损失成交价。决策日志中以 `bookConfirmBypass=true` 标记。

## 四、决策日志事件

| 事件 | 说明 |
|---|---|
| `HEDGE_ARMED` / `HEDGE_SKIPPED` | 对冲下单成功（含特征明细与得分）/ 评估未达标原因 |
| `HEDGE_FILLED` / `HEDGE_FAILED` | 对冲单成交 / 提交失败 |
| `TP_RESTING_PLACED` / `TP_RESTING_CANCELLED` | 预挂止盈挂单成功 / 结算前撤销 |
| `SCALP_BOOK_UNSTABLE` | 入场被熔断拦截（含异常类型与冷却剩余） |
| `EXIT_CHECK` 载荷新增 | `scalpSpotLeadPrimaryStopBookConfirmDrawdown`、`bookConfirmBypass` |
| `SETTLED` 载荷新增 | `triggerType`（区分 HEDGE 行） |

## 五、推荐配置档（在 V95 双层止损基础上叠加）

```text
# A 预挂止盈
scalpTpRestingEnabled = true

# B 反向对冲（激进档）
scalpHedgeEnabled = true
scalpHedgeArmSeconds = 25
scalpHedgeMinOwnBid = 0.95
scalpHedgeMaxPrice = 0.05
scalpHedgeBudgetUsdc = 单笔仓位×3%~5%
scalpHedgeMinFeatureScore = 1
scalpHedgeFeatureInstabilityLookbackSec = 120
scalpHedgeFeatureSpotCushionUsd = 20   # BTC；ETH 按价格比例折算
scalpHedgeFeatureGapShrinkRatio = 0.5
scalpHedgeFeatureRecentFlipLookback = 5
scalpHedgeFeatureOppAskFloor = 0.02

# C 盘口不稳定熔断
scalpBookInstabilityCooldownSec = 90
scalpBookInstabilityAskJump = 0.30

# D 主止损盘口确认旁路
scalpSpotLeadPrimaryStopBookConfirmDrawdown = 0.12
```

保守档调整：`scalpHedgeMinFeatureScore = 2`、`scalpHedgeMaxPrice = 0.03`、`scalpBookInstabilityCooldownSec = 120`。

## 六、成本-收益估算

以单笔仓位 100 USDC、对冲预算 4 USDC、买入价 0.03 为例：

- 正常结算（绝大多数周期）：损失保费 ≤ 4 USDC（特征不聚集时根本不触发，实际期望成本远低于上限）；
- 终场归零：对侧赔付 ≈ 4 / 0.03 ≈ 133 USDC，覆盖主仓 100 USDC 损失后净赚；
- 平衡点：只要"特征聚集的窗口内真实归零率" > 保费率（~3%），对冲期望为正。决策日志会持续记录 `HEDGE_ARMED` 样本，建议每周复盘命中率并调整 `scalpHedgeMinFeatureScore`。

## 七、相关文档

- 《SCALP 极简双层止损推荐配置档（V95）》：`scalp-spot-brain-dual-layer-stop.md`
- 《SCALP 尾盘退出韧性配置（V92）》：`scalp-late-exit-resilience-config.md`
