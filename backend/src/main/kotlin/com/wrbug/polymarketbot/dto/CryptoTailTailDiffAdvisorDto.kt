package com.wrbug.polymarketbot.dto

/** 参数建议请求：基于某策略已结算 TAIL_DIFF 触发记录反推 */
data class TailDiffAdvisorRequest(
    val strategyId: Long = 0L,
    /** 最小样本量：低于此值仅展示分桶、不出具建议 */
    val minSamples: Int = 30
)

/** 分桶统计行（数值以字符串返回，避免精度问题） */
data class TailDiffAdvisorBucket(
    /** 分桶维度键，如 score=[70,75) / price=0.90_0.93 / sigma=1.5_2.0 / remaining=60_120 / tier=PREMIUM */
    val bucket: String = "",
    val sampleCount: Int = 0,
    val winCount: Int = 0,
    /** 胜率 0~1 */
    val winRate: String = "0",
    /** 总已实现盈亏 USDC */
    val totalPnl: String = "0",
    /** 单笔平均盈亏 USDC（EV 近似） */
    val avgPnl: String = "0"
)

/** 单条参数建议 */
data class TailDiffAdvisorRecommendation(
    /** 实体字段名（如 tailDiffMinEntryScore） */
    val param: String = "",
    /** i18n key（前端可读标签） */
    val labelKey: String = "",
    val currentValue: String = "",
    val suggestedValue: String = "",
    /** 是否与当前值不同（需调整） */
    val changed: Boolean = false,
    /** 该建议覆盖的样本量 */
    val sampleCount: Int = 0,
    /** 置信度：LOW / MEDIUM / HIGH */
    val confidence: String = "LOW",
    /** 依据说明（中文，日志/展示用） */
    val rationale: String = ""
)

/** 参数建议响应（仅推荐，不自动写入策略） */
data class TailDiffAdvisorResponse(
    val strategyId: Long = 0L,
    val totalSettled: Int = 0,
    val winCount: Int = 0,
    val winRate: String = "0",
    val totalPnl: String = "0",
    val avgPnl: String = "0",
    /** 样本是否足够出建议 */
    val sufficientSamples: Boolean = false,
    val minSamples: Int = 0,
    val scoreBuckets: List<TailDiffAdvisorBucket> = emptyList(),
    val priceBuckets: List<TailDiffAdvisorBucket> = emptyList(),
    val diffSigmaBuckets: List<TailDiffAdvisorBucket> = emptyList(),
    val remainingBuckets: List<TailDiffAdvisorBucket> = emptyList(),
    val tierBuckets: List<TailDiffAdvisorBucket> = emptyList(),
    val recommendations: List<TailDiffAdvisorRecommendation> = emptyList()
)
