package com.wrbug.polymarketbot.controller.copytrading.research

import com.wrbug.polymarketbot.dto.ApiResponse
import com.wrbug.polymarketbot.dto.LeaderResearchApprovalRequest
import com.wrbug.polymarketbot.dto.LeaderResearchApprovalResponse
import com.wrbug.polymarketbot.dto.LeaderResearchCandidateDetailDto
import com.wrbug.polymarketbot.dto.LeaderResearchCandidateListRequest
import com.wrbug.polymarketbot.dto.LeaderResearchCandidateListResponse
import com.wrbug.polymarketbot.dto.LeaderResearchEventDto
import com.wrbug.polymarketbot.dto.LeaderPaperSessionDto
import com.wrbug.polymarketbot.dto.LeaderResearchRunDto
import com.wrbug.polymarketbot.dto.LeaderResearchRunRequest
import com.wrbug.polymarketbot.dto.LeaderResearchSourceStateDto
import com.wrbug.polymarketbot.dto.LeaderResearchSummaryDto
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.enums.LeaderResearchTriggerType
import com.wrbug.polymarketbot.service.copytrading.research.LeaderResearchApprovalConfirmRequiredException
import com.wrbug.polymarketbot.service.copytrading.research.LeaderResearchApprovalService
import com.wrbug.polymarketbot.service.copytrading.research.LeaderResearchCandidateNotReadyException
import com.wrbug.polymarketbot.service.copytrading.research.LeaderResearchCandidateLockedException
import com.wrbug.polymarketbot.service.copytrading.research.LeaderResearchDuplicateTrialConfigException
import com.wrbug.polymarketbot.service.copytrading.research.LeaderResearchJobService
import com.wrbug.polymarketbot.service.copytrading.research.LeaderResearchMapper
import com.wrbug.polymarketbot.service.copytrading.research.LeaderResearchRealMoneyForbiddenException
import com.wrbug.polymarketbot.service.copytrading.research.LeaderResearchService
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class LeaderResearchDetailRequest(val candidateId: Long)
data class LeaderResearchEventsRequest(val page: Int = 0, val size: Int = 50)
data class LeaderResearchPaperSessionsRequest(val candidateId: Long)

@RestController
@RequestMapping("/api/copy-trading/leader-research")
class LeaderResearchController(
    private val jobService: LeaderResearchJobService,
    private val researchService: LeaderResearchService,
    private val approvalService: LeaderResearchApprovalService,
    private val mapper: LeaderResearchMapper,
    private val messageSource: MessageSource
) {
    private val logger = LoggerFactory.getLogger(LeaderResearchController::class.java)

    @PostMapping("/run")
    fun run(@RequestBody request: LeaderResearchRunRequest): ResponseEntity<ApiResponse<LeaderResearchRunDto>> {
        return try {
            val trigger = runCatching { LeaderResearchTriggerType.valueOf(request.triggerType.uppercase()) }
                .getOrDefault(LeaderResearchTriggerType.MANUAL)
            val run = if (request.dryRun || trigger == LeaderResearchTriggerType.PREVIEW) {
                jobService.runOnce(request.dryRun, trigger)
            } else {
                jobService.startAsync(request.dryRun, trigger)
            }
            ResponseEntity.ok(ApiResponse.success(mapper.runDto(run)))
        } catch (e: Exception) {
            logger.error("Leader research run failed", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_LEADER_RESEARCH_RUN_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/summary")
    fun summary(): ResponseEntity<ApiResponse<LeaderResearchSummaryDto>> {
        return safe(ErrorCode.SERVER_LEADER_RESEARCH_FETCH_FAILED) { researchService.summary() }
    }

    @PostMapping("/candidates/list")
    fun list(@RequestBody request: LeaderResearchCandidateListRequest): ResponseEntity<ApiResponse<LeaderResearchCandidateListResponse>> {
        return safe(ErrorCode.SERVER_LEADER_RESEARCH_FETCH_FAILED) { researchService.listCandidates(request) }
    }

    @PostMapping("/candidates/detail")
    fun detail(@RequestBody request: LeaderResearchDetailRequest): ResponseEntity<ApiResponse<LeaderResearchCandidateDetailDto>> {
        if (request.candidateId <= 0) {
            return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_INVALID, "candidateId 无效", messageSource))
        }
        return safe(ErrorCode.SERVER_LEADER_RESEARCH_FETCH_FAILED) { researchService.detail(request.candidateId) }
    }

    @PostMapping("/paper-sessions")
    fun paperSessions(@RequestBody request: LeaderResearchPaperSessionsRequest): ResponseEntity<ApiResponse<List<LeaderPaperSessionDto>>> {
        if (request.candidateId <= 0) {
            return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_INVALID, "candidateId 无效", messageSource))
        }
        return safe(ErrorCode.SERVER_LEADER_RESEARCH_FETCH_FAILED) { researchService.paperSessions(request.candidateId) }
    }

    @PostMapping("/source-health")
    fun sourceHealth(): ResponseEntity<ApiResponse<List<LeaderResearchSourceStateDto>>> {
        return safe(ErrorCode.SERVER_LEADER_RESEARCH_FETCH_FAILED) { researchService.sourceHealth() }
    }

    @PostMapping("/events/list")
    fun events(@RequestBody request: LeaderResearchEventsRequest): ResponseEntity<ApiResponse<List<LeaderResearchEventDto>>> {
        return safe(ErrorCode.SERVER_LEADER_RESEARCH_FETCH_FAILED) { researchService.events(request.page, request.size) }
    }

    @PostMapping("/approval/create-disabled-trial-config")
    fun approve(@RequestBody request: LeaderResearchApprovalRequest): ResponseEntity<ApiResponse<LeaderResearchApprovalResponse>> {
        if (request.candidateId <= 0) {
            return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_INVALID, "candidateId 无效", messageSource))
        }
        if (request.accountId <= 0) {
            return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ACCOUNT_ID_INVALID, messageSource = messageSource))
        }
        return try {
            approvalService.createDisabledTrialConfig(request).fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                onFailure = { e -> errorResponse(e, ErrorCode.SERVER_LEADER_RESEARCH_APPROVAL_FAILED) }
            )
        } catch (e: Exception) {
            logger.error("Leader research approval failed", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_LEADER_RESEARCH_APPROVAL_FAILED, e.message, messageSource))
        }
    }

    private fun <T> safe(errorCode: ErrorCode, block: () -> T): ResponseEntity<ApiResponse<T>> {
        return try {
            ResponseEntity.ok(ApiResponse.success(block()))
        } catch (e: Exception) {
            logger.error("Leader research request failed", e)
            ResponseEntity.ok(ApiResponse.error(errorCode, e.message, messageSource))
        }
    }

    private fun <T> errorResponse(e: Throwable, fallback: ErrorCode): ResponseEntity<ApiResponse<T>> {
        val errorCode = when (e) {
            is LeaderResearchCandidateNotReadyException -> ErrorCode.LEADER_RESEARCH_CANDIDATE_NOT_READY
            is LeaderResearchApprovalConfirmRequiredException -> ErrorCode.LEADER_RESEARCH_APPROVAL_CONFIRM_REQUIRED
            is LeaderResearchDuplicateTrialConfigException -> ErrorCode.LEADER_RESEARCH_DUPLICATE_TRIAL_CONFIG
            is LeaderResearchRealMoneyForbiddenException -> ErrorCode.LEADER_RESEARCH_REAL_MONEY_FORBIDDEN
            is LeaderResearchCandidateLockedException -> ErrorCode.LEADER_RESEARCH_CANDIDATE_LOCKED
            is IllegalArgumentException -> when (e.message) {
                "账户不存在" -> ErrorCode.ACCOUNT_NOT_FOUND
                "候选不存在" -> ErrorCode.LEADER_RESEARCH_CANDIDATE_NOT_FOUND
                else -> ErrorCode.PARAM_ERROR
            }
            else -> fallback
        }
        return ResponseEntity.ok(ApiResponse.error(errorCode, null, messageSource))
    }
}
