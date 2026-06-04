package com.wrbug.polymarketbot.service.cryptotail.taildiff

import java.math.BigDecimal

/**
 * 价差/赔率/剩余时间的标准化分桶（与计划书"§二 分桶设计"对齐）。
 *
 * 分桶用于：
 *  - 历史反转统计聚合（按桶聚合，避免点估计噪声）
 *  - 决策日志可读化（人类可直接看出"我们在哪个桶里下注"）
 *  - 评分模型平滑（连续值 → 离散桶 → 更稳健的历史胜率匹配）
 *
 * 桶边界刻意留有重叠保护：min<=v<max 半开区间，最高桶包含上界。
 */
object TailDiffBuckets {

    /** diff_sigma 分桶：低（[0,1)/[1,1.5)）/ 中（[1.5,2)/[2,2.5)）/ 高（[2.5,3)/[3,∞)） */
    private val DIFF_SIGMA_EDGES = listOf(
        BigDecimal("0") to BigDecimal("1.0"),
        BigDecimal("1.0") to BigDecimal("1.5"),
        BigDecimal("1.5") to BigDecimal("2.0"),
        BigDecimal("2.0") to BigDecimal("2.5"),
        BigDecimal("2.5") to BigDecimal("3.0")
    )

    fun diffSigmaBucket(diffSigma: BigDecimal): String {
        for ((lo, hi) in DIFF_SIGMA_EDGES) {
            if (diffSigma >= lo && diffSigma < hi) return "${lo.toPlainString()}_${hi.toPlainString()}"
        }
        return "3.0_INF"
    }

    /** 赔率分桶：0.80_0.84 / 0.85_0.89 / 0.90_0.92 / 0.93_0.95 / 0.96_0.98 / 0.99_1.00 */
    private val ODDS_EDGES = listOf(
        BigDecimal("0.80") to BigDecimal("0.85"),
        BigDecimal("0.85") to BigDecimal("0.90"),
        BigDecimal("0.90") to BigDecimal("0.93"),
        BigDecimal("0.93") to BigDecimal("0.96"),
        BigDecimal("0.96") to BigDecimal("0.99"),
        BigDecimal("0.99") to BigDecimal("1.0001")
    )

    fun oddsBucket(price: BigDecimal): String {
        for ((lo, hi) in ODDS_EDGES) {
            if (price >= lo && price < hi) return "${lo.toPlainString()}_${hi.toPlainString()}"
        }
        return if (price < BigDecimal("0.80")) "lt_0.80" else "ge_1.00"
    }

    /** 剩余时间分桶（秒）：用于历史反转统计 */
    private val REMAINING_BUCKETS = listOf(
        0 to 30,
        30 to 60,
        60 to 120,
        120 to 180,
        180 to 300
    )

    fun remainingBucket(remainingSeconds: Int): String {
        for ((lo, hi) in REMAINING_BUCKETS) {
            if (remainingSeconds in lo until hi) return "${lo}_${hi}"
        }
        return if (remainingSeconds < 0) "neg" else "300_INF"
    }

    /** 价差百分比分桶（带符号），便于 UI 展示。 */
    private val DIFF_PCT_EDGES = listOf(
        BigDecimal("0") to BigDecimal("0.0010"),
        BigDecimal("0.0010") to BigDecimal("0.0025"),
        BigDecimal("0.0025") to BigDecimal("0.0050"),
        BigDecimal("0.0050") to BigDecimal("0.0100"),
        BigDecimal("0.0100") to BigDecimal("0.0200")
    )

    fun diffPctBucket(diffPct: BigDecimal): String {
        val abs = diffPct.abs()
        for ((lo, hi) in DIFF_PCT_EDGES) {
            if (abs >= lo && abs < hi) return "${lo.toPlainString()}_${hi.toPlainString()}"
        }
        return "0.0200_INF"
    }
}
