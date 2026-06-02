package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import com.wrbug.polymarketbot.entity.CryptoTailStrategyTrigger
import com.wrbug.polymarketbot.entity.CryptoTailTradeSnapshot
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.enums.SpreadMode
import com.wrbug.polymarketbot.enums.SpreadDirection
import com.wrbug.polymarketbot.repository.CryptoTailDecisionEventRepository
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
    private val eventPublisher: ApplicationEventPublisher
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
            val entryProb = request.entryProb?.toSafeBigDecimal() ?: BigDecimal("0.95")
            val entryEdge = request.entryEdge?.toSafeBigDecimal() ?: BigDecimal("0.02")
            val maxEntryPrice = request.maxEntryPrice?.toSafeBigDecimal() ?: BigDecimal("0.99")
            val costBuffer = request.costBuffer?.toSafeBigDecimal() ?: BigDecimal("0.02")
            val barrierMinMarketProb = request.barrierMinMarketProb?.toSafeBigDecimal() ?: BigDecimal.ZERO
            val sigmaScale = request.sigmaScale?.toSafeBigDecimal() ?: BigDecimal("1.2533")
            val dailyLossLimitUsdc = request.dailyLossLimitUsdc?.toSafeBigDecimal()
            val maxConcurrentPositions = request.maxConcurrentPositions
            val takerFeeBps = request.takerFeeBps ?: 0
            val makerRebateBps = request.makerRebateBps ?: 0
            val gasCostUsdc = request.gasCostUsdc?.toSafeBigDecimal() ?: BigDecimal.ZERO
            val entryOrderType = (request.entryOrderType ?: "FAK").trim().uppercase()
            val makerPriceOffset = request.makerPriceOffset?.toSafeBigDecimal() ?: BigDecimal.ZERO
            val makerCancelBeforeSettleSeconds = request.makerCancelBeforeSettleSeconds ?: 5
            val makerFallbackTaker = request.makerFallbackTaker ?: false
            val calibrationGateEnabled = request.calibrationGateEnabled ?: false
            val probeAmountUsdc = request.probeAmountUsdc?.toSafeBigDecimal() ?: BigDecimal.ONE
            val calibrationMinSamples = request.calibrationMinSamples ?: 30
            val calibrationMaxError = request.calibrationMaxError?.toSafeBigDecimal() ?: BigDecimal("0.10")
            val sigmaMethod = (request.sigmaMethod ?: "MAD").trim().uppercase()
            val ewmaLambda = request.ewmaLambda?.toSafeBigDecimal() ?: BigDecimal("0.94")
            val kellyEnabled = request.kellyEnabled ?: false
            val kellyFraction = request.kellyFraction?.toSafeBigDecimal() ?: BigDecimal("0.25")
            if (request.barrierEnabled && !isBarrierParamsValid(entryProb, entryEdge, maxEntryPrice, costBuffer, barrierMinMarketProb, sigmaScale, dailyLossLimitUsdc, maxConcurrentPositions, takerFeeBps, makerRebateBps, gasCostUsdc, entryOrderType, makerPriceOffset, makerCancelBeforeSettleSeconds, interval, probeAmountUsdc, calibrationMinSamples, calibrationMaxError, sigmaMethod, ewmaLambda, kellyFraction)) {
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
                barrierEnabled = request.barrierEnabled,
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
                kellyFraction = kellyFraction
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

            val newBarrierEnabled = request.barrierEnabled ?: existing.barrierEnabled
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
            if (newBarrierEnabled && !isBarrierParamsValid(newEntryProb, newEntryEdge, newMaxEntryPrice, newCostBuffer, newBarrierMinMarketProb, newSigmaScale, newDailyLossLimitUsdc, newMaxConcurrentPositions, newTakerFeeBps, newMakerRebateBps, newGasCostUsdc, newEntryOrderType, newMakerPriceOffset, newMakerCancelBeforeSettleSeconds, existing.intervalSeconds, newProbeAmountUsdc, newCalibrationMinSamples, newCalibrationMaxError, newSigmaMethod, newEwmaLambda, newKellyFraction)) {
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
                barrierEnabled = newBarrierEnabled,
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

    private fun generateStrategyName(marketSlugPrefix: String): String {
        val suffix = Instant.now().atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        return "加密价差策略-${marketSlugPrefix}-$suffix"
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
            lastTriggerAt = lastTriggerAt,
            totalRealizedPnl = totalPnl?.toPlainString(),
            settledCount = settledCount,
            winCount = winCount,
            winRate = winRateStr,
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
        createdAt = t.createdAt
    )
}
