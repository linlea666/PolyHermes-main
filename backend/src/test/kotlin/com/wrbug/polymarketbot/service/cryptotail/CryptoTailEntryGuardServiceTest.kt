package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import com.wrbug.polymarketbot.repository.CryptoTailStrategyTriggerRepository
import com.wrbug.polymarketbot.service.accounts.AccountService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal

class CryptoTailEntryGuardServiceTest {

    @Test
    fun `spendable balance subtracts pending entry amount for shared account`() {
        val accountService = Mockito.mock(AccountService::class.java)
        val triggerRepository = Mockito.mock(CryptoTailStrategyTriggerRepository::class.java)
        Mockito.`when`(accountService.getAvailableUsdc(7L)).thenReturn(
            Result.success(BigDecimal("10.00"))
        )
        Mockito.`when`(triggerRepository.sumPendingEntryAmountByAccountId(7L)).thenReturn(BigDecimal("4.25"))

        val guard = CryptoTailEntryGuardService(accountService, triggerRepository, 10_000L)
        val snapshot = guard.loadEntryBalanceSnapshot(7L)

        assertEquals(0, BigDecimal("10.00").compareTo(snapshot.rawAvailable))
        assertEquals(0, BigDecimal("4.25").compareTo(snapshot.pendingReserved))
        assertEquals(0, BigDecimal("5.75").compareTo(snapshot.spendable))
    }

    @Test
    fun `recent FAK fill reservation reduces spendable for concurrent same-account strategy`() {
        val accountService = Mockito.mock(AccountService::class.java)
        val triggerRepository = Mockito.mock(CryptoTailStrategyTriggerRepository::class.java)
        Mockito.`when`(accountService.getAvailableUsdc(7L)).thenReturn(
            Result.success(BigDecimal("10.00"))
        )
        Mockito.`when`(triggerRepository.sumPendingEntryAmountByAccountId(7L)).thenReturn(BigDecimal.ZERO)

        val guard = CryptoTailEntryGuardService(accountService, triggerRepository, 10_000L)
        // 模拟一笔刚成交、链上余额尚未反映的 FAK 花费
        guard.reserveRecentFill(7L, BigDecimal("6.00"))
        val snapshot = guard.loadEntryBalanceSnapshot(7L)

        assertEquals(0, BigDecimal("6.00").compareTo(snapshot.recentFillReserved))
        assertEquals(0, BigDecimal("4.00").compareTo(snapshot.spendable))
    }

    @Test
    fun `duplicate market position is blocked by default and bypassed when explicitly allowed`() {
        val accountService = Mockito.mock(AccountService::class.java)
        val triggerRepository = Mockito.mock(CryptoTailStrategyTriggerRepository::class.java)
        Mockito.`when`(
            triggerRepository.countOpenMarketPositionByAccountMarketPeriodOutcome(
                7L,
                "btc-updown-15m",
                1_717_000_000L,
                0
            )
        ).thenReturn(1L)

        val guard = CryptoTailEntryGuardService(accountService, triggerRepository, 10_000L)
        val defaultStrategy = CryptoTailStrategy(
            id = 1L,
            accountId = 7L,
            marketSlugPrefix = "btc-updown-15m"
        )
        val duplicateAllowedStrategy = defaultStrategy.copy(allowDuplicateMarketPosition = true)

        assertTrue(guard.hasDuplicateMarketPosition(defaultStrategy, 1_717_000_000L, 0))
        assertFalse(guard.hasDuplicateMarketPosition(duplicateAllowedStrategy, 1_717_000_000L, 0))
    }
}
