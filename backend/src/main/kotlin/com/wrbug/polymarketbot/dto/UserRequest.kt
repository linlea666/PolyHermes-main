package com.wrbug.polymarketbot.dto

/**
 * 创建用户请求
 */
data class UserCreateRequest(
    val username: String,
    val password: String
)

/**
 * 更新用户密码请求（管理员修改其他用户密码）
 */
data class UserUpdatePasswordRequest(
    val userId: Long,
    val newPassword: String
)

/**
 * 用户修改自己密码请求
 */
data class UserUpdateOwnPasswordRequest(
    val newPassword: String
)

/**
 * 删除用户请求
 */
data class UserDeleteRequest(
    val userId: Long
)

