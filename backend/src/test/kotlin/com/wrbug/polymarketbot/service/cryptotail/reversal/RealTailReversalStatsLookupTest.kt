package com.wrbug.polymarketbot.service.cryptotail.reversal

import com.wrbug.polymarketbot.entity.CryptoTailReversalStat
import com.wrbug.polymarketbot.repository.CryptoTailReversalStatRepository
import com.wrbug.polymarketbot.service.cryptotail.taildiff.TailReversalStatsLookup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal

class RealTailReversalStatsLookupTest {

    private val repo = Mockito.mock(CryptoTailReversalStatRepository::class.java)
    private val lookup = RealTailReversalStatsLookup(repo)

    private fun stub(
        leadOutcome: Int,
        diffSigmaBucket: String,
        oddsBucket: String,
        dataSource: String,
        row: CryptoTailReversalStat?
    ) {
        Mockito.`when`(
            repo.findFirstByCoinAndIntervalSecondsAndOutcomeIndexAndDiffSigmaBucketAndOddsBucketAndRemainingBucketAndLookbackDaysAndDataSourceOrderBySampleCountDesc(
                "BTC", 300, leadOutcome, diffSigmaBucket, oddsBucket, "60_120", 180, dataSource
            )
        ).thenReturn(row)
    }

    private fun query(leadOutcome: Int, oddsBucket: String, dataSource: String) =
        TailReversalStatsLookup.Query(
            coin = "btc",
            intervalSeconds = 300,
            leadOutcome = leadOutcome,
            diffSigma = BigDecimal("1.7"), // -> bucket 1.5_2.0
            oddsBucket = oddsBucket,
            remainingBucket = "60_120",
            lookbackDays = 180,
            dataSource = dataSource
        )

    private fun row(
        dataSource: String,
        diffSigmaBucket: String,
        oddsBucket: String,
        lead: Int,
        sampleCount: Int = 120,
        samplingSeconds: Int = 60
    ) =
        CryptoTailReversalStat(
            coin = "BTC", intervalSeconds = 300, outcomeIndex = lead,
            diffSigmaBucket = diffSigmaBucket, oddsBucket = oddsBucket, remainingBucket = "60_120",
            lookbackDays = 180, dataSource = dataSource, sampleCount = sampleCount,
            samplingSeconds = samplingSeconds, reversedCount = 12, modelProb = BigDecimal("0.90")
        )

    @Test
    fun `binance row keyed by sigma with odds ANY is matched via odds fallback`() {
        stub(0, "1.5_2.0", "ANY", "BINANCE", row("BINANCE", "1.5_2.0", "ANY", 0))

        val result = lookup.queryReversalProb(query(leadOutcome = 0, oddsBucket = "0.90_0.93", dataSource = "BINANCE"))

        assertEquals("STATS", result.source)
        assertEquals(BigDecimal("0.90"), result.modelProb)
        assertEquals(120, result.sampleCount)
    }

    @Test
    fun `leadOutcome semantics - querying the non-lead direction misses the bucket`() {
        stub(0, "1.5_2.0", "ANY", "BINANCE", row("BINANCE", "1.5_2.0", "ANY", 0))

        val result = lookup.queryReversalProb(query(leadOutcome = 1, oddsBucket = "0.90_0.93", dataSource = "BINANCE"))

        assertEquals("FALLBACK", result.source)
    }

    @Test
    fun `polymarket row keyed by odds with sigma ANY is matched via sigma fallback`() {
        stub(0, "ANY", "0.90_0.93", "POLYMARKET", row("POLYMARKET", "ANY", "0.90_0.93", 0))

        val result = lookup.queryReversalProb(query(leadOutcome = 0, oddsBucket = "0.90_0.93", dataSource = "POLYMARKET"))

        assertEquals("STATS", result.source)
        assertEquals(BigDecimal("0.90"), result.modelProb)
    }

    @Test
    fun `dataSource isolates buckets - binance query does not see polymarket rows`() {
        stub(0, "ANY", "0.90_0.93", "POLYMARKET", row("POLYMARKET", "ANY", "0.90_0.93", 0))

        val result = lookup.queryReversalProb(query(leadOutcome = 0, oddsBucket = "0.90_0.93", dataSource = "BINANCE"))

        assertEquals("FALLBACK", result.source)
    }

    @Test
    fun `fine 1s sampling row is returned when it is the richest for the bucket`() {
        // 尾盘细桶仅有 1s 数据（1m 采不到）：repo 的 OrderBySampleCountDesc 会取到该 1s 行，lookup 据此给出 STATS
        stub(0, "1.5_2.0", "ANY", "BINANCE", row("BINANCE", "1.5_2.0", "ANY", 0, sampleCount = 80, samplingSeconds = 1))

        val result = lookup.queryReversalProb(query(leadOutcome = 0, oddsBucket = "0.90_0.93", dataSource = "BINANCE"))

        assertEquals("STATS", result.source)
        assertEquals(BigDecimal("0.90"), result.modelProb)
        assertEquals(80, result.sampleCount)
    }

    @Test
    fun `blank coin yields fallback without querying repo`() {
        val result = lookup.queryReversalProb(
            TailReversalStatsLookup.Query(
                coin = null, intervalSeconds = 300, leadOutcome = 0, diffSigma = BigDecimal("1.7"),
                oddsBucket = "0.90_0.93", remainingBucket = "60_120", lookbackDays = 180, dataSource = "BINANCE"
            )
        )
        assertEquals("FALLBACK", result.source)
    }
}
