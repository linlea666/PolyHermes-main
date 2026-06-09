package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.repository.CryptoTailStrategyRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

/**
 * 后台账户余额刷新器。
 *
 * 根因：crypto-tail 进场热路径原本每次同步拉链上余额（getWalletBalance 串行 3 个远程往返，~1.8s），
 * 把"触发→发单"拖到 ~3s，使 SCALP 用过期盘口发单、FAK 频繁 kill。
 *
 * 方案：把"取链上余额"与"下单"解耦——本调度器周期性刷新所有启用中 crypto-tail 账户的可用余额，
 * 写入 CryptoTailEntryGuardService 的本地缓存；进场只读缓存（~0 延迟）。
 * 仅刷新"启用中策略"涉及的账户，避免刷无关账户或漏掉新账户。
 */
@Service
class CryptoTailBalanceRefreshService(
    private val strategyRepository: CryptoTailStrategyRepository,
    private val entryGuardService: CryptoTailEntryGuardService
) {
    private val logger = LoggerFactory.getLogger(CryptoTailBalanceRefreshService::class.java)

    @Scheduled(fixedDelayString = "\${crypto-tail.scalp.balance-refresh-interval-ms:5000}")
    fun refreshActiveAccountBalances() {
        val accountIds = try {
            strategyRepository.findAllByEnabledTrue().map { it.accountId }.distinct()
        } catch (e: Exception) {
            logger.warn("查询启用中 crypto-tail 账户失败，跳过本轮余额刷新: ${e.message}")
            return
        }
        if (accountIds.isEmpty()) return
        for (accountId in accountIds) {
            try {
                entryGuardService.refreshRawAvailableCache(accountId)
            } catch (e: Exception) {
                logger.warn("刷新 crypto-tail 账户余额缓存失败: accountId=$accountId, ${e.message}")
            }
        }
    }
}
