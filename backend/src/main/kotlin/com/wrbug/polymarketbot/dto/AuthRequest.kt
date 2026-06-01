package com.wrbug.polymarketbot.dto

/**
 * 登录请求
 */
data class LoginRequest(
    val username: String,
    val password: String
)

/**
 * 重置密码请求
 */
data class ResetPasswordRequest(
    val resetKey: String,
    val username: String,
    val newPassword: String
)

