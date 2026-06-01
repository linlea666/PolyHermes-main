# 三元市场订单簿实现说明

## 概述

Polymarket 的三元市场（Ternary Market）是指具有三个或更多可能结果的市场。与二元市场（YES/NO）不同，三元市场需要为每个 outcome 维护独立的订单簿。

## 核心概念

### 1. TokenId 与 Outcome 的关系

在 Polymarket 中，每个 outcome 都有唯一的 `tokenId`：

- **二元市场**：
  - YES (outcomeIndex = 0) → tokenId_0
  - NO (outcomeIndex = 1) → tokenId_1

- **三元市场**：
  - Outcome A (outcomeIndex = 0) → tokenId_0
  - Outcome B (outcomeIndex = 1) → tokenId_1
  - Outcome C (outcomeIndex = 2) → tokenId_2

- **多元市场**（N 个结果）：
  - Outcome 0 → tokenId_0
  - Outcome 1 → tokenId_1
  - ...
  - Outcome N-1 → tokenId_N-1

### 2. TokenId 的计算方式

`tokenId` 通过以下步骤计算：

```kotlin
// 1. 计算 indexSet：indexSet = 2^outcomeIndex
val indexSet = BigInteger.TWO.pow(outcomeIndex)

// 2. 调用链上合约 getCollectionId(EMPTY_SET, conditionId, indexSet)
val collectionId = getCollectionId(EMPTY_SET, conditionId, indexSet)

// 3. 调用链上合约 getPositionId(collateralToken, collectionId)
val tokenId = getPositionId(collateralToken, collectionId)
```

**示例**：
- outcomeIndex = 0 → indexSet = 1 (2^0)
- outcomeIndex = 1 → indexSet = 2 (2^1)
- outcomeIndex = 2 → indexSet = 4 (2^2)
- outcomeIndex = 3 → indexSet = 8 (2^3)

## 订单簿结构

### API 接口

Polymarket CLOB API 提供 `/book` 接口获取订单簿：

```kotlin
@GET("/book")
suspend fun getOrderbook(
    @Query("token_id") tokenId: String? = null,
    @Query("market") market: String? = null
): Response<OrderbookResponse>
```

### 订单簿响应结构

```kotlin
data class OrderbookResponse(
    val bids: List<OrderbookEntry>,  // 买入订单列表（按价格从高到低排序）
    val asks: List<OrderbookEntry>   // 卖出订单列表（按价格从低到高排序）
)

data class OrderbookEntry(
    val price: String,  // 价格（0.01 - 0.99）
    val size: String    // 数量（shares）
)
```

### 订单簿排序规则

1. **Bids（买入订单）**：
   - 按价格从高到低排序
   - 第一个元素是 `bestBid`（最高买入价）

2. **Asks（卖出订单）**：
   - 按价格从低到高排序
   - 第一个元素是 `bestAsk`（最低卖出价）

## 三元市场订单簿实现

### 1. 获取特定 Outcome 的订单簿

对于三元市场，需要为每个 outcome 单独获取订单簿：

```kotlin
// 示例：三元市场 "谁会赢得选举？"
// - Outcome 0: "候选人A"
// - Outcome 1: "候选人B"
// - Outcome 2: "候选人C"

// 获取 Outcome 0 的订单簿
val tokenId0 = blockchainService.getTokenId(conditionId, 0)
val orderbook0 = clobService.getOrderbookByTokenId(tokenId0)
// orderbook0.bids[0].price 是 Outcome 0 的 bestBid
// orderbook0.asks[0].price 是 Outcome 0 的 bestAsk

// 获取 Outcome 1 的订单簿
val tokenId1 = blockchainService.getTokenId(conditionId, 1)
val orderbook1 = clobService.getOrderbookByTokenId(tokenId1)

// 获取 Outcome 2 的订单簿
val tokenId2 = blockchainService.getTokenId(conditionId, 2)
val orderbook2 = clobService.getOrderbookByTokenId(tokenId2)
```

### 2. 市价单价格获取

在 `AccountService.getOptimalPriceFromOrderbook` 方法中：

```kotlin
private suspend fun getOptimalPriceFromOrderbook(tokenId: String, isSellOrder: Boolean): String {
    // 通过 tokenId 获取特定 outcome 的订单簿
    val orderbookResult = clobService.getOrderbookByTokenId(tokenId)
    
    if (orderbookResult.isSuccess) {
        val orderbook = orderbookResult.getOrNull()
        if (orderbook != null) {
            if (isSellOrder) {
                // 市价卖单：需要 bestBid（最高买入价）
                val bestBid = orderbook.bids.firstOrNull()?.price
                // 返回 bestBid 或后备价格
            } else {
                // 市价买单：需要 bestAsk（最低卖出价）
                val bestAsk = orderbook.asks.firstOrNull()?.price
                // 返回 bestAsk 或后备价格
            }
        }
    }
    
    // 如果获取失败，返回后备价格
    return fallbackPrice
}
```

### 3. 完整流程示例

```kotlin
// 1. 用户请求卖出 Outcome 2 的仓位
val request = PositionSellRequest(
    accountId = 1,
    marketId = "0x123...",  // conditionId
    side = "候选人C",
    outcomeIndex = 2,  // 关键：指定 outcome 索引
    orderType = "MARKET",
    quantity = "100"
)

// 2. 计算 tokenId
val tokenId = blockchainService.getTokenId(request.marketId, request.outcomeIndex)
// tokenId = "87660119269436753918591605029528224889066452434179554814663664703244066132110"

// 3. 获取订单簿并提取最优价
val optimalPrice = getOptimalPriceFromOrderbook(tokenId, isSellOrder = true)
// 从 orderbook.bids[0].price 获取 bestBid

// 4. 创建并提交订单
val signedOrder = orderSigningService.createAndSignOrder(
    tokenId = tokenId,
    side = "SELL",
    price = optimalPrice,
    size = request.quantity
)
```

## 与二元市场的区别

### 二元市场（YES/NO）

- 只有 2 个 outcome（outcomeIndex = 0, 1）
- 可以通过 `market` 参数获取整个市场的订单簿
- Gamma API 提供 `bestBid` 和 `bestAsk`（但可能只针对主要 outcome）

### 三元及以上市场

- 有 3 个或更多 outcome（outcomeIndex = 0, 1, 2, ...）
- **必须**通过 `tokenId` 参数获取特定 outcome 的订单簿
- 每个 outcome 都有独立的订单簿
- 需要明确指定 `outcomeIndex` 来计算 `tokenId`

## 注意事项

1. **必须提供 outcomeIndex**：
   - 三元及以上市场无法通过 `side` 字符串推断 `outcomeIndex`
   - 必须明确提供 `outcomeIndex` 参数

2. **每个 Outcome 独立订单簿**：
   - 不同 outcome 的订单簿是独立的
   - 不能通过 `market` 参数获取所有 outcome 的订单簿

3. **价格范围**：
   - 所有 outcome 的价格都在 0.01 - 0.99 范围内
   - 所有 outcome 的价格之和应该接近 1.0（考虑套利机会）

4. **后备价格机制**：
   - 如果无法获取订单簿，使用后备价格：
     - 市价卖单：0.06
     - 市价买单：1.0

## 代码位置

- **TokenId 计算**：`BlockchainService.getTokenId()`
- **订单簿获取**：`PolymarketClobService.getOrderbookByTokenId()`
- **最优价获取**：`AccountService.getOptimalPriceFromOrderbook()`
- **订单创建**：`AccountService.sellPosition()`

