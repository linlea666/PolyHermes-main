# 跟单订单跟踪与统计设计文档

## 1. 方案概述

采用**订单跟踪匹配方案**，精确追踪每笔买入订单，当 Leader 卖出时进行精确匹配，实现：
- 精确的买入-卖出匹配关系
- 准确的盈亏计算（已实现/未实现）
- 完整的订单统计信息
- 多维度数据统计

## 2. 核心思路

### 2.1 事件监听

**当前监听的事件类型**：**交易事件（trade）**

- **事件来源**：
  - WebSocket User Channel：`event_type = "trade"`
  - 轮询 CLOB API：`GET /trades?user={leaderAddress}`
- **触发时机**：交易已成交
- **数据字段**：`id`（trade_id）、`market`、`side`（BUY/SELL）、`price`、`size`、`timestamp`
- **去重标识**：`leader_id + trade_id`（trade.id）

**说明**：
- 只监听已成交的交易事件，不监听订单创建事件
- 交易事件表示 Leader 已经完成买入或卖出操作
- 通过 `trade.id` 进行去重，确保同一笔交易只处理一次

### 2.2 买入订单跟踪

当 Leader 买入时（通过交易事件）：
1. 检测到 `side = "BUY"` 的交易事件
2. 创建跟单买入订单
3. 记录到 `copy_order_tracking` 表
4. 记录买入数量、价格、状态等信息

### 2.3 卖出订单匹配

当 Leader 卖出时（通过交易事件）：
1. 检测到 `side = "SELL"` 的交易事件
2. 查找未匹配的买入订单（FIFO 策略）
3. 按比例匹配卖出数量
4. 更新买入订单的匹配状态
5. 记录匹配关系到 `sell_match_record` 和 `sell_match_detail`

### 2.4 匹配策略

- **FIFO（先进先出）**：按买入时间顺序匹配
- **部分匹配**：支持一个买入订单被多次卖出匹配
- **状态管理**：`filled` → `partially_matched` → `fully_matched`

## 3. 数据模型

### 3.1 订单跟踪表（copy_order_tracking）

```sql
CREATE TABLE copy_order_tracking (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    copy_trading_id BIGINT NOT NULL,  -- 跟单关系ID
    account_id BIGINT NOT NULL,
    leader_id BIGINT NOT NULL,
    template_id BIGINT NOT NULL,
    market_id VARCHAR(100) NOT NULL,
    side VARCHAR(10) NOT NULL,  -- YES/NO
    buy_order_id VARCHAR(100) NOT NULL,  -- 跟单买入订单ID
    leader_buy_trade_id VARCHAR(100) NOT NULL,  -- Leader 买入交易ID
    quantity DECIMAL(20, 8) NOT NULL,  -- 买入数量
    price DECIMAL(20, 8) NOT NULL,  -- 买入价格
    matched_quantity DECIMAL(20, 8) NOT NULL DEFAULT 0,  -- 已匹配卖出数量
    remaining_quantity DECIMAL(20, 8) NOT NULL,  -- 剩余未匹配数量
    status VARCHAR(20) NOT NULL,  -- filled, fully_matched, partially_matched
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    INDEX idx_copy_trading (copy_trading_id),
    INDEX idx_remaining (remaining_quantity, status)
);
```

### 3.2 卖出匹配记录表（sell_match_record）

```sql
CREATE TABLE sell_match_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    copy_trading_id BIGINT NOT NULL,
    sell_order_id VARCHAR(100) NOT NULL,  -- 跟单卖出订单ID
    leader_sell_trade_id VARCHAR(100) NOT NULL,  -- Leader 卖出交易ID
    market_id VARCHAR(100) NOT NULL,
    side VARCHAR(10) NOT NULL,
    total_matched_quantity DECIMAL(20, 8) NOT NULL,  -- 总匹配数量
    sell_price DECIMAL(20, 8) NOT NULL,  -- 卖出价格
    total_realized_pnl DECIMAL(20, 8) NOT NULL,  -- 总已实现盈亏
    created_at BIGINT NOT NULL,
    INDEX idx_copy_trading (copy_trading_id)
);
```

### 3.3 匹配明细表（sell_match_detail）

```sql
CREATE TABLE sell_match_detail (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    match_record_id BIGINT NOT NULL,  -- 关联 sell_match_record.id
    tracking_id BIGINT NOT NULL,  -- 关联 copy_order_tracking.id
    buy_order_id VARCHAR(100) NOT NULL,
    matched_quantity DECIMAL(20, 8) NOT NULL,  -- 匹配的数量
    buy_price DECIMAL(20, 8) NOT NULL,
    sell_price DECIMAL(20, 8) NOT NULL,
    realized_pnl DECIMAL(20, 8) NOT NULL,  -- 盈亏 = (sell_price - buy_price) * matched_quantity
    created_at BIGINT NOT NULL,
    FOREIGN KEY (match_record_id) REFERENCES sell_match_record(id),
    FOREIGN KEY (tracking_id) REFERENCES copy_order_tracking(id)
);
```

## 4. 核心流程

### 4.1 买入订单跟踪流程

```
Leader 买入交易
  ↓
检测到交易，计算跟单数量
  ↓
根据模板模式计算：
  - RATIO 模式: 数量 = Leader 数量 × copyRatio
  - FIXED 模式: 数量 = fixedAmount / 买入价格
  ↓
创建跟单买入订单
  ↓
记录到 copy_order_tracking
  - quantity: 买入数量
  - price: 买入价格
  - remaining_quantity: 初始等于 quantity
  - status: "filled"
```

### 4.2 卖出订单匹配流程

```
Leader 卖出交易
  ↓
查找未匹配的买入订单（remaining_quantity > 0）
  ↓
按 FIFO 顺序匹配
  ↓
计算匹配数量（统一按比例，不区分模式）
  - 需要匹配数量 = Leader 卖出数量 × copyRatio
  - 实际匹配数量 = min(需要匹配数量, 剩余持仓数量)
  ↓
更新买入订单状态
  - matched_quantity += 匹配数量
  - remaining_quantity -= 匹配数量
  - status: 根据剩余数量更新
  ↓
记录匹配关系
  - sell_match_record: 卖出订单记录
  - sell_match_detail: 匹配明细（每笔买入订单的匹配）
```

**重要说明**：
- **买入时**：根据模板的 `copyMode` 计算（RATIO 按比例，FIXED 按固定金额）
- **卖出时**：统一按比例计算（`Leader 卖出数量 × copyRatio`），不区分模式
- **固定金额模式**：只影响买入时的计算，卖出时仍然按比例

### 4.3 匹配计算示例

#### 示例1：比例模式

```
场景（比例模式，copyRatio = 100%）：
- 买入订单1: quantity=100, remaining=100
- 买入订单2: quantity=50, remaining=50
- Leader 卖出: 120

匹配过程：
1. 计算需要匹配：120 × 100% = 120
2. 订单1: 匹配 min(100, 120) = 100，剩余需匹配 = 20
3. 订单2: 匹配 min(50, 20) = 20，剩余需匹配 = 0

结果：
- 订单1: remaining = 0, status = "fully_matched"
- 订单2: remaining = 30, status = "partially_matched"
- 跟单卖出: 120
```

#### 示例2：固定金额模式

```
场景（固定金额模式，fixedAmount = 15 USDC，copyRatio = 100%）：
- Leader 买入: 100 数量，价格 0.5
- 跟单买入: 15 / 0.5 = 30 数量（固定金额）
- Leader 卖出: 50 数量，价格 0.7

匹配过程：
1. 计算需要匹配：50 × 100% = 50（按比例，不按固定金额）
2. 订单1: 匹配 min(30, 50) = 30，剩余需匹配 = 20

结果：
- 订单1: remaining = 0, status = "fully_matched"
- 跟单卖出: 30（不超过持仓）
- 注意：虽然买入时是固定金额，但卖出时按比例计算
```

#### 示例3：部分比例模式

```
场景（比例模式，copyRatio = 30%）：
- Leader 买入: 100 数量
- 跟单买入: 100 × 30% = 30 数量
- Leader 卖出: 50 数量

匹配过程：
1. 计算需要匹配：50 × 30% = 15
2. 订单1: 匹配 min(30, 15) = 15，剩余需匹配 = 0

结果：
- 订单1: remaining = 15, status = "partially_matched"
- 跟单卖出: 15
```

## 5. 统计功能

### 5.1 跟单关系统计

**统计维度**：
- 总买入数量/金额/订单数
- 总卖出数量/金额/订单数
- 当前持仓数量
- 平均买入价格
- 总已实现盈亏
- 总未实现盈亏（持仓盈亏）
- 总盈亏及百分比

**计算方式**：
```kotlin
// 使用 util 方法进行数值计算
val totalBuyQuantity = buyOrders.sumOf { it.quantity.toSafeBigDecimal() }
val totalSellQuantity = sellOrders.sumOf { it.quantity.toSafeBigDecimal() }
val currentPosition = buyOrders.sumOf { it.remainingQuantity.toSafeBigDecimal() }

// 已实现盈亏
val totalRealizedPnl = matchDetails.sumOf { it.realizedPnl.toSafeBigDecimal() }

// 未实现盈亏（需要当前市场价格）
val currentPrice = getMarketCurrentPrice(marketId)
val avgBuyPrice = totalBuyAmount.div(totalBuyQuantity)
val unrealizedPnl = currentPosition.multi(currentPrice.subtract(avgBuyPrice))

// 总盈亏
val totalPnl = totalRealizedPnl.add(totalUnrealizedPnl)
```

### 5.2 订单信息

**买入订单列表**：
- 订单ID、Leader 交易ID
- 市场、方向、数量、价格
- 已匹配数量、剩余数量
- 订单状态

**卖出订单列表**：
- 订单ID、Leader 交易ID
- 市场、方向、数量、价格
- 已实现盈亏

**匹配关系列表**：
- 卖出订单ID
- 匹配的买入订单ID
- 匹配数量
- 买入价格、卖出价格
- 盈亏

## 6. 数值计算规范

**使用 util 扩展方法**：
- `toSafeBigDecimal()`: 安全转换为 BigDecimal
- `multi()`: 乘法运算
- `div()`: 除法运算
- `eq()`, `lt()`, `gt()`, `gte()`, `lte()`: 比较运算

**示例**：
```kotlin
// 计算匹配数量
val matchedQty = min(remainingQty.toSafeBigDecimal(), needMatchQty.toSafeBigDecimal())

// 计算盈亏
val pnl = sellPrice.toSafeBigDecimal()
    .subtract(buyPrice.toSafeBigDecimal())
    .multi(matchedQty)

// 比较数量
if (remainingQty.toSafeBigDecimal().gt(BigDecimal.ZERO)) {
    // 还有剩余
}
```

## 7. 关键实现点

### 7.1 买入数量计算

```kotlin
// 买入时根据模式计算
fun calculateBuyQuantity(leaderTrade: Trade, template: CopyTradingTemplate): BigDecimal {
    return when (template.copyMode) {
        "RATIO" -> {
            // 比例模式：Leader 数量 × 比例
            leaderTrade.size.toSafeBigDecimal()
                .multi(template.copyRatio)
        }
        "FIXED" -> {
            // 固定金额模式：固定金额 / 买入价格
            val fixedAmount = template.fixedAmount?.toSafeBigDecimal()
                ?: throw IllegalStateException("固定金额模式下 fixedAmount 不能为空")
            val buyPrice = leaderTrade.price.toSafeBigDecimal()
            fixedAmount.div(buyPrice)
        }
        else -> throw IllegalArgumentException("不支持的 copyMode: ${template.copyMode}")
    }
}
```

### 7.2 卖出匹配算法

```kotlin
// 卖出时统一按比例计算（不区分模式）
fun matchSellOrder(leaderSellTrade: Trade, copyTrading: CopyTrading, template: CopyTradingTemplate): BigDecimal {
    // 统一按比例计算，不区分 RATIO 或 FIXED 模式
    val needMatch = leaderSellTrade.size.toSafeBigDecimal()
        .multi(template.copyRatio)
    
    val unmatchedOrders = findUnmatchedBuyOrders(copyTrading.id, leaderSellTrade.market, leaderSellTrade.side)
    var totalMatched = BigDecimal.ZERO
    var remaining = needMatch
    
    for (order in unmatchedOrders) {
        if (remaining.lte(BigDecimal.ZERO)) break
        
        val matchQty = min(order.remainingQuantity.toSafeBigDecimal(), remaining)
        totalMatched = totalMatched.add(matchQty)
        remaining = remaining.subtract(matchQty)
        
        updateOrderTracking(order, matchQty)
        recordMatchDetail(order, matchQty, leaderSellTrade)
    }
    
    return totalMatched
}
```

### 7.3 状态更新

```kotlin
fun updateOrderStatus(tracking: CopyOrderTracking) {
    when {
        tracking.remainingQuantity.toSafeBigDecimal().eq(BigDecimal.ZERO) -> {
            tracking.status = "fully_matched"
        }
        tracking.matchedQuantity.toSafeBigDecimal().gt(BigDecimal.ZERO) -> {
            tracking.status = "partially_matched"
        }
        else -> {
            tracking.status = "filled"
        }
    }
}
```

## 8. API 设计

### 8.1 查询跟单统计

```
POST /api/copy-trading/statistics/detail
Request: { copyTradingId: Long }
Response: CopyTradingStatisticsResponse
```

### 8.2 查询订单列表

```
POST /api/copy-trading/orders/tracking
Request: { copyTradingId: Long, type: "buy" | "sell" | "matched" }
Response: OrderListResponse
```

## 9. 优势

1. **精确匹配**：每笔卖出都能追溯到对应的买入订单
2. **准确盈亏**：可以精确计算每笔交易的盈亏
3. **完整统计**：支持多维度数据统计和分析
4. **可追溯性**：完整的买入-卖出匹配关系，便于审计

## 10. WebSocket 与轮询去重机制

### 10.1 同时运行策略

**WebSocket 和轮询可以同时运行**：
- **WebSocket**：作为主要数据源，实时接收交易推送
- **轮询**：作为补充数据源，定期查询确保不遗漏
- **去重机制**：通过 trade_id 确保同一笔交易只处理一次

### 10.2 去重数据模型

#### 已处理交易表（processed_trade）

```sql
CREATE TABLE processed_trade (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    leader_id BIGINT NOT NULL,
    leader_trade_id VARCHAR(100) NOT NULL,  -- Leader 的交易ID（trade.id，唯一标识）
    trade_type VARCHAR(10) NOT NULL,  -- BUY 或 SELL
    source VARCHAR(20) NOT NULL,  -- 'websocket' 或 'polling'
    processed_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    UNIQUE KEY uk_leader_trade (leader_id, leader_trade_id),
    INDEX idx_processed_at (processed_at)
);
```

**唯一标识**：`leader_id + leader_trade_id` 组合作为唯一键

**重要说明**：
- `leader_trade_id` 对应 `TradeResponse.id`（交易ID）
- 交易事件（trade）只有 `id` 字段，没有 `order_id` 字段
- 通过 `trade.id` 进行去重，确保同一笔交易只处理一次

### 10.3 去重流程

```kotlin
/**
 * 处理交易事件（WebSocket 或轮询）
 */
suspend fun processTrade(leaderId: Long, trade: TradeResponse, source: String) {
    // 1. 检查是否已处理（去重）
    // 使用 trade.id 作为唯一标识（TradeResponse 只有 id 字段，没有 order_id）
    val isProcessed = processedTradeRepository.existsByLeaderIdAndLeaderTradeId(
        leaderId, 
        trade.id  // trade.id 是交易ID，用于去重
    )
    
    if (isProcessed) {
        logger.debug("交易已处理，跳过: leaderId=$leaderId, tradeId=${trade.id}, source=$source")
        return
    }
    
    // 2. 处理交易逻辑
    try {
        // 根据 side 判断是买入还是卖出
        when (trade.side.uppercase()) {
            "BUY" -> processBuyTrade(leaderId, trade)
            "SELL" -> processSellTrade(leaderId, trade)
            else -> {
                logger.warn("未知的交易方向: ${trade.side}")
                return
            }
        }
        
        // 3. 标记为已处理
        val processed = ProcessedTrade(
            leaderId = leaderId,
            leaderTradeId = trade.id,  // 使用 trade.id 作为唯一标识
            tradeType = trade.side,
            source = source,
            processedAt = System.currentTimeMillis()
        )
        processedTradeRepository.save(processed)
        
        logger.info("成功处理交易: leaderId=$leaderId, tradeId=${trade.id}, source=$source, side=${trade.side}")
    } catch (e: Exception) {
        logger.error("处理交易失败: leaderId=$leaderId, tradeId=${trade.id}", e)
        // 失败时不标记为已处理，允许重试
    }
}
```

### 10.4 并发安全

**使用数据库唯一约束保证并发安全**：
- 数据库唯一约束：`UNIQUE KEY uk_leader_trade (leader_id, leader_trade_id)`
- 如果 WebSocket 和轮询同时收到同一笔交易：
  - 第一个请求：成功处理并插入记录
  - 第二个请求：插入失败（唯一约束），跳过处理

**或者使用分布式锁**：
```kotlin
// 使用 Redis 分布式锁
val lockKey = "trade:${leaderId}:${trade.id}"
if (redisLock.tryLock(lockKey, 5, TimeUnit.SECONDS)) {
    try {
        if (!isProcessed(leaderId, trade.id)) {
            processTrade(leaderId, trade)
            markAsProcessed(leaderId, trade.id)
        }
    } finally {
        redisLock.unlock(lockKey)
    }
}
```

### 10.5 清理策略

**定期清理过期记录**：
```kotlin
@Scheduled(cron = "0 0 2 * * ?")  // 每天凌晨 2 点
fun cleanupProcessedTrades() {
    val expireTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)  // 保留 7 天
    processedTradeRepository.deleteByProcessedAtBefore(expireTime)
}
```

### 10.6 优势

1. **高可用性**：WebSocket 断开时，轮询继续工作
2. **数据完整性**：轮询确保不遗漏任何交易
3. **实时性**：WebSocket 提供实时推送
4. **去重保证**：通过唯一标识确保不重复处理

## 11. 注意事项

1. **匹配策略**：默认使用 FIFO，可根据需求调整
2. **部分匹配**：支持一个买入订单被多次卖出匹配
3. **数量计算**：使用 util 方法确保数值计算安全
4. **状态同步**：及时更新订单状态，确保数据一致性
5. **模式区别**：
   - **买入时**：RATIO 模式按比例计算，FIXED 模式按固定金额计算
   - **卖出时**：统一按比例计算（`Leader 卖出数量 × copyRatio`），不区分模式
   - **固定金额模式**：只影响买入时的计算，卖出时仍然按比例
6. **去重机制**：
   - WebSocket 和轮询可以同时运行
   - 使用 `leader_id + leader_trade_id` 作为唯一标识去重
   - 数据库唯一约束保证并发安全
   - 定期清理过期记录（建议保留 7 天）

