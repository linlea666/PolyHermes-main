package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import com.wrbug.polymarketbot.repository.CryptoTailStrategyTriggerRepository
import com.wrbug.polymarketbot.service.accounts.AccountService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

@Service
class CryptoTailEntryGuardService(
    private val accountService: AccountService,
    private val triggerRepository: CryptoTailStrategyTriggerRepository,
    @Value("\${crypto-tail.scalp.balance-freshness-bound-ms:10000}") private val balanceFreshnessBoundMs: Long
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

    /**
     * 账户级链上可用余额缓存（rawAvailable）。
     * 根因：进场热路径原本每次同步拉链上余额（getWalletBalance 串行 3 个远程往返，~1.8s），
     * 把"触发→发单"拖到 ~3s，导致 SCALP 用 3s 前的盘口发单、FAK 频繁 kill。
     * 这里把"取链上余额"与"下单"解耦：后台 @Scheduled 周期刷新写入本缓存，进场只读本地缓存（~0 延迟）。
     * 安全性：缓存只替换 rawAvailable，pendingReserved/recentFillReserved 两层预留仍在读取时实时扣减；
     * 缓存最大滞后受 reservationTtlMs(20s) 兜底窗口覆盖，不破坏并发防超额保证。
     */
    private data class CachedRawAvailable(val value: BigDecimal, val refreshedAtMs: Long)

    private val rawAvailableCache = ConcurrentHashMap<Long, CachedRawAvailable>()

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

    /**
     * 后台余额刷新调度器调用：拉取最新可用 USDC 并写入缓存。失败时保留旧缓存（不覆盖、不清空）。
     * @return 刷新后的可用余额；失败返回 null。
     */
    fun refreshRawAvailableCache(accountId: Long): BigDecimal? {
        return fetchAndCacheRawAvailable(accountId)
    }

    private fun fetchAndCacheRawAvailable(accountId: Long): BigDecimal? {
        val result = accountService.getAvailableUsdc(accountId)
        val value = result.getOrNull()
        if (value == null) {
            logger.warn("crypto-tail 刷新可用余额失败: accountId=$accountId, ${result.exceptionOrNull()?.message}")
            return null
        }
        rawAvailableCache[accountId] = CachedRawAvailable(value, System.currentTimeMillis())
        return value
    }

    /**
     * 取账户当前可用余额（rawAvailable）：
     * - 命中缓存且在新鲜度上限内 → 直接用缓存（~0 延迟，热路径无网络）；
     * - 缓存缺失/超过新鲜度上限 → 同步兜底拉一次（不返默认值，保证正确性）；
     * - 同步兜底失败 → 退用旧缓存，但仅当滞后 ≤ reservationTtlMs(防超额兜底窗口) 时；超出则退化为 0（保守，与原失败语义一致）。
     */
    private fun currentRawAvailable(accountId: Long): BigDecimal {
        val now = System.currentTimeMillis()
        val cached = rawAvailableCache[accountId]
        if (cached != null && now - cached.refreshedAtMs <= balanceFreshnessBoundMs) {
            return cached.value
        }
        val fetched = fetchAndCacheRawAvailable(accountId)
        if (fetched != null) return fetched
        if (cached != null && now - cached.refreshedAtMs <= reservationTtlMs) {
            return cached.value
        }
        return BigDecimal.ZERO
    }

    fun loadEntryBalanceSnapshot(accountId: Long): EntryBalanceSnapshot {
        val rawAvailable = currentRawAvailable(accountId)
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
