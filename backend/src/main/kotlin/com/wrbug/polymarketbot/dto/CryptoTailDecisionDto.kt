package com.wrbug.polymarketbot.dto

/**
 * 决策日志查询请求
 * @param startDate 开始时间（毫秒），null 不限制
 * @param endDate 结束时间（毫秒），null 不限制
 */
data class CryptoTailDecisionLogListRequest(
    val strategyId: Long = 0L,
    val page: Int = 1,
    val pageSize: Int = 20,
    val startDate: Long? = null,
    val endDate: Long? = null
)

/**
 * 决策日志 DTO（REST 列表与 WS 推送共用）
 */
data class CryptoTailDecisionEventDto(
    val id: Long = 0L,
    val strategyId: Long = 0L,
    val periodStartUnix: Long = 0L,
    val correlationId: String = "",
    val eventType: String = "",
    val gateName: String? = null,
    val passed: Boolean? = null,
    val reason: String? = null,
    val payloadJson: String? = null,
    val outcomeIndex: Int? = null,
    val triggerId: Long? = null,
    val createdAt: Long = 0L
)

/**
 * 决策日志分页响应
 */
data class CryptoTailDecisionLogListResponse(
    val list: List<CryptoTailDecisionEventDto> = emptyList(),
    val total: Long = 0L
)
