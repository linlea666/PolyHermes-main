package com.wrbug.polymarketbot.controller.copytrading.research

import com.wrbug.polymarketbot.dto.LeaderResearchApprovalRequest
import com.wrbug.polymarketbot.dto.LeaderResearchRunRequest
import com.wrbug.polymarketbot.entity.LeaderResearchRun
import com.wrbug.polymarketbot.enums.LeaderResearchTriggerType
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.service.copytrading.research.LeaderResearchApprovalConfirmRequiredException
import com.wrbug.polymarketbot.service.copytrading.research.LeaderResearchApprovalService
import com.wrbug.polymarketbot.service.copytrading.research.LeaderResearchCandidateLockedException
import com.wrbug.polymarketbot.service.copytrading.research.LeaderResearchJobService
import com.wrbug.polymarketbot.service.copytrading.research.LeaderResearchMapper
import com.wrbug.polymarketbot.service.copytrading.research.LeaderResearchService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.context.support.StaticMessageSource

class LeaderResearchControllerTest {
    private val jobService: LeaderResearchJobService = mock()
    private val researchService: LeaderResearchService = mock()
    private val approvalService: LeaderResearchApprovalService = mock()
    private val mapper: LeaderResearchMapper = mock()
    private val controller = LeaderResearchController(
        jobService = jobService,
        researchService = researchService,
        approvalService = approvalService,
        mapper = mapper,
        messageSource = StaticMessageSource()
    )

    @Test
    fun `manual run queues async run and returns run dto`() {
        val run = LeaderResearchRun(id = 1L)
        Mockito.`when`(jobService.startAsync(false, LeaderResearchTriggerType.MANUAL)).thenReturn(run)
        Mockito.`when`(mapper.runDto(run)).thenReturn(
            com.wrbug.polymarketbot.dto.LeaderResearchRunDto(
                id = 1,
                status = "RUNNING",
                triggerType = "MANUAL",
                dryRun = false,
                startedAt = run.startedAt,
                finishedAt = null,
                durationMs = null,
                sourceCountsJson = null,
                candidateCountsJson = null,
                partialFailure = false,
                skippedReason = null,
                errorClass = null,
                errorMessage = null
            )
        )

        val response = controller.run(LeaderResearchRunRequest())

        assertEquals(0, response.body!!.code)
        assertEquals(1, response.body!!.data!!.id)
        Mockito.verify(jobService).startAsync(false, LeaderResearchTriggerType.MANUAL)
        Mockito.verify(jobService, Mockito.never()).runOnce(false, LeaderResearchTriggerType.MANUAL)
    }

    @Test
    fun `preview run stays synchronous`() {
        val run = LeaderResearchRun(id = 2L, dryRun = true, triggerType = LeaderResearchTriggerType.PREVIEW)
        Mockito.`when`(jobService.runOnce(true, LeaderResearchTriggerType.PREVIEW)).thenReturn(run)
        Mockito.`when`(mapper.runDto(run)).thenReturn(
            com.wrbug.polymarketbot.dto.LeaderResearchRunDto(
                id = 2,
                status = "SUCCESS",
                triggerType = "PREVIEW",
                dryRun = true,
                startedAt = run.startedAt,
                finishedAt = null,
                durationMs = null,
                sourceCountsJson = null,
                candidateCountsJson = null,
                partialFailure = false,
                skippedReason = null,
                errorClass = null,
                errorMessage = null
            )
        )

        val response = controller.run(LeaderResearchRunRequest(dryRun = true, triggerType = "PREVIEW"))

        assertEquals(0, response.body!!.code)
        assertEquals(2, response.body!!.data!!.id)
        Mockito.verify(jobService).runOnce(true, LeaderResearchTriggerType.PREVIEW)
        Mockito.verify(jobService, Mockito.never()).startAsync(true, LeaderResearchTriggerType.PREVIEW)
    }

    @Test
    fun `detail rejects invalid candidate id`() {
        val response = controller.detail(LeaderResearchDetailRequest(candidateId = 0))

        assertEquals(ErrorCode.PARAM_INVALID.code, response.body!!.code)
    }

    @Test
    fun `approval maps confirm required`() {
        Mockito.`when`(approvalService.createDisabledTrialConfig(anyApprovalRequest()))
            .thenReturn(Result.failure(LeaderResearchApprovalConfirmRequiredException()))

        val response = controller.approve(LeaderResearchApprovalRequest(candidateId = 1, accountId = 2, confirm = false))

        assertEquals(ErrorCode.LEADER_RESEARCH_APPROVAL_CONFIRM_REQUIRED.code, response.body!!.code)
    }

    @Test
    fun `approval maps locked candidate`() {
        Mockito.`when`(approvalService.createDisabledTrialConfig(anyApprovalRequest()))
            .thenReturn(Result.failure(LeaderResearchCandidateLockedException()))

        val response = controller.approve(LeaderResearchApprovalRequest(candidateId = 1, accountId = 2, confirm = true))

        assertEquals(ErrorCode.LEADER_RESEARCH_CANDIDATE_LOCKED.code, response.body!!.code)
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)

    private fun anyApprovalRequest(): LeaderResearchApprovalRequest {
        Mockito.any(LeaderResearchApprovalRequest::class.java)
        return LeaderResearchApprovalRequest(candidateId = 1, accountId = 2, confirm = true)
    }
}
