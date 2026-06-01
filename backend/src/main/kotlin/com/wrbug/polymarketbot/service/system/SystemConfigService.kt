package com.wrbug.polymarketbot.service.system

import com.wrbug.polymarketbot.dto.ChainlinkConfigUpdateRequest
import com.wrbug.polymarketbot.dto.SystemConfigDto
import com.wrbug.polymarketbot.dto.SystemConfigUpdateRequest
import com.wrbug.polymarketbot.entity.SystemConfig
import com.wrbug.polymarketbot.repository.SystemConfigRepository
import com.wrbug.polymarketbot.util.CryptoUtils
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 系统配置服务
 */
@Service
class SystemConfigService(
    private val systemConfigRepository: SystemConfigRepository,
    private val cryptoUtils: CryptoUtils
) {

    private val logger = LoggerFactory.getLogger(SystemConfigService::class.java)

    companion object {
        const val CONFIG_KEY_BUILDER_API_KEY = "builder.api_key"
        const val CONFIG_KEY_BUILDER_SECRET = "builder.secret"
        const val CONFIG_KEY_BUILDER_PASSPHRASE = "builder.passphrase"
        const val CONFIG_KEY_AUTO_REDEEM = "auto_redeem"

        // Chainlink Data Streams（crypto-tail 障碍模式价源，与 Polymarket 结算源一致）
        const val CONFIG_KEY_CHAINLINK_DS_API_KEY = "chainlink.ds.api_key"        // 加密存储
        const val CONFIG_KEY_CHAINLINK_DS_API_SECRET = "chainlink.ds.api_secret"  // 加密存储
        const val CONFIG_KEY_CHAINLINK_DS_REST_BASE = "chainlink.ds.rest_base"    // 明文，可选覆盖
        const val CONFIG_KEY_CHAINLINK_DS_FEED_BTC = "chainlink.ds.feed.btc"      // 明文 feedID
        const val CONFIG_KEY_CHAINLINK_DS_FEED_ETH = "chainlink.ds.feed.eth"
        const val CONFIG_KEY_CHAINLINK_DS_FEED_SOL = "chainlink.ds.feed.sol"
        const val CONFIG_KEY_CHAINLINK_DS_FEED_XRP = "chainlink.ds.feed.xrp"
    }

    /**
     * 获取系统配置
     */
    fun getSystemConfig(): SystemConfigDto {
        val builderApiKey = getConfigValue(CONFIG_KEY_BUILDER_API_KEY)
        val builderSecret = getConfigValue(CONFIG_KEY_BUILDER_SECRET)
        val builderPassphrase = getConfigValue(CONFIG_KEY_BUILDER_PASSPHRASE)
        val autoRedeem = isAutoRedeemEnabled()

        // 获取完整显示值（用于前端展示与编辑）
        val builderApiKeyDisplay = builderApiKey?.let {
            try {
                cryptoUtils.decrypt(it)
            } catch (e: Exception) {
                null
            }
        }

        val builderSecretDisplay = builderSecret?.let {
            try {
                cryptoUtils.decrypt(it)
            } catch (e: Exception) {
                null
            }
        }

        val builderPassphraseDisplay = builderPassphrase?.let {
            try {
                cryptoUtils.decrypt(it)
            } catch (e: Exception) {
                null
            }
        }

        val clApiKey = getConfigValue(CONFIG_KEY_CHAINLINK_DS_API_KEY)
        val clApiSecret = getConfigValue(CONFIG_KEY_CHAINLINK_DS_API_SECRET)
        val clApiKeyDisplay = clApiKey?.let { try { cryptoUtils.decrypt(it) } catch (e: Exception) { null } }

        return SystemConfigDto(
            builderApiKeyConfigured = builderApiKey != null,
            builderSecretConfigured = builderSecret != null,
            builderPassphraseConfigured = builderPassphrase != null,
            builderApiKeyDisplay = builderApiKeyDisplay,
            builderSecretDisplay = builderSecretDisplay,
            builderPassphraseDisplay = builderPassphraseDisplay,
            autoRedeemEnabled = autoRedeem,
            chainlinkApiKeyConfigured = clApiKey != null,
            chainlinkApiSecretConfigured = clApiSecret != null,
            chainlinkApiKeyDisplay = clApiKeyDisplay,
            chainlinkRestBase = getConfigValue(CONFIG_KEY_CHAINLINK_DS_REST_BASE),
            chainlinkFeedBtc = getConfigValue(CONFIG_KEY_CHAINLINK_DS_FEED_BTC),
            chainlinkFeedEth = getConfigValue(CONFIG_KEY_CHAINLINK_DS_FEED_ETH),
            chainlinkFeedSol = getConfigValue(CONFIG_KEY_CHAINLINK_DS_FEED_SOL),
            chainlinkFeedXrp = getConfigValue(CONFIG_KEY_CHAINLINK_DS_FEED_XRP)
        )
    }

    /**
     * 更新 Chainlink Data Streams 配置（api key/secret 加密，feedID/restBase 明文）。
     * 仅当请求字段非 null 时更新；空串表示清空该项。
     */
    @Transactional
    fun updateChainlinkConfig(request: ChainlinkConfigUpdateRequest): Result<SystemConfigDto> {
        return try {
            request.apiKey?.let {
                updateConfigValue(CONFIG_KEY_CHAINLINK_DS_API_KEY, if (it.isNotBlank()) cryptoUtils.encrypt(it) else null)
            }
            request.apiSecret?.let {
                updateConfigValue(CONFIG_KEY_CHAINLINK_DS_API_SECRET, if (it.isNotBlank()) cryptoUtils.encrypt(it) else null)
            }
            request.restBase?.let { updateConfigValue(CONFIG_KEY_CHAINLINK_DS_REST_BASE, it.ifBlank { null }) }
            request.feedBtc?.let { updateConfigValue(CONFIG_KEY_CHAINLINK_DS_FEED_BTC, it.ifBlank { null }) }
            request.feedEth?.let { updateConfigValue(CONFIG_KEY_CHAINLINK_DS_FEED_ETH, it.ifBlank { null }) }
            request.feedSol?.let { updateConfigValue(CONFIG_KEY_CHAINLINK_DS_FEED_SOL, it.ifBlank { null }) }
            request.feedXrp?.let { updateConfigValue(CONFIG_KEY_CHAINLINK_DS_FEED_XRP, it.ifBlank { null }) }
            Result.success(getSystemConfig())
        } catch (e: Exception) {
            logger.error("更新 Chainlink Data Streams 配置失败", e)
            Result.failure(e)
        }
    }

    /** Chainlink Data Streams 凭证（解密），未配置返回 null */
    fun getChainlinkApiKey(): String? = getConfigValue(CONFIG_KEY_CHAINLINK_DS_API_KEY)?.let { cryptoUtils.decrypt(it) }
    fun getChainlinkApiSecret(): String? = getConfigValue(CONFIG_KEY_CHAINLINK_DS_API_SECRET)?.let { cryptoUtils.decrypt(it) }
    fun getChainlinkRestBase(): String? = getConfigValue(CONFIG_KEY_CHAINLINK_DS_REST_BASE)?.takeIf { it.isNotBlank() }

    /** 按市场 slug base（btc-updown/eth-updown/sol-updown/xrp-updown）取对应 feedID，未配置返回 null */
    fun getChainlinkFeedId(slugBase: String): String? {
        val key = when (slugBase.lowercase()) {
            "btc-updown" -> CONFIG_KEY_CHAINLINK_DS_FEED_BTC
            "eth-updown" -> CONFIG_KEY_CHAINLINK_DS_FEED_ETH
            "sol-updown" -> CONFIG_KEY_CHAINLINK_DS_FEED_SOL
            "xrp-updown" -> CONFIG_KEY_CHAINLINK_DS_FEED_XRP
            else -> return null
        }
        return getConfigValue(key)?.takeIf { it.isNotBlank() }
    }

    /**
     * 更新 Builder API Key 配置
     */
    @Transactional
    fun updateBuilderApiKey(request: SystemConfigUpdateRequest): Result<SystemConfigDto> {
        return try {
            // 更新 Builder API Key
            if (request.builderApiKey != null) {
                updateConfigValue(
                    CONFIG_KEY_BUILDER_API_KEY,
                    if (request.builderApiKey.isNotBlank()) {
                        cryptoUtils.encrypt(request.builderApiKey)
                    } else {
                        null  // 清空配置
                    }
                )
            }

            // 更新 Builder Secret
            if (request.builderSecret != null) {
                updateConfigValue(
                    CONFIG_KEY_BUILDER_SECRET,
                    if (request.builderSecret.isNotBlank()) {
                        cryptoUtils.encrypt(request.builderSecret)
                    } else {
                        null  // 清空配置
                    }
                )
            }

            // 更新 Builder Passphrase
            if (request.builderPassphrase != null) {
                updateConfigValue(
                    CONFIG_KEY_BUILDER_PASSPHRASE,
                    if (request.builderPassphrase.isNotBlank()) {
                        cryptoUtils.encrypt(request.builderPassphrase)
                    } else {
                        null  // 清空配置
                    }
                )
            }

            // 更新自动赎回配置
            if (request.autoRedeem != null) {
                updateConfigValue(
                    CONFIG_KEY_AUTO_REDEEM,
                    request.autoRedeem.toString()
                )
            }

            Result.success(getSystemConfig())
        } catch (e: Exception) {
            logger.error("更新系统配置失败", e)
            Result.failure(e)
        }
    }

    /**
     * 获取配置值（解密）
     */
    fun getBuilderApiKey(): String? {
        return getConfigValue(CONFIG_KEY_BUILDER_API_KEY)?.let { cryptoUtils.decrypt(it) }
    }

    fun getBuilderSecret(): String? {
        return getConfigValue(CONFIG_KEY_BUILDER_SECRET)?.let { cryptoUtils.decrypt(it) }
    }

    fun getBuilderPassphrase(): String? {
        return getConfigValue(CONFIG_KEY_BUILDER_PASSPHRASE)?.let { cryptoUtils.decrypt(it) }
    }

    /**
     * 检查 Builder API Key 是否已配置
     */
    fun isBuilderApiKeyConfigured(): Boolean {
        val apiKey = getConfigValue(CONFIG_KEY_BUILDER_API_KEY)
        val secret = getConfigValue(CONFIG_KEY_BUILDER_SECRET)
        val passphrase = getConfigValue(CONFIG_KEY_BUILDER_PASSPHRASE)
        return apiKey != null && secret != null && passphrase != null
    }

    /**
     * 检查自动赎回是否启用
     */
    fun isAutoRedeemEnabled(): Boolean {
        val autoRedeemValue = getConfigValue(CONFIG_KEY_AUTO_REDEEM)
        return when (autoRedeemValue?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> false  // 默认开启
        }
    }

    /**
     * 更新自动赎回配置
     */
    @Transactional
    fun updateAutoRedeem(enabled: Boolean): Result<SystemConfigDto> {
        return try {
            updateConfigValue(CONFIG_KEY_AUTO_REDEEM, enabled.toString())
            Result.success(getSystemConfig())
        } catch (e: Exception) {
            logger.error("更新自动赎回配置失败", e)
            Result.failure(e)
        }
    }

    /**
     * 获取配置值（原始值，加密存储）
     */
    private fun getConfigValue(configKey: String): String? {
        return systemConfigRepository.findByConfigKey(configKey)?.configValue
    }

    /**
     * 更新配置值
     */
    private fun updateConfigValue(configKey: String, configValue: String?) {
        val existing = systemConfigRepository.findByConfigKey(configKey)
        if (existing != null) {
            val updated = existing.copy(
                configValue = configValue,
                updatedAt = System.currentTimeMillis()
            )
            systemConfigRepository.save(updated)
        } else {
            val newConfig = SystemConfig(
                configKey = configKey,
                configValue = configValue,
                description = when (configKey) {
                    CONFIG_KEY_BUILDER_API_KEY -> "Builder API Key（用于 Gasless 交易）"
                    CONFIG_KEY_BUILDER_SECRET -> "Builder Secret（用于 Gasless 交易）"
                    CONFIG_KEY_BUILDER_PASSPHRASE -> "Builder Passphrase（用于 Gasless 交易）"
                    CONFIG_KEY_AUTO_REDEEM -> "自动赎回（系统级别配置，默认开启）"
                    CONFIG_KEY_CHAINLINK_DS_API_KEY -> "Chainlink Data Streams API Key（障碍模式价源）"
                    CONFIG_KEY_CHAINLINK_DS_API_SECRET -> "Chainlink Data Streams API Secret（障碍模式价源）"
                    CONFIG_KEY_CHAINLINK_DS_REST_BASE -> "Chainlink Data Streams REST 基址（可选覆盖）"
                    CONFIG_KEY_CHAINLINK_DS_FEED_BTC -> "Chainlink BTC/USD feedID"
                    CONFIG_KEY_CHAINLINK_DS_FEED_ETH -> "Chainlink ETH/USD feedID"
                    CONFIG_KEY_CHAINLINK_DS_FEED_SOL -> "Chainlink SOL/USD feedID"
                    CONFIG_KEY_CHAINLINK_DS_FEED_XRP -> "Chainlink XRP/USD feedID"
                    else -> null
                }
            )
            systemConfigRepository.save(newConfig)
        }
    }
}

