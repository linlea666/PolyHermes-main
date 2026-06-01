package com.wrbug.polymarketbot.util

import com.wrbug.polymarketbot.entity.ProxyConfig
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * 代理配置提供者（单例）
 * 用于在工具函数中获取代理配置
 */
object ProxyConfigProvider {
    @Volatile
    private var proxyConfig: ProxyConfig? = null
    
    /**
     * 设置代理配置（由 ProxyConfigService 调用）
     */
    fun setProxyConfig(config: ProxyConfig?) {
        proxyConfig = config
    }
    
    /**
     * 获取代理配置
     */
    fun getProxyConfig(): ProxyConfig? = proxyConfig
    
    /**
     * 获取 Proxy 对象（用于 OkHttp）
     */
    fun getProxy(): Proxy? {
        val config = proxyConfig ?: return null
        if (!config.enabled) {
            return null
        }
        if (config.type != "HTTP") {
            return null  // 目前只支持 HTTP 代理
        }
        if (config.host == null || config.port == null) {
            return null
        }
        return Proxy(Proxy.Type.HTTP, InetSocketAddress(config.host, config.port))
    }
    
    /**
     * 获取代理用户名
     */
    fun getProxyUsername(): String? = proxyConfig?.username
    
    /**
     * 获取代理密码
     */
    fun getProxyPassword(): String? = proxyConfig?.password
}

