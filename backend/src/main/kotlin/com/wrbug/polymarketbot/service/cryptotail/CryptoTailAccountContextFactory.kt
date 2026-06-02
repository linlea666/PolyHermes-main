package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.api.PolymarketClobApi
import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.service.copytrading.orders.OrderSigningService
import com.wrbug.polymarketbot.util.CryptoUtils
import com.wrbug.polymarketbot.util.RetrofitFactory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 加密价差策略账户下单上下文：解密私钥/凭证 + L2 CLOB 客户端 + 签名类型。
 * 由 BRACKET 退出 (BracketExitService) 与退出对账 (ExitOrderReconciler) 共用，
 * 避免在多个新服务中重复"按 strategy.accountId 解密 + 构建 ClobApi"的样板代码。
 *
 * 注：CryptoTailStrategyExecutionService 内部仍保留私有 AccountOrderCtx + buildAccountOrderCtx，
 * 行为零变更（"保守修改"原则）；此工厂仅服务新代码路径。
 */
@Component
class CryptoTailAccountContextFactory(
    private val accountRepository: AccountRepository,
    private val cryptoUtils: CryptoUtils,
    private val retrofitFactory: RetrofitFactory,
    private val orderSigningService: OrderSigningService
) {

    private val logger = LoggerFactory.getLogger(CryptoTailAccountContextFactory::class.java)

    /**
     * 构建账户上下文。
     * 缺凭证 / 解密失败 / 私钥为空 → 返回 null（调用方应跳过本次操作并记日志）。
     */
    fun build(strategy: CryptoTailStrategy): CryptoTailAccountCtx? {
        val account = accountRepository.findById(strategy.accountId).orElse(null) ?: return null
        if (account.apiKey == null || account.apiSecret == null || account.apiPassphrase == null) return null
        val privateKey = try {
            cryptoUtils.decrypt(account.privateKey) ?: ""
        } catch (e: Exception) {
            logger.error("阶梯模式解密私钥失败: accountId=${account.id}", e)
            return null
        }
        if (privateKey.isBlank()) return null
        val apiSecret = try { cryptoUtils.decrypt(account.apiSecret) ?: "" } catch (e: Exception) { "" }
        val apiPassphrase = try { cryptoUtils.decrypt(account.apiPassphrase) ?: "" } catch (e: Exception) { "" }
        val clobApi = retrofitFactory.createClobApi(account.apiKey, apiSecret, apiPassphrase, account.walletAddress)
        val signatureType = orderSigningService.getSignatureTypeForWalletType(account.walletType)
        return CryptoTailAccountCtx(account, clobApi, account.apiKey!!, privateKey, account.proxyAddress, signatureType)
    }
}

/**
 * 阶梯模式账户下单上下文（公共 data class）。
 */
data class CryptoTailAccountCtx(
    val account: Account,
    val clobApi: PolymarketClobApi,
    val apiKey: String,
    val decryptedPrivateKey: String,
    val proxyAddress: String,
    val signatureType: Int
)
