## Why

当前跟单亏损已经不是单纯“leader 选错”能解释的问题。实盘排查显示，RN1 和 swisstony 的亏损与短周期体育市场归零、未平仓估值归零、以及过松的风控配置共同相关；如果直接做自动 leader 轮换，系统可能只是把风险从一个 leader 换到另一个 leader。

现在需要先把 PolyHermes 从“忠实执行跟单”升级为“带安全带的操作者控制台”：能解释亏损、识别危险配置、给出保守参数建议，并把任何真实配置修改都放在明确确认和可审计边界内。

## What Changes

- 新增跟单亏损归因能力：区分已实现 PnL、未实现 PnL、持仓成本、持仓估值、归零亏损、报价不可用、top losing markets 等关键原因。
- 新增风险安全带能力：对危险跟单配置给出提示和保守参数建议，优先复用已有 `maxDailyOrders`、`maxDailyLoss`、`minPrice`、`maxPrice`、`maxPositionValue`、`minOrderDepth`、`maxSpread` 等字段。
- 新增 operator-facing UI：在跟单统计/配置页显示亏损归因、风险提示、保守参数建议和证据来源。
- 新增审计和降级要求：当 Polymarket 持仓/报价接口失败时，必须明确显示“数据不可用”，不能把未知估值直接当作 0 亏损。
- 暂不做 leader 池状态机、leader 自动推荐、定时榜单同步、全自动加仓、全自动删除 leader、复杂资产组合优化、跨钱包资金调度或收益承诺。

## Capabilities

### New Capabilities

- `copy-trading-risk-seatbelt`: 覆盖跟单亏损归因、危险配置识别、保守参数建议、报价不可用处理和操作者确认边界。

### Modified Capabilities

- None.

## Impact

- 后端：影响 copy-trading statistics、PnL calculator、config service、repository 聚合查询、API response DTO。
- 前端：影响跟单配置管理、统计弹窗/统计页、风险提示和建议确认 UI。
- 数据库：第一阶段不新增业务表；如发现查询瓶颈，只追加必要索引，不创建 leader 状态/推荐记录/诊断快照表。
- 外部数据：继续使用现有 Polymarket 持仓/报价数据；第一阶段不接入 leaderboard 候选发现。
- 运维：Mac mini Docker 部署需要可观测日志、报价失败降级和手动确认边界。
- 测试：需要覆盖真实 PnL 归因、报价失败、危险配置识别、UI 空态/错误态，以及不自动执行配置变更的安全约束。
