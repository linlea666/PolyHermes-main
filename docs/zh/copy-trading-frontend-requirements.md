# 跟单系统前端需求文档

## 1. 页面概述

基于订单跟踪与统计设计，前端需要实现以下页面和功能：
- 跟单关系统计页面
- 买入订单列表页面
- 卖出订单列表页面
- 匹配关系列表页面

## 2. 跟单关系统计页面

### 2.1 页面路径
`/copy-trading/statistics/:copyTradingId`

### 2.2 显示内容

#### 2.2.1 基本信息卡片
- 账户名称
- Leader 名称
- 模板名称
- 跟单状态（启用/禁用）

#### 2.2.2 买入统计卡片
- **总买入数量**：所有买入订单的数量总和
- **总买入金额**：所有买入订单的金额总和（数量 × 价格）
- **总买入订单数**：买入订单的数量
- **平均买入价格**：总买入金额 / 总买入数量

#### 2.2.3 卖出统计卡片
- **总卖出数量**：所有卖出订单的数量总和
- **总卖出金额**：所有卖出订单的金额总和
- **总卖出订单数**：卖出订单的数量

#### 2.2.4 持仓统计卡片
- **当前持仓数量**：未匹配的买入数量总和
- **当前持仓价值**：当前持仓数量 × 当前市场价格
- **平均买入价格**：已买入订单的平均价格

#### 2.2.5 盈亏统计卡片
- **总已实现盈亏**：所有已匹配订单的盈亏总和
  - 颜色：盈利绿色，亏损红色
  - 图标：盈利↑，亏损↓
- **总未实现盈亏**：当前持仓的盈亏（持仓数量 × (当前价格 - 平均买入价格)）
  - 颜色：盈利绿色，亏损红色
- **总盈亏**：已实现盈亏 + 未实现盈亏
  - 颜色：盈利绿色，亏损红色
  - 图标：盈利↑，亏损↓
- **总盈亏百分比**：总盈亏 / 总买入金额 × 100%
  - 颜色：盈利绿色，亏损红色

### 2.3 UI 布局

**桌面端**：
- 使用 `Row` 和 `Col` 布局，每行 3-4 个统计卡片
- 卡片使用 `Statistic` 组件显示数据

**移动端**：
- 每行 1-2 个统计卡片
- 卡片内容简化，重要数据突出显示

### 2.4 数据格式化

- **数量**：使用 `formatUSDC` 格式化（最多 4 位小数，自动去除尾随零）
- **金额**：使用 `formatUSDC` 格式化，后缀 "USDC"
- **百分比**：显示 2 位小数，后缀 "%"
- **价格**：使用 `formatUSDC` 格式化

## 3. 买入订单列表页面

### 3.1 页面路径
`/copy-trading/orders/buy/:copyTradingId`

### 3.2 表格列

| 列名 | 字段 | 说明 |
|------|------|------|
| 订单ID | buyOrderId | 跟单买入订单ID（可点击查看详情） |
| Leader 交易ID | leaderBuyTradeId | Leader 的买入交易ID |
| 市场 | marketId | 市场地址（可点击查看市场详情） |
| 方向 | side | YES/NO 标签 |
| 买入数量 | quantity | 使用 formatUSDC 格式化 |
| 买入价格 | price | 使用 formatUSDC 格式化 |
| 买入金额 | amount | quantity × price，使用 formatUSDC 格式化 |
| 已匹配数量 | matchedQuantity | 已匹配的卖出数量，使用 formatUSDC 格式化 |
| 剩余数量 | remainingQuantity | 未匹配的数量，使用 formatUSDC 格式化 |
| 订单状态 | status | 标签显示：filled（已完成）、partially_matched（部分匹配）、fully_matched（完全匹配） |
| 创建时间 | createdAt | 时间戳转换为可读格式 |

### 3.3 状态标签颜色

- `filled`：蓝色（processing）
- `partially_matched`：橙色（warning）
- `fully_matched`：绿色（success）

### 3.4 功能

- **分页**：支持分页查询
- **排序**：默认按创建时间倒序
- **筛选**：可按市场、方向、状态筛选
- **详情**：点击订单ID查看详情（可选）

## 4. 卖出订单列表页面

### 4.1 页面路径
`/copy-trading/orders/sell/:copyTradingId`

### 4.2 表格列

| 列名 | 字段 | 说明 |
|------|------|------|
| 订单ID | sellOrderId | 跟单卖出订单ID（可点击查看详情） |
| Leader 交易ID | leaderSellTradeId | Leader 的卖出交易ID |
| 市场 | marketId | 市场地址（可点击查看市场详情） |
| 方向 | side | YES/NO 标签 |
| 卖出数量 | quantity | 使用 formatUSDC 格式化 |
| 卖出价格 | price | 使用 formatUSDC 格式化 |
| 卖出金额 | amount | quantity × price，使用 formatUSDC 格式化 |
| 已实现盈亏 | realizedPnl | 该卖出订单的盈亏，使用 formatUSDC 格式化，颜色：盈利绿色，亏损红色 |
| 创建时间 | createdAt | 时间戳转换为可读格式 |

### 4.3 功能

- **分页**：支持分页查询
- **排序**：默认按创建时间倒序
- **筛选**：可按市场、方向筛选
- **详情**：点击订单ID查看匹配明细（可选）

## 5. 匹配关系列表页面

### 5.1 页面路径
`/copy-trading/orders/matched/:copyTradingId`

### 5.2 表格列

| 列名 | 字段 | 说明 |
|------|------|------|
| 卖出订单ID | sellOrderId | 跟单卖出订单ID（可点击查看详情） |
| 买入订单ID | buyOrderId | 匹配的买入订单ID（可点击查看详情） |
| 匹配数量 | matchedQuantity | 匹配的数量，使用 formatUSDC 格式化 |
| 买入价格 | buyPrice | 买入价格，使用 formatUSDC 格式化 |
| 卖出价格 | sellPrice | 卖出价格，使用 formatUSDC 格式化 |
| 盈亏 | realizedPnl | (卖出价格 - 买入价格) × 匹配数量，使用 formatUSDC 格式化，颜色：盈利绿色，亏损红色 |
| 匹配时间 | matchedAt | 时间戳转换为可读格式 |

### 5.3 功能

- **分页**：支持分页查询
- **排序**：默认按匹配时间倒序
- **筛选**：可按卖出订单ID、买入订单ID筛选
- **详情**：点击订单ID查看详情（可选）

## 6. 跟单列表页面增强

### 6.1 在跟单列表中添加统计入口

在 `CopyTradingList` 页面中，每个跟单关系添加：
- **查看统计**按钮：跳转到统计页面
- **查看订单**按钮：跳转到订单列表页面（可选择买入/卖出/匹配）

### 6.2 快速统计显示

在跟单列表表格中，可添加快速统计列：
- **总盈亏**：显示该跟单关系的总盈亏（颜色标识）
- **订单数**：买入订单数 / 卖出订单数
- **持仓**：当前持仓数量

## 7. 类型定义

### 7.1 跟单关系统计响应

```typescript
export interface CopyTradingStatistics {
  copyTradingId: number
  accountId: number
  accountName: string
  leaderId: number
  leaderName: string
  templateId: number
  templateName: string
  
  // 买入统计
  totalBuyQuantity: string
  totalBuyOrders: number
  totalBuyAmount: string
  
  // 卖出统计
  totalSellQuantity: string
  totalSellOrders: number
  totalSellAmount: string
  
  // 持仓统计
  currentPositionQuantity: string
  currentPositionValue: string
  avgBuyPrice: string
  
  // 盈亏统计
  totalRealizedPnl: string
  totalUnrealizedPnl: string
  totalPnl: string
  totalPnlPercent: string
}
```

### 7.2 买入订单信息

```typescript
export interface BuyOrderInfo {
  orderId: string
  leaderTradeId: string
  marketId: string
  side: string
  quantity: string
  price: string
  amount: string
  matchedQuantity: string
  remainingQuantity: string
  status: 'filled' | 'partially_matched' | 'fully_matched'
  createdAt: number
}
```

### 7.3 卖出订单信息

```typescript
export interface SellOrderInfo {
  orderId: string
  leaderTradeId: string
  marketId: string
  side: string
  quantity: string
  price: string
  amount: string
  realizedPnl: string
  createdAt: number
}
```

### 7.4 匹配订单信息

```typescript
export interface MatchedOrderInfo {
  sellOrderId: string
  buyOrderId: string
  matchedQuantity: string
  buyPrice: string
  sellPrice: string
  realizedPnl: string
  matchedAt: number
}
```

## 8. API 接口

### 8.1 查询跟单统计

```
POST /api/copy-trading/statistics/detail
Request: { copyTradingId: number }
Response: ApiResponse<CopyTradingStatistics>
```

### 8.2 查询买入订单列表

```
POST /api/copy-trading/orders/tracking
Request: { 
  copyTradingId: number
  type: 'buy'
  page?: number
  limit?: number
  marketId?: string
  side?: string
  status?: string
}
Response: ApiResponse<{ list: BuyOrderInfo[], total: number }>
```

### 8.3 查询卖出订单列表

```
POST /api/copy-trading/orders/tracking
Request: { 
  copyTradingId: number
  type: 'sell'
  page?: number
  limit?: number
  marketId?: string
  side?: string
}
Response: ApiResponse<{ list: SellOrderInfo[], total: number }>
```

### 8.4 查询匹配关系列表

```
POST /api/copy-trading/orders/tracking
Request: { 
  copyTradingId: number
  type: 'matched'
  page?: number
  limit?: number
  sellOrderId?: string
  buyOrderId?: string
}
Response: ApiResponse<{ list: MatchedOrderInfo[], total: number }>
```

## 9. UI/UX 要求

### 9.1 移动端适配

- **响应式布局**：使用 `useMediaQuery` 检测移动端
- **表格优化**：移动端使用卡片布局或横向滚动
- **统计卡片**：移动端每行 1-2 个，简化显示

### 9.2 数据格式化

- **统一使用 `formatUSDC`**：所有 USDC 金额显示
- **时间格式化**：使用相对时间或标准时间格式
- **百分比显示**：保留 2 位小数

### 9.3 颜色规范

- **盈利**：绿色（#3f8600）
- **亏损**：红色（#cf1322）
- **状态标签**：
  - filled: 蓝色
  - partially_matched: 橙色
  - fully_matched: 绿色

### 9.4 交互优化

- **加载状态**：使用 `loading` 属性显示加载中
- **错误处理**：使用 `message.error` 显示错误信息
- **空状态**：显示友好的空状态提示
- **分页**：支持每页数量调整

## 10. 实现优先级

### Phase 1: 核心功能
1. 跟单关系统计页面（基础统计）
2. 买入订单列表页面
3. 卖出订单列表页面

### Phase 2: 增强功能
4. 匹配关系列表页面
5. 跟单列表页面增强（快速统计）
6. 订单详情页面（可选）

### Phase 3: 优化功能
7. 数据可视化（图表展示）
8. 导出功能（导出统计报表）
9. 高级筛选和搜索

