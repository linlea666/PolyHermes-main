package com.wrbug.polymarketbot.service.chainlink

import com.wrbug.polymarketbot.service.system.SystemConfigService
import com.wrbug.polymarketbot.service.cryptotail.CryptoTailCoinResolver
import com.wrbug.polymarketbot.service.cryptotail.PeriodPriceProvider
import com.wrbug.polymarketbot.util.createClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Chainlink Data Streams 价源服务（crypto-tail 障碍模式专用）。
 *
 * 根因：Polymarket 的 BTC/ETH/SOL/XRP up/down 5m/15m 市场以 Chainlink <COIN>/USD Data Stream 结算，
 * 而原模型用币安价计算 gap/pWin，价源不一致会让 pWin 严重失真。本服务把价源对齐到结算源。
 *
 * 设计：纯 REST + HMAC-SHA256 鉴权 + v3 报文手动 ABI 解码 + TTL 缓存（当前价 1.5s，历史报文不可变长缓存）。
 * 失败安全：任何失败（缺凭证/网络/解码异常）一律返回 null，调用方据此跳过下单——绝不在错价上交易，也绝不回退币安。
 */
@Service
class ChainlinkDataStreamsService(
    private val systemConfigService: SystemConfigService,
    @Value("\${chainlink.ds.api-key:}") private val envApiKey: String,
    @Value("\${chainlink.ds.api-secret:}") private val envApiSecret: String,
    @Value("\${chainlink.ds.rest-base:}") private val envRestBase: String
) {

    private val logger = LoggerFactory.getLogger(ChainlinkDataStreamsService::class.java)

    private val client by lazy { createClient().build() }

    /** mainnet REST 默认基址，可被 SystemConfig / env 覆盖 */
    private val defaultRestBase = "https://api.dataengine.chain.link"

    /** 当前价 TTL 缓存：feedId -> (price, fetchedAtMs) */
    private val latestCache = ConcurrentHashMap<String, Pair<BigDecimal, Long>>()
    private val latestTtlMs = 1500L

    /** 历史报文（按时间戳，不可变）缓存：feedId:tsSeconds -> price */
    private val timestampCache = ConcurrentHashMap<String, BigDecimal>()
    private val timestampCacheMax = 4000

    // ---------------- 凭证 / feedID ----------------

    private fun apiKey(): String? = systemConfigService.getChainlinkApiKey()?.takeIf { it.isNotBlank() }
        ?: envApiKey.takeIf { it.isNotBlank() }

    private fun apiSecret(): String? = systemConfigService.getChainlinkApiSecret()?.takeIf { it.isNotBlank() }
        ?: envApiSecret.takeIf { it.isNotBlank() }

    private fun restBase(): String = (systemConfigService.getChainlinkRestBase()?.takeIf { it.isNotBlank() }
        ?: envRestBase.takeIf { it.isNotBlank() }
        ?: defaultRestBase).trimEnd('/')

    /** 从市场 slug（btc-updown / btc-updown-5m / btc-updown-15m）解析 base 并取 feedID */
    private fun feedIdForSlug(marketSlugPrefix: String): String? {
        val coin = CryptoTailCoinResolver.coinOfSlug(marketSlugPrefix) ?: return null
        return systemConfigService.getChainlinkFeedId("$coin-updown")
            ?: systemConfigService.getChainlinkFeedId(coin)
    }

    /** 是否已配置（api key/secret 同时存在） */
    fun isConfigured(): Boolean = apiKey() != null && apiSecret() != null

    /** 指定市场是否可用（凭证 + 该币种 feedID 均已配置） */
    fun isConfiguredFor(marketSlugPrefix: String): Boolean = isConfigured() && feedIdForSlug(marketSlugPrefix) != null

    fun currentPriceAgeMs(marketSlugPrefix: String): Long? {
        val feedId = feedIdForSlug(marketSlugPrefix) ?: return null
        val cached = latestCache[feedId] ?: return null
        return (System.currentTimeMillis() - cached.second).coerceAtLeast(0L)
    }

    fun readiness(marketSlugPrefix: String): PeriodPriceProvider.PriceReadiness {
        val coin = CryptoTailCoinResolver.coinOfSlug(marketSlugPrefix)
            ?: return PeriodPriceProvider.PriceReadiness("CHAINLINK", null, false, "UNSUPPORTED_SLUG")
        if (!isConfigured()) return PeriodPriceProvider.PriceReadiness("CHAINLINK", coin, false, "FEED_NOT_CONFIGURED")
        val feedId = feedIdForSlug(marketSlugPrefix)
            ?: return PeriodPriceProvider.PriceReadiness("CHAINLINK", coin, false, "FEED_NOT_CONFIGURED")
        val age = latestCache[feedId]?.let { (System.currentTimeMillis() - it.second).coerceAtLeast(0L) }
        return if (age != null && age <= latestTtlMs) {
            PeriodPriceProvider.PriceReadiness("CHAINLINK", coin, true, "OK", age)
        } else {
            PeriodPriceProvider.PriceReadiness("CHAINLINK", coin, true, "OK", age)
        }
    }

    // ---------------- 对外取价 ----------------

    /** 当前最新价（benchmark），TTL 缓存。缺凭证/失败返回 null */
    fun getCurrentPrice(marketSlugPrefix: String): BigDecimal? {
        val feedId = feedIdForSlug(marketSlugPrefix) ?: return null
        val now = System.currentTimeMillis()
        latestCache[feedId]?.let { (price, ts) -> if (now - ts <= latestTtlMs) return price }
        val path = "/api/v1/reports/latest?feedID=$feedId"
        val price = fetchAndDecode(path) ?: return null
        latestCache[feedId] = price to now
        return price
    }

    /** 指定 Unix 秒时间戳处的价（用于窗口期初/期末），历史不可变长缓存。失败返回 null */
    fun getPriceAtTimestamp(marketSlugPrefix: String, tsSeconds: Long): BigDecimal? {
        val feedId = feedIdForSlug(marketSlugPrefix) ?: return null
        val cacheKey = "$feedId:$tsSeconds"
        timestampCache[cacheKey]?.let { return it }
        val path = "/api/v1/reports?feedID=$feedId&timestamp=$tsSeconds"
        val price = fetchAndDecode(path) ?: return null
        if (timestampCache.size > timestampCacheMax) timestampCache.clear()
        timestampCache[cacheKey] = price
        return price
    }

    /** 健康检查：返回 (已配置, 连通正常, 详情) */
    fun healthCheck(): Triple<Boolean, Boolean, String> {
        if (!isConfigured()) return Triple(false, false, "未配置 API Key/Secret")
        // 优先用已配置 feedID 探活（任取一个）
        val probeSlug = listOf("btc-updown", "eth-updown", "sol-updown", "xrp-updown")
            .firstOrNull { systemConfigService.getChainlinkFeedId(it) != null }
            ?: return Triple(true, false, "已配置凭证但未配置任何 feedID")
        val price = getCurrentPrice(probeSlug)
        return if (price != null && price > BigDecimal.ZERO) {
            Triple(true, true, "正常 ${probeSlug} 价=${price.toPlainString()}")
        } else {
            Triple(true, false, "取价失败（凭证/feedID/网络）")
        }
    }

    // ---------------- 内部：HTTP + HMAC + 解码 ----------------

    /** 发起带 HMAC 鉴权的 GET，解析 report.fullReport 并解码 benchmark 价。任何异常返回 null。 */
    private fun fetchAndDecode(fullPath: String): BigDecimal? {
        val key = apiKey() ?: return null
        val secret = apiSecret() ?: return null
        return try {
            val ts = System.currentTimeMillis()
            val bodyHash = sha256Hex("")
            val stringToSign = "GET $fullPath $bodyHash $key $ts"
            val signature = hmacSha256Hex(stringToSign, secret)
            val request = Request.Builder()
                .url(restBase() + fullPath)
                .get()
                .header("Authorization", key)
                .header("X-Authorization-Timestamp", ts.toString())
                .header("X-Authorization-Signature-SHA256", signature)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    logger.warn("Chainlink Data Streams 请求失败: code=${response.code} path=$fullPath")
                    return null
                }
                val text = response.body?.string() ?: return null
                val fullReport = extractFullReport(text) ?: return null
                decodeBenchmarkPrice(fullReport)
            }
        } catch (e: Exception) {
            logger.warn("Chainlink Data Streams 取价异常 path=$fullPath: ${e.message}")
            null
        }
    }

    /** 从响应 JSON 提取 fullReport（兼容 {report:{fullReport}} 与 {reports:[{fullReport}]}） */
    private fun extractFullReport(text: String): String? {
        return try {
            val json = com.google.gson.JsonParser.parseString(text).asJsonObject
            val report = json.getAsJsonObject("report")
                ?: json.getAsJsonArray("reports")?.firstOrNull()?.asJsonObject
                ?: return null
            report.get("fullReport")?.asString
        } catch (e: Exception) {
            logger.warn("Chainlink 响应解析失败: ${e.message}")
            null
        }
    }

    /**
     * 解码 v3 报文 fullReport 的 benchmark 价（18 位小数）。
     *
     * fullReport = abi.encode(bytes32[3] reportContext, bytes reportData, bytes32[] rawRs, bytes32[] rawSs, bytes32 rawVs)
     * head 第 3 个字（字节 96..128）= reportData 的偏移；reportData 内容从 offset+32 起，
     * 其第 6 个字（int192，字节 offset+32+6*32 = offset+224）即 benchmarkPrice。
     * 已用 Chainlink 官方样本校验。任何越界/异常返回 null。
     */
    fun decodeBenchmarkPrice(fullReportHex: String): BigDecimal? {
        return try {
            val clean = fullReportHex.removePrefix("0x").removePrefix("0X")
            if (clean.length < 256 || clean.length % 2 != 0) return null
            val bytes = hexToBytes(clean) ?: return null
            if (bytes.size < 128) return null
            // reportData 偏移（无符号），取 head word3 的低 4 字节足够
            val offset = BigInteger(1, bytes.copyOfRange(96, 128)).toInt()
            val priceStart = offset + 224
            if (priceStart < 0 || priceStart + 32 > bytes.size) return null
            val raw = BigInteger(bytes.copyOfRange(priceStart, priceStart + 32)) // 有符号 int192（32字节符号扩展）
            if (raw <= BigInteger.ZERO) return null
            BigDecimal(raw).divide(BigDecimal.TEN.pow(18), 8, RoundingMode.HALF_UP)
        } catch (e: Exception) {
            logger.warn("Chainlink v3 报文解码失败: ${e.message}")
            null
        }
    }

    private fun hexToBytes(hex: String): ByteArray? {
        return try {
            val len = hex.length
            val out = ByteArray(len / 2)
            var i = 0
            while (i < len) {
                out[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
                i += 2
            }
            out
        } catch (e: Exception) {
            null
        }
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun hmacSha256Hex(message: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val hash = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
