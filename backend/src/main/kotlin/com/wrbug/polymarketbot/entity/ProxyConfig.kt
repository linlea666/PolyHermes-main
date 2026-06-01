package com.wrbug.polymarketbot.entity

import jakarta.persistence.*

/**
 * 代理配置实体
 * 支持 HTTP 代理和订阅代理（Clash/SS）
 */
@Entity
@Table(name = "proxy_config")
data class ProxyConfig(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "type", nullable = false, length = 20)
    val type: String,  // HTTP, CLASH, SS
    
    @Column(name = "enabled", nullable = false)
    val enabled: Boolean = false,
    
    @Column(name = "host", length = 255)
    val host: String? = null,  // HTTP 代理主机
    
    @Column(name = "port")
    val port: Int? = null,  // HTTP 代理端口
    
    @Column(name = "username", length = 100)
    val username: String? = null,  // HTTP 代理用户名（可选）
    
    @Column(name = "password", length = 255)
    val password: String? = null,  // HTTP 代理密码（BCrypt加密，可选）
    
    @Column(name = "subscription_url", length = 500)
    val subscriptionUrl: String? = null,  // 订阅链接（Clash/SS）
    
    @Column(name = "subscription_config", columnDefinition = "TEXT")
    val subscriptionConfig: String? = null,  // 订阅配置内容（JSON格式）
    
    @Column(name = "last_subscription_update")
    val lastSubscriptionUpdate: Long? = null,  // 最后订阅更新时间
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)

