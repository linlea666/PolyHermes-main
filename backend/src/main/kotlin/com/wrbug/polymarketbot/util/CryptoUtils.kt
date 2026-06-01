package com.wrbug.polymarketbot.util

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 加密工具类
 * 用于加密/解密敏感数据（如私钥）
 * 使用 AES-256 加密算法
 */
@Component
class CryptoUtils {
    
    @Value("\${encryption.key:\${jwt.secret}}")
    private lateinit var encryptionKey: String
    
    private val ALGORITHM = "AES"
    // 使用 AES/CBC/PKCS5Padding 模式，明确支持 AES-256
    // 注意：如果 JVM 不支持 256 位密钥，可能需要安装 JCE 无限强度策略文件
    private val TRANSFORMATION = "AES/CBC/PKCS5Padding"
    
    /**
     * 获取加密密钥（从配置的密钥派生 32 字节密钥）
     * 
     * 支持任意长度的密钥：
     * - 如果密钥是十六进制字符串（64 字符或更长），会解析为字节数组
     * - 如果是普通字符串，会使用 UTF-8 编码转换为字节数组
     * - 无论输入多长，都会通过 SHA-256 哈希成固定的 32 字节（256 位）
     * 
     * 这样设计的好处：
     * 1. 支持任意长度的密钥（短密钥、长密钥都可以）
     * 2. 确保密钥长度固定为 32 字节，满足 AES-256 要求
     * 3. 即使密钥很短，通过哈希后也能提供足够的安全性
     */
    private fun getSecretKey(): SecretKeySpec {
        val keyBytes = if (encryptionKey.length >= 64 && encryptionKey.matches(Regex("^[0-9a-fA-F]+$"))) {
            // 十六进制字符串，解析为字节数组
            encryptionKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } else {
            // 普通字符串，使用 UTF-8 编码
            encryptionKey.toByteArray(StandardCharsets.UTF_8)
        }
        
        // 使用 SHA-256 哈希确保密钥长度为 32 字节（256 位）
        // 无论输入密钥多长，都会哈希成固定的 32 字节
        val messageDigest = MessageDigest.getInstance("SHA-256")
        messageDigest.update(keyBytes)
        val hash = messageDigest.digest()
        
        return SecretKeySpec(hash, ALGORITHM)
    }
    
    /**
     * 加密数据
     * 使用 AES-256/CBC/PKCS5Padding 模式
     * 
     * @param plainText 明文
     * @return Base64 编码的密文（包含 IV）
     */
    fun encrypt(plainText: String): String {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val secretKey = getSecretKey()
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            
            // 获取 IV（初始化向量）
            val iv = cipher.iv
            val encryptedBytes = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
            
            // 将 IV 和加密数据组合：IV (16 字节) + 加密数据
            val combined = ByteArray(iv.size + encryptedBytes.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encryptedBytes, 0, combined, iv.size, encryptedBytes.size)
            
            Base64.getEncoder().encodeToString(combined)
        } catch (e: Exception) {
            throw RuntimeException("加密失败: ${e.message}", e)
        }
    }
    
    /**
     * 解密数据
     * 使用 AES-256/CBC/PKCS5Padding 模式
     * 
     * @param encryptedText Base64 编码的密文（包含 IV）
     * @return 明文
     */
    fun decrypt(encryptedText: String): String {
        return try {
            val combined = Base64.getDecoder().decode(encryptedText)
            
            // 提取 IV（前 16 字节）和加密数据
            val iv = ByteArray(16)
            System.arraycopy(combined, 0, iv, 0, 16)
            val encryptedBytes = ByteArray(combined.size - 16)
            System.arraycopy(combined, 16, encryptedBytes, 0, encryptedBytes.size)
            
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val secretKey = getSecretKey()
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            
            String(decryptedBytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            throw RuntimeException("解密失败: ${e.message}", e)
        }
    }
    
    /**
     * 检查字符串是否为加密后的数据（Base64 格式）
     * 注意：这不是完全可靠的检测方法，仅用于向后兼容
     */
    fun isEncrypted(text: String): Boolean {
        return try {
            // 尝试 Base64 解码，如果成功且长度合理，可能是加密数据
            val decoded = Base64.getDecoder().decode(text)
            // 加密后的数据长度应该是 16 字节的倍数（AES 块大小）
            decoded.size % 16 == 0 && decoded.size >= 16
        } catch (e: Exception) {
            false
        }
    }
}

