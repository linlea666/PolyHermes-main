package com.wrbug.polymarketbot.controller.cryptotail

import com.wrbug.polymarketbot.dto.ApiResponse
import com.wrbug.polymarketbot.dto.ForensicsAggregateRequest
import com.wrbug.polymarketbot.dto.ForensicsAggregateResponse
import com.wrbug.polymarketbot.dto.ForensicsAggregateRow
import com.wrbug.polymarketbot.dto.ForensicsBackfillRequest
import com.wrbug.polymarketbot.dto.ForensicsBackfillResponse
import com.wrbug.polymarketbot.dto.ForensicsDto
import com.wrbug.polymarketbot.dto.ForensicsListRequest
import com.wrbug.polymarketbot.dto.ForensicsListResponse
import com.wrbug.polymarketbot.entity.CryptoTailTradeForensics
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.repository.CryptoTailTradeForensicsRepository
import com.wrbug.polymarketbot.service.cryptotail.CryptoTailTradeForensicsService
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.data.domain.PageRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 成交复盘因子分析接口：
 *  - /list      : 明细分页（多维过滤）
 *  - /aggregate : 一维分组聚合（赢/输单进场赔率·时段分布、止损过度体检、配置 A/B 等）
 *  - /aggregate2d : 二维分组聚合（热力图，如 进场赔率桶 × 剩余时间桶）
 *  - /backfill  : 从 durable trade_snapshot 重建复盘因子
 *  - /dimensions: 可用维度白名单
 */
@RestController
@RequestMapping("/api/crypto-tail-strategy/forensics")
class CryptoTailTradeForensicsController(
    private val forensicsRepository: CryptoTailTradeForensicsRepository,
    private val forensicsService: CryptoTailTradeForensicsService,
    private val messageSource: MessageSource
) {
    private val logger = LoggerFactory.getLogger(CryptoTailTradeForensicsController::class.java)

    @PostMapping("/list")
    fun list(@RequestBody request: ForensicsListRequest): ResponseEntity<ApiResponse<ForensicsListResponse>> {
        return try {
            val page = (request.page - 1).coerceAtLeast(0)
            val size = request.pageSize.coerceIn(1, 200)
            val result = forensicsRepository.search(
                strategyId = request.strategyId,
                marketSlug = request.marketSlug?.takeIf { it.isNotBlank() },
                intervalSeconds = request.intervalSeconds,
                outcomeCategory = request.outcomeCategory?.takeIf { it.isNotBlank() },
                onlySettled = request.onlySettled,
                startTs = request.startTs,
                endTs = request.endTs,
                pageable = PageRequest.of(page, size)
            )
            ResponseEntity.ok(
                ApiResponse.success(
                    ForensicsListResponse(list = result.content.map { it.toDto() }, total = result.totalElements)
                )
            )
        } catch (e: Exception) {
            logger.error("查询复盘因子明细失败: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }

    @PostMapping("/aggregate")
    fun aggregate(@RequestBody request: ForensicsAggregateRequest): ResponseEntity<ApiResponse<ForensicsAggregateResponse>> =
        doAggregate(request, twoDim = false)

    @PostMapping("/aggregate2d")
    fun aggregate2d(@RequestBody request: ForensicsAggregateRequest): ResponseEntity<ApiResponse<ForensicsAggregateResponse>> =
        doAggregate(request, twoDim = true)

    private fun doAggregate(request: ForensicsAggregateRequest, twoDim: Boolean): ResponseEntity<ApiResponse<ForensicsAggregateResponse>> {
        if (request.dim1.isBlank() || (twoDim && request.dim2.isNullOrBlank())) {
            return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_INVALID, messageSource = messageSource))
        }
        return try {
            val rows = forensicsService.aggregate(
                dim1 = request.dim1,
                dim2 = if (twoDim) request.dim2 else null,
                strategyId = request.strategyId,
                marketSlug = request.marketSlug?.takeIf { it.isNotBlank() },
                intervalSeconds = request.intervalSeconds,
                outcomeCategory = request.outcomeCategory?.takeIf { it.isNotBlank() },
                onlySettled = request.onlySettled,
                startTs = request.startTs,
                endTs = request.endTs
            ).map { it.toDto() }
            ResponseEntity.ok(
                ApiResponse.success(
                    ForensicsAggregateResponse(
                        dim1 = request.dim1,
                        dim2 = if (twoDim) request.dim2 else null,
                        rows = rows,
                        allowedDimensions = forensicsService.allowedDimensions().toList()
                    )
                )
            )
        } catch (e: IllegalArgumentException) {
            ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_INVALID, messageSource = messageSource))
        } catch (e: Exception) {
            logger.error("复盘因子聚合失败: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }

    @PostMapping("/backfill")
    fun backfill(@RequestBody request: ForensicsBackfillRequest): ResponseEntity<ApiResponse<ForensicsBackfillResponse>> {
        if (request.strategyId <= 0) {
            return ResponseEntity.ok(ApiResponse.error(ErrorCode.CRYPTO_TAIL_STRATEGY_NOT_FOUND, messageSource = messageSource))
        }
        return try {
            val processed = forensicsService.backfill(request.strategyId, request.startTs, request.endTs)
            ResponseEntity.ok(ApiResponse.success(ForensicsBackfillResponse(processed = processed)))
        } catch (e: Exception) {
            logger.error("复盘因子回填失败: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }

    @PostMapping("/dimensions")
    fun dimensions(): ResponseEntity<ApiResponse<List<String>>> =
        ResponseEntity.ok(ApiResponse.success(forensicsService.allowedDimensions().toList()))

    private fun rate(numerator: Long, denominator: Long): String {
        if (denominator <= 0) return "0"
        return BigDecimal(numerator).divide(BigDecimal(denominator), 4, RoundingMode.HALF_UP).toPlainString()
    }

    private fun CryptoTailTradeForensicsService.ForensicsAggRow.toDto(): ForensicsAggregateRow = ForensicsAggregateRow(
        key1 = key1,
        key2 = key2,
        count = count,
        wins = wins,
        directionCorrect = directionCorrect,
        cuts = cuts,
        wouldWin = wouldWin,
        reversedCount = reversedCount,
        recoveredCount = recoveredCount,
        winRate = rate(wins, count),
        directionAccuracy = rate(directionCorrect, count),
        cutRate = rate(cuts, count),
        cutButWouldWinRate = rate(wouldWin, cuts),
        reversalRate = rate(reversedCount, count),
        recoverRate = rate(recoveredCount, reversedCount),
        avgDiffSigma = avgDiffSigma?.toPlainString(),
        avgGapAbs = avgGapAbs?.toPlainString(),
        avgBestAsk = avgBestAsk?.toPlainString(),
        avgPwin = avgPwin?.toPlainString(),
        avgRetrace = avgRetrace?.toPlainString(),
        avgMaeOdds = avgMaeOdds?.toPlainString(),
        avgMfeOdds = avgMfeOdds?.toPlainString(),
        avgFirstReversalRemaining = avgFirstReversalRemaining?.toPlainString(),
        avgHoldSeconds = avgHoldSeconds?.toPlainString(),
        sumPnl = sumPnl?.toPlainString(),
        avgPnl = avgPnl?.toPlainString(),
        sumCutVsHold = sumCutVsHold?.toPlainString()
    )

    private fun CryptoTailTradeForensics.toDto(): ForensicsDto = ForensicsDto(
        id = id ?: 0L,
        strategyId = strategyId,
        accountId = accountId,
        marketSlug = marketSlug,
        intervalSeconds = intervalSeconds,
        periodStartUnix = periodStartUnix,
        triggerId = triggerId,
        mode = mode,
        outcomeIndex = outcomeIndex,
        entryTs = entryTs,
        entryRemainingSeconds = entryRemainingSeconds,
        entryOfficialTarget = entryOfficialTarget?.toPlainString(),
        entryCurrentPrice = entryCurrentPrice?.toPlainString(),
        entryGap = entryGap?.toPlainString(),
        entryGapAbs = entryGapAbs?.toPlainString(),
        entryGapPct = entryGapPct?.toPlainString(),
        entryDiffSigma = entryDiffSigma?.toPlainString(),
        entryPwin = entryPwin?.toPlainString(),
        entryModelSide = entryModelSide,
        entryBestBid = entryBestBid?.toPlainString(),
        entryBestAsk = entryBestAsk?.toPlainString(),
        entryFillPrice = entryFillPrice?.toPlainString(),
        entryWallHour = entryWallHour,
        entryDow = entryDow,
        entryDiffSigmaBucket = entryDiffSigmaBucket,
        entryOddsBucket = entryOddsBucket,
        entryRemainingBucket = entryRemainingBucket,
        fillVsBandDev = fillVsBandDev?.toPlainString(),
        requoteCount = requoteCount,
        submitLatencyMs = submitLatencyMs,
        entrySlippage = entrySlippage?.toPlainString(),
        leadReversed = leadReversed,
        firstReversalRemainingSeconds = firstReversalRemainingSeconds,
        troughSafeRatio = troughSafeRatio?.toPlainString(),
        troughGap = troughGap?.toPlainString(),
        maxDiffRetracePct = maxDiffRetracePct?.toPlainString(),
        minBestBid = minBestBid?.toPlainString(),
        peakBestBid = peakBestBid?.toPlainString(),
        reversalSampleCount = reversalSampleCount,
        recoveredAfterReversal = recoveredAfterReversal,
        maeOdds = maeOdds?.toPlainString(),
        mfeOdds = mfeOdds?.toPlainString(),
        maeSigma = maeSigma?.toPlainString(),
        mfeSigma = mfeSigma?.toPlainString(),
        exitKind = exitKind,
        exitReason = exitReason,
        wasCut = wasCut,
        exitPrice = exitPrice?.toPlainString(),
        exitSlippage = exitSlippage?.toPlainString(),
        exitExecutableDepthUsd = exitExecutableDepthUsd?.toPlainString(),
        holdSeconds = holdSeconds,
        settled = settled,
        won = won,
        winnerOutcomeIndex = winnerOutcomeIndex,
        finalOfficialTarget = finalOfficialTarget?.toPlainString(),
        finalCurrentPrice = finalCurrentPrice?.toPlainString(),
        finalGap = finalGap?.toPlainString(),
        realizedPnl = realizedPnl?.toPlainString(),
        wouldHaveWonIfHeld = wouldHaveWonIfHeld,
        counterfactualHoldPnl = counterfactualHoldPnl?.toPlainString(),
        cutVsHoldDelta = cutVsHoldDelta?.toPlainString(),
        cfgFingerprint = cfgFingerprint,
        cfgGapGateEnabled = cfgGapGateEnabled,
        directionCorrect = directionCorrect,
        outcomeCategory = outcomeCategory,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
