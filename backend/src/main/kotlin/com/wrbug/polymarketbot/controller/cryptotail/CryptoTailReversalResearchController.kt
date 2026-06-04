package com.wrbug.polymarketbot.controller.cryptotail

import com.wrbug.polymarketbot.dto.ApiResponse
import com.wrbug.polymarketbot.dto.PolymarketReversalBackfillRequest
import com.wrbug.polymarketbot.dto.PolymarketReversalBackfillResponse
import com.wrbug.polymarketbot.dto.ReversalBackfillRequest
import com.wrbug.polymarketbot.dto.ReversalBackfillResponse
import com.wrbug.polymarketbot.dto.ReversalResearchCsvResponse
import com.wrbug.polymarketbot.dto.ReversalResearchListRequest
import com.wrbug.polymarketbot.dto.ReversalResearchListResponse
import com.wrbug.polymarketbot.dto.ReversalStatDto
import com.wrbug.polymarketbot.entity.CryptoTailReversalStat
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.repository.CryptoTailReversalStatRepository
import com.wrbug.polymarketbot.service.cryptotail.reversal.CryptoTailPolymarketReversalHarvestService
import com.wrbug.polymarketbot.service.cryptotail.reversal.CryptoTailReversalHarvestService
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 历史反转率研究接口：
 *  - /backfill : 一键回填指定 (coin, interval, lookbackDays) 的反转统计（数据源 BINANCE）
 *  - /list     : 查询某维度下全部分桶
 *  - /export   : 导出 CSV（前端下载）
 */
@RestController
@RequestMapping("/api/crypto-tail-strategy/reversal")
class CryptoTailReversalResearchController(
    private val harvestService: CryptoTailReversalHarvestService,
    private val polymarketHarvestService: CryptoTailPolymarketReversalHarvestService,
    private val reversalStatRepository: CryptoTailReversalStatRepository,
    private val messageSource: MessageSource
) {
    private val logger = LoggerFactory.getLogger(CryptoTailReversalResearchController::class.java)

    private fun validCoin(coin: String): String? {
        val c = coin.trim().uppercase()
        return if (c == "BTC" || c == "ETH") c else null
    }

    @PostMapping("/backfill")
    fun backfill(@RequestBody request: ReversalBackfillRequest): ResponseEntity<ApiResponse<ReversalBackfillResponse>> {
        val coin = validCoin(request.coin)
        if (coin == null || (request.intervalSeconds != 300 && request.intervalSeconds != 900) || request.lookbackDays <= 0) {
            return ResponseEntity.ok(ApiResponse.error(ErrorCode.REVERSAL_RESEARCH_PARAM_INVALID, messageSource = messageSource))
        }
        return try {
            val summary = harvestService.backfill(coin, request.intervalSeconds, request.lookbackDays, request.samplingSeconds)
                ?: return ResponseEntity.ok(ApiResponse.error(ErrorCode.REVERSAL_RESEARCH_NO_DATA, messageSource = messageSource))
            ResponseEntity.ok(
                ApiResponse.success(
                    ReversalBackfillResponse(
                        coin = summary.coin,
                        intervalSeconds = summary.intervalSeconds,
                        lookbackDays = summary.lookbackDays,
                        samplingSeconds = summary.samplingSeconds,
                        periodsProcessed = summary.periodsProcessed,
                        observations = summary.observations,
                        bucketsWritten = summary.bucketsWritten
                    )
                )
            )
        } catch (e: Exception) {
            logger.error("反转回填失败: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_REVERSAL_RESEARCH_BACKFILL_FAILED, messageSource = messageSource))
        }
    }

    /**
     * Polymarket 历史反转回填（PoC）。
     * 失败/无命中不返回错误，而是返回 summary（periodsResolved=0），以便前端直观看到 PoC 结果——PoC 不阻塞。
     */
    @PostMapping("/backfill-polymarket")
    fun backfillPolymarket(@RequestBody request: PolymarketReversalBackfillRequest): ResponseEntity<ApiResponse<PolymarketReversalBackfillResponse>> {
        val coin = validCoin(request.coin)
        if (coin == null || (request.intervalSeconds != 300 && request.intervalSeconds != 900) || request.lookbackDays <= 0) {
            return ResponseEntity.ok(ApiResponse.error(ErrorCode.REVERSAL_RESEARCH_PARAM_INVALID, messageSource = messageSource))
        }
        return try {
            val summary = polymarketHarvestService.backfill(coin, request.intervalSeconds, request.lookbackDays, request.maxPeriods)
                ?: return ResponseEntity.ok(ApiResponse.error(ErrorCode.REVERSAL_RESEARCH_PARAM_INVALID, messageSource = messageSource))
            ResponseEntity.ok(
                ApiResponse.success(
                    PolymarketReversalBackfillResponse(
                        coin = summary.coin,
                        intervalSeconds = summary.intervalSeconds,
                        lookbackDays = summary.lookbackDays,
                        periodsRequested = summary.periodsRequested,
                        periodsResolved = summary.periodsResolved,
                        observations = summary.observations,
                        bucketsWritten = summary.bucketsWritten,
                        dataSource = summary.dataSource,
                        slugNotFound = summary.slugNotFound,
                        historyEmpty = summary.historyEmpty,
                        tooFewPoints = summary.tooFewPoints,
                        fetchError = summary.fetchError,
                        coverageCapped = summary.coverageCapped,
                        coverageDays = summary.coverageDays
                    )
                )
            )
        } catch (e: Exception) {
            // PoC 失败不阻塞：记录后返回失败码，但不抛出
            logger.error("Polymarket 反转回填失败: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_REVERSAL_RESEARCH_BACKFILL_FAILED, messageSource = messageSource))
        }
    }

    @PostMapping("/list")
    fun list(@RequestBody request: ReversalResearchListRequest): ResponseEntity<ApiResponse<ReversalResearchListResponse>> {
        val coin = validCoin(request.coin)
            ?: return ResponseEntity.ok(ApiResponse.error(ErrorCode.REVERSAL_RESEARCH_PARAM_INVALID, messageSource = messageSource))
        return try {
            val rows = reversalStatRepository
                .findByCoinAndIntervalSecondsAndLookbackDaysAndDataSourceOrderByOutcomeIndexAscDiffSigmaBucketAscRemainingBucketAsc(
                    coin, request.intervalSeconds, request.lookbackDays, request.dataSource.ifBlank { "BINANCE" }
                )
            val list = rows.map { it.toDto() }
            ResponseEntity.ok(ApiResponse.success(ReversalResearchListResponse(list = list, total = list.size)))
        } catch (e: Exception) {
            logger.error("查询反转统计失败: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_REVERSAL_RESEARCH_LIST_FETCH_FAILED, messageSource = messageSource))
        }
    }

    @PostMapping("/export")
    fun export(@RequestBody request: ReversalResearchListRequest): ResponseEntity<ApiResponse<ReversalResearchCsvResponse>> {
        val coin = validCoin(request.coin)
            ?: return ResponseEntity.ok(ApiResponse.error(ErrorCode.REVERSAL_RESEARCH_PARAM_INVALID, messageSource = messageSource))
        return try {
            val rows = reversalStatRepository
                .findByCoinAndIntervalSecondsAndLookbackDaysAndDataSourceOrderByOutcomeIndexAscDiffSigmaBucketAscRemainingBucketAsc(
                    coin, request.intervalSeconds, request.lookbackDays, request.dataSource.ifBlank { "BINANCE" }
                )
            val sb = StringBuilder()
            sb.append("coin,interval_seconds,outcome_index,diff_sigma_bucket,odds_bucket,remaining_bucket,lookback_days,data_source,sampling_seconds,sample_count,distinct_period_count,reversed_count,model_prob,reversal_rate,mae_avg,mfe_avg,virtual_tp_rate,virtual_stop_rate,virtual_win_rate,virtual_pnl_avg\n")
            for (r in rows) {
                val dto = r.toDto()
                sb.append("${dto.coin},${dto.intervalSeconds},${dto.outcomeIndex},${dto.diffSigmaBucket},${dto.oddsBucket},${dto.remainingBucket},${dto.lookbackDays},${dto.dataSource},${dto.samplingSeconds},${dto.sampleCount},${dto.distinctPeriodCount},${dto.reversedCount},${dto.modelProb},${dto.reversalRate},${dto.maeAvg},${dto.mfeAvg},${dto.virtualTpRate},${dto.virtualStopRate},${dto.virtualWinRate},${dto.virtualPnlAvg}\n")
            }
            val filename = "reversal_${coin}_${request.intervalSeconds}s_${request.lookbackDays}d.csv"
            ResponseEntity.ok(ApiResponse.success(ReversalResearchCsvResponse(filename = filename, csv = sb.toString(), total = rows.size)))
        } catch (e: Exception) {
            logger.error("导出反转 CSV 失败: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_REVERSAL_RESEARCH_CSV_EXPORT_FAILED, messageSource = messageSource))
        }
    }

    private fun CryptoTailReversalStat.toDto(): ReversalStatDto {
        val reversalRate = BigDecimal.ONE.subtract(modelProb).max(BigDecimal.ZERO).setScale(8, RoundingMode.HALF_UP)
        return ReversalStatDto(
            coin = coin,
            intervalSeconds = intervalSeconds,
            outcomeIndex = outcomeIndex,
            diffSigmaBucket = diffSigmaBucket,
            oddsBucket = oddsBucket,
            remainingBucket = remainingBucket,
            lookbackDays = lookbackDays,
            dataSource = dataSource,
            sampleCount = sampleCount,
            reversedCount = reversedCount,
            modelProb = modelProb.toPlainString(),
            reversalRate = reversalRate.toPlainString(),
            samplingSeconds = samplingSeconds,
            distinctPeriodCount = distinctPeriodCount,
            maeAvg = maeAvg?.toPlainString() ?: "",
            mfeAvg = mfeAvg?.toPlainString() ?: "",
            virtualTpRate = virtualTpRate?.toPlainString() ?: "",
            virtualStopRate = virtualStopRate?.toPlainString() ?: "",
            virtualWinRate = virtualWinRate?.toPlainString() ?: "",
            virtualPnlAvg = virtualPnlAvg?.toPlainString() ?: "",
            computedAt = computedAt
        )
    }
}
