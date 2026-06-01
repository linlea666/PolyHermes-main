package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.dto.CryptoTailCalibrationBin
import com.wrbug.polymarketbot.dto.CryptoTailCalibrationResponse
import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import com.wrbug.polymarketbot.entity.CryptoTailTradeSnapshot
import com.wrbug.polymarketbot.repository.CryptoTailTradeSnapshotRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap

/**
 * crypto-tail 校准统计与放量闸服务。
 * 基于「单笔成交快照」中的预测 pWin 与实际结算结果(won/realizedPnl)，计算可靠性分箱、整体校准误差与净 EV，
 * 并据此判定放量闸是否达标（小额实盘校准达标后才放大下注）。
 * 放量判定供下单热路径调用，带短 TTL 缓存避免每单聚合。
 */
@Service
class CryptoTailCalibrationService(
    private val snapshotRepository: CryptoTailTradeSnapshotRepository
) {

    private val logger = LoggerFactory.getLogger(CryptoTailCalibrationService::class.java)

    private val scale = 8

    /** 放量判定缓存：strategyId -> (computedAtMs, decision)，TTL 内复用，避免每单聚合 */
    private val gateCache = ConcurrentHashMap<Long, Pair<Long, ScalingDecision>>()
    private val gateCacheTtlMs = 30_000L

    /** 放量决策：是否钳制为小额、是否达标、生效金额、原因 */
    data class ScalingDecision(
        val gateEnabled: Boolean,
        val qualified: Boolean,
        val useProbe: Boolean,
        val sampleCount: Long,
        val reason: String
    )

    /** 整体校准聚合结果（内部用） */
    private data class CalibrationAgg(
        val sampleCount: Long,
        val winRate: BigDecimal?,
        val calibrationError: BigDecimal?,
        val totalNetPnl: BigDecimal,
        val avgNetPnl: BigDecimal?,
        val bins: List<CryptoTailCalibrationBin>
    )

    /**
     * 下单热路径：评估放量闸。gate 关闭→FULL；开启→按缓存的校准达标判定钳制小额或放量。
     * 缓存过期或缺失时实时聚合一次。
     */
    fun evaluateScalingGate(strategy: CryptoTailStrategy): ScalingDecision {
        val strategyId = strategy.id ?: return ScalingDecision(false, true, false, 0L, "策略ID缺失，按 FULL")
        if (!strategy.calibrationGateEnabled) {
            return ScalingDecision(false, true, false, 0L, "放量闸未开启")
        }
        val now = System.currentTimeMillis()
        val cached = gateCache[strategyId]
        if (cached != null && now - cached.first < gateCacheTtlMs) {
            return cached.second
        }
        val decision = computeScalingDecision(strategy)
        gateCache[strategyId] = now to decision
        return decision
    }

    private fun computeScalingDecision(strategy: CryptoTailStrategy): ScalingDecision {
        val agg = aggregate(strategy.id!!)
        val minSamples = strategy.calibrationMinSamples.toLong()
        val maxError = strategy.calibrationMaxError
        // 达标条件：样本量充足 且 校准误差达标 且 净 EV > 0（每笔平均净盈亏为正）
        val enoughSamples = agg.sampleCount >= minSamples
        val errorOk = agg.calibrationError != null && agg.calibrationError <= maxError
        val evPositive = agg.avgNetPnl != null && agg.avgNetPnl > BigDecimal.ZERO
        val qualified = enoughSamples && errorOk && evPositive
        val reason = when {
            qualified -> "已达标放量：样本=${agg.sampleCount} 校准误差=${agg.calibrationError?.toPlainString()} 平均净EV=${agg.avgNetPnl?.toPlainString()}"
            !enoughSamples -> "样本不足(${agg.sampleCount}/$minSamples)，钳制小额"
            !errorOk -> "校准误差未达标(${agg.calibrationError?.toPlainString() ?: "-"}>${maxError.toPlainString()})，钳制小额"
            else -> "净EV未转正(平均=${agg.avgNetPnl?.toPlainString() ?: "-"})，钳制小额"
        }
        return ScalingDecision(
            gateEnabled = true,
            qualified = qualified,
            useProbe = !qualified,
            sampleCount = agg.sampleCount,
            reason = reason
        )
    }

    /** 查询接口：返回完整校准统计 + 放量闸状态，供监控页展示 */
    fun getCalibration(strategy: CryptoTailStrategy): CryptoTailCalibrationResponse {
        val strategyId = strategy.id ?: 0L
        val agg = aggregate(strategyId)
        val decision = if (strategy.calibrationGateEnabled) computeScalingDecision(strategy)
        else ScalingDecision(false, true, false, agg.sampleCount, "放量闸未开启")
        return CryptoTailCalibrationResponse(
            strategyId = strategyId,
            sampleCount = agg.sampleCount,
            winRate = agg.winRate?.toPlainString(),
            calibrationError = agg.calibrationError?.toPlainString(),
            totalNetPnl = agg.totalNetPnl.toPlainString(),
            avgNetPnl = agg.avgNetPnl?.toPlainString(),
            gateEnabled = strategy.calibrationGateEnabled,
            qualified = decision.qualified,
            scalingMode = if (strategy.calibrationGateEnabled && decision.useProbe) "PROBE" else "FULL",
            probeAmountUsdc = strategy.probeAmountUsdc.toPlainString(),
            minSamples = strategy.calibrationMinSamples,
            maxError = strategy.calibrationMaxError.toPlainString(),
            reason = decision.reason,
            bins = agg.bins
        )
    }

    /** 聚合某策略所有已结算成交样本的可靠性分箱与整体指标 */
    private fun aggregate(strategyId: Long): CalibrationAgg {
        val samples: List<CryptoTailTradeSnapshot> =
            snapshotRepository.findAllByStrategyIdAndSettledTrueAndWonIsNotNullAndPWinIsNotNull(strategyId)
        if (samples.isEmpty()) {
            return CalibrationAgg(0L, null, null, BigDecimal.ZERO, null, emptyList())
        }
        val total = samples.size.toLong()
        val winCount = samples.count { it.won == true }.toLong()
        val winRate = BigDecimal(winCount).divide(BigDecimal(total), scale, RoundingMode.HALF_UP)
        val totalNetPnl = samples.fold(BigDecimal.ZERO) { acc, s -> acc.add(s.realizedPnl ?: BigDecimal.ZERO) }
        val avgNetPnl = totalNetPnl.divide(BigDecimal(total), scale, RoundingMode.HALF_UP)

        // 按 pwinBucket 分箱（缺失则按 pWin 重算 floor(pWin*20)，0~19）
        val grouped = samples.groupBy { s ->
            s.pwinBucket ?: ((s.pWin ?: BigDecimal.ZERO).multiply(BigDecimal(20)).setScale(0, RoundingMode.FLOOR).toInt().coerceIn(0, 19))
        }.toSortedMap()

        val bins = mutableListOf<CryptoTailCalibrationBin>()
        var weightedErrorNumerator = BigDecimal.ZERO
        for ((bucket, list) in grouped) {
            val cnt = list.size.toLong()
            val predicted = list.fold(BigDecimal.ZERO) { acc, s -> acc.add(s.pWin ?: BigDecimal.ZERO) }
                .divide(BigDecimal(cnt), scale, RoundingMode.HALF_UP)
            val wins = list.count { it.won == true }.toLong()
            val actual = BigDecimal(wins).divide(BigDecimal(cnt), scale, RoundingMode.HALF_UP)
            val netPnl = list.fold(BigDecimal.ZERO) { acc, s -> acc.add(s.realizedPnl ?: BigDecimal.ZERO) }
            val rangeLow = BigDecimal(bucket).divide(BigDecimal(20), 4, RoundingMode.HALF_UP)
            val rangeHigh = BigDecimal(bucket + 1).divide(BigDecimal(20), 4, RoundingMode.HALF_UP)
            bins.add(
                CryptoTailCalibrationBin(
                    bucket = bucket,
                    rangeLow = rangeLow.toPlainString(),
                    rangeHigh = rangeHigh.toPlainString(),
                    sampleCount = cnt,
                    predictedProb = predicted.toPlainString(),
                    actualWinRate = actual.toPlainString(),
                    netPnl = netPnl.toPlainString()
                )
            )
            // 样本量加权 |预测-实际|
            weightedErrorNumerator = weightedErrorNumerator.add(predicted.subtract(actual).abs().multiply(BigDecimal(cnt)))
        }
        val calibrationError = weightedErrorNumerator.divide(BigDecimal(total), scale, RoundingMode.HALF_UP)
        return CalibrationAgg(total, winRate, calibrationError, totalNetPnl, avgNetPnl, bins)
    }

    /** 清除指定策略的放量判定缓存（配置变更/结算后可调用以即时刷新；非必须） */
    fun invalidate(strategyId: Long) {
        gateCache.remove(strategyId)
    }
}
