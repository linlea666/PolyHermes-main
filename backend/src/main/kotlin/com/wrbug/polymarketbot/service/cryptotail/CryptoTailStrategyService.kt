package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import com.wrbug.polymarketbot.entity.CryptoTailStrategyTrigger
import com.wrbug.polymarketbot.entity.CryptoTailTradeSnapshot
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.enums.SpreadMode
import com.wrbug.polymarketbot.enums.SpreadDirection
import com.wrbug.polymarketbot.repository.CryptoTailDecisionEventRepository
import com.wrbug.polymarketbot.repository.CryptoTailStrategyExitRepository
import com.wrbug.polymarketbot.repository.CryptoTailStrategyRepository
import com.wrbug.polymarketbot.repository.CryptoTailStrategyTriggerRepository
import com.wrbug.polymarketbot.repository.CryptoTailTradeSnapshotRepository
import com.wrbug.polymarketbot.event.CryptoTailStrategyChangedEvent
import com.wrbug.polymarketbot.util.gt
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Service
class CryptoTailStrategyService(
    private val strategyRepository: CryptoTailStrategyRepository,
    private val triggerRepository: CryptoTailStrategyTriggerRepository,
    private val decisionEventRepository: CryptoTailDecisionEventRepository,
    private val tradeSnapshotRepository: CryptoTailTradeSnapshotRepository,
    private val calibrationService: CryptoTailCalibrationService,
    private val eventPublisher: ApplicationEventPublisher,
    private val exitRepository: CryptoTailStrategyExitRepository,
    private val entrySegmentResolver: com.wrbug.polymarketbot.service.cryptotail.taildiff.TailDiffEntrySegmentResolver,
    private val exitPresetResolver: com.wrbug.polymarketbot.service.cryptotail.taildiff.TailDiffExitPresetResolver
) {

    private val logger = LoggerFactory.getLogger(CryptoTailStrategyService::class.java)

    private val maxWindowByInterval = mapOf(300 to 300, 900 to 900)

    @Transactional
    fun create(request: CryptoTailStrategyCreateRequest): Result<CryptoTailStrategyDto> {
        return try {
            if (request.accountId <= 0) {
                return Result.failure(IllegalArgumentException(ErrorCode.PARAM_ACCOUNT_ID_INVALID.messageKey))
            }
            if (request.marketSlugPrefix.isBlank()) {
                return Result.failure(IllegalArgumentException(ErrorCode.PARAM_ERROR.messageKey))
            }
            val interval = request.intervalSeconds
            if (interval != 300 && interval != 900) {
                return Result.failure(IllegalArgumentException(ErrorCode.CRYPTO_TAIL_STRATEGY_INTERVAL_INVALID.messageKey))
            }
            val maxWindow = maxWindowByInterval[interval] ?: 300
            if (request.windowStartSeconds > request.windowEndSeconds) {
                return Result.failure(IllegalArgumentException(ErrorCode.CRYPTO_TAIL_STRATEGY_WINDOW_INVALID.messageKey))
            }
            if (request.windowEndSeconds > maxWindow) {
                return Result.failure(IllegalArgumentException(ErrorCode.CRYPTO_TAIL_STRATEGY_WINDOW_EXCEED.messageKey))
            }
            val amountMode = request.amountMode.uppercase()
            if (amountMode != "RATIO" && amountMode != "FIXED") {
                return Result.failure(IllegalArgumentException(ErrorCode.CRYPTO_TAIL_STRATEGY_AMOUNT_MODE_INVALID.messageKey))
            }
            val minPrice = request.minPrice.toSafeBigDecimal()
            val maxPrice = (request.maxPrice ?: "1").toSafeBigDecimal()
            if (minPrice > maxPrice) {
                return Result.failure(IllegalArgumentException(ErrorCode.PARAM_ERROR.messageKey))
            }
            val amountValue = request.amountValue.toSafeBigDecimal()
            if (amountValue <= BigDecimal.ZERO) {
                return Result.failure(IllegalArgumentException(ErrorCode.PARAM_ERROR.messageKey))
            }
            val spreadMode = try {
                SpreadMode.fromString(request.spreadMode)
            } catch (e: Exception) {
                return Result.failure(IllegalArgumentException(ErrorCode.PARAM_ERROR.messageKey))
            }
            val spreadValue = request.spreadValue?.toSafeBigDecimal()
            if (spreadMode == SpreadMode.FIXED && (spreadValue == null || spreadValue < BigDecimal.ZERO)) {
                return Result.failure(IllegalArgumentException(ErrorCode.PARAM_ERROR.messageKey))
            }
            val spreadDirection = try {
                SpreadDirection.fromString(request.spreadDirection)
            } catch (e: Exception) {
                return Result.failure(IllegalArgumentException(ErrorCode.PARAM_ERROR.messageKey))
            }

            val nameToSave = request.name?.takeIf { it.isNotBlank() }
                ?: generateStrategyName(request.marketSlugPrefix.trim())

            // 障碍模式参数（缺省即默认，barrierEnabled=false 时不影响旧行为）
            val entryProb = request.entryProb?.toSafeBigDecimal() ?: BigDecimal("0.55")
            val entryEdge = request.entryEdge?.toSafeBigDecimal() ?: BigDecimal("0.02")
            val maxEntryPrice = request.maxEntryPrice?.toSafeBigDecimal() ?: BigDecimal("0.99")
            val costBuffer = request.costBuffer?.toSafeBigDecimal() ?: BigDecimal("0.02")
            val barrierMinMarketProb = request.barrierMinMarketProb?.toSafeBigDecimal() ?: BigDecimal.ZERO
            val sigmaScale = request.sigmaScale?.toSafeBigDecimal() ?: BigDecimal("1.0")
            val dailyLossLimitUsdc = request.dailyLossLimitUsdc?.toSafeBigDecimal()
            val maxConcurrentPositions = request.maxConcurrentPositions
            val takerFeeBps = request.takerFeeBps ?: 0
            val makerRebateBps = request.makerRebateBps ?: 0
            val gasCostUsdc = request.gasCostUsdc?.toSafeBigDecimal() ?: BigDecimal.ZERO
            val entryOrderType = (request.entryOrderType ?: "FAK").trim().uppercase()
            val entryFakSlippage = request.entryFakSlippage?.toSafeBigDecimal() ?: BigDecimal("0.02")
            val exitFakSlippage = request.exitFakSlippage?.toSafeBigDecimal() ?: BigDecimal("0.02")
            if (!isEntryFakSlippageValid(exitFakSlippage)) {
                return Result.failure(IllegalArgumentException(ErrorCode.PARAM_ERROR.messageKey))
            }
            val makerPriceOffset = request.makerPriceOffset?.toSafeBigDecimal() ?: BigDecimal.ZERO
            val makerCancelBeforeSettleSeconds = request.makerCancelBeforeSettleSeconds ?: 5
            val makerFallbackTaker = request.makerFallbackTaker ?: false
            val calibrationGateEnabled = request.calibrationGateEnabled ?: false
            val probeAmountUsdc = request.probeAmountUsdc?.toSafeBigDecimal() ?: BigDecimal.ONE
            val calibrationMinSamples = request.calibrationMinSamples ?: 30
            val calibrationMaxError = request.calibrationMaxError?.toSafeBigDecimal() ?: BigDecimal("0.10")
            val sigmaMethod = (request.sigmaMethod ?: "GARMAN_KLASS").trim().uppercase()
            val ewmaLambda = request.ewmaLambda?.toSafeBigDecimal() ?: BigDecimal("0.94")
            val kellyEnabled = request.kellyEnabled ?: false
            val kellyFraction = request.kellyFraction?.toSafeBigDecimal() ?: BigDecimal("0.25")
            val allowDuplicateMarketPosition = request.allowDuplicateMarketPosition ?: false
            // Strong Gap Boost（V60）
            val enableStrongGapBoost = request.enableStrongGapBoost ?: false
            val strongGapBoostShadow = request.strongGapBoostShadow ?: true
            val strongGapMinPwin = request.strongGapMinPwin?.toSafeBigDecimal() ?: BigDecimal("0.90")
            val strongGapMinSafeRatio = request.strongGapMinSafeRatio?.toSafeBigDecimal() ?: BigDecimal("1.50")
            val strongGapStakeMultiplier = request.strongGapStakeMultiplier?.toSafeBigDecimal() ?: BigDecimal("1.50")
            val ultraGapMinPwin = request.ultraGapMinPwin?.toSafeBigDecimal() ?: BigDecimal("0.95")
            val ultraGapMinSafeRatio = request.ultraGapMinSafeRatio?.toSafeBigDecimal() ?: BigDecimal("2.00")
            val ultraGapStakeMultiplier = request.ultraGapStakeMultiplier?.toSafeBigDecimal() ?: BigDecimal("2.00")
            val maxStrongGapStakeMultiplier = request.maxStrongGapStakeMultiplier?.toSafeBigDecimal() ?: BigDecimal("2.00")
            val maxBoostedAmountUsdc = request.maxBoostedAmountUsdc?.takeIf { it.isNotBlank() }?.toSafeBigDecimal()
            val maxBoostedPeriodExposureUsdc = request.maxBoostedPeriodExposureUsdc?.takeIf { it.isNotBlank() }?.toSafeBigDecimal()
            val allowBoostWithKelly = request.allowBoostWithKelly ?: false

            // V52：mode 字段优先；request.mode 缺失时回退 barrierEnabled 兼容（旧前端不带 mode 也能运行）
            val resolvedMode = when {
                request.mode != null -> com.wrbug.polymarketbot.enums.TradingMode.fromValueOrDefault(request.mode)
                request.barrierEnabled -> com.wrbug.polymarketbot.enums.TradingMode.BARRIER_HOLD
                else -> com.wrbug.polymarketbot.enums.TradingMode.LEGACY_SPREAD
            }
            // 阶梯专属字段：用默认值兜底（与 V52 SQL 默认值一致）
            val bracketEntryProb = request.bracketEntryProb?.toSafeBigDecimal() ?: BigDecimal("0.80")
            val bracketEntryEdge = request.bracketEntryEdge?.toSafeBigDecimal() ?: BigDecimal("0.04")
            val bracketMaxEntryPrice = request.bracketMaxEntryPrice?.toSafeBigDecimal() ?: BigDecimal("0.90")
            val tp1Price = request.tp1Price?.toSafeBigDecimal() ?: BigDecimal("0.90")
            val tp1Ratio = request.tp1Ratio?.toSafeBigDecimal() ?: BigDecimal("0.50")
            val tp1HoldPwin = request.tp1HoldPwin?.toSafeBigDecimal() ?: BigDecimal("0.95")
            val tp2Price = request.tp2Price?.toSafeBigDecimal() ?: BigDecimal("0.95")
            val tp2Ratio = request.tp2Ratio?.toSafeBigDecimal() ?: BigDecimal("1.00")
            val tp2HoldPwin = request.tp2HoldPwin?.toSafeBigDecimal() ?: BigDecimal("0.99")
            val holdToSettlePwin = request.holdToSettlePwin?.toSafeBigDecimal() ?: BigDecimal("0.97")
            val holdToSettleSeconds = request.holdToSettleSeconds ?: 30
            val stopProb = request.stopProb?.toSafeBigDecimal() ?: BigDecimal("0.55")
            val stopPrice = request.stopPrice?.toSafeBigDecimal() ?: BigDecimal("0.70")
            val forceExitBeforeSettleSeconds = request.forceExitBeforeSettleSeconds ?: 15
            val exitOrderType = (request.exitOrderType ?: "FAK").trim().uppercase()
            val minSafeRatio = request.minSafeRatio?.toSafeBigDecimal() ?: BigDecimal("1.20")
            val minSafeRatioUp = request.minSafeRatioUp?.toSafeBigDecimal() ?: BigDecimal("1.50")
            val minSafeRatioDown = request.minSafeRatioDown?.toSafeBigDecimal() ?: BigDecimal("1.20")
            val highPriceThreshold = request.highPriceThreshold?.toSafeBigDecimal() ?: BigDecimal("0.90")
            val highPriceMinPWin = request.highPriceMinPWin?.toSafeBigDecimal() ?: BigDecimal("0.97")
            val highPriceMinSafeRatio = request.highPriceMinSafeRatio?.toSafeBigDecimal() ?: BigDecimal("2.50")
            val enableExitManager = request.enableExitManager ?: true
            val maxLossPct = request.maxLossPct?.toSafeBigDecimal() ?: BigDecimal("0.20")
            val exitPWin = request.exitPWin?.toSafeBigDecimal() ?: BigDecimal("0.70")
            val exitSafeRatio = request.exitSafeRatio?.toSafeBigDecimal() ?: BigDecimal("0.80")
            val exitConfirmTicks = request.exitConfirmTicks ?: 2
            val takeProfitDelta1 = request.takeProfitDelta1?.toSafeBigDecimal() ?: BigDecimal("0.08")
            val takeProfitSellPct1 = request.takeProfitSellPct1?.toSafeBigDecimal() ?: BigDecimal("0.50")
            val takeProfitBid2 = request.takeProfitBid2?.toSafeBigDecimal() ?: BigDecimal("0.93")
            val takeProfitSellPct2 = request.takeProfitSellPct2?.toSafeBigDecimal() ?: BigDecimal("0.80")
            val enableSmartHardStop = request.enableSmartHardStop ?: false
            val emergencyExitOnModelFlip = request.emergencyExitOnModelFlip ?: true
            val emergencyExitOnGapFlip = request.emergencyExitOnGapFlip ?: true
            val exitPollIntervalMs = request.exitPollIntervalMs ?: 3000
            val enableWickFilter = request.enableWickFilter ?: true
            val wickFilterMode = normalizeWickFilterMode(request.wickFilterMode)
            val wickLookbackMinutes = request.wickLookbackMinutes ?: 2
            val wickMinBodyRatio = request.wickMinBodyRatio?.toSafeBigDecimal() ?: BigDecimal("0.20")
            val wickRejectionRatio = request.wickRejectionRatio?.toSafeBigDecimal() ?: BigDecimal("0.55")
            val wickMaWindow = request.wickMaWindow ?: 3
            val wickEntryBlockScore = request.wickEntryBlockScore ?: 70
            val wickExitScore = request.wickExitScore ?: 75
            val wickHoldProfitScore = request.wickHoldProfitScore ?: 65
            val wickUseBinanceVolume = request.wickUseBinanceVolume ?: false
            val wickVolumeSpikeRatio = request.wickVolumeSpikeRatio?.toSafeBigDecimal() ?: BigDecimal("1.50")
            val wickMinTicksPerCandle = request.wickMinTicksPerCandle ?: 5
            val wickMinRangeSigmaRatio = request.wickMinRangeSigmaRatio?.toSafeBigDecimal() ?: BigDecimal("0.25")
            val wickClosePositionUpMax = request.wickClosePositionUpMax?.toSafeBigDecimal() ?: BigDecimal("0.35")
            val wickClosePositionDownMin = request.wickClosePositionDownMin?.toSafeBigDecimal() ?: BigDecimal("0.65")
            val maxHoldTp1DelaySeconds = request.maxHoldTp1DelaySeconds ?: 45
            val holdTp1PeakDrawdown = request.holdTp1PeakDrawdown?.toSafeBigDecimal() ?: BigDecimal("0.03")
            val maxEntrySpread = request.maxEntrySpread?.toSafeBigDecimal() ?: BigDecimal("0.03")
            val maxOrderbookAgeMs = request.maxOrderbookAgeMs ?: 3000
            val maxPriceAgeMs = request.maxPriceAgeMs ?: 3000
            val minRemainingSeconds = request.minRemainingSeconds ?: 90
            val maxRemainingSeconds = request.maxRemainingSeconds ?: 420
            val minExitBidDepthUsdc = request.minExitBidDepthUsdc?.toSafeBigDecimal() ?: BigDecimal("2.00")
            val maxExitSpread = request.maxExitSpread?.toSafeBigDecimal() ?: BigDecimal("0.05")
            val enableTrailingStop = request.enableTrailingStop ?: true
            val trailingStartDelta = request.trailingStartDelta?.toSafeBigDecimal() ?: BigDecimal("0.08")
            val trailingDrawdown = request.trailingDrawdown?.toSafeBigDecimal() ?: BigDecimal("0.06")
            val trailingSellPct = request.trailingSellPct?.toSafeBigDecimal() ?: BigDecimal.ONE
            val maxOrdersPerDay = request.maxOrdersPerDay
            val maxConsecutiveLosses = request.maxConsecutiveLosses
            val pauseAfterLossMinutes = request.pauseAfterLossMinutes ?: 0

            // ===== 尾盘价差模式（TAIL_DIFF, V62）：缺省走 V62 SQL 默认值，行为不影响其他模式 =====
            val td = resolveTailDiffCreate(request)
            if (resolvedMode == com.wrbug.polymarketbot.enums.TradingMode.TAIL_DIFF && !isTailDiffParamsValid(td)) {
                return Result.failure(IllegalArgumentException(ErrorCode.CRYPTO_TAIL_STRATEGY_TAIL_DIFF_PARAM_INVALID.messageKey))
            }

            if (resolvedMode == com.wrbug.polymarketbot.enums.TradingMode.BARRIER_HOLD &&
                !isBarrierParamsValid(entryProb, entryEdge, maxEntryPrice, costBuffer, barrierMinMarketProb, sigmaScale, dailyLossLimitUsdc, maxConcurrentPositions, takerFeeBps, makerRebateBps, gasCostUsdc, entryOrderType, entryFakSlippage, makerPriceOffset, makerCancelBeforeSettleSeconds, interval, probeAmountUsdc, calibrationMinSamples, calibrationMaxError, sigmaMethod, ewmaLambda, kellyFraction)) {
                return Result.failure(IllegalArgumentException(ErrorCode.CRYPTO_TAIL_STRATEGY_BARRIER_PARAM_INVALID.messageKey))
            }
            if (resolvedMode == com.wrbug.polymarketbot.enums.TradingMode.BRACKET_DYNAMIC &&
                !isBracketParamsValid(bracketEntryProb, bracketEntryEdge, bracketMaxEntryPrice,
                    tp1Price, tp1Ratio, tp1HoldPwin, tp2Price, tp2Ratio, tp2HoldPwin,
                    holdToSettlePwin, holdToSettleSeconds, stopProb, stopPrice, forceExitBeforeSettleSeconds, exitOrderType, entryFakSlippage)) {
                return Result.failure(IllegalArgumentException(ErrorCode.CRYPTO_TAIL_STRATEGY_BARRIER_PARAM_INVALID.messageKey))
            }
            if (resolvedMode != com.wrbug.polymarketbot.enums.TradingMode.LEGACY_SPREAD &&
                !isProbabilityRiskParamsValid(
                    minSafeRatio, minSafeRatioUp, minSafeRatioDown, highPriceThreshold, highPriceMinPWin, highPriceMinSafeRatio,
                    maxLossPct, exitPWin, exitSafeRatio, exitConfirmTicks, takeProfitDelta1, takeProfitSellPct1, takeProfitBid2,
                    takeProfitSellPct2, exitPollIntervalMs, wickLookbackMinutes, wickMinBodyRatio, wickRejectionRatio, wickMaWindow,
                    wickEntryBlockScore, wickExitScore, wickHoldProfitScore, wickVolumeSpikeRatio, wickMinTicksPerCandle,
                    wickMinRangeSigmaRatio, wickClosePositionUpMax, wickClosePositionDownMin, maxHoldTp1DelaySeconds,
                    holdTp1PeakDrawdown, maxEntrySpread, maxOrderbookAgeMs, maxPriceAgeMs, minRemainingSeconds, maxRemainingSeconds,
                    wickFilterMode, minExitBidDepthUsdc, maxExitSpread,
                    trailingStartDelta, trailingDrawdown, trailingSellPct, maxOrdersPerDay, maxConsecutiveLosses, pauseAfterLossMinutes
                )) {
                return Result.failure(IllegalArgumentException(ErrorCode.CRYPTO_TAIL_STRATEGY_BARRIER_PARAM_INVALID.messageKey))
            }

            val entity = CryptoTailStrategy(
                accountId = request.accountId,
                name = nameToSave,
                marketSlugPrefix = request.marketSlugPrefix.trim(),
                intervalSeconds = interval,
                windowStartSeconds = request.windowStartSeconds,
                windowEndSeconds = request.windowEndSeconds,
                minPrice = minPrice,
                maxPrice = maxPrice,
                amountMode = amountMode,
                amountValue = amountValue,
                spreadMode = spreadMode,
                spreadValue = spreadValue,
                spreadDirection = spreadDirection,
                enabled = request.enabled,
                entryProb = entryProb,
                entryEdge = entryEdge,
                maxEntryPrice = maxEntryPrice,
                costBuffer = costBuffer,
                barrierMinMarketProb = barrierMinMarketProb,
                sigmaScale = sigmaScale,
                dailyLossLimitUsdc = dailyLossLimitUsdc,
                maxConcurrentPositions = maxConcurrentPositions,
                takerFeeBps = takerFeeBps,
                makerRebateBps = makerRebateBps,
                gasCostUsdc = gasCostUsdc,
                entryOrderType = entryOrderType,
                entryFakSlippage = entryFakSlippage,
                exitFakSlippage = exitFakSlippage,
                makerPriceOffset = makerPriceOffset,
                makerCancelBeforeSettleSeconds = makerCancelBeforeSettleSeconds,
                makerFallbackTaker = makerFallbackTaker,
                calibrationGateEnabled = calibrationGateEnabled,
                probeAmountUsdc = probeAmountUsdc,
                calibrationMinSamples = calibrationMinSamples,
                calibrationMaxError = calibrationMaxError,
                sigmaMethod = sigmaMethod,
                ewmaLambda = ewmaLambda,
                kellyEnabled = kellyEnabled,
                kellyFraction = kellyFraction,
                allowDuplicateMarketPosition = allowDuplicateMarketPosition,
                enableStrongGapBoost = enableStrongGapBoost,
                strongGapBoostShadow = strongGapBoostShadow,
                strongGapMinPwin = strongGapMinPwin,
                strongGapMinSafeRatio = strongGapMinSafeRatio,
                strongGapStakeMultiplier = strongGapStakeMultiplier,
                ultraGapMinPwin = ultraGapMinPwin,
                ultraGapMinSafeRatio = ultraGapMinSafeRatio,
                ultraGapStakeMultiplier = ultraGapStakeMultiplier,
                maxStrongGapStakeMultiplier = maxStrongGapStakeMultiplier,
                maxBoostedAmountUsdc = maxBoostedAmountUsdc,
                maxBoostedPeriodExposureUsdc = maxBoostedPeriodExposureUsdc,
                allowBoostWithKelly = allowBoostWithKelly,
                mode = resolvedMode,
                // 同步 barrierEnabled 兼容字段：mode==BARRIER_HOLD ⇔ barrierEnabled=true
                barrierEnabled = resolvedMode == com.wrbug.polymarketbot.enums.TradingMode.BARRIER_HOLD,
                bracketEntryProb = bracketEntryProb,
                bracketEntryEdge = bracketEntryEdge,
                bracketMaxEntryPrice = bracketMaxEntryPrice,
                tp1Price = tp1Price,
                tp1Ratio = tp1Ratio,
                tp1HoldPwin = tp1HoldPwin,
                tp2Price = tp2Price,
                tp2Ratio = tp2Ratio,
                tp2HoldPwin = tp2HoldPwin,
                holdToSettlePwin = holdToSettlePwin,
                holdToSettleSeconds = holdToSettleSeconds,
                stopProb = stopProb,
                stopPrice = stopPrice,
                forceExitBeforeSettleSeconds = forceExitBeforeSettleSeconds,
                exitOrderType = exitOrderType,
                minSafeRatio = minSafeRatio,
                minSafeRatioUp = minSafeRatioUp,
                minSafeRatioDown = minSafeRatioDown,
                highPriceThreshold = highPriceThreshold,
                highPriceMinPWin = highPriceMinPWin,
                highPriceMinSafeRatio = highPriceMinSafeRatio,
                enableExitManager = enableExitManager,
                maxLossPct = maxLossPct,
                exitPWin = exitPWin,
                exitSafeRatio = exitSafeRatio,
                exitConfirmTicks = exitConfirmTicks,
                takeProfitDelta1 = takeProfitDelta1,
                takeProfitSellPct1 = takeProfitSellPct1,
                takeProfitBid2 = takeProfitBid2,
                takeProfitSellPct2 = takeProfitSellPct2,
                enableSmartHardStop = enableSmartHardStop,
                emergencyExitOnModelFlip = emergencyExitOnModelFlip,
                emergencyExitOnGapFlip = emergencyExitOnGapFlip,
                exitPollIntervalMs = exitPollIntervalMs,
                enableWickFilter = enableWickFilter,
                wickFilterMode = wickFilterMode,
                wickLookbackMinutes = wickLookbackMinutes,
                wickMinBodyRatio = wickMinBodyRatio,
                wickRejectionRatio = wickRejectionRatio,
                wickMaWindow = wickMaWindow,
                wickEntryBlockScore = wickEntryBlockScore,
                wickExitScore = wickExitScore,
                wickHoldProfitScore = wickHoldProfitScore,
                wickUseBinanceVolume = wickUseBinanceVolume,
                wickVolumeSpikeRatio = wickVolumeSpikeRatio,
                wickMinTicksPerCandle = wickMinTicksPerCandle,
                wickMinRangeSigmaRatio = wickMinRangeSigmaRatio,
                wickClosePositionUpMax = wickClosePositionUpMax,
                wickClosePositionDownMin = wickClosePositionDownMin,
                maxHoldTp1DelaySeconds = maxHoldTp1DelaySeconds,
                holdTp1PeakDrawdown = holdTp1PeakDrawdown,
                maxEntrySpread = maxEntrySpread,
                maxOrderbookAgeMs = maxOrderbookAgeMs,
                maxPriceAgeMs = maxPriceAgeMs,
                minRemainingSeconds = minRemainingSeconds,
                maxRemainingSeconds = maxRemainingSeconds,
                minExitBidDepthUsdc = minExitBidDepthUsdc,
                maxExitSpread = maxExitSpread,
                enableTrailingStop = enableTrailingStop,
                trailingStartDelta = trailingStartDelta,
                trailingDrawdown = trailingDrawdown,
                trailingSellPct = trailingSellPct,
                maxOrdersPerDay = maxOrdersPerDay,
                maxConsecutiveLosses = maxConsecutiveLosses,
                pauseAfterLossMinutes = pauseAfterLossMinutes,
                tailDiffDirection = td.direction,
                tailDiffWindowStartSeconds = td.windowStartSeconds,
                tailDiffWindowEndSeconds = td.windowEndSeconds,
                tailDiffMinRemainingSeconds = td.minRemainingSeconds,
                tailDiffConfirmTicks = td.confirmTicks,
                tailDiffMinPrice = td.minPrice,
                tailDiffMaxPrice = td.maxPrice,
                tailDiffHardMaxPrice = td.hardMaxPrice,
                tailDiffMinModelProb = td.minModelProb,
                tailDiffMinEdge = td.minEdge,
                tailDiffCostBuffer = td.costBuffer,
                tailDiffMinDiffSigma = td.minDiffSigma,
                tailDiffModelProbSource = td.modelProbSource,
                tailDiffStatsMinSamples = td.statsMinSamples,
                tailDiffStatsLookbackDays = td.statsLookbackDays,
                tailDiffStatsDataSource = td.statsDataSource,
                tailDiffMaxSpread = td.maxSpread,
                tailDiffDepthMultiplier = td.depthMultiplier,
                tailDiffMaxOrderbookAgeMs = td.maxOrderbookAgeMs,
                tailDiffMaxPriceAgeMs = td.maxPriceAgeMs,
                tailDiffReverseVelocityWindowSeconds = td.reverseVelocityWindowSeconds,
                tailDiffMaxReverseVelocitySigma = td.maxReverseVelocitySigma,
                tailDiffWeightDiff = td.weightDiff,
                tailDiffWeightTime = td.weightTime,
                tailDiffWeightOddsUnderprice = td.weightOddsUnderprice,
                tailDiffWeightOddsLag = td.weightOddsLag,
                tailDiffWeightHistory = td.weightHistory,
                tailDiffWeightBook = td.weightBook,
                tailDiffWeightData = td.weightData,
                tailDiffMinEntryScore = td.minEntryScore,
                tailDiffPremiumScore = td.premiumScore,
                tailDiffTopScore = td.topScore,
                tailDiffBaseAmount = td.baseAmount,
                tailDiffTierNormalMult = td.tierNormalMult,
                tailDiffTierPremiumMult = td.tierPremiumMult,
                tailDiffTierTopMult = td.tierTopMult,
                tailDiffMaxAmountPerOrder = td.maxAmountPerOrder,
                tailDiffExitPresetNormalJson = td.exitPresetNormalJson,
                tailDiffExitPresetPremiumJson = td.exitPresetPremiumJson,
                tailDiffExitPresetTopJson = td.exitPresetTopJson,
                tailDiffDailyLossLimitUsdc = td.dailyLossLimitUsdc,
                tailDiffConsecLossPauseCount = td.consecLossPauseCount,
                tailDiffConsecLossStopCount = td.consecLossStopCount,
                tailDiffEntrySegmentsJson = td.entrySegmentsJson
            )
            val saved = strategyRepository.save(entity)
            eventPublisher.publishEvent(CryptoTailStrategyChangedEvent(this))
            Result.success(entityToDto(saved, null))
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        } catch (e: Exception) {
            logger.error("创建加密价差策略失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    @Transactional
    fun update(request: CryptoTailStrategyUpdateRequest): Result<CryptoTailStrategyDto> {
        return try {
            val existing = strategyRepository.findById(request.strategyId).orElse(null)
                ?: return Result.failure(IllegalArgumentException(ErrorCode.CRYPTO_TAIL_STRATEGY_NOT_FOUND.messageKey))
            val interval = existing.intervalSeconds
            val maxWindow = maxWindowByInterval[interval] ?: 300

            request.windowStartSeconds?.let { ws ->
                request.windowEndSeconds?.let { we ->
                    if (ws > we) return Result.failure(IllegalArgumentException(ErrorCode.CRYPTO_TAIL_STRATEGY_WINDOW_INVALID.messageKey))
                    if (we > maxWindow) return Result.failure(IllegalArgumentException(ErrorCode.CRYPTO_TAIL_STRATEGY_WINDOW_EXCEED.messageKey))
                }
            }
            request.windowStartSeconds?.let { if (it > (request.windowEndSeconds ?: existing.windowEndSeconds)) return Result.failure(IllegalArgumentException(ErrorCode.CRYPTO_TAIL_STRATEGY_WINDOW_INVALID.messageKey)) }
            request.windowEndSeconds?.let { if (it > maxWindow) return Result.failure(IllegalArgumentException(ErrorCode.CRYPTO_TAIL_STRATEGY_WINDOW_EXCEED.messageKey)) }

            val nameToSave = request.name?.takeIf { it.isNotBlank() }
                ?: existing.name?.takeIf { it.isNotBlank() }
                ?: generateStrategyName(existing.marketSlugPrefix)

            val newSpreadMode = if (request.spreadMode != null) {
                try {
                    SpreadMode.fromString(request.spreadMode)
                } catch (e: Exception) {
                    return Result.failure(IllegalArgumentException(ErrorCode.PARAM_ERROR.messageKey))
                }
            } else {
                existing.spreadMode
            }
            val newSpreadValue = request.spreadValue?.toSafeBigDecimal() ?: existing.spreadValue
            if (newSpreadMode == SpreadMode.FIXED && (newSpreadValue == null || newSpreadValue < BigDecimal.ZERO)) {
                return Result.failure(IllegalArgumentException(ErrorCode.PARAM_ERROR.messageKey))
            }
            val newSpreadDirection = if (request.spreadDirection != null) {
                try {
                    SpreadDirection.fromString(request.spreadDirection)
                } catch (e: Exception) {
                    return Result.failure(IllegalArgumentException(ErrorCode.PARAM_ERROR.messageKey))
                }
            } else {
                existing.spreadDirection
            }

            val newEntryProb = request.entryProb?.toSafeBigDecimal() ?: existing.entryProb
            val newEntryEdge = request.entryEdge?.toSafeBigDecimal() ?: existing.entryEdge
            val newMaxEntryPrice = request.maxEntryPrice?.toSafeBigDecimal() ?: existing.maxEntryPrice
            val newCostBuffer = request.costBuffer?.toSafeBigDecimal() ?: existing.costBuffer
            val newBarrierMinMarketProb = request.barrierMinMarketProb?.toSafeBigDecimal() ?: existing.barrierMinMarketProb
            val newSigmaScale = request.sigmaScale?.toSafeBigDecimal() ?: existing.sigmaScale
            val newDailyLossLimitUsdc = request.dailyLossLimitUsdc?.toSafeBigDecimal() ?: existing.dailyLossLimitUsdc
            val newMaxConcurrentPositions = request.maxConcurrentPositions ?: existing.maxConcurrentPositions
            val newTakerFeeBps = request.takerFeeBps ?: existing.takerFeeBps
            val newMakerRebateBps = request.makerRebateBps ?: existing.makerRebateBps
            val newGasCostUsdc = request.gasCostUsdc?.toSafeBigDecimal() ?: existing.gasCostUsdc
            val newEntryOrderType = (request.entryOrderType?.trim()?.uppercase()) ?: existing.entryOrderType
            val newEntryFakSlippage = request.entryFakSlippage?.toSafeBigDecimal() ?: existing.entryFakSlippage
            val newExitFakSlippage = request.exitFakSlippage?.toSafeBigDecimal() ?: existing.exitFakSlippage
            if (!isEntryFakSlippageValid(newExitFakSlippage)) {
                return Result.failure(IllegalArgumentException(ErrorCode.PARAM_ERROR.messageKey))
            }
            val newMakerPriceOffset = request.makerPriceOffset?.toSafeBigDecimal() ?: existing.makerPriceOffset
            val newMakerCancelBeforeSettleSeconds = request.makerCancelBeforeSettleSeconds ?: existing.makerCancelBeforeSettleSeconds
            val newMakerFallbackTaker = request.makerFallbackTaker ?: existing.makerFallbackTaker
            val newCalibrationGateEnabled = request.calibrationGateEnabled ?: existing.calibrationGateEnabled
            val newProbeAmountUsdc = request.probeAmountUsdc?.toSafeBigDecimal() ?: existing.probeAmountUsdc
            val newCalibrationMinSamples = request.calibrationMinSamples ?: existing.calibrationMinSamples
            val newCalibrationMaxError = request.calibrationMaxError?.toSafeBigDecimal() ?: existing.calibrationMaxError
            val newSigmaMethod = (request.sigmaMethod?.trim()?.uppercase()) ?: existing.sigmaMethod
            val newEwmaLambda = request.ewmaLambda?.toSafeBigDecimal() ?: existing.ewmaLambda
            val newKellyEnabled = request.kellyEnabled ?: existing.kellyEnabled
            val newKellyFraction = request.kellyFraction?.toSafeBigDecimal() ?: existing.kellyFraction
            val newAllowDuplicateMarketPosition = request.allowDuplicateMarketPosition ?: existing.allowDuplicateMarketPosition
            // Strong Gap Boost（V60）
            val newEnableStrongGapBoost = request.enableStrongGapBoost ?: existing.enableStrongGapBoost
            val newStrongGapBoostShadow = request.strongGapBoostShadow ?: existing.strongGapBoostShadow
            val newStrongGapMinPwin = request.strongGapMinPwin?.toSafeBigDecimal() ?: existing.strongGapMinPwin
            val newStrongGapMinSafeRatio = request.strongGapMinSafeRatio?.toSafeBigDecimal() ?: existing.strongGapMinSafeRatio
            val newStrongGapStakeMultiplier = request.strongGapStakeMultiplier?.toSafeBigDecimal() ?: existing.strongGapStakeMultiplier
            val newUltraGapMinPwin = request.ultraGapMinPwin?.toSafeBigDecimal() ?: existing.ultraGapMinPwin
            val newUltraGapMinSafeRatio = request.ultraGapMinSafeRatio?.toSafeBigDecimal() ?: existing.ultraGapMinSafeRatio
            val newUltraGapStakeMultiplier = request.ultraGapStakeMultiplier?.toSafeBigDecimal() ?: existing.ultraGapStakeMultiplier
            val newMaxStrongGapStakeMultiplier = request.maxStrongGapStakeMultiplier?.toSafeBigDecimal() ?: existing.maxStrongGapStakeMultiplier
            val newMaxBoostedAmountUsdc = request.maxBoostedAmountUsdc?.let { if (it.isBlank()) null else it.toSafeBigDecimal() } ?: existing.maxBoostedAmountUsdc
            val newMaxBoostedPeriodExposureUsdc = request.maxBoostedPeriodExposureUsdc?.let { if (it.isBlank()) null else it.toSafeBigDecimal() } ?: existing.maxBoostedPeriodExposureUsdc
            val newAllowBoostWithKelly = request.allowBoostWithKelly ?: existing.allowBoostWithKelly

            // V52：mode 字段优先；缺失时按 barrierEnabled 兼容
            val newMode = when {
                request.mode != null -> com.wrbug.polymarketbot.enums.TradingMode.fromValueOrDefault(request.mode)
                request.barrierEnabled != null && request.barrierEnabled -> com.wrbug.polymarketbot.enums.TradingMode.BARRIER_HOLD
                request.barrierEnabled != null && !request.barrierEnabled -> com.wrbug.polymarketbot.enums.TradingMode.LEGACY_SPREAD
                else -> existing.mode
            }
            val newBracketEntryProb = request.bracketEntryProb?.toSafeBigDecimal() ?: existing.bracketEntryProb
            val newBracketEntryEdge = request.bracketEntryEdge?.toSafeBigDecimal() ?: existing.bracketEntryEdge
            val newBracketMaxEntryPrice = request.bracketMaxEntryPrice?.toSafeBigDecimal() ?: existing.bracketMaxEntryPrice
            val newTp1Price = request.tp1Price?.toSafeBigDecimal() ?: existing.tp1Price
            val newTp1Ratio = request.tp1Ratio?.toSafeBigDecimal() ?: existing.tp1Ratio
            val newTp1HoldPwin = request.tp1HoldPwin?.toSafeBigDecimal() ?: existing.tp1HoldPwin
            val newTp2Price = request.tp2Price?.toSafeBigDecimal() ?: existing.tp2Price
            val newTp2Ratio = request.tp2Ratio?.toSafeBigDecimal() ?: existing.tp2Ratio
            val newTp2HoldPwin = request.tp2HoldPwin?.toSafeBigDecimal() ?: existing.tp2HoldPwin
            val newHoldToSettlePwin = request.holdToSettlePwin?.toSafeBigDecimal() ?: existing.holdToSettlePwin
            val newHoldToSettleSeconds = request.holdToSettleSeconds ?: existing.holdToSettleSeconds
            val newStopProb = request.stopProb?.toSafeBigDecimal() ?: existing.stopProb
            val newStopPrice = request.stopPrice?.toSafeBigDecimal() ?: existing.stopPrice
            val newForceExitBeforeSettleSeconds = request.forceExitBeforeSettleSeconds ?: existing.forceExitBeforeSettleSeconds
            val newExitOrderType = (request.exitOrderType?.trim()?.uppercase()) ?: existing.exitOrderType
            val newMinSafeRatio = request.minSafeRatio?.toSafeBigDecimal() ?: existing.minSafeRatio
            val newMinSafeRatioUp = request.minSafeRatioUp?.toSafeBigDecimal() ?: existing.minSafeRatioUp
            val newMinSafeRatioDown = request.minSafeRatioDown?.toSafeBigDecimal() ?: existing.minSafeRatioDown
            val newHighPriceThreshold = request.highPriceThreshold?.toSafeBigDecimal() ?: existing.highPriceThreshold
            val newHighPriceMinPWin = request.highPriceMinPWin?.toSafeBigDecimal() ?: existing.highPriceMinPWin
            val newHighPriceMinSafeRatio = request.highPriceMinSafeRatio?.toSafeBigDecimal() ?: existing.highPriceMinSafeRatio
            val newEnableExitManager = request.enableExitManager ?: existing.enableExitManager
            val newMaxLossPct = request.maxLossPct?.toSafeBigDecimal() ?: existing.maxLossPct
            val newExitPWin = request.exitPWin?.toSafeBigDecimal() ?: existing.exitPWin
            val newExitSafeRatio = request.exitSafeRatio?.toSafeBigDecimal() ?: existing.exitSafeRatio
            val newExitConfirmTicks = request.exitConfirmTicks ?: existing.exitConfirmTicks
            val newTakeProfitDelta1 = request.takeProfitDelta1?.toSafeBigDecimal() ?: existing.takeProfitDelta1
            val newTakeProfitSellPct1 = request.takeProfitSellPct1?.toSafeBigDecimal() ?: existing.takeProfitSellPct1
            val newTakeProfitBid2 = request.takeProfitBid2?.toSafeBigDecimal() ?: existing.takeProfitBid2
            val newTakeProfitSellPct2 = request.takeProfitSellPct2?.toSafeBigDecimal() ?: existing.takeProfitSellPct2
            val newEnableSmartHardStop = request.enableSmartHardStop ?: existing.enableSmartHardStop
            val newEmergencyExitOnModelFlip = request.emergencyExitOnModelFlip ?: existing.emergencyExitOnModelFlip
            val newEmergencyExitOnGapFlip = request.emergencyExitOnGapFlip ?: existing.emergencyExitOnGapFlip
            val newExitPollIntervalMs = request.exitPollIntervalMs ?: existing.exitPollIntervalMs
            val newEnableWickFilter = request.enableWickFilter ?: existing.enableWickFilter
            val newWickFilterMode = request.wickFilterMode?.let { normalizeWickFilterMode(it) } ?: existing.wickFilterMode
            val newWickLookbackMinutes = request.wickLookbackMinutes ?: existing.wickLookbackMinutes
            val newWickMinBodyRatio = request.wickMinBodyRatio?.toSafeBigDecimal() ?: existing.wickMinBodyRatio
            val newWickRejectionRatio = request.wickRejectionRatio?.toSafeBigDecimal() ?: existing.wickRejectionRatio
            val newWickMaWindow = request.wickMaWindow ?: existing.wickMaWindow
            val newWickEntryBlockScore = request.wickEntryBlockScore ?: existing.wickEntryBlockScore
            val newWickExitScore = request.wickExitScore ?: existing.wickExitScore
            val newWickHoldProfitScore = request.wickHoldProfitScore ?: existing.wickHoldProfitScore
            val newWickUseBinanceVolume = request.wickUseBinanceVolume ?: existing.wickUseBinanceVolume
            val newWickVolumeSpikeRatio = request.wickVolumeSpikeRatio?.toSafeBigDecimal() ?: existing.wickVolumeSpikeRatio
            val newWickMinTicksPerCandle = request.wickMinTicksPerCandle ?: existing.wickMinTicksPerCandle
            val newWickMinRangeSigmaRatio = request.wickMinRangeSigmaRatio?.toSafeBigDecimal() ?: existing.wickMinRangeSigmaRatio
            val newWickClosePositionUpMax = request.wickClosePositionUpMax?.toSafeBigDecimal() ?: existing.wickClosePositionUpMax
            val newWickClosePositionDownMin = request.wickClosePositionDownMin?.toSafeBigDecimal() ?: existing.wickClosePositionDownMin
            val newMaxHoldTp1DelaySeconds = request.maxHoldTp1DelaySeconds ?: existing.maxHoldTp1DelaySeconds
            val newHoldTp1PeakDrawdown = request.holdTp1PeakDrawdown?.toSafeBigDecimal() ?: existing.holdTp1PeakDrawdown
            val newMaxEntrySpread = request.maxEntrySpread?.toSafeBigDecimal() ?: existing.maxEntrySpread
            val newMaxOrderbookAgeMs = request.maxOrderbookAgeMs ?: existing.maxOrderbookAgeMs
            val newMaxPriceAgeMs = request.maxPriceAgeMs ?: existing.maxPriceAgeMs
            val newMinRemainingSeconds = request.minRemainingSeconds ?: existing.minRemainingSeconds
            val newMaxRemainingSeconds = request.maxRemainingSeconds ?: existing.maxRemainingSeconds
            val newMinExitBidDepthUsdc = request.minExitBidDepthUsdc?.toSafeBigDecimal() ?: existing.minExitBidDepthUsdc
            val newMaxExitSpread = request.maxExitSpread?.toSafeBigDecimal() ?: existing.maxExitSpread
            val newEnableTrailingStop = request.enableTrailingStop ?: existing.enableTrailingStop
            val newTrailingStartDelta = request.trailingStartDelta?.toSafeBigDecimal() ?: existing.trailingStartDelta
            val newTrailingDrawdown = request.trailingDrawdown?.toSafeBigDecimal() ?: existing.trailingDrawdown
            val newTrailingSellPct = request.trailingSellPct?.toSafeBigDecimal() ?: existing.trailingSellPct
            val newMaxOrdersPerDay = request.maxOrdersPerDay ?: existing.maxOrdersPerDay
            val newMaxConsecutiveLosses = request.maxConsecutiveLosses ?: existing.maxConsecutiveLosses
            val newPauseAfterLossMinutes = request.pauseAfterLossMinutes ?: existing.pauseAfterLossMinutes

            // ===== 尾盘价差模式（TAIL_DIFF, V62）：null 字段保留 existing，行为不影响其他模式 =====
            val td = resolveTailDiffUpdate(request, existing)
            if (newMode == com.wrbug.polymarketbot.enums.TradingMode.TAIL_DIFF && !isTailDiffParamsValid(td)) {
                return Result.failure(IllegalArgumentException(ErrorCode.CRYPTO_TAIL_STRATEGY_TAIL_DIFF_PARAM_INVALID.messageKey))
            }

            if (newMode == com.wrbug.polymarketbot.enums.TradingMode.BARRIER_HOLD &&
                !isBarrierParamsValid(newEntryProb, newEntryEdge, newMaxEntryPrice, newCostBuffer, newBarrierMinMarketProb, newSigmaScale, newDailyLossLimitUsdc, newMaxConcurrentPositions, newTakerFeeBps, newMakerRebateBps, newGasCostUsdc, newEntryOrderType, newEntryFakSlippage, newMakerPriceOffset, newMakerCancelBeforeSettleSeconds, existing.intervalSeconds, newProbeAmountUsdc, newCalibrationMinSamples, newCalibrationMaxError, newSigmaMethod, newEwmaLambda, newKellyFraction)) {
                return Result.failure(IllegalArgumentException(ErrorCode.CRYPTO_TAIL_STRATEGY_BARRIER_PARAM_INVALID.messageKey))
            }
            if (newMode == com.wrbug.polymarketbot.enums.TradingMode.BRACKET_DYNAMIC &&
                !isBracketParamsValid(newBracketEntryProb, newBracketEntryEdge, newBracketMaxEntryPrice,
                    newTp1Price, newTp1Ratio, newTp1HoldPwin, newTp2Price, newTp2Ratio, newTp2HoldPwin,
                    newHoldToSettlePwin, newHoldToSettleSeconds, newStopProb, newStopPrice, newForceExitBeforeSettleSeconds, newExitOrderType, newEntryFakSlippage)) {
                return Result.failure(IllegalArgumentException(ErrorCode.CRYPTO_TAIL_STRATEGY_BARRIER_PARAM_INVALID.messageKey))
            }
            if (newMode != com.wrbug.polymarketbot.enums.TradingMode.LEGACY_SPREAD &&
                !isProbabilityRiskParamsValid(
                    newMinSafeRatio, newMinSafeRatioUp, newMinSafeRatioDown, newHighPriceThreshold, newHighPriceMinPWin, newHighPriceMinSafeRatio,
                    newMaxLossPct, newExitPWin, newExitSafeRatio, newExitConfirmTicks, newTakeProfitDelta1, newTakeProfitSellPct1,
                    newTakeProfitBid2, newTakeProfitSellPct2, newExitPollIntervalMs, newWickLookbackMinutes, newWickMinBodyRatio,
                    newWickRejectionRatio, newWickMaWindow, newWickEntryBlockScore, newWickExitScore, newWickHoldProfitScore,
                    newWickVolumeSpikeRatio, newWickMinTicksPerCandle, newWickMinRangeSigmaRatio, newWickClosePositionUpMax,
                    newWickClosePositionDownMin, newMaxHoldTp1DelaySeconds, newHoldTp1PeakDrawdown, newMaxEntrySpread,
                    newMaxOrderbookAgeMs, newMaxPriceAgeMs, newMinRemainingSeconds, newMaxRemainingSeconds, newWickFilterMode,
                    newMinExitBidDepthUsdc, newMaxExitSpread, newTrailingStartDelta,
                    newTrailingDrawdown, newTrailingSellPct, newMaxOrdersPerDay, newMaxConsecutiveLosses, newPauseAfterLossMinutes
                )) {
                return Result.failure(IllegalArgumentException(ErrorCode.CRYPTO_TAIL_STRATEGY_BARRIER_PARAM_INVALID.messageKey))
            }

            val updated = existing.copy(
                name = nameToSave,
                windowStartSeconds = request.windowStartSeconds ?: existing.windowStartSeconds,
                windowEndSeconds = request.windowEndSeconds ?: existing.windowEndSeconds,
                minPrice = request.minPrice?.toSafeBigDecimal() ?: existing.minPrice,
                maxPrice = request.maxPrice?.toSafeBigDecimal() ?: existing.maxPrice,
                amountMode = request.amountMode?.uppercase() ?: existing.amountMode,
                amountValue = request.amountValue?.toSafeBigDecimal() ?: existing.amountValue,
                spreadMode = newSpreadMode,
                spreadValue = newSpreadValue,
                spreadDirection = newSpreadDirection,
                enabled = request.enabled ?: existing.enabled,
                entryProb = newEntryProb,
                entryEdge = newEntryEdge,
                maxEntryPrice = newMaxEntryPrice,
                costBuffer = newCostBuffer,
                barrierMinMarketProb = newBarrierMinMarketProb,
                sigmaScale = newSigmaScale,
                dailyLossLimitUsdc = newDailyLossLimitUsdc,
                maxConcurrentPositions = newMaxConcurrentPositions,
                takerFeeBps = newTakerFeeBps,
                makerRebateBps = newMakerRebateBps,
                gasCostUsdc = newGasCostUsdc,
                entryOrderType = newEntryOrderType,
                entryFakSlippage = newEntryFakSlippage,
                exitFakSlippage = newExitFakSlippage,
                makerPriceOffset = newMakerPriceOffset,
                makerCancelBeforeSettleSeconds = newMakerCancelBeforeSettleSeconds,
                makerFallbackTaker = newMakerFallbackTaker,
                calibrationGateEnabled = newCalibrationGateEnabled,
                probeAmountUsdc = newProbeAmountUsdc,
                calibrationMinSamples = newCalibrationMinSamples,
                calibrationMaxError = newCalibrationMaxError,
                sigmaMethod = newSigmaMethod,
                ewmaLambda = newEwmaLambda,
                kellyEnabled = newKellyEnabled,
                kellyFraction = newKellyFraction,
                allowDuplicateMarketPosition = newAllowDuplicateMarketPosition,
                enableStrongGapBoost = newEnableStrongGapBoost,
                strongGapBoostShadow = newStrongGapBoostShadow,
                strongGapMinPwin = newStrongGapMinPwin,
                strongGapMinSafeRatio = newStrongGapMinSafeRatio,
                strongGapStakeMultiplier = newStrongGapStakeMultiplier,
                ultraGapMinPwin = newUltraGapMinPwin,
                ultraGapMinSafeRatio = newUltraGapMinSafeRatio,
                ultraGapStakeMultiplier = newUltraGapStakeMultiplier,
                maxStrongGapStakeMultiplier = newMaxStrongGapStakeMultiplier,
                maxBoostedAmountUsdc = newMaxBoostedAmountUsdc,
                maxBoostedPeriodExposureUsdc = newMaxBoostedPeriodExposureUsdc,
                allowBoostWithKelly = newAllowBoostWithKelly,
                mode = newMode,
                barrierEnabled = newMode == com.wrbug.polymarketbot.enums.TradingMode.BARRIER_HOLD,
                bracketEntryProb = newBracketEntryProb,
                bracketEntryEdge = newBracketEntryEdge,
                bracketMaxEntryPrice = newBracketMaxEntryPrice,
                tp1Price = newTp1Price,
                tp1Ratio = newTp1Ratio,
                tp1HoldPwin = newTp1HoldPwin,
                tp2Price = newTp2Price,
                tp2Ratio = newTp2Ratio,
                tp2HoldPwin = newTp2HoldPwin,
                holdToSettlePwin = newHoldToSettlePwin,
                holdToSettleSeconds = newHoldToSettleSeconds,
                stopProb = newStopProb,
                stopPrice = newStopPrice,
                forceExitBeforeSettleSeconds = newForceExitBeforeSettleSeconds,
                exitOrderType = newExitOrderType,
                minSafeRatio = newMinSafeRatio,
                minSafeRatioUp = newMinSafeRatioUp,
                minSafeRatioDown = newMinSafeRatioDown,
                highPriceThreshold = newHighPriceThreshold,
                highPriceMinPWin = newHighPriceMinPWin,
                highPriceMinSafeRatio = newHighPriceMinSafeRatio,
                enableExitManager = newEnableExitManager,
                maxLossPct = newMaxLossPct,
                exitPWin = newExitPWin,
                exitSafeRatio = newExitSafeRatio,
                exitConfirmTicks = newExitConfirmTicks,
                takeProfitDelta1 = newTakeProfitDelta1,
                takeProfitSellPct1 = newTakeProfitSellPct1,
                takeProfitBid2 = newTakeProfitBid2,
                takeProfitSellPct2 = newTakeProfitSellPct2,
                enableSmartHardStop = newEnableSmartHardStop,
                emergencyExitOnModelFlip = newEmergencyExitOnModelFlip,
                emergencyExitOnGapFlip = newEmergencyExitOnGapFlip,
                exitPollIntervalMs = newExitPollIntervalMs,
                enableWickFilter = newEnableWickFilter,
                wickFilterMode = newWickFilterMode,
                wickLookbackMinutes = newWickLookbackMinutes,
                wickMinBodyRatio = newWickMinBodyRatio,
                wickRejectionRatio = newWickRejectionRatio,
                wickMaWindow = newWickMaWindow,
                wickEntryBlockScore = newWickEntryBlockScore,
                wickExitScore = newWickExitScore,
                wickHoldProfitScore = newWickHoldProfitScore,
                wickUseBinanceVolume = newWickUseBinanceVolume,
                wickVolumeSpikeRatio = newWickVolumeSpikeRatio,
                wickMinTicksPerCandle = newWickMinTicksPerCandle,
                wickMinRangeSigmaRatio = newWickMinRangeSigmaRatio,
                wickClosePositionUpMax = newWickClosePositionUpMax,
                wickClosePositionDownMin = newWickClosePositionDownMin,
                maxHoldTp1DelaySeconds = newMaxHoldTp1DelaySeconds,
                holdTp1PeakDrawdown = newHoldTp1PeakDrawdown,
                maxEntrySpread = newMaxEntrySpread,
                maxOrderbookAgeMs = newMaxOrderbookAgeMs,
                maxPriceAgeMs = newMaxPriceAgeMs,
                minRemainingSeconds = newMinRemainingSeconds,
                maxRemainingSeconds = newMaxRemainingSeconds,
                minExitBidDepthUsdc = newMinExitBidDepthUsdc,
                maxExitSpread = newMaxExitSpread,
                enableTrailingStop = newEnableTrailingStop,
                trailingStartDelta = newTrailingStartDelta,
                trailingDrawdown = newTrailingDrawdown,
                trailingSellPct = newTrailingSellPct,
                maxOrdersPerDay = newMaxOrdersPerDay,
                maxConsecutiveLosses = newMaxConsecutiveLosses,
                pauseAfterLossMinutes = newPauseAfterLossMinutes,
                tailDiffDirection = td.direction,
                tailDiffWindowStartSeconds = td.windowStartSeconds,
                tailDiffWindowEndSeconds = td.windowEndSeconds,
                tailDiffMinRemainingSeconds = td.minRemainingSeconds,
                tailDiffConfirmTicks = td.confirmTicks,
                tailDiffMinPrice = td.minPrice,
                tailDiffMaxPrice = td.maxPrice,
                tailDiffHardMaxPrice = td.hardMaxPrice,
                tailDiffMinModelProb = td.minModelProb,
                tailDiffMinEdge = td.minEdge,
                tailDiffCostBuffer = td.costBuffer,
                tailDiffMinDiffSigma = td.minDiffSigma,
                tailDiffModelProbSource = td.modelProbSource,
                tailDiffStatsMinSamples = td.statsMinSamples,
                tailDiffStatsLookbackDays = td.statsLookbackDays,
                tailDiffStatsDataSource = td.statsDataSource,
                tailDiffMaxSpread = td.maxSpread,
                tailDiffDepthMultiplier = td.depthMultiplier,
                tailDiffMaxOrderbookAgeMs = td.maxOrderbookAgeMs,
                tailDiffMaxPriceAgeMs = td.maxPriceAgeMs,
                tailDiffReverseVelocityWindowSeconds = td.reverseVelocityWindowSeconds,
                tailDiffMaxReverseVelocitySigma = td.maxReverseVelocitySigma,
                tailDiffWeightDiff = td.weightDiff,
                tailDiffWeightTime = td.weightTime,
                tailDiffWeightOddsUnderprice = td.weightOddsUnderprice,
                tailDiffWeightOddsLag = td.weightOddsLag,
                tailDiffWeightHistory = td.weightHistory,
                tailDiffWeightBook = td.weightBook,
                tailDiffWeightData = td.weightData,
                tailDiffMinEntryScore = td.minEntryScore,
                tailDiffPremiumScore = td.premiumScore,
                tailDiffTopScore = td.topScore,
                tailDiffBaseAmount = td.baseAmount,
                tailDiffTierNormalMult = td.tierNormalMult,
                tailDiffTierPremiumMult = td.tierPremiumMult,
                tailDiffTierTopMult = td.tierTopMult,
                tailDiffMaxAmountPerOrder = td.maxAmountPerOrder,
                tailDiffExitPresetNormalJson = td.exitPresetNormalJson,
                tailDiffExitPresetPremiumJson = td.exitPresetPremiumJson,
                tailDiffExitPresetTopJson = td.exitPresetTopJson,
                tailDiffDailyLossLimitUsdc = td.dailyLossLimitUsdc,
                tailDiffConsecLossPauseCount = td.consecLossPauseCount,
                tailDiffConsecLossStopCount = td.consecLossStopCount,
                tailDiffEntrySegmentsJson = td.entrySegmentsJson,
                updatedAt = System.currentTimeMillis()
            )
            if (updated.minPrice > updated.maxPrice) {
                return Result.failure(IllegalArgumentException(ErrorCode.PARAM_ERROR.messageKey))
            }
            request.amountMode?.uppercase()?.let { if (it != "RATIO" && it != "FIXED") return Result.failure(IllegalArgumentException(ErrorCode.CRYPTO_TAIL_STRATEGY_AMOUNT_MODE_INVALID.messageKey)) }
            val saved = strategyRepository.save(updated)
            eventPublisher.publishEvent(CryptoTailStrategyChangedEvent(this))
            val lastTrigger = triggerRepository.findAllByStrategyIdOrderByCreatedAtDesc(saved.id!!, PageRequest.of(0, 1))
                .content.firstOrNull()?.createdAt
            Result.success(entityToDto(saved, lastTrigger))
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        } catch (e: Exception) {
            logger.error("更新加密价差策略失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    @Transactional
    fun delete(strategyId: Long): Result<Unit> {
        return try {
            if (!strategyRepository.existsById(strategyId)) {
                return Result.failure(IllegalArgumentException(ErrorCode.CRYPTO_TAIL_STRATEGY_NOT_FOUND.messageKey))
            }
            strategyRepository.deleteById(strategyId)
            eventPublisher.publishEvent(CryptoTailStrategyChangedEvent(this))
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("删除加密价差策略失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    fun list(request: CryptoTailStrategyListRequest): Result<CryptoTailStrategyListResponse> {
        return try {
            val list = when {
                request.accountId != null && request.enabled != null -> strategyRepository.findByAccountIdAndEnabled(request.accountId, request.enabled)
                request.accountId != null -> strategyRepository.findAllByAccountId(request.accountId)
                request.enabled == true -> strategyRepository.findAllByEnabledTrue()
                request.enabled == false -> strategyRepository.findAll().filter { !it.enabled }
                else -> strategyRepository.findAll()
            }
            val lastTriggerMap = list.map { it.id!! }.associateWith { id ->
                triggerRepository.findAllByStrategyIdOrderByCreatedAtDesc(id, PageRequest.of(0, 1))
                    .content.firstOrNull()?.createdAt
            }
            val dtos = list.map { entityToDto(it, lastTriggerMap[it.id]) }
            Result.success(CryptoTailStrategyListResponse(list = dtos))
        } catch (e: Exception) {
            logger.error("查询加密价差策略列表失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    fun getPnlCurve(request: CryptoTailPnlCurveRequest): Result<CryptoTailPnlCurveResponse> {
        return try {
            val strategy = strategyRepository.findById(request.strategyId).orElse(null)
                ?: return Result.failure(IllegalArgumentException(ErrorCode.CRYPTO_TAIL_STRATEGY_NOT_FOUND.messageKey))
            val start = request.startDate ?: 0L
            val end = request.endDate ?: Long.MAX_VALUE
            val triggers = triggerRepository.findResolvedByStrategyIdAndTimeRangeOrderBySettledAsc(
                request.strategyId, start, end
            )
            var cumulative = BigDecimal.ZERO
            var peak = BigDecimal.ZERO
            var maxDrawdown = BigDecimal.ZERO
            var winCountInRange = 0L
            val curveData = triggers.map { t ->
                val pnl = t.realizedPnl ?: BigDecimal.ZERO
                cumulative = cumulative.add(pnl)
                if (cumulative.gt(peak)) peak = cumulative
                val drawdown = peak.subtract(cumulative)
                if (drawdown.gt(maxDrawdown)) maxDrawdown = drawdown
                if (t.winnerOutcomeIndex != null && t.outcomeIndex == t.winnerOutcomeIndex) winCountInRange++
                val ts = t.settledAt ?: t.createdAt
                CryptoTailPnlCurvePoint(
                    timestamp = ts,
                    cumulativePnl = cumulative.toPlainString(),
                    pointPnl = pnl.toPlainString(),
                    settledCount = 0L
                )
            }.mapIndexed { index, p ->
                p.copy(settledCount = (index + 1).toLong())
            }
            val totalPnl = if (curveData.isEmpty()) BigDecimal.ZERO else curveData.last().cumulativePnl.toSafeBigDecimal()
            val settledCountInRange = curveData.size.toLong()
            val winRateStr = if (settledCountInRange > 0L) {
                BigDecimal(winCountInRange).divide(BigDecimal(settledCountInRange), 4, java.math.RoundingMode.HALF_UP).toPlainString()
            } else null
            Result.success(
                CryptoTailPnlCurveResponse(
                    strategyId = request.strategyId,
                    strategyName = strategy.name ?: strategy.marketSlugPrefix,
                    totalRealizedPnl = totalPnl.toPlainString(),
                    settledCount = settledCountInRange,
                    winCount = winCountInRange,
                    winRate = winRateStr,
                    maxDrawdown = if (maxDrawdown.compareTo(BigDecimal.ZERO) > 0) maxDrawdown.toPlainString() else null,
                    curveData = curveData
                )
            )
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        } catch (e: Exception) {
            logger.error("查询收益曲线失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    fun getTriggerRecords(request: CryptoTailStrategyTriggerListRequest): Result<CryptoTailStrategyTriggerListResponse> {
        return try {
            val page = PageRequest.of((request.page - 1).coerceAtLeast(0), request.pageSize.coerceIn(1, 100))
            val startTs = request.startDate ?: 0L
            val endTs = request.endDate ?: Long.MAX_VALUE
            val useTimeRange = request.startDate != null || request.endDate != null
            val pageResult = when {
                useTimeRange && request.status != null && request.status.isNotBlank() ->
                    triggerRepository.findAllByStrategyIdAndStatusAndCreatedAtBetweenOrderByCreatedAtDesc(
                        request.strategyId, request.status, startTs, endTs, page
                    )
                useTimeRange ->
                    triggerRepository.findAllByStrategyIdAndCreatedAtBetweenOrderByCreatedAtDesc(
                        request.strategyId, startTs, endTs, page
                    )
                request.status != null && request.status.isNotBlank() ->
                    triggerRepository.findAllByStrategyIdAndStatusOrderByCreatedAtDesc(request.strategyId, request.status, page)
                else ->
                    triggerRepository.findAllByStrategyIdOrderByCreatedAtDesc(request.strategyId, page)
            }
            val list = pageResult.content.map { triggerToDto(it) }
            val total = when {
                useTimeRange && request.status != null && request.status.isNotBlank() ->
                    triggerRepository.countByStrategyIdAndStatusAndCreatedAtBetween(request.strategyId, request.status, startTs, endTs)
                useTimeRange ->
                    triggerRepository.countByStrategyIdAndCreatedAtBetween(request.strategyId, startTs, endTs)
                request.status != null && request.status.isNotBlank() ->
                    triggerRepository.countByStrategyIdAndStatus(request.strategyId, request.status)
                else ->
                    pageResult.totalElements
            }
            Result.success(CryptoTailStrategyTriggerListResponse(list = list, total = total))
        } catch (e: Exception) {
            logger.error("查询触发记录失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    fun getStrategy(strategyId: Long): CryptoTailStrategy? = strategyRepository.findById(strategyId).orElse(null)

    /**
     * 概率模式退出明细列表（按 trigger 维度），用于前端展开行展示分档/止损退出过程。
     * 任意 trigger 都可调用，未接入退出管理的 trigger 返回空列表。
     */
    fun getStrategyExits(request: CryptoTailStrategyExitListRequest): Result<CryptoTailStrategyExitListResponse> {
        return try {
            if (request.triggerId <= 0L) {
                return Result.failure(IllegalArgumentException(ErrorCode.PARAM_ERROR.messageKey))
            }
            val rows = exitRepository.findByTriggerIdOrderByCreatedAtAsc(request.triggerId)
            val list = rows.map { e ->
                CryptoTailStrategyExitDto(
                    id = e.id ?: 0L,
                    triggerId = e.triggerId,
                    strategyId = e.strategyId,
                    exitKind = e.exitKind,
                    targetSize = e.targetSize.toPlainString(),
                    filledSize = e.filledSize?.toPlainString(),
                    filledAmount = e.filledAmount?.toPlainString(),
                    exitPrice = e.exitPrice?.toPlainString(),
                    orderId = e.orderId,
                    orderType = e.orderType,
                    status = e.status,
                    pwinAtDecision = e.pwinAtDecision?.toPlainString(),
                    bestBidAtDecision = e.bestBidAtDecision?.toPlainString(),
                    remainingSeconds = e.remainingSeconds,
                    decisionReason = e.decisionReason,
                    failReason = e.failReason,
                    createdAt = e.createdAt,
                    settledAt = e.settledAt
                )
            }
            Result.success(CryptoTailStrategyExitListResponse(triggerId = request.triggerId, list = list))
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        } catch (e: Exception) {
            logger.error("查询阶梯退出明细失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    /** 查询全链路决策日志（分页，按时间倒序） */
    fun getDecisionLog(request: CryptoTailDecisionLogListRequest): Result<CryptoTailDecisionLogListResponse> {
        return try {
            val page = PageRequest.of((request.page - 1).coerceAtLeast(0), request.pageSize.coerceIn(1, 100))
            val useTimeRange = request.startDate != null || request.endDate != null
            val pageResult = if (useTimeRange) {
                decisionEventRepository.findAllByStrategyIdAndCreatedAtBetweenOrderByCreatedAtDesc(
                    request.strategyId, request.startDate ?: 0L, request.endDate ?: Long.MAX_VALUE, page
                )
            } else {
                decisionEventRepository.findAllByStrategyIdOrderByCreatedAtDesc(request.strategyId, page)
            }
            Result.success(
                CryptoTailDecisionLogListResponse(
                    list = pageResult.content.map { it.toDto() },
                    total = pageResult.totalElements
                )
            )
        } catch (e: Exception) {
            logger.error("查询决策日志失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    /** 跨策略决策日志分页查询（strategyId<=0 = 全部），结果按策略名富化 */
    fun getDecisionLogAll(request: CryptoTailDecisionLogListRequest): Result<CryptoTailDecisionLogListResponse> {
        return try {
            val page = PageRequest.of((request.page - 1).coerceAtLeast(0), request.pageSize.coerceIn(1, 100))
            val useTimeRange = request.startDate != null || request.endDate != null
            val start = request.startDate ?: 0L
            val end = request.endDate ?: Long.MAX_VALUE
            val pageResult = when {
                request.strategyId > 0 && useTimeRange ->
                    decisionEventRepository.findAllByStrategyIdAndCreatedAtBetweenOrderByCreatedAtDesc(request.strategyId, start, end, page)
                request.strategyId > 0 ->
                    decisionEventRepository.findAllByStrategyIdOrderByCreatedAtDesc(request.strategyId, page)
                useTimeRange ->
                    decisionEventRepository.findAllByCreatedAtBetweenOrderByCreatedAtDesc(start, end, page)
                else ->
                    decisionEventRepository.findAllByOrderByCreatedAtDesc(page)
            }
            Result.success(
                CryptoTailDecisionLogListResponse(
                    list = enrichStrategyName(pageResult.content.map { it.toDto() }),
                    total = pageResult.totalElements
                )
            )
        } catch (e: Exception) {
            logger.error("查询全局决策日志失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    /** 决策日志整段导出（按时间区间，strategyId<=0 = 全部），上限保护避免一次拉取过多 */
    fun exportDecisionLog(request: CryptoTailDecisionLogExportRequest): Result<CryptoTailDecisionLogExportResponse> {
        return try {
            val cap = PageRequest.of(0, 100000)
            val useTimeRange = request.startDate != null || request.endDate != null
            val start = request.startDate ?: 0L
            val end = request.endDate ?: Long.MAX_VALUE
            val pageResult = when {
                request.strategyId > 0 && useTimeRange ->
                    decisionEventRepository.findAllByStrategyIdAndCreatedAtBetweenOrderByCreatedAtDesc(request.strategyId, start, end, cap)
                request.strategyId > 0 ->
                    decisionEventRepository.findAllByStrategyIdOrderByCreatedAtDesc(request.strategyId, cap)
                useTimeRange ->
                    decisionEventRepository.findAllByCreatedAtBetweenOrderByCreatedAtDesc(start, end, cap)
                else ->
                    decisionEventRepository.findAllByOrderByCreatedAtDesc(cap)
            }
            Result.success(
                CryptoTailDecisionLogExportResponse(
                    list = enrichStrategyName(pageResult.content.map { it.toDto() }),
                    total = pageResult.totalElements
                )
            )
        } catch (e: Exception) {
            logger.error("导出决策日志失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    /** 为决策事件 DTO 批量填充策略名（一次性按 id 集合查询，避免 N+1） */
    private fun enrichStrategyName(list: List<CryptoTailDecisionEventDto>): List<CryptoTailDecisionEventDto> {
        if (list.isEmpty()) return list
        val ids = list.map { it.strategyId }.toSet()
        val nameById = strategyRepository.findAllById(ids).associate { (it.id ?: 0L) to it.name }
        return list.map { it.copy(strategyName = nameById[it.strategyId]) }
    }

    /** 单笔成交分析快照分页查询 */
    fun getTradeSnapshots(request: CryptoTailTradeSnapshotListRequest): Result<CryptoTailTradeSnapshotListResponse> {
        return try {
            val page = PageRequest.of((request.page - 1).coerceAtLeast(0), request.pageSize.coerceIn(1, 100))
            val useTimeRange = request.startDate != null || request.endDate != null
            val pageResult = if (useTimeRange) {
                tradeSnapshotRepository.findAllByStrategyIdAndSubmitTsBetweenOrderByPeriodStartUnixDesc(
                    request.strategyId, request.startDate ?: 0L, request.endDate ?: Long.MAX_VALUE, page
                )
            } else {
                tradeSnapshotRepository.findAllByStrategyIdOrderByPeriodStartUnixDesc(request.strategyId, page)
            }
            Result.success(
                CryptoTailTradeSnapshotListResponse(
                    list = pageResult.content.map { it.toDto() },
                    total = pageResult.totalElements
                )
            )
        } catch (e: Exception) {
            logger.error("查询单笔成交快照失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    /** 校准统计 + 放量闸状态查询（监控页展示） */
    fun getCalibration(request: CryptoTailCalibrationRequest): Result<CryptoTailCalibrationResponse> {
        return try {
            val strategy = strategyRepository.findById(request.strategyId).orElse(null)
                ?: return Result.failure(IllegalArgumentException(ErrorCode.CRYPTO_TAIL_STRATEGY_NOT_FOUND.messageKey))
            Result.success(calibrationService.getCalibration(strategy))
        } catch (e: Exception) {
            logger.error("查询校准统计失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    /** 按已结算样本推荐 sigmaScale（仅推荐，不自动套用） */
    fun recommendSigmaScale(request: CryptoTailRecommendSigmaScaleRequest): Result<CryptoTailRecommendSigmaScaleResponse> {
        return try {
            val strategy = strategyRepository.findById(request.strategyId).orElse(null)
                ?: return Result.failure(IllegalArgumentException(ErrorCode.CRYPTO_TAIL_STRATEGY_NOT_FOUND.messageKey))
            Result.success(calibrationService.recommendSigmaScale(strategy))
        } catch (e: Exception) {
            logger.error("推荐σ校准系数失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    /** 单笔成交分析快照 CSV 导出（按时间范围取全部，升序便于回测时间序列） */
    fun exportTradeSnapshots(request: CryptoTailTradeSnapshotExportRequest): Result<CryptoTailTradeSnapshotExportResponse> {
        return try {
            val useTimeRange = request.startDate != null || request.endDate != null
            val rows = if (useTimeRange) {
                tradeSnapshotRepository.findAllByStrategyIdAndSubmitTsBetweenOrderByPeriodStartUnixAsc(
                    request.strategyId, request.startDate ?: 0L, request.endDate ?: Long.MAX_VALUE
                )
            } else {
                tradeSnapshotRepository.findAllByStrategyIdOrderByPeriodStartUnixAsc(request.strategyId)
            }
            val csv = buildSnapshotCsv(rows.map { it.toDto() })
            val filename = "crypto_tail_snapshot_${request.strategyId}_${System.currentTimeMillis()}.csv"
            Result.success(CryptoTailTradeSnapshotExportResponse(filename = filename, csv = csv, total = rows.size))
        } catch (e: Exception) {
            logger.error("导出单笔成交快照失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun buildSnapshotCsv(rows: List<CryptoTailTradeSnapshotDto>): String {
        val headers = listOf(
            "id", "strategyId", "triggerId", "periodStartUnix", "marketSlug", "conditionId", "outcomeIndex", "intervalSeconds",
            "openPrice", "entryMarkPrice", "entryGap", "sigmaPerSqrtS", "pWin", "safeRatio", "modelSide", "remainingSecondsAtEntry",
            "bestBid", "bestAsk", "midPrice", "effectiveCost", "entryEdge",
            "entryProbThreshold", "entryEdgeThreshold", "barrierMinMarketProb", "sigmaScale", "maxEntryPrice", "costBuffer",
            "orderType", "targetPrice", "requestedAmount", "submitTs",
            "fillStatus", "fillPrice", "fillSize", "fillAmount", "slippage", "orderId", "execError",
            "settled", "winnerOutcomeIndex", "won", "realizedPnl", "settleTs", "holdSeconds",
            "finalOpen", "finalClose", "finalGap", "reversed", "settleSource", "lossReason", "pwinBucket", "createdAt", "updatedAt"
        )
        val sb = StringBuilder()
        sb.append(headers.joinToString(",")).append("\n")
        for (r in rows) {
            val cells = listOf(
                r.id, r.strategyId, r.triggerId, r.periodStartUnix, r.marketSlug, r.conditionId, r.outcomeIndex, r.intervalSeconds,
                r.openPrice, r.entryMarkPrice, r.entryGap, r.sigmaPerSqrtS, r.pWin, r.safeRatio, r.modelSide, r.remainingSecondsAtEntry,
                r.bestBid, r.bestAsk, r.midPrice, r.effectiveCost, r.entryEdge,
                r.entryProbThreshold, r.entryEdgeThreshold, r.barrierMinMarketProb, r.sigmaScale, r.maxEntryPrice, r.costBuffer,
                r.orderType, r.targetPrice, r.requestedAmount, r.submitTs,
                r.fillStatus, r.fillPrice, r.fillSize, r.fillAmount, r.slippage, r.orderId, r.execError,
                r.settled, r.winnerOutcomeIndex, r.won, r.realizedPnl, r.settleTs, r.holdSeconds,
                r.finalOpen, r.finalClose, r.finalGap, r.reversed, r.settleSource, r.lossReason, r.pwinBucket, r.createdAt, r.updatedAt
            )
            sb.append(cells.joinToString(",") { csvCell(it) }).append("\n")
        }
        return sb.toString()
    }

    /** CSV 单元格转义：含逗号/引号/换行时用双引号包裹并转义内部引号 */
    private fun csvCell(value: Any?): String {
        val s = value?.toString() ?: ""
        return if (s.contains(',') || s.contains('"') || s.contains('\n') || s.contains('\r')) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else s
    }

    private fun CryptoTailTradeSnapshot.toDto(): CryptoTailTradeSnapshotDto = CryptoTailTradeSnapshotDto(
        id = id ?: 0L,
        strategyId = strategyId,
        triggerId = triggerId,
        periodStartUnix = periodStartUnix,
        marketSlug = marketSlug,
        conditionId = conditionId,
        outcomeIndex = outcomeIndex,
        intervalSeconds = intervalSeconds,
        openPrice = openPrice?.toPlainString(),
        entryMarkPrice = entryMarkPrice?.toPlainString(),
        entryGap = entryGap?.toPlainString(),
        sigmaPerSqrtS = sigmaPerSqrtS?.toPlainString(),
        pWin = pWin?.toPlainString(),
        safeRatio = safeRatio?.toPlainString(),
        modelSide = modelSide,
        remainingSecondsAtEntry = remainingSecondsAtEntry,
        bestBid = bestBid?.toPlainString(),
        bestAsk = bestAsk?.toPlainString(),
        midPrice = midPrice?.toPlainString(),
        effectiveCost = effectiveCost?.toPlainString(),
        entryEdge = entryEdge?.toPlainString(),
        entryProbThreshold = entryProbThreshold?.toPlainString(),
        entryEdgeThreshold = entryEdgeThreshold?.toPlainString(),
        barrierMinMarketProb = barrierMinMarketProb?.toPlainString(),
        sigmaScale = sigmaScale?.toPlainString(),
        maxEntryPrice = maxEntryPrice?.toPlainString(),
        costBuffer = costBuffer?.toPlainString(),
        orderType = orderType,
        targetPrice = targetPrice?.toPlainString(),
        requestedAmount = requestedAmount?.toPlainString(),
        submitTs = submitTs,
        fillStatus = fillStatus,
        fillPrice = fillPrice?.toPlainString(),
        fillSize = fillSize?.toPlainString(),
        fillAmount = fillAmount?.toPlainString(),
        slippage = slippage?.toPlainString(),
        orderId = orderId,
        execError = execError,
        settled = settled,
        winnerOutcomeIndex = winnerOutcomeIndex,
        won = won,
        realizedPnl = realizedPnl?.toPlainString(),
        settleTs = settleTs,
        holdSeconds = holdSeconds,
        finalOpen = finalOpen?.toPlainString(),
        finalClose = finalClose?.toPlainString(),
        finalGap = finalGap?.toPlainString(),
        reversed = reversed,
        settleSource = settleSource,
        lossReason = lossReason,
        pwinBucket = pwinBucket,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    /** entryFakSlippage 通用校验：[0, 0.10] */
    private fun isEntryFakSlippageValid(slippage: BigDecimal): Boolean {
        return slippage >= BigDecimal.ZERO && slippage <= BigDecimal("0.10")
    }

    /** 障碍模式参数合法性校验（仅 barrierEnabled=true 时强制） */
    private fun isBarrierParamsValid(
        entryProb: BigDecimal,
        entryEdge: BigDecimal,
        maxEntryPrice: BigDecimal,
        costBuffer: BigDecimal,
        barrierMinMarketProb: BigDecimal,
        sigmaScale: BigDecimal,
        dailyLossLimitUsdc: BigDecimal?,
        maxConcurrentPositions: Int?,
        takerFeeBps: Int,
        makerRebateBps: Int,
        gasCostUsdc: BigDecimal,
        entryOrderType: String,
        entryFakSlippage: BigDecimal,
        makerPriceOffset: BigDecimal,
        makerCancelBeforeSettleSeconds: Int,
        intervalSeconds: Int,
        probeAmountUsdc: BigDecimal,
        calibrationMinSamples: Int,
        calibrationMaxError: BigDecimal,
        sigmaMethod: String,
        ewmaLambda: BigDecimal,
        kellyFraction: BigDecimal
    ): Boolean {
        val one = BigDecimal.ONE
        val zero = BigDecimal.ZERO
        if (entryProb <= zero || entryProb > one) return false
        if (entryEdge < zero || entryEdge >= one) return false
        if (maxEntryPrice <= zero || maxEntryPrice > one) return false
        if (costBuffer < zero || costBuffer >= one) return false
        if (barrierMinMarketProb < zero || barrierMinMarketProb > one) return false
        if (sigmaScale <= zero) return false
        if (dailyLossLimitUsdc != null && dailyLossLimitUsdc < zero) return false
        if (maxConcurrentPositions != null && maxConcurrentPositions < 0) return false
        // 费用/返佣基点合理上限 10000bps(=100%)，gas 非负
        if (takerFeeBps < 0 || takerFeeBps > 10000) return false
        if (makerRebateBps < 0 || makerRebateBps > 10000) return false
        if (gasCostUsdc < zero) return false
        // 进场订单类型仅 FAK / MAKER
        if (entryOrderType != "FAK" && entryOrderType != "MAKER") return false
        // V53: FAK 进场限价滑点合法范围
        if (!isEntryFakSlippageValid(entryFakSlippage)) return false
        // maker 价格偏移须在 (-1,1) 区间，避免越界
        if (makerPriceOffset <= one.negate() || makerPriceOffset >= one) return false
        // maker 撤单提前秒数须在 [0, interval) 内，至少留出成交窗口
        if (makerCancelBeforeSettleSeconds < 0 || makerCancelBeforeSettleSeconds >= intervalSeconds) return false
        // 放量闸：小额须 >= 1 USDC（平台最小下单额），样本数 >= 1，校准误差 (0,1)
        if (probeAmountUsdc < BigDecimal.ONE) return false
        if (calibrationMinSamples < 1) return false
        if (calibrationMaxError <= zero || calibrationMaxError >= one) return false
        // σ 估计方法仅 MAD / EWMA / GARMAN_KLASS；EWMA 衰减系数须在 (0,1)
        if (sigmaMethod != "MAD" && sigmaMethod != "EWMA" && sigmaMethod != "GARMAN_KLASS") return false
        if (ewmaLambda <= zero || ewmaLambda >= one) return false
        // 分数 Kelly：fraction 须在 (0,1]
        if (kellyFraction <= zero || kellyFraction > one) return false
        return true
    }

    /**
     * 概率阶梯止盈模式参数合法性校验。要求：
     *  - 概率/比率/价格类字段在 [0,1] 内（部分严格 (0,1)）
     *  - 止盈阈值递增：tp1Price <= tp2Price，TP1 跳过阈值不应高于 TP2（更紧的持有标准应在更晚阶段）
     *  - 强制平仓窗口必须严格小于持有到结算窗口（否则强平不会触发）
     *  - 止损概率必须严格低于入场概率（否则一进就止损）
     *  - 时间字段非负
     *  - exitOrderType 限定 FAK/MAKER
     *  - entryFakSlippage 在 [0, 0.10]
     */
    private fun isBracketParamsValid(
        bracketEntryProb: BigDecimal,
        bracketEntryEdge: BigDecimal,
        bracketMaxEntryPrice: BigDecimal,
        tp1Price: BigDecimal,
        tp1Ratio: BigDecimal,
        tp1HoldPwin: BigDecimal,
        tp2Price: BigDecimal,
        tp2Ratio: BigDecimal,
        tp2HoldPwin: BigDecimal,
        holdToSettlePwin: BigDecimal,
        holdToSettleSeconds: Int,
        stopProb: BigDecimal,
        stopPrice: BigDecimal,
        forceExitBeforeSettleSeconds: Int,
        exitOrderType: String,
        entryFakSlippage: BigDecimal
    ): Boolean {
        val one = BigDecimal.ONE
        val zero = BigDecimal.ZERO
        if (bracketEntryProb <= zero || bracketEntryProb > one) return false
        if (bracketEntryEdge < zero || bracketEntryEdge >= one) return false
        if (bracketMaxEntryPrice <= zero || bracketMaxEntryPrice > one) return false
        if (tp1Price <= zero || tp1Price > one) return false
        if (tp2Price <= zero || tp2Price > one) return false
        if (tp1Price > tp2Price) return false
        if (tp1Ratio < zero || tp1Ratio > one) return false
        if (tp2Ratio < zero || tp2Ratio > one) return false
        if (tp1HoldPwin < zero || tp1HoldPwin > one) return false
        if (tp2HoldPwin < zero || tp2HoldPwin > one) return false
        if (holdToSettlePwin < zero || holdToSettlePwin > one) return false
        if (stopProb < zero || stopProb > one) return false
        if (stopPrice <= zero || stopPrice > one) return false
        if (holdToSettleSeconds < 0) return false
        if (forceExitBeforeSettleSeconds < 0) return false
        if (exitOrderType != "FAK" && exitOrderType != "MAKER") return false
        // V53: FAK 进场限价滑点合法范围
        if (!isEntryFakSlippageValid(entryFakSlippage)) return false
        // 入场最高价应高于止损价（否则一进就止损）
        if (bracketMaxEntryPrice <= stopPrice) return false
        // 止损 pWin 应低于持有 pWin（否则没有"持有"区间）
        if (stopProb >= holdToSettlePwin) return false
        // V53 新增不变量：
        // (1) TP1 跳过阈值不应高于 TP2 跳过阈值（更早的止盈档位"放手得更早"，递增更严格的持有标准）
        if (tp1HoldPwin > tp2HoldPwin) return false
        // (2) 强制平仓窗口必须严格小于持有到结算窗口（否则在 hold 阶段强平窗口已生效，逻辑矛盾）
        if (forceExitBeforeSettleSeconds >= holdToSettleSeconds) return false
        // (3) 止损概率必须严格低于入场概率（避免入场即止损的死循环）
        if (stopProb >= bracketEntryProb) return false
        return true
    }

    /** 新增概率模式风控参数校验：覆盖入场安全比、高价保护、退出管理、影线评分。 */
    private fun isProbabilityRiskParamsValid(
        minSafeRatio: BigDecimal,
        minSafeRatioUp: BigDecimal,
        minSafeRatioDown: BigDecimal,
        highPriceThreshold: BigDecimal,
        highPriceMinPWin: BigDecimal,
        highPriceMinSafeRatio: BigDecimal,
        maxLossPct: BigDecimal,
        exitPWin: BigDecimal,
        exitSafeRatio: BigDecimal,
        exitConfirmTicks: Int,
        takeProfitDelta1: BigDecimal,
        takeProfitSellPct1: BigDecimal,
        takeProfitBid2: BigDecimal,
        takeProfitSellPct2: BigDecimal,
        exitPollIntervalMs: Int,
        wickLookbackMinutes: Int,
        wickMinBodyRatio: BigDecimal,
        wickRejectionRatio: BigDecimal,
        wickMaWindow: Int,
        wickEntryBlockScore: Int,
        wickExitScore: Int,
        wickHoldProfitScore: Int,
        wickVolumeSpikeRatio: BigDecimal,
        wickMinTicksPerCandle: Int,
        wickMinRangeSigmaRatio: BigDecimal,
        wickClosePositionUpMax: BigDecimal,
        wickClosePositionDownMin: BigDecimal,
        maxHoldTp1DelaySeconds: Int,
        holdTp1PeakDrawdown: BigDecimal,
        maxEntrySpread: BigDecimal,
        maxOrderbookAgeMs: Int,
        maxPriceAgeMs: Int,
        minRemainingSeconds: Int,
        maxRemainingSeconds: Int,
        wickFilterMode: String,
        minExitBidDepthUsdc: BigDecimal,
        maxExitSpread: BigDecimal,
        trailingStartDelta: BigDecimal,
        trailingDrawdown: BigDecimal,
        trailingSellPct: BigDecimal,
        maxOrdersPerDay: Int?,
        maxConsecutiveLosses: Int?,
        pauseAfterLossMinutes: Int
    ): Boolean {
        val zero = BigDecimal.ZERO
        val one = BigDecimal.ONE
        if (minSafeRatio < zero || minSafeRatioUp < zero || minSafeRatioDown < zero) return false
        if (highPriceThreshold <= zero || highPriceThreshold > one) return false
        if (highPriceMinPWin <= zero || highPriceMinPWin > one) return false
        if (highPriceMinSafeRatio < zero) return false
        if (maxLossPct <= zero || maxLossPct >= one) return false
        if (exitPWin < zero || exitPWin > one) return false
        if (exitSafeRatio < zero) return false
        if (exitConfirmTicks < 1) return false
        if (takeProfitDelta1 < zero || takeProfitDelta1 > one) return false
        if (takeProfitSellPct1 <= zero || takeProfitSellPct1 > one) return false
        if (takeProfitBid2 <= zero || takeProfitBid2 > one) return false
        if (takeProfitSellPct2 <= zero || takeProfitSellPct2 > one) return false
        if (exitPollIntervalMs < 500) return false
        if (wickLookbackMinutes !in 1..10) return false
        if (wickMinBodyRatio < zero || wickMinBodyRatio > one) return false
        if (wickRejectionRatio < zero || wickRejectionRatio > one) return false
        if (wickMaWindow !in 1..20) return false
        if (wickEntryBlockScore !in 0..100) return false
        if (wickExitScore !in 0..100) return false
        if (wickHoldProfitScore !in 0..100) return false
        if (wickVolumeSpikeRatio < one) return false
        if (wickMinTicksPerCandle !in 0..60) return false
        if (wickMinRangeSigmaRatio < zero) return false
        if (wickClosePositionUpMax < zero || wickClosePositionUpMax > one) return false
        if (wickClosePositionDownMin < zero || wickClosePositionDownMin > one) return false
        if (wickClosePositionUpMax >= wickClosePositionDownMin) return false
        if (maxHoldTp1DelaySeconds < 0) return false
        if (holdTp1PeakDrawdown < zero || holdTp1PeakDrawdown > one) return false
        if (maxEntrySpread < zero || maxEntrySpread > one) return false
        if (maxOrderbookAgeMs < 0 || (maxOrderbookAgeMs in 1..499)) return false
        if (maxPriceAgeMs < 0 || (maxPriceAgeMs in 1..499)) return false
        if (minRemainingSeconds < 0 || maxRemainingSeconds < 0) return false
        if (minRemainingSeconds > 0 && maxRemainingSeconds > 0 && minRemainingSeconds > maxRemainingSeconds) return false
        if (wickFilterMode != "OFF" && wickFilterMode != "SHADOW" && wickFilterMode != "ENFORCE") return false
        if (minExitBidDepthUsdc < zero) return false
        if (maxExitSpread < zero || maxExitSpread > one) return false
        if (trailingStartDelta < zero || trailingStartDelta > one) return false
        if (trailingDrawdown < zero || trailingDrawdown > one) return false
        if (trailingSellPct <= zero || trailingSellPct > one) return false
        if (maxOrdersPerDay != null && maxOrdersPerDay < 0) return false
        if (maxConsecutiveLosses != null && maxConsecutiveLosses < 0) return false
        if (pauseAfterLossMinutes < 0) return false
        return true
    }

    private fun normalizeWickFilterMode(mode: String?): String {
        val normalized = (mode ?: "SHADOW").trim().uppercase()
        return if (normalized == "OFF" || normalized == "SHADOW" || normalized == "ENFORCE") normalized else "SHADOW"
    }

    private fun generateStrategyName(marketSlugPrefix: String): String {
        val suffix = Instant.now().atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        return "加密价差策略-${marketSlugPrefix}-$suffix"
    }

    /** 尾盘价差模式（TAIL_DIFF）解析后的字段集合，类型与实体一致 */
    private data class TailDiffResolved(
        val direction: Int,
        val windowStartSeconds: Int,
        val windowEndSeconds: Int,
        val minRemainingSeconds: Int,
        val confirmTicks: Int,
        val minPrice: BigDecimal,
        val maxPrice: BigDecimal,
        val hardMaxPrice: BigDecimal,
        val minModelProb: BigDecimal,
        val minEdge: BigDecimal,
        val costBuffer: BigDecimal,
        val minDiffSigma: BigDecimal,
        val modelProbSource: String,
        val statsMinSamples: Int,
        val statsLookbackDays: Int,
        val statsDataSource: String,
        val maxSpread: BigDecimal,
        val depthMultiplier: BigDecimal,
        val maxOrderbookAgeMs: Int,
        val maxPriceAgeMs: Int,
        val reverseVelocityWindowSeconds: Int,
        val maxReverseVelocitySigma: BigDecimal,
        val weightDiff: Int,
        val weightTime: Int,
        val weightOddsUnderprice: Int,
        val weightOddsLag: Int,
        val weightHistory: Int,
        val weightBook: Int,
        val weightData: Int,
        val minEntryScore: Int,
        val premiumScore: Int,
        val topScore: Int,
        val baseAmount: BigDecimal,
        val tierNormalMult: BigDecimal,
        val tierPremiumMult: BigDecimal,
        val tierTopMult: BigDecimal,
        val maxAmountPerOrder: BigDecimal,
        val exitPresetNormalJson: String?,
        val exitPresetPremiumJson: String?,
        val exitPresetTopJson: String?,
        val dailyLossLimitUsdc: BigDecimal?,
        val consecLossPauseCount: Int,
        val consecLossStopCount: Int,
        val entrySegmentsJson: String?
    )

    private fun normalizeTailDiffSource(raw: String?): String {
        val v = (raw ?: "HYBRID").trim().uppercase()
        return if (v == "HYBRID" || v == "STATS" || v == "FALLBACK") v else "HYBRID"
    }

    private fun normalizeTailDiffDataSource(raw: String?): String {
        val v = (raw ?: "BINANCE").trim().uppercase()
        return if (v == "BINANCE" || v == "POLYMARKET") v else "BINANCE"
    }

    /** 创建场景：缺省走默认值（与 V62 SQL 默认一致） */
    private fun resolveTailDiffCreate(r: CryptoTailStrategyCreateRequest): TailDiffResolved = TailDiffResolved(
        direction = r.tailDiffDirection ?: 0,
        windowStartSeconds = r.tailDiffWindowStartSeconds ?: 150,
        windowEndSeconds = r.tailDiffWindowEndSeconds ?: 60,
        minRemainingSeconds = r.tailDiffMinRemainingSeconds ?: 50,
        confirmTicks = r.tailDiffConfirmTicks ?: 2,
        minPrice = r.tailDiffMinPrice?.toSafeBigDecimal() ?: BigDecimal("0.88"),
        maxPrice = r.tailDiffMaxPrice?.toSafeBigDecimal() ?: BigDecimal("0.93"),
        hardMaxPrice = r.tailDiffHardMaxPrice?.toSafeBigDecimal() ?: BigDecimal("0.94"),
        minModelProb = r.tailDiffMinModelProb?.toSafeBigDecimal() ?: BigDecimal("0.95"),
        minEdge = r.tailDiffMinEdge?.toSafeBigDecimal() ?: BigDecimal("0.025"),
        costBuffer = r.tailDiffCostBuffer?.toSafeBigDecimal() ?: BigDecimal("0.01"),
        minDiffSigma = r.tailDiffMinDiffSigma?.toSafeBigDecimal() ?: BigDecimal("1.8"),
        modelProbSource = normalizeTailDiffSource(r.tailDiffModelProbSource),
        statsMinSamples = r.tailDiffStatsMinSamples ?: 50,
        statsLookbackDays = r.tailDiffStatsLookbackDays ?: 180,
        statsDataSource = normalizeTailDiffDataSource(r.tailDiffStatsDataSource),
        maxSpread = r.tailDiffMaxSpread?.toSafeBigDecimal() ?: BigDecimal("0.02"),
        depthMultiplier = r.tailDiffDepthMultiplier?.toSafeBigDecimal() ?: BigDecimal("3.0"),
        maxOrderbookAgeMs = r.tailDiffMaxOrderbookAgeMs ?: 2000,
        maxPriceAgeMs = r.tailDiffMaxPriceAgeMs ?: 2000,
        reverseVelocityWindowSeconds = r.tailDiffReverseVelocityWindowSeconds ?: 10,
        maxReverseVelocitySigma = r.tailDiffMaxReverseVelocitySigma?.toSafeBigDecimal() ?: BigDecimal("0.30"),
        weightDiff = r.tailDiffWeightDiff ?: 25,
        weightTime = r.tailDiffWeightTime ?: 15,
        weightOddsUnderprice = r.tailDiffWeightOddsUnderprice ?: 20,
        weightOddsLag = r.tailDiffWeightOddsLag ?: 10,
        weightHistory = r.tailDiffWeightHistory ?: 15,
        weightBook = r.tailDiffWeightBook ?: 10,
        weightData = r.tailDiffWeightData ?: 5,
        minEntryScore = r.tailDiffMinEntryScore ?: 70,
        premiumScore = r.tailDiffPremiumScore ?: 80,
        topScore = r.tailDiffTopScore ?: 90,
        baseAmount = r.tailDiffBaseAmount?.toSafeBigDecimal() ?: BigDecimal.ONE,
        tierNormalMult = r.tailDiffTierNormalMult?.toSafeBigDecimal() ?: BigDecimal("1.0"),
        tierPremiumMult = r.tailDiffTierPremiumMult?.toSafeBigDecimal() ?: BigDecimal("1.5"),
        tierTopMult = r.tailDiffTierTopMult?.toSafeBigDecimal() ?: BigDecimal("2.0"),
        maxAmountPerOrder = r.tailDiffMaxAmountPerOrder?.toSafeBigDecimal() ?: BigDecimal("5"),
        exitPresetNormalJson = r.tailDiffExitPresetNormalJson?.takeIf { it.isNotBlank() },
        exitPresetPremiumJson = r.tailDiffExitPresetPremiumJson?.takeIf { it.isNotBlank() },
        exitPresetTopJson = r.tailDiffExitPresetTopJson?.takeIf { it.isNotBlank() },
        dailyLossLimitUsdc = r.tailDiffDailyLossLimitUsdc?.takeIf { it.isNotBlank() }?.toSafeBigDecimal(),
        consecLossPauseCount = r.tailDiffConsecLossPauseCount ?: 2,
        consecLossStopCount = r.tailDiffConsecLossStopCount ?: 3,
        entrySegmentsJson = r.tailDiffEntrySegmentsJson?.takeIf { it.isNotBlank() }
    )

    /** 更新场景：null 字段保留 existing */
    private fun resolveTailDiffUpdate(r: CryptoTailStrategyUpdateRequest, e: CryptoTailStrategy): TailDiffResolved = TailDiffResolved(
        direction = r.tailDiffDirection ?: e.tailDiffDirection,
        windowStartSeconds = r.tailDiffWindowStartSeconds ?: e.tailDiffWindowStartSeconds,
        windowEndSeconds = r.tailDiffWindowEndSeconds ?: e.tailDiffWindowEndSeconds,
        minRemainingSeconds = r.tailDiffMinRemainingSeconds ?: e.tailDiffMinRemainingSeconds,
        confirmTicks = r.tailDiffConfirmTicks ?: e.tailDiffConfirmTicks,
        minPrice = r.tailDiffMinPrice?.toSafeBigDecimal() ?: e.tailDiffMinPrice,
        maxPrice = r.tailDiffMaxPrice?.toSafeBigDecimal() ?: e.tailDiffMaxPrice,
        hardMaxPrice = r.tailDiffHardMaxPrice?.toSafeBigDecimal() ?: e.tailDiffHardMaxPrice,
        minModelProb = r.tailDiffMinModelProb?.toSafeBigDecimal() ?: e.tailDiffMinModelProb,
        minEdge = r.tailDiffMinEdge?.toSafeBigDecimal() ?: e.tailDiffMinEdge,
        costBuffer = r.tailDiffCostBuffer?.toSafeBigDecimal() ?: e.tailDiffCostBuffer,
        minDiffSigma = r.tailDiffMinDiffSigma?.toSafeBigDecimal() ?: e.tailDiffMinDiffSigma,
        modelProbSource = r.tailDiffModelProbSource?.let { normalizeTailDiffSource(it) } ?: e.tailDiffModelProbSource,
        statsMinSamples = r.tailDiffStatsMinSamples ?: e.tailDiffStatsMinSamples,
        statsLookbackDays = r.tailDiffStatsLookbackDays ?: e.tailDiffStatsLookbackDays,
        statsDataSource = r.tailDiffStatsDataSource?.let { normalizeTailDiffDataSource(it) } ?: e.tailDiffStatsDataSource,
        maxSpread = r.tailDiffMaxSpread?.toSafeBigDecimal() ?: e.tailDiffMaxSpread,
        depthMultiplier = r.tailDiffDepthMultiplier?.toSafeBigDecimal() ?: e.tailDiffDepthMultiplier,
        maxOrderbookAgeMs = r.tailDiffMaxOrderbookAgeMs ?: e.tailDiffMaxOrderbookAgeMs,
        maxPriceAgeMs = r.tailDiffMaxPriceAgeMs ?: e.tailDiffMaxPriceAgeMs,
        reverseVelocityWindowSeconds = r.tailDiffReverseVelocityWindowSeconds ?: e.tailDiffReverseVelocityWindowSeconds,
        maxReverseVelocitySigma = r.tailDiffMaxReverseVelocitySigma?.toSafeBigDecimal() ?: e.tailDiffMaxReverseVelocitySigma,
        weightDiff = r.tailDiffWeightDiff ?: e.tailDiffWeightDiff,
        weightTime = r.tailDiffWeightTime ?: e.tailDiffWeightTime,
        weightOddsUnderprice = r.tailDiffWeightOddsUnderprice ?: e.tailDiffWeightOddsUnderprice,
        weightOddsLag = r.tailDiffWeightOddsLag ?: e.tailDiffWeightOddsLag,
        weightHistory = r.tailDiffWeightHistory ?: e.tailDiffWeightHistory,
        weightBook = r.tailDiffWeightBook ?: e.tailDiffWeightBook,
        weightData = r.tailDiffWeightData ?: e.tailDiffWeightData,
        minEntryScore = r.tailDiffMinEntryScore ?: e.tailDiffMinEntryScore,
        premiumScore = r.tailDiffPremiumScore ?: e.tailDiffPremiumScore,
        topScore = r.tailDiffTopScore ?: e.tailDiffTopScore,
        baseAmount = r.tailDiffBaseAmount?.toSafeBigDecimal() ?: e.tailDiffBaseAmount,
        tierNormalMult = r.tailDiffTierNormalMult?.toSafeBigDecimal() ?: e.tailDiffTierNormalMult,
        tierPremiumMult = r.tailDiffTierPremiumMult?.toSafeBigDecimal() ?: e.tailDiffTierPremiumMult,
        tierTopMult = r.tailDiffTierTopMult?.toSafeBigDecimal() ?: e.tailDiffTierTopMult,
        maxAmountPerOrder = r.tailDiffMaxAmountPerOrder?.toSafeBigDecimal() ?: e.tailDiffMaxAmountPerOrder,
        // 可空字段三态语义（与前端约定一致）：字段缺失(null)=保留旧值；空串=显式清空(置 NULL/默认)；非空=更新。
        exitPresetNormalJson = resolveNullableStringUpdate(r.tailDiffExitPresetNormalJson, e.tailDiffExitPresetNormalJson),
        exitPresetPremiumJson = resolveNullableStringUpdate(r.tailDiffExitPresetPremiumJson, e.tailDiffExitPresetPremiumJson),
        exitPresetTopJson = resolveNullableStringUpdate(r.tailDiffExitPresetTopJson, e.tailDiffExitPresetTopJson),
        dailyLossLimitUsdc = resolveNullableBigDecimalUpdate(r.tailDiffDailyLossLimitUsdc, e.tailDiffDailyLossLimitUsdc),
        consecLossPauseCount = r.tailDiffConsecLossPauseCount ?: e.tailDiffConsecLossPauseCount,
        consecLossStopCount = r.tailDiffConsecLossStopCount ?: e.tailDiffConsecLossStopCount,
        entrySegmentsJson = resolveNullableStringUpdate(r.tailDiffEntrySegmentsJson, e.tailDiffEntrySegmentsJson)
    )

    /** 可空字符串字段三态：null=保留旧值；空串=清空(null)；非空=trim 后更新。 */
    private fun resolveNullableStringUpdate(requestValue: String?, existing: String?): String? = when {
        requestValue == null -> existing
        requestValue.isBlank() -> null
        else -> requestValue.trim()
    }

    /** 可空 BigDecimal 字段三态：null=保留旧值；空串=清空(null)；非空=解析更新。 */
    private fun resolveNullableBigDecimalUpdate(requestValue: String?, existing: BigDecimal?): BigDecimal? = when {
        requestValue == null -> existing
        requestValue.isBlank() -> null
        else -> requestValue.toSafeBigDecimal()
    }

    /** TAIL_DIFF 参数校验：价格区间、概率/边际、权重总和、分层阈值递增、方向枚举等 */
    private fun isTailDiffParamsValid(td: TailDiffResolved): Boolean {
        val zero = BigDecimal.ZERO
        val one = BigDecimal.ONE
        if (td.direction !in 0..2) return false
        if (td.windowStartSeconds <= td.windowEndSeconds) return false
        if (td.windowEndSeconds < 0 || td.minRemainingSeconds < 0) return false
        if (td.confirmTicks < 0) return false
        // 价格区间：0 < minPrice <= maxPrice <= hardMaxPrice <= 1
        if (td.minPrice <= zero || td.minPrice > one) return false
        if (td.maxPrice < td.minPrice || td.maxPrice > one) return false
        if (td.hardMaxPrice < td.maxPrice || td.hardMaxPrice > one) return false
        if (td.minModelProb <= zero || td.minModelProb > one) return false
        if (td.minEdge < zero || td.minEdge >= one) return false
        if (td.costBuffer < zero || td.costBuffer >= one) return false
        if (td.minDiffSigma < zero) return false
        if (td.statsMinSamples < 0 || td.statsLookbackDays <= 0) return false
        if (td.maxSpread <= zero || td.maxSpread >= one) return false
        if (td.depthMultiplier <= zero) return false
        if (td.maxOrderbookAgeMs <= 0 || td.maxPriceAgeMs <= 0) return false
        if (td.reverseVelocityWindowSeconds <= 0 || td.maxReverseVelocitySigma < zero) return false
        // 权重非负且总和必须为 100
        val weights = listOf(td.weightDiff, td.weightTime, td.weightOddsUnderprice, td.weightOddsLag, td.weightHistory, td.weightBook, td.weightData)
        if (weights.any { it < 0 }) return false
        if (weights.sum() != 100) return false
        // 分层阈值：0 < minEntryScore <= premiumScore <= topScore <= 100
        if (td.minEntryScore <= 0 || td.minEntryScore > 100) return false
        if (td.premiumScore < td.minEntryScore || td.premiumScore > 100) return false
        if (td.topScore < td.premiumScore || td.topScore > 100) return false
        if (td.baseAmount <= zero || td.maxAmountPerOrder <= zero) return false
        if (td.tierNormalMult <= zero || td.tierPremiumMult <= zero || td.tierTopMult <= zero) return false
        td.dailyLossLimitUsdc?.let { if (it < zero) return false }
        if (td.consecLossPauseCount < 0 || td.consecLossStopCount < 0) return false
        if (!entrySegmentResolver.isValid(td.entrySegmentsJson)) return false
        // 三档退出预设 JSON：结构 + 边界校验（非法直接判参数无效，不再静默回退默认档）
        if (!exitPresetResolver.isValid(td.exitPresetNormalJson)) return false
        if (!exitPresetResolver.isValid(td.exitPresetPremiumJson)) return false
        if (!exitPresetResolver.isValid(td.exitPresetTopJson)) return false
        return true
    }

    private fun entityToDto(e: CryptoTailStrategy, lastTriggerAt: Long?): CryptoTailStrategyDto {
        val strategyId = e.id ?: 0L
        val totalPnl = triggerRepository.sumRealizedPnlByStrategyId(strategyId)
        val settledCount = triggerRepository.countResolvedByStrategyId(strategyId)
        val winCount = triggerRepository.countWinsByStrategyId(strategyId)
        val winRateStr = if (settledCount > 0L) {
            BigDecimal(winCount).divide(BigDecimal(settledCount), 4, java.math.RoundingMode.HALF_UP).toPlainString()
        } else null
        return CryptoTailStrategyDto(
            id = strategyId,
            accountId = e.accountId,
            name = e.name,
            marketSlugPrefix = e.marketSlugPrefix,
            marketTitle = null,
            intervalSeconds = e.intervalSeconds,
            windowStartSeconds = e.windowStartSeconds,
            windowEndSeconds = e.windowEndSeconds,
            minPrice = e.minPrice.toPlainString(),
            maxPrice = e.maxPrice.toPlainString(),
            amountMode = e.amountMode,
            amountValue = e.amountValue.toPlainString(),
            spreadMode = e.spreadMode.name,
            spreadValue = e.spreadValue?.toPlainString(),
            spreadDirection = e.spreadDirection.name,
            enabled = e.enabled,
            barrierEnabled = e.barrierEnabled,
            entryProb = e.entryProb.toPlainString(),
            entryEdge = e.entryEdge.toPlainString(),
            maxEntryPrice = e.maxEntryPrice.toPlainString(),
            costBuffer = e.costBuffer.toPlainString(),
            barrierMinMarketProb = e.barrierMinMarketProb.toPlainString(),
            sigmaScale = e.sigmaScale.toPlainString(),
            dailyLossLimitUsdc = e.dailyLossLimitUsdc?.toPlainString(),
            maxConcurrentPositions = e.maxConcurrentPositions,
            takerFeeBps = e.takerFeeBps,
            makerRebateBps = e.makerRebateBps,
            gasCostUsdc = e.gasCostUsdc.toPlainString(),
            entryOrderType = e.entryOrderType,
            entryFakSlippage = e.entryFakSlippage.toPlainString(),
            exitFakSlippage = e.exitFakSlippage.toPlainString(),
            makerPriceOffset = e.makerPriceOffset.toPlainString(),
            makerCancelBeforeSettleSeconds = e.makerCancelBeforeSettleSeconds,
            makerFallbackTaker = e.makerFallbackTaker,
            calibrationGateEnabled = e.calibrationGateEnabled,
            probeAmountUsdc = e.probeAmountUsdc.toPlainString(),
            calibrationMinSamples = e.calibrationMinSamples,
            calibrationMaxError = e.calibrationMaxError.toPlainString(),
            sigmaMethod = e.sigmaMethod,
            ewmaLambda = e.ewmaLambda.toPlainString(),
            kellyEnabled = e.kellyEnabled,
            kellyFraction = e.kellyFraction.toPlainString(),
            allowDuplicateMarketPosition = e.allowDuplicateMarketPosition,
            enableStrongGapBoost = e.enableStrongGapBoost,
            strongGapBoostShadow = e.strongGapBoostShadow,
            strongGapMinPwin = e.strongGapMinPwin.toPlainString(),
            strongGapMinSafeRatio = e.strongGapMinSafeRatio.toPlainString(),
            strongGapStakeMultiplier = e.strongGapStakeMultiplier.toPlainString(),
            ultraGapMinPwin = e.ultraGapMinPwin.toPlainString(),
            ultraGapMinSafeRatio = e.ultraGapMinSafeRatio.toPlainString(),
            ultraGapStakeMultiplier = e.ultraGapStakeMultiplier.toPlainString(),
            maxStrongGapStakeMultiplier = e.maxStrongGapStakeMultiplier.toPlainString(),
            maxBoostedAmountUsdc = e.maxBoostedAmountUsdc?.toPlainString(),
            maxBoostedPeriodExposureUsdc = e.maxBoostedPeriodExposureUsdc?.toPlainString(),
            allowBoostWithKelly = e.allowBoostWithKelly,
            mode = e.mode.value,
            bracketEntryProb = e.bracketEntryProb.toPlainString(),
            bracketEntryEdge = e.bracketEntryEdge.toPlainString(),
            bracketMaxEntryPrice = e.bracketMaxEntryPrice.toPlainString(),
            tp1Price = e.tp1Price.toPlainString(),
            tp1Ratio = e.tp1Ratio.toPlainString(),
            tp1HoldPwin = e.tp1HoldPwin.toPlainString(),
            tp2Price = e.tp2Price.toPlainString(),
            tp2Ratio = e.tp2Ratio.toPlainString(),
            tp2HoldPwin = e.tp2HoldPwin.toPlainString(),
            holdToSettlePwin = e.holdToSettlePwin.toPlainString(),
            holdToSettleSeconds = e.holdToSettleSeconds,
            stopProb = e.stopProb.toPlainString(),
            stopPrice = e.stopPrice.toPlainString(),
            forceExitBeforeSettleSeconds = e.forceExitBeforeSettleSeconds,
            exitOrderType = e.exitOrderType,
            minSafeRatio = e.minSafeRatio.toPlainString(),
            minSafeRatioUp = e.minSafeRatioUp.toPlainString(),
            minSafeRatioDown = e.minSafeRatioDown.toPlainString(),
            highPriceThreshold = e.highPriceThreshold.toPlainString(),
            highPriceMinPWin = e.highPriceMinPWin.toPlainString(),
            highPriceMinSafeRatio = e.highPriceMinSafeRatio.toPlainString(),
            enableExitManager = e.enableExitManager,
            maxLossPct = e.maxLossPct.toPlainString(),
            exitPWin = e.exitPWin.toPlainString(),
            exitSafeRatio = e.exitSafeRatio.toPlainString(),
            exitConfirmTicks = e.exitConfirmTicks,
            takeProfitDelta1 = e.takeProfitDelta1.toPlainString(),
            takeProfitSellPct1 = e.takeProfitSellPct1.toPlainString(),
            takeProfitBid2 = e.takeProfitBid2.toPlainString(),
            takeProfitSellPct2 = e.takeProfitSellPct2.toPlainString(),
            enableSmartHardStop = e.enableSmartHardStop,
            emergencyExitOnModelFlip = e.emergencyExitOnModelFlip,
            emergencyExitOnGapFlip = e.emergencyExitOnGapFlip,
            exitPollIntervalMs = e.exitPollIntervalMs,
            enableWickFilter = e.enableWickFilter,
            wickFilterMode = e.wickFilterMode,
            wickLookbackMinutes = e.wickLookbackMinutes,
            wickMinBodyRatio = e.wickMinBodyRatio.toPlainString(),
            wickRejectionRatio = e.wickRejectionRatio.toPlainString(),
            wickMaWindow = e.wickMaWindow,
            wickEntryBlockScore = e.wickEntryBlockScore,
            wickExitScore = e.wickExitScore,
            wickHoldProfitScore = e.wickHoldProfitScore,
            wickUseBinanceVolume = e.wickUseBinanceVolume,
            wickVolumeSpikeRatio = e.wickVolumeSpikeRatio.toPlainString(),
            wickMinTicksPerCandle = e.wickMinTicksPerCandle,
            wickMinRangeSigmaRatio = e.wickMinRangeSigmaRatio.toPlainString(),
            wickClosePositionUpMax = e.wickClosePositionUpMax.toPlainString(),
            wickClosePositionDownMin = e.wickClosePositionDownMin.toPlainString(),
            maxHoldTp1DelaySeconds = e.maxHoldTp1DelaySeconds,
            holdTp1PeakDrawdown = e.holdTp1PeakDrawdown.toPlainString(),
            maxEntrySpread = e.maxEntrySpread.toPlainString(),
            maxOrderbookAgeMs = e.maxOrderbookAgeMs,
            maxPriceAgeMs = e.maxPriceAgeMs,
            minRemainingSeconds = e.minRemainingSeconds,
            maxRemainingSeconds = e.maxRemainingSeconds,
            minExitBidDepthUsdc = e.minExitBidDepthUsdc.toPlainString(),
            maxExitSpread = e.maxExitSpread.toPlainString(),
            enableTrailingStop = e.enableTrailingStop,
            trailingStartDelta = e.trailingStartDelta.toPlainString(),
            trailingDrawdown = e.trailingDrawdown.toPlainString(),
            trailingSellPct = e.trailingSellPct.toPlainString(),
            maxOrdersPerDay = e.maxOrdersPerDay,
            maxConsecutiveLosses = e.maxConsecutiveLosses,
            pauseAfterLossMinutes = e.pauseAfterLossMinutes,
            lastTriggerAt = lastTriggerAt,
            totalRealizedPnl = totalPnl?.toPlainString(),
            settledCount = settledCount,
            winCount = winCount,
            winRate = winRateStr,
            tailDiffDirection = e.tailDiffDirection,
            tailDiffWindowStartSeconds = e.tailDiffWindowStartSeconds,
            tailDiffWindowEndSeconds = e.tailDiffWindowEndSeconds,
            tailDiffMinRemainingSeconds = e.tailDiffMinRemainingSeconds,
            tailDiffConfirmTicks = e.tailDiffConfirmTicks,
            tailDiffMinPrice = e.tailDiffMinPrice.toPlainString(),
            tailDiffMaxPrice = e.tailDiffMaxPrice.toPlainString(),
            tailDiffHardMaxPrice = e.tailDiffHardMaxPrice.toPlainString(),
            tailDiffMinModelProb = e.tailDiffMinModelProb.toPlainString(),
            tailDiffMinEdge = e.tailDiffMinEdge.toPlainString(),
            tailDiffCostBuffer = e.tailDiffCostBuffer.toPlainString(),
            tailDiffMinDiffSigma = e.tailDiffMinDiffSigma.toPlainString(),
            tailDiffModelProbSource = e.tailDiffModelProbSource,
            tailDiffStatsMinSamples = e.tailDiffStatsMinSamples,
            tailDiffStatsLookbackDays = e.tailDiffStatsLookbackDays,
            tailDiffStatsDataSource = e.tailDiffStatsDataSource,
            tailDiffMaxSpread = e.tailDiffMaxSpread.toPlainString(),
            tailDiffDepthMultiplier = e.tailDiffDepthMultiplier.toPlainString(),
            tailDiffMaxOrderbookAgeMs = e.tailDiffMaxOrderbookAgeMs,
            tailDiffMaxPriceAgeMs = e.tailDiffMaxPriceAgeMs,
            tailDiffReverseVelocityWindowSeconds = e.tailDiffReverseVelocityWindowSeconds,
            tailDiffMaxReverseVelocitySigma = e.tailDiffMaxReverseVelocitySigma.toPlainString(),
            tailDiffWeightDiff = e.tailDiffWeightDiff,
            tailDiffWeightTime = e.tailDiffWeightTime,
            tailDiffWeightOddsUnderprice = e.tailDiffWeightOddsUnderprice,
            tailDiffWeightOddsLag = e.tailDiffWeightOddsLag,
            tailDiffWeightHistory = e.tailDiffWeightHistory,
            tailDiffWeightBook = e.tailDiffWeightBook,
            tailDiffWeightData = e.tailDiffWeightData,
            tailDiffMinEntryScore = e.tailDiffMinEntryScore,
            tailDiffPremiumScore = e.tailDiffPremiumScore,
            tailDiffTopScore = e.tailDiffTopScore,
            tailDiffBaseAmount = e.tailDiffBaseAmount.toPlainString(),
            tailDiffTierNormalMult = e.tailDiffTierNormalMult.toPlainString(),
            tailDiffTierPremiumMult = e.tailDiffTierPremiumMult.toPlainString(),
            tailDiffTierTopMult = e.tailDiffTierTopMult.toPlainString(),
            tailDiffMaxAmountPerOrder = e.tailDiffMaxAmountPerOrder.toPlainString(),
            tailDiffExitPresetNormalJson = e.tailDiffExitPresetNormalJson,
            tailDiffExitPresetPremiumJson = e.tailDiffExitPresetPremiumJson,
            tailDiffExitPresetTopJson = e.tailDiffExitPresetTopJson,
            tailDiffDailyLossLimitUsdc = e.tailDiffDailyLossLimitUsdc?.toPlainString(),
            tailDiffConsecLossPauseCount = e.tailDiffConsecLossPauseCount,
            tailDiffConsecLossStopCount = e.tailDiffConsecLossStopCount,
            tailDiffEntrySegmentsJson = e.tailDiffEntrySegmentsJson,
            createdAt = e.createdAt,
            updatedAt = e.updatedAt
        )
    }

    private fun triggerToDto(t: CryptoTailStrategyTrigger): CryptoTailStrategyTriggerDto = CryptoTailStrategyTriggerDto(
        id = t.id ?: 0L,
        strategyId = t.strategyId,
        periodStartUnix = t.periodStartUnix,
        marketTitle = t.marketTitle,
        outcomeIndex = t.outcomeIndex,
        triggerPrice = t.triggerPrice.toPlainString(),
        amountUsdc = t.amountUsdc.toPlainString(),
        filledSize = t.filledSize?.toPlainString(),
        filledAmount = t.filledAmount?.toPlainString(),
        orderType = t.orderType,
        orderId = t.orderId,
        status = t.status,
        failReason = t.failReason,
        resolved = t.resolved,
        realizedPnl = t.realizedPnl?.toPlainString(),
        winnerOutcomeIndex = t.winnerOutcomeIndex,
        settledAt = t.settledAt,
        createdAt = t.createdAt,
        mode = t.mode.value,
        remainingSize = t.remainingSize?.toPlainString(),
        exitStatus = t.exitStatus
    )
}
