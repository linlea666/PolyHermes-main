# SCALP_FLIP 尾盘退出韧性（V92）配置说明

> 适用模式：仅 `SCALP_FLIP`（快进快出）。其他模式忽略本文所有参数。
> 面向读者：配置策略的人 / 协助配置的 AI。读完应能独立、安全地填写这组参数。

---

## 0. 背景：为什么需要这组参数

复盘 `-5.00 USDC` 那单：入场质量正常，bid 长期稳在 ~0.97，**最后约 2 秒标的穿越行权价 → 二元期权急速归零**，bid 从 0.97 gap 到 0.25 再到空盘。尾盘止损评估命中并发了 FAK，但**盘口已被抽干、无对手盘**→ 无法成交 → 骑到结算亏掉全部权利金，连硬地板都没救住。

把失败分成两类：

- **A 类 可恢复**：瞬时薄盘 / 竞速 / 盘口刷新慢，窗口内会回补对手盘 → **提速 + 快速重试有效**。
- **B 类 终局塌缩**：尾盘穿价 → 二元归零 → 盘口空到结算 → **无论多快都没有对手盘** → 重试无效，**只能提前减仓**换掉尾部归零风险。

V92 用 4 个**全部可配、默认关、零回归**的杠杆覆盖这两类：

| 杠杆 | 参数 | 治 A 类 | 治 B 类 |
|---|---|:---:|:---:|
| 1 尾盘提速 | `scalpLateFastPollSeconds` / `scalpLateFastPollMs` | ✅ | ➖（更早发现，但救不了空盘） |
| 2 FAK 失败快速重试 | `scalpEmergencyRetryCount` / `scalpEmergencyRetryIntervalMs` | ✅ | ❌ |
| 3 尾盘 marketable | `scalpLateIgnoreWorstPriceSeconds` | ✅ | ➖ |
| 4 尾盘减仓 | `scalpLateScaleOutSeconds` / `scalpLateScaleOutRatio` | ✅ | ✅ 唯一有效 |

> 核心认知：**软件提速无法消灭 B 类终局塌缩**。要避免尾盘归零，唯一可靠手段是「不持有到结算、提前减仓」（杠杆4）。

---

## 1. 先理清节流体系（和 `exitPollIntervalMs` 的关系）

退出评估的频率由**三层节流叠加 + 一个调度硬上限**决定：

```
最终评估间隔 intervalMs = min(
    exitPollIntervalMs,                       // ① 全程基线（默认3000，强制下限500）
    if (危险区) 500ms,                         // ② bestBid ≤ 入场成交价×0.95 时
    if (尾盘窗口) scalpLateFastPollMs          // ③ 杠杆1：剩余≤scalpLateFastPollSeconds 时（仅SCALP，下限100）
)
// 命中条件：now - lastExitCheckAt >= intervalMs 才真正评估
```

调度硬上限：`@Scheduled(fixedDelay = 500)` + 单线程跳过 → **POLL 路径最快 ~500ms**。

两条触发路径，行为不同：

| 路径 | 触发方式 | 受 500ms 调度上限 | `scalpLateFastPollMs<500` 是否有效 |
|---|---|:---:|:---:|
| POLL | 定时轮询拉 /book | 是 | 否（被 500ms 钳制） |
| WS | 盘口 bestBid 变化事件 | 否 | **是**（可低至 fastPollMs） |

**结论**：
- `exitPollIntervalMs` 是**粗粒度、全程、所有模式**通用的基线，决定"平时多久看一次盘"。
- 杠杆1 是**细粒度、仅尾盘 N 秒、仅 SCALP** 的加速覆盖，对基线取 `min`。
- 二者**不冲突、是分层**：平时省着轮询，尾盘/崩盘时自动提速。
- 终局塌缩由 WS 最先感知，所以 `scalpLateFastPollMs` 设 300 这类 <500 值，**只在 WS 路径产生额外收益**。

---

## 2. 参数逐项说明

### 杠杆1 — 尾盘提速

| 参数 | 类型 | 默认 | 含义 |
|---|---|---|---|
| `scalpLateFastPollSeconds` | Int 秒 | `0`(关) | 剩余时间 ≤ 此值时进入"尾盘提速窗口" |
| `scalpLateFastPollMs` | Int 毫秒 | `300` | 提速窗口内的评估节流间隔（下限 100），与基线/危险区取 min |

- 任一为 0（这里是 `scalpLateFastPollSeconds=0`）即关闭，行为完全等同改动前。
- 推荐：`scalpLateFastPollSeconds = 20~30`，`scalpLateFastPollMs = 300`。
- 不要把 `scalpLateFastPollMs` 设低于 200——POLL 路径被 500ms 钳制，WS 路径过密会在崩盘期触发"下单风暴"（系统已有危险区 500ms 兜底，但仍以稳为先）。

### 杠杆2 — FAK 失败快速重试（同次评估内）

| 参数 | 类型 | 默认 | 含义 |
|---|---|---|---|
| `scalpEmergencyRetryCount` | Int 次 | `0`(关) | 紧急 marketable 止损 FAK **提交被拒/异常**时，同次评估内重新签名重试的次数 |
| `scalpEmergencyRetryIntervalMs` | Int 毫秒 | `150` | 每次重试间隔 |

- **只在"提交硬失败"（`orderId` 为空：被交易所拒单 / 签名失败 / 网络异常）时重试。**
- **盘口抽干导致的"零成交"不走此重试**——那由下一个评估 tick 的紧急抢占重发处理（开了杠杆1 后约 300~500ms 一次），这样避免重复下单 → 超卖。
- 推荐：`scalpEmergencyRetryCount = 1~2`，`scalpEmergencyRetryIntervalMs = 150`。

### 杠杆3 — 尾盘 marketable（无视地板直发市价扫单）

| 参数 | 类型 | 默认 | 含义 |
|---|---|---|---|
| `scalpLateIgnoreWorstPriceSeconds` | Int 秒 | `0`(关) | 剩余 ≤ 此值时，软退出（softPriceExit）也忽略 `worstPrice` 地板、直接挂 marketable 限价（0.01）扫单 |

- 平时退出限价受"最差可接受价"地板约束（防止过度滑点）；尾盘临近归零时，**成交 > 价格**，故放开地板。
- 紧急/止损类退出本就 marketable；本杠杆主要影响"软退出"在尾盘的成交确定性。
- 推荐：`scalpLateIgnoreWorstPriceSeconds = 10~15`。

### 杠杆4 — 尾盘减仓（治 B 类的唯一有效手段）

| 参数 | 类型 | 默认 | 含义 |
|---|---|---|---|
| `scalpLateScaleOutSeconds` | Int 秒 | `0`(关) | 剩余 ≤ 此值时触发一次主动减仓 |
| `scalpLateScaleOutRatio` | 0~1 小数 | `0`(关) | 减仓比例（按剩余仓位） |

- **两参任一为 0 即关。**
- 对**窗口内每一笔持仓（含正在盈利的单子）**触发一次，**无条件、按时间**减仓（不是"异常才减"）。用确定性小滑点换掉尾盘穿价归零的尾部风险。
- 复用 `FORCE` 退出种类：FAK marketable + 紧急抢占；`hasExitOfKind` 守卫全局**仅触发一次**。
- 内置 dust 护栏：减仓量按 size 精度（2 位）向下取整须 > 0（即 ≥ 0.01）才下单，否则跳过让常规逻辑接管，避免残尾反复空判遮蔽 TP/软退出。
- 推荐（保守上手）：`scalpLateScaleOutSeconds = 8~12`，`scalpLateScaleOutRatio = 0.5`（先减一半，观察滑点与避损效果再调）。

---

## 3. 与 V91「尾盘动态止损」的区别（别混淆）

这两块都在尾盘生效，但**职责不同、互补**：

| | V91 尾盘动态止损 | V92 尾盘退出韧性 |
|---|---|---|
| 触发条件 | **有条件**：尾盘 + 大回撤 / 跌破 bid 地板 | 杠杆1/3/4 多为**按时间无条件**；杠杆2 按失败重试 |
| 目的 | 命中即**全清**止损，并可越过 WICK_GUARD | 让"已决定的退出"**更快、更易成交、提前减仓** |
| 关键参数 | `scalpLateStopEnabled` / `scalpLateStopSeconds` / `scalpLatePeakDrawdown` / `scalpLateBidFloor` / `scalpDisableWickGuardOnLateStop` | 本文 7 参 |
| 关系 | 命中 V91 全清止损时，**杠杆4 减仓不再触发**（应走全清而非部分） | V91 决定"要不要退"，V92 决定"退得快不快/会不会被空盘卡住/要不要提前减" |

---

## 4. 推荐配置档位

> 全部默认关 = 完全沿用旧行为。按风险偏好选一档，逐步加码。

**保守起步（先验证不误伤）**
```
exitPollIntervalMs              = 1000
scalpLateFastPollSeconds        = 20
scalpLateFastPollMs             = 300
scalpEmergencyRetryCount        = 1
scalpEmergencyRetryIntervalMs   = 150
scalpLateIgnoreWorstPriceSeconds= 0        # 先不开
scalpLateScaleOutSeconds        = 0        # 先不开
scalpLateScaleOutRatio          = 0
```

**均衡（推荐，覆盖 A 类 + 部分 B 类）**
```
exitPollIntervalMs              = 1000
scalpLateFastPollSeconds        = 25
scalpLateFastPollMs             = 300
scalpEmergencyRetryCount        = 2
scalpEmergencyRetryIntervalMs   = 150
scalpLateIgnoreWorstPriceSeconds= 12
scalpLateScaleOutSeconds        = 10
scalpLateScaleOutRatio          = 0.5
```

**激进（最大化避免尾盘归零，牺牲部分盈利单的尾部收益）**
```
exitPollIntervalMs              = 1000
scalpLateFastPollSeconds        = 30
scalpLateFastPollMs             = 250
scalpEmergencyRetryCount        = 2
scalpEmergencyRetryIntervalMs   = 120
scalpLateIgnoreWorstPriceSeconds= 15
scalpLateScaleOutSeconds        = 12
scalpLateScaleOutRatio          = 0.7
```

---

## 5. 给 AI 的填写检查清单

1. 仅当 `mode == SCALP_FLIP` 才填这组参数；其他模式留默认。
2. 想开杠杆1/4 必须**成对**给值（任一为 0 = 关）。
3. `scalpLateFastPollMs` 不建议 < 200；想靠它在尾盘 <500ms 评估，前提是 WS 盘口在推送（否则 POLL 路径被 500ms 钳制）。
4. 杠杆4 是唯一能对抗"终局塌缩归零（B 类）"的手段；只开提速/重试**救不了**最后 2 秒穿价归零。
5. 杠杆4 会对**盈利单也减仓**——若策略高度依赖"赢单持有到结算吃满"，请把 `scalpLateScaleOutSeconds` 设小、`Ratio` 设低，或暂不开。
6. 命中 V91 全清止损时杠杆4 自动让位，无需手动协调。
7. 调参后用 decision-log 验证：看尾盘窗口内评估 tick 是否变密、是否出现 `LATE_SCALE_OUT` / 紧急重试记录、实际成交滑点是否可接受。

---

## 6. 实现位置（便于核对/排查）

- 节流与杠杆1b：`CryptoTailBracketExitService.evaluateAndExit`（`intervalMs` 计算）
- 杠杆1a 巡检间隔：`CryptoTailExitPoller.shouldPoll`
- 杠杆2/3：`CryptoTailBracketExitService.placeExitOrder`（重试循环 + `forceMarketable`）
- 杠杆4：`CryptoTailBracketExitService.decideTailDiffExit`（`LATE_SCALE_OUT` 分支 + dust 护栏）
- 字段定义：`entity/CryptoTailStrategy.kt`、`dto/CryptoTailStrategyDto.kt`（`CryptoTailStrategyScalpDto`）
- 迁移：`db/migration/V92__crypto_tail_scalp_late_exit_resilience.sql`
- 前端配置 UI：`frontend/src/pages/CryptoTailStrategyList.tsx`（"尾盘退出韧性" 区块）
