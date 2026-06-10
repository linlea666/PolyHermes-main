package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.service.binance.BinanceKlineService
import com.wrbug.polymarketbot.service.binance.BinanceSpotTickerService
import com.wrbug.polymarketbot.service.okx.OkxKlineService
import com.wrbug.polymarketbot.service.okx.OkxSpotTickerService
import org.springframework.stereotype.Service
import java.math.BigDecimal

/**
 * 现货领先早警价源插件抽象（v2）：把"某交易所同周期 (开盘价, 当前价, 当前价 age)"做成可插拔数据源，
 * 供 [CryptoTailSpotLeadService] 按策略配置的 source（BINANCE/OKX/CONSENSUS）路由消费。
 *
 * 设计哲学（与 [PeriodPriceProvider] 一致）：
 *  - 每个交易所一个实现，open 与 current 必须**同源自洽**（binGap=current-open 基差自抵消）；
 *  - 纯 WS 推送、零鉴权；数据缺失返回 null（fail-safe，绝不返回假数据/过期值由上层 age 门禁拦截）；
 *  - 仅提供"领先早警"信号原料，绝不进入 pWin/结算口径。
 */
interface SpotLeadPriceProvider {

    /** 价源标识：BINANCE / OKX */
    val source: String

    /**
     * 取该市场同周期现货快照：开盘价(strike) + 当前价(实时 tick 与 K 线 running close 取 age 更小者) + age + 来源。
     * 无周期开盘价（无 K 线）时返回 null。
     */
    fun getSpotSnapshot(marketSlugPrefix: String, intervalSeconds: Int, periodStartUnix: Long): SpotSnapshot?

    /** 按需更新订阅（当前价 + K 线），空集合时关闭连接（零开销） */
    fun updateSubscriptions(marketPrefixes: Set<String>)

    /** 各连接状态（供 API 健康检查） */
    fun getConnectionStatuses(): Map<String, Boolean>

    /**
     * 注册实时 tick 监听器（供混合推送触发器）：每条实时现价回调 (exchange, symbol, mid, tsMs)。
     * 仅监听实时 tick 源（K 线不回调），用于尾盘/危险区把 tick 推送成退出重评估。
     */
    fun setTickListener(listener: (exchange: String, symbol: String, mid: BigDecimal, tsMs: Long) -> Unit)

    /**
     * 现货快照。
     * @param open 周期开盘价（strike）
     * @param current 当前价（tick 或 kline close 中 age 更小者）
     * @param currentAgeMs 当前价 age（ms）
     * @param currentSourceKind 当前价来源："TICK"=实时逐笔，"KLINE"=K 线 running close
     */
    data class SpotSnapshot(
        val open: BigDecimal,
        val current: BigDecimal,
        val currentAgeMs: Long,
        val currentSourceKind: String
    )
}

/** 现货价源公共选价逻辑：实时 tick 与 K 线 running close 取 age 更小者作为 current。 */
private fun chooseFreshestSnapshot(
    open: BigDecimal,
    klineClose: BigDecimal,
    klineAgeMs: Long?,
    tickMid: BigDecimal?,
    tickAgeMs: Long?
): SpotLeadPriceProvider.SpotSnapshot {
    val tickCandidate = if (tickMid != null && tickAgeMs != null) Triple("TICK", tickMid, tickAgeMs) else null
    val klineCandidate = if (klineAgeMs != null) Triple("KLINE", klineClose, klineAgeMs) else null
    val chosen = listOfNotNull(tickCandidate, klineCandidate).minByOrNull { it.third }
    // 两者皆无 age（理论上 kline 存在则 klineAge 存在）时退化为 kline close、age 给一个大值由上层判不新鲜。
    val sourceKind = chosen?.first ?: "KLINE"
    val current = chosen?.second ?: klineClose
    val ageMs = chosen?.third ?: Long.MAX_VALUE
    return SpotLeadPriceProvider.SpotSnapshot(open, current, ageMs, sourceKind)
}

/** 币安价源：复用现有 [BinanceKlineService](open/close) + [BinanceSpotTickerService](实时 mid)。 */
@Service
class BinanceSpotLeadProvider(
    private val klineService: BinanceKlineService,
    private val tickerService: BinanceSpotTickerService
) : SpotLeadPriceProvider {

    override val source: String = "BINANCE"

    override fun getSpotSnapshot(marketSlugPrefix: String, intervalSeconds: Int, periodStartUnix: Long): SpotLeadPriceProvider.SpotSnapshot? {
        val oc = klineService.getCurrentOpenClose(marketSlugPrefix, intervalSeconds, periodStartUnix) ?: return null
        val now = System.currentTimeMillis()
        val klineAge = klineService.getLastUpdateMs(marketSlugPrefix, intervalSeconds, periodStartUnix)?.let { now - it }
        val tickMid = tickerService.getLatestMid(marketSlugPrefix)
        val tickAge = tickerService.getLastUpdateMs(marketSlugPrefix)?.let { now - it }
        return chooseFreshestSnapshot(oc.first, oc.second, klineAge, tickMid, tickAge)
    }

    /**
     * 仅订阅币安实时 tick；币安 K 线由 [CryptoTailOrderbookWsService] 全局订阅（覆盖所有策略，含 legacy/barrier）统一管理，
     * 此处不重复订阅，避免用更小的子集覆盖全局更大集合（保守，零回归）。
     */
    override fun updateSubscriptions(marketPrefixes: Set<String>) {
        tickerService.updateSubscriptions(marketPrefixes)
    }

    override fun setTickListener(listener: (exchange: String, symbol: String, mid: BigDecimal, tsMs: Long) -> Unit) {
        tickerService.setTickListener { symbol, mid, tsMs -> listener(source, symbol, mid, tsMs) }
    }

    override fun getConnectionStatuses(): Map<String, Boolean> =
        tickerService.getConnectionStatuses().mapKeys { "BINANCE_TICK_${it.key}" }
}

/** OKX 价源：复用 [OkxKlineService](candle open/close) + [OkxSpotTickerService](tickers 实时 mid)。 */
@Service
class OkxSpotLeadProvider(
    private val klineService: OkxKlineService,
    private val tickerService: OkxSpotTickerService
) : SpotLeadPriceProvider {

    override val source: String = "OKX"

    override fun getSpotSnapshot(marketSlugPrefix: String, intervalSeconds: Int, periodStartUnix: Long): SpotLeadPriceProvider.SpotSnapshot? {
        val oc = klineService.getCurrentOpenClose(marketSlugPrefix, intervalSeconds, periodStartUnix) ?: return null
        val now = System.currentTimeMillis()
        val klineAge = klineService.getLastUpdateMs(marketSlugPrefix, intervalSeconds, periodStartUnix)?.let { now - it }
        val tickMid = tickerService.getLatestMid(marketSlugPrefix)
        val tickAge = tickerService.getLastUpdateMs(marketSlugPrefix)?.let { now - it }
        return chooseFreshestSnapshot(oc.first, oc.second, klineAge, tickMid, tickAge)
    }

    override fun updateSubscriptions(marketPrefixes: Set<String>) {
        klineService.updateSubscriptions(marketPrefixes)
        tickerService.updateSubscriptions(marketPrefixes)
    }

    override fun setTickListener(listener: (exchange: String, symbol: String, mid: BigDecimal, tsMs: Long) -> Unit) {
        tickerService.setTickListener { instId, mid, tsMs -> listener(source, instId, mid, tsMs) }
    }

    override fun getConnectionStatuses(): Map<String, Boolean> =
        tickerService.getConnectionStatuses().mapKeys { "OKX_TICK_${it.key}" } +
            klineService.getConnectionStatuses().mapKeys { "OKX_KLINE_${it.key}" }
}
