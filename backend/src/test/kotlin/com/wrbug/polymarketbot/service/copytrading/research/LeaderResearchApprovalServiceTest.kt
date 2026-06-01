package com.wrbug.polymarketbot.service.copytrading.research

import com.wrbug.polymarketbot.dto.CopyTradingDto
import com.wrbug.polymarketbot.dto.LeaderResearchApprovalRequest
import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.entity.LeaderPool
import com.wrbug.polymarketbot.entity.LeaderResearchCandidate
import com.wrbug.polymarketbot.enums.LeaderResearchState
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.LeaderPoolRepository
import com.wrbug.polymarketbot.repository.LeaderResearchCandidateRepository
import com.wrbug.polymarketbot.service.copytrading.configs.CopyTradingService
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import java.util.Optional

class LeaderResearchApprovalServiceTest {
    private val candidateRepository: LeaderResearchCandidateRepository = mock()
    private val accountRepository: AccountRepository = mock()
    private val copyTradingRepository: CopyTradingRepository = mock()
    private val leaderPoolRepository: LeaderPoolRepository = mock()
    private val copyTradingService: CopyTradingService = mock()
    private val poolMappingService: LeaderResearchPoolMappingService = mock()
    private val eventService: LeaderResearchEventService = mock()
    private val service = LeaderResearchApprovalService(
        candidateRepository,
        accountRepository,
        copyTradingRepository,
        leaderPoolRepository,
        copyTradingService,
        poolMappingService,
        eventService
    )

    @Test
    fun `approval requires explicit confirm`() {
        val result = service.createDisabledTrialConfig(LeaderResearchApprovalRequest(candidateId = 1L, accountId = 2L, confirm = false))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is LeaderResearchApprovalConfirmRequiredException)
        Mockito.verify(copyTradingService, Mockito.never()).createCopyTrading(anyCreateRequest())
    }

    @Test
    fun `approval creates disabled copy trading config only`() {
        val candidate = LeaderResearchCandidate(
            id = 1L,
            normalizedWallet = "0x1111111111111111111111111111111111111111",
            leaderId = 9L,
            poolId = 10L,
            researchState = LeaderResearchState.TRIAL_READY
        )
        Mockito.`when`(candidateRepository.findById(1L)).thenReturn(Optional.of(candidate))
        Mockito.`when`(accountRepository.findByIdForUpdate(2L)).thenReturn(account())
        Mockito.`when`(poolMappingService.syncCandidate(candidate)).thenReturn(candidate)
        Mockito.`when`(leaderPoolRepository.findById(10L)).thenReturn(Optional.of(pool()))
        Mockito.`when`(copyTradingRepository.findByAccountIdAndLeaderId(2L, 9L)).thenReturn(emptyList())
        Mockito.`when`(copyTradingService.createCopyTrading(anyCreateRequest())).thenReturn(Result.success(copyTradingDto()))
        Mockito.`when`(leaderPoolRepository.save(anyLeaderPool())).thenAnswer { it.arguments[0] }

        val result = service.createDisabledTrialConfig(LeaderResearchApprovalRequest(candidateId = 1L, accountId = 2L, confirm = true))

        assertTrue(result.isSuccess)
        val captor = ArgumentCaptor.forClass(com.wrbug.polymarketbot.dto.CopyTradingCreateRequest::class.java)
        Mockito.verify(copyTradingService).createCopyTrading(captureCreateRequest(captor))
        assertFalse(captor.value.enabled)
        Mockito.verify(accountRepository).findByIdForUpdate(2L)
    }

    @Test
    fun `locked candidate cannot create approval config`() {
        val candidate = LeaderResearchCandidate(
            id = 1L,
            normalizedWallet = "0x1111111111111111111111111111111111111111",
            leaderId = 9L,
            poolId = 10L,
            researchState = LeaderResearchState.TRIAL_READY,
            locked = true
        )
        Mockito.`when`(candidateRepository.findById(1L)).thenReturn(Optional.of(candidate))

        val result = service.createDisabledTrialConfig(LeaderResearchApprovalRequest(candidateId = 1L, accountId = 2L, confirm = true))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is LeaderResearchCandidateLockedException)
        Mockito.verify(accountRepository, Mockito.never()).findByIdForUpdate(2L)
        Mockito.verify(copyTradingService, Mockito.never()).createCopyTrading(anyCreateRequest())
    }

    private fun account() = Account(
        id = 2L,
        privateKey = "enc",
        walletAddress = "0x2222222222222222222222222222222222222222",
        proxyAddress = "0x3333333333333333333333333333333333333333"
    )

    private fun pool() = LeaderPool(id = 10L, leaderId = 9L, researchCandidateId = 1L)

    private fun copyTradingDto() = CopyTradingDto(
        id = 20L,
        accountId = 2L,
        accountName = null,
        walletAddress = "0x2222222222222222222222222222222222222222",
        leaderId = 9L,
        leaderName = null,
        leaderAddress = "0x1111111111111111111111111111111111111111",
        enabled = false,
        copyMode = "FIXED",
        copyRatio = "1",
        fixedAmount = "1",
        maxOrderSize = "1",
        minOrderSize = "1",
        maxDailyLoss = "5",
        maxDailyOrders = 10,
        priceTolerance = "1",
        delaySeconds = 0,
        pollIntervalSeconds = 5,
        useWebSocket = true,
        websocketReconnectInterval = 5000,
        websocketMaxRetries = 10,
        supportSell = true,
        minOrderDepth = null,
        maxSpread = null,
        minPrice = "0.1",
        maxPrice = "0.8",
        maxPositionValue = "5",
        createdAt = 1L,
        updatedAt = 1L
    )

    private fun anyCreateRequest(): com.wrbug.polymarketbot.dto.CopyTradingCreateRequest {
        Mockito.any(com.wrbug.polymarketbot.dto.CopyTradingCreateRequest::class.java)
        return com.wrbug.polymarketbot.dto.CopyTradingCreateRequest(accountId = 2L, leaderId = 9L)
    }

    private fun anyLeaderPool(): LeaderPool {
        Mockito.any(LeaderPool::class.java)
        return pool()
    }

    private fun captureCreateRequest(captor: ArgumentCaptor<com.wrbug.polymarketbot.dto.CopyTradingCreateRequest>): com.wrbug.polymarketbot.dto.CopyTradingCreateRequest {
        captor.capture()
        return com.wrbug.polymarketbot.dto.CopyTradingCreateRequest(accountId = 2L, leaderId = 9L)
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)
}
