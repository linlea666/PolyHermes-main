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
    /** 策略名（跨策略汇总页展示用；单策略查询可为 null） */
    val strategyName: String? = null,
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

/**
 * 决策日志导出请求（按时间区间整段导出，strategyId<=0 表示全部策略）
 */
data class CryptoTailDecisionLogExportRequest(
    val strategyId: Long = 0L,
    val startDate: Long? = null,
    val endDate: Long? = null
)

/**
 * 决策日志导出响应（JSON 整段返回，前端序列化为 .json 下载）
 */
data class CryptoTailDecisionLogExportResponse(
    val list: List<CryptoTailDecisionEventDto> = emptyList(),
    val total: Long = 0L,
    val exportedAt: Long = System.currentTimeMillis()
)
