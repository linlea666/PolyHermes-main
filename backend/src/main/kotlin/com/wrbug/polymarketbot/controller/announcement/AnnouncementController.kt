package com.wrbug.polymarketbot.controller.announcement

import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.service.announcement.AnnouncementService
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * 公告控制器
 */
@RestController
@RequestMapping("/api/announcements")
class AnnouncementController(
    private val announcementService: AnnouncementService,
    private val messageSource: MessageSource
) {
    
    private val logger = LoggerFactory.getLogger(AnnouncementController::class.java)
    
    /**
     * 获取公告列表（最近10条）
     */
    @PostMapping("/list")
    fun getAnnouncementList(@RequestBody request: AnnouncementListRequest): ResponseEntity<ApiResponse<AnnouncementListResponse>> {
        return try {
            val result = runBlocking { announcementService.getAnnouncementList(request.forceRefresh) }
            result.fold(
                onSuccess = { response ->
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e ->
                    logger.error("获取公告列表失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("获取公告列表异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }
    
    /**
     * 获取公告详情
     */
    @PostMapping("/detail")
    fun getAnnouncementDetail(@RequestBody request: AnnouncementDetailRequest): ResponseEntity<ApiResponse<AnnouncementDto>> {
        return try {
            val result = runBlocking { announcementService.getAnnouncementDetail(request.id, request.forceRefresh) }
            result.fold(
                onSuccess = { announcement ->
                    ResponseEntity.ok(ApiResponse.success(announcement))
                },
                onFailure = { e ->
                    logger.error("获取公告详情失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(
                            ApiResponse.error(ErrorCode.PARAM_ERROR, e.message, messageSource)
                        )
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("获取公告详情异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }
}

