package com.wrbug.polymarketbot.dto

/**
 * API 健康检查响应
 */
data class ApiHealthCheckDto(
    val name: String,  // API 名称
    val url: String,  // API URL
    val status: String,  // "success" 或 "error"
    val message: String,  // 状态消息
    val responseTime: Long? = null  // 响应时间（毫秒）
)

/**
 * 所有 API 健康检查响应
 */
data class ApiHealthCheckResponse(
    val apis: List<ApiHealthCheckDto>
)

