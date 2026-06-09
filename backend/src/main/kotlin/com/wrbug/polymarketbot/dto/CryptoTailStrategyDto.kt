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
    /** FAK 进场限价滑点上限（V53）：最终限价受 EV 安全最高价和 maxEntryPrice/bracketMaxEntryPrice 共同约束，默认 0.02 */
    val entryFakSlippage: String? = null,
    /** FAK 退出限价滑点（V66，全局）：FAK 卖出限价 = bestBid - exitFakSlippage，默认 0.02 */
    val exitFakSlippage: String? = null,
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
    val ewmaLambda: String? = null,
    /** 分数 Kelly 动态仓位开关 */
    val kellyEnabled: Boolean? = null,
    /** Kelly 分数 0~1 */
    val kellyFraction: String? = null,
    /** 是否允许同账户同 market/period/outcome 重复开仓 */
    val allowDuplicateMarketPosition: Boolean? = null,
    /** Strong Gap Boost（强价差放量，V60） */
    val enableStrongGapBoost: Boolean? = null,
    val strongGapBoostShadow: Boolean? = null,
    val strongGapMinPwin: String? = null,
    val strongGapMinSafeRatio: String? = null,
    val strongGapStakeMultiplier: String? = null,
    val ultraGapMinPwin: String? = null,
    val ultraGapMinSafeRatio: String? = null,
    val ultraGapStakeMultiplier: String? = null,
    val maxStrongGapStakeMultiplier: String? = null,
    val maxBoostedAmountUsdc: String? = null,
    val maxBoostedPeriodExposureUsdc: String? = null,
    val allowBoostWithKelly: Boolean? = null,
    /** 交易模式: 0=LEGACY_SPREAD, 1=BARRIER_HOLD, 2=BRACKET_DYNAMIC（V52） */
    val mode: Int? = null,
    val bracketEntryProb: String? = null,
    val bracketEntryEdge: String? = null,
    val bracketMaxEntryPrice: String? = null,
    val tp1Price: String? = null,
    val tp1Ratio: String? = null,
    val tp1HoldPwin: String? = null,
    val tp2Price: String? = null,
    val tp2Ratio: String? = null,
    val tp2HoldPwin: String? = null,
    val holdToSettlePwin: String? = null,
    val holdToSettleSeconds: Int? = null,
    val stopProb: String? = null,
    val stopPrice: String? = null,
    val forceExitBeforeSettleSeconds: Int? = null,
    /** 退出订单类型: FAK / MAKER */
    val exitOrderType: String? = null,
    val minSafeRatio: String? = null,
    val minSafeRatioUp: String? = null,
    val minSafeRatioDown: String? = null,
    val highPriceThreshold: String? = null,
    val highPriceMinPWin: String? = null,
    val highPriceMinSafeRatio: String? = null,
    val enableExitManager: Boolean? = null,
    val maxLossPct: String? = null,
    val exitPWin: String? = null,
    val exitSafeRatio: String? = null,
    val exitConfirmTicks: Int? = null,
    val takeProfitDelta1: String? = null,
    val takeProfitSellPct1: String? = null,
    val takeProfitBid2: String? = null,
    val takeProfitSellPct2: String? = null,
    /** 智能硬止损开关（V61）：HARD_STOP 命中后强势临近结算时豁免持有到结算 */
    val enableSmartHardStop: Boolean? = null,
    val emergencyExitOnModelFlip: Boolean? = null,
    val emergencyExitOnGapFlip: Boolean? = null,
    val exitPollIntervalMs: Int? = null,
    val enableWickFilter: Boolean? = null,
    val wickFilterMode: String? = null,
    val wickLookbackMinutes: Int? = null,
    val wickMinBodyRatio: String? = null,
    val wickRejectionRatio: String? = null,
    val wickMaWindow: Int? = null,
    val wickEntryBlockScore: Int? = null,
    val wickExitScore: Int? = null,
    val wickHoldProfitScore: Int? = null,
    val wickUseBinanceVolume: Boolean? = null,
    val wickVolumeSpikeRatio: String? = null,
    val wickMinTicksPerCandle: Int? = null,
    val wickMinRangeSigmaRatio: String? = null,
    val wickClosePositionUpMax: String? = null,
    val wickClosePositionDownMin: String? = null,
    val maxHoldTp1DelaySeconds: Int? = null,
    val holdTp1PeakDrawdown: String? = null,
    val maxEntrySpread: String? = null,
    val maxOrderbookAgeMs: Int? = null,
    val maxPriceAgeMs: Int? = null,
    val minRemainingSeconds: Int? = null,
    val maxRemainingSeconds: Int? = null,
    val minExitBidDepthUsdc: String? = null,
    val maxExitSpread: String? = null,
    val enableTrailingStop: Boolean? = null,
    val trailingStartDelta: String? = null,
    val trailingDrawdown: String? = null,
    val trailingSellPct: String? = null,
    val maxOrdersPerDay: Int? = null,
    val maxConsecutiveLosses: Int? = null,
    val pauseAfterLossMinutes: Int? = null,
    // ===== 尾盘价差模式（TAIL_DIFF, V62）；mode=3 时生效 =====
    val tailDiffDirection: Int? = null,
    val tailDiffWindowStartSeconds: Int? = null,
    val tailDiffWindowEndSeconds: Int? = null,
    val tailDiffMinRemainingSeconds: Int? = null,
    val tailDiffConfirmTicks: Int? = null,
    val tailDiffMinPrice: String? = null,
    val tailDiffMaxPrice: String? = null,
    val tailDiffHardMaxPrice: String? = null,
    val tailDiffMinModelProb: String? = null,
    val tailDiffMinEdge: String? = null,
    val tailDiffCostBuffer: String? = null,
    val tailDiffMinDiffSigma: String? = null,
    val tailDiffModelProbSource: String? = null,
    val tailDiffStatsMinSamples: Int? = null,
    val tailDiffStatsLookbackDays: Int? = null,
    val tailDiffStatsDataSource: String? = null,
    val tailDiffMaxSpread: String? = null,
    val tailDiffDepthMultiplier: String? = null,
    val tailDiffMaxOrderbookAgeMs: Int? = null,
    val tailDiffMaxPriceAgeMs: Int? = null,
    val tailDiffReverseVelocityWindowSeconds: Int? = null,
    val tailDiffMaxReverseVelocitySigma: String? = null,
    val tailDiffWeightDiff: Int? = null,
    val tailDiffWeightTime: Int? = null,
    val tailDiffWeightOddsUnderprice: Int? = null,
    val tailDiffWeightOddsLag: Int? = null,
    val tailDiffWeightHistory: Int? = null,
    val tailDiffWeightBook: Int? = null,
    val tailDiffWeightData: Int? = null,
    val tailDiffMinEntryScore: Int? = null,
    val tailDiffPremiumScore: Int? = null,
    val tailDiffTopScore: Int? = null,
    val tailDiffBaseAmount: String? = null,
    val tailDiffTierNormalMult: String? = null,
    val tailDiffTierPremiumMult: String? = null,
    val tailDiffTierTopMult: String? = null,
    val tailDiffMaxAmountPerOrder: String? = null,
    val tailDiffExitPresetNormalJson: String? = null,
    val tailDiffExitPresetPremiumJson: String? = null,
    val tailDiffExitPresetTopJson: String? = null,
    val tailDiffDailyLossLimitUsdc: String? = null,
    val tailDiffConsecLossPauseCount: Int? = null,
    val tailDiffConsecLossStopCount: Int? = null,
    val tailDiffEntrySegmentsJson: String? = null,
    // V72 评分锚点（请求侧可选，null 时走默认/保留原值）
    val tailDiffOddsLagMode: String? = null,
    val tailDiffOddsLagWindowSeconds: Int? = null,
    val tailDiffOddsLagStrongEdgeBypass: Boolean? = null,
    val tailDiffLagPriceMoveFullScaleSigma: String? = null,
    val tailDiffLagOddsMoveFullScale: String? = null,
    val tailDiffEdgeFullScale: String? = null,
    val tailDiffLagFullScale: String? = null,
    val tailDiffHistoryProbFloor: String? = null,
    val tailDiffHistoryProbCeil: String? = null,
    val tailDiffSigmaScoreMultiple: String? = null,
    val tailDiffEnableKellyCap: Boolean? = null,
    val tailDiffKellyFraction: String? = null,
    val tailDiffDepthFillRatio: String? = null,
    // ===== 快进快出模式 SCALP_FLIP（V77，均可选，null 走默认）=====
    val scalpEntryMinPrice: String? = null,
    val scalpEntryMaxPrice: String? = null,
    val scalpMaxFillPrice: String? = null,
    val scalpWindowStartSeconds: Int? = null,
    val scalpWindowEndSeconds: Int? = null,
    val scalpMinRemainingSeconds: Int? = null,
    val scalpMinExitBidDepthUsdc: String? = null,
    val scalpReversalGateEnabled: Boolean? = null,
    val scalpMinModelProb: String? = null,
    val scalpMinEdge: String? = null,
    val scalpStatsSource: String? = null,
    val scalpStatsLookbackDays: Int? = null,
    val scalpStatsMinSamples: Int? = null,
    val scalpRequireStats: Boolean? = null,
    val scalpMaxConcurrentSameDirection: Int? = null,
    val scalpHoldWinnerToSettle: Boolean? = null,
    val scalpTpPrice: String? = null,
    val scalpStopEnabled: Boolean? = null,
    val scalpStopOffset: String? = null,
    val scalpStopMinPrice: String? = null,
    val scalpMinOddsAfterEntry: String? = null,
    val scalpUnderlyingStopEnabled: Boolean? = null,
    val scalpUnderlyingStopSigma: String? = null,
    val scalpReverseVelocityStopEnabled: Boolean? = null,
    val scalpMaxReverseVelocitySigma: String? = null,
    val scalpReverseVelocityWindowSeconds: Int? = null,
    val scalpMinModelProbAfterEntry: String? = null,
    val scalpMaxDiffRetracePct: String? = null,
    val scalpCatastropheBidFloor: String? = null,
    val scalpCatastropheImmediate: Boolean? = null,
    val scalpCatastropheFloorRatio: String? = null,
    val scalpWsFreshnessSkipRestMs: Int? = null,
    val scalpEntryRequoteMax: Int? = null,
    val scalpRequireUnderlyingAgreement: Boolean? = null,
    val scalpEntryMinPwin: String? = null,
    val scalpSmartStopMinPwin: String? = null,
    val scalpSmartStopMinSafeRatio: String? = null,
    val scalpHardFloorRatio: String? = null,
    val scalpDailyLossLimitUsdc: String? = null,
    val scalpConsecLossPauseCount: Int? = null,
    val scalpConsecLossStopCount: Int? = null,
    val scalpGapGateEnabled: Boolean? = null,
    val scalpMinEntryDiffSigma: String? = null,
    val scalpMinEntryGapAbs: String? = null,
    val scalpGapGateRemainingLo: Int? = null,
    val scalpGapGateRemainingHi: Int? = null,
    val scalpEvLimitMode: String? = null,
    val scalpEvGuardMargin: String? = null,
    // ===== 尾盘动态止损 SCALP_FLIP（V91，均可选，null 走默认）=====
    val scalpLateStopEnabled: Boolean? = null,
    val scalpLateStopSeconds: Int? = null,
    val scalpLatePeakDrawdown: String? = null,
    val scalpLateBidFloor: String? = null,
    val scalpDisableWickGuardOnLateStop: Boolean? = null,
    val scalpLateStopRequireWeakModel: Boolean? = null,
    val scalpLateFastPollSeconds: Int? = null,
    val scalpLateFastPollMs: Int? = null,
    val scalpEmergencyRetryCount: Int? = null,
    val scalpEmergencyRetryIntervalMs: Int? = null,
    val scalpLateIgnoreWorstPriceSeconds: Int? = null,
    val scalpLateScaleOutSeconds: Int? = null,
    val scalpLateScaleOutRatio: String? = null
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
    /** FAK 进场限价滑点（V53） */
    val entryFakSlippage: String? = null,
    /** FAK 退出限价滑点（V66，全局） */
    val exitFakSlippage: String? = null,
    val makerPriceOffset: String? = null,
    val makerCancelBeforeSettleSeconds: Int? = null,
    val makerFallbackTaker: Boolean? = null,
    val calibrationGateEnabled: Boolean? = null,
    val probeAmountUsdc: String? = null,
    val calibrationMinSamples: Int? = null,
    val calibrationMaxError: String? = null,
    val sigmaMethod: String? = null,
    val ewmaLambda: String? = null,
    val kellyEnabled: Boolean? = null,
    val kellyFraction: String? = null,
    val allowDuplicateMarketPosition: Boolean? = null,
    /** Strong Gap Boost（强价差放量，V60） */
    val enableStrongGapBoost: Boolean? = null,
    val strongGapBoostShadow: Boolean? = null,
    val strongGapMinPwin: String? = null,
    val strongGapMinSafeRatio: String? = null,
    val strongGapStakeMultiplier: String? = null,
    val ultraGapMinPwin: String? = null,
    val ultraGapMinSafeRatio: String? = null,
    val ultraGapStakeMultiplier: String? = null,
    val maxStrongGapStakeMultiplier: String? = null,
    val maxBoostedAmountUsdc: String? = null,
    val maxBoostedPeriodExposureUsdc: String? = null,
    val allowBoostWithKelly: Boolean? = null,
    /** 交易模式: 0/1/2（V52） */
    val mode: Int? = null,
    val bracketEntryProb: String? = null,
    val bracketEntryEdge: String? = null,
    val bracketMaxEntryPrice: String? = null,
    val tp1Price: String? = null,
    val tp1Ratio: String? = null,
    val tp1HoldPwin: String? = null,
    val tp2Price: String? = null,
    val tp2Ratio: String? = null,
    val tp2HoldPwin: String? = null,
    val holdToSettlePwin: String? = null,
    val holdToSettleSeconds: Int? = null,
    val stopProb: String? = null,
    val stopPrice: String? = null,
    val forceExitBeforeSettleSeconds: Int? = null,
    val exitOrderType: String? = null,
    val minSafeRatio: String? = null,
    val minSafeRatioUp: String? = null,
    val minSafeRatioDown: String? = null,
    val highPriceThreshold: String? = null,
    val highPriceMinPWin: String? = null,
    val highPriceMinSafeRatio: String? = null,
    val enableExitManager: Boolean? = null,
    val maxLossPct: String? = null,
    val exitPWin: String? = null,
    val exitSafeRatio: String? = null,
    val exitConfirmTicks: Int? = null,
    val takeProfitDelta1: String? = null,
    val takeProfitSellPct1: String? = null,
    val takeProfitBid2: String? = null,
    val takeProfitSellPct2: String? = null,
    /** 智能硬止损开关（V61） */
    val enableSmartHardStop: Boolean? = null,
    val emergencyExitOnModelFlip: Boolean? = null,
    val emergencyExitOnGapFlip: Boolean? = null,
    val exitPollIntervalMs: Int? = null,
    val enableWickFilter: Boolean? = null,
    val wickFilterMode: String? = null,
    val wickLookbackMinutes: Int? = null,
    val wickMinBodyRatio: String? = null,
    val wickRejectionRatio: String? = null,
    val wickMaWindow: Int? = null,
    val wickEntryBlockScore: Int? = null,
    val wickExitScore: Int? = null,
    val wickHoldProfitScore: Int? = null,
    val wickUseBinanceVolume: Boolean? = null,
    val wickVolumeSpikeRatio: String? = null,
    val wickMinTicksPerCandle: Int? = null,
    val wickMinRangeSigmaRatio: String? = null,
    val wickClosePositionUpMax: String? = null,
    val wickClosePositionDownMin: String? = null,
    val maxHoldTp1DelaySeconds: Int? = null,
    val holdTp1PeakDrawdown: String? = null,
    val maxEntrySpread: String? = null,
    val maxOrderbookAgeMs: Int? = null,
    val maxPriceAgeMs: Int? = null,
    val minRemainingSeconds: Int? = null,
    val maxRemainingSeconds: Int? = null,
    val minExitBidDepthUsdc: String? = null,
    val maxExitSpread: String? = null,
    val enableTrailingStop: Boolean? = null,
    val trailingStartDelta: String? = null,
    val trailingDrawdown: String? = null,
    val trailingSellPct: String? = null,
    val maxOrdersPerDay: Int? = null,
    val maxConsecutiveLosses: Int? = null,
    val pauseAfterLossMinutes: Int? = null,
    // ===== 尾盘价差模式（TAIL_DIFF, V62）；mode=3 时生效 =====
    val tailDiffDirection: Int? = null,
    val tailDiffWindowStartSeconds: Int? = null,
    val tailDiffWindowEndSeconds: Int? = null,
    val tailDiffMinRemainingSeconds: Int? = null,
    val tailDiffConfirmTicks: Int? = null,
    val tailDiffMinPrice: String? = null,
    val tailDiffMaxPrice: String? = null,
    val tailDiffHardMaxPrice: String? = null,
    val tailDiffMinModelProb: String? = null,
    val tailDiffMinEdge: String? = null,
    val tailDiffCostBuffer: String? = null,
    val tailDiffMinDiffSigma: String? = null,
    val tailDiffModelProbSource: String? = null,
    val tailDiffStatsMinSamples: Int? = null,
    val tailDiffStatsLookbackDays: Int? = null,
    val tailDiffStatsDataSource: String? = null,
    val tailDiffMaxSpread: String? = null,
    val tailDiffDepthMultiplier: String? = null,
    val tailDiffMaxOrderbookAgeMs: Int? = null,
    val tailDiffMaxPriceAgeMs: Int? = null,
    val tailDiffReverseVelocityWindowSeconds: Int? = null,
    val tailDiffMaxReverseVelocitySigma: String? = null,
    val tailDiffWeightDiff: Int? = null,
    val tailDiffWeightTime: Int? = null,
    val tailDiffWeightOddsUnderprice: Int? = null,
    val tailDiffWeightOddsLag: Int? = null,
    val tailDiffWeightHistory: Int? = null,
    val tailDiffWeightBook: Int? = null,
    val tailDiffWeightData: Int? = null,
    val tailDiffMinEntryScore: Int? = null,
    val tailDiffPremiumScore: Int? = null,
    val tailDiffTopScore: Int? = null,
    val tailDiffBaseAmount: String? = null,
    val tailDiffTierNormalMult: String? = null,
    val tailDiffTierPremiumMult: String? = null,
    val tailDiffTierTopMult: String? = null,
    val tailDiffMaxAmountPerOrder: String? = null,
    val tailDiffExitPresetNormalJson: String? = null,
    val tailDiffExitPresetPremiumJson: String? = null,
    val tailDiffExitPresetTopJson: String? = null,
    val tailDiffDailyLossLimitUsdc: String? = null,
    val tailDiffConsecLossPauseCount: Int? = null,
    val tailDiffConsecLossStopCount: Int? = null,
    val tailDiffEntrySegmentsJson: String? = null,
    // V72 评分锚点（请求侧可选，null 时保留原值）
    val tailDiffOddsLagMode: String? = null,
    val tailDiffOddsLagWindowSeconds: Int? = null,
    val tailDiffOddsLagStrongEdgeBypass: Boolean? = null,
    val tailDiffLagPriceMoveFullScaleSigma: String? = null,
    val tailDiffLagOddsMoveFullScale: String? = null,
    val tailDiffEdgeFullScale: String? = null,
    val tailDiffLagFullScale: String? = null,
    val tailDiffHistoryProbFloor: String? = null,
    val tailDiffHistoryProbCeil: String? = null,
    val tailDiffSigmaScoreMultiple: String? = null,
    val tailDiffEnableKellyCap: Boolean? = null,
    val tailDiffKellyFraction: String? = null,
    val tailDiffDepthFillRatio: String? = null,
    // ===== 快进快出模式 SCALP_FLIP（V77，均可选，null 时保留原值）=====
    val scalpEntryMinPrice: String? = null,
    val scalpEntryMaxPrice: String? = null,
    val scalpMaxFillPrice: String? = null,
    val scalpWindowStartSeconds: Int? = null,
    val scalpWindowEndSeconds: Int? = null,
    val scalpMinRemainingSeconds: Int? = null,
    val scalpMinExitBidDepthUsdc: String? = null,
    val scalpReversalGateEnabled: Boolean? = null,
    val scalpMinModelProb: String? = null,
    val scalpMinEdge: String? = null,
    val scalpStatsSource: String? = null,
    val scalpStatsLookbackDays: Int? = null,
    val scalpStatsMinSamples: Int? = null,
    val scalpRequireStats: Boolean? = null,
    val scalpMaxConcurrentSameDirection: Int? = null,
    val scalpHoldWinnerToSettle: Boolean? = null,
    val scalpTpPrice: String? = null,
    val scalpStopEnabled: Boolean? = null,
    val scalpStopOffset: String? = null,
    val scalpStopMinPrice: String? = null,
    val scalpMinOddsAfterEntry: String? = null,
    val scalpUnderlyingStopEnabled: Boolean? = null,
    val scalpUnderlyingStopSigma: String? = null,
    val scalpReverseVelocityStopEnabled: Boolean? = null,
    val scalpMaxReverseVelocitySigma: String? = null,
    val scalpReverseVelocityWindowSeconds: Int? = null,
    val scalpMinModelProbAfterEntry: String? = null,
    val scalpMaxDiffRetracePct: String? = null,
    val scalpCatastropheBidFloor: String? = null,
    val scalpCatastropheImmediate: Boolean? = null,
    val scalpCatastropheFloorRatio: String? = null,
    val scalpWsFreshnessSkipRestMs: Int? = null,
    val scalpEntryRequoteMax: Int? = null,
    val scalpRequireUnderlyingAgreement: Boolean? = null,
    val scalpEntryMinPwin: String? = null,
    val scalpSmartStopMinPwin: String? = null,
    val scalpSmartStopMinSafeRatio: String? = null,
    val scalpHardFloorRatio: String? = null,
    val scalpDailyLossLimitUsdc: String? = null,
    val scalpConsecLossPauseCount: Int? = null,
    val scalpConsecLossStopCount: Int? = null,
    val scalpGapGateEnabled: Boolean? = null,
    val scalpMinEntryDiffSigma: String? = null,
    val scalpMinEntryGapAbs: String? = null,
    val scalpGapGateRemainingLo: Int? = null,
    val scalpGapGateRemainingHi: Int? = null,
    val scalpEvLimitMode: String? = null,
    val scalpEvGuardMargin: String? = null,
    // ===== 尾盘动态止损 SCALP_FLIP（V91，均可选，null 走默认）=====
    val scalpLateStopEnabled: Boolean? = null,
    val scalpLateStopSeconds: Int? = null,
    val scalpLatePeakDrawdown: String? = null,
    val scalpLateBidFloor: String? = null,
    val scalpDisableWickGuardOnLateStop: Boolean? = null,
    val scalpLateStopRequireWeakModel: Boolean? = null,
    val scalpLateFastPollSeconds: Int? = null,
    val scalpLateFastPollMs: Int? = null,
    val scalpEmergencyRetryCount: Int? = null,
    val scalpEmergencyRetryIntervalMs: Int? = null,
    val scalpLateIgnoreWorstPriceSeconds: Int? = null,
    val scalpLateScaleOutSeconds: Int? = null,
    val scalpLateScaleOutRatio: String? = null
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
    val entryProb: String = "0.55",
    val entryEdge: String = "0.02",
    val maxEntryPrice: String = "0.99",
    val costBuffer: String = "0.02",
    val barrierMinMarketProb: String = "0",
    val sigmaScale: String = "1.0",
    val dailyLossLimitUsdc: String? = null,
    val maxConcurrentPositions: Int? = null,
    val takerFeeBps: Int = 0,
    val makerRebateBps: Int = 0,
    val gasCostUsdc: String = "0",
    /** 进场订单类型: FAK吃单 / MAKER挂单 */
    val entryOrderType: String = "FAK",
    /** FAK 进场限价滑点上限（V53）：最终限价受 EV 安全最高价和 maxEntryPrice/bracketMaxEntryPrice 共同约束 */
    val entryFakSlippage: String = "0.02",
    /** FAK 退出限价滑点（V66，全局） */
    val exitFakSlippage: String = "0.02",
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
    /** σ 估计方法: GARMAN_KLASS/MAD/EWMA */
    val sigmaMethod: String = "GARMAN_KLASS",
    /** EWMA 衰减系数 λ */
    val ewmaLambda: String = "0.94",
    /** 分数 Kelly 动态仓位开关 */
    val kellyEnabled: Boolean = false,
    /** Kelly 分数 0~1 */
    val kellyFraction: String = "0.25",
    val allowDuplicateMarketPosition: Boolean = false,
    /** Strong Gap Boost（强价差放量，V60） */
    val enableStrongGapBoost: Boolean = false,
    val strongGapBoostShadow: Boolean = true,
    val strongGapMinPwin: String = "0.90",
    val strongGapMinSafeRatio: String = "1.50",
    val strongGapStakeMultiplier: String = "1.50",
    val ultraGapMinPwin: String = "0.95",
    val ultraGapMinSafeRatio: String = "2.00",
    val ultraGapStakeMultiplier: String = "2.00",
    val maxStrongGapStakeMultiplier: String = "2.00",
    val maxBoostedAmountUsdc: String? = null,
    val maxBoostedPeriodExposureUsdc: String? = null,
    val allowBoostWithKelly: Boolean = false,
    /** 交易模式: 0=LEGACY_SPREAD, 1=BARRIER_HOLD, 2=BRACKET_DYNAMIC */
    val mode: Int = 0,
    val bracketEntryProb: String = "0.80",
    val bracketEntryEdge: String = "0.04",
    val bracketMaxEntryPrice: String = "0.90",
    val tp1Price: String = "0.90",
    val tp1Ratio: String = "0.50",
    val tp1HoldPwin: String = "0.95",
    val tp2Price: String = "0.95",
    val tp2Ratio: String = "1.00",
    val tp2HoldPwin: String = "0.99",
    val holdToSettlePwin: String = "0.97",
    val holdToSettleSeconds: Int = 30,
    val stopProb: String = "0.55",
    val stopPrice: String = "0.70",
    val forceExitBeforeSettleSeconds: Int = 15,
    /** 退出订单类型: FAK / MAKER */
    val exitOrderType: String = "FAK",
    val minSafeRatio: String = "1.20",
    val minSafeRatioUp: String = "1.50",
    val minSafeRatioDown: String = "1.20",
    val highPriceThreshold: String = "0.90",
    val highPriceMinPWin: String = "0.97",
    val highPriceMinSafeRatio: String = "2.50",
    val enableExitManager: Boolean = true,
    val maxLossPct: String = "0.20",
    val exitPWin: String = "0.70",
    val exitSafeRatio: String = "0.80",
    val exitConfirmTicks: Int = 2,
    val takeProfitDelta1: String = "0.08",
    val takeProfitSellPct1: String = "0.50",
    val takeProfitBid2: String = "0.93",
    val takeProfitSellPct2: String = "0.80",
    /** 智能硬止损开关（V61），默认 false */
    val enableSmartHardStop: Boolean = false,
    val emergencyExitOnModelFlip: Boolean = true,
    val emergencyExitOnGapFlip: Boolean = true,
    val exitPollIntervalMs: Int = 3000,
    val enableWickFilter: Boolean = true,
    val wickFilterMode: String = "SHADOW",
    val wickLookbackMinutes: Int = 2,
    val wickMinBodyRatio: String = "0.20",
    val wickRejectionRatio: String = "0.55",
    val wickMaWindow: Int = 3,
    val wickEntryBlockScore: Int = 70,
    val wickExitScore: Int = 75,
    val wickHoldProfitScore: Int = 65,
    val wickUseBinanceVolume: Boolean = false,
    val wickVolumeSpikeRatio: String = "1.50",
    val wickMinTicksPerCandle: Int = 5,
    val wickMinRangeSigmaRatio: String = "0.25",
    val wickClosePositionUpMax: String = "0.35",
    val wickClosePositionDownMin: String = "0.65",
    val maxHoldTp1DelaySeconds: Int = 45,
    val holdTp1PeakDrawdown: String = "0.03",
    val maxEntrySpread: String = "0.03",
    val maxOrderbookAgeMs: Int = 3000,
    val maxPriceAgeMs: Int = 3000,
    val minRemainingSeconds: Int = 90,
    val maxRemainingSeconds: Int = 420,
    val minExitBidDepthUsdc: String = "2.00",
    val maxExitSpread: String = "0.05",
    val enableTrailingStop: Boolean = true,
    val trailingStartDelta: String = "0.08",
    val trailingDrawdown: String = "0.06",
    val trailingSellPct: String = "1.00",
    val maxOrdersPerDay: Int? = null,
    val maxConsecutiveLosses: Int? = null,
    val pauseAfterLossMinutes: Int = 0,
    val lastTriggerAt: Long? = null,
    /** 已实现总收益 USDC（已结算订单的 realizedPnl 之和） */
    val totalRealizedPnl: String? = null,
    /** 已结算笔数（用于胜率分母） */
    val settledCount: Long = 0L,
    /** 已结算中赢的笔数（用于胜率分子） */
    val winCount: Long = 0L,
    /** 胜率 0~1（已结算时 = winCount/settledCount，无结算为 null） */
    val winRate: String? = null,
    // ===== 尾盘价差模式（TAIL_DIFF, V62）=====
    val tailDiffDirection: Int = 0,
    val tailDiffWindowStartSeconds: Int = 150,
    val tailDiffWindowEndSeconds: Int = 60,
    val tailDiffMinRemainingSeconds: Int = 50,
    val tailDiffConfirmTicks: Int = 2,
    val tailDiffMinPrice: String = "0.88",
    val tailDiffMaxPrice: String = "0.93",
    val tailDiffHardMaxPrice: String = "0.94",
    val tailDiffMinModelProb: String = "0.95",
    val tailDiffMinEdge: String = "0.025",
    val tailDiffCostBuffer: String = "0.01",
    val tailDiffMinDiffSigma: String = "1.8",
    val tailDiffModelProbSource: String = "HYBRID",
    val tailDiffStatsMinSamples: Int = 50,
    val tailDiffStatsLookbackDays: Int = 180,
    val tailDiffStatsDataSource: String = "BINANCE",
    val tailDiffMaxSpread: String = "0.02",
    val tailDiffDepthMultiplier: String = "3.0",
    val tailDiffMaxOrderbookAgeMs: Int = 2000,
    val tailDiffMaxPriceAgeMs: Int = 2000,
    val tailDiffReverseVelocityWindowSeconds: Int = 10,
    val tailDiffMaxReverseVelocitySigma: String = "0.30",
    val tailDiffWeightDiff: Int = 25,
    val tailDiffWeightTime: Int = 15,
    val tailDiffWeightOddsUnderprice: Int = 20,
    val tailDiffWeightOddsLag: Int = 10,
    val tailDiffWeightHistory: Int = 15,
    val tailDiffWeightBook: Int = 10,
    val tailDiffWeightData: Int = 5,
    val tailDiffMinEntryScore: Int = 70,
    val tailDiffPremiumScore: Int = 80,
    val tailDiffTopScore: Int = 90,
    val tailDiffBaseAmount: String = "1",
    val tailDiffTierNormalMult: String = "1.0",
    val tailDiffTierPremiumMult: String = "1.5",
    val tailDiffTierTopMult: String = "2.0",
    val tailDiffMaxAmountPerOrder: String = "5",
    val tailDiffExitPresetNormalJson: String? = null,
    val tailDiffExitPresetPremiumJson: String? = null,
    val tailDiffExitPresetTopJson: String? = null,
    val tailDiffDailyLossLimitUsdc: String? = null,
    val tailDiffConsecLossPauseCount: Int = 2,
    val tailDiffConsecLossStopCount: Int = 3,
    val tailDiffEntrySegmentsJson: String? = null,
    // V72 评分锚点（响应回显，默认值 = 实体默认）
    val tailDiffOddsLagMode: String = "STATIC",
    val tailDiffOddsLagWindowSeconds: Int = 5,
    val tailDiffOddsLagStrongEdgeBypass: Boolean = false,
    val tailDiffLagPriceMoveFullScaleSigma: String = "0.5",
    val tailDiffLagOddsMoveFullScale: String = "0.05",
    val tailDiffEdgeFullScale: String = "0.10",
    val tailDiffLagFullScale: String = "0.15",
    val tailDiffHistoryProbFloor: String = "0.90",
    val tailDiffHistoryProbCeil: String = "1.00",
    val tailDiffSigmaScoreMultiple: String = "1.8",
    val tailDiffEnableKellyCap: Boolean = false,
    val tailDiffKellyFraction: String = "0.10",
    val tailDiffDepthFillRatio: String = "0",
    // ===== 快进快出模式 SCALP_FLIP（V77，响应回显，默认值 = 实体默认）=====
    val scalpEntryMinPrice: String = "0.96",
    val scalpEntryMaxPrice: String = "0.97",
    val scalpMaxFillPrice: String = "0.975",
    val scalpWindowStartSeconds: Int = 0,
    val scalpWindowEndSeconds: Int = 0,
    val scalpMinRemainingSeconds: Int = 30,
    val scalpMinExitBidDepthUsdc: String? = null,
    val scalpReversalGateEnabled: Boolean = true,
    val scalpMinModelProb: String = "0.95",
    val scalpMinEdge: String = "0",
    val scalpStatsSource: String = "HYBRID",
    val scalpStatsLookbackDays: Int = 180,
    val scalpStatsMinSamples: Int = 30,
    val scalpRequireStats: Boolean = false,
    val scalpMaxConcurrentSameDirection: Int? = null,
    val scalpHoldWinnerToSettle: Boolean = true,
    val scalpTpPrice: String = "0.99",
    val scalpStopEnabled: Boolean = true,
    val scalpStopOffset: String = "0.05",
    val scalpStopMinPrice: String = "0.90",
    val scalpMinOddsAfterEntry: String = "0.93",
    val scalpUnderlyingStopEnabled: Boolean = true,
    val scalpUnderlyingStopSigma: String = "0.30",
    val scalpReverseVelocityStopEnabled: Boolean = true,
    val scalpMaxReverseVelocitySigma: String = "0.40",
    val scalpReverseVelocityWindowSeconds: Int = 10,
    val scalpMinModelProbAfterEntry: String = "0",
    val scalpMaxDiffRetracePct: String = "0",
    val scalpCatastropheBidFloor: String = "0.88",
    val scalpCatastropheImmediate: Boolean = true,
    val scalpCatastropheFloorRatio: String = "0.85",
    val scalpWsFreshnessSkipRestMs: Int = 500,
    val scalpEntryRequoteMax: Int = 2,
    val scalpRequireUnderlyingAgreement: Boolean = true,
    val scalpEntryMinPwin: String = "0.90",
    val scalpSmartStopMinPwin: String = "0.70",
    val scalpSmartStopMinSafeRatio: String = "1.30",
    val scalpHardFloorRatio: String = "0.50",
    val scalpDailyLossLimitUsdc: String? = null,
    val scalpConsecLossPauseCount: Int = 2,
    val scalpConsecLossStopCount: Int = 3,
    val scalpGapGateEnabled: Boolean = false,
    val scalpMinEntryDiffSigma: String = "0",
    val scalpMinEntryGapAbs: String = "0",
    val scalpGapGateRemainingLo: Int = 0,
    val scalpGapGateRemainingHi: Int = 0,
    val scalpEvLimitMode: String = "CLAMP",
    val scalpEvGuardMargin: String = "0.10",
    // ===== 尾盘动态止损 SCALP_FLIP（V91）=====
    val scalpLateStopEnabled: Boolean = false,
    val scalpLateStopSeconds: Int = 15,
    val scalpLatePeakDrawdown: String = "0.18",
    val scalpLateBidFloor: String = "0.70",
    val scalpDisableWickGuardOnLateStop: Boolean = true,
    val scalpLateStopRequireWeakModel: Boolean = false,
    val scalpLateFastPollSeconds: Int = 0,
    val scalpLateFastPollMs: Int = 300,
    val scalpEmergencyRetryCount: Int = 0,
    val scalpEmergencyRetryIntervalMs: Int = 150,
    val scalpLateIgnoreWorstPriceSeconds: Int = 0,
    val scalpLateScaleOutSeconds: Int = 0,
    val scalpLateScaleOutRatio: String = "0",
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
    val createdAt: Long = 0L,
    /** 触发时模式: 0=LEGACY_SPREAD, 1=BARRIER_HOLD, 2=BRACKET_DYNAMIC（V52） */
    val mode: Int = 0,
    /** 阶梯模式剩余仓位（shares），其他模式为 null */
    val remainingSize: String? = null,
    /** 阶梯模式仓位状态: NONE/OPEN/PARTIAL_EXIT/FULLY_EXITED/HELD_TO_SETTLE */
    val exitStatus: String = "NONE"
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

/**
 * 加密价差盈亏统计概览请求
 * @param mode 交易模式过滤（4=SCALP_FLIP 等），null=全部
 * @param accountId 账户过滤，null=全部
 * @param strategyId 单策略过滤，null=全部
 * @param startDate/endDate 结算时间区间（毫秒），null=不限
 * @param granularity 分桶粒度：day / week / month
 */
data class CryptoTailStatsRequest(
    val mode: Int? = null,
    val accountId: Long? = null,
    val strategyId: Long? = null,
    val startDate: Long? = null,
    val endDate: Long? = null,
    val granularity: String = "day"
)

/** 概览六指标（与全局统计卡片口径对齐；仅统计已结算触发，totalOrders 即已结算笔数；winRate 为 0~100 百分比字符串） */
data class CryptoTailStatsSummary(
    val totalOrders: Long = 0L,
    val totalPnl: String = "0",
    val winRate: String = "0",
    val avgPnl: String = "0",
    val maxProfit: String = "0",
    val maxLoss: String = "0"
)

/** 时间分桶（日/周/月）总盈亏 */
data class CryptoTailStatsBucket(
    val label: String = "",
    val startMs: Long = 0L,
    val pnl: String = "0",
    val settledCount: Long = 0L,
    val winCount: Long = 0L
)

/** 按市场（marketSlugPrefix）跨策略汇总盈亏；winRate 为 0~100 百分比字符串 */
data class CryptoTailStatsMarket(
    val marketSlugPrefix: String = "",
    val marketTitle: String = "",
    val totalPnl: String = "0",
    val settledCount: Long = 0L,
    val winRate: String = "0",
    val avgPnl: String = "0"
)

/**
 * 逐笔结算明细（WS4 看板对账）：暴露 exitStatus / settleSource / 实现盈亏，供前端核对幽灵盈利类不一致。
 * settleSource 含 RECONCILED = WS1 链上对账修正生效；won=null 表示按全退口径结算（非满仓判赢）。
 */
data class CryptoTailStatsTrade(
    val triggerId: Long = 0L,
    val strategyId: Long = 0L,
    val marketSlugPrefix: String = "",
    val marketTitle: String = "",
    val mode: Int = 0,
    val outcomeIndex: Int? = null,
    val settledAt: Long? = null,
    val realizedPnl: String = "0",
    val exitStatus: String? = null,
    val settleSource: String? = null,
    val won: Boolean? = null,
    val remainingSize: String? = null
)

/**
 * 一致性告警（WS4）：severity = info/warning；type 标识类别，供前端按类着色与 i18n。
 *  - RECONCILED：WS1 对账修正已生效（链上漏记卖出被纠正，防幽灵盈利）。
 *  - HELD_TO_SETTLE_WIN：持有到结算判赢，建议核对链上是否有漏记卖出（潜在幽灵盈利）。
 *  - PNL_SNAPSHOT_MISMATCH：trigger 与快照实现盈亏偏差超阈值。
 */
data class CryptoTailStatsAlert(
    val triggerId: Long = 0L,
    val strategyId: Long = 0L,
    val type: String = "",
    val severity: String = "warning",
    val message: String = ""
)

/** 加密价差盈亏统计概览响应 */
data class CryptoTailStatsResponse(
    val summary: CryptoTailStatsSummary = CryptoTailStatsSummary(),
    val buckets: List<CryptoTailStatsBucket> = emptyList(),
    val byMarket: List<CryptoTailStatsMarket> = emptyList(),
    val trades: List<CryptoTailStatsTrade> = emptyList(),
    val alerts: List<CryptoTailStatsAlert> = emptyList()
)

/**
 * 阶梯模式退出明细查询请求（按 trigger 维度展开）
 */
data class CryptoTailStrategyExitListRequest(
    val triggerId: Long = 0L
)

/**
 * 单条退出明细
 */
data class CryptoTailStrategyExitDto(
    val id: Long = 0L,
    val triggerId: Long = 0L,
    val strategyId: Long = 0L,
    /** 退出类型: TP1/TP2/STOP/FORCE/SETTLE */
    val exitKind: String = "",
    val targetSize: String = "0",
    val filledSize: String? = null,
    val filledAmount: String? = null,
    val exitPrice: String? = null,
    val orderId: String? = null,
    val orderType: String? = null,
    /** 状态: pending/success/failed/cancelled/unfilled */
    val status: String = "pending",
    val pwinAtDecision: String? = null,
    val bestBidAtDecision: String? = null,
    val remainingSeconds: Int? = null,
    val decisionReason: String? = null,
    val failReason: String? = null,
    val createdAt: Long = 0L,
    val settledAt: Long? = null
)

/**
 * 阶梯模式退出明细查询响应
 */
data class CryptoTailStrategyExitListResponse(
    val triggerId: Long = 0L,
    val list: List<CryptoTailStrategyExitDto> = emptyList()
)
