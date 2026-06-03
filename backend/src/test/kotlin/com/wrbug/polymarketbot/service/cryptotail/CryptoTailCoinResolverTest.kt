package com.wrbug.polymarketbot.service.cryptotail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CryptoTailCoinResolverTest {

    @Test
    fun `resolves supported updown slugs and intervals`() {
        assertEquals("btc", CryptoTailCoinResolver.coinOfSlug("btc-updown"))
        assertEquals("btc", CryptoTailCoinResolver.coinOfSlug("btc-updown-5m"))
        assertEquals("eth", CryptoTailCoinResolver.coinOfSlug("eth-updown-15m"))
        assertEquals("sol", CryptoTailCoinResolver.coinOfSlug("sol-up-or-down"))
        assertEquals("xrp", CryptoTailCoinResolver.coinOfSlug("ripple-updown-5m"))
    }

    @Test
    fun `rejects unsupported slugs`() {
        assertNull(CryptoTailCoinResolver.coinOfSlug("doge-updown"))
        assertNull(CryptoTailCoinResolver.coinOfSlug(""))
    }
}
