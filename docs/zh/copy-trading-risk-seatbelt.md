# 跟单亏损诊断与风险安全带

本文档说明跟单统计页新增的亏损诊断、安全带建议和手动确认流程。第一阶段只做诊断和保守配置应用，不做自动换 leader、不做自动暂停，也不做收益预测。

## 一、诊断口径

### 1.1 PnL 分解

诊断结果基于现有跟单订单、卖出匹配记录、过滤记录和当前持仓报价生成，主要字段包括：

- 已实现 PnL：来自已匹配卖出记录的真实盈亏。
- 未实现 PnL：当前持仓估值减去未平仓成本。
- 总 PnL：已实现 PnL 加未实现 PnL。
- 持仓成本：当前未平仓买入成本。
- 持仓估值：当前可用报价计算出的持仓市值。
- 归零或未知持仓成本：当前估值为 0、未匹配到报价或报价不可用的持仓成本。
- 已确认归零成本：只有报价状态为 `AVAILABLE` 且当前价格为 0 的持仓才计入。
- 卖出归零亏损：已实现亏损中按市场聚合出的归因结果。

### 1.2 报价状态

第一阶段不再把所有“没有价格”的情况都当作 0：

- `AVAILABLE`：报价可用，价格可以作为诊断依据。
- `NO_MATCH`：持仓存在，但没有在当前报价列表中匹配到对应 token。
- `UNAVAILABLE`：外部报价接口失败、超时或异常。

如果存在 `NO_MATCH` 或 `UNAVAILABLE`，页面会显示数据不完整提示。旧统计字段仍保持兼容返回，但前端必须展示报价状态，避免把未知估值误读成确定亏损。

### 1.3 低样本置信度

当样本量过小但总 PnL 为正时，诊断会标记为低置信度，避免把少量盈利订单误判成稳定盈利 leader。当前第一版阈值是样本量小于 10。

## 二、风险安全带

### 2.1 检查字段

安全带检查以下风控字段：

- `maxDailyOrders`
- `maxDailyLoss`
- `minPrice`
- `maxPrice`
- `maxPositionValue`
- `minOrderDepth`
- `maxSpread`
- `priceTolerance`
- `supportSell`

每条建议会返回字段名、当前值、建议值、严重程度和中文原因。建议只是降低尾部风险的安全带，不代表收益承诺。

### 2.2 第一阶段保守建议

第一阶段使用固定保守区间：

- `maxDailyOrders`：不超过 20。
- `maxDailyLoss`：不超过 10。
- `minPrice`：建议 0.10。
- `maxPrice`：建议 0.80。
- `maxPositionValue`：建议 10。
- `minOrderDepth`：建议 100。
- `maxSpread`：建议 0.03。
- `priceTolerance`：不超过 3。

## 三、手动确认流程

用户在统计页或统计弹窗中点击“应用保守配置”后，前端会展示字段级 diff。只有用户在确认弹窗中点击“确认应用”时，才会调用后端保存接口。

接口：

```http
POST /api/copy-trading/configs/apply-conservative-config
```

请求必须包含：

```json
{
  "copyTradingId": 1,
  "confirm": true
}
```

后端只接受白名单字段，不会修改以下真实交易行为：

- leader
- account
- enabled
- copyMode
- fixedAmount
- copyRatio
- supportSell

如果 `confirm` 不是 `true`，后端返回业务错误，不保存任何配置。

## 四、Mac mini 排查命令

### 4.1 登录和容器状态

```bash
ssh m4
cd ~/polyhermes
docker compose ps
docker compose logs -f app
docker compose logs -f mysql
```

如果生产环境使用的是旧版 compose 命令：

```bash
docker-compose ps
docker-compose logs -f app
docker-compose logs -f mysql
```

### 4.2 MySQL 容器排查

```bash
docker exec -it polyhermes-mysql mysql -uroot -p
SHOW DATABASES;
USE polyhermes;
SHOW TABLES;
```

常用只读检查：

```sql
SELECT id, account_id, leader_id, enabled, max_daily_orders, max_daily_loss
FROM copy_trading
ORDER BY id DESC
LIMIT 20;
```

### 4.3 后端接口和日志

```bash
curl -s http://127.0.0.1/api/copy-trading/statistics/detail \
  -H 'Content-Type: application/json' \
  -d '{"copyTradingId":1}' | jq
```

观察日志时重点搜索：

```bash
docker compose logs app | grep -E '应用保守配置|报价|CopyTradingRiskDiagnosis|statistics'
```

### 4.4 非交互 PATH 注意事项

在非交互 shell、CI 或远程命令中，不要假设 `java`、`node`、`docker compose` 一定在 PATH 中。必要时显式指定：

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export PATH="$JAVA_HOME/bin:$PATH"
```

本地后端测试命令：

```bash
cd backend
JAVA_HOME=/opt/homebrew/opt/openjdk@17 PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH" ./gradlew test
```

前端构建命令：

```bash
cd frontend
npm ci
npm run build
```

## 五、本地验证记录

本次第一阶段本地已验证：

- 后端 PnL 计算、亏损诊断、保守配置和 controller 测试通过。
- 前端 TypeScript 和 Vite 生产构建通过。
- 前端构建仍有既有大 chunk 和 `api.ts` 动静态混合 import 警告，不阻断发布。
- `npm ci` 报告既有依赖审计问题，需要单独评估，避免在本次安全带改动里做破坏性升级。

## 六、部署前和部署后验证

部署前建议用测试环境或本地脱敏数据抽查：

- 旧统计字段和新增诊断字段的 PnL 数字是否一致。
- `UNAVAILABLE` 是否显示为“报价不可用”，而不是 0 亏损。
- 点击取消确认弹窗时不发保存请求。
- 点击确认后只修改白名单字段。
- 低样本盈利 leader 是否显示低置信度。

部署后观察 24-72 小时：

- 诊断日志是否稳定生成。
- 页面是否仍能兼容旧统计字段。
- 是否出现把 `UNAVAILABLE` 展示成已确认归零的情况。
- 是否有任何非用户确认触发的配置修改。

## 七、第二阶段边界

以下能力明确不在第一阶段：

- leader 池状态机：`ACTIVE`、`WATCH`、`COOLDOWN`、`RETIRED`、`LOCKED`。
- leader 推荐记录表、推荐详情页、忽略/锁定/应用推荐动作。
- 官方 Polymarket leaderboard 候选发现和定时同步。
- 自动新增、删除、启用、停用 leader 或跟单配置。
- 自动暂停、自动加仓、组合优化、跨钱包资金调度或收益预测。
