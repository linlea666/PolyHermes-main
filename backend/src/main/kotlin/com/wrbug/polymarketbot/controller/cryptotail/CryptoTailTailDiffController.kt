package com.wrbug.polymarketbot.controller.cryptotail

import com.wrbug.polymarketbot.dto.ApiResponse
import com.wrbug.polymarketbot.dto.TailDiffAdvisorRequest
import com.wrbug.polymarketbot.dto.TailDiffAdvisorResponse
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.enums.TradingMode
import com.wrbug.polymarketbot.repository.CryptoTailStrategyRepository
import com.wrbug.polymarketbot.service.cryptotail.OrderbookQualitySnapshot
import com.wrbug.polymarketbot.service.cryptotail.taildiff.CryptoTailScoreEngine
import com.wrbug.polymarketbot.service.cryptotail.taildiff.CryptoTailTailDiffDecisionService
import com.wrbug.polymarketbot.service.cryptotail.taildiff.CryptoTailTailDiffParamAdvisor
import com.wrbug.polymarketbot.service.cryptotail.taildiff.TailDiffExitPresetResolver
import com.wrbug.polymarketbot.service.cryptotail.taildiff.TailDiffTier
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal

/**
 * 尾盘价差模式相关接口：
 *  - /api/crypto-tail-strategy/tail-diff/preview : 用前端表单参数 + 模拟盘口/价格 即时计算评分预览
 *
 * 仅 mode=TAIL_DIFF 策略可调用 preview；其他模式返回 PARAM_ERROR。
 */
@RestController
@RequestMapping("/api/crypto-tail-strategy/tail-diff")
class CryptoTailTailDiffController(
    private val strategyRepository: CryptoTailStrategyRepository,
    private val decisionService: CryptoTailTailDiffDecisionService,
    private val scoreEngine: CryptoTailScoreEngine,
    private val exitPresetResolver: TailDiffExitPresetResolver,
    private val paramAdvisor: CryptoTailTailDiffParamAdvisor,
    private val messageSource: MessageSource
) {

    private val logger = LoggerFactory.getLogger(CryptoTailTailDiffController::class.java)

    /**
     * 评分预览请求：前端表单填好策略 ID + 一组虚拟盘口/价源参数，后端返回 7 项分项 + 总分 + tier + vetoes + 推荐金额。
     * 用于"调参时即时看效果"。
     */
    data class TailDiffPreviewRequest(
        /** 必填：现有 TAIL_DIFF 策略 ID（前端表单也可挂未保存策略，但 preview 必须基于已存策略表的阈值） */
        val strategyId: Long = 0L,
        /** 当前周期起点 unix 秒 */
        val periodStartUnix: Long = 0L,
        /** 0=Up 1=Down */
        val outcomeIndex: Int = 0,
        /** 模拟期初价 */
        val openPrice: String = "",
        /** 模拟当前价 */
        val closePrice: String = "",
        /** 模拟 σ_per_√s */
        val sigmaPerSqrtS: String = "",
        /** 模拟剩余秒数 */
        val remainingSeconds: Int = 60,
        /** 模拟 bestBid */
        val bestBid: String = "",
        /** 模拟 bestAsk（可空） */
        val bestAsk: String = "",
        /** 模拟 bid 深度 USDC */
        val bidDepthUsd: String = "0",
        /** 模拟 ask 深度 USDC */
        val askDepthUsd: String = "0",
        /** 模拟订单簿年龄（毫秒） */
        val orderbookAgeMs: Long = 0L,
        /** 模拟价源年龄（毫秒） */
        val priceAgeMs: Long = 0L,
        /** 模拟反抽速度（σ/s）；正值=反向回抽 */
        val reverseVelocitySigmaPerSec: String = "0",
        /** 模拟历史统计样本数（HYBRID/STATS 下用以决定回退） */
        val statsSampleCount: Int = 0,
        /** 模拟历史反转 modelProb；空=不可用 */
        val statsModelProb: String = "",
        /** 候选金额 USDC（用于深度否决检查） */
        val candidateAmountUsdc: String = "1"
    )

    data class TailDiffPreviewResponse(
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
            val openP = request.openPrice.toSafeBigDecimal()
            val closeP = request.closePrice.toSafeBigDecimal()
            val sigma = request.sigmaPerSqrtS.toSafeBigDecimal()
            val bestBid = request.bestBid.toSafeBigDecimal()
            val bestAsk = if (request.bestAsk.isBlank()) null else request.bestAsk.toSafeBigDecimal()
            val bidDepth = request.bidDepthUsd.toSafeBigDecimal()
            val askDepth = request.askDepthUsd.toSafeBigDecimal()
            val candidateAmount = request.candidateAmountUsdc.toSafeBigDecimal()
            if (openP <= BigDecimal.ZERO || closeP <= BigDecimal.ZERO || sigma <= BigDecimal.ZERO || bestBid <= BigDecimal.ZERO || request.remainingSeconds <= 0) {
                return ResponseEntity.ok(
                    ApiResponse.error(ErrorCode.CRYPTO_TAIL_STRATEGY_TAIL_DIFF_PARAM_INVALID, messageSource = messageSource)
                )
            }

            val rawDiff = closeP.subtract(openP)
            val diffPct = rawDiff.divide(openP, 8, java.math.RoundingMode.HALF_UP)
            val modelSide = if (rawDiff.signum() >= 0) 0 else 1
            // diffSigma = |rawDiff| / (σ × √remaining)
            val expected = sigma.toDouble() * Math.sqrt(request.remainingSeconds.toDouble())
            val diffSigma = if (expected > 0) BigDecimal(rawDiff.abs().toDouble() / expected).setScale(6, java.math.RoundingMode.HALF_UP) else BigDecimal.ZERO

            val rawAskPrice = bestAsk ?: bestBid.add(strategy.tailDiffCostBuffer)
            val feePerShare = rawAskPrice.multiply(BigDecimal(strategy.takerFeeBps))
                .divide(BigDecimal(10000), 8, java.math.RoundingMode.HALF_UP)
            val effectiveCost = rawAskPrice.add(feePerShare).min(strategy.tailDiffHardMaxPrice)
            // modelProb 来源：HYBRID 时优先用 statsModelProb（样本足够时），否则回退 BarrierProbability
            //   Preview 输入只是模拟值，因此只看 statsModelProb 是否给出 + sampleCount。
            val statsProb = if (request.statsModelProb.isBlank()) null else request.statsModelProb.toSafeBigDecimal()
            val (modelProb, modelProbSource) = when (strategy.tailDiffModelProbSource.uppercase()) {
                "STATS" -> if (statsProb != null && request.statsSampleCount >= strategy.tailDiffStatsMinSamples)
                    statsProb to "STATS"
                else
                    barrierPWinFromGap(rawDiff, sigma, request.remainingSeconds) to "STATS_FALLBACK"
                "FALLBACK" -> barrierPWinFromGap(rawDiff, sigma, request.remainingSeconds) to "FALLBACK"
                else -> if (statsProb != null && request.statsSampleCount >= strategy.tailDiffStatsMinSamples)
                    statsProb to "HYBRID_STATS"
                else
                    barrierPWinFromGap(rawDiff, sigma, request.remainingSeconds) to "HYBRID_FALLBACK"
            }
            val edge = modelProb.subtract(effectiveCost)
            val midImpliedProb = if (bestAsk != null) bestBid.add(bestAsk).divide(BigDecimal(2), 8, java.math.RoundingMode.HALF_UP) else bestBid

            val input = CryptoTailScoreEngine.Input(
                coin = inferCoin(strategy.marketSlugPrefix) ?: "",
                open = openP,
                close = closeP,
                rawDiff = rawDiff,
                diffPct = diffPct,
                diffSigma = diffSigma,
                outcomeIndex = request.outcomeIndex,
                modelSide = modelSide,
                remainingSeconds = request.remainingSeconds,
                periodSeconds = strategy.intervalSeconds,
                modelProb = modelProb,
                modelProbSource = modelProbSource,
                statsSampleCount = request.statsSampleCount,
                effectiveCost = effectiveCost,
                edge = edge,
                midImpliedProb = midImpliedProb,
                bestBid = bestBid,
                bestAsk = bestAsk,
                spread = bestAsk?.subtract(bestBid),
                bidDepthUsd = bidDepth,
                askDepthUsd = askDepth,
                orderbookAgeMs = request.orderbookAgeMs,
                priceAgeMs = request.priceAgeMs,
                reverseVelocitySigmaPerSec = request.reverseVelocitySigmaPerSec.toSafeBigDecimal(),
                reverseVelocityReason = null,
                candidateAmountUsdc = candidateAmount
            )
            val out = scoreEngine.evaluate(input, strategy)
            val recommendedAmount = if (out.tier != null) {
                val mult = when (out.tier!!) {
                    TailDiffTier.NORMAL -> strategy.tailDiffTierNormalMult
                    TailDiffTier.PREMIUM -> strategy.tailDiffTierPremiumMult
                    TailDiffTier.TOP -> strategy.tailDiffTierTopMult
                }
                strategy.tailDiffBaseAmount.multiply(mult)
                    .min(strategy.tailDiffMaxAmountPerOrder)
            } else BigDecimal.ZERO

            val componentWeighted = mapOf(
                "diff" to out.component.scoreDiff.toPlainString(),
                "time" to out.component.scoreTime.toPlainString(),
                "oddsUnderprice" to out.component.scoreOddsUnderprice.toPlainString(),
                "oddsLag" to out.component.scoreOddsLag.toPlainString(),
                "history" to out.component.scoreHistory.toPlainString(),
                "book" to out.component.scoreBook.toPlainString(),
                "data" to out.component.scoreData.toPlainString()
            )
            val componentRaw = mapOf(
                "diff" to out.rawComponentScores.diff.toPlainString(),
                "time" to out.rawComponentScores.time.toPlainString(),
                "oddsUnderprice" to out.rawComponentScores.oddsUnderprice.toPlainString(),
                "oddsLag" to out.rawComponentScores.oddsLag.toPlainString(),
                "history" to out.rawComponentScores.history.toPlainString(),
                "book" to out.rawComponentScores.book.toPlainString(),
                "data" to out.rawComponentScores.data.toPlainString()
            )
            ResponseEntity.ok(
                ApiResponse.success(
                    TailDiffPreviewResponse(
                        score = out.score,
                        tier = out.tier?.label,
                        passed = out.passed,
                        vetoes = out.vetoes,
                        componentWeighted = componentWeighted,
                        componentRaw = componentRaw,
                        diffSigma = diffSigma.toPlainString(),
                        rawDiff = rawDiff.toPlainString(),
                        diffPct = diffPct.toPlainString(),
                        modelSide = modelSide,
                        effectiveCost = effectiveCost.toPlainString(),
                        edge = edge.toPlainString(),
                        midImpliedProb = midImpliedProb.toPlainString(),
                        modelProb = modelProb.toPlainString(),
                        modelProbSource = modelProbSource,
                        recommendedAmountUsdc = recommendedAmount.toPlainString(),
                        limitPriceCap = strategy.tailDiffHardMaxPrice.toPlainString(),
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

    private fun barrierPWinFromGap(rawDiff: BigDecimal, sigma: BigDecimal, remainingSeconds: Int): BigDecimal {
        val sd = sigma.toDouble() * Math.sqrt(remainingSeconds.toDouble())
        if (sd <= 0.0) return BigDecimal("0.5")
        val z = rawDiff.abs().toDouble() / sd
        // erf 近似（与 BarrierProbability 同源）
        val sign = 1.0
        val ax = Math.abs(z / Math.sqrt(2.0))
        val t = 1.0 / (1.0 + 0.3275911 * ax)
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * Math.exp(-ax * ax)
        val phi = 0.5 * (1.0 + sign * y)
        return BigDecimal(phi).setScale(8, java.math.RoundingMode.HALF_UP)
    }

    private fun inferCoin(slug: String): String? {
        val s = slug.lowercase()
        return when {
            s.contains("btc") -> "BTC"
            s.contains("eth") -> "ETH"
            else -> null
        }
    }
}
