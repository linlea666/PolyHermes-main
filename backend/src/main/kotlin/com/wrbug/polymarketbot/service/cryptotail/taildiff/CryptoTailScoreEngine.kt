package com.wrbug.polymarketbot.service.cryptotail.taildiff

import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 尾盘价差模式机会评分引擎（V62）。
 *
 * 输入：当下信号（价差/方向/剩余时间/盘口/模型概率/统计反转率/反抽速度）+ 策略阈值；
 * 输出：0-100 分 + 各分项 + 触发的硬否决列表 + 候选分层（NORMAL/PREMIUM/TOP/null）。
 *
 * 算法与计划书"§四 机会评分模型"对齐：
 *  - 7 个分项各自归一化到 [0,1]，再乘以策略权重（权重总和应=100，否则按比例归一化）
 *  - 加和得到 [0,100] 分数
 *  - 硬否决（vetoes）独立于评分，命中任一条 → 直接 SKIP，不计分
 *
 * 不依赖任何外部 service（除 [TailReversalStatsLookup] 可选）；纯函数风格，便于单测。
 */
@Component
class CryptoTailScoreEngine {

    /** 单次评分快照（输入） */
    data class Input(
        // 价差/方向
        val coin: String,
        val open: BigDecimal,
        val close: BigDecimal,
        val rawDiff: BigDecimal,
        val diffPct: BigDecimal,
        val diffSigma: BigDecimal,
        val outcomeIndex: Int,
        val modelSide: Int,
        // 时间
        val remainingSeconds: Int,
        val periodSeconds: Int,
        // 概率/EV
        val modelProb: BigDecimal,
        val modelProbSource: String,
        val statsSampleCount: Int,
        val effectiveCost: BigDecimal,
        val edge: BigDecimal,
        val midImpliedProb: BigDecimal,
        // 盘口
        val bestBid: BigDecimal,
        val bestAsk: BigDecimal?,
        val spread: BigDecimal?,
        val bidDepthUsd: BigDecimal,
        val askDepthUsd: BigDecimal,
        val orderbookAgeMs: Long,
        val priceAgeMs: Long?,
        // 反抽
        val reverseVelocitySigmaPerSec: BigDecimal,
        val reverseVelocityReason: String?,
        // 候选金额（用于盘口深度否决）
        val candidateAmountUsdc: BigDecimal,
        // 动态赔率滞后因子（V72，DYNAMIC/HYBRID 模式用；null=不可用，回退静态滞后）
        /** 窗口内标的朝领先方向移动的 σ 单位幅度（带符号，正=领先扩大） */
        val priceLeadMoveSigma: BigDecimal? = null,
        /** 同期赔率上行幅度（带符号，正=赔率跟上） */
        val oddsMoveOverWindow: BigDecimal? = null
    )

    /** 评分结果 */
    data class Output(
        val score: Int,
        val tier: TailDiffTier?,
        val passed: Boolean,
        val vetoes: List<String>,
        val component: Components,
        /** 每项分项原始分数（0-100） */
        val rawComponentScores: ComponentScores
    )

    /** 7 项加权分项 */
    data class Components(
        val scoreDiff: BigDecimal,
        val scoreTime: BigDecimal,
        val scoreOddsUnderprice: BigDecimal,
        val scoreOddsLag: BigDecimal,
        val scoreHistory: BigDecimal,
        val scoreBook: BigDecimal,
        val scoreData: BigDecimal
    )

    /** 7 项归一化原始分数（0-1，便于复盘） */
    data class ComponentScores(
        val diff: BigDecimal,
        val time: BigDecimal,
        val oddsUnderprice: BigDecimal,
        val oddsLag: BigDecimal,
        val history: BigDecimal,
        val book: BigDecimal,
        val data: BigDecimal
    )

    /**
     * 主入口：评分 + 否决 + 分层判定。
     */
    fun evaluate(input: Input, strategy: CryptoTailStrategy): Output {
        val vetoes = checkVetoes(input, strategy)
        // 即使被否决也算出分数，便于复盘"它本来能得多少"
        val rawScores = computeRawScores(input, strategy)
        val components = applyWeights(rawScores, strategy)
        val totalScore = (
            components.scoreDiff
                .add(components.scoreTime)
                .add(components.scoreOddsUnderprice)
                .add(components.scoreOddsLag)
                .add(components.scoreHistory)
                .add(components.scoreBook)
                .add(components.scoreData)
        ).setScale(0, RoundingMode.HALF_UP).toInt().coerceIn(0, 100)
        val tier = if (vetoes.isEmpty()) {
            TailDiffTier.fromScore(
                totalScore,
                strategy.tailDiffMinEntryScore,
                strategy.tailDiffPremiumScore,
                strategy.tailDiffTopScore
            )
        } else null
        return Output(
            score = totalScore,
            tier = tier,
            passed = vetoes.isEmpty() && tier != null,
            vetoes = vetoes,
            component = components,
            rawComponentScores = rawScores
        )
    }

    /**
     * 硬否决（计划书"§四 否决条件"）：任一命中即返回 SKIP，不入场。
     * 列表按检查顺序，前否决一旦命中即返回（短路，便于日志可读）。
     */
    private fun checkVetoes(input: Input, strategy: CryptoTailStrategy): List<String> {
        val vetoes = mutableListOf<String>()

        // 1) 方向一致性：模型方向与候选 outcome 不一致 → 直接否决
        if (input.modelSide != input.outcomeIndex) {
            vetoes += "MODEL_DIRECTION_MISMATCH"
        }

        // 2) 价格区间：买入实际成交价为 bestAsk；无 ask 时用 bestBid+costBuffer 兜底（与 DecisionService.effectiveCost 同口径）。
        //    历史 bug：此前用 bestBid 判断买入价区间会误杀 ask 在区间内、bid 偏低的可买机会（如 ask=0.86/bid=0.83）。
        val ask = input.bestAsk
        val effectiveAsk = ask ?: input.bestBid.add(strategy.tailDiffCostBuffer)
        if (effectiveAsk > strategy.tailDiffHardMaxPrice) vetoes += "ASK_TOO_HIGH"
        // 入场价格区间下限/上限：统一按买入成交价 bestAsk 判断
        if (effectiveAsk < strategy.tailDiffMinPrice) vetoes += "ASK_BELOW_MIN_PRICE"
        if (effectiveAsk > strategy.tailDiffMaxPrice) vetoes += "ASK_ABOVE_MAX_PRICE"

        // 3) 模型概率 / EV / diff_sigma 三道门
        if (input.modelProb < strategy.tailDiffMinModelProb) vetoes += "MODEL_PROB_TOO_LOW"
        if (input.edge < strategy.tailDiffMinEdge) vetoes += "EDGE_TOO_LOW"
        if (input.diffSigma < strategy.tailDiffMinDiffSigma) vetoes += "DIFF_SIGMA_TOO_LOW"

        // 4) 时间窗口：剩余时间不在 [windowEndSeconds, windowStartSeconds] 内
        //    注意：windowStartSeconds=距 periodStart 多少秒后允许入场；windowEndSeconds=距 periodStart 多少秒后停止入场
        //    剩余时间 = periodSeconds - elapsed = (windowEnd 表示更接近 settle) → 业务语义：剩余时间应在 (periodSeconds - windowStart, periodSeconds - windowEnd) 之间
        //    实际计划书表达更直白："距离结算 60-150s 入场" → remainingSeconds ∈ [windowEndSeconds, windowStartSeconds]
        val winLo = strategy.tailDiffWindowEndSeconds // 60
        val winHi = strategy.tailDiffWindowStartSeconds // 150
        if (input.remainingSeconds < winLo) vetoes += "WINDOW_TOO_LATE"
        if (input.remainingSeconds > winHi) vetoes += "WINDOW_TOO_EARLY"
        if (input.remainingSeconds < strategy.tailDiffMinRemainingSeconds) vetoes += "REMAINING_SECONDS_TOO_SHORT"

        // 5) 盘口质量：spread / depth / 数据新鲜度
        val spread = input.spread
        if (ask == null || spread == null) vetoes += "ORDERBOOK_NO_ASK"
        if (spread != null && strategy.tailDiffMaxSpread > BigDecimal.ZERO && spread > strategy.tailDiffMaxSpread) vetoes += "SPREAD_TOO_WIDE"
        val requiredDepth = input.candidateAmountUsdc.multiply(strategy.tailDiffDepthMultiplier)
        if (input.bidDepthUsd < requiredDepth || input.askDepthUsd < requiredDepth) vetoes += "DEPTH_TOO_SHALLOW"
        if (strategy.tailDiffMaxOrderbookAgeMs > 0 && input.orderbookAgeMs > strategy.tailDiffMaxOrderbookAgeMs) {
            vetoes += "ORDERBOOK_STALE"
        }
        val priceAge = input.priceAgeMs
        if (strategy.tailDiffMaxPriceAgeMs > 0 && (priceAge == null || priceAge > strategy.tailDiffMaxPriceAgeMs)) {
            vetoes += "PRICE_STALE"
        }

        // 6) 反抽过快：仅在反向时检查（isReversing 由 tracker 输出，velocity>0 即视为反向）
        if (input.reverseVelocitySigmaPerSec > strategy.tailDiffMaxReverseVelocitySigma) {
            vetoes += "PRICE_RETRACING_FAST"
        }

        // 7) 动态赔率滞后门控（P1，仅 DYNAMIC/HYBRID 生效；STATIC 不检查 → 默认零回归）
        //    根因：modelProb≈Phi(diffSigma)，与按 Phi 定价的市场同构，静态概率无持续 edge；唯一 alpha 是
        //    「标的已朝领先方向移动、Polymarket 赔率还没跟上」的时间差。该门控要求确有净滞后(lag>0)，
        //    否则（赔率已跟上=已被定价，或纯便宜反转票，或动量数据不可用）一律否决，把滞后探测从评分项升级为必要条件。
        //    注意：本门控依赖新鲜价（P0）与速度追踪器有数据，stale 价下 priceLeadMoveSigma 失真 → 应配合 P0 使用。
        if (strategy.tailDiffOddsLagMode.uppercase() != "STATIC") {
            val lag = dynamicLagScore(input, strategy)
            if (lag == null || lag <= BigDecimal.ZERO) vetoes += "ODDS_LAG_INSUFFICIENT"
        }

        return vetoes.distinct()
    }

    private fun computeRawScores(input: Input, strategy: CryptoTailStrategy): ComponentScores {
        // (1) 价差优势分：diffSigma 归一化（[minDiffSigma, multiple×minDiffSigma] → [0, 1]）
        val minSigma = strategy.tailDiffMinDiffSigma.coerceAtLeastOne()
        val sigmaMultiple = strategy.tailDiffSigmaScoreMultiple.let { if (it <= BigDecimal.ONE) BigDecimal("3") else it }
        val maxSigma = minSigma.multiply(sigmaMultiple)
        val sigmaScore = normalize01(input.diffSigma, minSigma, maxSigma)

        // (2) 时间优势分：越接近 windowEnd 越好（剩余越少分越高，前提是未越过窗口）
        //   分数 = 1 - (remainingSeconds - windowEnd) / (windowStart - windowEnd)
        val winLo = strategy.tailDiffWindowEndSeconds.toBigDecimal()
        val winHi = strategy.tailDiffWindowStartSeconds.toBigDecimal()
        val timeScore = if (winHi > winLo) {
            val raw = input.remainingSeconds.toBigDecimal().subtract(winLo).divide(winHi.subtract(winLo), 6, RoundingMode.HALF_UP)
            BigDecimal.ONE.subtract(raw).coerceIn01()
        } else BigDecimal.ZERO

        // (3) 赔率低估分：edge / edgeFullScale → 满分（默认 0.10，即 edge=10% 视为顶级低估）
        val edgeFullScale = strategy.tailDiffEdgeFullScale.let { if (it <= BigDecimal.ZERO) BigDecimal("0.10") else it }
        val oddsUnderpriceScore = normalize01(input.edge, BigDecimal.ZERO, edgeFullScale)

        // (4) 赔率滞后分：STATIC=modelProb-midImplied / DYNAMIC=领先动量-赔率动量 / HYBRID=两者均值
        val lagFullScale = strategy.tailDiffLagFullScale.let { if (it <= BigDecimal.ZERO) BigDecimal("0.15") else it }
        val staticLag = input.modelProb.subtract(input.midImpliedProb)
        val staticLagScore = normalize01(staticLag, BigDecimal.ZERO, lagFullScale)
        val oddsLagScore = when (strategy.tailDiffOddsLagMode.uppercase()) {
            "DYNAMIC" -> dynamicLagScore(input, strategy) ?: staticLagScore
            "HYBRID" -> {
                val dyn = dynamicLagScore(input, strategy)
                if (dyn != null) staticLagScore.add(dyn).divide(BigDecimal("2"), 6, RoundingMode.HALF_UP) else staticLagScore
            }
            else -> staticLagScore
        }

        // (5) 历史胜率分：statsSampleCount 不足时按 0.5；够时用 (modelProb-floor)/(ceil-floor)
        val historyScore = when {
            input.statsSampleCount < strategy.tailDiffStatsMinSamples ->
                BigDecimal("0.5") // 中性分，避免无统计样本时一律得 0
            input.modelProbSource.startsWith("HYBRID_FALLBACK") || input.modelProbSource == "FALLBACK" ->
                BigDecimal("0.5")
            else -> normalize01(input.modelProb, strategy.tailDiffHistoryProbFloor, strategy.tailDiffHistoryProbCeil)
        }

        // (6) 盘口质量分：spread 越小、深度越大越好（两者均权 0.5）
        val spreadComponent = if (input.spread == null || strategy.tailDiffMaxSpread <= BigDecimal.ZERO) {
            BigDecimal.ZERO
        } else {
            // spread=0 满分；spread>=maxSpread 0 分
            BigDecimal.ONE.subtract(
                input.spread.divide(strategy.tailDiffMaxSpread, 6, RoundingMode.HALF_UP).coerceIn01()
            ).coerceIn01()
        }
        val depthRequired = input.candidateAmountUsdc.multiply(strategy.tailDiffDepthMultiplier).max(BigDecimal.ONE)
        val depthComponent = normalize01(
            input.bidDepthUsd.min(input.askDepthUsd),
            depthRequired,
            depthRequired.multiply(BigDecimal("3"))
        )
        val bookScore = spreadComponent.add(depthComponent).divide(BigDecimal("2"), 6, RoundingMode.HALF_UP).coerceIn01()

        // (7) 数据可靠性分：orderbookAge / priceAge 越新越好
        val obAgeScore = if (strategy.tailDiffMaxOrderbookAgeMs > 0) {
            val ratio = input.orderbookAgeMs.toBigDecimal()
                .divide(strategy.tailDiffMaxOrderbookAgeMs.toBigDecimal(), 6, RoundingMode.HALF_UP)
            BigDecimal.ONE.subtract(ratio.coerceIn01()).coerceIn01()
        } else BigDecimal.ONE
        val priceAgeMs = input.priceAgeMs
        val priceAgeScore = if (priceAgeMs == null || strategy.tailDiffMaxPriceAgeMs <= 0) {
            BigDecimal.ONE
        } else {
            val ratio = priceAgeMs.toBigDecimal()
                .divide(strategy.tailDiffMaxPriceAgeMs.toBigDecimal(), 6, RoundingMode.HALF_UP)
            BigDecimal.ONE.subtract(ratio.coerceIn01()).coerceIn01()
        }
        val dataScore = obAgeScore.add(priceAgeScore).divide(BigDecimal("2"), 6, RoundingMode.HALF_UP).coerceIn01()

        return ComponentScores(
            diff = sigmaScore,
            time = timeScore,
            oddsUnderprice = oddsUnderpriceScore,
            oddsLag = oddsLagScore,
            history = historyScore,
            book = bookScore,
            data = dataScore
        )
    }

    private fun applyWeights(raw: ComponentScores, strategy: CryptoTailStrategy): Components {
        // 权重总和（若 != 100 则按比例归一化，保证总分 0-100）
        val weights = intArrayOf(
            strategy.tailDiffWeightDiff,
            strategy.tailDiffWeightTime,
            strategy.tailDiffWeightOddsUnderprice,
            strategy.tailDiffWeightOddsLag,
            strategy.tailDiffWeightHistory,
            strategy.tailDiffWeightBook,
            strategy.tailDiffWeightData
        )
        val sum = weights.sum().coerceAtLeast(1)
        val scale = BigDecimal("100").divide(sum.toBigDecimal(), 6, RoundingMode.HALF_UP)
        fun w(idx: Int): BigDecimal = weights[idx].toBigDecimal().multiply(scale)
        return Components(
            scoreDiff = raw.diff.multiply(w(0)).setScale(2, RoundingMode.HALF_UP),
            scoreTime = raw.time.multiply(w(1)).setScale(2, RoundingMode.HALF_UP),
            scoreOddsUnderprice = raw.oddsUnderprice.multiply(w(2)).setScale(2, RoundingMode.HALF_UP),
            scoreOddsLag = raw.oddsLag.multiply(w(3)).setScale(2, RoundingMode.HALF_UP),
            scoreHistory = raw.history.multiply(w(4)).setScale(2, RoundingMode.HALF_UP),
            scoreBook = raw.book.multiply(w(5)).setScale(2, RoundingMode.HALF_UP),
            scoreData = raw.data.multiply(w(6)).setScale(2, RoundingMode.HALF_UP)
        )
    }

    /**
     * 动态赔率滞后分（gtp §12）：标的朝领先方向扩大幅度（priceComp）− 同期赔率上行幅度（oddsComp）。
     *  - 标的领先未扩大（<=0）→ 0 分（无滞后机会）
     *  - 标的大幅领先但赔率没跟 → priceComp 高、oddsComp 低 → 高分
     * 数据不可用（任一为 null）→ 返回 null，调用方回退静态滞后。
     */
    private fun dynamicLagScore(input: Input, strategy: CryptoTailStrategy): BigDecimal? {
        val priceMove = input.priceLeadMoveSigma ?: return null
        val oddsMove = input.oddsMoveOverWindow ?: return null
        if (priceMove <= BigDecimal.ZERO) return BigDecimal.ZERO
        val priceFull = strategy.tailDiffLagPriceMoveFullScaleSigma.let { if (it <= BigDecimal.ZERO) BigDecimal("0.5") else it }
        val oddsFull = strategy.tailDiffLagOddsMoveFullScale.let { if (it <= BigDecimal.ZERO) BigDecimal("0.05") else it }
        val priceComp = normalize01(priceMove, BigDecimal.ZERO, priceFull)
        val oddsComp = normalize01(oddsMove, BigDecimal.ZERO, oddsFull)
        return priceComp.subtract(oddsComp).coerceIn01()
    }

    private fun normalize01(value: BigDecimal, min: BigDecimal, max: BigDecimal): BigDecimal {
        if (max <= min) return BigDecimal.ZERO
        if (value <= min) return BigDecimal.ZERO
        if (value >= max) return BigDecimal.ONE
        return value.subtract(min).divide(max.subtract(min), 6, RoundingMode.HALF_UP)
    }

    private fun BigDecimal.coerceIn01(): BigDecimal {
        if (this < BigDecimal.ZERO) return BigDecimal.ZERO
        if (this > BigDecimal.ONE) return BigDecimal.ONE
        return this
    }

    private fun BigDecimal.coerceAtLeastOne(): BigDecimal = if (this < BigDecimal.ONE) BigDecimal.ONE else this
}
