package com.wrbug.polymarketbot.dto

/** 历史反转回填请求（samplingSeconds：1=尾盘 1s 细采样，60=1m 默认） */
data class ReversalBackfillRequest(
    val coin: String = "",
    val intervalSeconds: Int = 300,
    val lookbackDays: Int = 180,
    val samplingSeconds: Int = 60
)

/** 历史反转回填响应 */
data class ReversalBackfillResponse(
    val coin: String = "",
    val intervalSeconds: Int = 0,
    val lookbackDays: Int = 0,
    val samplingSeconds: Int = 60,
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
    val dataSource: String = "POLYMARKET",
    // 诊断分类计数：定位"为什么没数据"
    val slugNotFound: Int = 0,
    val historyEmpty: Int = 0,
    val tooFewPoints: Int = 0,
    val fetchError: Int = 0,
    // 覆盖范围：仍有未采集周期（pending>0）时为 true；coverageDays 为已覆盖天数
    val coverageCapped: Boolean = false,
    val coverageDays: Double = 0.0,
    // 增量进度：本次复用缓存 / 新联网采集 / 受预算限制尚未采集（再次执行可续跑）
    val reused: Int = 0,
    val newlyFetched: Int = 0,
    val pending: Int = 0
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
    /** 取样间隔（秒）：60=1m，1=尾盘 1s，0=POLYMARKET 原生不规则采样 */
    val samplingSeconds: Int = 60,
    /** 去重后贡献该桶的不同周期数 */
    val distinctPeriodCount: Int = 0,
    /** 平均最大不利偏移（领先方向胜率口径）；不可用为空串 */
    val maeAvg: String = "",
    /** 平均最大有利偏移（领先方向胜率口径）；不可用为空串 */
    val mfeAvg: String = "",
    /** 虚拟括号退出先触达 TP 的比例；不可用为空串 */
    val virtualTpRate: String = "",
    /** 虚拟括号退出先触达 STOP 的比例；不可用为空串 */
    val virtualStopRate: String = "",
    /** 虚拟括号退出净盈利比例；不可用为空串 */
    val virtualWinRate: String = "",
    /** 虚拟括号退出每单位平均盈亏；不可用为空串 */
    val virtualPnlAvg: String = "",
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
