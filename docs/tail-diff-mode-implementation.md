# 尾盘价差模式（TAIL_DIFF）实现原理与落地报告

> 本文供第三方 AI / 工程师审核。覆盖：设计目标、整体架构、数据模型、核心算法（评分/否决/分层/退出）、实时决策链路、历史反转统计、modelProb 接入、Polymarket 历史采集 PoC、自动参数建议，以及全部落地文件清单、配置默认值、已知限制与风险点。
>
> 适用代码版本：分阶段实现 P0–P5 完成，后端 `compileKotlin` 通过、前端 `tsc --noEmit` 通过、三语 i18n 一致。

---

## 1. 设计目标与约束

在现有 PolyHermes 加密价差交易系统中，新增**第 4 个独立交易模式** `TAIL_DIFF`（尾盘价差），与现有 `LEGACY_SPREAD(0)` / `BARRIER_HOLD(1)` / `BRACKET_DYNAMIC(2)` 并列。

针对 BTC/ETH 的 5 分钟 / 15 分钟 Up/Down 市场，在**临近结算的尾盘窗口**内，综合目标价、现价、剩余时间、Polymarket 赔率、盘口深度、价差、`diff_sigma`、历史反转率、机会评分、临结算 EV 等因子，独立做出入场 / 加权 / 退出决策。

### 硬性约束（贯穿实现）

| 约束 | 落地方式 |
|---|---|
| 不重写账户/下单/风控/日志系统 | 复用 `placeOrderForTrigger`、`CryptoTailRiskService`、`CryptoTailEntryGuardService`、`CryptoTailDecisionRecorder` |
| 优先复用现有交易执行链路 | 入场决策通过后直接走 BARRIER/BRACKET 同款 FAK 定价 + 下单 + 退出引擎 |
| 新增"策略大脑"+ 配置项 | 新增 `CryptoTailScoreEngine` 等组件 + 约 40 个 `tail_diff_*` 配置列（全部可前端配置，不写死） |
| 所有 BUY/SKIP/WATCH 必须记录原因 | `CryptoTailTailDiffDecisionService` 对三类结局均落库 `CryptoTailDecisionEvent` |
| 所有候选机会都要记录（非仅成交单） | 每 5s 节流记 `TAIL_DIFF_SCORE_COMPUTED` / `TAIL_DIFF_SKIP` 决策日志 |
| 单市场最多入场一次 | `CryptoTailEntryGuardService` 重复持仓检查（`countOpenMarketPositionByAccountMarketPeriodOutcome`） |
| 禁止马丁 | `CryptoTailTailDiffSizingPolicy` 仅按评分分层给倍率，无"亏损加仓" |
| 数据/盘口/目标价异常必须禁止交易 | 评分引擎硬否决：价源/σ/盘口/数据新鲜度任一异常 → SKIP |

---

## 2. 整体架构

```
WS 价格/盘口 tick
        │
        ▼
CryptoTailStrategyExecutionService.tryTriggerWithPriceFromWs
   when (strategy.mode) { ... TAIL_DIFF -> ... }              ← 模式分发（复用现有触发入口）
        │
        ▼
CryptoTailTailDiffDecisionService.evaluate()                  ← 决策编排（P1 大脑）
   ├─ PeriodPriceProvider          (open/close/σ/价源新鲜度，复用)
   ├─ BarrierProbability           (pWin / safeRatio=diff_sigma / 方向，复用 BARRIER 内核)
   ├─ CryptoTailReverseVelocityTracker (反抽速度 σ/s)
   ├─ TailReversalStatsLookup      (历史反转 modelProb；Real 实现来自 P2/P3)
   ├─ CryptoTailScoreEngine        (7 项加权评分 + 硬否决 + 分层)
   ├─ CryptoTailTailDiffSizingPolicy (分层金额)
   └─ TailDiffExitPresetResolver   (退出预设解析 + 冻结)
        │  BUY → 返回 amount/tier/exitPresetJson
        ▼
placeOrderForTrigger(...)  (复用 BARRIER/BRACKET 的 FAK 定价/下单/重试/日志)
        │
        ├─ saveTriggerRecord：把 TAIL_DIFF 入场快照（score/tier/diff_sigma/exit_preset_json…）写入 trigger 新列
        │
        ▼
CryptoTailBracketExitService.decideExit
   if (mode == TAIL_DIFF) decideTailDiffExit(...)              ← 退出（按冻结预设，独立逻辑）
```

设计要点：
- **决策与下单分离**：`DecisionService` 只产出决策，绝不直接发单；发单仍走唯一的 `placeOrderForTrigger`。
- **快照冻结**：入场瞬间把退出预设序列化进 `trigger.exit_preset_json`，退出时优先读快照，避免中途改策略表污染在途持仓。
- **数据传递用内存缓存**：`tailDiffEntrySnapshotCache`（Caffeine）把决策元数据从决策点透传到 `saveTriggerRecord`，避免大改函数签名。

---

## 3. 数据模型（Flyway 迁移）

### 3.1 V62 — `crypto_tail_strategy` 扩展（约 40 列）+ trigger 扩展（7 列）

`crypto_tail_strategy` 新增列（全部带默认值，对历史其它模式记录零影响）：

| 分组 | 关键列（默认值） |
|---|---|
| 方向 | `tail_diff_direction=0`（0 自动/1 只Up/2 只Down） |
| 入场窗口 | `tail_diff_window_start_seconds=150`、`tail_diff_window_end_seconds=60`、`tail_diff_min_remaining_seconds=50`、`tail_diff_confirm_ticks=2` |
| 价格区间 | `tail_diff_min_price=0.88`、`tail_diff_max_price=0.93`、`tail_diff_hard_max_price=0.94` |
| 模型/EV | `tail_diff_min_model_prob=0.95`、`tail_diff_min_edge=0.025`、`tail_diff_cost_buffer=0.01`、`tail_diff_min_diff_sigma=1.8`、`tail_diff_model_prob_source='HYBRID'`、`tail_diff_stats_min_samples=50`、`tail_diff_stats_lookback_days=180`、`tail_diff_stats_data_source='BINANCE'`（V69，BINANCE/POLYMARKET） |
| 盘口质量 | `tail_diff_max_spread=0.02`、`tail_diff_depth_multiplier=3.0`、`tail_diff_max_orderbook_age_ms=2000`、`tail_diff_max_price_age_ms=2000` |
| 反抽 | `tail_diff_reverse_velocity_window_seconds=10`、`tail_diff_max_reverse_velocity_sigma=0.30` |
| 评分权重（和=100） | diff=25、time=15、odds_underprice=20、odds_lag=10、history=15、book=10、data=5 |
| 分层阈值 | `tail_diff_min_entry_score=70`、`tail_diff_premium_score=80`、`tail_diff_top_score=90` |
| 仓位分层 | `tail_diff_base_amount=1`、normal_mult=1.0、premium_mult=1.5、top_mult=2.0、`tail_diff_max_amount_per_order=5` |
| 退出预设 | `tail_diff_exit_preset_normal_json` / `premium_json` / `top_json`（TEXT，空则用代码默认） |
| 专属风控 | `tail_diff_daily_loss_limit_usdc`（NULL 复用全局）、`tail_diff_consec_loss_pause_count=2`、`tail_diff_consec_loss_stop_count=3` |

`crypto_tail_strategy_trigger` 新增 7 列（其他模式恒 NULL）：`score`、`tier`、`exit_preset_json`、`raw_diff`、`diff_pct`、`diff_sigma`、`model_prob_source`；并建索引 `idx_ct_trigger_tier`。

### 3.2 V63 — `crypto_tail_reversal_stat`（历史反转聚合）

分桶唯一键：`(coin, interval_seconds, outcome_index, diff_sigma_bucket, odds_bucket, remaining_bucket, lookback_days, data_source)`，存 `sample_count`、`reversed_count`、`model_prob`(=1−反转率)。`data_source` 维度同时支持 `BINANCE` 与 `POLYMARKET`。

### 3.3 V64 — `crypto_tail_polymarket_price_history`（PoC 原始缓存）

缓存从 CLOB `/prices-history` 拉取的历史赔率点，唯一键 `(token_id, t_unix)`，仅服务 PoC 复盘，不参与交易主链路。

---

## 4. 核心算法

### 4.1 diff_sigma 与方向

直接复用 BARRIER 内核 `BarrierProbability.winProbTerminal(rawDiff, σ, remaining)`：

- `raw_diff = close − open`（带符号）
- `diff_sigma = |raw_diff| / (σ × √remaining)` —— 等价于现有 `safeRatio`
- `modelSide`、`barrierPWin` 由同一函数给出

σ 来自 `PeriodPriceProvider.getSigmaPerSqrtS(...)`（复用策略的 `sigmaScale/sigmaMethod/ewmaLambda`）。

### 4.2 机会评分模型（`CryptoTailScoreEngine`）

7 个分项各自归一化到 `[0,1]`，再乘以策略权重（权重和若 ≠100 则按比例归一化保证总分 0–100），加和取整 clamp 到 `[0,100]`：

| 分项 | 归一化逻辑 |
|---|---|
| diff（价差优势） | `diff_sigma` 在 `[minDiffSigma, 3×minDiffSigma]` 线性归一 |
| time（时间优势） | `1 − (remaining − windowEnd)/(windowStart − windowEnd)`，越接近窗口末端分越高 |
| oddsUnderprice（赔率低估） | `edge` 在 `[0, 0.10]` 归一（edge=10% 视满分） |
| oddsLag（赔率滞后） | `modelProb − midImpliedProb` 在 `[0, 0.15]` 归一 |
| history（历史胜率） | 样本不足/FALLBACK 给中性 0.5；否则 `modelProb` 在 `[0.90, 1.00]` 归一 |
| book（盘口质量） | spread 分（spread/maxSpread 反向）与深度分（min(bid,ask)深度 vs 需求深度）各占 0.5 |
| data（数据可靠性） | orderbookAge / priceAge 相对各自上限的新鲜度，各占 0.5 |

**硬否决（vetoes，命中任一 → SKIP，独立于评分）**，按短路顺序：

1. `MODEL_DIRECTION_MISMATCH`：`modelSide != outcomeIndex`
2. `ASK_TOO_HIGH`（ask > hardMaxPrice）、`BID_BELOW_MIN_PRICE`、`BID_ABOVE_MAX_PRICE`
3. `MODEL_PROB_TOO_LOW`、`EDGE_TOO_LOW`、`DIFF_SIGMA_TOO_LOW`
4. `WINDOW_TOO_LATE`（remaining < windowEnd）、`WINDOW_TOO_EARLY`（remaining > windowStart）、`REMAINING_SECONDS_TOO_SHORT`
5. `ORDERBOOK_NO_ASK`、`SPREAD_TOO_WIDE`、`DEPTH_TOO_SHALLOW`、`ORDERBOOK_STALE`、`PRICE_STALE`
6. `PRICE_RETRACING_FAST`（反抽 σ/s 超限）

> 注：即使命中否决也会算出分数（便于复盘"它本来能得多少"），但 `tier` 置 null、`passed=false`。

**分层判定**（`TailDiffTier.fromScore`）：`score < minEntry → null(WATCH)`；`≥ top → TOP`；`≥ premium → PREMIUM`；否则 `NORMAL`。

### 4.3 modelProb 来源切换（HYBRID/STATS/FALLBACK）

```
STATS    : 统计样本足(≥statsMinSamples) → 用历史 modelProb；否则回退 BarrierPWin（标 STATS_FALLBACK）
FALLBACK : 始终用 BarrierPWin
HYBRID   : 样本足 → HYBRID_STATS（历史）；否则 HYBRID_FALLBACK（BarrierPWin）
```

历史统计来自 `TailReversalStatsLookup.queryReversalProb(Query)`，按 `(coin, interval, outcome, diffSigmaBucket, oddsBucket, remainingBucket, lookbackDays)` 命中聚合表。决策日志同时记录 `statsSampleCount` 与 `statsReversalProb`（即 SHADOW 对比口径）。

### 4.4 分层下注（`CryptoTailTailDiffSizingPolicy`）

`amount = baseAmount × tierMultiplier`，clamp 到 `[1 USDC, maxAmountPerOrder, spendableBalance]`；余额不足返回 0（调用方按"金额不足"SKIP）。**不做 Kelly、不做 gapBoost、不做马丁**。

### 4.5 退出管理（`TailDiffExitPreset` + `decideTailDiffExit`）

三档独立预设（可前端配 JSON，缺省走代码默认）：

| 档 | holdToExpiry | TP | StopLoss | DynamicExit |
|---|---|---|---|---|
| NORMAL | false | 0.98 全卖 | offset 0.20 / minPrice 0.70 | minDiffσ 1.3 / minProb 0.88 / minOdds 0.80 / maxRevVel 0.40 |
| PREMIUM | false | 0.99 全卖 | 关闭 | minDiffσ 1.0 / minProb 0.85 / minOdds 0.75 / maxRevVel 0.50 |
| TOP | **true（持有到结算）** | 关 | 关 | 关 |

退出评估顺序（`CryptoTailBracketExitService.decideTailDiffExit`，mode=TAIL_DIFF 时早分流）：
1. `holdToExpiry=true` → 持有到结算，返回 null
2. StopLoss：`bestBid ≤ max(entryFill×(1−offset), minPrice)` → HARD_STOP
3. DynamicExit：`pWin < minModelProbAfterEntry` / `safeRatio < minDiffSigmaAfterEntry` / `bestBid < minOddsAfterEntry` / 方向翻转 → 退出
4. TpLimit：`bestBid ≥ tp.price` → TP1
5. 否则继续持有

退出预设**优先读 `trigger.exit_preset_json` 冻结快照**，缺失才回退当前策略表配置。

---

## 5. 实时决策链路（端到端）

`CryptoTailTailDiffDecisionService.evaluate()` 步骤：

1. 价源就绪检查 → 取 open/close（缺失 SKIP）
2. 反抽速度采样（持续喂入 tracker）
3. 计算 σ → `BarrierProbability` 得 pWin/diff_sigma/方向（任一不可用 SKIP）
4. 方向闸（`tailDiffDirection` 限制 Up/Down）
5. 查历史统计 → 按 source 决定 modelProb 与来源标签
6. 算 effectiveCost（含 takerFee）/ edge / midImpliedProb
7. 反抽速度（仅反向时报 σ/s）
8. 组装 `Input` → `ScoreEngine.evaluate`
9. 评分快照日志（每 5s 节流，所有 tick 都参与候选但不刷库）
10. 否决 → SKIP（清零 confirm 计数）
11. 未分层（分数 < minEntry）→ WATCH（清零）
12. 连续确认：累计 `confirmTicks` 次后才放行（防瞬时抖动）
13. 分层金额 + 退出预设冻结
14. BUY 决策日志 → 返回 `BUY`（带 amount/tier/exitPresetJson）

执行层 `tryTriggerWithPriceFromWs` 的 TAIL_DIFF 分支：
- WATCH/SKIP → 直接 return
- BUY → 跑 `cryptoTailRiskService.checkRiskGate`（复用全局风控）
- SHADOW 模式 → 记 `TAIL_DIFF_SHADOW` 日志但不下单
- 否则 → 写入 `tailDiffEntrySnapshotCache` → `placeOrderForTrigger`（复用 FAK 定价/下单/重试链路）
- `saveTriggerRecord` 取出快照写入 trigger 的 7 个新列

`checkEntryMarketQuality` 已做 **模式感知**：TAIL_DIFF 用自身 `tailDiffMinRemainingSeconds` / `tailDiffWindowStartSeconds`，而非 BARRIER 的窗口，避免冲突。

---

## 6. 历史反转统计（P2）

### 6.1 BINANCE 源（`CryptoTailReversalHarvestService`）

- 拉 Binance 1m K 线（回溯 `lookbackDays`），按 interval 对齐切周期
- 每周期：`open=`首根 open，`finalClose=`末根 close → 结算方向
- 滑动窗口（最近 60 根 1m 绝对变动）估 `σ_per_√s`
- 周期内每个 1m 边界作观测点：算 `diff_sigma`、领先方向，判断是否被最终结算反转
- 按 `(outcome, diffSigmaBucket, remainingBucket)` 累计，`model_prob = 1 − 反转率`
- 幂等：先删旧分桶再写新；`odds_bucket` 恒为 `ANY`（Binance 无赔率）

### 6.2 接入（P3）

`RealTailReversalStatsLookup`（`@Component`）通过 `@ConditionalOnMissingBean(TailReversalStatsLookup)` **自动取代 Noop**，ScoreEngine/DecisionService 无需改一行即可拿到历史 modelProb。查询先精确命中 `oddsBucket`，再回退 `ANY`。

### 6.3 研究接口与前端

- `/reversal/backfill`（BINANCE 回填）、`/list`、`/export`(CSV)，均支持 `dataSource` 维度
- 前端 `CryptoTailReversalResearchModal`：币种/周期/回溯天数/数据源选择、一键回填、分桶表格、CSV 导出

---

## 7. Polymarket 历史采集 PoC（P4）

> 明确为 PoC，失败不阻塞主链路（任意环节异常即跳过该周期）。

- `PolymarketHistoricalPriceSource` 接口 + `GammaDataPolymarketHistoricalPriceSource` 实现：
  - 由确定性 slug `${coin}-updown-${5m|15m}-${periodStartUnix}` 经 Gamma `getEventBySlug` 解析 Up tokenId
  - 经 CLOB 新增端点 `GET /prices-history?market={tokenId}&startTs&endTs&fidelity=1` 拉历史赔率
  - 命中 `crypto_tail_polymarket_price_history` 缓存优先，否则拉取并落缓存
- `CryptoTailPolymarketReversalHarvestService`：按"**真实赔率桶 × 剩余时间**"统计反转率（`diff_sigma_bucket='ANY'`，与 BINANCE 版互补），写 `data_source=POLYMARKET`；`maxPeriods` 限制采集请求量（默认 300）
- 接口 `/reversal/backfill-polymarket`；list/export 已支持 `dataSource`，天然支持**双数据源对比**
- 前端：研究弹窗加"数据源 / 最大周期数"选择 + Polymarket 回填(PoC) 按钮 + 赔率桶列

**互补语义**：BINANCE 回答"diff_sigma 多强时不易被反转"；POLYMARKET 回答"赔率到 0.9x 后还有多大概率被反转"。

---

## 8. 自动参数建议（P5）

`CryptoTailTailDiffParamAdvisor`：以"**已结算 + 带入场快照（score 非空）**"的真实成交为唯一 ground truth：

- 维度分桶：score（5 分宽）/ 价格（odds 桶）/ diff_sigma 桶 / 剩余时间桶 / tier；每桶统计样本数、胜率、单笔均盈亏、总盈亏
- 反推建议：
  - `tailDiffMinEntryScore`：选使"score≥阈值"子集平均盈亏>0 的最低阈值（无正则取均盈亏最大阈值）
  - `tailDiffMinPrice/MaxPrice`：取盈利价格桶的连续区间上下界
  - `tailDiffMinDiffSigma`：选使子集均盈亏>0 的最低 diff_sigma 阈值
- 置信度：样本 ≥100 HIGH / ≥50 MEDIUM / 否则 LOW；总样本 < `minSamples`（默认 30）只给分桶不出建议
- **仅推荐，绝不自动写入**；用户需手动到表单调整后保存

接口 `/tail-diff/advisor`；前端 `CryptoTailTailDiffAdvisorModal`（策略行灯泡入口，仅 TAIL_DIFF 显示）展示总览 + 建议表（当前值 vs 建议值 + 依据 + 置信度）+ 各维度分桶对比，顶部明确标注"仅推荐"。

---

## 9. 前端表单与评分预览（P1 收尾）

- `mode=3 尾盘价差` 单选项 + 完整参数区（窗口/价格/概率边际/盘口质量/反抽/7 项评分权重/分层阈值/分层退出预设 JSON/风控），含权重和=100 的前端校验
- 评分预览 `/tail-diff/preview`：用表单参数 + 模拟盘口/价源实时算出总分、tier、分项、diff_sigma、modelProb 来源、推荐金额与硬否决
- DTO/Service：`CryptoTailStrategyService` 新增 `resolveTailDiffCreate/Update` + `isTailDiffParamsValid`（校验价格区间递增、权重和=100、分层阈值递增、方向枚举等），接入 `entityToDto`
- 三语 i18n（zh-CN/zh-TW/en）全部补齐

---

## 10. 落地文件清单

### 后端（新增）

| 文件 | 职责 |
|---|---|
| `enums/TradingMode.kt` | 新增 `TAIL_DIFF(3)` |
| `db/migration/V62__crypto_tail_tail_diff_mode.sql` | 策略表 + trigger 表扩展 |
| `db/migration/V63__crypto_tail_reversal_stat.sql` | 反转聚合表 |
| `db/migration/V64__crypto_tail_polymarket_price_history.sql` | PoC 赔率缓存表 |
| `service/cryptotail/taildiff/TailDiffBuckets.kt` | diff_sigma/赔率/剩余时间分桶 |
| `service/cryptotail/taildiff/TailReversalStatsLookup.kt` | 反转统计查询接口 + Noop + Bean 配置 |
| `service/cryptotail/taildiff/TailDiffExitPreset.kt` | 退出预设模型 + 解析器 + TailDiffTier |
| `service/cryptotail/taildiff/CryptoTailReverseVelocityTracker.kt` | 反抽速度 σ/s |
| `service/cryptotail/taildiff/CryptoTailTailDiffSizingPolicy.kt` | 分层金额 |
| `service/cryptotail/taildiff/CryptoTailScoreEngine.kt` | 评分大脑（7 项加权 + 否决 + 分层） |
| `service/cryptotail/taildiff/CryptoTailTailDiffDecisionService.kt` | 实时决策编排 |
| `service/cryptotail/taildiff/CryptoTailTailDiffParamAdvisor.kt` | 自动参数建议（P5） |
| `service/cryptotail/reversal/CryptoTailReversalHarvestService.kt` | BINANCE 反转回填 |
| `service/cryptotail/reversal/RealTailReversalStatsLookup.kt` | 反转统计真实实现（替换 Noop） |
| `service/cryptotail/reversal/PolymarketHistoricalPriceSource.kt` | Polymarket 历史价源接口 |
| `service/cryptotail/reversal/GammaDataPolymarketHistoricalPriceSource.kt` | Gamma+CLOB 实现（PoC） |
| `service/cryptotail/reversal/CryptoTailPolymarketReversalHarvestService.kt` | POLYMARKET 反转回填（PoC） |
| `controller/cryptotail/CryptoTailTailDiffController.kt` | `/preview` + `/advisor` |
| `controller/cryptotail/CryptoTailReversalResearchController.kt` | `/backfill` `/backfill-polymarket` `/list` `/export` |
| `entity/CryptoTailReversalStat.kt`、`entity/CryptoTailPolymarketPriceHistory.kt` | 实体 |
| `repository/CryptoTailReversalStatRepository.kt`、`CryptoTailPolymarketPriceHistoryRepository.kt` | 仓储 |
| `dto/CryptoTailReversalResearchDto.kt`、`dto/CryptoTailTailDiffAdvisorDto.kt` | DTO |

### 后端（改动）

| 文件 | 改动 |
|---|---|
| `entity/CryptoTailStrategy.kt`、`entity/CryptoTailStrategyTrigger.kt` | 新增 TAIL_DIFF 字段 |
| `service/cryptotail/CryptoTailStrategyExecutionService.kt` | TAIL_DIFF 分发分支、快照缓存、模式感知市场质量检查、saveTriggerRecord 写新列 |
| `service/cryptotail/CryptoTailBracketExitService.kt` | `decideTailDiffExit` + 冻结预设解析 |
| `service/cryptotail/CryptoTailStrategyService.kt` | TAIL_DIFF 参数解析/校验/映射 |
| `dto/CryptoTailStrategyDto.kt` | Create/Update/Dto 新增 43 个 tailDiff 字段 |
| `api/PolymarketClobApi.kt` | 新增 `/prices-history` |
| `api/PolymarketGammaApi.kt` | 复用 `getEventBySlug` |
| `enums/ErrorCode.kt` + i18n properties | 新增错误码 + 三语消息 |
| `repository/CryptoTailStrategyTriggerRepository.kt` | `findResolvedTailDiffByStrategyId` |
| `service/cryptotail/CryptoTailDecisionPayloads.kt` | `TailDiffScorePayload` |

### 前端

| 文件 | 改动 |
|---|---|
| `types/index.ts` | TAIL_DIFF / 预览 / 反转研究 / Polymarket PoC / 参数建议全部类型 |
| `services/api.ts` | preview / advisor / reversal* / reversalBackfillPolymarket |
| `pages/CryptoTailStrategyList.tsx` | mode=3 表单区 + 评分预览 + 研究/建议入口 |
| `pages/CryptoTailReversalResearchModal.tsx` | 反转研究弹窗（双数据源对比 + PoC 回填） |
| `pages/CryptoTailTailDiffAdvisorModal.tsx` | 参数建议弹窗 |
| `locales/{zh-CN,zh-TW,en}/common.json` | 全部 i18n key |

---

## 11. 第一版默认配置（BTC 5m 起步）

入场窗口 60–150s、价格区间 0.88–0.93（硬上限 0.94）、minModelProb 0.95、minEdge 0.025、minDiffSigma 1.8、maxSpread 0.02、深度 3×、反抽上限 0.30 σ/s、连续确认 2 tick、modelProb 来源 HYBRID（样本阈值 50、回看 180 天）、评分权重 25/15/20/10/15/10/5、分层阈值 70/80/90、倍率 1.0/1.5/2.0、单单上限 5 USDC、连亏暂停 2 / 停止 3。

---

## 12. 验收标准对照

| 计划书要求 | 落地证据 |
|---|---|
| 独立模式 | `TradingMode.TAIL_DIFF` |
| 自动/手动领先方向 | `tailDiffDirection` 0/1/2 + 方向闸 |
| diff_sigma 计算 | `BarrierProbability.safeRatio` 复用 |
| 0–100 评分 + 硬否决 | `CryptoTailScoreEngine`（7 项加权 + 12 类 veto） |
| 分层下注 | `CryptoTailTailDiffSizingPolicy`（无马丁） |
| 退出管理 | `decideTailDiffExit` + 三档预设 + 快照冻结 |
| 实时决策流程 | `DecisionService.evaluate` 14 步 + confirmTicks |
| 全量候选日志 | SCORE/SKIP/WATCH/BUY 决策事件落库 |
| 历史反转统计 | V63 + Harvest + RealLookup |
| 单市场入场一次 | EntryGuard 重复持仓检查 |
| 数据异常禁止交易 | 价源/σ/盘口/新鲜度否决 |
| 自动参数建议 | `ParamAdvisor`（仅推荐不写入） |

---

## 13. 已知限制与风险点（请审核重点关注）

1. **快照透传用内存缓存**：`tailDiffEntrySnapshotCache`（Caffeine）从决策点传递到 `saveTriggerRecord`。若进程在两步间崩溃，trigger 新列可能落空（但订单本身仍正确，仅复盘字段缺失）。属务实取舍，未改 `placeOrderForTrigger` 签名。
2. **Polymarket PoC 规模受限**：5m 市场 180 天约 5 万周期，逐周期 Gamma+CLOB 请求不现实，故用 `maxPeriods`（默认 300）只取最近 N 个已结算周期；结论仅作方向性参考，非全量统计。
3. **POLYMARKET 反转统计的结算判定**：用 Up 代币历史价末点 `≥0.5` 判定结算方向（已结算市场收敛到 1/0），极端薄盘/异常行情下可能有偏差。
4. **diff_sigma 历史 σ 估计**：BINANCE 回填用最近 60 根 1m 绝对变动估 σ，窗口较短在剧烈行情下噪声偏大。
5. **参数建议样本依赖**：阶段四建议质量强依赖实盘积累的已结算样本量；冷启动阶段（< minSamples）只给分桶不出建议，符合预期但需用户知晓。
6. **modelProb 历史命中率**：HYBRID 在无对应分桶样本时回退 BarrierPWin（标 `HYBRID_FALLBACK`），需观察 STATS 命中率是否达预期，否则历史分项长期取中性 0.5。
7. **窗口语义**：`windowStart(150) > windowEnd(60)` 表示"剩余时间"区间 `[60,150]`，评分 time 分项与否决窗口语义需保持一致（已在 ScoreEngine 注释明确）。

---

## 14. 建议的上线步骤

1. 测试库执行迁移 V62/V63/V64，确认应用启动正常（实体/列对齐）。
2. 用 preview（以实盘 evaluate 为准）观察当前市场决策与评分，积累决策日志与候选样本。
3. 跑 BINANCE 反转回填（180 天），可选跑 Polymarket PoC 做双源对比。
4. 用参数建议器查看分桶与推荐，手动微调阈值后保存。
5. 以最小金额实盘灰度，结合每日盈亏/连亏风控观察。

---

## 15. 实盘审查后的增强（P0/P1 + 入场分段 + 退出滑点）

### 15.1 P0 风控与动态退出补线
- **TAIL_DIFF 风控参数接线**（`CryptoTailRiskService`）：`tailDiffDailyLossLimitUsdc` 在 TAIL_DIFF 模式覆盖全局日亏限额（为空回退全局）；`tailDiffConsecLossPauseCount/StopCount` 通过 `checkTailDiffConsecutiveLossGate` 生效（此前定义未接线）。
- **动态退出条件强制执行**（`CryptoTailBracketExitService.decideTailDiffExit` → `dynamicExitDecision`）：`maxReverseVelocitySigma`（反抽速度过快）与 `maxDiffRetracePct`（价差/σ 回撤过深）此前配置未读取，现已纳入退出判定。
- **反抽速度状态喂数**：`CryptoTailReverseVelocityTracker` 此前仅在入场评估喂数，持仓期无数据。现于 `computeHoldingState` 内 `observe()`，使持仓期速度退出可触发。

### 15.2 P1 安全网与 OCO 规避
- **TOP 档（holdToExpiry）安全网**：原 `holdToExpiry=true` 对不利波动零保护。现 `dynamicExitDecision(hardOnly=true)` 允许 `minOddsAfterEntry`/`maxReverseVelocitySigma`/`maxDiffRetracePct`/模型方向翻转等硬条件在持有到结算下仍触发退出（不引入硬止盈）。
- **强制 FAK 退出规避 OCO 阻塞**：TAIL_DIFF 的 TP1/TP2 一律走 FAK，避免挂单型止盈长期占用 pending 守卫而阻塞止损。

### 15.3 入场分段（copy-overlay）
- 新增 `tailDiffEntrySegmentsJson`（V65 迁移）。`TailDiffEntrySegmentResolver` 按剩余秒命中分段，`copy()` 覆盖 `{minEntryScore, minDiffSigma, minEdge, hardMaxPrice, window 上下界}` 后交回原 ScoreEngine/分层/退出冻结链路，零回归。
- `exit_tier_bias`：分段可指定退出分层偏置，覆盖评分分层用于冻结退出预设。
- 空/NULL 段 → 行为完全不变（单窗口）；非空但 remaining 不落任何段 → `WINDOW_NO_SEGMENT` SKIP（opt-in）。
- `checkEntryMarketQuality` 用 `windowEnvelope` 取所有段并集做预过滤，确保早窗能通过粗筛。

### 15.4 退出滑点可配
- **全局**：新增 `exitFakSlippage`（V66 迁移，默认 0.02），`computeExitPrice(orderType, bestBid, slippage)` 接收滑点参数，所有模式 FAK 卖出限价 = `bestBid - exitFakSlippage`（裁剪到合法价格区间）。
- **TAIL_DIFF 专属覆盖**（退出预设 JSON 内 `execution` 块，随入场快照冻结）：
  - `tp_slippage` / `stop_slippage`：按 TP / STOP 分别覆盖全局 `exitFakSlippage`（缺省回退全局）。
  - `worst_price`：FAK 卖出限价绝对底线，`exitPrice = max(bestBid - slippage, worst_price)`；当 `bestBid` 低于底线时 FAK 不成交，避免滑点把仓位贱卖（语义为"宁可不卖，留到结算"）。
  - 序列化/反序列化已在 `TailDiffExitPreset.toMap()`、`TailDiffExitPresetResolver.parsePreset()` 与 `CryptoTailBracketExitService.parseTailDiffPresetFromMap()` 三处对齐；默认全 null → 行为不变。

> 已显式延后：未成交 FAK 的有界改价重试循环（`max_exit_retries`/`reprice_step`/`reprice_interval_ms`/`reprice_seq`）与对账器，因涉及状态机与并发守卫改造，单独排期。

---

## 16. 历史反转统计精度增强（V67 + 细采样 + 去重 + MAE/MFE/虚拟退出）

### 16.1 数据库与实体
- V67 在 `crypto_tail_reversal_stat` 追加可加列（旧行/旧查询不受影响）：`sampling_seconds`、`distinct_period_count`、`mae_avg`、`mfe_avg`、`virtual_tp_rate`、`virtual_stop_rate`、`virtual_win_rate`、`virtual_pnl_avg`。实体 [CryptoTailReversalStat.kt](backend/src/main/kotlin/com/wrbug/polymarketbot/entity/CryptoTailReversalStat.kt) 同步新增字段（指标列可空）。

### 16.2 采样与去重
- **细采样（BINANCE）**：[CryptoTailReversalHarvestService.kt](backend/src/main/kotlin/com/wrbug/polymarketbot/service/cryptotail/reversal/CryptoTailReversalHarvestService.kt) `backfill` 新增 `samplingSeconds`（默认 60=1m 旧行为；1=1s 细采样）。1s 数据量巨大，自动把回溯天数收敛到 ≤14 天（约 121 万根、1210 页，落在 2000 请求上限内）并告警。Binance 无原生 5s K 线，故仅提供 1s/1m。σ 滑动窗口改为按时间跨度（3600s）自适应步数，1m 下仍为 60 步（向后兼容）。
- **first-satisfy 去重**：BINANCE 与 POLYMARKET 两个回填器都改为"同一周期对同一桶仅记最早命中的观测点"，消除相邻观测点强相关导致的样本虚高，使 `model_prob` 估计更可信。`distinct_period_count` 记录去重后贡献该桶的周期数。

### 16.3 MAE/MFE 与虚拟括号退出
- 统一口径在 [TailReversalMetrics.kt](backend/src/main/kotlin/com/wrbug/polymarketbot/service/cryptotail/reversal/TailReversalMetrics.kt)：以"领先方向胜率 p"为路径变量。
  - POLYMARKET：p = 真实赔率（领先方向）。
  - BINANCE：p = `BarrierProbability.winProbTerminal` 推导的终值胜率（同样换算到领先方向）。
  - MAE/MFE = 入场后相对入场 p 的最大不利/有利偏移。
  - 虚拟括号退出：成本=入场 p；前瞻路径先触达 TP(p≥0.99) 按 0.99 结清、先触达 STOP(p≤0.70) 按 0.70 结清，否则按结算结清（领先方向赢=1，输=0）。输出 `virtual_tp_rate`/`virtual_stop_rate`/`virtual_win_rate`/`virtual_pnl_avg`。

### 16.4 接口与前端
- 回填请求 [ReversalBackfillRequest] 增加 `samplingSeconds`；`ReversalStatDto` + CSV 导出新增全部精度列；[控制器](backend/src/main/kotlin/com/wrbug/polymarketbot/controller/cryptotail/CryptoTailReversalResearchController.kt) 透传与映射。
- 研究弹窗 [CryptoTailReversalResearchModal.tsx](frontend/src/pages/CryptoTailReversalResearchModal.tsx)：新增采样间隔选择（1m/1s）、MAE/MFE/虚拟胜率/TP%/STOP%/单位盈亏列、加权命中率汇总（加权维持概率、加权虚拟胜率、样本总数、低样本分桶数）、低样本分桶标记（橙色 Tag + 行淡化，阈值 30）、以及 POLYMARKET 数据源的 PoC 警示横幅。三语 i18n 已补齐。

> 口径说明：BINANCE 的 MAE/MFE/虚拟退出基于 `winProbTerminal` 概率代理（非真实赔率）；POLYMARKET 基于真实赔率路径但为 PoC 样本。两者均作研究参考，不直接进入实盘下单决策（实盘 modelProb 仍由 ScoreEngine 的 STATS/HYBRID 策略消费 `model_prob`）。

---

## 17. 统一修复（实盘审查 P0/P1/P2）

本轮修复严格按"根因优先 / 全局视角 / 复用决策 / 保守修改"推进，迁移 V68/V69 均为可加/删列，旧记录零影响。

### 17.1 删除策略级影子模式 `tailDiffShadowMode`（V68）
- 影子空跑能力已由 preview（统一走实盘 evaluate）与决策日志覆盖，移除字段/DTO/前端/执行拦截/payload/文档全链路噪声。
- 迁移 [V68__crypto_tail_drop_shadow_mode.sql](backend/src/main/resources/db/migration/V68__crypto_tail_drop_shadow_mode.sql) `DROP COLUMN tail_diff_shadow_mode`（不回改 V62）。

### 17.2 退出预设键名 camelCase/snake_case 兼容
- [TailDiffExitPreset.kt](backend/src/main/kotlin/com/wrbug/polymarketbot/service/cryptotail/taildiff/TailDiffExitPreset.kt) 引入 `Map.pick(snake,camel)`/`pickMap` 别名取值；`parsePreset` 与 [CryptoTailBracketExitService](backend/src/main/kotlin/com/wrbug/polymarketbot/service/cryptotail/CryptoTailBracketExitService.kt) 的 `parseTailDiffPresetFromMap` 复用同一 helper。`toMap()` 仍恒产 snake_case 落库。
- 校验：`TailDiffExitPresetResolver.isValid` 做"结构+边界"校验，`isTailDiffParamsValid` 对三档退出 JSON 严格校验（非法 4xx，不再静默回退默认档）。

### 17.3 preview 以实盘为准
- [控制器 preview](backend/src/main/kotlin/com/wrbug/polymarketbot/controller/cryptotail/CryptoTailTailDiffController.kt) 取该策略当前实时盘口（[CryptoTailOrderbookWsService.livePreviewContext](backend/src/main/kotlin/com/wrbug/polymarketbot/service/cryptotail/CryptoTailOrderbookWsService.kt)）+ 可支配余额，直接调 `CryptoTailTailDiffDecisionService.preview()`（复用 evaluate，无副作用）。删除控制器本地 modelSide/diffSigma 重复计算与模拟入参；价源未就绪时返回与实盘一致的 SKIP（`CRYPTO_TAIL_STRATEGY_TAIL_DIFF_PREVIEW_NOT_READY`）。

### 17.4 急跌止损抢占
- [CryptoTailBracketExitService](backend/src/main/kotlin/com/wrbug/polymarketbot/service/cryptotail/CryptoTailBracketExitService.kt)：`isEmergencyExit`（HARD_STOP/STOP/MODEL_INVALID/MODEL_FLIP/GAP_FLIP）触发时，先经 [CryptoTailExitOrderReconciler.cancelPendingExitForPreempt](backend/src/main/kotlin/com/wrbug/polymarketbot/service/cryptotail/CryptoTailExitOrderReconciler.kt) 撤未成交退出单并按实际成交重读 remaining（防超卖），再放宽深度门禁直发 FAK，并以退出预设 `execution.worstPrice` 兜底；保留 `failBackoff`。非紧急退出行为不变。

### 17.5 清空语义（三态）与 TAIL_DIFF 专属发单前闸
- 更新可空字段（exitPreset*Json/entrySegmentsJson/dailyLossLimit）采用三态：缺失/`null`=保留、空串=清空置 NULL、非空=更新；前端清空发空串。
- 新增 `evaluateTailDiffEntryGates`：复用价源/盘口质量校验，仅套 TAIL_DIFF 阈值（hardMaxPrice/minEdge/minModelProb/minDiffSigma + 方向），不再误用 BARRIER 的 entryProb(0.55)/wick 等闸。

### 17.6 统计桶语义与数据源（V69）
- 桶以"领先方向"（leadOutcome=modelSide）为键：[TailReversalStatsLookup.Query](backend/src/main/kotlin/com/wrbug/polymarketbot/service/cryptotail/taildiff/TailReversalStatsLookup.kt) 字段更名 `leadOutcome`，DecisionService 传 `modelSide`；修复评估非领先方向时无谓 FALLBACK。
- 数据源可配置：新增策略列 `tail_diff_stats_data_source`（V69，默认 BINANCE）→ `Query.dataSource`。[RealTailReversalStatsLookup](backend/src/main/kotlin/com/wrbug/polymarketbot/service/cryptotail/reversal/RealTailReversalStatsLookup.kt) 在 (diffSigma, odds) 两轴各自回退 ANY，统一覆盖 BINANCE（按 σ 细分、odds=ANY）与 POLYMARKET（按 odds 细分、σ=ANY）两种回填口径。

### 17.7 分段 `min_model_prob` 与 WS 窗口统一
- [TailDiffEntrySegmentResolver](backend/src/main/kotlin/com/wrbug/polymarketbot/service/cryptotail/taildiff/TailDiffEntrySegmentResolver.kt) `Segment`/`applyOverrides`/`parseSegment`/`isValid` 增 `min_model_prob`；三语 help/placeholder 示例补齐。
- WS [onBestBid](backend/src/main/kotlin/com/wrbug/polymarketbot/service/cryptotail/CryptoTailOrderbookWsService.kt) 对 TAIL_DIFF 改用 `windowEnvelope`（remaining 维度）预过滤，避免早窗分段被全局 elapsed 窗误卡；其他模式保持原行为。

### 17.8 清理与单测
- 死代码移除：`TailDiffBuckets.diffPctBucket`、`CryptoTailPolymarketPriceHistoryRepository.existsByTokenId`；`buildScorePayload` 补 `strategyId/marketSlug`。
- 新增单测：`TailDiffBucketsTest`、`TailDiffExitPresetTest`（snake+camel/toMap 往返/isValid）、`TailDiffEntrySegmentResolverTest`（含 min_model_prob/windowEnvelope）、`CryptoTailScoreEngineTest`（veto/tier/窗口）、`RealTailReversalStatsLookupTest`（leadOutcome 语义 + dataSource + ANY 回退矩阵）。
