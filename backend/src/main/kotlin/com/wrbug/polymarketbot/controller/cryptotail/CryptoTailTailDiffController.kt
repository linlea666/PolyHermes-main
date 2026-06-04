package com.wrbug.polymarketbot.controller.cryptotail

import com.wrbug.polymarketbot.dto.ApiResponse
import com.wrbug.polymarketbot.dto.TailDiffAdvisorRequest
import com.wrbug.polymarketbot.dto.TailDiffAdvisorResponse
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.enums.TradingMode
import com.wrbug.polymarketbot.repository.CryptoTailStrategyRepository
import com.wrbug.polymarketbot.service.cryptotail.CryptoTailEntryGuardService
import com.wrbug.polymarketbot.service.cryptotail.CryptoTailOrderbookWsService
import com.wrbug.polymarketbot.service.cryptotail.taildiff.CryptoTailTailDiffDecisionService
import com.wrbug.polymarketbot.service.cryptotail.taildiff.CryptoTailTailDiffParamAdvisor
import com.wrbug.polymarketbot.service.cryptotail.taildiff.TailDiffExitPresetResolver
import com.wrbug.polymarketbot.service.cryptotail.taildiff.TailDiffTier
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 尾盘价差模式相关接口：
 *  - /api/crypto-tail-strategy/tail-diff/preview : 以实盘 evaluate 决策链对该策略当前实时盘口/余额算分预览
 *
 * 仅 mode=TAIL_DIFF 策略可调用 preview；其他模式返回 PARAM_ERROR。
 *
 * 设计（以实盘为准）：preview 不再接受手填模拟盘口/价源，而是复用与实盘完全一致的
 * [CryptoTailTailDiffDecisionService.preview]（内部走 evaluate：分段 overlay + BarrierProbability 方向/σ
 * + 真实反转统计 + 方向闸 + tier/veto + sizing 余额钳制）。实时盘口取自 WS 订阅缓存，余额取自实盘可支配余额。
 * 因此 preview 结果与实盘逐项一致；当策略未启用/未在订阅周期内/盘口未就绪时，返回明确的"未就绪"错误而非误导分数。
 */
@RestController
@RequestMapping("/api/crypto-tail-strategy/tail-diff")
class CryptoTailTailDiffController(
    private val strategyRepository: CryptoTailStrategyRepository,
    private val decisionService: CryptoTailTailDiffDecisionService,
    private val exitPresetResolver: TailDiffExitPresetResolver,
    private val paramAdvisor: CryptoTailTailDiffParamAdvisor,
    private val orderbookWsService: CryptoTailOrderbookWsService,
    private val entryGuardService: CryptoTailEntryGuardService,
    private val messageSource: MessageSource
) {

    private val logger = LoggerFactory.getLogger(CryptoTailTailDiffController::class.java)

    /**
     * 评分预览请求：仅需策略 ID + 评估方向；其余一律取实时盘口/余额/价源，保证与实盘一致。
     */
    data class TailDiffPreviewRequest(
        /** 必填：现有 TAIL_DIFF 策略 ID */
        val strategyId: Long = 0L,
        /** 评估方向 0=Up 1=Down（默认 Up） */
        val outcomeIndex: Int = 0
    )

    data class TailDiffPreviewResponse(
        /** 实盘决策结果：BUY/WATCH/SKIP */
        val outcome: String,
        /** 决策原因（与实盘决策日志一致，便于解释为何未入场） */
        val reason: String,
        val score: Int,
        val tier: String?,
        val passed: Boolean,
        val vetoes: List<String>,
        val componentWeighted: Map<String, String>,
        val componentRaw: Map<String, String>,
        val diffSigma: String,
        val rawDiff: String,
        val diffPct: String,
        val modelSide: Int,
        val effectiveCost: String,
        val edge: String,
        val midImpliedProb: String,
        val modelProb: String,
        val modelProbSource: String,
        val recommendedAmountUsdc: String,
        val limitPriceCap: String,
        val exitPresetNormal: Map<String, Any>?,
        val exitPresetPremium: Map<String, Any>?,
        val exitPresetTop: Map<String, Any>?
    )

    @PostMapping("/preview")
    fun preview(@RequestBody request: TailDiffPreviewRequest): ResponseEntity<ApiResponse<TailDiffPreviewResponse>> {
        return try {
            val strategy = strategyRepository.findById(request.strategyId).orElse(null)
                ?: return ResponseEntity.ok(
                    ApiResponse.error(ErrorCode.CRYPTO_TAIL_STRATEGY_NOT_FOUND, messageSource = messageSource)
                )
            if (strategy.mode != TradingMode.TAIL_DIFF) {
                return ResponseEntity.ok(
                    ApiResponse.error(ErrorCode.CRYPTO_TAIL_STRATEGY_TAIL_DIFF_PARAM_INVALID, messageSource = messageSource)
                )
            }
            // 实时上下文：策略当前已订阅周期 + 最新盘口快照（未就绪则与实盘语义一致地返回 NOT_READY）
            val live = orderbookWsService.livePreviewContext(strategy.id!!, request.outcomeIndex)
                ?: return ResponseEntity.ok(
                    ApiResponse.error(ErrorCode.CRYPTO_TAIL_STRATEGY_TAIL_DIFF_PREVIEW_NOT_READY, messageSource = messageSource)
                )
            val spendable = entryGuardService.loadEntryBalanceSnapshot(strategy.accountId).spendable

            val decision = decisionService.preview(
                strategy = strategy,
                periodStartUnix = live.periodStartUnix,
                outcomeIndex = request.outcomeIndex,
                orderbook = live.orderbook,
                spendableBalance = spendable
            )

            val componentWeighted = decision.components?.let {
                mapOf(
                    "diff" to it.scoreDiff.toPlainString(),
                    "time" to it.scoreTime.toPlainString(),
                    "oddsUnderprice" to it.scoreOddsUnderprice.toPlainString(),
                    "oddsLag" to it.scoreOddsLag.toPlainString(),
                    "history" to it.scoreHistory.toPlainString(),
                    "book" to it.scoreBook.toPlainString(),
                    "data" to it.scoreData.toPlainString()
                )
            } ?: emptyMap()
            val componentRaw = decision.rawComponents?.let {
                mapOf(
                    "diff" to it.diff.toPlainString(),
                    "time" to it.time.toPlainString(),
                    "oddsUnderprice" to it.oddsUnderprice.toPlainString(),
                    "oddsLag" to it.oddsLag.toPlainString(),
                    "history" to it.history.toPlainString(),
                    "book" to it.book.toPlainString(),
                    "data" to it.data.toPlainString()
                )
            } ?: emptyMap()
            // 有效成本：edge = modelProb - effectiveCost，故 effectiveCost = modelProb - edge（两者皆备时）
            val effectiveCost = if (decision.modelProb != null && decision.edge != null)
                decision.modelProb!!.subtract(decision.edge).toPlainString() else ""

            ResponseEntity.ok(
                ApiResponse.success(
                    TailDiffPreviewResponse(
                        outcome = decision.outcome.name,
                        reason = decision.reason,
                        score = decision.score,
                        tier = decision.tier?.label,
                        passed = decision.passed,
                        vetoes = decision.vetoes,
                        componentWeighted = componentWeighted,
                        componentRaw = componentRaw,
                        diffSigma = decision.diffSigma?.toPlainString() ?: "",
                        rawDiff = decision.rawDiff?.toPlainString() ?: "",
                        diffPct = decision.diffPct?.toPlainString() ?: "",
                        modelSide = decision.modelSide ?: -1,
                        effectiveCost = effectiveCost,
                        edge = decision.edge?.toPlainString() ?: "",
                        midImpliedProb = decision.midImpliedProb?.toPlainString() ?: "",
                        modelProb = decision.modelProb?.toPlainString() ?: "",
                        modelProbSource = decision.modelProbSource ?: "",
                        recommendedAmountUsdc = decision.amountUsdc?.toPlainString() ?: "0",
                        limitPriceCap = (decision.limitPriceCap ?: strategy.tailDiffHardMaxPrice).toPlainString(),
                        exitPresetNormal = exitPresetResolver.resolveForTier(strategy, TailDiffTier.NORMAL).toMap(),
                        exitPresetPremium = exitPresetResolver.resolveForTier(strategy, TailDiffTier.PREMIUM).toMap(),
                        exitPresetTop = exitPresetResolver.resolveForTier(strategy, TailDiffTier.TOP).toMap()
                    )
                )
            )
        } catch (e: Exception) {
            logger.error("尾盘价差评分预览失败: strategyId=${request.strategyId}, ${e.message}", e)
            ResponseEntity.ok(
                ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource)
            )
        }
    }

    /**
     * 参数建议（阶段四）：基于该策略已结算 TAIL_DIFF 触发记录反推更优阈值。仅推荐，不写入。
     */
    @PostMapping("/advisor")
    fun advisor(@RequestBody request: TailDiffAdvisorRequest): ResponseEntity<ApiResponse<TailDiffAdvisorResponse>> {
        if (request.strategyId <= 0L) {
            return ResponseEntity.ok(
                ApiResponse.error(ErrorCode.CRYPTO_TAIL_STRATEGY_TAIL_DIFF_PARAM_INVALID, messageSource = messageSource)
            )
        }
        return try {
            val result = paramAdvisor.advise(request.strategyId, request.minSamples)
                ?: return ResponseEntity.ok(
                    ApiResponse.error(ErrorCode.CRYPTO_TAIL_STRATEGY_NOT_FOUND, messageSource = messageSource)
                )
            ResponseEntity.ok(ApiResponse.success(result))
        } catch (e: Exception) {
            logger.error("尾盘价差参数建议失败: strategyId=${request.strategyId}, ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }
}
