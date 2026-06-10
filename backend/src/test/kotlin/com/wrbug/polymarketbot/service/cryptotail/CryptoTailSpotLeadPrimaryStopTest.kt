package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.entity.CryptoTailScalpSpotLeadConfig
import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import com.wrbug.polymarketbot.entity.CryptoTailStrategyTrigger
import com.wrbug.polymarketbot.enums.ExitKind
import com.wrbug.polymarketbot.enums.TradingMode
import com.wrbug.polymarketbot.repository.CryptoTailStrategyExitRepository
import com.wrbug.polymarketbot.repository.CryptoTailStrategyTriggerRepository
import com.wrbug.polymarketbot.service.cryptotail.taildiff.CryptoTailReverseVelocityTracker
import com.wrbug.polymarketbot.service.cryptotail.taildiff.TailDiffExitPresetResolver
import com.wrbug.polymarketbot.service.copytrading.orders.OrderSigningService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal

/**
 * V95 现货主止损（SPOT_LEAD_PRIMARY_STOP）单测：
 *  - 持续确认：危险须连续保持 >= persistMs 才触发；
 *  - 瞬时过滤：危险中断（亚秒假穿收回）立即重置计时，不触发；
 *  - 零回归：现货缺失/不新鲜/minGapUsd 不达标 → 返回 null 回退现有逻辑。
 */
class CryptoTailSpotLeadPrimaryStopTest {

    private fun newService(): CryptoTailBracketExitService = CryptoTailBracketExitService(
        Mockito.mock(CryptoTailStrategyTriggerRepository::class.java),
        Mockito.mock(CryptoTailStrategyExitRepository::class.java),
        Mockito.mock(OrderSigningService::class.java),
        Mockito.mock(CryptoTailAccountContextFactory::class.java),
        Mockito.mock(PeriodPriceProvider::class.java),
        Mockito.mock(CryptoTailDecisionRecorder::class.java),
        Mockito.mock(CryptoTailWickSignalService::class.java),
        Mockito.mock(TailDiffExitPresetResolver::class.java),
        Mockito.mock(CryptoTailReverseVelocityTracker::class.java),
        Mockito.mock(CryptoTailExitOrderReconciler::class.java),
        Mockito.mock(CryptoTailSpotLeadService::class.java),
        Mockito.mock(CryptoTailSpotLeadTelemetry::class.java)
    )

    private fun strategy(persistMs: Int = 0, minGapUsd: BigDecimal = BigDecimal.ZERO) = CryptoTailStrategy(
        id = 1L,
        accountId = 1L,
        marketSlugPrefix = "btc-updown-5m",
        mode = TradingMode.SCALP_FLIP,
        scalpSpotLeadConfig = CryptoTailScalpSpotLeadConfig(
            enabled = true,
            primaryStopEnabled = true,
            primaryStopPersistMs = persistMs,
            primaryStopMinGapUsd = minGapUsd
        )
    )

    private fun trigger(id: Long = 100L) = CryptoTailStrategyTrigger(
        id = id,
        strategyId = 1L,
        periodStartUnix = 1_700_000_000L,
        outcomeIndex = 0,
        triggerPrice = BigDecimal("0.9"),
        amountUsdc = BigDecimal("10")
    )

    private fun lead(
        fresh: Boolean = true,
        spotGap: BigDecimal = BigDecimal("-3.5"),
        supportsHolding: Boolean = false
    ) = CryptoTailSpotLeadService.SpotLeadState(
        fresh = fresh,
        spotGap = spotGap,
        supportsHolding = supportsHolding,
        distanceToFlipSigma = null,
        ageMs = 120L,
        exchange = "CONSENSUS"
    )

    @Test
    fun `fires immediately with full clear when persistMs is zero and danger qualified`() {
        val service = newService()
        val spotLead = lead()
        val decision = service.decideSpotLeadPrimaryStop(
            strategy(persistMs = 0), trigger(), spotLead, spotDanger = true,
            bestBid = BigDecimal("0.95"), remainingSeconds = 120, tier = null
        )
        assertNotNull(decision)
        assertEquals(ExitKind.HARD_STOP, decision!!.kind)
        assertEquals(0, BigDecimal.ONE.compareTo(decision.ratio))
        assertTrue(decision.forceImmediate)
        assertTrue(decision.spotEarlyWarningActed)
        assertTrue(decision.spotLeadPrimaryStop)
        assertTrue(decision.reason.contains("SPOT_LEAD_PRIMARY_STOP"))
        assertTrue(decision.reason.contains("bestBid=0.95"))
    }

    @Test
    fun `does not fire before persist window elapses`() {
        val service = newService()
        val decision = service.decideSpotLeadPrimaryStop(
            strategy(persistMs = 60_000), trigger(), lead(), spotDanger = true,
            bestBid = BigDecimal("0.95"), remainingSeconds = 120, tier = null
        )
        assertNull(decision)
    }

    @Test
    fun `fires after danger persists beyond persist window`() {
        val service = newService()
        val strategy = strategy(persistMs = 50)
        val trigger = trigger()
        assertNull(
            service.decideSpotLeadPrimaryStop(
                strategy, trigger, lead(), spotDanger = true,
                bestBid = BigDecimal("0.95"), remainingSeconds = 120, tier = null
            )
        )
        Thread.sleep(120)
        val decision = service.decideSpotLeadPrimaryStop(
            strategy, trigger, lead(), spotDanger = true,
            bestBid = BigDecimal("0.9"), remainingSeconds = 119, tier = null
        )
        assertNotNull(decision)
        assertEquals(ExitKind.HARD_STOP, decision!!.kind)
    }

    @Test
    fun `transient danger interruption resets persist timer`() {
        val service = newService()
        val strategy = strategy(persistMs = 50)
        val trigger = trigger()
        // 第一次危险：登记起点
        assertNull(
            service.decideSpotLeadPrimaryStop(
                strategy, trigger, lead(), spotDanger = true,
                bestBid = BigDecimal("0.95"), remainingSeconds = 120, tier = null
            )
        )
        Thread.sleep(80)
        // 危险中断（现货收回）：重置计时
        assertNull(
            service.decideSpotLeadPrimaryStop(
                strategy, trigger, lead(supportsHolding = true, spotGap = BigDecimal("2")), spotDanger = false,
                bestBid = BigDecimal("0.95"), remainingSeconds = 119, tier = null
            )
        )
        // 再次危险：计时从零开始，已过的 80ms 不算 → 不触发
        assertNull(
            service.decideSpotLeadPrimaryStop(
                strategy, trigger, lead(), spotDanger = true,
                bestBid = BigDecimal("0.95"), remainingSeconds = 119, tier = null
            )
        )
    }

    @Test
    fun `does not fire when spot is stale even if danger flagged`() {
        val service = newService()
        val decision = service.decideSpotLeadPrimaryStop(
            strategy(persistMs = 0), trigger(), lead(fresh = false), spotDanger = true,
            bestBid = BigDecimal("0.95"), remainingSeconds = 120, tier = null
        )
        assertNull(decision)
    }

    @Test
    fun `does not fire when spot lead missing`() {
        val service = newService()
        val decision = service.decideSpotLeadPrimaryStop(
            strategy(persistMs = 0), trigger(), spotLead = null, spotDanger = false,
            bestBid = BigDecimal("0.95"), remainingSeconds = 120, tier = null
        )
        assertNull(decision)
    }

    @Test
    fun `minGapUsd filters shallow crossings but passes deep ones`() {
        val service = newService()
        val strategy = strategy(persistMs = 0, minGapUsd = BigDecimal("2"))
        // 浅穿：|spotGap|=1.5 < 2 → 不触发
        assertNull(
            service.decideSpotLeadPrimaryStop(
                strategy, trigger(id = 101L), lead(spotGap = BigDecimal("-1.5")), spotDanger = true,
                bestBid = BigDecimal("0.95"), remainingSeconds = 120, tier = null
            )
        )
        // 深穿：|spotGap|=2.5 >= 2 → 触发
        val decision = service.decideSpotLeadPrimaryStop(
            strategy, trigger(id = 102L), lead(spotGap = BigDecimal("-2.5")), spotDanger = true,
            bestBid = BigDecimal("0.95"), remainingSeconds = 120, tier = null
        )
        assertNotNull(decision)
    }

    @Test
    fun `minGapUsd not applied to near-flip warning without actual crossing`() {
        val service = newService()
        // 未实际穿价（supportsHolding=true）但近翻转预警判危险：穿价深度滤不适用 → 触发
        val decision = service.decideSpotLeadPrimaryStop(
            strategy(persistMs = 0, minGapUsd = BigDecimal("5")), trigger(),
            lead(spotGap = BigDecimal("0.8"), supportsHolding = true), spotDanger = true,
            bestBid = BigDecimal("0.95"), remainingSeconds = 120, tier = null
        )
        assertNotNull(decision)
    }
}
