package com.wrbug.polymarketbot.service.copytrading.configs

import com.wrbug.polymarketbot.dto.ApplyConservativeConfigRequest
import com.wrbug.polymarketbot.entity.CopyTrading
import java.math.BigDecimal

object CopyTradingSafetyConfigService {
    private val MAX_DAILY_LOSS_LIMIT = BigDecimal("10")
    private val MAX_POSITION_VALUE_LIMIT = BigDecimal("10")
    private val MIN_ORDER_DEPTH_LIMIT = BigDecimal("100")
    private val MAX_SPREAD_LIMIT = BigDecimal("0.03")
    private val PRICE_TOLERANCE_LIMIT = BigDecimal("3")

    fun applyConservativeConfig(
        current: CopyTrading,
        request: ApplyConservativeConfigRequest
    ): CopyTrading {
        if (!request.confirm) {
            throw IllegalStateException("应用保守配置需要显式确认")
        }

        val maxDailyOrders = request.maxDailyOrders?.also {
            if (it !in 1..20) {
                throw IllegalArgumentException("maxDailyOrders 必须在 1 到 20 之间")
            }
        } ?: current.maxDailyOrders

        val maxDailyLoss = request.maxDailyLoss?.asPositiveDecimalAtMost("maxDailyLoss", MAX_DAILY_LOSS_LIMIT)
            ?: current.maxDailyLoss
        val minPrice = request.minPrice?.asPrice("minPrice") ?: current.minPrice
        val maxPrice = request.maxPrice?.asPrice("maxPrice") ?: current.maxPrice
        if (minPrice != null && maxPrice != null && minPrice > maxPrice) {
            throw IllegalArgumentException("minPrice 不能大于 maxPrice")
        }

        return current.copy(
            maxDailyOrders = maxDailyOrders,
            maxDailyLoss = maxDailyLoss,
            minPrice = minPrice,
            maxPrice = maxPrice,
            maxPositionValue = request.maxPositionValue?.asPositiveDecimalAtMost("maxPositionValue", MAX_POSITION_VALUE_LIMIT)
                ?: current.maxPositionValue,
            minOrderDepth = request.minOrderDepth?.asPositiveDecimalAtLeast("minOrderDepth", MIN_ORDER_DEPTH_LIMIT)
                ?: current.minOrderDepth,
            maxSpread = request.maxSpread?.asPositiveDecimalAtMost("maxSpread", MAX_SPREAD_LIMIT)
                ?: current.maxSpread,
            priceTolerance = request.priceTolerance?.asPositiveDecimalAtMost("priceTolerance", PRICE_TOLERANCE_LIMIT)
                ?: current.priceTolerance,
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun String.asPositiveDecimal(field: String): BigDecimal {
        val value = trim().toBigDecimalOrNull()
            ?: throw IllegalArgumentException("$field 必须是有效数字")
        if (value <= BigDecimal.ZERO) {
            throw IllegalArgumentException("$field 必须大于 0")
        }
        return value
    }

    private fun String.asPositiveDecimalAtMost(field: String, max: BigDecimal): BigDecimal {
        val value = asPositiveDecimal(field)
        if (value > max) {
            throw IllegalArgumentException("$field 必须大于 0 且不超过 ${max.strip()}")
        }
        return value
    }

    private fun String.asPositiveDecimalAtLeast(field: String, min: BigDecimal): BigDecimal {
        val value = asPositiveDecimal(field)
        if (value < min) {
            throw IllegalArgumentException("$field 必须不小于 ${min.strip()}")
        }
        return value
    }

    private fun String.asPrice(field: String): BigDecimal {
        val value = asPositiveDecimal(field)
        if (value > BigDecimal.ONE) {
            throw IllegalArgumentException("$field 必须在 0 到 1 之间")
        }
        return value
    }

    private fun BigDecimal.strip(): String = stripTrailingZeros().toPlainString()
}
