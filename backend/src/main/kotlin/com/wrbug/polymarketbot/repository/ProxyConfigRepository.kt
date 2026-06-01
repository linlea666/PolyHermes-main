package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.ProxyConfig
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/**
 * 代理配置 Repository
 */
@Repository
interface ProxyConfigRepository : JpaRepository<ProxyConfig, Long> {
    /**
     * 查找启用的代理配置
     */
    fun findByEnabledTrue(): ProxyConfig?
    
    /**
     * 根据类型查找启用的代理配置
     */
    fun findByTypeAndEnabledTrue(type: String): ProxyConfig?
    
    /**
     * 根据类型查找代理配置（无论是否启用）
     */
    fun findByType(type: String): ProxyConfig?
}

