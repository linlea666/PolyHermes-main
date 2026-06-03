package com.wrbug.polymarketbot.service.cryptotail

object CryptoTailCoinResolver {
    private val aliases = mapOf(
        "btc" to setOf("btc", "bitcoin"),
        "eth" to setOf("eth", "ethereum"),
        "sol" to setOf("sol", "solana"),
        "xrp" to setOf("xrp", "ripple")
    )

    val supportedCoins: Set<String> = aliases.keys

    fun coinOfSlug(marketSlugPrefix: String): String? {
        val normalized = marketSlugPrefix
            .lowercase()
            .replace('_', '-')
            .trim()
            .removeSuffix("-15m")
            .removeSuffix("-5m")
            .removeSuffix("-updown")
            .removeSuffix("-up-or-down")
        val tokens = normalized.split('-', '/', ' ', '_').filter { it.isNotBlank() }
        for ((coin, names) in aliases) {
            if (normalized == coin || tokens.any { it in names }) return coin
        }
        return null
    }
}
