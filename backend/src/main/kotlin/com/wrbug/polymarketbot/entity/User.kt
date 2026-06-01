package com.wrbug.polymarketbot.entity

import jakarta.persistence.*

/**
 * 用户实体（用于JWT登录鉴权）
 */
@Entity
@Table(name = "users")
data class User(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "username", unique = true, nullable = false, length = 50)
    val username: String,
    
    @Column(name = "password", nullable = false, length = 255)
    val password: String,  // BCrypt加密后的密码
    
    @Column(name = "is_default", nullable = false)
    val isDefault: Boolean = false,  // 是否默认账户（首次创建的用户）
    
    @Column(name = "token_version", nullable = false)
    var tokenVersion: Long = 0,  // Token版本号，修改密码时递增，用于使所有JWT失效
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)

