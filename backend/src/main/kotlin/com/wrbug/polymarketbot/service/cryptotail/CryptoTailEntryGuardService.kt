package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import com.wrbug.polymarketbot.repository.CryptoTailStrategyTriggerRepository
import com.wrbug.polymarketbot.service.accounts.AccountService
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal

@Service
class CryptoTailEntryGuardService(
    private val accountService: AccountService,
    private val triggerRepository: CryptoTailStrategyTriggerRepository
) {
    private val logger = LoggerFactory.getLogger(CryptoTailEntryGuardService::class.java)

    data class EntryBalanceSnapshot(
        val rawAvailable: BigDecimal,
        val pendingReserved: BigDecimal,
        val spendable: BigDecimal
    )

    fun loadEntryBalanceSnapshot(accountId: Long): EntryBalanceSnapshot {
        val rawAvailable = accountService.getAccountBalance(accountId)
            .getOrNull()
            ?.availableBalance
            ?.toSafeBigDecimal()
            ?: BigDecimal.ZERO
        val pendingReserved = try {
            triggerRepository.sumPendingEntryAmountByAccountId(accountId) ?: BigDecimal.ZERO
        } catch (e: Exception) {
            logger.warn("读取 crypto-tail pending 入场预留失败: accountId=$accountId, ${e.message}")
            BigDecimal.ZERO
        }
        val spendable = rawAvailable.subtract(pendingReserved).max(BigDecimal.ZERO)
        return EntryBalanceSnapshot(rawAvailable, pendingReserved, spendable)
    }

    fun insufficientBalanceReason(amountUsdc: BigDecimal, balance: EntryBalanceSnapshot): String {
        return "可用余额不足: need=${amountUsdc.toPlainString()} spendable=${balance.spendable.toPlainString()} rawAvailable=${balance.rawAvailable.toPlainString()} pendingReserved=${balance.pendingReserved.toPlainString()}"
    }

    fun hasDuplicateMarketPosition(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        outcomeIndex: Int
    ): Boolean {
        if (strategy.allowDuplicateMarketPosition) return false
        val count = try {
            triggerRepository.countOpenMarketPositionByAccountMarketPeriodOutcome(
                strategy.accountId,
                strategy.marketSlugPrefix,
                periodStartUnix,
                outcomeIndex
            )
        } catch (e: Exception) {
            logger.warn("检查重复 market position 失败: accountId=${strategy.accountId}, market=${strategy.marketSlugPrefix}, period=$periodStartUnix, outcome=$outcomeIndex, ${e.message}")
            return true
        }
        return count > 0
    }
}
