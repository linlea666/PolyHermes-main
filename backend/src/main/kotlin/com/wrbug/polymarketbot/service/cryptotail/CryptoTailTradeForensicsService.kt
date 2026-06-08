package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.entity.CryptoTailDecisionEvent
import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import com.wrbug.polymarketbot.entity.CryptoTailTradeForensics
import com.wrbug.polymarketbot.entity.CryptoTailTradeSnapshot
import com.wrbug.polymarketbot.repository.CryptoTailStrategyRepository
import com.wrbug.polymarketbot.repository.CryptoTailTradeForensicsRepository
import com.wrbug.polymarketbot.repository.CryptoTailTradeSnapshotRepository
import com.wrbug.polymarketbot.repository.CryptoTailDecisionEventRepository
import com.wrbug.polymarketbot.service.cryptotail.taildiff.TailDiffBuckets
import com.wrbug.polymarketbot.util.fromJson
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset

/**
 * 成交复盘因子聚合服务。
 *
 * 数据源（复用优先，避免重复解析热路径事件）：
 *  - [CryptoTailTradeSnapshot]：durable 的进场/成交/结算派生（开仓信号、成交、结算、反转标记），作为权威进场/结算口径；
 *  - [CryptoTailDecisionEvent] 的 EXIT_CHECK/EXIT_SIGNAL：持仓中轨迹，派生「反转动态/MAE-MFE/出场归因」。
 *
 * 写入策略：仅在「终态」写一次（已结算 / 已结束且未成交），幂等 upsert；决策日志被清理后老周期不再重算但既有派生量保留。
 * 全程失败安全（单周期异常只记日志），绝不触碰下单/退出/结算热路径。
 *
 * 局限（v1，明确不造假）：requote_count / submit_latency_ms 需热路径打点方可精确，当前留空，列已就位待后续接线。
 * cfg_* 指纹取聚合时刻的策略配置（调度滞后结算 < 数分钟，近似进场配置）；用于按配置版本做 A/B 对比。
 */
@Service
class CryptoTailTradeForensicsService(
    private val forensicsRepository: CryptoTailTradeForensicsRepository,
    private val snapshotRepository: CryptoTailTradeSnapshotRepository,
    private val decisionEventRepository: CryptoTailDecisionEventRepository,
    private val strategyRepository: CryptoTailStrategyRepository,
    @PersistenceContext private val entityManager: EntityManager
) {
    private val logger = LoggerFactory.getLogger(CryptoTailTradeForensicsService::class.java)

    /** 聚合回看窗口：最近 6 小时出现过决策的周期都尝试定稿（覆盖最深 15m 周期与少量重连缺口） */
    private val aggregateLookbackMs = 6L * 3600 * 1000

    /** 未成交终态判定宽限：周期结束后超过此时长仍无成交，则按 UNFILLED 定稿 */
    private val unfilledGraceMs = 5L * 60 * 1000

    /**
     * 已成交但超期未结算的兜底宽限：周期结束后超过此时长仍未结算，则按 ABANDONED 先行定稿（provisional），
     * 避免"成交却永不结算"的单从复盘中消失。取值 < aggregateLookbackMs(6h)，确保仍在调度回看窗内；
     * 若其后真的结算（snapshot.settled=true），aggregateOne 会把该 provisional 行升级为正式结算口径。
     */
    private val abandonedGraceMs = 30L * 60 * 1000

    @Scheduled(fixedDelay = 60_000)
    fun scheduledAggregate() {
        try {
            aggregateRecentTerminal()
        } catch (e: Exception) {
            logger.warn("复盘因子聚合异常: ${e.message}", e)
        }
    }

    fun aggregateRecentTerminal() {
        val since = System.currentTimeMillis() - aggregateLookbackMs
        val periods = decisionEventRepository.findDistinctStrategyPeriodsSince(since)
        for (row in periods) {
            val strategyId = (row[0] as Number).toLong()
            val periodStartUnix = (row[1] as Number).toLong()
            try {
                aggregateOne(strategyId, periodStartUnix, force = false)
            } catch (e: Exception) {
                logger.debug("复盘因子单周期聚合失败: strategyId=$strategyId, period=$periodStartUnix, ${e.message}")
            }
        }
    }

    /**
     * 回填：从 durable 的 trade_snapshot 重建复盘因子（决策日志被清理也可用，但反转动态/出场归因仅在事件仍在时可派生）。
     * 逐周期提交（不包一个大事务）：单周期的 save 走 Spring Data 自身事务，海量回填时避免长事务锁/内存压力，
     * 且失败仅跳过该周期、已处理周期不回滚（幂等回填，重跑可补齐）。
     * @return 处理（写入/更新）的行数
     */
    fun backfill(strategyId: Long, startTs: Long?, endTs: Long?): Int {
        val snapshots = if (startTs != null && endTs != null) {
            snapshotRepository.findAllByStrategyIdAndSubmitTsBetweenOrderByPeriodStartUnixAsc(strategyId, startTs, endTs)
        } else {
            snapshotRepository.findAllByStrategyIdOrderByPeriodStartUnixAsc(strategyId)
        }
        var count = 0
        for (s in snapshots) {
            try {
                if (aggregateOne(strategyId, s.periodStartUnix, force = true)) count++
            } catch (e: Exception) {
                logger.debug("复盘因子回填单周期失败: strategyId=$strategyId, period=${s.periodStartUnix}, ${e.message}")
            }
        }
        return count
    }

    /**
     * 聚合单周期。仅终态写入；非 force 时既有行直接跳过（终态只定稿一次，避免写放大）。
     * @return 是否写入/更新
     */
    @Transactional
    fun aggregateOne(strategyId: Long, periodStartUnix: Long, force: Boolean): Boolean {
        val snapshot = snapshotRepository.findByStrategyIdAndPeriodStartUnix(strategyId, periodStartUnix) ?: return false
        val strategy = strategyRepository.findById(strategyId).orElse(null) ?: return false
        val nowUnix = System.currentTimeMillis() / 1000
        val periodEndUnix = snapshot.periodStartUnix + snapshot.intervalSeconds
        val filled = snapshot.fillStatus == "success" || (snapshot.fillSize != null && snapshot.fillSize.signum() > 0)
        val periodEnded = nowUnix > periodEndUnix + unfilledGraceMs / 1000
        // 成交却超期未结算 → ABANDONED 兜底定稿，防漏记。
        val abandoned = filled && !snapshot.settled && nowUnix > periodEndUnix + abandonedGraceMs / 1000
        val terminal = snapshot.settled || (!filled && periodEnded) || abandoned
        if (!terminal) return false

        val existing = forensicsRepository.findByStrategyIdAndPeriodStartUnix(strategyId, periodStartUnix)
        // 既有行默认只定稿一次；唯一例外：provisional 的 ABANDONED 行在其后真正结算时，允许升级为正式结算口径。
        if (existing != null && !force) {
            val canUpgrade = existing.outcomeCategory == "ABANDONED" && snapshot.settled
            if (!canUpgrade) return false
        }

        val events = decisionEventRepository.findAllByStrategyIdAndPeriodStartUnixOrderByCreatedAtAsc(strategyId, periodStartUnix)
        val row = build(strategy, snapshot, events, filled, existing)
        forensicsRepository.save(row)
        return true
    }

    private fun build(
        strategy: CryptoTailStrategy,
        s: CryptoTailTradeSnapshot,
        events: List<CryptoTailDecisionEvent>,
        filled: Boolean,
        existing: CryptoTailTradeForensics?
    ): CryptoTailTradeForensics {
        val now = System.currentTimeMillis()
        val outcomeIndex = s.outcomeIndex
        val entryGap = s.entryGap
        val entryGapAbs = entryGap?.abs()
        val entryDiffSigma = s.safeRatio
        val entryFillPrice = s.fillPrice

        // 方向是否正确：以结算赢家为准；赢家缺失时退回 won
        val directionCorrect: Boolean? = when {
            s.winnerOutcomeIndex != null && outcomeIndex != null -> s.winnerOutcomeIndex == outcomeIndex
            s.won != null -> s.won
            else -> null
        }

        // ===== 反转动态 / MAE-MFE / 出场归因（扫 EXIT_CHECK/EXIT_SIGNAL） =====
        val dyn = deriveExitDynamics(events, entryGap, entryDiffSigma, entryFillPrice)

        // ===== 分类 =====
        val isTpKind = dyn.exitKind?.contains("TAKE_PROFIT", ignoreCase = true) == true ||
            dyn.exitKind?.contains("TP", ignoreCase = true) == true
        val category: String = when {
            !filled -> "UNFILLED"
            !s.settled -> "ABANDONED" // 成交但超期未结算（provisional，结算后会被升级重算）
            directionCorrect == true -> when {
                dyn.wasCut && !isTpKind -> "CUT_BUT_WOULD_WIN"
                dyn.wasCut -> "WON_TP"
                else -> "WON_HELD"
            }
            directionCorrect == false -> if (dyn.leadReversed == true) "LOST_REVERSED" else "LOST_WRONG_FROM_START"
            else -> if (s.won == true) "WON_HELD" else "UNKNOWN"
        }

        // ===== 反事实：若持有到结算 =====
        val counterfactualHoldPnl: BigDecimal? = if (filled && entryFillPrice != null && s.fillSize != null) {
            if (directionCorrect == true) {
                BigDecimal.ONE.subtract(entryFillPrice).multiply(s.fillSize).setScale(8, RoundingMode.HALF_UP)
            } else if (directionCorrect == false) {
                s.fillAmount?.negate() ?: entryFillPrice.multiply(s.fillSize).negate().setScale(8, RoundingMode.HALF_UP)
            } else null
        } else null
        val cutVsHoldDelta: BigDecimal? = if (counterfactualHoldPnl != null && s.realizedPnl != null) {
            counterfactualHoldPnl.subtract(s.realizedPnl)
        } else null
        val wouldHaveWonIfHeld: Boolean? = directionCorrect

        // ===== 分桶 =====
        val oddsBucket = entryFillPrice?.let { TailDiffBuckets.oddsBucket(it) }
            ?: s.bestAsk?.let { TailDiffBuckets.oddsBucket(it) }
        val diffSigmaBucket = entryDiffSigma?.let { TailDiffBuckets.diffSigmaBucket(it) }
        val remainingBucket = s.remainingSecondsAtEntry?.let { TailDiffBuckets.remainingBucket(it.toInt()) }

        // ===== 时段 =====
        val entryTs = s.submitTs
        val (wallHour, dow) = if (entryTs != null) {
            val dt = Instant.ofEpochMilli(entryTs).atZone(ZoneOffset.UTC)
            dt.hour to dt.dayOfWeek.value
        } else null to null

        // ===== 进场成交质量（SCALP 区间偏离） =====
        val fillVsBandDev: BigDecimal? = if (strategy.mode.value == 4 && entryFillPrice != null) {
            entryFillPrice.subtract(strategy.scalpEntryMaxPrice)
        } else null

        // ===== 配置指纹 =====
        val cfgFingerprint = buildFingerprint(strategy)

        val base = existing ?: CryptoTailTradeForensics(
            strategyId = strategy.id ?: s.strategyId,
            periodStartUnix = s.periodStartUnix,
            correlationId = s.correlationId,
            createdAt = now
        )
        return base.copy(
            strategyId = strategy.id ?: s.strategyId,
            accountId = strategy.accountId,
            marketSlug = s.marketSlug ?: strategy.marketSlugPrefix,
            intervalSeconds = if (s.intervalSeconds > 0) s.intervalSeconds else strategy.intervalSeconds,
            periodStartUnix = s.periodStartUnix,
            triggerId = s.triggerId,
            correlationId = s.correlationId,
            mode = strategy.mode.value,
            outcomeIndex = outcomeIndex,

            entryTs = entryTs,
            entryRemainingSeconds = s.remainingSecondsAtEntry?.toInt(),
            entryOfficialTarget = s.openPrice,
            entryCurrentPrice = s.entryMarkPrice,
            entryGap = entryGap,
            entryGapAbs = entryGapAbs,
            entryGapPct = if (entryGapAbs != null && s.openPrice != null && s.openPrice.signum() != 0)
                entryGapAbs.divide(s.openPrice.abs(), 8, RoundingMode.HALF_UP) else null,
            entryDiffSigma = entryDiffSigma,
            entryPwin = s.pWin,
            entryModelSide = s.modelSide,
            entryBestBid = s.bestBid,
            entryBestAsk = s.bestAsk,
            entryFillPrice = entryFillPrice,
            entryWallHour = wallHour,
            entryDow = dow,

            entryDiffSigmaBucket = diffSigmaBucket,
            entryOddsBucket = oddsBucket,
            entryRemainingBucket = remainingBucket,

            fillVsBandDev = fillVsBandDev,
            entrySlippage = s.slippage,

            leadReversed = dyn.leadReversed,
            firstReversalRemainingSeconds = dyn.firstReversalRemainingSeconds,
            troughSafeRatio = dyn.troughSafeRatio,
            troughGap = dyn.troughGap,
            maxDiffRetracePct = dyn.maxDiffRetracePct,
            minBestBid = dyn.minBestBid,
            peakBestBid = dyn.peakBestBid,
            reversalSampleCount = dyn.sampleCount,
            recoveredAfterReversal = if (dyn.leadReversed == true) (directionCorrect == true) else false,

            maeOdds = dyn.maeOdds,
            mfeOdds = dyn.mfeOdds,
            maeSigma = dyn.maeSigma,
            mfeSigma = dyn.mfeSigma,

            exitKind = dyn.exitKind,
            exitReason = dyn.exitReason,
            wasCut = if (filled) dyn.wasCut else null,
            exitPrice = dyn.exitPrice,
            exitSlippage = dyn.exitSlippage,
            exitExecutableDepthUsd = dyn.exitExecutableDepthUsd,
            holdSeconds = s.holdSeconds,
            settled = s.settled,
            won = s.won,
            winnerOutcomeIndex = s.winnerOutcomeIndex,
            finalOfficialTarget = s.finalOpen,
            finalCurrentPrice = s.finalClose,
            finalGap = s.finalGap,
            realizedPnl = s.realizedPnl,

            wouldHaveWonIfHeld = wouldHaveWonIfHeld,
            counterfactualHoldPnl = counterfactualHoldPnl,
            cutVsHoldDelta = cutVsHoldDelta,

            cfgEntryMinPwin = strategy.scalpEntryMinPwin,
            cfgMinModelProb = strategy.scalpMinModelProb,
            cfgReversalGateEnabled = strategy.scalpReversalGateEnabled,
            cfgMaxDiffRetracePct = strategy.scalpMaxDiffRetracePct,
            cfgMinModelProbAfterEntry = strategy.scalpMinModelProbAfterEntry,
            cfgGapGateEnabled = strategy.scalpGapGateEnabled,
            cfgFingerprint = cfgFingerprint,

            directionCorrect = directionCorrect,
            outcomeCategory = category,
            sourceMaxEventId = events.maxOfOrNull { it.id ?: 0L },
            updatedAt = now
        )
    }

    private data class ExitDynamics(
        val leadReversed: Boolean?,
        val firstReversalRemainingSeconds: Int?,
        val troughSafeRatio: BigDecimal?,
        val troughGap: BigDecimal?,
        val maxDiffRetracePct: BigDecimal?,
        val minBestBid: BigDecimal?,
        val peakBestBid: BigDecimal?,
        val sampleCount: Int?,
        val maeOdds: BigDecimal?,
        val mfeOdds: BigDecimal?,
        val maeSigma: BigDecimal?,
        val mfeSigma: BigDecimal?,
        val wasCut: Boolean,
        val exitKind: String?,
        val exitReason: String?,
        val exitPrice: BigDecimal?,
        val exitSlippage: BigDecimal?,
        val exitExecutableDepthUsd: BigDecimal?
    )

    private fun deriveExitDynamics(
        events: List<CryptoTailDecisionEvent>,
        entryGap: BigDecimal?,
        entryDiffSigma: BigDecimal?,
        entryFillPrice: BigDecimal?
    ): ExitDynamics {
        val entryGapSign = entryGap?.signum()
        var sampleCount = 0
        var troughSafe: BigDecimal? = null
        var troughGap: BigDecimal? = null
        var peakSafe: BigDecimal? = null
        var minBid: BigDecimal? = null
        var peakBid: BigDecimal? = null
        var leadReversed: Boolean? = if (entryGapSign != null && entryGapSign != 0) false else null
        var firstReversalRemaining: Int? = null
        var lastSignal: CryptoTailDecisionEvent? = null
        var lastSignalPayload: ExitCheckPayload? = null

        for (e in events) {
            if (e.eventType != "EXIT_CHECK" && e.eventType != "EXIT_SIGNAL") continue
            val p = e.payloadJson?.fromJson<ExitCheckPayload>() ?: continue
            val curSafe = p.currentSafeRatio.toBd()
            val curGap = p.currentGap.toBd()
            val curBid = p.currentBestBid.toBd()
            val remaining = p.remainingSeconds.toIntv()
            if (curSafe != null || curGap != null || curBid != null) sampleCount++

            if (curSafe != null) {
                if (troughSafe == null || curSafe < troughSafe) {
                    troughSafe = curSafe
                    troughGap = curGap
                }
                if (peakSafe == null || curSafe > peakSafe) peakSafe = curSafe
            }
            if (curBid != null) {
                if (minBid == null || curBid < minBid) minBid = curBid
                if (peakBid == null || curBid > peakBid) peakBid = curBid
            }
            // 领先方向反转：currentGap 符号与进场 gap 反号
            if (entryGapSign != null && entryGapSign != 0 && curGap != null && curGap.signum() != 0 &&
                curGap.signum() != entryGapSign
            ) {
                leadReversed = true
                if (firstReversalRemaining == null && remaining != null) firstReversalRemaining = remaining
            }
            if (e.eventType == "EXIT_SIGNAL") {
                lastSignal = e
                lastSignalPayload = p
            }
        }

        val peakBidEff = peakBid?.let { pb -> entryFillPrice?.let { pb.max(it) } ?: pb } ?: entryFillPrice
        val maxRetrace: BigDecimal? = if (entryDiffSigma != null && entryDiffSigma.signum() > 0 && troughSafe != null) {
            entryDiffSigma.subtract(troughSafe).max(BigDecimal.ZERO).divide(entryDiffSigma, 8, RoundingMode.HALF_UP)
        } else null
        val maeOdds: BigDecimal? = if (entryFillPrice != null && minBid != null) entryFillPrice.subtract(minBid).max(BigDecimal.ZERO) else null
        val mfeOdds: BigDecimal? = if (entryFillPrice != null && peakBidEff != null) peakBidEff.subtract(entryFillPrice).max(BigDecimal.ZERO) else null
        val maeSigma: BigDecimal? = if (entryDiffSigma != null && troughSafe != null) entryDiffSigma.subtract(troughSafe).max(BigDecimal.ZERO) else null
        val mfeSigma: BigDecimal? = if (entryDiffSigma != null && peakSafe != null) peakSafe.subtract(entryDiffSigma).max(BigDecimal.ZERO) else null

        return ExitDynamics(
            // 无任何持仓轨迹样本时不能断言"未反转"，置 null 避免反转率分母被"无数据"单虚高。
            leadReversed = if (sampleCount > 0) leadReversed else null,
            firstReversalRemainingSeconds = firstReversalRemaining,
            troughSafeRatio = troughSafe,
            troughGap = troughGap,
            maxDiffRetracePct = maxRetrace,
            minBestBid = minBid,
            peakBestBid = peakBid,
            sampleCount = if (sampleCount > 0) sampleCount else null,
            maeOdds = maeOdds,
            mfeOdds = mfeOdds,
            maeSigma = maeSigma,
            mfeSigma = mfeSigma,
            wasCut = lastSignal != null,
            exitKind = lastSignal?.gateName ?: lastSignalPayload?.exitReason,
            exitReason = lastSignal?.reason,
            exitPrice = lastSignalPayload?.expectedExitPrice.toBd(),
            exitSlippage = lastSignalPayload?.expectedExitSlippage.toBd(),
            exitExecutableDepthUsd = lastSignalPayload?.executableExitDepthUsd.toBd()
        )
    }

    /** 关键阈值指纹：把进场/反转防线相关配置拼成稳定串取短 md5，用于按配置版本聚合对比 */
    private fun buildFingerprint(s: CryptoTailStrategy): String {
        val raw = listOf(
            s.mode.value,
            s.scalpEntryMinPwin.toPlainString(),
            s.scalpMinModelProb.toPlainString(),
            s.scalpReversalGateEnabled,
            s.scalpMaxDiffRetracePct.toPlainString(),
            s.scalpMinModelProbAfterEntry.toPlainString(),
            s.scalpGapGateEnabled,
            s.scalpMinEntryDiffSigma.toPlainString(),
            s.scalpMinEntryGapAbs.toPlainString()
        ).joinToString("|")
        val digest = MessageDigest.getInstance("MD5").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.substring(0, 16)
    }

    // ===== 多维分组聚合（EntityManager + 维度白名单，防注入） =====

    /** 维度白名单：DTO 维度名 → 表列名 */
    private val dimColumns = mapOf(
        "entryOddsBucket" to "entry_odds_bucket",
        "entryDiffSigmaBucket" to "entry_diff_sigma_bucket",
        "entryRemainingBucket" to "entry_remaining_bucket",
        "entryWallHour" to "entry_wall_hour",
        "entryDow" to "entry_dow",
        "outcomeCategory" to "outcome_category",
        "exitKind" to "exit_kind",
        "outcomeIndex" to "outcome_index",
        "intervalSeconds" to "interval_seconds",
        "marketSlug" to "market_slug",
        "cfgFingerprint" to "cfg_fingerprint"
    )

    fun allowedDimensions(): Set<String> = dimColumns.keys

    /**
     * 按 1~2 个维度分组聚合。维度经白名单校验，非法维度抛 [IllegalArgumentException]。
     * @return 每组一行 [ForensicsAggRow]，按样本数降序
     */
    fun aggregate(
        dim1: String,
        dim2: String?,
        strategyId: Long?,
        marketSlug: String?,
        intervalSeconds: Int?,
        outcomeCategory: String?,
        onlySettled: Boolean,
        startTs: Long?,
        endTs: Long?
    ): List<ForensicsAggRow> {
        val col1 = dimColumns[dim1] ?: throw IllegalArgumentException("invalid dimension: $dim1")
        val col2 = dim2?.let { dimColumns[it] ?: throw IllegalArgumentException("invalid dimension: $it") }
        val groupCols = if (col2 != null) "$col1, $col2" else col1

        val sb = StringBuilder()
        sb.append("SELECT $groupCols, ")
        sb.append("COUNT(*) AS n, ")
        sb.append("SUM(CASE WHEN won=1 THEN 1 ELSE 0 END) AS wins, ")
        sb.append("SUM(CASE WHEN direction_correct=1 THEN 1 ELSE 0 END) AS dir_correct, ")
        sb.append("SUM(CASE WHEN was_cut=1 THEN 1 ELSE 0 END) AS cuts, ")
        // 错杀率分子口径：必须是「被平仓 且 方向最终正确」的单，与分母 cuts 同总体；
        // 不能用 would_have_won_if_held(=directionCorrect，含正常持有获胜单)，否则分子分母异总体、率值失真(可>100%)。
        sb.append("SUM(CASE WHEN was_cut=1 AND direction_correct=1 THEN 1 ELSE 0 END) AS would_win, ")
        sb.append("SUM(CASE WHEN lead_reversed=1 THEN 1 ELSE 0 END) AS reversed_n, ")
        sb.append("SUM(CASE WHEN recovered_after_reversal=1 THEN 1 ELSE 0 END) AS recovered_n, ")
        sb.append("AVG(entry_diff_sigma) AS avg_diff_sigma, ")
        sb.append("AVG(entry_gap_abs) AS avg_gap_abs, ")
        sb.append("AVG(entry_best_ask) AS avg_best_ask, ")
        sb.append("AVG(entry_pwin) AS avg_pwin, ")
        sb.append("AVG(max_diff_retrace_pct) AS avg_retrace, ")
        sb.append("AVG(mae_odds) AS avg_mae_odds, ")
        sb.append("AVG(mfe_odds) AS avg_mfe_odds, ")
        sb.append("AVG(first_reversal_remaining_seconds) AS avg_first_rev_rem, ")
        sb.append("AVG(hold_seconds) AS avg_hold, ")
        sb.append("SUM(realized_pnl) AS sum_pnl, ")
        sb.append("AVG(realized_pnl) AS avg_pnl, ")
        sb.append("SUM(cut_vs_hold_delta) AS sum_cut_vs_hold ")
        sb.append("FROM crypto_tail_trade_forensics WHERE 1=1 ")
        if (strategyId != null) sb.append("AND strategy_id = :strategyId ")
        if (marketSlug != null) sb.append("AND market_slug = :marketSlug ")
        if (intervalSeconds != null) sb.append("AND interval_seconds = :intervalSeconds ")
        if (outcomeCategory != null) sb.append("AND outcome_category = :outcomeCategory ")
        if (onlySettled) sb.append("AND settled = 1 ")
        if (startTs != null) sb.append("AND entry_ts >= :startTs ")
        if (endTs != null) sb.append("AND entry_ts <= :endTs ")
        sb.append("GROUP BY $groupCols ORDER BY n DESC")

        val q = entityManager.createNativeQuery(sb.toString())
        if (strategyId != null) q.setParameter("strategyId", strategyId)
        if (marketSlug != null) q.setParameter("marketSlug", marketSlug)
        if (intervalSeconds != null) q.setParameter("intervalSeconds", intervalSeconds)
        if (outcomeCategory != null) q.setParameter("outcomeCategory", outcomeCategory)
        if (startTs != null) q.setParameter("startTs", startTs)
        if (endTs != null) q.setParameter("endTs", endTs)

        @Suppress("UNCHECKED_CAST")
        val rows = q.resultList as List<Array<Any?>>
        return rows.map { r ->
            var i = 0
            val key1 = r[i++]?.toString()
            val key2 = if (col2 != null) r[i++]?.toString() else null
            ForensicsAggRow(
                key1 = key1,
                key2 = key2,
                count = num(r[i++]).toLong(),
                wins = num(r[i++]).toLong(),
                directionCorrect = num(r[i++]).toLong(),
                cuts = num(r[i++]).toLong(),
                wouldWin = num(r[i++]).toLong(),
                reversedCount = num(r[i++]).toLong(),
                recoveredCount = num(r[i++]).toLong(),
                avgDiffSigma = dec(r[i++]),
                avgGapAbs = dec(r[i++]),
                avgBestAsk = dec(r[i++]),
                avgPwin = dec(r[i++]),
                avgRetrace = dec(r[i++]),
                avgMaeOdds = dec(r[i++]),
                avgMfeOdds = dec(r[i++]),
                avgFirstReversalRemaining = dec(r[i++]),
                avgHoldSeconds = dec(r[i++]),
                sumPnl = dec(r[i++]),
                avgPnl = dec(r[i++]),
                sumCutVsHold = dec(r[i++])
            )
        }
    }

    private fun num(v: Any?): Number = (v as? Number) ?: BigDecimal.ZERO
    private fun dec(v: Any?): BigDecimal? = when (v) {
        null -> null
        is BigDecimal -> v
        is Number -> BigDecimal(v.toString())
        else -> v.toString().toBigDecimalOrNull()
    }

    // 与投影器同口径的安全转换
    private fun String?.toBd(): BigDecimal? = this?.takeIf { it.isNotBlank() }?.let {
        try { BigDecimal(it) } catch (e: NumberFormatException) { null }
    }

    private fun String?.toIntv(): Int? = this?.takeIf { it.isNotBlank() }?.toDoubleOrNull()?.toInt()

    /** 聚合单组结果（供 Controller 组装 DTO） */
    data class ForensicsAggRow(
        val key1: String?,
        val key2: String?,
        val count: Long,
        val wins: Long,
        val directionCorrect: Long,
        val cuts: Long,
        val wouldWin: Long,
        val reversedCount: Long,
        val recoveredCount: Long,
        val avgDiffSigma: BigDecimal?,
        val avgGapAbs: BigDecimal?,
        val avgBestAsk: BigDecimal?,
        val avgPwin: BigDecimal?,
        val avgRetrace: BigDecimal?,
        val avgMaeOdds: BigDecimal?,
        val avgMfeOdds: BigDecimal?,
        val avgFirstReversalRemaining: BigDecimal?,
        val avgHoldSeconds: BigDecimal?,
        val sumPnl: BigDecimal?,
        val avgPnl: BigDecimal?,
        val sumCutVsHold: BigDecimal?
    )
}
