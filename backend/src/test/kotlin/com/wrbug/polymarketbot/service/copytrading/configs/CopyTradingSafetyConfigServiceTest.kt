package com.wrbug.polymarketbot.service.copytrading.configs

import com.wrbug.polymarketbot.dto.ApplyConservativeConfigRequest
import com.wrbug.polymarketbot.entity.CopyTrading
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CopyTradingSafetyConfigServiceTest {

    @Test
    fun `requires explicit confirmation before applying conservative config`() {
        val error = assertThrows(IllegalStateException::class.java) {
            CopyTradingSafetyConfigService.applyConservativeConfig(
                current = riskyCopyTrading(),
                request = ApplyConservativeConfigRequest(copyTradingId = 1, confirm = false)
            )
        }

        assertEquals("应用保守配置需要显式确认", error.message)
    }

    @Test
    fun `applies only whitelisted risk fields`() {
        val updated = CopyTradingSafetyConfigService.applyConservativeConfig(
            current = riskyCopyTrading(),
            request = ApplyConservativeConfigRequest(
                copyTradingId = 1,
                confirm = true,
                maxDailyOrders = 20,
                maxDailyLoss = "10",
                minPrice = "0.10",
                maxPrice = "0.80",
                maxPositionValue = "10",
                minOrderDepth = "100",
                maxSpread = "0.03",
                priceTolerance = "3"
            )
        )

        assertEquals(20, updated.maxDailyOrders)
        assertEquals("10", updated.maxDailyLoss.strip())
        assertEquals(0, BigDecimal("0.10").compareTo(updated.minPrice))
        assertEquals(0, BigDecimal("0.80").compareTo(updated.maxPrice))
        assertEquals("10", updated.maxPositionValue?.strip())
        assertEquals("100", updated.minOrderDepth?.strip())
        assertEquals("0.03", updated.maxSpread?.strip())
        assertEquals("3", updated.priceTolerance.strip())
        assertEquals(true, updated.enabled)
        assertEquals(1L, updated.leaderId)
        assertEquals("FIXED", updated.copyMode)
    }

    @Test
    fun `rejects unsafe values before saving`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            CopyTradingSafetyConfigService.applyConservativeConfig(
                current = riskyCopyTrading(),
                request = ApplyConservativeConfigRequest(
                    copyTradingId = 1,
                    confirm = true,
                    maxDailyOrders = 0
                )
            )
        }

        assertEquals("maxDailyOrders 必须在 1 到 20 之间", error.message)
    }

    @Test
    fun `rejects values outside conservative guardrails`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            CopyTradingSafetyConfigService.applyConservativeConfig(
                current = riskyCopyTrading(),
                request = ApplyConservativeConfigRequest(
                    copyTradingId = 1,
                    confirm = true,
                    maxDailyLoss = "100"
                )
            )
        }

        assertEquals("maxDailyLoss 必须大于 0 且不超过 10", error.message)
    }

    @Test
    fun `rejects invalid decimal strings before saving`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            CopyTradingSafetyConfigService.applyConservativeConfig(
                current = riskyCopyTrading(),
                request = ApplyConservativeConfigRequest(
                    copyTradingId = 1,
                    confirm = true,
                    maxSpread = "not-a-number"
                )
            )
        }

        assertEquals("maxSpread 必须是有效数字", error.message)
    }

    private fun riskyCopyTrading() = CopyTrading(
        id = 1,
        accountId = 1,
        leaderId = 1,
        enabled = true,
        copyMode = "FIXED",
        fixedAmount = BigDecimal.ONE,
        maxDailyLoss = BigDecimal("10000"),
        maxDailyOrders = 100,
        priceTolerance = BigDecimal("5")
    )

    private fun BigDecimal.strip(): String = stripTrailingZeros().toPlainString()
}
