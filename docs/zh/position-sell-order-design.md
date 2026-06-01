# 仓位出售订单功能设计文档

## 1. 功能概述

为仓位管理页面添加出售功能，支持用户对当前仓位进行市价或限价卖出操作。

## 2. 后端接口设计

### 2.1 创建卖出订单接口

**接口路径**: `POST /api/copy-trading/positions/sell`

**请求体**:
```kotlin
data class PositionSellRequest(
    val accountId: Long,           // 账户ID（必需）
    val marketId: String,          // 市场ID（必需）
    val side: String,              // 方向：YES 或 NO（必需）
    val orderType: String,         // 订单类型：MARKET（市价）或 LIMIT（限价）（必需）
    val quantity: String,          // 卖出数量（必需，BigDecimal字符串）
    val price: String? = null      // 限价价格（限价订单必需，市价订单不需要）
)
```

**响应体**:
```kotlin
data class PositionSellResponse(
    val orderId: String,            // 订单ID
    val marketId: String,          // 市场ID
    val side: String,               // 方向
    val orderType: String,         // 订单类型
    val quantity: String,          // 订单数量
    val price: String?,             // 订单价格（限价订单）
    val status: String,             // 订单状态
    val createdAt: Long             // 创建时间戳
)
```

**业务逻辑**:
1. 验证账户是否存在且已配置API凭证
2. 验证仓位是否存在且数量足够
3. 验证订单参数（数量、价格等）
4. 市价订单：获取当前最优卖价（bestBid）作为价格
5. 限价订单：验证价格是否合理
6. 调用Polymarket CLOB API创建订单
7. 返回订单信息

**错误处理**:
- 账户不存在或未配置API凭证：返回错误码 2001
- 仓位不存在或数量不足：返回错误码 4001
- 价格或数量格式错误：返回错误码 1001
- API调用失败：返回错误码 5001

### 2.2 获取市场当前价格接口（可选，用于显示参考价格）

**接口路径**: `POST /api/copy-trading/markets/price`

**请求体**:
```kotlin
data class MarketPriceRequest(
    val marketId: String  // 市场ID
)
```

**响应体**:
```kotlin
data class MarketPriceResponse(
    val marketId: String,
    val lastPrice: String?,    // 最新成交价
    val bestBid: String?,      // 最优买价（用于卖出参考）
    val bestAsk: String?,      // 最优卖价（用于买入参考）
    val midpoint: String?      // 中间价
)
```

## 3. 前端交互设计

### 3.1 UI组件设计

#### 3.1.1 出售按钮
- **位置**: 每个仓位卡片/列表项的操作区域
- **样式**: 
  - 卡片视图：卡片底部或操作区域
  - 列表视图：操作列
- **显示条件**: 仅当前仓位显示（历史仓位不显示）
- **按钮文本**: "卖出" 或 "出售"

#### 3.1.2 出售模态框

**布局结构**:
```
┌─────────────────────────────────┐
│  出售仓位 - [市场标题]           │
├─────────────────────────────────┤
│  账户: [账户名称]                │
│  方向: [YES/NO标签]              │
│  当前持仓: [数量]                │
│  平均价格: [平均买入价格]        │
│  当前价格: [当前市场价格]        │
├─────────────────────────────────┤
│  订单类型:                       │
│  ○ 市价出售  ○ 限价出售          │
├─────────────────────────────────┤
│  卖出数量:                       │
│  [输入框]                        │
│  [20%] [50%] [80%] [100%]       │
├─────────────────────────────────┤
│  限价价格: (限价时显示)          │
│  [输入框]                        │
│  参考价格: [当前最优买价]        │
├─────────────────────────────────┤
│  预计平仓收益:                   │
│  收益金额: [+/-XXX.XX USDC]     │
│  收益率: [+/-XX.XX%]            │
│  (实时计算，根据数量和价格更新)  │
├─────────────────────────────────┤
│  [取消] [确认卖出]               │
└─────────────────────────────────┘
```

**字段说明**:
1. **订单类型选择**:
   - 单选按钮：市价出售 / 限价出售
   - 默认：限价出售
   - 切换时显示/隐藏限价输入框

2. **卖出数量**:
   - 输入框：支持手动输入
   - 快捷按钮：20%, 50%, 80%, 100%
   - 点击快捷按钮自动填充到输入框
   - 验证：不能超过当前持仓数量，不能为0

3. **限价价格**（限价订单时显示）:
   - 输入框：支持手动输入
   - 显示参考价格：当前最优买价（bestBid）
   - 验证：价格必须大于0

4. **按钮**:
   - 取消：关闭模态框
   - 确认卖出：提交订单（加载状态）

### 3.2 交互流程

1. **打开模态框**:
   - 点击"卖出"按钮
   - 加载市场当前价格（用于显示参考价格）
   - 初始化表单（默认限价，数量为空）

2. **选择订单类型**:
   - 切换市价/限价
   - 市价：隐藏限价输入框
   - 限价：显示限价输入框和参考价格

3. **设置数量**:
   - 点击快捷按钮（20%, 50%, 80%, 100%）
   - 自动计算并填充到输入框
   - 实时验证数量是否有效

4. **设置限价**（限价订单）:
   - 手动输入价格
   - 显示参考价格提示
   - 实时更新平仓收益

5. **查看平仓收益**（实时计算）:
   - 根据卖出数量和价格实时计算
   - 计算公式：
     - 收益金额 = (卖出价格 - 平均买入价格) × 卖出数量
     - 收益率 = (卖出价格 - 平均买入价格) / 平均买入价格 × 100%
   - 市价订单：使用当前最优买价计算
   - 限价订单：使用输入的限价计算
   - 颜色显示：盈利为绿色，亏损为红色

6. **提交订单**:
   - 点击"确认卖出"
   - 显示加载状态
   - 调用后端接口
   - 成功：显示成功提示，关闭模态框，刷新仓位列表
   - 失败：显示错误提示

### 3.3 数据验证

**前端验证**:
- 数量：必填，大于0，不超过当前持仓数量
- 限价价格：限价订单必填，大于0
- 账户：必须已配置API凭证

**后端验证**:
- 账户存在且已配置API凭证
- 仓位存在且数量足够
- 价格和数量格式正确
- 市价订单自动获取最优价格

## 4. 类型定义

### 4.1 前端TypeScript类型

```typescript
/**
 * 仓位卖出请求
 */
export interface PositionSellRequest {
  accountId: number
  marketId: string
  side: 'YES' | 'NO'
  orderType: 'MARKET' | 'LIMIT'
  quantity: string
  price?: string  // 限价订单必需
}

/**
 * 仓位卖出响应
 */
export interface PositionSellResponse {
  orderId: string
  marketId: string
  side: string
  orderType: string
  quantity: string
  price?: string
  status: string
  createdAt: number
}

/**
 * 市场价格请求
 */
export interface MarketPriceRequest {
  marketId: string
}

/**
 * 市场价格响应
 */
export interface MarketPriceResponse {
  marketId: string
  lastPrice?: string
  bestBid?: string
  bestAsk?: string
  midpoint?: string
}
```

## 5. API服务方法

### 5.1 前端API服务

```typescript
// frontend/src/services/api.ts
export const apiService = {
  positions: {
    /**
     * 卖出仓位
     */
    sell: (data: PositionSellRequest) => 
      apiClient.post<ApiResponse<PositionSellResponse>>('/copy-trading/positions/sell', data),
    
    /**
     * 获取市场价格
     */
    getMarketPrice: (data: MarketPriceRequest) => 
      apiClient.post<ApiResponse<MarketPriceResponse>>('/copy-trading/markets/price', data)
  }
}
```

### 5.2 后端Controller方法

```kotlin
@PostMapping("/positions/sell")
suspend fun sellPosition(@RequestBody request: PositionSellRequest): ResponseEntity<ApiResponse<PositionSellResponse>>

@PostMapping("/markets/price")
suspend fun getMarketPrice(@RequestBody request: MarketPriceRequest): ResponseEntity<ApiResponse<MarketPriceResponse>>
```

## 6. 实现细节

### 6.1 市价订单处理

- 市价订单需要获取当前最优买价（bestBid）作为卖出价格
- 如果无法获取最优买价，使用最新成交价（lastPrice）
- 如果都没有，返回错误提示

### 6.2 数量计算

- 快捷按钮计算：`数量 = 当前持仓数量 × 百分比`
- 保留4位小数（与仓位数量精度一致）
- 验证：不能超过当前持仓数量

### 6.3 平仓收益实时计算

**计算逻辑**:
```typescript
// 获取仓位信息
const avgPrice = parseFloat(position.avgPrice)  // 平均买入价格
const quantity = parseFloat(sellQuantity)      // 卖出数量
const sellPrice = orderType === 'MARKET' 
  ? parseFloat(marketPrice.bestBid)           // 市价：使用最优买价
  : parseFloat(limitPrice)                    // 限价：使用输入价格

// 计算收益
const pnl = (sellPrice - avgPrice) * quantity
const percentPnl = ((sellPrice - avgPrice) / avgPrice) * 100

// 显示格式
const pnlDisplay = `${pnl >= 0 ? '+' : ''}${pnl.toFixed(2)} USDC`
const percentPnlDisplay = `${percentPnl >= 0 ? '+' : ''}${percentPnl.toFixed(2)}%`
```

**更新时机**:
- 数量输入框值变化时
- 限价价格输入框值变化时（限价订单）
- 订单类型切换时（市价/限价）
- 市场价格更新时（市价订单，如果支持实时更新）

**显示样式**:
- 盈利：绿色文字（#52c41a）
- 亏损：红色文字（#f5222d）
- 字体：加粗显示，突出重要性

### 6.4 错误处理

- 网络错误：显示"网络错误，请重试"
- API错误：显示后端返回的错误信息
- 验证错误：显示具体的验证失败原因

### 6.5 用户体验优化

- 提交订单时禁用按钮，显示加载状态
- 成功后自动刷新仓位列表
- 提供清晰的成功/失败提示
- 模态框支持ESC键关闭

## 7. 安全考虑

1. **权限验证**: 验证账户是否属于当前用户
2. **数量验证**: 确保卖出数量不超过持仓数量
3. **价格验证**: 限价订单验证价格合理性
4. **API凭证**: 确保账户已配置有效的API凭证

## 8. 测试要点

1. 市价订单创建成功
2. 限价订单创建成功
3. 数量快捷按钮功能
4. 数量验证（超过持仓、为0等）
5. 价格验证（限价订单）
6. **平仓收益实时计算**:
   - 数量变化时收益更新
   - 限价变化时收益更新
   - 订单类型切换时收益更新
   - 收益金额和收益率计算正确
   - 盈利/亏损颜色显示正确
7. 账户未配置API凭证的错误处理
8. 仓位不存在的错误处理
9. 网络错误处理

