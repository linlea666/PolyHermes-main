# SCALP 极简双层止损推荐配置档：现货共识大脑 + 深地板兜底（V95）

## 一、架构总览

将 SCALP_FLIP 原有 6+ 层"盘口价格触发"的滞后止损，收敛为两层：

| 层 | 名称 | 触发源 | 角色 |
|---|---|---|---|
| 第 1 层（大脑·主决策） | `SPOT_LEAD_PRIMARY_STOP` 现货主止损 | 币安+欧意现货共识（WS 毫秒级） | 持仓全程，现货新鲜且危险并**持续确认**后，无视盘口价立即市价全清——砍在盘口塌陷之前 |
| 第 2 层（保险丝·兜底） | `HARD_FLOOR` 深底线 | 盘口 bestBid | 无条件兜底，专保现货数据陈旧/双源全断时的极端场景 |

**为什么这样设计**（两日决策日志复盘结论）：

- 6 笔止损样本中，现货共识 danger 状态 6/6 正确区分了"真反转"（应砍）与"插针误伤"（应扛）；
- 盘口触发的止损（机械止损/熔断/尾盘动态/深底线）全部砍在盘口已闪崩到 0.4~0.5 之后，成交价锁死大额亏损；
- 插针的现货特征是"亚秒级假穿后立即收回"→ 用 `persistMs` 持续确认窗口即可精确过滤，无需盘口维度任何判断。

决策优先级（`decideTailDiffExit` 内）：`HARD_FLOOR`（decideExit 顶部）> `SPOT_LEAD_PRIMARY_STOP` > 其余各层（推荐配置下全部关闭）。

## 二、推荐配置参数表

### 保留并开启（双层核心）

| 参数 | 推荐值 | 说明 |
|---|---|---|
| `scalpSpotLeadEnabled` | `true` | 现货领先层总开关（主止损的前提） |
| `scalpSpotLeadSource` | `CONSENSUS` | 币安+欧意双源共识（单源降级自动容错） |
| `scalpSpotLeadPrimaryStopEnabled` | `true` | **现货主止损总开关（本期新增）** |
| `scalpSpotLeadPrimaryStopPersistMs` | `600` | 危险持续确认 600ms（两日样本上 A/B 全对：真反转持续加深通过，0.5s 假穿被过滤） |
| `scalpSpotLeadPrimaryStopMinGapUsd` | `0` | 穿价深度下限不限（仅靠持续确认过滤；如需更保守可设 1~3 USD） |
| `scalpHardFloorRatio` | `0.30` | 深底线降到入场×0.30（原 0.50）：只做现货失效时的最后保险丝 |
| `scalpSpotLeadPushEnabled` | `true` | 现货 tick→退出重评估推送 |
| `scalpSpotLeadPushTailSeconds` | `300`（5m 周期） | 推送窗口=整周期才能全程毫秒级评估；**15m 周期需设 `900`**，否则盘中前段只有盘口 tick 驱动 |
| `scalpSpotLeadPushMinIntervalMs` | `80` | tick 风暴防抖；SPOT_PUSH 评估已绕过常规 `exitPollIntervalMs` 节流（按此值收紧），无需调小 `exitPollIntervalMs` |
| `scalpSpotLeadEntryGateEnabled` | `true` | 入场现货闸（拦截逆向 gap 单，保留） |
| `scalpSpotLeadMaxAgeMs` | `3000` | 现货新鲜度阈值（默认即可） |

### 全部关闭（被主止损取代的旧止损层）

| 参数 | 关闭值 | 原角色 |
|---|---|---|
| `scalpLateStopEnabled` | `false` | 尾盘动态止损 V91（峰值回撤/地板）——盘口触发，误伤主源 |
| `catastropheFloorRatio` | `0` | 熔断相对地板——盘口触发，砍在崩盘后 |
| `catastropheBidFloor` | `0` | 熔断绝对地板——同上 |
| `stopLoss.enabled`（退出预设止损） | `false` | 机械止损线（入场×(1-offset)）——盘口触发 |
| `minOdds`（动态退出软止损） | `0` | 裸盘口跌破赔率线——纯价格噪声触发 |
| dynamicExit 其余软退出阈值 | 全 `0` | 坍缩/反抽等盘口维度软退出 |
| `scalpLateScaleOutSeconds` | `0` | 尾盘主动减仓 V92——时间触发，不再需要 |
| `scalpSpotLeadEarlyStopSeconds` | `0` | 现货早警减仓 B2（仅尾盘 50% 一次）——被全程全清的主止损取代 |
| `scalpSpotLeadLateStopGateEnabled` | `false` | 尾盘硬止损现货门控（尾盘止损已关，无对象） |

### 保持现值（自然失效，无需改动）

- `enableSmartHardStop` / WICK_GUARD：盘口止损全关后无止损可豁免，自然失效；
- 模型方向止损（MODEL_FLIP/MODEL_INVALID）：属 Chainlink 模型触发，与盘口无关，可按个人偏好保留或经 dynamicExit 配置关闭；
- 止盈（TP/tp_limit）链路完全不受影响，照常运行。

## 三、参数调优指引

- **`persistMs`（核心旋钮）**：是"误伤过滤 vs 砍价延迟"的权衡。
  - 调小（300ms）→ 砍得更早、成交价更好，但浅假穿可能误触发；
  - 调大（1000ms+）→ 过滤更稳，但真反转时盘口可能已开始下塌。
  - 上线后用决策日志 `spotLeadPrimaryStopPersistElapsedMs` 字段的分布回测再调。
- **`minGapUsd`**：二级过滤。现货已穿价时要求 |spotGap| 达到此深度才触发；近翻转预警（未实际穿价，由 `flipDistanceSigma` 启用）不受此滤。BTC 建议 0 或 1~3，山寨高波动品种可适当放大。
- **`scalpHardFloorRatio=0.30`**：现货双源全断 + 真反转时的最大敞口约 -68%（接受该尾部风险换取插针免疫）。如不能接受，可回调 0.40。

## 四、决策日志可追踪字段（回测用）

| 字段 | 含义 |
|---|---|
| `reason` 含 `SPOT_LEAD_PRIMARY_STOP` | 本次退出由现货主止损触发 |
| `spotLeadPrimaryStopTriggered` | 主止损触发标志（布尔，便于过滤） |
| `spotLeadPrimaryStopPersistElapsedMs` | 危险持续确认进度（每条 EXIT_CHECK 都带，可回测 persistMs 分布） |
| `currentBestBid` | 触发时盘口价（证明砍在盘口塌陷之前） |
| `spotLeadGap` / `spotLeadExchange` / `spotLeadCrossed` / `spotLeadAgeMs` | 触发时现货快照 |
| `scalpSpotLeadPrimaryStopEnabled/PersistMs/MinGapUsd` | 当时生效的配置（EXIT_CHECK payload） |
| `spotEarlyWarningActed` | 现货真正改变了决策（telemetry 沿用） |

## 五、风险与边界

- **现货双源全断 + 真反转**：只剩 0.30 深地板兜底（敞口 -68%）。CONSENSUS 降级/陈旧已有日志，建议关注 `CONSENSUS_DEGRADED_*` / `spotLeadFresh=false` 频次；
- **fail-safe 链**：现货缺失/不新鲜 → 主止损不触发 → 完全回退旧行为（推荐配置下即"持有 + 深地板"）；
- **零回归**：`scalpSpotLeadPrimaryStopEnabled` 默认 `false`，存量策略与 TAIL_DIFF/BARRIER 模式行为完全不变；
- 本配置档为**推荐档**，需在 UI 手动应用，不改库默认值。

## 六、历史数据回验（预期效果）

| 案例 | 旧体系结果 | 双层体系预期 |
|---|---|---|
| 6/10 09:15 真反转 | -2.93（盘口崩到 0.4x 才砍） | 现货持续加深穿越 → 600ms 后约 bid 0.5~0.6 全清 → 约 -2.0 |
| 6/10 10:10 插针误伤 | -2.82（尾盘止损砍在最低点） | 现货仅 0.5s 瞬时穿越 → 持续确认过滤不触发；盘口最低 0.41 > 0.30 兜底线 → 持有到期 → **+0.26** |
| 6/9 trigger 335 真反转 | 大额亏损（盘口触发过晚） | 现货 -4.05 深穿 → 约 bid 0.68 全清 → 亏损减半以上 |
