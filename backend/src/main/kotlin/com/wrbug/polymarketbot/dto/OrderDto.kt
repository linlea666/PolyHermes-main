package com.wrbug.polymarketbot.dto

/**
 * 订单 DTO（用于返回给前端）
 */
data class OrderDto(
    val id: String,
    val market: String,
    val side: String,
    val price: String,
    val size: String,
    val filled: String,
    val status: String,
    val createdAt: Long  // 时间戳（毫秒）
)

/**
 * 交易 DTO
 */
data class TradeDto(
    val id: String,
    val market: String,
    val side: String,
    val price: String,
    val size: String,
    val timestamp: Long,  // 时间戳（毫秒）
    val user: String?
)

