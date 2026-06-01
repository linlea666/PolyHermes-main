package com.wrbug.polymarketbot.entity

import jakarta.persistence.*

/**
 * 消息推送配置实体
 * 支持多种推送方式（Telegram、Discord、Slack 等）
 */
@Entity
@Table(name = "notification_configs")
data class NotificationConfig(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "type", nullable = false, length = 50)
    val type: String,  // telegram、discord、slack 等
    
    @Column(name = "name", nullable = false, length = 100)
    val name: String,  // 配置名称（用于显示）
    
    @Column(name = "enabled", nullable = false)
    var enabled: Boolean = true,  // 是否启用
    
    @Column(name = "config_json", nullable = false, columnDefinition = "TEXT")
    val configJson: String,  // 配置信息（JSON格式）
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)

