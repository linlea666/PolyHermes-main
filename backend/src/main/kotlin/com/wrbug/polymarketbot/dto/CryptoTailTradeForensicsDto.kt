package com.wrbug.polymarketbot.dto

/** 复盘因子明细查询请求（所有过滤项可空，page 从 1 起） */
data class ForensicsListRequest(
    val strategyId: Long? = null,
    val marketSlug: String? = null,
    val intervalSeconds: Int? = null,
    val outcomeCategory: String? = null,
    val onlySettled: Boolean = false,
    val startTs: Long? = null,
    val endTs: Long? = null,
    val page: Int = 1,
    val pageSize: Int = 20
)

/** 复盘因子明细 DTO（数值以字符串返回，避免精度丢失，前端用 formatUSDC 等格式化） */
data class ForensicsDto(
    val id: Long = 0L,
    val strategyId: Long = 0L,
    val accountId: Long? = null,
    val marketSlug: String? = null,
    val intervalSeconds: Int = 0,
    val periodStartUnix: Long = 0L,
    val triggerId: Long? = null,
    val mode: Int? = null,
    val outcomeIndex: Int? = null,

    val entryTs: Long? = null,
    val entryRemainingSeconds: Int? = null,
    val entryOfficialTarget: String? = null,
    val entryCurrentPrice: String? = null,
    val entryGap: String? = null,
    val entryGapAbs: String? = null,
    val entryGapPct: String? = null,
    val entryDiffSigma: String? = null,
    val entryPwin: String? = null,
    val entryModelSide: Int? = null,
    val entryBestBid: String? = null,
    val entryBestAsk: String? = null,
    val entryFillPrice: String? = null,
    val entryWallHour: Int? = null,
    val entryDow: Int? = null,

    val entryDiffSigmaBucket: String? = null,
    val entryOddsBucket: String? = null,
    val entryRemainingBucket: String? = null,

    val fillVsBandDev: String? = null,
    val requoteCount: Int? = null,
    val submitLatencyMs: Long? = null,
    val entrySlippage: String? = null,

    val leadReversed: Boolean? = null,
    val firstReversalRemainingSeconds: Int? = null,
    val troughSafeRatio: String? = null,
    val troughGap: String? = null,
    val maxDiffRetracePct: String? = null,
    val minBestBid: String? = null,
    val peakBestBid: String? = null,
    val reversalSampleCount: Int? = null,
    val recoveredAfterReversal: Boolean? = null,

    val maeOdds: String? = null,
    val mfeOdds: String? = null,
    val maeSigma: String? = null,
    val mfeSigma: String? = null,

    val exitKind: String? = null,
    val exitReason: String? = null,
    val wasCut: Boolean? = null,
    val exitPrice: String? = null,
    val exitSlippage: String? = null,
    val exitExecutableDepthUsd: String? = null,
    val holdSeconds: Long? = null,
    val settled: Boolean = false,
    val won: Boolean? = null,
    val winnerOutcomeIndex: Int? = null,
    val finalOfficialTarget: String? = null,
    val finalCurrentPrice: String? = null,
    val finalGap: String? = null,
    val realizedPnl: String? = null,

    val wouldHaveWonIfHeld: Boolean? = null,
    val counterfactualHoldPnl: String? = null,
    val cutVsHoldDelta: String? = null,

    val cfgFingerprint: String? = null,
    val cfgGapGateEnabled: Boolean? = null,

    val directionCorrect: Boolean? = null,
    val outcomeCategory: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

/** 复盘因子明细分页响应 */
data class ForensicsListResponse(
    val list: List<ForensicsDto> = emptyList(),
    val total: Long = 0L
)

/** 多维分组聚合请求：dim1 必填、dim2 可选（二维热力）。维度名须在 allowedDimensions 白名单内 */
data class ForensicsAggregateRequest(
    val dim1: String = "",
    val dim2: String? = null,
    val strategyId: Long? = null,
    val marketSlug: String? = null,
    val intervalSeconds: Int? = null,
    val outcomeCategory: String? = null,
    val onlySettled: Boolean = true,
    val startTs: Long? = null,
    val endTs: Long? = null
)

/** 聚合单组结果（计数 + 派生率 + 均值，均以字符串返回） */
data class ForensicsAggregateRow(
    val key1: String? = null,
    val key2: String? = null,
    val count: Long = 0L,
    val wins: Long = 0L,
    val directionCorrect: Long = 0L,
    val cuts: Long = 0L,
    val wouldWin: Long = 0L,
    val reversedCount: Long = 0L,
    val recoveredCount: Long = 0L,
    val winRate: String = "0",
    val directionAccuracy: String = "0",
    val cutRate: String = "0",
    val cutButWouldWinRate: String = "0",
    val reversalRate: String = "0",
    val recoverRate: String = "0",
    val avgDiffSigma: String? = null,
    val avgGapAbs: String? = null,
    val avgBestAsk: String? = null,
    val avgPwin: String? = null,
    val avgRetrace: String? = null,
    val avgMaeOdds: String? = null,
    val avgMfeOdds: String? = null,
    val avgFirstReversalRemaining: String? = null,
    val avgHoldSeconds: String? = null,
    val sumPnl: String? = null,
    val avgPnl: String? = null,
    val sumCutVsHold: String? = null
)

/** 聚合响应 */
data class ForensicsAggregateResponse(
    val dim1: String = "",
    val dim2: String? = null,
    val rows: List<ForensicsAggregateRow> = emptyList(),
    val allowedDimensions: List<String> = emptyList()
)

/** 回填请求：从 durable trade_snapshot 重建复盘因子（startTs/endTs 均空则全量） */
data class ForensicsBackfillRequest(
    val strategyId: Long = 0L,
    val startTs: Long? = null,
    val endTs: Long? = null
)

/** 回填响应 */
data class ForensicsBackfillResponse(
    val processed: Int = 0
)
