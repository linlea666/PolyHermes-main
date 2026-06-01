package com.wrbug.polymarketbot.dto

/**
 * 加密价差策略创建请求
 * 金额与价格使用 String，后端转为 BigDecimal
 */
data class CryptoTailStrategyCreateRequest(
    val accountId: Long = 0L,
    val name: String? = null,
    val marketSlugPrefix: String = "",
    val intervalSeconds: Int = 300,
    val windowStartSeconds: Int = 0,
    val windowEndSeconds: Int = 0,
    val minPrice: String = "0",
    val maxPrice: String? = null,
    val amountMode: String = "RATIO",
    val amountValue: String = "0",
    /** 价差模式: NONE, FIXED, AUTO */
    val spreadMode: String = "NONE",
    /** 价差数值 */
    val spreadValue: String? = null,
    /** 价差方向: MIN=最小价差, MAX=最大价差 */
    val spreadDirection: String = "MIN",
    val enabled: Boolean = true,
    /** 障碍（终值概率）模式开关 */
    val barrierEnabled: Boolean = false,
    /** 进场胜率阈值 0~1 */
    val entryProb: String? = null,
    /** 扣费 EV 边际阈值 */
    val entryEdge: String? = null,
    /** 障碍模式最高买入限价 0~1 */
    val maxEntryPrice: String? = null,
    /** bestAsk 缺失成本缓冲 */
    val costBuffer: String? = null,
    /** 市场隐含概率下限 0~1，0=关 */
    val barrierMinMarketProb: String? = null,
    /** σ 校准系数 */
    val sigmaScale: String? = null,
    /** 当日已实现亏损熔断阈值 USDC，null/<=0=关 */
    val dailyLossLimitUsdc: String? = null,
    /** 最大并发未结算敞口笔数，null/<=0=关 */
    val maxConcurrentPositions: Int? = null,
    /** taker 手续费(基点bps) */
    val takerFeeBps: Int? = null,
    /** maker 返佣(基点bps) */
    val makerRebateBps: Int? = null,
    /** 单笔 gas 成本 USDC */
    val gasCostUsdc: String? = null,
    /** 进场订单类型: FAK / MAKER */
    val entryOrderType: String? = null,
    /** maker 挂单相对 bestBid 价格偏移(可负) */
    val makerPriceOffset: String? = null,
    /** maker 距结算多少秒未成交触发撤单决策 */
    val makerCancelBeforeSettleSeconds: Int? = null,
    /** maker 到期未成交是否回退 FAK */
    val makerFallbackTaker: Boolean? = null,
    /** 放量闸开关 */
    val calibrationGateEnabled: Boolean? = null,
    /** 校准期小额 USDC */
    val probeAmountUsdc: String? = null,
    /** 放量达标最少样本数 */
    val calibrationMinSamples: Int? = null,
    /** 放量达标最大校准误差 */
    val calibrationMaxError: String? = null,
    /** σ 估计方法: MAD/EWMA/GARMAN_KLASS */
    val sigmaMethod: String? = null,
    /** EWMA 衰减系数 λ */
    val ewmaLambda: String? = null
)

/**
 * 加密价差策略更新请求
 */
data class CryptoTailStrategyUpdateRequest(
    val strategyId: Long = 0L,
    val name: String? = null,
    val windowStartSeconds: Int? = null,
    val windowEndSeconds: Int? = null,
    val minPrice: String? = null,
    val maxPrice: String? = null,
    val amountMode: String? = null,
    val amountValue: String? = null,
    /** 价差模式: NONE, FIXED, AUTO */
    val spreadMode: String? = null,
    /** 价差数值 */
    val spreadValue: String? = null,
    /** 价差方向: MIN=最小价差, MAX=最大价差 */
    val spreadDirection: String? = null,
    val enabled: Boolean? = null,
    /** 障碍模式开关 */
    val barrierEnabled: Boolean? = null,
    val entryProb: String? = null,
    val entryEdge: String? = null,
    val maxEntryPrice: String? = null,
    val costBuffer: String? = null,
    val barrierMinMarketProb: String? = null,
    val sigmaScale: String? = null,
    val dailyLossLimitUsdc: String? = null,
    val maxConcurrentPositions: Int? = null,
    val takerFeeBps: Int? = null,
    val makerRebateBps: Int? = null,
    val gasCostUsdc: String? = null,
    val entryOrderType: String? = null,
    val makerPriceOffset: String? = null,
    val makerCancelBeforeSettleSeconds: Int? = null,
    val makerFallbackTaker: Boolean? = null,
    val calibrationGateEnabled: Boolean? = null,
    val probeAmountUsdc: String? = null,
    val calibrationMinSamples: Int? = null,
    val calibrationMaxError: String? = null,
    val sigmaMethod: String? = null,
    val ewmaLambda: String? = null
)

/**
 * 加密价差策略列表请求
 */
data class CryptoTailStrategyListRequest(
    val accountId: Long? = null,
    val enabled: Boolean? = null
)

/**
 * 加密价差策略 DTO（列表与详情）
 */
data class CryptoTailStrategyDto(
    val id: Long = 0L,
    val accountId: Long = 0L,
    val name: String? = null,
    val marketSlugPrefix: String = "",
    val marketTitle: String? = null,
    val intervalSeconds: Int = 0,
    val windowStartSeconds: Int = 0,
    val windowEndSeconds: Int = 0,
    val minPrice: String = "0",
    val maxPrice: String = "1",
    val amountMode: String = "RATIO",
    val amountValue: String = "0",
    /** 价差模式: NONE, FIXED, AUTO */
    val spreadMode: String = "NONE",
    /** 价差数值 */
    val spreadValue: String? = null,
    /** 价差方向: MIN=最小价差（价差>=配置值触发）, MAX=最大价差（价差<=配置值触发） */
    val spreadDirection: String = "MIN",
    val enabled: Boolean = true,
    /** 障碍（终值概率）模式开关 */
    val barrierEnabled: Boolean = false,
    val entryProb: String = "0.95",
    val entryEdge: String = "0.02",
    val maxEntryPrice: String = "0.99",
    val costBuffer: String = "0.02",
    val barrierMinMarketProb: String = "0",
    val sigmaScale: String = "1.2533",
    val dailyLossLimitUsdc: String? = null,
    val maxConcurrentPositions: Int? = null,
    val takerFeeBps: Int = 0,
    val makerRebateBps: Int = 0,
    val gasCostUsdc: String = "0",
    /** 进场订单类型: FAK吃单 / MAKER挂单 */
    val entryOrderType: String = "FAK",
    /** maker 挂单相对 bestBid 价格偏移 */
    val makerPriceOffset: String = "0",
    /** maker 距结算多少秒未成交触发撤单决策 */
    val makerCancelBeforeSettleSeconds: Int = 5,
    /** maker 到期未成交是否回退 FAK */
    val makerFallbackTaker: Boolean = false,
    /** 放量闸开关 */
    val calibrationGateEnabled: Boolean = false,
    /** 校准期小额 USDC */
    val probeAmountUsdc: String = "1",
    /** 放量达标最少样本数 */
    val calibrationMinSamples: Int = 30,
    /** 放量达标最大校准误差 */
    val calibrationMaxError: String = "0.10",
    /** σ 估计方法: MAD/EWMA/GARMAN_KLASS */
    val sigmaMethod: String = "MAD",
    /** EWMA 衰减系数 λ */
    val ewmaLambda: String = "0.94",
    val lastTriggerAt: Long? = null,
    /** 已实现总收益 USDC（已结算订单的 realizedPnl 之和） */
    val totalRealizedPnl: String? = null,
    /** 已结算笔数（用于胜率分母） */
    val settledCount: Long = 0L,
    /** 已结算中赢的笔数（用于胜率分子） */
    val winCount: Long = 0L,
    /** 胜率 0~1（已结算时 = winCount/settledCount，无结算为 null） */
    val winRate: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

/**
 * 加密价差策略列表响应
 */
data class CryptoTailStrategyListResponse(
    val list: List<CryptoTailStrategyDto> = emptyList()
)

/**
 * 加密价差策略删除请求
 */
data class CryptoTailStrategyDeleteRequest(
    val strategyId: Long = 0L
)

/**
 * 触发记录列表请求
 * @param startDate 开始日期（当天 00:00:00.000 的时间戳毫秒），为 null 表示不限制
 * @param endDate 结束日期（当天 23:59:59.999 的时间戳毫秒），为 null 表示不限制
 */
data class CryptoTailStrategyTriggerListRequest(
    val strategyId: Long = 0L,
    val page: Int = 1,
    val pageSize: Int = 20,
    val status: String? = null,
    val startDate: Long? = null,
    val endDate: Long? = null
)

/**
 * 触发记录 DTO
 */
data class CryptoTailStrategyTriggerDto(
    val id: Long = 0L,
    val strategyId: Long = 0L,
    val periodStartUnix: Long = 0L,
    val marketTitle: String? = null,
    val outcomeIndex: Int = 0,
    val triggerPrice: String = "0",
    val amountUsdc: String = "0",
    /** 实际成交份额(shares)，未成交为 null */
    val filledSize: String? = null,
    /** 实际成交金额 USDC，未成交为 null */
    val filledAmount: String? = null,
    /** 订单类型: FAK / GTC_POST_ONLY 等 */
    val orderType: String? = null,
    val orderId: String? = null,
    val status: String = "success",
    val failReason: String? = null,
    /** 是否已结算 */
    val resolved: Boolean = false,
    /** 已实现盈亏 USDC（结算后有值） */
    val realizedPnl: String? = null,
    /** 市场赢家 outcome 索引（结算后有值） */
    val winnerOutcomeIndex: Int? = null,
    val settledAt: Long? = null,
    val createdAt: Long = 0L
)

/**
 * 触发记录分页响应
 */
data class CryptoTailStrategyTriggerListResponse(
    val list: List<CryptoTailStrategyTriggerDto> = emptyList(),
    val total: Long = 0L
)

/**
 * 校准统计请求
 */
data class CryptoTailCalibrationRequest(
    val strategyId: Long = 0L
)

/**
 * 可靠性分箱单元：预测胜率 vs 实际胜率（5% 一箱）
 */
data class CryptoTailCalibrationBin(
    /** 分箱序号 0~19（floor(pWin*20)） */
    val bucket: Int = 0,
    /** 箱区间下界 0~1（bucket/20） */
    val rangeLow: String = "0",
    /** 箱区间上界 0~1 */
    val rangeHigh: String = "0",
    /** 样本数 */
    val sampleCount: Long = 0L,
    /** 该箱平均预测胜率（pWin 均值） */
    val predictedProb: String = "0",
    /** 该箱实际胜率（won 比例） */
    val actualWinRate: String = "0",
    /** 该箱净已实现盈亏合计 USDC */
    val netPnl: String = "0"
)

/**
 * 校准统计响应：整体校准质量 + 分箱 + 放量闸状态
 */
data class CryptoTailCalibrationResponse(
    val strategyId: Long = 0L,
    /** 已结算且成交的样本数 */
    val sampleCount: Long = 0L,
    /** 整体实际胜率 0~1 */
    val winRate: String? = null,
    /** 样本量加权校准误差（越小越准） */
    val calibrationError: String? = null,
    /** 净已实现盈亏合计 USDC */
    val totalNetPnl: String = "0",
    /** 每笔平均净已实现盈亏 USDC */
    val avgNetPnl: String? = null,
    /** 放量闸是否开启 */
    val gateEnabled: Boolean = false,
    /** 是否已达标放量 */
    val qualified: Boolean = false,
    /** 当前生效下注模式: PROBE 小额 / FULL 正常 */
    val scalingMode: String = "FULL",
    /** 校准期小额 USDC */
    val probeAmountUsdc: String = "0",
    /** 达标所需最少样本 */
    val minSamples: Int = 0,
    /** 达标允许的最大校准误差 */
    val maxError: String = "0",
    /** 达标/未达标说明 */
    val reason: String? = null,
    val bins: List<CryptoTailCalibrationBin> = emptyList()
)

/**
 * sigmaScale 自动校准推荐请求
 */
data class CryptoTailRecommendSigmaScaleRequest(
    val strategyId: Long = 0L
)

/**
 * sigmaScale 自动校准推荐响应
 * 基于已结算成交样本，最小化样本量加权校准误差搜索得出的建议系数；仅推荐，不自动套用。
 */
data class CryptoTailRecommendSigmaScaleResponse(
    val strategyId: Long = 0L,
    /** 可用样本数（z 与 oldScale 均存在的已结算样本） */
    val sampleCount: Long = 0L,
    /** 达标所需最少样本（复用策略 calibrationMinSamples） */
    val minSamples: Int = 0,
    /** 样本是否足够给出推荐 */
    val enough: Boolean = false,
    /** 当前 sigmaScale */
    val currentSigmaScale: String = "0",
    /** 推荐 sigmaScale（样本不足时为 null） */
    val recommendedSigmaScale: String? = null,
    /** 当前系数下的加权校准误差（样本不足时为 null） */
    val currentError: String? = null,
    /** 推荐系数下的加权校准误差（样本不足时为 null） */
    val recommendedError: String? = null,
    /** 当前 σ 估计方法（推荐仅对该方法的样本有效） */
    val sigmaMethod: String = "MAD",
    /** 说明 */
    val reason: String = ""
)

/**
 * 自动价差计算响应（按 30 根历史 K 线 + IQR 剔除后 × 0.7）
 */
data class CryptoTailAutoMinSpreadResponse(
    val minSpreadUp: String = "0",
    val minSpreadDown: String = "0"
)

/**
 * 5/15 分钟市场项（供前端选择市场）
 */
data class CryptoTailMarketOptionDto(
    val slug: String = "",
    val title: String = "",
    val intervalSeconds: Int = 0,
    val periodStartUnix: Long = 0L,
    val endDate: String? = null
)

/**
 * 收益曲线请求
 * @param strategyId 策略ID
 * @param startDate 开始时间（毫秒时间戳），null 表示不限制
 * @param endDate 结束时间（毫秒时间戳），null 表示不限制
 */
data class CryptoTailPnlCurveRequest(
    val strategyId: Long = 0L,
    val startDate: Long? = null,
    val endDate: Long? = null
)

/**
 * 收益曲线单点数据
 */
data class CryptoTailPnlCurvePoint(
    /** 时间点（毫秒时间戳，结算时间或创建时间） */
    val timestamp: Long = 0L,
    /** 累计收益 USDC */
    val cumulativePnl: String = "0",
    /** 当笔收益 USDC */
    val pointPnl: String = "0",
    /** 截至该点累计已结算笔数 */
    val settledCount: Long = 0L
)

/**
 * 收益曲线响应
 */
data class CryptoTailPnlCurveResponse(
    val strategyId: Long = 0L,
    val strategyName: String = "",
    /** 筛选范围内总已实现收益 USDC */
    val totalRealizedPnl: String = "0",
    val settledCount: Long = 0L,
    val winCount: Long = 0L,
    val winRate: String? = null,
    /** 最大回撤 USDC（正数表示回撤幅度） */
    val maxDrawdown: String? = null,
    val curveData: List<CryptoTailPnlCurvePoint> = emptyList()
)
