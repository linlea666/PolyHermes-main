package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.api.GammaEventBySlugResponse
import com.wrbug.polymarketbot.api.GammaEventMarketItem
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * Optional official open resolver for crypto-tail markets.
 *
 * Gamma currently does not expose target/open/initial fields for the observed
 * up/down markets, but this resolver gives those fields priority if Polymarket
 * starts returning them. It never falls back to non-official sources.
 */
@Service
class CryptoTailOfficialOpenResolver(
    private val retrofitFactory: RetrofitFactory
) {
    private val logger = LoggerFactory.getLogger(CryptoTailOfficialOpenResolver::class.java)
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val ttlMs = 60_000L

    private data class CacheEntry(
        val value: BigDecimal?,
        val cachedAtMs: Long
    )

    fun resolve(marketSlugPrefix: String, intervalSeconds: Int, periodStartUnix: Long): BigDecimal? {
        if (periodStartUnix <= 0 || intervalSeconds <= 0) return null
        val key = "$marketSlugPrefix-$intervalSeconds-$periodStartUnix"
        val now = System.currentTimeMillis()
        cache[key]?.let { cached ->
            if (now - cached.cachedAtMs <= ttlMs) return cached.value
        }

        val slug = "$marketSlugPrefix-$periodStartUnix"
        val value = try {
            runBlocking {
                val response = retrofitFactory.createGammaApi().getEventBySlug(slug)
                if (!response.isSuccessful) {
                    null
                } else {
                    response.body()?.officialOpen()
                }
            }
        } catch (e: Exception) {
            logger.warn("查询官方 open 失败 slug=$slug: ${e.message}")
            null
        }
        cache[key] = CacheEntry(value, now)
        return value
    }

    private fun GammaEventBySlugResponse.officialOpen(): BigDecimal? =
        firstValid(targetPrice, openPrice, initialValue, startPrice)
            ?: markets?.asSequence()?.mapNotNull { it.officialOpen() }?.firstOrNull()

    private fun GammaEventMarketItem.officialOpen(): BigDecimal? =
        firstValid(targetPrice, openPrice, initialValue, startPrice)

    private fun firstValid(vararg values: String?): BigDecimal? =
        values.asSequence()
            .mapNotNull { it?.takeIf { s -> s.isNotBlank() }?.toSafeBigDecimal() }
            .firstOrNull { it > BigDecimal.ZERO }
}
