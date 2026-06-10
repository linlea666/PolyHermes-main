package com.wrbug.polymarketbot.service.okx

/**
 * OKX 交易对（instId）映射与市场 slug 解析（tickers / candle 等多服务共用，避免重复维护映射）。
 *
 * 独立新写理由（复用决策）：与 [com.wrbug.polymarketbot.service.binance.BinanceSymbolResolver] 语义相同，
 * 但 OKX instId 形如 `BTC-USDT`（带连字符、报价币不同），与币安 `BTCUSDC` 不同；若强行共用一份映射会把
 * 两个交易所的符号口径耦合到一起，反而更脆。故独立维护一份 OKX 专属映射，slug 解析逻辑各自内聚。
 *
 * 报价币选择：OKX 现货 BTC-USDT 流动性显著高于 BTC-USDC，tickers 推送更密更快；现货领先早警只比较
 * "当前价相对周期开盘价的位移方向"（同源自洽，基差自抵消），与报价币绝对水平无关，故统一用 USDT 报价。
 */
object OkxSymbolResolver {

    /** 市场 slug 前缀（如 btc-updown）-> OKX instId */
    private val marketToInstId = mapOf(
        "btc-updown" to "BTC-USDT",
        "eth-updown" to "ETH-USDT",
        "sol-updown" to "SOL-USDT",
        "xrp-updown" to "XRP-USDT"
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

    /** 从市场 base 前缀（如 btc-updown）获取 OKX instId */
    fun getInstId(basePrefix: String): String? = marketToInstId[basePrefix]

    /** 从完整市场 slug（如 btc-updown-5m）直接解析 instId，失败返回 null */
    fun instIdOfMarketSlug(full: String): String? {
        val (base, _) = parseMarketSlug(full.lowercase()) ?: return null
        return getInstId(base)
    }

    /** OKX K 线频道名：5m -> candle5m，15m -> candle15m */
    fun candleChannel(interval: String): String? = when (interval) {
        "5m" -> "candle5m"
        "15m" -> "candle15m"
        else -> null
    }
}
