package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import com.wrbug.polymarketbot.repository.CryptoTailStrategyRepository
import com.wrbug.polymarketbot.repository.CryptoTailStrategyTriggerRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import java.math.BigDecimal

class CryptoTailRiskServiceTest {

    @Test
    fun `account level concurrency blocks when shared account reaches aggregate limit`() {
        val triggerRepository = Mockito.mock(CryptoTailStrategyTriggerRepository::class.java)
        val strategyRepository = Mockito.mock(CryptoTailStrategyRepository::class.java)
        val strategy = CryptoTailStrategy(
            id = 1L,
            accountId = 7L,
            marketSlugPrefix = "btc-updown-15m",
            maxConcurrentPositions = 2
        )
        val ethStrategy = CryptoTailStrategy(
            id = 2L,
            accountId = 7L,
            marketSlugPrefix = "eth-updown-5m",
            maxConcurrentPositions = 1
        )
        Mockito.`when`(triggerRepository.countByStrategyIdAndStatusAndResolvedFalse(1L, "success")).thenReturn(0L)
        Mockito.`when`(triggerRepository.sumRealizedPnlByStrategyIdAndSettledAtAfter(eq(1L), anyLong())).thenReturn(BigDecimal.ZERO)
        Mockito.`when`(strategyRepository.findByAccountIdAndEnabled(7L, true)).thenReturn(listOf(strategy, ethStrategy))
        Mockito.`when`(triggerRepository.sumRealizedPnlByAccountIdAndSettledAtAfter(eq(7L), anyLong())).thenReturn(BigDecimal.ZERO)
        Mockito.`when`(triggerRepository.countByAccountIdAndStatusAndResolvedFalse(7L, "success")).thenReturn(3L)

        val risk = CryptoTailRiskService(triggerRepository, strategyRepository).checkRiskGate(strategy)

        assertFalse(risk.passed)
        assertEquals("RISK_ACCOUNT_CONCURRENCY", risk.gateName)
    }
}
