package com.wrbug.polymarketbot.dto

/**
 * 加密价差策略监控初始化请求
 */
data class CryptoTailMonitorInitRequest(
    /** 策略ID */
    val strategyId: Long = 0L,
    /** 指定周期开始时间 (Unix 秒)，不传则用服务器当前周期 */
    val periodStartUnix: Long? = null
)

/**
 * 加密价差策略监控初始化响应
 */
data class CryptoTailMonitorInitResponse(
    /** 策略ID */
    val strategyId: Long = 0L,
    /** 策略名称 */
    val name: String = "",
    /** 账户ID */
    val accountId: Long = 0L,
    /** 账户名称 */
    val accountName: String = "",
    /** 市场 slug 前缀 */
    val marketSlugPrefix: String = "",
    /** 市场标题 */
    val marketTitle: String = "",
    /** 周期秒数 (300=5m, 900=15m) */
    val intervalSeconds: Int = 300,
    /** 当前周期开始时间 (Unix 秒) */
    val periodStartUnix: Long = 0L,
    /** 时间窗口开始秒数 */
    val windowStartSeconds: Int = 0,
    /** 时间窗口结束秒数 */
    val windowEndSeconds: Int = 0,
    /** 最低价格 */
    val minPrice: String = "0",
    /** 最高价格 */
    val maxPrice: String = "1",
    /** 最小价差模式: NONE, FIXED, AUTO */
    val minSpreadMode: String = "NONE",
    /** 价差方向: MIN（显示周期内最小价差）, MAX（显示周期内最大价差） */
    val spreadDirection: String = "MIN",
    /** 最小价差数值 (FIXED 时有值) */
    val minSpreadValue: String? = null,
    /** 自动计算的最小价差 (Up方向) */
    val autoMinSpreadUp: String? = null,
    /** 自动计算的最小价差 (Down方向) */
    val autoMinSpreadDown: String? = null,
    /** 兼容旧字段：官方标的开盘价（概率模式）或 Binance 开盘价（legacy） */
    val openPriceBtc: String? = null,
    val officialOpen: String? = null,
    val officialClose: String? = null,
    val officialPriceSource: String? = null,
    val officialPriceAgeMs: Long? = null,
    val priceMode: String? = null,
    val lastSnapshotAt: Long? = null,
    val lastRealtimeUpdateAt: Long? = null,
    val latestPriceAgeMs: Long? = null,
    val latestSampleTime: Long? = null,
    val priceReadyReason: String? = null,
    val coin: String? = null,
    val fallbackUsed: Boolean = false,
    val legacyOpen: String? = null,
    val legacyClose: String? = null,
    val legacyPriceSource: String? = null,
    /** Up tokenId */
    val tokenIdUp: String? = null,
    /** Down tokenId */
    val tokenIdDown: String? = null,
    /** 当前时间 (毫秒时间戳) */
    val currentTimestamp: Long = System.currentTimeMillis(),
    /** 是否启用 */
    val enabled: Boolean = true,
    /** 投入金额模式: FIXED or RATIO */
    val amountMode: String? = null,
    /** 投入金额数值 */
    val amountValue: String? = null
)

/**
 * 加密价差策略监控实时推送数据
 */
data class CryptoTailMonitorPushData(
    /** 策略ID */
    val strategyId: Long = 0L,
    /** 推送时间 (毫秒时间戳) */
    val timestamp: Long = System.currentTimeMillis(),
    /** 当前周期开始时间 (Unix 秒) */
    val periodStartUnix: Long = 0L,
    /** 当前周期市场标题（周期切换时更新） */
    val marketTitle: String = "",
    /** 当前价格 (Up方向，来自订单簿) */
    val currentPriceUp: String? = null,
    /** 当前价格 (Down方向，来自订单簿) */
    val currentPriceDown: String? = null,
    /** 当前价差 (Up方向: 1 - currentPriceUp) */
    val spreadUp: String? = null,
    /** 当前价差 (Down方向: currentPriceUp) */
    val spreadDown: String? = null,
    /** 最小价差线 (Up方向) */
    val minSpreadLineUp: String? = null,
    /** 最小价差线 (Down方向，USDC 价差) */
    val minSpreadLineDown: String? = null,
    /** 兼容旧字段：官方标的开盘价（概率模式）或 Binance 开盘价（legacy） */
    val openPriceBtc: String? = null,
    /** 兼容旧字段：官方标的最新价（概率模式）或 Binance 最新价（legacy） */
    val currentPriceBtc: String? = null,
    /** BTC 价差 USDC（currentPriceBtc - openPriceBtc） */
    val spreadBtc: String? = null,
    val officialOpen: String? = null,
    val officialClose: String? = null,
    val officialPriceSource: String? = null,
    val officialPriceAgeMs: Long? = null,
    val priceMode: String? = null,
    val lastSnapshotAt: Long? = null,
    val lastRealtimeUpdateAt: Long? = null,
    val latestPriceAgeMs: Long? = null,
    val latestSampleTime: Long? = null,
    val priceReadyReason: String? = null,
    val coin: String? = null,
    val fallbackUsed: Boolean = false,
    val outcomeBestBidUp: String? = null,
    val outcomeBestBidDown: String? = null,
    val outcomeBestAskUp: String? = null,
    val outcomeBestAskDown: String? = null,
    val outcomeSpreadUp: String? = null,
    val outcomeSpreadDown: String? = null,
    val outcomeDirection: String? = null,
    val legacyOpen: String? = null,
    val legacyClose: String? = null,
    val legacyPriceSource: String? = null,
    /** 周期剩余秒数 */
    val remainingSeconds: Int = 0,
    /** 是否在时间窗口内 */
    val inTimeWindow: Boolean = false,
    /** 是否在价格区间内 (Up方向) */
    val inPriceRangeUp: Boolean = false,
    /** 是否在价格区间内 (Down方向) */
    val inPriceRangeDown: Boolean = false,
    /** 是否已触发 */
    val triggered: Boolean = false,
    /** 触发方向: UP, DOWN, null */
    val triggerDirection: String? = null,
    /** 周期是否已结束 */
    val periodEnded: Boolean = false,
    val positionId: Long? = null,
    val positionOutcomeIndex: Int? = null,
    val entryFillPrice: String? = null,
    val currentBestBid: String? = null,
    val floatingPnl: String? = null,
    val peakBid: String? = null,
    val drawdownFromPeak: String? = null,
    val stopLossLine: String? = null,
    val trailingStartLine: String? = null,
    val trailingStopLine: String? = null,
    val takeProfitLine1: String? = null,
    val takeProfitLine2: String? = null,
    val exitReason: String? = null,
    val wickUpperRatio: String? = null,
    val wickLowerRatio: String? = null,
    val wickBodyRatio: String? = null,
    val wickCloseVsMa: String? = null,
    val wickClosePosition: String? = null,
    val wickRangeSigmaRatio: String? = null,
    val wickTickCount: Int? = null,
    val wickQualityReason: String? = null,
    val wickReversalScore: Int? = null,
    val wickContinuationScore: Int? = null,
    val wickRejectionSide: String? = null
)
