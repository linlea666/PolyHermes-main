package com.wrbug.polymarketbot.controller.copytrading.configs

import com.google.gson.Gson
import com.wrbug.polymarketbot.dto.ApplyConservativeConfigRequest
import com.wrbug.polymarketbot.dto.CopyTradingDto
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.CopyTradingTemplateRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.service.copytrading.configs.CopyTradingService
import com.wrbug.polymarketbot.service.copytrading.configs.FilteredOrderService
import com.wrbug.polymarketbot.service.copytrading.monitor.CopyTradingMonitorService
import com.wrbug.polymarketbot.util.JsonUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.context.support.StaticMessageSource

class CopyTradingControllerTest {

    @Test
    fun `apply conservative config returns success response`() {
        val service = StubCopyTradingService(Result.success(sampleCopyTradingDto()))
        val controller = controller(service)

        val response = controller.applyConservativeConfig(
            ApplyConservativeConfigRequest(
                copyTradingId = 7,
                confirm = true,
                maxDailyOrders = 20
            )
        )

        assertEquals(0, response.body!!.code)
        assertEquals(7, response.body!!.data!!.id)
        assertEquals(1, service.callCount)
        assertEquals(true, service.lastRequest!!.confirm)
        assertEquals(20, service.lastRequest!!.maxDailyOrders)
    }

    @Test
    fun `apply conservative config rejects invalid id before service call`() {
        val service = StubCopyTradingService(Result.success(sampleCopyTradingDto()))
        val controller = controller(service)

        val response = controller.applyConservativeConfig(
            ApplyConservativeConfigRequest(copyTradingId = 0, confirm = true)
        )

        assertEquals(ErrorCode.PARAM_COPY_TRADING_ID_INVALID.code, response.body!!.code)
        assertEquals(0, service.callCount)
    }

    @Test
    fun `apply conservative config maps missing copy trading to not found`() {
        val service = StubCopyTradingService(Result.failure(IllegalArgumentException("跟单配置不存在")))
        val controller = controller(service)

        val response = controller.applyConservativeConfig(
            ApplyConservativeConfigRequest(copyTradingId = 7, confirm = true)
        )

        assertEquals(ErrorCode.COPY_TRADING_NOT_FOUND.code, response.body!!.code)
        assertEquals("跟单配置不存在", response.body!!.msg)
    }

    @Test
    fun `apply conservative config maps missing confirmation to business error`() {
        val service = StubCopyTradingService(Result.failure(IllegalStateException("应用保守配置需要显式确认")))
        val controller = controller(service)

        val response = controller.applyConservativeConfig(
            ApplyConservativeConfigRequest(copyTradingId = 7, confirm = false)
        )

        assertEquals(ErrorCode.BUSINESS_ERROR.code, response.body!!.code)
        assertEquals("应用保守配置需要显式确认", response.body!!.msg)
    }

    @Test
    fun `apply conservative config maps validation failure to parameter error`() {
        val service = StubCopyTradingService(Result.failure(IllegalArgumentException("maxDailyOrders 必须在 1 到 20 之间")))
        val controller = controller(service)

        val response = controller.applyConservativeConfig(
            ApplyConservativeConfigRequest(copyTradingId = 7, confirm = true, maxDailyOrders = 0)
        )

        assertEquals(ErrorCode.PARAM_ERROR.code, response.body!!.code)
        assertEquals("maxDailyOrders 必须在 1 到 20 之间", response.body!!.msg)
    }

    @Test
    fun `apply conservative config maps unexpected service failure to update server error`() {
        val service = StubCopyTradingService(Result.failure(RuntimeException("外部数据不可用")))
        val controller = controller(service)

        val response = controller.applyConservativeConfig(
            ApplyConservativeConfigRequest(copyTradingId = 7, confirm = true)
        )

        assertEquals(ErrorCode.SERVER_COPY_TRADING_UPDATE_FAILED.code, response.body!!.code)
        assertEquals("外部数据不可用", response.body!!.msg)
    }

    private fun controller(copyTradingService: CopyTradingService) = CopyTradingController(
        copyTradingService = copyTradingService,
        filteredOrderService = mock(),
        messageSource = StaticMessageSource()
    )

    private class StubCopyTradingService(
        private val nextResult: Result<CopyTradingDto>
    ) : CopyTradingService(
        copyTradingRepository = mock(),
        accountRepository = mock(),
        templateRepository = mock(),
        leaderRepository = mock(),
        monitorService = mock(),
        jsonUtils = mock(),
        gson = Gson()
    ) {
        var callCount = 0
        var lastRequest: ApplyConservativeConfigRequest? = null

        override fun applyConservativeConfig(request: ApplyConservativeConfigRequest): Result<CopyTradingDto> {
            callCount++
            lastRequest = request
            return nextResult
        }
    }

    companion object {
        private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)

        private fun sampleCopyTradingDto() = CopyTradingDto(
            id = 7,
            accountId = 1,
            accountName = "账户 A",
            walletAddress = "0xaccount",
            leaderId = 2,
            leaderName = "Leader A",
            leaderAddress = "0xleader",
            enabled = true,
            copyMode = "FIXED",
            copyRatio = "1",
            fixedAmount = "10",
            maxOrderSize = "10",
            minOrderSize = "1",
            maxDailyLoss = "10",
            maxDailyOrders = 20,
            priceTolerance = "3",
            delaySeconds = 0,
            pollIntervalSeconds = 5,
            useWebSocket = true,
            websocketReconnectInterval = 5000,
            websocketMaxRetries = 10,
            supportSell = true,
            minOrderDepth = "100",
            maxSpread = "0.03",
            minPrice = "0.10",
            maxPrice = "0.80",
            maxPositionValue = "10",
            createdAt = 1,
            updatedAt = 2
        )
    }
}
