# Leader Research Agent

Leader Research Agent 用来自动发现潜在优秀 leader、做纸上跟单、评分并给出试跟建议。它不会自动启用真钱跟单。

## 使用方式

1. 打开「跟单交易 -> Leader 研究」。
2. 点击「立即运行研究」拉取 watchlist、已有 Leader 和已持久化 activity-derived 候选。
3. 在候选表查看状态、评分、纸跟 PnL、过滤比例和估值状态。
4. 只有 `建议试跟` 状态的候选可以点击「创建禁用试跟」。
5. 创建后系统只生成 `enabled=false` 的跟单配置；如果你决定真钱跟单，需要到「跟单配置」页手动启用。

## 研究状态

- `DISCOVERED`: 已发现，但评分或新鲜度不足。
- `CANDIDATE`: 满足基础评分和新鲜度，可以进入纸跟。
- `PAPER`: 正在用独立纸跟账本模拟跟单。
- `TRIAL_READY`: 纸跟样本、PnL、回撤、未知报价暴露和过滤比例满足阈值。
- `COOLDOWN`: 回撤、亏损、来源陈旧或退出流动性风险触发冷却。
- `RETIRED`: 多轮冷却或长时间无新鲜来源后淘汰。

## 评分公式

当前版本为 `research-copyability-v1`。总分满分 100，分项权重如下：

- profit signal 20、repeatability 15、liquidity fit 10、entry price fit 10、slippage risk 10。
- drawdown risk 10、holding period fit 5、market type risk 5、exit liquidity risk 5、data freshness 5、filter pass rate 5。

缺数据会保守扣分：没有纸跟 session 或报价 UNKNOWN 时，不会把未知估值当成亏到 0，但会降低 liquidity、slippage、exit liquidity 等分项。样本不足 10 笔时总分 cap 到 59，不能进入 `TRIAL_READY`。

## 来源限制

v1 只启用三类来源：

- watchlist: `system_config.config_key = leader_research.watchlist`，值可以用逗号、空格、换行分隔钱包地址。
- existing leader: 已在 Leader 管理里的地址。
- activity-derived: `leader_activity_event` 里已经持久化、可归因、较新的活动事件。

public leaderboard 当前显式禁用，页面会展示 source limitation，避免误以为系统已经能全网发现。

## 运维与开关

- 定时任务默认关闭：`leader.research.enabled=false`。
- 手动运行不依赖定时开关，可以在「Leader 研究」页面点击「立即运行研究」。
- 全局 activity capture 默认关闭：`leader.research.global-capture.enabled=false`。
- 全局 capture 写入上限：`leader.research.global-capture.max-writes-per-minute=120`。
- Data API bounded backfill 每个钱包默认最多拉取 `leader.research.data-api-backfill.limit=200` 条，最多处理 50 个钱包。
- kill switch: 关闭 `leader.research.enabled` 可停止自动推进；关闭 `leader.research.global-capture.enabled` 可停止地址过滤前的全局事件捕获；前端入口可以隐藏但历史研究数据仍可读。
- 性能验证脚本：`scripts/leader-research-perf-check.sql` 可在一次性测试库中生成 100 个候选、1 万条 activity event 和 1 万条 paper trade，并对热查询执行 `EXPLAIN`。

## 排查

- source health 显示 `DISABLED`: 检查是否是 public leaderboard 或 global capture 未启用。
- source health 显示 `FAILURE`: 查看 recent research events 中的 `SOURCE_FAILURE`。
- 估值为 `UNKNOWN` 或 `UNAVAILABLE`: 代表无法确认当前市场价格，不会被当成亏到 0。
- 重复 activity event: 依赖 `stable_event_key` 和 `leader_paper_trade(session_id, leader_trade_id, side)` 去重。
- 重复审批: 同账户同 leader 已有配置时会拒绝，不会创建第二条真钱或试跟配置。

## 安全边界

- 研究状态和 Leader Pool 状态分离。
- `TRIAL_READY` 只是推荐 badge，不代表真钱跟单已启用。
- 审批接口只创建禁用配置，并强制 `enabled=false`。
- 纸跟账本使用 `leader_paper_session`、`leader_paper_trade`、`leader_paper_position`，不写入真钱订单跟踪表。
- `UNKNOWN` 估值不会被当成 confirmed zero 计入收益。
