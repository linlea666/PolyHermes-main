package com.wrbug.polymarketbot.dto

/**
 * 被过滤订单列表请求
 */
data class FilteredOrderListRequest(
    val copyTradingId: Long,
    val filterType: String? = null,  // 过滤类型（可选）
    val page: Int? = 1,
    val limit: Int? = 20,
    val startTime: Long? = null,  // 开始时间（毫秒时间戳，可选）
    val endTime: Long? = null  // 结束时间（毫秒时间戳，可选）
)

/**
 * 被过滤订单信息响应
 */
data class FilteredOrderDto(
    val id: Long,
    val copyTradingId: Long,
    val accountId: Long,
    val accountName: String?,
    val leaderId: Long,
    val leaderName: String?,
    val leaderTradeId: String,
    val marketId: String,
    val marketTitle: String?,
    val marketSlug: String?,
    val side: String,  // BUY 或 SELL
    val outcomeIndex: Int?,
    val outcome: String?,
    val price: String,
    val size: String,
    val calculatedQuantity: String?,
    val filterReason: String,
    val filterType: String,
    val createdAt: Long
)

/**
 * 被过滤订单列表响应
 */
data class FilteredOrderListResponse(
    val list: List<FilteredOrderDto>,
    val total: Long,
    val page: Int,
    val limit: Int
)

