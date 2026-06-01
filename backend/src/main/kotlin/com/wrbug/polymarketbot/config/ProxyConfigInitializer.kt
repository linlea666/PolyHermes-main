package com.wrbug.polymarketbot.config

import com.wrbug.polymarketbot.service.system.ProxyConfigService
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 代理配置初始化器
 * 在应用启动时初始化代理配置
 */
@Component
class ProxyConfigInitializer(
    private val proxyConfigService: ProxyConfigService
) {
    
    private val logger = LoggerFactory.getLogger(ProxyConfigInitializer::class.java)
    
    @PostConstruct
    fun init() {
        try {
            proxyConfigService.initProxyConfig()
        } catch (e: Exception) {
            logger.error("初始化代理配置失败", e)
        }
    }
}

