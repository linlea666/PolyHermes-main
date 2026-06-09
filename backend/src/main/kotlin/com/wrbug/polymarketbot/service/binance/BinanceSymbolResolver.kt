package com.wrbug.polymarketbot.service.binance

/**
 * 币安交易对映射与市场 slug 解析（kline / bookTicker 等多服务共用，避免重复维护一份映射）。
 *
 * 提取复用：原先 [BinanceKlineService] 内私有维护 marketToSymbol/parseMarketSlug/getSymbol，
 * 新增的实时 tick 服务([BinanceSpotTickerService]) 也需要同一份映射；为避免两处分叉，提取为共享对象。
 */
object BinanceSymbolResolver {

    /** 市场 slug 前缀（如 btc-updown）-> Binance 交易对 */
    private val marketToSymbol = mapOf(
        "btc-updown" to "BTCUSDC",
        "eth-updown" to "ETHUSDC",
        "sol-updown" to "SOLUSDC",
        "xrp-updown" to "XRPUSDC"
    )

    /** 解析完整市场 slug（如 btc-updown-5m）为 (basePrefix, interval)，不支持则返回 null */
    fun parseMarketSlug(full: String): Pair<String, String>? {
        val lower = full.lowercase()
        return when {
            lower.endsWith("-5m") -> Pair(lower.removeSuffix("-5m"), "5m")
            lower.endsWith("-15m") -> Pair(lower.removeSuffix("-15m"), "15m")
            else -> null
        }
    }

    /** 从市场 base 前缀（如 btc-updown）获取 Binance 交易对 */
    fun getSymbol(basePrefix: String): String? = marketToSymbol[basePrefix]

    /** 从完整市场 slug（如 btc-updown-5m）直接解析交易对，失败返回 null */
    fun symbolOfMarketSlug(full: String): String? {
        val (base, _) = parseMarketSlug(full.lowercase()) ?: return null
        return getSymbol(base)
    }
}
