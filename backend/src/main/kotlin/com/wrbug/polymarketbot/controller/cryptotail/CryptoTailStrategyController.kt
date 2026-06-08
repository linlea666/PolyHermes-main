package com.wrbug.polymarketbot.controller.cryptotail

import com.wrbug.polymarketbot.dto.ApiResponse
import com.wrbug.polymarketbot.dto.CryptoTailStrategyCreateRequest
import com.wrbug.polymarketbot.dto.CryptoTailStrategyDeleteRequest
import com.wrbug.polymarketbot.dto.CryptoTailStrategyDto
import com.wrbug.polymarketbot.dto.CryptoTailStrategyListRequest
import com.wrbug.polymarketbot.dto.CryptoTailStrategyListResponse
import com.wrbug.polymarketbot.dto.CryptoTailStrategyExitListRequest
import com.wrbug.polymarketbot.dto.CryptoTailStrategyExitListResponse
import com.wrbug.polymarketbot.dto.CryptoTailStrategyTriggerListRequest
import com.wrbug.polymarketbot.dto.CryptoTailStrategyTriggerListResponse
import com.wrbug.polymarketbot.dto.CryptoTailStrategyUpdateRequest
import com.wrbug.polymarketbot.dto.CryptoTailMarketOptionDto
import com.wrbug.polymarketbot.dto.CryptoTailAutoMinSpreadResponse
import com.wrbug.polymarketbot.dto.CryptoTailMonitorInitRequest
import com.wrbug.polymarketbot.dto.CryptoTailMonitorInitResponse
import com.wrbug.polymarketbot.dto.CryptoTailManualOrderRequest
import com.wrbug.polymarketbot.dto.CryptoTailManualOrderResponse
import com.wrbug.polymarketbot.dto.CryptoTailPnlCurveRequest
import com.wrbug.polymarketbot.dto.CryptoTailPnlCurveResponse
import com.wrbug.polymarketbot.dto.CryptoTailStatsRequest
import com.wrbug.polymarketbot.dto.CryptoTailStatsResponse
import com.wrbug.polymarketbot.dto.CryptoTailCalibrationRequest
import com.wrbug.polymarketbot.dto.CryptoTailCalibrationResponse
import com.wrbug.polymarketbot.dto.CryptoTailRecommendSigmaScaleRequest
import com.wrbug.polymarketbot.dto.CryptoTailRecommendSigmaScaleResponse
import com.wrbug.polymarketbot.dto.CryptoTailDecisionLogListRequest
import com.wrbug.polymarketbot.dto.CryptoTailDecisionLogListResponse
import com.wrbug.polymarketbot.dto.CryptoTailDecisionLogExportRequest
import com.wrbug.polymarketbot.dto.CryptoTailDecisionLogExportResponse
import com.wrbug.polymarketbot.dto.CryptoTailDecisionLogBatchDeleteRequest
import com.wrbug.polymarketbot.dto.CryptoTailDecisionLogPurgeRequest
import com.wrbug.polymarketbot.dto.CryptoTailDecisionLogDeleteResponse
import com.wrbug.polymarketbot.dto.CryptoTailPeriodSummaryListRequest
import com.wrbug.polymarketbot.dto.CryptoTailPeriodSummaryListResponse
import com.wrbug.polymarketbot.dto.CryptoTailTradeSnapshotListRequest
import com.wrbug.polymarketbot.dto.CryptoTailTradeSnapshotListResponse
import com.wrbug.polymarketbot.dto.CryptoTailTradeSnapshotExportRequest
import com.wrbug.polymarketbot.dto.CryptoTailTradeSnapshotExportResponse
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.service.binance.BinanceKlineAutoSpreadService
import com.wrbug.polymarketbot.service.cryptotail.CryptoTailStrategyService
import com.wrbug.polymarketbot.service.cryptotail.CryptoTailMonitorService
import com.wrbug.polymarketbot.service.cryptotail.CryptoTailStrategyExecutionService
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import kotlinx.coroutines.runBlocking

@RestController
@RequestMapping("/api/crypto-tail-strategy")
class CryptoTailStrategyController(
    private val cryptoTailStrategyService: CryptoTailStrategyService,
    private val cryptoTailMonitorService: CryptoTailMonitorService,
    private val cryptoTailStrategyExecutionService: CryptoTailStrategyExecutionService,
    private val binanceKlineAutoSpreadService: BinanceKlineAutoSpreadService,
    private val messageSource: MessageSource
) {

    private val logger = LoggerFactory.getLogger(CryptoTailStrategyController::class.java)

    @PostMapping("/list")
    fun list(@RequestBody request: CryptoTailStrategyListRequest): ResponseEntity<ApiResponse<CryptoTailStrategyListResponse>> {
        return try {
            val result = cryptoTailStrategyService.list(request)
            result.fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                onFailure = { e ->
                    logger.error("查询加密价差策略列表失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_LIST_FETCH_FAILED, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("查询加密价差策略列表异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_LIST_FETCH_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/create")
    fun create(@RequestBody request: CryptoTailStrategyCreateRequest): ResponseEntity<ApiResponse<CryptoTailStrategyDto>> {
        return try {
            val result = cryptoTailStrategyService.create(request)
            result.fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                onFailure = { e ->
                    logger.error("创建加密价差策略失败: ${e.message}", e)
                    val code = when (e.message) {
                        ErrorCode.CRYPTO_TAIL_STRATEGY_WINDOW_INVALID.messageKey -> ErrorCode.CRYPTO_TAIL_STRATEGY_WINDOW_INVALID
                        ErrorCode.CRYPTO_TAIL_STRATEGY_WINDOW_EXCEED.messageKey -> ErrorCode.CRYPTO_TAIL_STRATEGY_WINDOW_EXCEED
                        ErrorCode.CRYPTO_TAIL_STRATEGY_INTERVAL_INVALID.messageKey -> ErrorCode.CRYPTO_TAIL_STRATEGY_INTERVAL_INVALID
                        ErrorCode.CRYPTO_TAIL_STRATEGY_AMOUNT_MODE_INVALID.messageKey -> ErrorCode.CRYPTO_TAIL_STRATEGY_AMOUNT_MODE_INVALID
                        ErrorCode.CRYPTO_TAIL_STRATEGY_BARRIER_PARAM_INVALID.messageKey -> ErrorCode.CRYPTO_TAIL_STRATEGY_BARRIER_PARAM_INVALID
                        else -> ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_CREATE_FAILED
                    }
                    ResponseEntity.ok(ApiResponse.error(code, messageSource = messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("创建加密价差策略异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_CREATE_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/update")
    fun update(@RequestBody request: CryptoTailStrategyUpdateRequest): ResponseEntity<ApiResponse<CryptoTailStrategyDto>> {
        return try {
            if (request.strategyId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.CRYPTO_TAIL_STRATEGY_NOT_FOUND, messageSource = messageSource))
            }
            val result = cryptoTailStrategyService.update(request)
            result.fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                onFailure = { e ->
                    logger.error("更新加密价差策略失败: ${e.message}", e)
                    val code = when (e.message) {
                        ErrorCode.CRYPTO_TAIL_STRATEGY_NOT_FOUND.messageKey -> ErrorCode.CRYPTO_TAIL_STRATEGY_NOT_FOUND
                        ErrorCode.CRYPTO_TAIL_STRATEGY_WINDOW_INVALID.messageKey -> ErrorCode.CRYPTO_TAIL_STRATEGY_WINDOW_INVALID
                        ErrorCode.CRYPTO_TAIL_STRATEGY_WINDOW_EXCEED.messageKey -> ErrorCode.CRYPTO_TAIL_STRATEGY_WINDOW_EXCEED
                        ErrorCode.CRYPTO_TAIL_STRATEGY_AMOUNT_MODE_INVALID.messageKey -> ErrorCode.CRYPTO_TAIL_STRATEGY_AMOUNT_MODE_INVALID
                        ErrorCode.CRYPTO_TAIL_STRATEGY_BARRIER_PARAM_INVALID.messageKey -> ErrorCode.CRYPTO_TAIL_STRATEGY_BARRIER_PARAM_INVALID
                        else -> ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_UPDATE_FAILED
                    }
                    ResponseEntity.ok(ApiResponse.error(code, messageSource = messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("更新加密价差策略异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_UPDATE_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/delete")
    fun delete(@RequestBody request: CryptoTailStrategyDeleteRequest): ResponseEntity<ApiResponse<Unit>> {
        return try {
            val strategyId = request.strategyId
            if (strategyId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.CRYPTO_TAIL_STRATEGY_NOT_FOUND, messageSource = messageSource))
            }
            val result = cryptoTailStrategyService.delete(strategyId)
            result.fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(Unit)) },
                onFailure = { e ->
                    logger.error("删除加密价差策略失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_DELETE_FAILED, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("删除加密价差策略异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_DELETE_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/pnl-curve")
    fun getPnlCurve(@RequestBody request: CryptoTailPnlCurveRequest): ResponseEntity<ApiResponse<CryptoTailPnlCurveResponse>> {
        return try {
            if (request.strategyId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.CRYPTO_TAIL_STRATEGY_NOT_FOUND, messageSource = messageSource))
            }
            val result = cryptoTailStrategyService.getPnlCurve(request)
            result.fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                onFailure = { e ->
                    logger.error("查询收益曲线失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_TRIGGERS_FETCH_FAILED, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("查询收益曲线异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_TRIGGERS_FETCH_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/stats-overview")
    fun getStatsOverview(@RequestBody request: CryptoTailStatsRequest): ResponseEntity<ApiResponse<CryptoTailStatsResponse>> {
        return try {
            val result = cryptoTailStrategyService.getStatsOverview(request)
            result.fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                onFailure = { e ->
                    logger.error("查询统计概览失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_TRIGGERS_FETCH_FAILED, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("查询统计概览异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_TRIGGERS_FETCH_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/calibration")
    fun getCalibration(@RequestBody request: CryptoTailCalibrationRequest): ResponseEntity<ApiResponse<CryptoTailCalibrationResponse>> {
        return try {
            if (request.strategyId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.CRYPTO_TAIL_STRATEGY_NOT_FOUND, messageSource = messageSource))
            }
            val result = cryptoTailStrategyService.getCalibration(request)
            result.fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                onFailure = { e ->
                    logger.error("查询校准统计失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_TRIGGERS_FETCH_FAILED, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("查询校准统计异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_TRIGGERS_FETCH_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/recommend-sigma-scale")
    fun recommendSigmaScale(@RequestBody request: CryptoTailRecommendSigmaScaleRequest): ResponseEntity<ApiResponse<CryptoTailRecommendSigmaScaleResponse>> {
        return try {
            if (request.strategyId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.CRYPTO_TAIL_STRATEGY_NOT_FOUND, messageSource = messageSource))
            }
            val result = cryptoTailStrategyService.recommendSigmaScale(request)
            result.fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                onFailure = { e ->
                    logger.error("推荐σ校准系数失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_TRIGGERS_FETCH_FAILED, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("推荐σ校准系数异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_TRIGGERS_FETCH_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/triggers")
    fun getTriggerRecords(@RequestBody request: CryptoTailStrategyTriggerListRequest): ResponseEntity<ApiResponse<CryptoTailStrategyTriggerListResponse>> {
        return try {
            if (request.strategyId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.CRYPTO_TAIL_STRATEGY_NOT_FOUND, messageSource = messageSource))
            }
            val result = cryptoTailStrategyService.getTriggerRecords(request)
            result.fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                onFailure = { e ->
                    logger.error("查询触发记录失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_TRIGGERS_FETCH_FAILED, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("查询触发记录异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_TRIGGERS_FETCH_FAILED, e.message, messageSource))
        }
    }

    /**
     * 阶梯模式退出明细：按 triggerId 查询某次入场后的多档退出记录
     */
    @PostMapping("/exits")
    fun getStrategyExits(@RequestBody request: CryptoTailStrategyExitListRequest): ResponseEntity<ApiResponse<CryptoTailStrategyExitListResponse>> {
        return try {
            if (request.triggerId <= 0L) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, messageSource = messageSource))
            }
            val result = cryptoTailStrategyService.getStrategyExits(request)
            result.fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                onFailure = { e ->
                    logger.error("查询阶梯退出明细失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_TRIGGERS_FETCH_FAILED, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("查询阶梯退出明细异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_TRIGGERS_FETCH_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/decision-log/list")
    fun getDecisionLog(@RequestBody request: CryptoTailDecisionLogListRequest): ResponseEntity<ApiResponse<CryptoTailDecisionLogListResponse>> {
        return try {
            if (request.strategyId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.CRYPTO_TAIL_STRATEGY_NOT_FOUND, messageSource = messageSource))
            }
            val result = cryptoTailStrategyService.getDecisionLog(request)
            result.fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                onFailure = { e ->
                    logger.error("查询决策日志失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_TRIGGERS_FETCH_FAILED, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("查询决策日志异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_TRIGGERS_FETCH_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/decision-log/list-all")
    fun getDecisionLogAll(@RequestBody request: CryptoTailDecisionLogListRequest): ResponseEntity<ApiResponse<CryptoTailDecisionLogListResponse>> {
        return try {
            val result = cryptoTailStrategyService.getDecisionLogAll(request)
            result.fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                onFailure = { e ->
                    logger.error("查询全局决策日志失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_TRIGGERS_FETCH_FAILED, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("查询全局决策日志异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_TRIGGERS_FETCH_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/period-summary/list")
    fun getPeriodSummaries(@RequestBody request: CryptoTailPeriodSummaryListRequest): ResponseEntity<ApiResponse<CryptoTailPeriodSummaryListResponse>> {
        return try {
            val result = cryptoTailStrategyService.getPeriodSummaries(request)
            result.fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                onFailure = { e ->
                    logger.error("查询周期汇总失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_TRIGGERS_FETCH_FAILED, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("查询周期汇总异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_TRIGGERS_FETCH_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/decision-log/export")
    fun exportDecisionLog(@RequestBody request: CryptoTailDecisionLogExportRequest): ResponseEntity<ApiResponse<CryptoTailDecisionLogExportResponse>> {
        return try {
            val result = cryptoTailStrategyService.exportDecisionLog(request)
            result.fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                onFailure = { e ->
                    logger.error("导出决策日志失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_TRIGGERS_FETCH_FAILED, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("导出决策日志异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_TRIGGERS_FETCH_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/decision-log/batch-delete")
    fun batchDeleteDecisionLog(@RequestBody request: CryptoTailDecisionLogBatchDeleteRequest): ResponseEntity<ApiResponse<CryptoTailDecisionLogDeleteResponse>> {
        return try {
            if (request.ids.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_EMPTY, messageSource = messageSource))
            }
            val result = cryptoTailStrategyService.deleteDecisionLogs(request)
            result.fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                onFailure = { e ->
                    logger.error("批量删除决策日志失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_DECISION_LOG_DELETE_FAILED, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("批量删除决策日志异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_DECISION_LOG_DELETE_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/decision-log/purge")
    fun purgeDecisionLog(@RequestBody request: CryptoTailDecisionLogPurgeRequest): ResponseEntity<ApiResponse<CryptoTailDecisionLogDeleteResponse>> {
        return try {
            if (request.beforeDate <= 0L) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_INVALID, messageSource = messageSource))
            }
            val result = cryptoTailStrategyService.purgeDecisionLogs(request)
            result.fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                onFailure = { e ->
                    logger.error("清理决策日志失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_DECISION_LOG_DELETE_FAILED, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("清理决策日志异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_DECISION_LOG_DELETE_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/decision/snapshot/list")
    fun getTradeSnapshots(@RequestBody request: CryptoTailTradeSnapshotListRequest): ResponseEntity<ApiResponse<CryptoTailTradeSnapshotListResponse>> {
        return try {
            if (request.strategyId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.CRYPTO_TAIL_STRATEGY_NOT_FOUND, messageSource = messageSource))
            }
            val result = cryptoTailStrategyService.getTradeSnapshots(request)
            result.fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                onFailure = { e ->
                    logger.error("查询单笔成交快照失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_TRIGGERS_FETCH_FAILED, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("查询单笔成交快照异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_TRIGGERS_FETCH_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/decision/snapshot/export")
    fun exportTradeSnapshots(@RequestBody request: CryptoTailTradeSnapshotExportRequest): ResponseEntity<ApiResponse<CryptoTailTradeSnapshotExportResponse>> {
        return try {
            if (request.strategyId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.CRYPTO_TAIL_STRATEGY_NOT_FOUND, messageSource = messageSource))
            }
            val result = cryptoTailStrategyService.exportTradeSnapshots(request)
            result.fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                onFailure = { e ->
                    logger.error("导出单笔成交快照失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_TRIGGERS_FETCH_FAILED, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("导出单笔成交快照异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_TRIGGERS_FETCH_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/market-options")
    fun getMarketOptions(): ResponseEntity<ApiResponse<List<CryptoTailMarketOptionDto>>> {
        return try {
            val options = listOf(
                CryptoTailMarketOptionDto(slug = "btc-updown-5m", title = "Bitcoin Up or Down - 5 minute", intervalSeconds = 300, periodStartUnix = 0L, endDate = null),
                CryptoTailMarketOptionDto(slug = "btc-updown-15m", title = "Bitcoin Up or Down - 15 minute", intervalSeconds = 900, periodStartUnix = 0L, endDate = null),
                CryptoTailMarketOptionDto(slug = "eth-updown-5m", title = "Ethereum Up or Down - 5 minute", intervalSeconds = 300, periodStartUnix = 0L, endDate = null),
                CryptoTailMarketOptionDto(slug = "eth-updown-15m", title = "Ethereum Up or Down - 15 minute", intervalSeconds = 900, periodStartUnix = 0L, endDate = null),
                CryptoTailMarketOptionDto(slug = "sol-updown-5m", title = "Solana Up or Down - 5 minute", intervalSeconds = 300, periodStartUnix = 0L, endDate = null),
                CryptoTailMarketOptionDto(slug = "sol-updown-15m", title = "Solana Up or Down - 15 minute", intervalSeconds = 900, periodStartUnix = 0L, endDate = null),
                CryptoTailMarketOptionDto(slug = "xrp-updown-5m", title = "XRP Up or Down - 5 minute", intervalSeconds = 300, periodStartUnix = 0L, endDate = null),
                CryptoTailMarketOptionDto(slug = "xrp-updown-15m", title = "XRP Up or Down - 15 minute", intervalSeconds = 900, periodStartUnix = 0L, endDate = null)
            )
            ResponseEntity.ok(ApiResponse.success(options))
        } catch (e: Exception) {
            logger.error("获取市场选项异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }

    /**
     * 自动最小价差预览：按「当前周期」计算一次并返回，仅用于前端展示参考。
     * 实际触发时按每个周期在需要时计算，不依赖此接口。
     */
    @PostMapping("/auto-min-spread")
    fun getAutoMinSpread(@RequestBody request: java.util.Map<String, Any>): ResponseEntity<ApiResponse<CryptoTailAutoMinSpreadResponse>> {
        return try {
            val intervalSeconds = (request["intervalSeconds"] as? Number)?.toInt() ?: 300
            if (intervalSeconds != 300 && intervalSeconds != 900) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, messageSource = messageSource))
            }
            val periodStartUnix = (request["periodStartUnix"] as? Number)?.toLong()
                ?: ((System.currentTimeMillis() / 1000 / intervalSeconds) * intervalSeconds)
            // 默认使用 BTC 市场（向后兼容）
            val marketSlugPrefix = (request["marketSlugPrefix"] as? String) ?: "btc-updown"
            val pair = binanceKlineAutoSpreadService.computeAndCache(marketSlugPrefix, intervalSeconds, periodStartUnix)
                ?: return ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, "fetch_failed", messageSource))
            val body = CryptoTailAutoMinSpreadResponse(
                minSpreadUp = pair.first.toPlainString(),
                minSpreadDown = pair.second.toPlainString()
            )
            ResponseEntity.ok(ApiResponse.success(body))
        } catch (e: Exception) {
            logger.error("计算自动最小价差异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }

    /**
     * 初始化加密价差策略监控
     * 返回策略信息、开盘价、tokenIds等初始化数据
     */
    @PostMapping("/monitor/init")
    fun initMonitor(@RequestBody request: CryptoTailMonitorInitRequest): ResponseEntity<ApiResponse<CryptoTailMonitorInitResponse>> {
        return try {
            if (request.strategyId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.CRYPTO_TAIL_STRATEGY_NOT_FOUND, messageSource = messageSource))
            }
            val result = cryptoTailMonitorService.initMonitor(request)
            result.fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                onFailure = { e ->
                    logger.error("初始化加密价差策略监控失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("初始化加密价差策略监控异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }

    /**
     * 手动下单
     * 用户主动触发下单，不检查任何条件，仅检查当前周期是否已下单
     */
    @PostMapping("/manual-order")
    fun manualOrder(@RequestBody request: CryptoTailManualOrderRequest): ResponseEntity<ApiResponse<CryptoTailManualOrderResponse>> {
        return runBlocking {
            try {
                if (request.strategyId <= 0) {
                    return@runBlocking ResponseEntity.ok(
                        ApiResponse.error(ErrorCode.CRYPTO_TAIL_STRATEGY_NOT_FOUND, messageSource = messageSource)
                    )
                }
                val result = cryptoTailStrategyExecutionService.manualOrder(request)
                result.fold(
                    onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                    onFailure = { e ->
                        logger.error("手动下单失败: ${e.message}", e)
                        val code = when (e.message) {
                            "策略不存在" -> ErrorCode.CRYPTO_TAIL_STRATEGY_NOT_FOUND
                            "当前周期已下单" -> ErrorCode.PARAM_ERROR
                            "价格必须在 0~1 之间" -> ErrorCode.PARAM_ERROR
                            "数量不能少于 1" -> ErrorCode.PARAM_ERROR
                            "总金额不能少于 $1" -> ErrorCode.PARAM_ERROR
                            "总金额超过策略配置的投入金额" -> ErrorCode.PARAM_ERROR
                            else -> ErrorCode.SERVER_ERROR
                        }
                        ResponseEntity.ok(ApiResponse.error(code, e.message, messageSource))
                    }
                )
            } catch (e: Exception) {
                logger.error("手动下单异常: ${e.message}", e)
                ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
            }
        }
    }
}
