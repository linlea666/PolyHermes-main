# 跟单买入和卖出实现方案

## 1. 核心思路

### 1.1 基本原理
- **买入跟单**：当 Leader 执行 `BUY` 交易时，系统自动创建 `BUY` 订单
- **卖出跟单**：当 Leader 执行 `SELL` 交易时，系统自动创建 `SELL` 订单
- **方向复制**：直接复制 Leader 交易的 `side` 字段（BUY 或 SELL）

### 1.2 数据来源
- **方式1（优先）**：WebSocket 推送（RTDS API）
  - 实时接收 Leader 的交易推送
  - WebSocket URL: `wss://ws-live-data.polymarket.com`
  - 订阅用户交易频道，实时获取交易数据
- **方式2（备选）**：轮询 CLOB API
  - 通过 CLOB API `/trades?user={leaderAddress}` 获取 Leader 的交易记录
  - 定期轮询（默认每 5 秒）

**交易数据包含**：
- `side`: "BUY" 或 "SELL"（直接复制）
- `market`: 市场地址（直接复制）
- `price`: 交易价格（可调整）
- `size`: 交易数量（按比例或固定金额计算）

## 2. 实现流程

### 2.1 监控 Leader 交易

#### 2.1.1 WebSocket 推送模式（优先）

```kotlin
/**
 * WebSocket 推送监控服务
 */
@Service
class CopyTradingWebSocketService(
    private val leaderRepository: LeaderRepository,
    private val configRepository: CopyTradingConfigRepository
) {
    private var webSocketClient: WebSocketClient? = null
    private val subscribedLeaders = mutableSetOf<String>()
    
    /**
     * 初始化 WebSocket 连接
     */
    @PostConstruct
    fun initWebSocket() {
        val config = configRepository.findFirstByOrderByIdAsc() ?: getDefaultConfig()
        
        if (config.useWebSocket) {
            connectWebSocket()
        }
    }
    
    /**
     * 连接 WebSocket
     */
    private fun connectWebSocket() {
        try {
            val wsUrl = "wss://ws-live-data.polymarket.com"
            webSocketClient = WebSocketClient(wsUrl)
            
            webSocketClient?.onMessage { message ->
                handleWebSocketMessage(message)
            }
            
            webSocketClient?.onError { error ->
                logger.error("WebSocket 连接错误", error)
                // 降级到轮询模式
                fallbackToPolling()
            }
            
            webSocketClient?.onClose {
                logger.warn("WebSocket 连接关闭，尝试重连")
                reconnectWebSocket()
            }
            
            // 订阅所有启用的 Leader
            subscribeAllLeaders()
            
        } catch (e: Exception) {
            logger.error("WebSocket 连接失败，降级到轮询模式", e)
            fallbackToPolling()
        }
    }
    
    /**
     * 订阅所有启用的 Leader
     */
    private fun subscribeAllLeaders() {
        val enabledLeaders = leaderRepository.findByEnabledTrue()
        enabledLeaders.forEach { leader ->
            subscribeLeader(leader.leaderAddress)
        }
    }
    
    /**
     * 订阅单个 Leader
     */
    fun subscribeLeader(leaderAddress: String) {
        val subscribeMessage = jsonObjectOf(
            "type" to "subscribe",
            "channel" to "user",
            "user" to leaderAddress
        )
        webSocketClient?.send(subscribeMessage.toString())
        subscribedLeaders.add(leaderAddress)
    }
    
    /**
     * 处理 WebSocket 消息
     */
    private fun handleWebSocketMessage(message: String) {
        try {
            val json = JSONObject(message)
            val channel = json.optString("channel")
            val eventType = json.optString("event")
            
            if (channel == "user" && eventType == "trade") {
                val trade = parseTradeMessage(json)
                // 触发跟单逻辑
                processTradeFromWebSocket(trade)
            }
        } catch (e: Exception) {
            logger.error("处理 WebSocket 消息失败", e)
        }
    }
    
    /**
     * 重连 WebSocket
     */
    private fun reconnectWebSocket() {
        val config = configRepository.findFirstByOrderByIdAsc() ?: getDefaultConfig()
        var retryCount = 0
        
        while (retryCount < config.websocketMaxRetries) {
            try {
                Thread.sleep(config.websocketReconnectInterval.toLong())
                connectWebSocket()
                return
            } catch (e: Exception) {
                retryCount++
                logger.warn("WebSocket 重连失败 (${retryCount}/${config.websocketMaxRetries})", e)
            }
        }
        
        // 重连失败，降级到轮询
        logger.error("WebSocket 重连失败，降级到轮询模式")
        fallbackToPolling()
    }
}
```

#### 2.1.2 轮询模式（备选）

```kotlin
/**
 * 轮询监控服务（WebSocket 不可用时的备选方案）
 */
@Service
class CopyTradingPollingService(
    private val clobService: PolymarketClobService,
    private val leaderRepository: LeaderRepository,
    private val configRepository: CopyTradingConfigRepository
) {
    
    /**
     * 定期轮询所有启用的 Leader 的交易
     */
    @Scheduled(fixedDelayString = "\${copy.trading.poll.interval:5000}")
    suspend fun monitorLeaders() {
        val config = configRepository.findFirstByOrderByIdAsc() ?: getDefaultConfig()
        
        // 如果配置了使用 WebSocket 且 WebSocket 可用，则不轮询
        if (config.useWebSocket && isWebSocketAvailable()) {
            return
        }
        
        val enabledLeaders = leaderRepository.findByEnabledTrue()
        
        enabledLeaders.forEach { leader ->
            try {
                processLeaderTrades(leader)
            } catch (e: Exception) {
                logger.error("处理 Leader ${leader.id} 交易失败", e)
            }
        }
    }
    
    /**
     * 处理单个 Leader 的交易
     */
    private suspend fun processLeaderTrades(leader: Leader) {
        // 1. 获取 Leader 的最新交易记录
        val tradesResult = clobService.getTrades(
            market = null,
            user = leader.leaderAddress,
            limit = 50,
            offset = 0
        )
        
        tradesResult.fold(
            onSuccess = { trades ->
                trades.forEach { trade ->
                    // 2. 检查是否已处理过（去重）
                    if (!isProcessed(leader.id, trade.id)) {
                        // 3. 处理交易（买入或卖出）
                        processTrade(leader, trade)
                        // 4. 标记为已处理
                        markAsProcessed(leader.id, trade.id)
                    }
                }
            },
            onFailure = { e ->
                logger.error("获取 Leader ${leader.id} 交易失败", e)
            }
        )
    }
}
```

### 2.2 处理交易（买入/卖出）

```kotlin
/**
 * 处理单笔交易，自动识别买入或卖出
 */
private suspend fun processTrade(leader: Leader, trade: TradeResponse) {
    // 1. 验证分类筛选
    if (leader.category != null) {
        val marketCategory = getMarketCategory(trade.market)
        if (marketCategory != leader.category) {
            logger.debug("跳过交易：分类不匹配 ${trade.market}")
            return
        }
    }
    
    // 2. 验证风险控制
    if (!checkRiskControl(leader)) {
        logger.warn("风险控制限制，跳过跟单 Leader ${leader.id}")
        return
    }
    
    // 3. 确定使用的账户
    val account = getAccountForLeader(leader)
    if (account == null) {
        logger.error("无法获取账户，跳过跟单 Leader ${leader.id}")
        return
    }
    
    // 4. 计算跟单订单参数
    val orderParams = calculateOrderParams(leader, trade)
    
    // 5. 创建跟单订单（买入或卖出）
    createCopyOrder(leader, account, trade, orderParams)
}

/**
 * 计算跟单订单参数
 */
private fun calculateOrderParams(leader: Leader, trade: TradeResponse): OrderParams {
    // 获取配置
    val globalConfig = configRepository.findFirstByOrderByIdAsc() ?: getDefaultConfig()
    
    // 计算订单大小
    val leaderSize = trade.size.toSafeBigDecimal()
    val copyRatio = leader.copyRatio ?: globalConfig.copyRatio
    var orderSize = leaderSize.multiply(copyRatio)
    
    // 应用限制
    val maxSize = leader.maxOrderSize ?: globalConfig.maxOrderSize
    val minSize = leader.minOrderSize ?: globalConfig.minOrderSize
    orderSize = orderSize.coerceIn(minSize, maxSize)
    
    // 计算价格（默认使用 Leader 的价格）
    val price = trade.price.toSafeBigDecimal()
    // TODO: 可以根据价格容忍度调整价格
    
    return OrderParams(
        market = trade.market,
        side = trade.side,  // 直接复制 BUY 或 SELL
        price = price,
        size = orderSize
    )
}

data class OrderParams(
    val market: String,
    val side: String,  // "BUY" 或 "SELL"
    val price: BigDecimal,
    val size: BigDecimal
)
```

### 2.3 创建跟单订单

```kotlin
/**
 * 创建跟单订单（买入或卖出）
 */
private suspend fun createCopyOrder(
    leader: Leader,
    account: Account,
    trade: TradeResponse,
    params: OrderParams
) {
    try {
        // 1. 创建订单请求
        val orderRequest = CreateOrderRequest(
            market = params.market,
            side = params.side,  // "BUY" 或 "SELL"
            price = params.price.toPlainString(),
            size = params.size.toPlainString(),
            type = "LIMIT"
        )
        
        // 2. 使用账户的 API Key 创建订单
        val apiKey = account.apiKey ?: throw IllegalStateException("账户 ${account.id} 未配置 API Key")
        val clobApi = createClobApiWithApiKey(apiKey)
        
        val orderResult = clobApi.createOrder(orderRequest)
        
        orderResult.fold(
            onSuccess = { orderResponse ->
                // 3. 保存跟单记录
                val copyOrder = CopyOrder(
                    accountId = account.id!!,
                    leaderId = leader.id!!,
                    leaderAddress = leader.leaderAddress,
                    leaderTradeId = trade.id,
                    marketId = params.market,
                    category = getMarketCategory(params.market),
                    side = params.side,  // 保存买入或卖出方向
                    price = params.price,
                    size = params.size,
                    copyRatio = leader.copyRatio ?: BigDecimal.ONE,
                    orderId = orderResponse.id,
                    status = "created"
                )
                copyOrderRepository.save(copyOrder)
                
                logger.info("成功创建跟单订单: Leader=${leader.id}, Side=${params.side}, Market=${params.market}")
            },
            onFailure = { e ->
                logger.error("创建跟单订单失败: Leader=${leader.id}, Side=${params.side}", e)
                
                // 记录失败的跟单订单
                val copyOrder = CopyOrder(
                    accountId = account.id!!,
                    leaderId = leader.id!!,
                    leaderAddress = leader.leaderAddress,
                    leaderTradeId = trade.id,
                    marketId = params.market,
                    category = getMarketCategory(params.market),
                    side = params.side,
                    price = params.price,
                    size = params.size,
                    copyRatio = leader.copyRatio ?: BigDecimal.ONE,
                    status = "failed"
                )
                copyOrderRepository.save(copyOrder)
            }
        )
    } catch (e: Exception) {
        logger.error("创建跟单订单异常: Leader=${leader.id}, Side=${params.side}", e)
    }
}
```

## 3. 关键实现细节

### 3.1 买入和卖出的区别

**买入跟单（BUY）**：
- Leader 执行 `BUY` 交易 → 系统创建 `BUY` 订单
- 订单参数：`side = "BUY"`
- 表示买入该市场的 YES 或 NO 代币

**卖出跟单（SELL）**：
- Leader 执行 `SELL` 交易 → 系统创建 `SELL` 订单
- 订单参数：`side = "SELL"`
- 表示卖出持有的代币

**实现上无区别**：
- 买入和卖出的处理逻辑完全相同
- 只是 `side` 字段的值不同（"BUY" 或 "SELL"）
- 都通过 `createOrder` API 创建订单

### 3.2 去重机制

```kotlin
/**
 * 检查交易是否已处理
 */
private suspend fun isProcessed(leaderId: Long, tradeId: String): Boolean {
    return processedTradeRepository.existsByLeaderIdAndTradeId(leaderId, tradeId)
}

/**
 * 标记交易为已处理
 */
private suspend fun markAsProcessed(leaderId: Long, tradeId: String) {
    val processed = ProcessedTrade(
        leaderId = leaderId,
        tradeId = tradeId,
        processedAt = System.currentTimeMillis()
    )
    processedTradeRepository.save(processed)
}
```

### 3.3 账户选择

```kotlin
/**
 * 获取 Leader 使用的账户
 */
private suspend fun getAccountForLeader(leader: Leader): Account? {
    return if (leader.accountId != null) {
        // 使用 Leader 指定的账户
        accountRepository.findById(leader.accountId)
    } else {
        // 使用默认账户
        accountRepository.findByIsDefaultTrue()
    }
}
```

### 3.4 风险控制检查

```kotlin
/**
 * 检查风险控制限制
 */
private suspend fun checkRiskControl(leader: Leader): Boolean {
    val config = configRepository.findFirstByOrderByIdAsc() ?: getDefaultConfig()
    
    // 检查每日亏损限制
    val todayLoss = getTodayLoss(leader.accountId ?: getDefaultAccountId())
    if (todayLoss >= config.maxDailyLoss) {
        logger.warn("达到每日亏损限制: $todayLoss >= ${config.maxDailyLoss}")
        return false
    }
    
    // 检查每日订单数限制
    val todayOrderCount = getTodayOrderCount(leader.accountId ?: getDefaultAccountId())
    if (todayOrderCount >= config.maxDailyOrders) {
        logger.warn("达到每日订单数限制: $todayOrderCount >= ${config.maxDailyOrders}")
        return false
    }
    
    return true
}
```

## 4. 数据模型

### 4.1 ProcessedTrade（已处理交易）

```kotlin
@Entity
@Table(name = "copy_trading_processed_trades")
data class ProcessedTrade(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "leader_id", nullable = false)
    val leaderId: Long,
    
    @Column(name = "trade_id", nullable = false, length = 100)
    val tradeId: String,
    
    @Column(name = "processed_at", nullable = false)
    val processedAt: Long = System.currentTimeMillis(),
    
    @UniqueConstraint(columnNames = ["leader_id", "trade_id"])
)
```

## 5. 完整示例

### 5.1 买入跟单示例

```
1. Leader 执行买入交易：
   - Trade: { id: "trade_123", market: "0x...", side: "BUY", price: "0.5", size: "100" }

2. 系统检测到交易：
   - 验证分类、风险控制
   - 计算跟单参数：size = 100 × 1.0 = 100

3. 创建跟单订单：
   - CreateOrderRequest: { market: "0x...", side: "BUY", price: "0.5", size: "100" }
   - 调用 CLOB API 创建订单

4. 保存记录：
   - CopyOrder: { side: "BUY", ... }
```

### 5.2 卖出跟单示例

```
1. Leader 执行卖出交易：
   - Trade: { id: "trade_456", market: "0x...", side: "SELL", price: "0.6", size: "50" }

2. 系统检测到交易：
   - 验证分类、风险控制
   - 计算跟单参数：size = 50 × 1.0 = 50

3. 创建跟单订单：
   - CreateOrderRequest: { market: "0x...", side: "SELL", price: "0.6", size: "50" }
   - 调用 CLOB API 创建订单

4. 保存记录：
   - CopyOrder: { side: "SELL", ... }
```

## 6. 注意事项

### 6.1 交易 vs 订单
- **交易（Trade）**：已成交的记录，包含 `side` 字段
- **订单（Order）**：挂单，可能未成交
- 跟单系统基于**交易记录**触发，创建**订单**

### 6.2 价格和数量
- **价格**：默认使用 Leader 的交易价格，可配置价格容忍度
- **数量**：按跟单比例计算，应用最大/最小限制

### 6.3 错误处理
- API 调用失败时记录失败状态
- 网络异常时重试机制
- 记录详细日志便于排查

### 6.4 性能优化
- 批量查询多个 Leader 的交易
- 使用缓存减少重复查询
- 异步处理订单创建

## 7. 总结

**买入和卖出的实现完全相同**：
- 都通过监控 Leader 的交易记录触发
- 都通过 `side` 字段区分（"BUY" 或 "SELL"）
- 都调用相同的 `createOrder` API
- 区别仅在于 `side` 参数的值

**核心流程**：
1. 轮询 Leader 交易 → 2. 识别买入/卖出 → 3. 计算参数 → 4. 创建订单 → 5. 保存记录

