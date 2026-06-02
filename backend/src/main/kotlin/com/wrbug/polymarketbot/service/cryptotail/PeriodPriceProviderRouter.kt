package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.service.system.SystemConfigService
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service
import java.math.BigDecimal

/**
 * 价源路由：按系统配置 `crypto-tail.price_source` 在 RTDS（默认，免凭证）与 Chainlink（自建直连）之间转发。
 *
 * 作为 @Primary 的 [PeriodPriceProvider]，供消费者按接口注入；内部按**具体类型**持有两个实现，避免 Bean 歧义。
 * 不做跨源自动回退——单次决策只用一个价源，防止混源污染 pWin/校准口径（保守、一致性优先）。
 */
@Service
@Primary
class PeriodPriceProviderRouter(
    private val rtdsProvider: RtdsPeriodPriceProvider,
    private val chainlinkProvider: ChainlinkPeriodPriceProvider,
    private val systemConfigService: SystemConfigService
) : PeriodPriceProvider {

    // 价源配置短 TTL 缓存：active() 每个 WS tick 多次调用，避免每次查库；3s 内复用，配置变更秒级生效
    @Volatile
    private var cachedSource: String = SystemConfigService.PRICE_SOURCE_RTDS
    @Volatile
    private var cachedAt: Long = 0
    private val cacheTtlMs = 3_000L

    private fun activeSource(): String {
        val now = System.currentTimeMillis()
        if (now - cachedAt > cacheTtlMs) {
            cachedSource = systemConfigService.getCryptoTailPriceSource()
            cachedAt = now
        }
        return cachedSource
    }

    private fun active(): PeriodPriceProvider =
        if (activeSource() == SystemConfigService.PRICE_SOURCE_CHAINLINK) {
            chainlinkProvider
        } else {
            rtdsProvider
        }

    override fun isAvailable(marketSlugPrefix: String): Boolean =
        active().isAvailable(marketSlugPrefix)

    override fun getCurrentOpenClose(marketSlugPrefix: String, intervalSeconds: Int, periodStartUnix: Long): Pair<BigDecimal, BigDecimal>? =
        active().getCurrentOpenClose(marketSlugPrefix, intervalSeconds, periodStartUnix)

    override fun getFinalOpenClose(marketSlugPrefix: String, intervalSeconds: Int, periodStartUnix: Long): Pair<BigDecimal, BigDecimal>? =
        active().getFinalOpenClose(marketSlugPrefix, intervalSeconds, periodStartUnix)

    override fun getCurrentPriceAgeMs(marketSlugPrefix: String): Long? =
        active().getCurrentPriceAgeMs(marketSlugPrefix)

    override fun getRecentOhlc1m(marketSlugPrefix: String, minutes: Int, nowSeconds: Long): List<PeriodPriceProvider.Ohlc1m> =
        active().getRecentOhlc1m(marketSlugPrefix, minutes, nowSeconds)

    override fun getSigmaPerSqrtS(
        marketSlugPrefix: String,
        intervalSeconds: Int,
        periodStartUnix: Long,
        outcomeIndex: Int,
        sigmaScale: BigDecimal,
        sigmaMethod: String,
        ewmaLambda: BigDecimal
    ): BigDecimal? =
        active().getSigmaPerSqrtS(marketSlugPrefix, intervalSeconds, periodStartUnix, outcomeIndex, sigmaScale, sigmaMethod, ewmaLambda)
}
