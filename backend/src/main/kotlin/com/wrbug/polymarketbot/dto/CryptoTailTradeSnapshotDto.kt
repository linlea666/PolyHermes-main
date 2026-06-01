package com.wrbug.polymarketbot.dto

/**
 * 单笔成交分析快照查询请求
 */
data class CryptoTailTradeSnapshotListRequest(
    val strategyId: Long = 0L,
    val page: Int = 1,
    val pageSize: Int = 20,
    val startDate: Long? = null,
    val endDate: Long? = null
)

/**
 * 单笔成交分析快照导出请求（按时间范围导出全部）
 */
data class CryptoTailTradeSnapshotExportRequest(
    val strategyId: Long = 0L,
    val startDate: Long? = null,
    val endDate: Long? = null
)

/**
 * 单笔成交分析快照 DTO（数值以字符串返回，避免精度丢失，前端用 formatUSDC 等格式化）
 */
data class CryptoTailTradeSnapshotDto(
    val id: Long = 0L,
    val strategyId: Long = 0L,
    val triggerId: Long? = null,
    val periodStartUnix: Long = 0L,
    val marketSlug: String? = null,
    val conditionId: String? = null,
    val outcomeIndex: Int? = null,
    val intervalSeconds: Int = 0,
    val openPrice: String? = null,
    val entryMarkPrice: String? = null,
    val entryGap: String? = null,
    val sigmaPerSqrtS: String? = null,
    val pWin: String? = null,
    val safeRatio: String? = null,
    val modelSide: Int? = null,
    val remainingSecondsAtEntry: Long? = null,
    val bestBid: String? = null,
    val bestAsk: String? = null,
    val midPrice: String? = null,
    val effectiveCost: String? = null,
    val entryEdge: String? = null,
    val entryProbThreshold: String? = null,
    val entryEdgeThreshold: String? = null,
    val barrierMinMarketProb: String? = null,
    val sigmaScale: String? = null,
    val maxEntryPrice: String? = null,
    val costBuffer: String? = null,
    val orderType: String? = null,
    val targetPrice: String? = null,
    val requestedAmount: String? = null,
    val submitTs: Long? = null,
    val fillStatus: String? = null,
    val fillPrice: String? = null,
    val fillSize: String? = null,
    val fillAmount: String? = null,
    val slippage: String? = null,
    val orderId: String? = null,
    val execError: String? = null,
    val settled: Boolean = false,
    val winnerOutcomeIndex: Int? = null,
    val won: Boolean? = null,
    val realizedPnl: String? = null,
    val settleTs: Long? = null,
    val holdSeconds: Long? = null,
    val finalOpen: String? = null,
    val finalClose: String? = null,
    val finalGap: String? = null,
    val reversed: Boolean? = null,
    val settleSource: String? = null,
    val lossReason: String? = null,
    val pwinBucket: Int? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

/**
 * 单笔成交分析快照分页响应
 */
data class CryptoTailTradeSnapshotListResponse(
    val list: List<CryptoTailTradeSnapshotDto> = emptyList(),
    val total: Long = 0L
)

/**
 * 单笔成交分析快照 CSV 导出响应：csv 为完整文件内容，前端据此生成 Blob 下载
 */
data class CryptoTailTradeSnapshotExportResponse(
    val filename: String = "",
    val csv: String = "",
    val total: Int = 0
)
