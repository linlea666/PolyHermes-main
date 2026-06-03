package com.wrbug.polymarketbot.service.cryptotail

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Cache
import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.toJson
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import kotlin.math.sqrt

@Service
class CryptoTailWickSignalService(
    private val periodPriceProvider: PeriodPriceProvider,
    private val retrofitFactory: RetrofitFactory
) {
    private val logger = LoggerFactory.getLogger(CryptoTailWickSignalService::class.java)
    private val volumeSpikeCache: Cache<String, Boolean> = Caffeine.newBuilder()
        .maximumSize(256)
        .expireAfterWrite(Duration.ofSeconds(45))
        .build()

    data class Signal(
        val available: Boolean,
        val outcomeIndex: Int,
        val upperWickRatio: BigDecimal = BigDecimal.ZERO,
        val lowerWickRatio: BigDecimal = BigDecimal.ZERO,
        val bodyRatio: BigDecimal = BigDecimal.ZERO,
        val closeVsMa: BigDecimal = BigDecimal.ZERO,
        val closePosition: BigDecimal = BigDecimal.ZERO,
        val candleRange: BigDecimal = BigDecimal.ZERO,
        val tickCount: Int = 0,
        val rangeSigmaRatio: BigDecimal = BigDecimal.ZERO,
        val rejectionSide: String = "NONE",
        val qualityReason: String = "DISABLED",
        val volumeSpike: Boolean = false,
        val continuationScore: Int = 0,
        val reversalScore: Int = 0,
        val rawOhlc: List<Map<String, String>> = emptyList()
    ) {
        fun toPayload(): Map<String, Any> = mapOf(
            "wickAvailable" to available,
            "upperWickRatio" to upperWickRatio.toPlainString(),
            "lowerWickRatio" to lowerWickRatio.toPlainString(),
            "bodyRatio" to bodyRatio.toPlainString(),
            "closeVsMa" to closeVsMa.toPlainString(),
            "closePosition" to closePosition.toPlainString(),
            "candleRange" to candleRange.toPlainString(),
            "tickCount" to tickCount,
            "rangeSigmaRatio" to rangeSigmaRatio.toPlainString(),
            "rejectionSide" to rejectionSide,
            "qualityReason" to qualityReason,
            "volumeSpike" to volumeSpike,
            "continuationScore" to continuationScore,
            "reversalScore" to reversalScore,
            "rawOhlc" to rawOhlc
        )

        fun toJson(): String = toPayload().toJson()
    }

    fun evaluate(strategy: CryptoTailStrategy, outcomeIndex: Int, nowSeconds: Long = System.currentTimeMillis() / 1000): Signal {
        if (!strategy.enableWickFilter || strategy.wickFilterMode.uppercase() == "OFF") {
            return Signal(available = false, outcomeIndex = outcomeIndex)
        }
        val lookback = strategy.wickLookbackMinutes.coerceIn(1, 10)
        val maWindow = strategy.wickMaWindow.coerceIn(1, 20)
        val candles = periodPriceProvider.getRecentOhlc1m(strategy.marketSlugPrefix, maxOf(lookback, maWindow), nowSeconds)
        if (candles.isEmpty()) return Signal(available = false, outcomeIndex = outcomeIndex)
        val recent = candles.takeLast(lookback)
        val latest = recent.last()
        val ma = candles.takeLast(maWindow).map { it.close }
            .fold(BigDecimal.ZERO) { a, b -> a.add(b) }
            .divide(BigDecimal(candles.takeLast(maWindow).size), 8, RoundingMode.HALF_UP)
        val closeVsMa = latest.close.subtract(ma)
        val sigma1m = periodPriceProvider.getSigmaPerSqrtS(
            strategy.marketSlugPrefix,
            60,
            latest.minuteStartUnix,
            outcomeIndex,
            strategy.sigmaScale,
            strategy.sigmaMethod,
            strategy.ewmaLambda
        )?.multiply(BigDecimal(sqrt(60.0)))
        val volumeSpike = if (strategy.wickUseBinanceVolume) {
            fetchBinanceVolumeSpike(strategy.marketSlugPrefix, strategy.wickVolumeSpikeRatio, nowSeconds)
        } else {
            false
        }
        val features = recent.mapNotNull { candleFeatures(it) }
        if (features.isEmpty()) return Signal(available = false, outcomeIndex = outcomeIndex)
        val latestFeature = candleFeatures(latest) ?: features.last()
        val riskFeature = features.maxByOrNull {
            when (outcomeIndex) {
                0 -> scoreRisk(it.upperWickRatio, it.bodyRatio, closeVsMa < BigDecimal.ZERO, isSignalQualityOk(strategy, it, sigma1m), volumeSpike)
                else -> scoreRisk(it.lowerWickRatio, it.bodyRatio, closeVsMa > BigDecimal.ZERO, isSignalQualityOk(strategy, it, sigma1m), volumeSpike)
            }
        } ?: latestFeature
        val riskQualityOk = isSignalQualityOk(strategy, riskFeature, sigma1m)
        val latestQualityOk = isSignalQualityOk(strategy, latestFeature, sigma1m)
        val riskRangeSigmaRatio = rangeSigmaRatio(riskFeature.range, sigma1m)
        val qualityReason = qualityReason(strategy, riskFeature, sigma1m)

        val upRisk = riskFeature.upperWickRatio >= strategy.wickRejectionRatio &&
                riskFeature.bodyRatio >= strategy.wickMinBodyRatio &&
                riskFeature.closePosition <= strategy.wickClosePositionUpMax &&
                latest.close < ma &&
                riskQualityOk
        val downRisk = riskFeature.lowerWickRatio >= strategy.wickRejectionRatio &&
                riskFeature.bodyRatio >= strategy.wickMinBodyRatio &&
                riskFeature.closePosition >= strategy.wickClosePositionDownMin &&
                latest.close > ma &&
                riskQualityOk
        val rejectionSide = when {
            upRisk -> "UP_REJECTED"
            downRisk -> "DOWN_REJECTED"
            !riskQualityOk -> qualityReason
            riskFeature.upperWickRatio >= strategy.wickRejectionRatio -> "UPPER_WICK"
            riskFeature.lowerWickRatio >= strategy.wickRejectionRatio -> "LOWER_WICK"
            else -> "NONE"
        }
        val reversalScore = when (outcomeIndex) {
            0 -> scoreRisk(riskFeature.upperWickRatio, riskFeature.bodyRatio, closeVsMa < BigDecimal.ZERO, upRisk, volumeSpike)
            else -> scoreRisk(riskFeature.lowerWickRatio, riskFeature.bodyRatio, closeVsMa > BigDecimal.ZERO, downRisk, volumeSpike)
        }
        val continuationScore = if (latestQualityOk) {
            when (outcomeIndex) {
                0 -> scoreContinuation(latestFeature.upperWickRatio, latestFeature.lowerWickRatio, latestFeature.bodyRatio, closeVsMa >= BigDecimal.ZERO)
                else -> scoreContinuation(latestFeature.lowerWickRatio, latestFeature.upperWickRatio, latestFeature.bodyRatio, closeVsMa <= BigDecimal.ZERO)
            }
        } else {
            0
        }

        return Signal(
            available = true,
            outcomeIndex = outcomeIndex,
            upperWickRatio = riskFeature.upperWickRatio,
            lowerWickRatio = riskFeature.lowerWickRatio,
            bodyRatio = riskFeature.bodyRatio,
            closeVsMa = closeVsMa,
            closePosition = riskFeature.closePosition,
            candleRange = riskFeature.range,
            tickCount = riskFeature.tickCount,
            rangeSigmaRatio = riskRangeSigmaRatio,
            rejectionSide = rejectionSide,
            qualityReason = qualityReason,
            volumeSpike = volumeSpike,
            continuationScore = continuationScore,
            reversalScore = reversalScore,
            rawOhlc = recent.map {
                mapOf(
                    "minuteStartUnix" to it.minuteStartUnix.toString(),
                    "open" to it.open.toPlainString(),
                    "high" to it.high.toPlainString(),
                    "low" to it.low.toPlainString(),
                    "close" to it.close.toPlainString(),
                    "tickCount" to it.tickCount.toString(),
                    "range" to it.high.subtract(it.low).abs().toPlainString()
                )
            }
        )
    }

    private fun fetchBinanceVolumeSpike(marketSlugPrefix: String, spikeRatio: BigDecimal, nowSeconds: Long): Boolean {
        val symbol = binanceSymbol(marketSlugPrefix) ?: return false
        val minute = nowSeconds - (nowSeconds % 60)
        val key = "$symbol-$minute-${spikeRatio.toPlainString()}"
        volumeSpikeCache.getIfPresent(key)?.let { return it }
        val spike = try {
            val response = retrofitFactory.createBinanceApi()
                .getKlines(symbol = symbol, interval = "1m", limit = 6)
                .execute()
            val rows = response.body().orEmpty()
            val volumes = rows.mapNotNull { it.getOrNull(5)?.toString()?.toSafeBigDecimal() }
                .filter { it > BigDecimal.ZERO }
            if (volumes.size < 3) {
                false
            } else {
                val latest = volumes.last()
                val baseline = volumes.dropLast(1).fold(BigDecimal.ZERO) { a, b -> a.add(b) }
                    .divide(BigDecimal(volumes.size - 1), 8, RoundingMode.HALF_UP)
                baseline > BigDecimal.ZERO && latest >= baseline.multiply(spikeRatio)
            }
        } catch (e: Exception) {
            logger.warn("影线成交量辅助查询 Binance 失败: ${e.message}")
            false
        }
        volumeSpikeCache.put(key, spike)
        return spike
    }

    private fun binanceSymbol(marketSlugPrefix: String): String? {
        val base = marketSlugPrefix.lowercase().removeSuffix("-15m").removeSuffix("-5m")
        return when (base) {
            "btc-updown" -> "BTCUSDC"
            "eth-updown" -> "ETHUSDC"
            "sol-updown" -> "SOLUSDC"
            "xrp-updown" -> "XRPUSDC"
            else -> null
        }
    }

    private data class CandleFeature(
        val upperWickRatio: BigDecimal,
        val lowerWickRatio: BigDecimal,
        val bodyRatio: BigDecimal,
        val closePosition: BigDecimal,
        val range: BigDecimal,
        val tickCount: Int
    )

    private fun candleFeatures(candle: PeriodPriceProvider.Ohlc1m): CandleFeature? {
        val range = candle.high.subtract(candle.low).abs()
        if (range <= BigDecimal.ZERO) return null
        val body = candle.close.subtract(candle.open).abs()
        val upper = candle.high.subtract(candle.open.max(candle.close)).max(BigDecimal.ZERO)
        val lower = candle.open.min(candle.close).subtract(candle.low).max(BigDecimal.ZERO)
        return CandleFeature(
            upperWickRatio = upper.divide(range, 8, RoundingMode.HALF_UP),
            lowerWickRatio = lower.divide(range, 8, RoundingMode.HALF_UP),
            bodyRatio = body.divide(range, 8, RoundingMode.HALF_UP),
            closePosition = candle.close.subtract(candle.low).divide(range, 8, RoundingMode.HALF_UP).max(BigDecimal.ZERO).min(BigDecimal.ONE),
            range = range,
            tickCount = candle.tickCount
        )
    }

    private fun isSignalQualityOk(strategy: CryptoTailStrategy, feature: CandleFeature, sigma1m: BigDecimal?): Boolean {
        if (feature.tickCount < strategy.wickMinTicksPerCandle) return false
        return rangeSigmaRatio(feature.range, sigma1m) >= strategy.wickMinRangeSigmaRatio
    }

    private fun qualityReason(strategy: CryptoTailStrategy, feature: CandleFeature, sigma1m: BigDecimal?): String {
        if (feature.tickCount < strategy.wickMinTicksPerCandle) return "WICK_LOW_DENSITY"
        if (rangeSigmaRatio(feature.range, sigma1m) < strategy.wickMinRangeSigmaRatio) return "WICK_RANGE_TOO_SMALL"
        return "OK"
    }

    private fun rangeSigmaRatio(range: BigDecimal, sigma1m: BigDecimal?): BigDecimal {
        if (sigma1m == null || sigma1m <= BigDecimal.ZERO) return BigDecimal.ZERO
        return range.divide(sigma1m, 8, RoundingMode.HALF_UP)
    }

    private fun scoreRisk(wickRatio: BigDecimal, bodyRatio: BigDecimal, maBroken: Boolean, qualityOk: Boolean, volumeSpike: Boolean): Int {
        if (!qualityOk) return 0
        var score = wickRatio.multiply(BigDecimal(80)).setScale(0, RoundingMode.HALF_UP).toInt()
        if (bodyRatio >= BigDecimal("0.20")) score += 10
        if (maBroken) score += 20
        if (volumeSpike) score += 10
        return score.coerceIn(0, 100)
    }

    private fun scoreContinuation(adverseWickRatio: BigDecimal, favorableWickRatio: BigDecimal, bodyRatio: BigDecimal, maAligned: Boolean): Int {
        var score = 35
        if (maAligned) score += 30
        if (adverseWickRatio < BigDecimal("0.35")) score += 20
        if (favorableWickRatio > BigDecimal("0.20")) score += 5
        if (bodyRatio >= BigDecimal("0.20")) score += 10
        return score.coerceIn(0, 100)
    }
}
