package com.wrbug.polymarketbot.service.cryptotail

import org.springframework.stereotype.Service

/**
 * 现货领先价源路由：按策略配置的 source（BINANCE/OKX/CONSENSUS）选择 [SpotLeadPriceProvider]。
 *
 * 不做"跨源静默回退"：CONSENSUS 的多源融合由 [CryptoTailSpotLeadService] 显式处理（两源均新鲜且方向一致才判危险，
 * 单源新鲜时退化为该源），路由本身只负责按 source 取实现与按源分发订阅。
 */
@Service
class SpotLeadPriceProviderRouter(
    private val binanceProvider: BinanceSpotLeadProvider,
    private val okxProvider: OkxSpotLeadProvider
) {

    /** 按 source 取单一价源实现；CONSENSUS/未知返回 null（由调用方处理多源融合） */
    fun provider(source: String): SpotLeadPriceProvider? = when (source.uppercase()) {
        "BINANCE" -> binanceProvider
        "OKX" -> okxProvider
        else -> null
    }

    val binance: SpotLeadPriceProvider get() = binanceProvider
    val okx: SpotLeadPriceProvider get() = okxProvider

    /** 全部价源（供健康检查聚合） */
    fun all(): List<SpotLeadPriceProvider> = listOf(binanceProvider, okxProvider)

    /** 聚合全部价源连接状态（供 API 健康检查） */
    fun getConnectionStatuses(): Map<String, Boolean> =
        all().fold(LinkedHashMap()) { acc, p -> acc.putAll(p.getConnectionStatuses()); acc }

    /** 在全部价源上注册同一个 tick 监听器（供混合推送触发器） */
    fun setTickListener(listener: (exchange: String, symbol: String, mid: java.math.BigDecimal, tsMs: Long) -> Unit) {
        all().forEach { it.setTickListener(listener) }
    }

    /**
     * 按"源 -> 该源需要现货领先的市场集合"更新订阅；未出现的源以空集合关闭其连接（零开销）。
     * CONSENSUS 策略的市场应同时计入 BINANCE 与 OKX 两个集合（由调用方展开）。
     */
    fun updateSubscriptions(marketsBySource: Map<String, Set<String>>) {
        binanceProvider.updateSubscriptions(marketsBySource["BINANCE"] ?: emptySet())
        okxProvider.updateSubscriptions(marketsBySource["OKX"] ?: emptySet())
    }
}
