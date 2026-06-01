package com.wrbug.polymarketbot.service.system

import com.fasterxml.jackson.databind.ObjectMapper
import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.entity.NotificationConfig
import com.wrbug.polymarketbot.repository.NotificationConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 消息推送配置服务
 */
@Service
class NotificationConfigService(
    private val notificationConfigRepository: NotificationConfigRepository,
    private val objectMapper: ObjectMapper
) {
    
    private val logger = LoggerFactory.getLogger(NotificationConfigService::class.java)
    
    /**
     * 获取所有配置
     */
    suspend fun getAllConfigs(): List<NotificationConfigDto> {
        return withContext(Dispatchers.IO) {
            notificationConfigRepository.findAll().map { entityToDto(it) }
        }
    }
    
    /**
     * 根据类型获取配置
     */
    suspend fun getConfigsByType(type: String): List<NotificationConfigDto> {
        return withContext(Dispatchers.IO) {
            notificationConfigRepository.findByType(type).map { entityToDto(it) }
        }
    }
    
    /**
     * 获取启用的配置（按类型）
     */
    suspend fun getEnabledConfigsByType(type: String): List<NotificationConfigDto> {
        return withContext(Dispatchers.IO) {
            notificationConfigRepository.findByTypeAndEnabled(type, true).map { entityToDto(it) }
        }
    }
    
    /**
     * 根据 ID 获取配置
     */
    suspend fun getConfigById(id: Long): NotificationConfigDto? {
        return withContext(Dispatchers.IO) {
            notificationConfigRepository.findById(id).orElse(null)?.let { entityToDto(it) }
        }
    }
    
    /**
     * 创建配置
     */
    @Transactional
    suspend fun createConfig(request: NotificationConfigRequest): Result<NotificationConfigDto> {
        return try {
            // 验证配置数据
            validateConfig(request.type, request.config)
            
            val configJson = objectMapper.writeValueAsString(request.config)
            val config = NotificationConfig(
                type = request.type,
                name = request.name,
                enabled = request.enabled ?: true,
                configJson = configJson
            )
            
            val saved = withContext(Dispatchers.IO) {
                notificationConfigRepository.save(config)
            }
            
            Result.success(entityToDto(saved))
        } catch (e: Exception) {
            logger.error("创建通知配置失败: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 更新配置
     */
    @Transactional
    suspend fun updateConfig(id: Long, request: NotificationConfigRequest): Result<NotificationConfigDto> {
        return try {
            val existing = withContext(Dispatchers.IO) {
                notificationConfigRepository.findById(id).orElse(null)
            } ?: return Result.failure(IllegalArgumentException("配置不存在"))
            
            // 验证配置数据
            validateConfig(request.type, request.config)
            
            val configJson = objectMapper.writeValueAsString(request.config)
            val updated = existing.copy(
                type = request.type,
                name = request.name,
                enabled = request.enabled ?: existing.enabled,
                configJson = configJson,
                updatedAt = System.currentTimeMillis()
            )
            
            val saved = withContext(Dispatchers.IO) {
                notificationConfigRepository.save(updated)
            }
            
            Result.success(entityToDto(saved))
        } catch (e: Exception) {
            logger.error("更新通知配置失败: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 更新启用状态
     */
    @Transactional
    suspend fun updateEnabled(id: Long, enabled: Boolean): Result<NotificationConfigDto> {
        return try {
            val existing = withContext(Dispatchers.IO) {
                notificationConfigRepository.findById(id).orElse(null)
            } ?: return Result.failure(IllegalArgumentException("配置不存在"))
            
            val updated = existing.copy(
                enabled = enabled,
                updatedAt = System.currentTimeMillis()
            )
            
            val saved = withContext(Dispatchers.IO) {
                notificationConfigRepository.save(updated)
            }
            
            Result.success(entityToDto(saved))
        } catch (e: Exception) {
            logger.error("更新通知配置启用状态失败: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 删除配置
     */
    @Transactional
    suspend fun deleteConfig(id: Long): Result<Unit> {
        return try {
            withContext(Dispatchers.IO) {
                notificationConfigRepository.deleteById(id)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("删除通知配置失败: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 验证配置数据
     */
    private fun validateConfig(type: String, config: Map<String, Any>) {
        when (type.lowercase()) {
            "telegram" -> {
                val botToken = config["botToken"] as? String
                val chatIds = config["chatIds"]
                
                if (botToken.isNullOrBlank()) {
                    throw IllegalArgumentException("Telegram Bot Token 不能为空")
                }
                
                if (chatIds == null) {
                    throw IllegalArgumentException("Telegram Chat IDs 不能为空")
                }
                
                // 支持数组或逗号分隔的字符串
                val chatIdList = when (chatIds) {
                    is List<*> -> chatIds.mapNotNull { it?.toString() }.filter { it.isNotBlank() }
                    is String -> chatIds.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    else -> throw IllegalArgumentException("Chat IDs 格式错误，应为数组或逗号分隔的字符串")
                }
                
                if (chatIdList.isEmpty()) {
                    throw IllegalArgumentException("至少需要一个 Chat ID")
                }
            }
            // 未来可以添加其他类型的验证
            else -> {
                // 其他类型暂时不验证，允许扩展
            }
        }
    }
    
    /**
     * 实体转 DTO
     */
    private fun entityToDto(entity: NotificationConfig): NotificationConfigDto {
        val configMap = try {
            objectMapper.readValue(entity.configJson, Map::class.java) as Map<String, Any>
        } catch (e: Exception) {
            logger.error("解析配置 JSON 失败: ${e.message}", e)
            emptyMap()
        }
        
        val configData = when (entity.type.lowercase()) {
            "telegram" -> {
                val botToken = configMap["botToken"]?.toString() ?: ""
                val chatIds = when (val ids = configMap["chatIds"]) {
                    is List<*> -> ids.mapNotNull { it?.toString() }
                    is String -> ids.split(",").map { it.trim() }
                    else -> emptyList()
                }
                NotificationConfigData.Telegram(TelegramConfigData(botToken, chatIds))
            }
            else -> {
                // 其他类型暂时不支持，返回空配置
                NotificationConfigData.Telegram(TelegramConfigData("", emptyList()))
            }
        }
        
        return NotificationConfigDto(
            id = entity.id,
            type = entity.type,
            name = entity.name,
            enabled = entity.enabled,
            config = configData,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt
        )
    }
}

