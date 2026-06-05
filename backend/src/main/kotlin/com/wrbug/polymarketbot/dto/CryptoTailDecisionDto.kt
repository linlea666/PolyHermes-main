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

/**
 * 决策日志批量删除请求（按 id 集合）
 */
data class CryptoTailDecisionLogBatchDeleteRequest(
    val ids: List<Long> = emptyList()
)

/**
 * 决策日志按时间清理请求
 * @param strategyId 0 = 全部策略，>0 = 仅清理该策略
 * @param beforeDate 清理 createdAt 严格小于该时间（毫秒）的日志
 */
data class CryptoTailDecisionLogPurgeRequest(
    val strategyId: Long = 0L,
    val beforeDate: Long = 0L
)

/**
 * 决策日志删除/清理响应
 */
data class CryptoTailDecisionLogDeleteResponse(
    val deleted: Int = 0
)

/**
 * 周期生命周期汇总查询请求（startDate/endDate 为毫秒，strategyId<=0 = 全部策略）
 */
data class CryptoTailPeriodSummaryListRequest(
    val strategyId: Long = 0L,
    val page: Int = 1,
    val pageSize: Int = 20,
    val startDate: Long? = null,
    val endDate: Long? = null
)

/**
 * 周期生命周期汇总 DTO（金额/价格用字符串避免精度丢失）
 */
data class CryptoTailPeriodSummaryDto(
    val id: Long = 0L,
    val strategyId: Long = 0L,
    val strategyName: String? = null,
    val periodStartUnix: Long = 0L,
    val periodEndUnix: Long = 0L,
    val marketSlug: String? = null,
    val firstChosenOutcomeIndex: Int? = null,
    val lastChosenOutcomeIndex: Int? = null,
    val directionFlipCount: Int = 0,
    val bestScore: Int = 0,
    val dominantVeto: String? = null,
    val scoreEventCount: Int = 0,
    val skipEventCount: Int = 0,
    val buyEventCount: Int = 0,
    val traded: Boolean = false,
    val triggerId: Long? = null,
    val officialOpen: String? = null,
    val officialClose: String? = null,
    val officialGap: String? = null,
    val settledWinnerOutcomeIndex: Int? = null,
    val directionCorrect: Boolean? = null,
    val realizedPnl: String? = null,
    val status: String = "OPEN",
    val settledAt: Long? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

/**
 * 周期汇总分页响应 + 方向准确率汇总
 * @param directionAccuracy 命中数/已结算且首选方向非空总数（0-1 字符串），无样本为 null
 */
data class CryptoTailPeriodSummaryListResponse(
    val list: List<CryptoTailPeriodSummaryDto> = emptyList(),
    val total: Long = 0L,
    val settledCount: Long = 0L,
    val directionCorrectCount: Long = 0L,
    val tradedCount: Long = 0L,
    val directionAccuracy: String? = null
)
