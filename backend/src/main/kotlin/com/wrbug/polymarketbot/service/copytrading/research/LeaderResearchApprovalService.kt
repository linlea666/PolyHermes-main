package com.wrbug.polymarketbot.service.copytrading.research

import com.wrbug.polymarketbot.dto.CopyTradingCreateRequest
import com.wrbug.polymarketbot.dto.LeaderResearchApprovalRequest
import com.wrbug.polymarketbot.dto.LeaderResearchApprovalResponse
import com.wrbug.polymarketbot.entity.LeaderPool
import com.wrbug.polymarketbot.enums.LeaderPoolStatus
import com.wrbug.polymarketbot.enums.LeaderResearchEventType
import com.wrbug.polymarketbot.enums.LeaderResearchState
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.LeaderPoolRepository
import com.wrbug.polymarketbot.repository.LeaderResearchCandidateRepository
import com.wrbug.polymarketbot.service.copytrading.configs.CopyTradingService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

class LeaderResearchCandidateNotReadyException : RuntimeException("候选尚未进入 TRIAL_READY，不能创建试跟配置")
class LeaderResearchApprovalConfirmRequiredException : RuntimeException("创建禁用试跟配置需要显式确认")
class LeaderResearchDuplicateTrialConfigException : RuntimeException("该账户已存在此 Leader 的跟单配置")
class LeaderResearchRealMoneyForbiddenException : RuntimeException("Leader Research Agent 不允许自动启用真钱跟单")
class LeaderResearchCandidateLockedException : RuntimeException("研究候选已锁定")

@Service
class LeaderResearchApprovalService(
    private val candidateRepository: LeaderResearchCandidateRepository,
    private val accountRepository: AccountRepository,
    private val copyTradingRepository: CopyTradingRepository,
    private val leaderPoolRepository: LeaderPoolRepository,
    private val copyTradingService: CopyTradingService,
    private val poolMappingService: LeaderResearchPoolMappingService,
    private val eventService: LeaderResearchEventService
) {
    private val logger = LoggerFactory.getLogger(LeaderResearchApprovalService::class.java)

    @Transactional
    fun createDisabledTrialConfig(request: LeaderResearchApprovalRequest): Result<LeaderResearchApprovalResponse> {
        return try {
            if (!request.confirm) {
                return Result.failure(LeaderResearchApprovalConfirmRequiredException())
            }
            val candidate = candidateRepository.findById(request.candidateId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("候选不存在"))
            if (candidate.locked) {
                eventService.record(
                    type = LeaderResearchEventType.APPROVAL_REJECTED,
                    candidateId = candidate.id,
                    reason = "Candidate is locked; manual unlock is required before approval"
                )
                return Result.failure(LeaderResearchCandidateLockedException())
            }
            if (candidate.researchState != LeaderResearchState.TRIAL_READY) {
                eventService.record(
                    type = LeaderResearchEventType.APPROVAL_REJECTED,
                    candidateId = candidate.id,
                    reason = "Candidate state is ${candidate.researchState}, not TRIAL_READY"
                )
                return Result.failure(LeaderResearchCandidateNotReadyException())
            }
            val account = accountRepository.findByIdForUpdate(request.accountId)
                ?: return Result.failure(IllegalArgumentException("账户不存在"))
            val synced = poolMappingService.syncCandidate(candidate)
            val pool = synced.poolId?.let { leaderPoolRepository.findById(it).orElse(null) }
                ?: return Result.failure(IllegalStateException("Leader Pool 同步失败"))
            val leaderId = synced.leaderId ?: pool.leaderId
            if (copyTradingRepository.findByAccountIdAndLeaderId(account.id ?: request.accountId, leaderId).isNotEmpty()) {
                eventService.record(
                    type = LeaderResearchEventType.DUPLICATE_APPROVAL,
                    candidateId = candidate.id,
                    reason = "Duplicate copy trading config for account=${account.id}, leader=$leaderId"
                )
                return Result.failure(LeaderResearchDuplicateTrialConfigException())
            }

            val copyRequest = buildDisabledCopyTradingRequest(pool, request.accountId, leaderId)
            if (copyRequest.enabled) {
                eventService.record(
                    type = LeaderResearchEventType.REAL_MONEY_ACTIVATION_FORBIDDEN,
                    candidateId = candidate.id,
                    reason = "Research approval attempted to create enabled copy trading config",
                    dedupeKey = "approval-real-money-forbidden:${candidate.id}:${request.accountId}"
                )
                return Result.failure(LeaderResearchRealMoneyForbiddenException())
            }
            val copyTrading = copyTradingService.createCopyTrading(copyRequest).getOrThrow()
            val now = System.currentTimeMillis()
            leaderPoolRepository.save(
                pool.copy(
                    status = LeaderPoolStatus.TRIAL,
                    lastPromotedAt = now,
                    lastReviewedAt = now,
                    researchState = LeaderResearchState.TRIAL_READY,
                    researchBadge = "DISABLED_TRIAL_CREATED",
                    researchUpdatedAt = now,
                    updatedAt = now
                )
            )
            eventService.record(
                type = LeaderResearchEventType.APPROVAL_CREATED_DISABLED_CONFIG,
                candidateId = candidate.id,
                reason = "Created disabled copy trading config id=${copyTrading.id}; manual enable required",
                payloadSummary = "accountId=${request.accountId}, leaderId=$leaderId",
                dedupeKey = "approval-disabled:${candidate.id}:${request.accountId}"
            )
            Result.success(LeaderResearchApprovalResponse(copyTrading))
        } catch (e: Exception) {
            logger.error("Leader research approval failed: candidateId=${request.candidateId}", e)
            Result.failure(e)
        }
    }

    private fun buildDisabledCopyTradingRequest(pool: LeaderPool, accountId: Long, leaderId: Long): CopyTradingCreateRequest {
        val fixedAmount = pool.suggestedFixedAmount.takeIf { it > BigDecimal.ZERO } ?: BigDecimal("1.00000000")
        return CopyTradingCreateRequest(
            accountId = accountId,
            leaderId = leaderId,
            enabled = false,
            copyMode = "FIXED",
            copyRatio = "1",
            fixedAmount = fixedAmount.strip(),
            maxOrderSize = fixedAmount.strip(),
            minOrderSize = "1",
            maxDailyLoss = (pool.suggestedMaxDailyLoss.takeIf { it > BigDecimal.ZERO } ?: BigDecimal("5.00000000")).strip(),
            maxDailyOrders = pool.suggestedMaxDailyOrders.coerceIn(1, 10),
            priceTolerance = "1",
            delaySeconds = 0,
            pollIntervalSeconds = 5,
            useWebSocket = true,
            websocketReconnectInterval = 5000,
            websocketMaxRetries = 10,
            supportSell = true,
            minPrice = pool.suggestedMinPrice?.strip() ?: "0.1",
            maxPrice = pool.suggestedMaxPrice?.strip() ?: "0.8",
            maxPositionValue = pool.suggestedMaxPositionValue?.strip() ?: "5",
            keywordFilterMode = "DISABLED",
            keywords = null,
            configName = "Research试跟-${pool.researchCandidateId ?: pool.leaderId}",
            pushFailedOrders = true,
            pushFilteredOrders = true
        )
    }

    private fun BigDecimal.strip(): String = stripTrailingZeros().toPlainString()
}
