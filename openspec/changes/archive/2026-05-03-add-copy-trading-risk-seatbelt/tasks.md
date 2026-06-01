## 0. 实施前同步

- [x] 0.1 在开始编码前把当前分支与最新 `origin/main` 对齐，重点确认 CLOB V2、pUSD/$ 展示、持仓接口和金额单位没有冲突
- [x] 0.2 对齐后重新运行现有 `CopyTradingPnlCalculatorTest`，确保当前真实 PnL 基线没有先坏掉

## 1. 报价状态和 PnL 口径

- [x] 1.1 重构持仓估值返回结构，支持 `AVAILABLE`、`NO_MATCH`、`UNAVAILABLE` 三种报价状态
- [x] 1.2 调整 `CopyTradingStatisticsService.buildPositionValuationQuotes`，接口失败或超时时返回 `UNAVAILABLE` 状态，不再返回空 quotes 伪装成 0 估值
- [x] 1.3 调整 `CopyTradingPnlCalculator` 或其调用层，只有 `AVAILABLE` 且当前价格为 0 时才计入“已确认归零”；`NO_MATCH` 和 `UNAVAILABLE` 必须从诊断中暴露出来
- [x] 1.4 保持现有统计字段向后兼容；如果存在未知估值，旧字段可以继续返回数值，但必须新增状态字段告诉前端该数值不是完整结论
- [x] 1.5 更新或替换当前“无报价按 0”的单元测试，覆盖报价成功为 0、报价未匹配、报价接口不可用、混合持仓四种场景

## 2. 亏损诊断后端

- [x] 2.1 新增 `CopyTradingRiskDiagnosisService`，聚合买入订单、卖出匹配、filtered orders、当前持仓估值和真实 PnL 统计
- [x] 2.2 实现诊断 DTO：已实现 PnL、未实现 PnL、总 PnL、持仓成本、持仓估值、归零亏损、未平仓暴露、订单数量、样本量、top losing markets、报价状态摘要、数据完整性和生成时间
- [x] 2.3 实现低样本识别逻辑，避免把 sovereign2013 这种少量盈利订单标记成高置信度盈利
- [x] 2.4 实现诊断降级逻辑：报价或来源数据不可用时返回部分结果，并明确列出缺失来源
- [x] 2.5 优先用 repository 聚合查询计算 top losing markets 和来源计数；如果需要索引，只新增 append-only Flyway migration，不新增诊断快照表

## 3. 风险安全带后端

- [x] 3.1 新增风险配置体检逻辑，检查 `maxDailyOrders`、`maxDailyLoss`、`minPrice`、`maxPrice`、`maxPositionValue`、`minOrderDepth`、`maxSpread`、`priceTolerance` 和 `supportSell`
- [x] 3.2 实现保守参数建议，第一版使用固定保守区间：`maxDailyOrders=10-20`、`maxDailyLoss=5-10`、`minPrice=0.10`、`maxPrice=0.80`、`maxPositionValue=5-10`
- [x] 3.3 在风险建议中返回字段级原因、当前值、建议值和严重程度，并标明建议只是安全带，不是收益承诺
- [x] 3.4 新增“应用保守配置”的后端显式确认入口，请求必须带 `confirm=true`，后端只允许保存白名单风控字段
- [x] 3.5 确保取消、未确认、参数校验失败、非白名单字段混入时不会修改任何真实跟单配置

## 4. API 和 DTO

- [x] 4.1 新增或扩展跟单诊断 API，返回 PnL 分解、亏损归因、报价状态、风险配置体检、保守配置 diff 和生成时间
- [x] 4.2 新增应用保守配置 API，接收 `copyTradingId`、`confirm=true` 和后端生成的建议字段，成功后返回更新后的跟单配置
- [x] 4.3 保持现有统计 API 向后兼容，旧字段继续返回，新诊断字段缺失时前端能显示空态
- [x] 4.4 为新增或扩展 API 增加明确错误响应，区分参数错误、数据不存在、未确认、报价不可用和外部接口失败
- [x] 4.5 不新增 leader 推荐列表 API、推荐处理 API、leader 状态更新 API；这些留到第二阶段

## 5. 前端界面

- [x] 5.1 在跟单统计页和统计弹窗中新增 PnL 分解卡片，展示已实现、未实现、总 PnL、持仓成本和持仓估值
- [x] 5.2 新增亏损归因区块，展示归零亏损、top losing markets、未平仓风险、报价状态和数据完整性
- [x] 5.3 新增风险配置体检区块，展示危险字段、当前值、建议值、原因和严重程度
- [x] 5.4 新增应用保守配置确认弹窗，展示后端返回的字段变更前后 diff，取消时不发保存请求，确认时调用显式确认 API
- [x] 5.5 补齐空态、加载态、部分数据不可用态和错误态，尤其是 `UNAVAILABLE` 报价状态不能显示成 0 亏损
- [x] 5.6 前端文案使用中文优先，英文/繁中 i18n 可以先补基础 key，但不能让中文用户看到英文诊断主体

## 6. 测试

- [x] 6.1 为 PnL 计算增加单元测试，覆盖 `AVAILABLE` 且价格为 0、`NO_MATCH`、`UNAVAILABLE` 和混合持仓
- [x] 6.2 为亏损诊断服务增加单元测试，覆盖归零亏损、top losing markets、低样本盈利、部分数据缺失和数据完整性字段
- [x] 6.3 为风险配置体检增加单元测试，覆盖危险配置、保守配置和建议值生成
- [x] 6.4 为新增或扩展 controller 增加接口测试，覆盖成功响应、参数错误、数据不存在、未确认、非白名单字段混入和外部数据不可用
- [x] 6.5 为前端关键组件增加构建验证和交互检查，确保确认弹窗、空态和错误态可用

## 7. 文档、部署和验证

- [x] 7.1 更新 README 或运维文档，说明亏损诊断、风险安全带和手动确认流程
- [x] 7.2 记录 Mac mini 排查命令，包括 `ssh m4`、Docker 日志、MySQL 容器和非交互 PATH 注意事项
- [x] 7.3 明确第二阶段才做 leader 池状态机、leader 推荐、leaderboard 候选发现和任何自动暂停
- [x] 7.4 本地运行后端测试和前端构建，记录命令与结果
- [ ] 7.5 在 Mac mini 部署前先用测试环境或本地数据验证诊断结果与现有统计一致
- [ ] 7.6 部署后观察 24-72 小时诊断日志和页面显示，确认没有把 `UNAVAILABLE` 展示成 0 亏损，也没有自动改动真实跟单配置

## 不在本阶段范围

- leader 池状态机：`ACTIVE`、`WATCH`、`COOLDOWN`、`RETIRED`、`LOCKED`
- leader 推荐记录表、推荐详情页、忽略/锁定/应用推荐动作
- 官方 Polymarket leaderboard 候选发现和定时同步
- 自动新增、删除、启用、停用 leader 或跟单配置
- 自动加仓、组合优化、跨钱包资金调度或收益预测
