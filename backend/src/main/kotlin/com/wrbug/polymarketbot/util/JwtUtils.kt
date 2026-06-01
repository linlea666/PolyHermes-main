package com.wrbug.polymarketbot.util

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.*
import javax.crypto.SecretKey

/**
 * JWT工具类
 */
@Component
class JwtUtils {
    
    @Value("\${jwt.secret}")
    private lateinit var secret: String
    
    @Value("\${jwt.expiration}")
    private var expiration: Long = 604800000  // 7天，默认值
    
    @Value("\${jwt.refresh-threshold}")
    private var refreshThreshold: Long = 86400000  // 1天，默认值
    
    /**
     * 获取签名密钥
     * 支持十六进制字符串和普通字符串
     * 确保密钥长度至少 32 字节（256 位）以满足 JWT 规范要求
     */
    private fun getSigningKey(): SecretKey {
        val keyBytes = try {
            // 尝试将密钥作为十六进制字符串解析
            if (secret.length >= 64 && secret.matches(Regex("^[0-9a-fA-F]+$"))) {
                // 十六进制字符串，每 2 个字符 = 1 字节
                // 64 字符 = 32 字节（256 位），符合 JWT 要求
                secret.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            } else {
                // 普通字符串，使用 UTF-8 编码
                secret.toByteArray()
            }
        } catch (e: Exception) {
            // 如果解析失败，使用 UTF-8 编码
            secret.toByteArray()
        }
        
        // 确保密钥长度至少 32 字节（256 位）
        // 如果密钥长度不够，使用 SHA-256 哈希扩展到 32 字节（更安全）
        val finalKeyBytes = if (keyBytes.size < 32) {
            val messageDigest = java.security.MessageDigest.getInstance("SHA-256")
            messageDigest.update(keyBytes)
            messageDigest.digest()  // SHA-256 总是返回 32 字节
        } else if (keyBytes.size > 64) {
            // 如果太长，截取前 64 字节（支持 HS512，但通常使用 HS256 需要 32 字节）
            keyBytes.sliceArray(0 until 64)
        } else {
            keyBytes
        }
        
        return Keys.hmacShaKeyFor(finalKeyBytes)
    }
    
    /**
     * 生成JWT token
     * @param username 用户名
     * @param tokenVersion Token版本号（用于使修改密码后的旧token失效）
     * @return JWT token字符串
     */
    fun generateToken(username: String, tokenVersion: Long = 0): String {
        val now = Date()
        val expiryDate = Date(now.time + expiration)
        
        return Jwts.builder()
            .subject(username)
            .claim("tokenVersion", tokenVersion)  // 添加tokenVersion到payload
            .issuedAt(now)
            .expiration(expiryDate)
            .signWith(getSigningKey())
            .compact()
    }
    
    /**
     * 从token中获取Claims
     */
    private fun getClaimsFromToken(token: String): Claims? {
        return try {
            Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .payload
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 验证token是否有效
     */
    fun validateToken(token: String): Boolean {
        return try {
            val claims = getClaimsFromToken(token)
            claims != null && !isTokenExpired(token)
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 从token中获取用户名
     */
    fun getUsernameFromToken(token: String): String? {
        return try {
            val claims = getClaimsFromToken(token)
            claims?.subject
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 从token中获取tokenVersion
     */
    fun getTokenVersionFromToken(token: String): Long? {
        return try {
            val claims = getClaimsFromToken(token)
            val version = claims?.get("tokenVersion")
            when (version) {
                is Number -> version.toLong()
                is String -> version.toLongOrNull()
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 获取token签发时间（毫秒时间戳）
     */
    fun getIssuedAtFromToken(token: String): Long? {
        return try {
            val claims = getClaimsFromToken(token)
            claims?.issuedAt?.time
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 判断token是否已过期
     */
    fun isTokenExpired(token: String): Boolean {
        return try {
            val claims = getClaimsFromToken(token)
            val expiration = claims?.expiration ?: return true
            expiration.before(Date())
        } catch (e: Exception) {
            true
        }
    }
    
    /**
     * 判断token是否使用超过1天但未过期（用于自动刷新）
     */
    fun isTokenExpiring(token: String): Boolean {
        return try {
            if (isTokenExpired(token)) {
                return false
            }
            val issuedAt = getIssuedAtFromToken(token) ?: return false
            val now = System.currentTimeMillis()
            val timeSinceIssued = now - issuedAt
            // 如果使用时间超过刷新阈值（1天）但未过期，则需要刷新
            timeSinceIssued >= refreshThreshold
        } catch (e: Exception) {
            false
        }
    }
}

