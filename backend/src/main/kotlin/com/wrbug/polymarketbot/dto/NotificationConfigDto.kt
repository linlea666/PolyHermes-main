package com.wrbug.polymarketbot.dto

/**
 * 消息推送配置 DTO
 */
data class NotificationConfigDto(
    val id: Long? = null,
    val type: String,  // telegram、discord、slack 等
    val name: String,  // 配置名称
    val enabled: Boolean,  // 是否启用
    val config: NotificationConfigData,  // 配置信息（根据类型不同而不同）
    val createdAt: Long? = null,
    val updatedAt: Long? = null
)

/**
 * Telegram 配置数据
 */
data class TelegramConfigData(
    val botToken: String,
    val chatIds: List<String>  // 多个 Chat ID，逗号分隔或数组
)

/**
 * 通用配置数据（用于未来扩展）
 */
sealed class NotificationConfigData {
    data class Telegram(val data: TelegramConfigData) : NotificationConfigData()
    // 未来可以添加其他类型
    // data class Discord(val data: DiscordConfigData) : NotificationConfigData()
    // data class Slack(val data: SlackConfigData) : NotificationConfigData()
}

/**
 * 创建/更新配置请求
 */
data class NotificationConfigRequest(
    val type: String,
    val name: String,
    val enabled: Boolean? = true,
    val config: Map<String, Any>  // 配置信息（JSON 对象）
)

/**
 * 测试通知请求
 */
data class TestNotificationRequest(
    val configId: Long? = null,  // 如果提供，使用指定配置；否则使用所有启用的配置
    val message: String? = null  // 测试消息内容
)

