package com.wrbug.polymarketbot.service.cryptotail

/**
 * crypto-tail 决策事件 payloadJson 的强类型映射（C-8）。
 * 替代投影器中「字符串键 Map」的弱类型取值，提供编译期键安全。
 *
 * 约定：所有字段声明为 String? 并给默认值——
 *  - 落库时数值统一 toPlainString()、布尔/整型偶有原生 JSON token，Gson 会把 number/boolean token 也读成字符串；
 *  - 由投影器侧统一用 toBd()/toIntv()/toLng()/toBoolv() 做安全转换（空串/解析失败均返回 null）。
 * 这样既消除键名拼写风险，又保留原有对「字符串/数字混合编码」的鲁棒解析。
 */

/** ORDER_SUBMITTED：下单前信号/盘口/阈值/订单意图全量快照 */
data class OrderSubmittedPayload(
    val marketSlug: String? = null,
    val intervalSeconds: String? = null,
    val open: String? = null,
    val close: String? = null,
    val gap: String? = null,
    val sigmaPerSqrtS: String? = null,
    val remainingSeconds: String? = null,
    val pWin: String? = null,
    val modelSide: String? = null,
    val safeRatio: String? = null,
    val bestBid: String? = null,
    val bestAsk: String? = null,
    val mid: String? = null,
    val effectiveCost: String? = null,
    val edge: String? = null,
    val entryProb: String? = null,
    val entryEdge: String? = null,
    val barrierMinMarketProb: String? = null,
    val sigmaScale: String? = null,
    val maxEntryPrice: String? = null,
    val costBuffer: String? = null,
    val orderType: String? = null,
    val targetPrice: String? = null
)

/** ORDER_RESULT：下单受理/成交/失败结果 */
data class OrderResultPayload(
    val status: String? = null,
    val orderId: String? = null,
    val amountUsdc: String? = null,
    val filledSize: String? = null,
    val filledAmount: String? = null,
    val orderType: String? = null
)

/** SETTLED：链上结算与复盘归因 */
data class SettledPayload(
    val won: String? = null,
    val winnerOutcomeIndex: String? = null,
    val realizedPnl: String? = null,
    val settleTs: String? = null,
    val fillPrice: String? = null,
    val fillSize: String? = null,
    val amountUsdc: String? = null,
    val finalOpen: String? = null,
    val finalClose: String? = null,
    val finalGap: String? = null,
    val settleSource: String? = null
)

/**
 * TAIL_DIFF_SCORE_COMPUTED：尾盘价差模式评分快照（V62）。
 * 决策日志 payload；所有数值用 toPlainString 编码避免精度漂移。
 *
 * 字段分组：
 *  - 价差与方向：rawDiff / diffPct / diffSigma / open / close / modelSide / outcomeIndex
 *  - 时间与窗口：remainingSeconds / windowStartSeconds / windowEndSeconds
 *  - 概率与EV：modelProb / modelProbSource / effectiveCost / edge / midImpliedProb
 *  - 盘口快照：bestBid / bestAsk / spread / bidDepthUsd / askDepthUsd
 *  - 评分明细：componentScores（7 项原始分） / weights（7 项权重） / score / tier / passed / vetoReasons
 *  - 候选金额：baseAmount / amountUsdc / tierMultiplier
 *  - 反抽指标：reverseVelocitySigmaPerSec / maxReverseVelocitySigma
 *  - 阈值快照：minModelProb / minEdge / minDiffSigma / minScore
 */
data class TailDiffScorePayload(
    val rawDiff: String? = null,
    val diffPct: String? = null,
    val diffSigma: String? = null,
    val open: String? = null,
    val close: String? = null,
    val modelSide: String? = null,
    val outcomeIndex: String? = null,
    val remainingSeconds: String? = null,
    val windowStartSeconds: String? = null,
    val windowEndSeconds: String? = null,
    val modelProb: String? = null,
    val modelProbSource: String? = null,
    val effectiveCost: String? = null,
    val edge: String? = null,
    val midImpliedProb: String? = null,
    val bestBid: String? = null,
    val bestAsk: String? = null,
    val spread: String? = null,
    val bidDepthUsd: String? = null,
    val askDepthUsd: String? = null,
    val scoreDiff: String? = null,
    val scoreTime: String? = null,
    val scoreOddsUnderprice: String? = null,
    val scoreOddsLag: String? = null,
    val scoreHistory: String? = null,
    val scoreBook: String? = null,
    val scoreData: String? = null,
    val score: String? = null,
    val tier: String? = null,
    val passed: String? = null,
    val vetoReasons: String? = null,
    val baseAmount: String? = null,
    val amountUsdc: String? = null,
    val tierMultiplier: String? = null,
    val reverseVelocitySigmaPerSec: String? = null,
    val maxReverseVelocitySigma: String? = null,
    val minModelProb: String? = null,
    val minEdge: String? = null,
    val minDiffSigma: String? = null,
    val minScore: String? = null,
    val statsSampleCount: String? = null,
    val statsReversalProb: String? = null,
    val shadowMode: String? = null
)
