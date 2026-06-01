package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/**
 * 用户 Repository
 */
@Repository
interface UserRepository : JpaRepository<User, Long> {
    
    /**
     * 根据用户名查找用户
     */
    fun findByUsername(username: String): User?
    
    /**
     * 检查用户名是否存在
     */
    fun existsByUsername(username: String): Boolean
    
    /**
     * 查找默认账户
     */
    fun findByIsDefaultTrue(): User?
    
    /**
     * 查找所有用户，按创建时间排序
     */
    fun findAllByOrderByCreatedAtAsc(): List<User>
}

