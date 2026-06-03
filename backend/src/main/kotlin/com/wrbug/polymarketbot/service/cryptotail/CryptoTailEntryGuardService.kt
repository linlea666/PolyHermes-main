package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import com.wrbug.polymarketbot.repository.CryptoTailStrategyTriggerRepository
import com.wrbug.polymarketbot.service.accounts.AccountService
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

@Service
class CryptoTailEntryGuardService(
    private val accountService: AccountService,
    private val triggerRepository: CryptoTailStrategyTriggerRepository
) {
    private val logger = LoggerFactory.getLogger(CryptoTailEntryGuardService::class.java)

    /**
     * 账户级短时内存预留：FAK 成交后 trigger 立即记为 'success'（不是 'pending'），
     * sumPendingEntryAmountByAccountId 查不到它，而链上余额 API 可能滞后几秒。
     * 这期间同账户另一策略读取余额会误以为资金仍可用而再切一份，导致超额下单。
     * 这里在确认成交时登记实际花费的 USDC，带 TTL 自动过期（到时链上余额已反映该笔花费），
     * loadEntryBalanceSnapshot 的 spendable 会额外扣减这部分，杜绝并发超额。
     */
    private data class Reservation(val amount: BigDecimal, val expiresAtMs: Long)

    private val recentFillReservations = ConcurrentHashMap<Long, MutableList<Reservation>>()

    private val reservationTtlMs = 20_000L

    data class EntryBalanceSnapshot(
        val rawAvailable: BigDecimal,
        val pendingReserved: BigDecimal,
        val recentFillReserved: BigDecimal,
        val spendable: BigDecimal
    )

    /** 登记一笔刚成交、尚未反映到链上余额的花费（FAK/手动成交后调用） */
    fun reserveRecentFill(accountId: Long, amountUsdc: BigDecimal) {
        if (amountUsdc <= BigDecimal.ZERO) return
        val now = System.currentTimeMillis()
        val list = recentFillReservations.computeIfAbsent(accountId) { java.util.Collections.synchronizedList(mutableListOf()) }
        synchronized(list) {
            list.removeIf { it.expiresAtMs <= now }
            list.add(Reservation(amountUsdc, now + reservationTtlMs))
        }
    }

    private fun sumRecentFillReserved(accountId: Long): BigDecimal {
        val list = recentFillReservations[accountId] ?: return BigDecimal.ZERO
        val now = System.currentTimeMillis()
        synchronized(list) {
            list.removeIf { it.expiresAtMs <= now }
            return list.fold(BigDecimal.ZERO) { acc, r -> acc.add(r.amount) }
        }
    }

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
        val recentFillReserved = sumRecentFillReserved(accountId)
        val spendable = rawAvailable.subtract(pendingReserved).subtract(recentFillReserved).max(BigDecimal.ZERO)
        return EntryBalanceSnapshot(rawAvailable, pendingReserved, recentFillReserved, spendable)
    }

    fun insufficientBalanceReason(amountUsdc: BigDecimal, balance: EntryBalanceSnapshot): String {
        return "可用余额不足: need=${amountUsdc.toPlainString()} spendable=${balance.spendable.toPlainString()} rawAvailable=${balance.rawAvailable.toPlainString()} pendingReserved=${balance.pendingReserved.toPlainString()} recentFillReserved=${balance.recentFillReserved.toPlainString()}"
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
