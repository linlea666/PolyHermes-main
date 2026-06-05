package com.wrbug.polymarketbot.service.cryptotail.taildiff

import com.wrbug.polymarketbot.entity.CryptoTailDecisionEvent
import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import com.wrbug.polymarketbot.enums.TradingMode
import com.wrbug.polymarketbot.service.cryptotail.BarrierProbability
import com.wrbug.polymarketbot.service.cryptotail.CryptoTailDecisionRecorder
import com.wrbug.polymarketbot.service.cryptotail.OrderbookQualitySnapshot
import com.wrbug.polymarketbot.service.cryptotail.PeriodPriceProvider
import com.wrbug.polymarketbot.service.cryptotail.TailDiffScorePayload
import com.wrbug.polymarketbot.util.toJson
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration

/**
 * 尾盘价差模式实时决策服务（V62 P1）。
 *
 * 职责：
 *  1. 接收 WS 价格/盘口 tick；按 [CryptoTailScoreEngine] 计算 modelProb / diffSigma / edge / 评分 / Tier
 *  2. N 次确认（[CryptoTailStrategy.tailDiffConfirmTicks]）后才视为"可入场候选"
 *  3. 输出 [TailDiffDecision]：放行 → 调用方拿到 amountUsdc/limitPrice/tier/exitPreset 自行走 placeOrder
 *     否决 → 调用方直接 return
 *  4. 所有 BUY/SKIP/WATCH 决策都通过 [CryptoTailDecisionRecorder] 落库，包含完整 [TailDiffScorePayload]
 *
 * 该服务严格只做"决策"，不直接发单：发单仍走 [com.wrbug.polymarketbot.service.cryptotail.CryptoTailStrategyExecutionService.placeOrderForTrigger]
 * 复用现有路径（账户/解密/CLOB/退出/风控/日志）。
 */
@Service
class CryptoTailTailDiffDecisionService(
    private val periodPriceProvider: PeriodPriceProvider,
    private val scoreEngine: CryptoTailScoreEngine,
    private val sizingPolicy: CryptoTailTailDiffSizingPolicy,
    private val exitPresetResolver: TailDiffExitPresetResolver,
    private val reverseVelocityTracker: CryptoTailReverseVelocityTracker,
    private val oddsVelocityTracker: CryptoTailOddsVelocityTracker,
    private val reversalStatsLookup: TailReversalStatsLookup,
    private val entrySegmentResolver: TailDiffEntrySegmentResolver,
    private val decisionRecorder: CryptoTailDecisionRecorder
) {

    private val logger = LoggerFactory.getLogger(CryptoTailTailDiffDecisionService::class.java)

    /**
     * 连续 confirm tick 计数器：(strategyId, periodStartUnix, outcomeIndex) -> 连续命中数
     * 30 秒未命中自动归零（cache expire）
     */
    private val confirmCounter: Cache<String, Int> = Caffeine.newBuilder()
        .maximumSize(2000)
        .expireAfterWrite(Duration.ofSeconds(30))
        .build()

    /** 同一 (strategyId-period-key) 评分日志每 [SCORE_LOG_INTERVAL_MS] 才记一次，避免每 tick 刷库 */
    private val scoreLogCache: Cache<String, Long> = Caffeine.newBuilder()
        .maximumSize(2000)
        .expireAfterWrite(Duration.ofMinutes(10))
        .build()

    private val SCORE_LOG_INTERVAL_MS = 5_000L

    /**
     * 决策结果：调用方据此决定是否真的下单。
     *
     * - [outcome] = BUY：放行，使用 [amountUsdc] 与 [limitPriceCap]（由调用方走 FAK 定价 policy）
     * - [outcome] = WATCH：仍在确认窗口/分数不足/未命中候选，记日志但不下单
     * - [outcome] = SKIP：硬否决命中，记日志并跳过
     */
    data class TailDiffDecision(
        val outcome: Outcome,
        val score: Int,
        val tier: TailDiffTier?,
        val passed: Boolean,
        val amountUsdc: BigDecimal? = null,
        val limitPriceCap: BigDecimal? = null,
        val exitPreset: TailDiffExitPreset? = null,
        val exitPresetJson: String? = null,
        val diffSigma: BigDecimal? = null,
        val rawDiff: BigDecimal? = null,
        val diffPct: BigDecimal? = null,
        val modelProbSource: String? = null,
        val modelProb: BigDecimal? = null,
        val edge: BigDecimal? = null,
        val midImpliedProb: BigDecimal? = null,
        val vetoes: List<String> = emptyList(),
        val reason: String = "",
        val components: CryptoTailScoreEngine.Components? = null,
        val rawComponents: CryptoTailScoreEngine.ComponentScores? = null,
        val confirmTicks: Int = 0,
        val candidateOutcomeIndex: Int? = null,
        val candidateOpen: BigDecimal? = null,
        val candidateClose: BigDecimal? = null,
        val modelSide: Int? = null,
        val statsSampleCount: Int = 0,
        val statsReversalProb: BigDecimal? = null
    ) {
        enum class Outcome { BUY, WATCH, SKIP }
    }

    /**
     * 主入口：评估当前 tick 是否应入场。
     *
     * @param strategy 必须为 mode=TAIL_DIFF；调用方负责过滤
     * @param periodStartUnix 当前周期起点 unix 秒
     * @param outcomeIndex WS 推送对应的 outcome（0=Up,1=Down）
     * @param orderbook 当前 outcome 的盘口快照
     * @param spendableBalance 当前账户可支配余额（USDC）
     */
    fun evaluate(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        outcomeIndex: Int,
        orderbook: OrderbookQualitySnapshot,
        spendableBalance: BigDecimal
    ): TailDiffDecision {
        require(strategy.mode == TradingMode.TAIL_DIFF) { "evaluate 仅适用于 TAIL_DIFF 模式" }
        val nowMs = System.currentTimeMillis()
        val nowSeconds = nowMs / 1000
        val remainingSeconds = (periodStartUnix + strategy.intervalSeconds - nowSeconds).toInt()

        // 0) 入场分段：命中段后用段内窗口/阈值覆盖策略对应字段（copy-overlay），ScoreEngine 照常读取即得分段有效阈值。
        //    segments 为空 → 默认段(无覆盖)，行为与单窗口完全一致；配置了 segments 但 remaining 不落任何段 → SKIP。
        val segment = entrySegmentResolver.resolve(strategy, remainingSeconds)
            ?: return skip(
                strategy, periodStartUnix, outcomeIndex, listOf("WINDOW_NO_SEGMENT"),
                "剩余 ${remainingSeconds}s 不在任何入场分段窗口内", remainingSeconds, orderbook
            )
        val effStrategy = entrySegmentResolver.applyOverrides(strategy, segment)

        // 1) 价源就绪
        if (!periodPriceProvider.isAvailable(strategy.marketSlugPrefix)) {
            return skip(strategy, periodStartUnix, outcomeIndex, listOf("PRICE_SOURCE_NOT_READY"), "价源未就绪", remainingSeconds, orderbook)
        }
        val oc = periodPriceProvider.getCurrentOpenClose(strategy.marketSlugPrefix, strategy.intervalSeconds, periodStartUnix)
            ?: return skip(strategy, periodStartUnix, outcomeIndex, listOf("PRICE_OPEN_CLOSE_NA"), "期初/当前价缺失", remainingSeconds, orderbook)
        val (openP, closeP) = oc

        // 2) 反抽速度采样（持续喂入；评估反抽用）
        reverseVelocityTracker.observe(strategy.marketSlugPrefix, closeP, nowMs)
        // 2b) 赔率速度采样（动态赔率滞后因子用；按 outcome 分序列，喂入该 token 的最优买价）
        val oddsLagKey = "${strategy.marketSlugPrefix}-$outcomeIndex"
        oddsVelocityTracker.observe(oddsLagKey, orderbook.bestBid, nowMs)

        // 3) σ 与 BarrierProbability：复用 BARRIER 内核计算 pWin/safeRatio/方向
        val sigma = periodPriceProvider.getSigmaPerSqrtS(
            strategy.marketSlugPrefix,
            strategy.intervalSeconds,
            periodStartUnix,
            outcomeIndex,
            strategy.sigmaScale,
            strategy.sigmaMethod,
            strategy.ewmaLambda
        ) ?: return skip(strategy, periodStartUnix, outcomeIndex, listOf("SIGMA_NOT_READY"), "σ 基准不可用", remainingSeconds, orderbook)

        val rProb = BarrierProbability.winProbTerminal(closeP.subtract(openP), sigma, remainingSeconds.toDouble())
            ?: return skip(strategy, periodStartUnix, outcomeIndex, listOf("PWIN_NA"), "无法计算 pWin", remainingSeconds, orderbook)
        val modelSide = rProb.side
        val barrierPWin = rProb.pWin
        val diffSigma = rProb.safeRatio // |raw_diff| / (σ·√remaining)
        val rawDiff = closeP.subtract(openP)
        val diffPct = if (openP > BigDecimal.ZERO) rawDiff.divide(openP, 8, RoundingMode.HALF_UP) else BigDecimal.ZERO

        // 4) 方向闸：strategy.tailDiffDirection 限定（1=只 Up，2=只 Down）
        val dirAllowed = when (strategy.tailDiffDirection) {
            1 -> outcomeIndex == 0
            2 -> outcomeIndex == 1
            else -> true
        }
        if (!dirAllowed) {
            return skip(
                strategy, periodStartUnix, outcomeIndex, listOf("DIRECTION_NOT_ALLOWED"),
                "方向受限: tailDiffDirection=${strategy.tailDiffDirection}, outcomeIndex=$outcomeIndex",
                remainingSeconds, orderbook
            )
        }

        // 5) modelProb 来源：HYBRID/STATS/FALLBACK
        val statsResult = reversalStatsLookup.queryReversalProb(
            TailReversalStatsLookup.Query(
                coin = inferCoin(strategy),
                intervalSeconds = strategy.intervalSeconds,
                // 桶以领先方向（leadOutcome）为键，必须用 modelSide 匹配回填语义，而非被评估 token 的 outcomeIndex
                leadOutcome = modelSide,
                diffSigma = diffSigma,
                oddsBucket = TailDiffBuckets.oddsBucket(orderbook.bestBid),
                remainingBucket = TailDiffBuckets.remainingBucket(remainingSeconds),
                lookbackDays = strategy.tailDiffStatsLookbackDays,
                dataSource = strategy.tailDiffStatsDataSource
            )
        )
        val source = strategy.tailDiffModelProbSource.uppercase()
        val (modelProb, modelProbSource) = when (source) {
            "STATS" -> {
                val stats = statsResult.modelProb
                if (stats != null && statsResult.sampleCount >= strategy.tailDiffStatsMinSamples) stats to "STATS"
                else barrierPWin to "STATS_FALLBACK"
            }
            "FALLBACK" -> barrierPWin to "FALLBACK"
            else -> {
                val stats = statsResult.modelProb
                if (stats != null && statsResult.sampleCount >= strategy.tailDiffStatsMinSamples) stats to "HYBRID_STATS"
                else barrierPWin to "HYBRID_FALLBACK"
            }
        }
        // 来源/回退可观测：完整快照已随 TAIL_DIFF_SCORE_COMPUTED 落库（modelProbSource/statsSampleCount），
        // 此处 DEBUG 级补一条即时线索，避免高频 tick 下 INFO 刷屏。
        if (logger.isDebugEnabled) {
            logger.debug(
                "TAIL_DIFF modelProb 来源: strategyId={}, leadOutcome={}, source={}, samples={}/{}, dataSource={}",
                strategy.id, modelSide, modelProbSource, statsResult.sampleCount, strategy.tailDiffStatsMinSamples, strategy.tailDiffStatsDataSource
            )
        }

        // 6) 有效成本 / edge / midImpliedProb
        val bestAsk = orderbook.bestAsk
        val bestBid = orderbook.bestBid
        val rawAskPrice = bestAsk ?: bestBid.add(strategy.tailDiffCostBuffer)
        val feePerShare = rawAskPrice.multiply(BigDecimal(strategy.takerFeeBps))
            .divide(BigDecimal(10000), 8, RoundingMode.HALF_UP)
        val effectiveCost = rawAskPrice.add(feePerShare).min(effStrategy.tailDiffHardMaxPrice)
        val edge = modelProb.subtract(effectiveCost)
        val midImpliedProb = if (bestAsk != null) bestBid.add(bestAsk).divide(BigDecimal(2), 8, RoundingMode.HALF_UP) else bestBid

        // 7) 反抽速度（仅当 outcomeIndex 反向时报反抽 σ/s）
        val velocity = reverseVelocityTracker.computeReverseVelocity(
            marketSlugPrefix = strategy.marketSlugPrefix,
            outcomeIndex = outcomeIndex,
            sigmaPerSqrtS = sigma,
            windowSeconds = strategy.tailDiffReverseVelocityWindowSeconds,
            nowMs = nowMs
        )
        val reverseVelocity = if (velocity.isReversing) velocity.velocitySigmaPerSec else BigDecimal.ZERO

        // 7b) 动态赔率滞后因子（V62→V72）：仅 DYNAMIC/HYBRID 模式参与评分；STATIC 模式下不读取（节省计算）
        val (priceLeadMoveSigma, oddsMoveOverWindow) = if (strategy.tailDiffOddsLagMode.uppercase() != "STATIC") {
            val lead = reverseVelocityTracker.computeLeadMoveSigma(
                marketSlugPrefix = strategy.marketSlugPrefix,
                outcomeIndex = outcomeIndex,
                sigmaPerSqrtS = sigma,
                windowSeconds = strategy.tailDiffOddsLagWindowSeconds,
                nowMs = nowMs
            )
            val oddsMove = oddsVelocityTracker.computeOddsMove(
                key = oddsLagKey,
                windowSeconds = strategy.tailDiffOddsLagWindowSeconds,
                nowMs = nowMs
            ).let { if (it.reason == null) it.oddsDelta else null }
            lead to oddsMove
        } else {
            null to null
        }

        // 8) 候选金额（先按 1 USDC 算预设，深度否决用；最后再用 tier 计算真正金额）
        val candidateAmountForBookCheck = strategy.tailDiffBaseAmount
            .multiply(strategy.tailDiffTierTopMult.max(BigDecimal.ONE))
            .min(strategy.tailDiffMaxAmountPerOrder)

        val input = CryptoTailScoreEngine.Input(
            coin = inferCoin(strategy) ?: "",
            open = openP,
            close = closeP,
            rawDiff = rawDiff,
            diffPct = diffPct,
            diffSigma = diffSigma,
            outcomeIndex = outcomeIndex,
            modelSide = modelSide,
            remainingSeconds = remainingSeconds,
            periodSeconds = strategy.intervalSeconds,
            modelProb = modelProb,
            modelProbSource = modelProbSource,
            statsSampleCount = statsResult.sampleCount,
            effectiveCost = effectiveCost,
            edge = edge,
            midImpliedProb = midImpliedProb,
            bestBid = bestBid,
            bestAsk = bestAsk,
            spread = orderbook.spread,
            bidDepthUsd = orderbook.bidDepthUsd ?: BigDecimal.ZERO,
            askDepthUsd = orderbook.askDepthUsd ?: BigDecimal.ZERO,
            orderbookAgeMs = orderbook.quoteAgeMs(nowMs),
            priceAgeMs = periodPriceProvider.getCurrentPriceAgeMs(strategy.marketSlugPrefix),
            reverseVelocitySigmaPerSec = reverseVelocity,
            reverseVelocityReason = velocity.reason,
            candidateAmountUsdc = candidateAmountForBookCheck,
            priceLeadMoveSigma = priceLeadMoveSigma,
            oddsMoveOverWindow = oddsMoveOverWindow
        )

        val scoreOutput = scoreEngine.evaluate(input, effStrategy)
        val tier = scoreOutput.tier

        // 9) 评分日志（每 5s 一次，所有 tick 都参与候选，但不刷库）
        recordScoreSnapshot(
            effStrategy, periodStartUnix, outcomeIndex, scoreOutput, input,
            tier = tier, source = modelProbSource, statsResult = statsResult,
            remainingSeconds = remainingSeconds, nowMs = nowMs
        )

        // 10) 否决 → 直接 SKIP（counter 也清零）
        if (scoreOutput.vetoes.isNotEmpty()) {
            confirmCounter.invalidate(confirmKey(strategy, periodStartUnix, outcomeIndex))
            return TailDiffDecision(
                outcome = TailDiffDecision.Outcome.SKIP,
                score = scoreOutput.score,
                tier = null,
                passed = false,
                vetoes = scoreOutput.vetoes,
                reason = "硬否决: ${scoreOutput.vetoes.joinToString(",")}",
                components = scoreOutput.component,
                rawComponents = scoreOutput.rawComponentScores,
                diffSigma = diffSigma,
                rawDiff = rawDiff,
                diffPct = diffPct,
                modelProbSource = modelProbSource,
                modelProb = modelProb,
                edge = edge,
                midImpliedProb = midImpliedProb,
                candidateOutcomeIndex = outcomeIndex,
                candidateOpen = openP,
                candidateClose = closeP,
                modelSide = modelSide,
                statsSampleCount = statsResult.sampleCount,
                statsReversalProb = statsResult.modelProb
            )
        }

        // 11) Tier 未分层 → WATCH（分数低于 minEntryScore），confirmTicks 清零
        if (tier == null) {
            confirmCounter.invalidate(confirmKey(strategy, periodStartUnix, outcomeIndex))
            return TailDiffDecision(
                outcome = TailDiffDecision.Outcome.WATCH,
                score = scoreOutput.score,
                tier = null,
                passed = false,
                reason = "分数=${scoreOutput.score}<minEntryScore=${effStrategy.tailDiffMinEntryScore}",
                components = scoreOutput.component,
                rawComponents = scoreOutput.rawComponentScores,
                diffSigma = diffSigma,
                rawDiff = rawDiff,
                diffPct = diffPct,
                modelProbSource = modelProbSource,
                modelProb = modelProb,
                edge = edge,
                midImpliedProb = midImpliedProb,
                candidateOutcomeIndex = outcomeIndex,
                candidateOpen = openP,
                candidateClose = closeP,
                modelSide = modelSide,
                statsSampleCount = statsResult.sampleCount,
                statsReversalProb = statsResult.modelProb
            )
        }

        // 12) 连续 confirm：累计 N 次后才放行
        val key = confirmKey(strategy, periodStartUnix, outcomeIndex)
        val ticks = (confirmCounter.getIfPresent(key) ?: 0) + 1
        confirmCounter.put(key, ticks)
        if (ticks < strategy.tailDiffConfirmTicks) {
            return TailDiffDecision(
                outcome = TailDiffDecision.Outcome.WATCH,
                score = scoreOutput.score,
                tier = tier,
                passed = false,
                reason = "等待连续确认: ticks=$ticks/${strategy.tailDiffConfirmTicks}",
                components = scoreOutput.component,
                rawComponents = scoreOutput.rawComponentScores,
                diffSigma = diffSigma,
                rawDiff = rawDiff,
                diffPct = diffPct,
                modelProbSource = modelProbSource,
                modelProb = modelProb,
                edge = edge,
                midImpliedProb = midImpliedProb,
                confirmTicks = ticks,
                candidateOutcomeIndex = outcomeIndex,
                candidateOpen = openP,
                candidateClose = closeP,
                modelSide = modelSide,
                statsSampleCount = statsResult.sampleCount,
                statsReversalProb = statsResult.modelProb
            )
        }
        confirmCounter.invalidate(key)

        // 13) 分层金额 + 退出预设冻结（V72：可选叠加 1/10 Kelly 与盘口可成交深度上限）
        val availableDepthUsd = input.bidDepthUsd.min(input.askDepthUsd)
        val sizing = sizingPolicy.computeAmount(
            strategy = strategy,
            tier = tier,
            spendableBalance = spendableBalance,
            modelProb = modelProb,
            effectiveCost = effectiveCost,
            availableDepthUsd = availableDepthUsd
        )
        if (sizing.amountUsdc <= BigDecimal.ZERO) {
            return skip(
                strategy, periodStartUnix, outcomeIndex,
                listOf("AMOUNT_BELOW_MIN"),
                "金额不足: spendable=${spendableBalance.toPlainString()}, tier=$tier",
                remainingSeconds, orderbook
            )
        }
        // 退出分层：段内 exit_tier_bias 优先（如早窗用 PREMIUM 的 0.98 止盈+动态止损，而非 TOP 持有到结算）；
        // 仓位仍按评分分层 tier 计算，仅退出预设按 bias 档冻结。
        val exitTier = entrySegmentResolver.resolveExitTier(segment, tier)
        val (preset, presetJson) = exitPresetResolver.resolveAndFreeze(strategy, exitTier)

        // 14) BUY 决策日志
        recordBuyDecision(
            effStrategy, periodStartUnix, outcomeIndex, scoreOutput, input,
            tier = tier, source = modelProbSource, statsResult = statsResult,
            remainingSeconds = remainingSeconds,
            amountUsdc = sizing.amountUsdc, tierMultiplier = sizing.effectiveMultiplier,
            limitPriceCap = effStrategy.tailDiffHardMaxPrice
        )

        return TailDiffDecision(
            outcome = TailDiffDecision.Outcome.BUY,
            score = scoreOutput.score,
            tier = tier,
            passed = true,
            amountUsdc = sizing.amountUsdc,
            limitPriceCap = effStrategy.tailDiffHardMaxPrice,
            exitPreset = preset,
            exitPresetJson = presetJson,
            diffSigma = diffSigma,
            rawDiff = rawDiff,
            diffPct = diffPct,
            modelProbSource = modelProbSource,
            modelProb = modelProb,
            edge = edge,
            midImpliedProb = midImpliedProb,
            vetoes = emptyList(),
            reason = "评分=${scoreOutput.score}>=阈值 进入 ${tier} 档, 连续确认=${strategy.tailDiffConfirmTicks}",
            components = scoreOutput.component,
            rawComponents = scoreOutput.rawComponentScores,
            confirmTicks = ticks,
            candidateOutcomeIndex = outcomeIndex,
            candidateOpen = openP,
            candidateClose = closeP,
            modelSide = modelSide,
            statsSampleCount = statsResult.sampleCount,
            statsReversalProb = statsResult.modelProb
        )
    }

    /**
     * 仅评估、不维护 confirm 计数（前端/手动预览/单测使用）。
     */
    fun preview(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        outcomeIndex: Int,
        orderbook: OrderbookQualitySnapshot,
        spendableBalance: BigDecimal
    ): TailDiffDecision {
        // 简单复用 evaluate 后清零 confirmCounter（评估副作用对 preview 不可见）
        val before = confirmCounter.getIfPresent(confirmKey(strategy, periodStartUnix, outcomeIndex))
        val result = evaluate(strategy, periodStartUnix, outcomeIndex, orderbook, spendableBalance)
        if (before == null) {
            confirmCounter.invalidate(confirmKey(strategy, periodStartUnix, outcomeIndex))
        } else {
            confirmCounter.put(confirmKey(strategy, periodStartUnix, outcomeIndex), before)
        }
        return result
    }

    private fun confirmKey(strategy: CryptoTailStrategy, periodStartUnix: Long, outcomeIndex: Int): String =
        "${strategy.id}-$periodStartUnix-$outcomeIndex"

    private fun skip(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        outcomeIndex: Int,
        vetoes: List<String>,
        reason: String,
        remainingSeconds: Int,
        orderbook: OrderbookQualitySnapshot
    ): TailDiffDecision {
        confirmCounter.invalidate(confirmKey(strategy, periodStartUnix, outcomeIndex))
        // 仅记一次（每 5s 节流），避免每 tick 刷库
        val key = "${strategy.id}-$periodStartUnix-$outcomeIndex-SKIP-${vetoes.firstOrNull() ?: "NA"}"
        val now = System.currentTimeMillis()
        val last = scoreLogCache.getIfPresent(key) ?: 0L
        if (now - last >= SCORE_LOG_INTERVAL_MS) {
            scoreLogCache.put(key, now)
            val readiness = periodPriceProvider.getReadiness(strategy.marketSlugPrefix)
            val payload = mapOf(
                "vetoes" to vetoes,
                "remainingSeconds" to remainingSeconds,
                "bestBid" to orderbook.bestBid.toPlainString(),
                "bestAsk" to (orderbook.bestAsk?.toPlainString() ?: ""),
                "spread" to (orderbook.spread?.toPlainString() ?: ""),
                // 价源诊断：定位 PRICE_SOURCE_NOT_READY/PRICE_STALE 是真实间隙还是阈值过严
                "priceAgeMs" to (periodPriceProvider.getCurrentPriceAgeMs(strategy.marketSlugPrefix)?.toString() ?: ""),
                "maxPriceAgeMs" to strategy.tailDiffMaxPriceAgeMs.toString(),
                "readinessReason" to readiness.reason,
                "priceSource" to readiness.source,
                // P0 可观测性：区分 realtime 断流 vs snapshot 兜底滞后，以及 RTDS 层(30s)与策略层就绪口径差异
                "priceMode" to (readiness.priceMode ?: ""),
                "priceRealtimeGapMs" to (readiness.lastRealtimeUpdateAt?.let { (now - it).coerceAtLeast(0L).toString() } ?: ""),
                "rtdsReady" to readiness.ready.toString(),
                "snapshotAgeMs" to (readiness.lastSnapshotAt?.let { (now - it).coerceAtLeast(0L).toString() } ?: "")
            ).toJson()
            decisionRecorder.record(
                CryptoTailDecisionEvent(
                    strategyId = strategy.id!!,
                    periodStartUnix = periodStartUnix,
                    correlationId = "${strategy.id}-$periodStartUnix",
                    eventType = "TAIL_DIFF_SKIP",
                    gateName = vetoes.firstOrNull(),
                    passed = false,
                    reason = reason,
                    payloadJson = payload,
                    outcomeIndex = outcomeIndex,
                    triggerId = null
                )
            )
        }
        return TailDiffDecision(
            outcome = TailDiffDecision.Outcome.SKIP,
            score = 0,
            tier = null,
            passed = false,
            vetoes = vetoes,
            reason = reason,
            candidateOutcomeIndex = outcomeIndex
        )
    }

    private fun recordScoreSnapshot(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        outcomeIndex: Int,
        scoreOutput: CryptoTailScoreEngine.Output,
        input: CryptoTailScoreEngine.Input,
        tier: TailDiffTier?,
        source: String,
        statsResult: TailReversalStatsLookup.Result,
        remainingSeconds: Int,
        nowMs: Long
    ) {
        val key = "${strategy.id}-$periodStartUnix-$outcomeIndex-SCORE-${tier?.label ?: "NONE"}-${scoreOutput.vetoes.joinToString(",")}"
        val last = scoreLogCache.getIfPresent(key) ?: 0L
        if (nowMs - last < SCORE_LOG_INTERVAL_MS) return
        scoreLogCache.put(key, nowMs)
        val payload = buildScorePayload(scoreOutput, input, tier, source, statsResult, strategy, remainingSeconds, priceDiagOf(strategy, nowMs))
        decisionRecorder.record(
            CryptoTailDecisionEvent(
                strategyId = strategy.id!!,
                periodStartUnix = periodStartUnix,
                correlationId = "${strategy.id}-$periodStartUnix",
                eventType = "TAIL_DIFF_SCORE_COMPUTED",
                gateName = if (scoreOutput.vetoes.isNotEmpty()) "VETOED" else (tier?.label ?: "BELOW_THRESHOLD"),
                passed = scoreOutput.passed,
                reason = "score=${scoreOutput.score}, tier=${tier?.label ?: "NONE"}, vetoes=${scoreOutput.vetoes.size}",
                payloadJson = payload.toJson(),
                outcomeIndex = outcomeIndex,
                triggerId = null
            )
        )
    }

    private fun recordBuyDecision(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        outcomeIndex: Int,
        scoreOutput: CryptoTailScoreEngine.Output,
        input: CryptoTailScoreEngine.Input,
        tier: TailDiffTier,
        source: String,
        statsResult: TailReversalStatsLookup.Result,
        remainingSeconds: Int,
        amountUsdc: BigDecimal,
        tierMultiplier: BigDecimal,
        limitPriceCap: BigDecimal
    ) {
        val payload = buildScorePayload(scoreOutput, input, tier, source, statsResult, strategy, remainingSeconds, priceDiagOf(strategy, System.currentTimeMillis()))
            .copy(
                amountUsdc = amountUsdc.toPlainString(),
                tierMultiplier = tierMultiplier.toPlainString(),
                passed = "true"
            )
        decisionRecorder.record(
            CryptoTailDecisionEvent(
                strategyId = strategy.id!!,
                periodStartUnix = periodStartUnix,
                correlationId = "${strategy.id}-$periodStartUnix",
                eventType = "TAIL_DIFF_BUY",
                gateName = tier.label,
                passed = true,
                reason = "tier=${tier.label} amount=${amountUsdc.toPlainString()} cap=${limitPriceCap.toPlainString()}",
                payloadJson = payload.toJson(),
                outcomeIndex = outcomeIndex,
                triggerId = null
            )
        )
    }

    private fun buildScorePayload(
        scoreOutput: CryptoTailScoreEngine.Output,
        input: CryptoTailScoreEngine.Input,
        tier: TailDiffTier?,
        source: String,
        statsResult: TailReversalStatsLookup.Result,
        strategy: CryptoTailStrategy,
        remainingSeconds: Int,
        priceDiag: PriceDiag
    ): TailDiffScorePayload = TailDiffScorePayload(
        strategyId = strategy.id?.toString(),
        marketSlug = strategy.marketSlugPrefix,
        rawDiff = input.rawDiff.toPlainString(),
        diffPct = input.diffPct.toPlainString(),
        diffSigma = input.diffSigma.toPlainString(),
        open = input.open.toPlainString(),
        close = input.close.toPlainString(),
        modelSide = input.modelSide.toString(),
        outcomeIndex = input.outcomeIndex.toString(),
        remainingSeconds = remainingSeconds.toString(),
        windowStartSeconds = strategy.tailDiffWindowStartSeconds.toString(),
        windowEndSeconds = strategy.tailDiffWindowEndSeconds.toString(),
        modelProb = input.modelProb.toPlainString(),
        modelProbSource = source,
        effectiveCost = input.effectiveCost.toPlainString(),
        edge = input.edge.toPlainString(),
        midImpliedProb = input.midImpliedProb.toPlainString(),
        bestBid = input.bestBid.toPlainString(),
        bestAsk = input.bestAsk?.toPlainString(),
        spread = input.spread?.toPlainString(),
        bidDepthUsd = input.bidDepthUsd.toPlainString(),
        askDepthUsd = input.askDepthUsd.toPlainString(),
        scoreDiff = scoreOutput.component.scoreDiff.toPlainString(),
        scoreTime = scoreOutput.component.scoreTime.toPlainString(),
        scoreOddsUnderprice = scoreOutput.component.scoreOddsUnderprice.toPlainString(),
        scoreOddsLag = scoreOutput.component.scoreOddsLag.toPlainString(),
        scoreHistory = scoreOutput.component.scoreHistory.toPlainString(),
        scoreBook = scoreOutput.component.scoreBook.toPlainString(),
        scoreData = scoreOutput.component.scoreData.toPlainString(),
        score = scoreOutput.score.toString(),
        tier = tier?.label,
        passed = scoreOutput.passed.toString(),
        vetoReasons = scoreOutput.vetoes.joinToString(","),
        baseAmount = strategy.tailDiffBaseAmount.toPlainString(),
        reverseVelocitySigmaPerSec = input.reverseVelocitySigmaPerSec.toPlainString(),
        maxReverseVelocitySigma = strategy.tailDiffMaxReverseVelocitySigma.toPlainString(),
        minModelProb = strategy.tailDiffMinModelProb.toPlainString(),
        minEdge = strategy.tailDiffMinEdge.toPlainString(),
        minDiffSigma = strategy.tailDiffMinDiffSigma.toPlainString(),
        minScore = strategy.tailDiffMinEntryScore.toString(),
        statsSampleCount = statsResult.sampleCount.toString(),
        statsReversalProb = statsResult.modelProb?.toPlainString(),
        priceAgeMs = input.priceAgeMs?.toString(),
        orderbookAgeMs = input.orderbookAgeMs.toString(),
        maxPriceAgeMs = strategy.tailDiffMaxPriceAgeMs.toString(),
        maxOrderbookAgeMs = strategy.tailDiffMaxOrderbookAgeMs.toString(),
        sigmaScoreMultiple = strategy.tailDiffSigmaScoreMultiple.toPlainString(),
        effectiveAsk = (input.bestAsk ?: input.bestBid.add(strategy.tailDiffCostBuffer)).toPlainString(),
        priceMode = priceDiag.priceMode,
        priceRealtimeGapMs = priceDiag.realtimeGapMs?.toString(),
        rtdsReady = priceDiag.rtdsReady.toString(),
        strategyPriceFresh = (input.priceAgeMs != null && strategy.tailDiffMaxPriceAgeMs > 0 &&
            input.priceAgeMs <= strategy.tailDiffMaxPriceAgeMs).toString(),
        snapshotAgeMs = priceDiag.snapshotAgeMs?.toString()
    )

    /** 价源新鲜度诊断快照：把 RTDS readiness 折算成日志可读字段（P0 可观测性）。 */
    private data class PriceDiag(
        val priceMode: String?,
        val realtimeGapMs: Long?,
        val rtdsReady: Boolean,
        val snapshotAgeMs: Long?
    )

    private fun priceDiagOf(strategy: CryptoTailStrategy, nowMs: Long): PriceDiag {
        val readiness = periodPriceProvider.getReadiness(strategy.marketSlugPrefix)
        val gap = readiness.lastRealtimeUpdateAt?.let { (nowMs - it).coerceAtLeast(0L) }
        val snapGap = readiness.lastSnapshotAt?.let { (nowMs - it).coerceAtLeast(0L) }
        return PriceDiag(priceMode = readiness.priceMode, realtimeGapMs = gap, rtdsReady = readiness.ready, snapshotAgeMs = snapGap)
    }

    private fun inferCoin(strategy: CryptoTailStrategy): String? {
        val slug = strategy.marketSlugPrefix.lowercase()
        return when {
            slug.contains("btc") -> "BTC"
            slug.contains("eth") -> "ETH"
            else -> null
        }
    }
}
