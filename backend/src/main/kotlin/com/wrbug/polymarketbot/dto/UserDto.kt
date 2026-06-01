package com.wrbug.polymarketbot.dto

/**
 * 用户DTO
 */
data class UserDto(
    val id: Long,
    val username: String,
    val isDefault: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

