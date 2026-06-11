package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.api.NewOrderRequest
import com.wrbug.polymarketbot.entity.CryptoTailDecisionEvent
import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import com.wrbug.polymarketbot.entity.CryptoTailStrategyTrigger
import com.wrbug.polymarketbot.enums.ExitStatus
import com.wrbug.polymarketbot.enums.TradingMode
import com.wrbug.polymarketbot.repository.CryptoTailStrategyTriggerRepository
import com.wrbug.polymarketbot.service.copytrading.orders.OrderSigningService
import com.wrbug.polymarketbot.util.toJson
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration

/**
 * SCALP 终场反向对冲服务（V96 终场闪针防御 Phase 2 之 B，"彩票式保险"）。
 *
 * 背景（decision-log 20260611 复盘）：主仓深度获利（bid≈0.96）临近结算时遭遇终场闪针翻转，
 * 任何响应式退出都来不及在簿面蒸发前成交，单笔近归零。对侧 token 此时极廉价（ask 0.01~0.05），
 * 花极小权利金买入对侧：翻转发生 → 对侧结算 $1/份对冲主仓归零损失；未翻转 → 仅损失权利金。
 *
 * 触发机制（时间预埋，绝不信号追买）：remaining <= hedgeArmSeconds 且本方 bid >= hedgeMinOwnBid
 * 且主仓在持（未止损、未止盈退出）→ 按风险特征库（F1~F5）评分，命中数 >= hedgeMinFeatureScore 才下单。
 * 每周期最多布防一次（边沿触发，含失败尝试）；所有特征值无论是否启用全量落日志，供回测数据驱动调阈值。
 *
 * 落地结构（复用决策）：对冲单以 triggerType=HEDGE 的独立 trigger 行入账（对侧 tokenId/outcomeIndex），
 * 完整复用下单签名、结算（exitStatus=NONE 走链上 condition 判赢路径）、PnL 管线；
 * 退出评估（exitStatus 恒 NONE 不进退出管理）、风控连亏/并发/日订单/胜率统计均排除 HEDGE 行
 * （见 [CryptoTailStrategyTriggerRepository] 各查询的 V96 注释），日亏合计包含（权利金是真实支出）。
 */
@Service
class CryptoTailScalpHedgeService(
    private val triggerRepository: CryptoTailStrategyTriggerRepository,
    private val accountContextFactory: CryptoTailAccountContextFactory,
    private val orderSigningService: OrderSigningService,
    private val orderbookCache: CryptoTailOrderbookCache,
    private val bookInstabilityTracker: CryptoTailBookInstabilityTracker,
    private val spotLeadService: CryptoTailSpotLeadService,
    private val periodPriceProvider: PeriodPriceProvider,
    private val decisionRecorder: CryptoTailDecisionRecorder
) {

    private val logger = LoggerFactory.getLogger(CryptoTailScalpHedgeService::class.java)

    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + scopeJob)

    /**
     * 周期级布防终态标记："strategyId-periodStartUnix" → true 表示本周期布防已终结
     * （已下单/已失败尝试/确认无需布防），不再评估。10 分钟过期（>> 周期长度）。
     */
    private val armDoneCache: Cache<String, Boolean> = Caffeine.newBuilder()
        .maximumSize(1000)
        .expireAfterWrite(Duration.ofMinutes(10))
        .build()

    /**
     * 在途守卫：同周期同时只跑一个布防评估协程。
     * 用 Caffeine（有界 + 过期）而非裸 ConcurrentHashMap：每 (strategy, period) 一条目，
     * 裸 Map 永不清理会无界累积；过期清理也避免了手动 remove 与并发 getOrPut 之间的竞态窗口。
     */
    private val armInFlight: Cache<String, java.util.concurrent.atomic.AtomicBoolean> = Caffeine.newBuilder()
        .maximumSize(2000)
        .expireAfterAccess(Duration.ofMinutes(10))
        .build()

    /**
     * 评估节流：key → 上次评估启动时间(ms)。瞬态 skip（无主仓/bid 未达标/评分不足/保费过贵）
     * 按设计在窗口内持续复评，但 WS tick 可达 10~30 条/s，每次评估含 1~3 次 DB 查询——
     * 无节流等于布防窗内对 DB 持续打点。500ms 间隔下复评时效不受影响（窗口长 25s 级）。
     */
    private val lastEvalAtCache: Cache<String, Long> = Caffeine.newBuilder()
        .maximumSize(2000)
        .expireAfterWrite(Duration.ofMinutes(10))
        .build()

    /** HEDGE_ARM_CHECK 日志去重：同 (周期, skip 原因类别) 只落一条，防 WS 高频 tick 刷屏 */
    private val armLogDedupCache: Cache<String, Boolean> = Caffeine.newBuilder()
        .maximumSize(2000)
        .expireAfterWrite(Duration.ofMinutes(10))
        .build()

    /**
     * WS tick 驱动入口（由 [CryptoTailOrderbookWsService.onBestBid] 在布防窗口内调用，非阻塞）。
     * 调用方已保证 strategy.mode==SCALP_FLIP 且 scalpHedgeEnabled 且 remaining ∈ (0, hedgeArmSeconds]。
     */
    fun maybeArm(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        marketTitle: String?,
        tokenIds: List<String>,
        nowSeconds: Long
    ) {
        val strategyId = strategy.id ?: return
        val key = "$strategyId-$periodStartUnix"
        if (armDoneCache.getIfPresent(key) != null) return
        // 评估节流：距上次评估启动不足 EVAL_THROTTLE_MS 直接返回（先于在途守卫，纯内存零开销）
        val nowMs = System.currentTimeMillis()
        val lastEvalAt = lastEvalAtCache.getIfPresent(key)
        if (lastEvalAt != null && nowMs - lastEvalAt < EVAL_THROTTLE_MS) return
        val guard = armInFlight.get(key) { java.util.concurrent.atomic.AtomicBoolean(false) }
        if (!guard.compareAndSet(false, true)) return
        lastEvalAtCache.put(key, nowMs)
        scope.launch {
            try {
                evaluateArm(strategy, periodStartUnix, marketTitle, tokenIds, nowSeconds)
            } catch (e: Exception) {
                logger.error("对冲布防评估异常 strategyId=$strategyId period=$periodStartUnix, ${e.message}", e)
            } finally {
                guard.set(false)
            }
        }
    }

    private suspend fun evaluateArm(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        marketTitle: String?,
        tokenIds: List<String>,
        nowSeconds: Long
    ) {
        val strategyId = strategy.id ?: return
        val key = "$strategyId-$periodStartUnix"
        val remainingSeconds = (periodStartUnix + strategy.intervalSeconds - nowSeconds).toInt()
        if (remainingSeconds <= 0) {
            armDoneCache.put(key, true)
            return
        }

        // 1) 主仓在持检查：本周期主入场（HEDGE 已被查询排除）成交且未退出
        val main = triggerRepository.findByStrategyIdAndPeriodStartUnix(strategyId, periodStartUnix)
        if (main == null || main.status != "success" || main.resolved) {
            recordArmSkipOnce(strategy, periodStartUnix, "NO_MAIN_POSITION", "本周期无在持主仓（未入场/未成交/已结算），不布防", null, null)
            return
        }
        val mainRemaining = main.remainingSize ?: BigDecimal.ZERO
        if (main.exitStatus !in listOf(ExitStatus.OPEN.name, ExitStatus.PARTIAL_EXIT.name) || mainRemaining <= DUST_THRESHOLD) {
            // 主仓已退出（止损砍掉或预挂止盈成交）→ 没有归零风险可保，本周期布防终结
            armDoneCache.put(key, true)
            recordArmSkipOnce(strategy, periodStartUnix, "MAIN_EXITED", "主仓已退出(exitStatus=${main.exitStatus} remaining=${mainRemaining.toPlainString()})，无需保险", main, null)
            return
        }
        // 2) 重启幂等：DB 已有本周期 HEDGE 行（含失败尝试）→ 终结
        if (triggerRepository.findFirstByStrategyIdAndPeriodStartUnixAndTriggerType(strategyId, periodStartUnix, HEDGE_TRIGGER_TYPE) != null) {
            armDoneCache.put(key, true)
            return
        }

        // 3) 深度获利门槛：本方 bestBid >= hedgeMinOwnBid（只给"看似已赢"的仓位买保险）
        val ownTokenId = main.tokenId ?: run {
            armDoneCache.put(key, true)
            return
        }
        val ownOb = orderbookCache.get(ownTokenId)
        val ownBid = ownOb?.bestBid
        if (ownBid == null || ownBid < strategy.scalpHedgeMinOwnBid) {
            recordArmSkipOnce(strategy, periodStartUnix, "OWN_BID_BELOW_MIN",
                "本方 bid=${ownBid?.toPlainString() ?: "-"}<${strategy.scalpHedgeMinOwnBid.toPlainString()}，未达深度获利门槛（瞬态，窗口内持续复评）", main, ownBid)
            return
        }

        // 4) 对侧盘口
        val oppOutcomeIndex = 1 - main.outcomeIndex
        val oppTokenId = tokenIds.getOrNull(oppOutcomeIndex) ?: run {
            armDoneCache.put(key, true)
            return
        }
        val oppOb = orderbookCache.get(oppTokenId)
        val oppAsk = oppOb?.bestAsk

        // 5) 风险特征库 F1~F5：无论是否启用全量计算并落日志；仅"已启用(配置>0)且命中"计入评分
        val features = computeFeatures(strategy, main, ownTokenId, oppAsk, remainingSeconds)
        val score = features.count { it.enabled && it.hit }

        val featurePayload = features.associate { f ->
            "feature${f.id}_${f.name}" to mapOf(
                "enabled" to f.enabled,
                "hit" to f.hit,
                "value" to f.value,
                "threshold" to f.threshold
            )
        }
        val basePayload = mapOf(
            "mainTriggerId" to main.id,
            "ownBid" to (ownBid.toPlainString()),
            "ownTokenId" to ownTokenId,
            "oppTokenId" to oppTokenId,
            "oppositeAsk" to (oppAsk?.toPlainString() ?: ""),
            "oppositeAskDepthUsd" to (oppOb?.askDepthUsd?.toPlainString() ?: ""),
            "remainingSeconds" to remainingSeconds,
            "featureScore" to score,
            "hedgeMinFeatureScore" to strategy.scalpHedgeMinFeatureScore,
            "hedgeArmSeconds" to strategy.scalpHedgeArmSeconds,
            "hedgeMinOwnBid" to strategy.scalpHedgeMinOwnBid.toPlainString(),
            "hedgeMaxPrice" to strategy.scalpHedgeMaxPrice.toPlainString(),
            "hedgeBudgetUsdc" to strategy.scalpHedgeBudgetUsdc.toPlainString()
        ).plus(featurePayload)

        if (score < strategy.scalpHedgeMinFeatureScore) {
            recordArmCheck(strategy, periodStartUnix, main, armed = false,
                reason = "特征评分不足: score=$score<${strategy.scalpHedgeMinFeatureScore}（瞬态，窗口内持续复评）",
                payload = basePayload, dedupKey = "$key-SCORE_LOW-$score")
            return
        }
        // 6) 保费检查：对侧 ask 缺失或高于限价 → 赔率不足，跳过（瞬态：闪针前 ask 可能回落）
        if (oppAsk == null || oppAsk > strategy.scalpHedgeMaxPrice) {
            recordArmCheck(strategy, periodStartUnix, main, armed = false,
                reason = "对侧保费过贵/无卖盘: oppAsk=${oppAsk?.toPlainString() ?: "-"}>${strategy.scalpHedgeMaxPrice.toPlainString()}（瞬态，窗口内持续复评）",
                payload = basePayload, dedupKey = "$key-PREMIUM_HIGH")
            return
        }
        // 7) 份额按"最坏价口径"算（预算/限价），保证实际支出 <= 预算；须满足 CLOB 最小 5 股
        val size = strategy.scalpHedgeBudgetUsdc
            .divide(strategy.scalpHedgeMaxPrice, SIZE_SCALE, RoundingMode.DOWN)
        if (size < MIN_ORDER_SHARES) {
            armDoneCache.put(key, true)
            recordArmCheck(strategy, periodStartUnix, main, armed = false,
                reason = "预算不足最小单: size=${size.toPlainString()}<${MIN_ORDER_SHARES.toPlainString()} 股(预算=${strategy.scalpHedgeBudgetUsdc.toPlainString()}/限价=${strategy.scalpHedgeMaxPrice.toPlainString()})，配置性终结",
                payload = basePayload, dedupKey = "$key-SIZE_TOO_SMALL")
            return
        }

        // 8) 布防开火：先落终态标记（边沿触发，无论成败本周期仅此一次）
        armDoneCache.put(key, true)
        recordArmCheck(strategy, periodStartUnix, main, armed = true,
            reason = "布防条件满足: ownBid=${ownBid.toPlainString()} score=$score oppAsk=${oppAsk.toPlainString()} size=${size.toPlainString()} → 提交对侧 FAK 限价=${strategy.scalpHedgeMaxPrice.toPlainString()}",
            payload = basePayload.plus("hedgeSize" to size.toPlainString()), dedupKey = null)
        placeHedgeOrder(strategy, main, periodStartUnix, marketTitle, oppTokenId, oppOutcomeIndex, oppAsk, size, remainingSeconds, basePayload)
    }

    /** 单特征评估结果（值与阈值均落日志，供回测调参） */
    private data class FeatureEval(
        val id: String,
        val name: String,
        val enabled: Boolean,
        val hit: Boolean,
        val value: String,
        val threshold: String
    )

    /**
     * 风险特征库 F1~F5。每个特征"配置=0/关 → enabled=false 仅记录不计分"；值不可得时 hit=false（fail-safe 不虚报）。
     */
    private fun computeFeatures(
        strategy: CryptoTailStrategy,
        main: CryptoTailStrategyTrigger,
        ownTokenId: String,
        oppAsk: BigDecimal?,
        remainingSeconds: Int
    ): List<FeatureEval> {
        val features = mutableListOf<FeatureEval>()

        // F1 盘口不稳定记忆：本周期内最近 lookback 秒出现过盘口异常（复用 C 的分类器与 askJump 阈值）
        run {
            val lookback = strategy.scalpHedgeFeatureInstabilityLookbackSec
            val anomaly = if (lookback > 0) {
                bookInstabilityTracker.recentAnomaly(ownTokenId, lookback, strategy.scalpBookInstabilityAskJump)
            } else null
            features += FeatureEval(
                "F1", "bookInstability", enabled = lookback > 0, hit = anomaly != null,
                value = anomaly?.let { "${it.type}:${it.detail}" } ?: "none",
                threshold = "lookback=${lookback}s askJump>=${strategy.scalpBookInstabilityAskJump.toPlainString()}"
            )
        }

        // F2 现货安全垫薄：现货新鲜且 |spotGap| < 阈值（领先优势随时可被一根针打穿）
        val spotLead = try {
            if (strategy.scalpSpotLeadEnabled) spotLeadService.evaluate(strategy, main, remainingSeconds) else null
        } catch (e: Exception) {
            logger.debug("对冲特征现货信号计算失败: ${e.message}")
            null
        }
        run {
            val cushion = strategy.scalpHedgeFeatureSpotCushionUsd
            val enabled = cushion > BigDecimal.ZERO
            val gapAbs = spotLead?.takeIf { it.fresh }?.spotGap?.abs()
            features += FeatureEval(
                "F2", "spotCushionThin", enabled = enabled, hit = enabled && gapAbs != null && gapAbs < cushion,
                value = gapAbs?.toPlainString() ?: "unavailable",
                threshold = "absSpotGap<${cushion.toPlainString()}"
            )
        }

        // F3 领先优势萎缩：当前 |gap|（结算同源 Chainlink）<= 入场 |entryGap| × ratio（动能衰减）
        run {
            val ratio = strategy.scalpHedgeFeatureGapShrinkRatio
            val enabled = ratio > BigDecimal.ZERO
            val entryGapAbs = main.entryGap?.abs()
            val currentGapAbs = try {
                periodPriceProvider.getCurrentOpenClose(strategy.marketSlugPrefix, strategy.intervalSeconds, main.periodStartUnix)
                    ?.let { (open, close) -> close.subtract(open).abs() }
            } catch (e: Exception) {
                null
            }
            val hit = enabled && entryGapAbs != null && entryGapAbs > BigDecimal.ZERO && currentGapAbs != null &&
                currentGapAbs <= entryGapAbs.multiply(ratio)
            features += FeatureEval(
                "F3", "gapShrink", enabled = enabled, hit = hit,
                value = "entry=${entryGapAbs?.toPlainString() ?: "-"} current=${currentGapAbs?.toPlainString() ?: "-"}",
                threshold = "current<=entry×${ratio.toPlainString()}"
            )
        }

        // F4 近期翻转记忆：该策略最近 N 个已结算周期（HEDGE 已排除）出现过"大亏(亏损>成本一半)结算"
        run {
            val lookback = strategy.scalpHedgeFeatureRecentFlipLookback
            val enabled = lookback > 0
            var flipCount = 0
            if (enabled) {
                try {
                    val recent = triggerRepository.findLatestResolvedByStrategyId(
                        strategyId = strategy.id ?: 0L,
                        pageable = org.springframework.data.domain.PageRequest.of(0, lookback)
                    )
                    flipCount = recent.count { t ->
                        val pnl = t.realizedPnl
                        val cost = t.filledAmount ?: t.amountUsdc
                        pnl != null && cost > BigDecimal.ZERO && pnl < cost.multiply(BIG_LOSS_RATIO).negate()
                    }
                } catch (e: Exception) {
                    logger.debug("对冲特征近期翻转查询失败: ${e.message}")
                }
            }
            features += FeatureEval(
                "F4", "recentFlip", enabled = enabled, hit = enabled && flipCount > 0,
                value = "flipCount=$flipCount",
                threshold = "lookback=${lookback}周期 pnl<-cost×${BIG_LOSS_RATIO.toPlainString()}"
            )
        }

        // F5 对侧 ask 抬升：对侧 bestAsk >= 阈值（市场仍在给尾部风险定价，对侧没死透）
        run {
            val floor = strategy.scalpHedgeFeatureOppAskFloor
            val enabled = floor > BigDecimal.ZERO
            features += FeatureEval(
                "F5", "oppAskElevated", enabled = enabled, hit = enabled && oppAsk != null && oppAsk >= floor,
                value = oppAsk?.toPlainString() ?: "unavailable",
                threshold = "oppAsk>=${floor.toPlainString()}"
            )
        }
        return features
    }

    /**
     * 提交对冲单（对侧 FAK 限价买）并以 triggerType=HEDGE 落 trigger 行。
     * FAK 限价 = hedgeMaxPrice（最差可接受保费），实际成交价为对手盘价（<= 限价）；
     * 零成交/被拒也落 fail 行（每周期至多一次尝试的 DB 幂等凭据）。
     */
    private suspend fun placeHedgeOrder(
        strategy: CryptoTailStrategy,
        main: CryptoTailStrategyTrigger,
        periodStartUnix: Long,
        marketTitle: String?,
        oppTokenId: String,
        oppOutcomeIndex: Int,
        oppAsk: BigDecimal,
        size: BigDecimal,
        remainingSeconds: Int,
        basePayload: Map<String, Any?>
    ) {
        val ctx = accountContextFactory.build(strategy) ?: run {
            logger.warn("对冲下单无法构建账户上下文: strategyId=${strategy.id}, accountId=${strategy.accountId}")
            return
        }
        val limitPrice = strategy.scalpHedgeMaxPrice
        val sizeStr = size.toPlainString()
        recordHedgeEvent(strategy, periodStartUnix, main, "HEDGE_SUBMITTED", passed = true,
            reason = "对冲单提交: token=$oppTokenId 限价=${limitPrice.toPlainString()} size=$sizeStr (oppAsk=${oppAsk.toPlainString()})",
            payload = basePayload.plus(mapOf("limitPrice" to limitPrice.toPlainString(), "size" to sizeStr)))

        var orderId: String? = null
        var failReason: String? = null
        var filledSize: BigDecimal? = null
        var filledAmount: BigDecimal? = null
        try {
            val signedOrder = orderSigningService.createAndSignOrder(
                privateKey = ctx.decryptedPrivateKey,
                makerAddress = ctx.proxyAddress,
                tokenId = oppTokenId,
                side = "BUY",
                price = limitPrice.toPlainString(),
                size = sizeStr,
                signatureType = ctx.signatureType
            )
            val resp = ctx.clobApi.createOrder(NewOrderRequest(order = signedOrder, owner = ctx.apiKey, orderType = "FAK"))
            if (resp.isSuccessful && resp.body() != null) {
                val body = resp.body()!!
                if (body.success && body.orderId != null) {
                    orderId = body.orderId
                    // BUY 侧：takingAmount=成交份额，makingAmount=支出 USDC（与入场 fallbackToTaker 同口径）
                    val fs = body.takingAmount?.toSafeBigDecimal() ?: BigDecimal.ZERO
                    val fa = body.makingAmount?.toSafeBigDecimal() ?: BigDecimal.ZERO
                    if (fs > BigDecimal.ZERO && fa > BigDecimal.ZERO) {
                        filledSize = fs
                        filledAmount = fa
                    } else {
                        failReason = "FAK 零成交 status=${body.status ?: ""}"
                    }
                } else {
                    failReason = body.errorMsg ?: body.getErrorMessage()
                }
            } else {
                failReason = resp.errorBody()?.string().orEmpty().ifEmpty { "请求失败" }
            }
        } catch (e: Exception) {
            failReason = e.message ?: e.toString()
            logger.error("对冲下单异常 strategyId=${strategy.id} period=$periodStartUnix, ${e.message}", e)
        }

        val fsFinal = filledSize
        val faFinal = filledAmount
        val filled = fsFinal != null && faFinal != null
        val entryFillPrice = if (fsFinal != null && faFinal != null && fsFinal > BigDecimal.ZERO) {
            faFinal.divide(fsFinal, 8, RoundingMode.HALF_UP)
        } else null
        // HEDGE trigger 行：exitStatus 恒 NONE（不进退出管理，持有到结算走链上 condition 判赢路径）；
        // remainingSize 恒 null（退出 poller 的 remainingSize IS NOT NULL 过滤天然排除）。
        val hedgeTrigger = CryptoTailStrategyTrigger(
            strategyId = strategy.id ?: 0L,
            periodStartUnix = periodStartUnix,
            marketTitle = marketTitle,
            outcomeIndex = oppOutcomeIndex,
            triggerPrice = entryFillPrice ?: limitPrice,
            amountUsdc = strategy.scalpHedgeBudgetUsdc,
            filledSize = filledSize,
            filledAmount = filledAmount,
            orderType = "FAK",
            tokenId = oppTokenId,
            orderId = orderId,
            status = if (filled) "success" else "fail",
            failReason = failReason,
            triggerType = HEDGE_TRIGGER_TYPE,
            mode = TradingMode.SCALP_FLIP,
            exitStatus = ExitStatus.NONE.name,
            entryFillPrice = entryFillPrice,
            entryRemainingSeconds = remainingSeconds
        )
        val saved = try {
            triggerRepository.save(hedgeTrigger)
        } catch (e: Exception) {
            logger.error("对冲 trigger 行落库失败 strategyId=${strategy.id} period=$periodStartUnix, ${e.message}", e)
            null
        }
        recordHedgeEvent(strategy, periodStartUnix, main, "HEDGE_RESULT", passed = filled,
            reason = if (filled) {
                "对冲已成交: orderId=$orderId size=${fsFinal?.toPlainString()} 权利金=${faFinal?.toPlainString()} 均价=${entryFillPrice?.toPlainString()}（翻转时对侧结算 \$1/份）"
            } else {
                "对冲下单失败: ${failReason ?: "unknown"}"
            },
            payload = basePayload.plus(mapOf(
                "hedgeTriggerId" to (saved?.id ?: ""),
                "orderId" to (orderId ?: ""),
                "limitPrice" to limitPrice.toPlainString(),
                "size" to sizeStr,
                "filledSize" to (filledSize?.toPlainString() ?: ""),
                "filledAmount" to (filledAmount?.toPlainString() ?: ""),
                "avgPrice" to (entryFillPrice?.toPlainString() ?: ""),
                "failReason" to (failReason ?: "")
            )),
            triggerId = saved?.id ?: main.id, outcomeIndex = oppOutcomeIndex)
        if (filled) {
            logger.info(
                "终场对冲已布防: strategyId=${strategy.id} period=$periodStartUnix mainTriggerId=${main.id} " +
                    "oppToken=$oppTokenId size=${fsFinal?.toPlainString()} premium=${faFinal?.toPlainString()}"
            )
        } else {
            logger.warn("终场对冲布防失败: strategyId=${strategy.id} period=$periodStartUnix reason=$failReason")
        }
    }

    /** HEDGE_ARM_CHECK 事件（带去重：dedupKey 非空时同 key 只落一条；armed=true 恒落） */
    private fun recordArmCheck(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        main: CryptoTailStrategyTrigger?,
        armed: Boolean,
        reason: String,
        payload: Map<String, Any?>,
        dedupKey: String?
    ) {
        if (dedupKey != null && armLogDedupCache.getIfPresent(dedupKey) != null) return
        if (dedupKey != null) armLogDedupCache.put(dedupKey, true)
        recordHedgeEvent(strategy, periodStartUnix, main, "HEDGE_ARM_CHECK", passed = armed, reason = reason,
            payload = payload.plus("armed" to armed))
    }

    /** 轻量 skip 记录（主仓缺失等早退场景，特征未计算）：同周期同原因只落一条 */
    private fun recordArmSkipOnce(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        skipReason: String,
        reason: String,
        main: CryptoTailStrategyTrigger?,
        ownBid: BigDecimal?
    ) {
        val dedupKey = "${strategy.id}-$periodStartUnix-$skipReason"
        if (armLogDedupCache.getIfPresent(dedupKey) != null) return
        armLogDedupCache.put(dedupKey, true)
        recordHedgeEvent(strategy, periodStartUnix, main, "HEDGE_ARM_CHECK", passed = false, reason = reason,
            payload = mapOf(
                "armed" to false,
                "skipReason" to skipReason,
                "mainTriggerId" to (main?.id ?: ""),
                "ownBid" to (ownBid?.toPlainString() ?: "")
            ))
    }

    private fun recordHedgeEvent(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        main: CryptoTailStrategyTrigger?,
        eventType: String,
        passed: Boolean,
        reason: String,
        payload: Map<String, Any?>,
        triggerId: Long? = null,
        outcomeIndex: Int? = null
    ) {
        decisionRecorder.record(
            CryptoTailDecisionEvent(
                strategyId = strategy.id ?: 0L,
                periodStartUnix = periodStartUnix,
                correlationId = "${strategy.id}-$periodStartUnix-hedge",
                eventType = eventType,
                gateName = "SCALP_HEDGE",
                passed = passed,
                reason = reason,
                payloadJson = payload
                    .plus("strategyId" to (strategy.id ?: ""))
                    .plus("strategyName" to (strategy.name ?: ""))
                    .plus("marketSlug" to strategy.marketSlugPrefix)
                    .toJson(),
                outcomeIndex = outcomeIndex ?: main?.outcomeIndex,
                triggerId = triggerId ?: main?.id
            )
        )
    }

    @PreDestroy
    fun destroy() {
        scopeJob.cancel()
    }

    companion object {
        const val HEDGE_TRIGGER_TYPE: String = "HEDGE"

        /** dust 容差：与 BracketExitService/Reconciler 对齐，主仓残余 <= 此值视为已退出 */
        private val DUST_THRESHOLD: BigDecimal = BigDecimal("0.01")

        /** Polymarket CLOB 最小下单份额 */
        private val MIN_ORDER_SHARES: BigDecimal = BigDecimal("5")

        /** F4 大亏判定：单笔结算亏损 > 成本×此比例 视为"翻转大亏"（区分普通小亏止损） */
        private val BIG_LOSS_RATIO: BigDecimal = BigDecimal("0.5")

        /** 份额精度（与 OrderSigningService roundConfig.size 一致） */
        private const val SIZE_SCALE: Int = 2

        /** 布防评估最小间隔：瞬态 skip 的窗口内复评按此节流（WS tick 10~30/s，无节流会对 DB 持续打点） */
        private const val EVAL_THROTTLE_MS: Long = 500L
    }
}
