package com.wrbug.polymarketbot.dto

/** 历史反转回填请求 */
data class ReversalBackfillRequest(
    val coin: String = "",
    val intervalSeconds: Int = 300,
    val lookbackDays: Int = 180
)

/** 历史反转回填响应 */
data class ReversalBackfillResponse(
    val coin: String = "",
    val intervalSeconds: Int = 0,
    val lookbackDays: Int = 0,
    val periodsProcessed: Int = 0,
    val observations: Int = 0,
    val bucketsWritten: Int = 0
)

/** Polymarket 历史反转回填请求（PoC，maxPeriods 限制采集请求量） */
data class PolymarketReversalBackfillRequest(
    val coin: String = "",
    val intervalSeconds: Int = 300,
    val lookbackDays: Int = 7,
    val maxPeriods: Int = 300
)

/** Polymarket 历史反转回填响应（PoC） */
data class PolymarketReversalBackfillResponse(
    val coin: String = "",
    val intervalSeconds: Int = 0,
    val lookbackDays: Int = 0,
    val periodsRequested: Int = 0,
    val periodsResolved: Int = 0,
    val observations: Int = 0,
    val bucketsWritten: Int = 0,
    val dataSource: String = "POLYMARKET"
)

/** 历史反转研究列表请求 */
data class ReversalResearchListRequest(
    val coin: String = "",
    val intervalSeconds: Int = 300,
    val lookbackDays: Int = 180,
    val dataSource: String = "BINANCE"
)

/** 单个分桶反转统计 DTO（数值以字符串返回） */
data class ReversalStatDto(
    val coin: String = "",
    val intervalSeconds: Int = 0,
    val outcomeIndex: Int = 0,
    val diffSigmaBucket: String = "",
    val oddsBucket: String = "ANY",
    val remainingBucket: String = "",
    val lookbackDays: Int = 0,
    val dataSource: String = "BINANCE",
    val sampleCount: Int = 0,
    val reversedCount: Int = 0,
    /** 维持到结算概率 0~1 */
    val modelProb: String = "0",
    /** 反转率 0~1 = 1 - modelProb */
    val reversalRate: String = "0",
    val computedAt: Long = 0L
)

/** 历史反转研究列表响应 */
data class ReversalResearchListResponse(
    val list: List<ReversalStatDto> = emptyList(),
    val total: Int = 0
)

/** 历史反转 CSV 导出响应 */
data class ReversalResearchCsvResponse(
    val filename: String = "",
    val csv: String = "",
    val total: Int = 0
)
