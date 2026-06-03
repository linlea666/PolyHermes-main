# Crypto-Tail 决策闸拦截分析与分策略调参指南

本文件用于：基于真实决策日志（`crypto_tail_decision_event` 表）统计各 `gate_name` 的拦截分布，判断拦截属于「合理保护 / 配置太严 / 价源或盘口技术问题 / 疑似 bug」，并给出 BTC 15m、ETH 5m 两条策略的推荐起步参数。

> 原则：所有阈值都通过策略配置下发，代码不硬编码。本文给出的是**起步建议**，需结合下方 SQL 的实盘统计迭代调整。

## 一、gateName 拦截统计 SQL 模板

决策事件表关键列：`strategy_id`、`period_start_unix`、`event_type`、`gate_name`、`passed`、`reason`、`outcome_index`、`trigger_id`、`created_at`（毫秒时间戳）、`payload_json`。

### 1. 近 24h 各闸拦截次数（按策略）

```sql
SELECT strategy_id,
       gate_name,
       COUNT(*) AS blocked
FROM crypto_tail_decision_event
WHERE event_type = 'GATE_FAILED'
  AND created_at >= (UNIX_TIMESTAMP() - 24 * 3600) * 1000
GROUP BY strategy_id, gate_name
ORDER BY strategy_id, blocked DESC;
```

### 2. 各闸拦截占比（定位最常拦截的闸）

```sql
SELECT gate_name,
       COUNT(*) AS blocked,
       ROUND(100.0 * COUNT(*) / SUM(COUNT(*)) OVER (), 1) AS pct
FROM crypto_tail_decision_event
WHERE event_type = 'GATE_FAILED'
  AND created_at >= (UNIX_TIMESTAMP() - 24 * 3600) * 1000
GROUP BY gate_name
ORDER BY blocked DESC;
```

### 3. 入场尝试漏斗（通过率）

```sql
SELECT event_type,
       COUNT(*) AS cnt
FROM crypto_tail_decision_event
WHERE strategy_id = :strategyId
  AND created_at >= (UNIX_TIMESTAMP() - 24 * 3600) * 1000
  AND event_type IN ('GATE_FAILED', 'ORDER_SUBMITTED', 'ORDER_RESULT')
GROUP BY event_type;
```

### 4. 某闸最近拦截原因明细（看 reason 文本，定位是配置严还是技术问题）

```sql
SELECT created_at, period_start_unix, outcome_index, reason, payload_json
FROM crypto_tail_decision_event
WHERE strategy_id = :strategyId
  AND event_type = 'GATE_FAILED'
  AND gate_name = :gateName
ORDER BY created_at DESC
LIMIT 50;
```

## 二、拦截分类判定标准

按 `gate_name` 将拦截归类，指导是否调参：

- 合理保护（不要放宽）
  - `DIRECTION`：模型方向与 outcome 不一致——核心安全闸，拦截是对的。
  - `HIGH_PRICE`：高价票未达 pWin/safeRatio 高门槛——防买贵，保留。
  - `EV_SAFE_LIMIT` / `FINAL_LIMIT_ABOVE_EV_SAFE_LIMIT`：扣费后 edge 不足 / 最终限价超过 EV 安全上限——防负期望、防买贵，保留。
  - `MAX_ENTRY_PRICE`：有效成本超上限——保留。
- 可能配置太严（结合占比与 reason 调整）
  - `PWIN`：若占比极高且 reason 中 pWin 普遍接近阈值，可小幅下调 `entryProb`。
  - `SAFE_RATIO`：若 Up/Down 分项过严导致几乎不入场，按方向分别微调 `minSafeRatioUp/Down`。
  - `REMAINING_TIME`：若大量拦截集中在周期初/末，调整 `minRemainingSeconds/maxRemainingSeconds` 窗口。
  - `SPREAD_TOO_WIDE`：若盘口本就偏宽，适度放宽 `maxEntrySpread`（但别失控）。
- 价源/盘口技术问题（先修数据，别动策略阈值）
  - `PRICE_SOURCE`：价源未就绪/期初价缺失——查 `readiness.reason`、`priceMode`、`latestSampleTime`。
  - `ORDERBOOK_STALE`：盘口快照缺失/过期——查 WS 连接与 `maxOrderbookAgeMs`。
  - `PRICE_STALE`：结算同源价过期——查 RTDS/Chainlink 新鲜度与 `maxPriceAgeMs`。
- 疑似 bug（需排查代码）
  - 同一周期同闸高频重复落库（去重后应每周期每闸约 1 行）。
  - `DIRECTION` 拦截但 `payload_json` 显示方向其实一致。
  - `PRICE_SOURCE` 在 BTC realtime 正常时仍频繁出现。

## 三、推荐起步参数

> 仅起步值；上线后用上面 SQL 跑 1~3 天，按拦截分布迭代。两条策略独立配置，互不覆盖。

### BTC 15m（btc-updown-15m，intervalSeconds=900）

- entryProb: 0.80
- entryEdge: 0.03
- barrierMinMarketProb: 0.62
- maxEntryPrice: 0.92
- minSafeRatioUp: 1.30
- minSafeRatioDown: 1.10
- highPriceThreshold: 0.90
- highPriceMinPWin: 0.985
- highPriceMinSafeRatio: 2.30
- minRemainingSeconds: 60
- maxRemainingSeconds: 660
- maxEntrySpread: 0.04
- maxOrderbookAgeMs: 7000
- maxPriceAgeMs: 7000
- entryFakSlippage: 0.01
- enableExitManager: true（FAK 入场）

### ETH 5m（eth-updown-5m，intervalSeconds=300）

- entryProb: 0.84
- entryEdge: 0.03
- barrierMinMarketProb: 0.65
- maxEntryPrice: 0.92
- minSafeRatioUp: 1.35
- minSafeRatioDown: 1.25
- highPriceThreshold: 0.90
- highPriceMinPWin: 0.985
- highPriceMinSafeRatio: 2.50
- minRemainingSeconds: 30
- maxRemainingSeconds: 210
- maxEntrySpread: 0.03
- maxOrderbookAgeMs: 4000
- maxPriceAgeMs: 4000
- entryFakSlippage: 0.01
- enableExitManager: true（FAK 入场）

## 四、注意

- ETH 5m 周期短，价源新鲜度要求更高（`maxOrderbookAgeMs`/`maxPriceAgeMs` 比 BTC 更小）。
- 不要为提高单量把 `maxEntryPrice` 放宽到 0.97/0.99，也不要关闭 EV / 高价保护。
- BARRIER/BRACKET 价源必须与结算同源（RTDS/Chainlink），缺凭证时安全跳过，绝不回退 Binance。
