package com.wrbug.polymarketbot.service.cryptotail.taildiff

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class TailDiffBucketsTest {

    @Test
    fun `diffSigmaBucket maps to half-open ranges with INF tail`() {
        assertEquals("0_1.0", TailDiffBuckets.diffSigmaBucket(BigDecimal("0")))
        assertEquals("0_1.0", TailDiffBuckets.diffSigmaBucket(BigDecimal("0.99")))
        assertEquals("1.0_1.5", TailDiffBuckets.diffSigmaBucket(BigDecimal("1.0")))
        assertEquals("1.5_2.0", TailDiffBuckets.diffSigmaBucket(BigDecimal("1.7")))
        assertEquals("2.5_3.0", TailDiffBuckets.diffSigmaBucket(BigDecimal("2.9")))
        // 上界进入 INF 桶
        assertEquals("3.0_INF", TailDiffBuckets.diffSigmaBucket(BigDecimal("3.0")))
        assertEquals("3.0_INF", TailDiffBuckets.diffSigmaBucket(BigDecimal("12.5")))
    }

    @Test
    fun `oddsBucket maps price into odds buckets with out-of-range labels`() {
        assertEquals("0.90_0.93", TailDiffBuckets.oddsBucket(BigDecimal("0.91")))
        assertEquals("0.93_0.96", TailDiffBuckets.oddsBucket(BigDecimal("0.95")))
        assertEquals("0.99_1.0001", TailDiffBuckets.oddsBucket(BigDecimal("1.0")))
        assertEquals("lt_0.80", TailDiffBuckets.oddsBucket(BigDecimal("0.5")))
    }

    @Test
    fun `remainingBucket maps seconds into half-open ranges`() {
        assertEquals("0_30", TailDiffBuckets.remainingBucket(0))
        assertEquals("30_60", TailDiffBuckets.remainingBucket(45))
        assertEquals("60_120", TailDiffBuckets.remainingBucket(60))
        assertEquals("300_INF", TailDiffBuckets.remainingBucket(600))
        assertEquals("neg", TailDiffBuckets.remainingBucket(-5))
    }
}
