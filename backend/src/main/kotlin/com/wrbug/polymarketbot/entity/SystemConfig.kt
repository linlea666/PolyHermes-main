package com.wrbug.polymarketbot.entity

import jakarta.persistence.*

/**
 * 系统配置实体
 * 用于存储系统级别的配置（如 Builder API Key）
 */
@Entity
@Table(name = "system_config")
data class SystemConfig(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "config_key", unique = true, nullable = false, length = 100)
    val configKey: String,  // 配置键（唯一）
    
    @Column(name = "config_value", columnDefinition = "TEXT")
    val configValue: String? = null,  // 配置值（加密存储）
    
    @Column(name = "description", length = 255)
    val description: String? = null,  // 配置描述
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)

