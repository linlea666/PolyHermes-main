package com.wrbug.polymarketbot.service.cryptotail

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import jakarta.annotation.PreDestroy

/**
 * 加密价差策略 maker 挂单生命周期对账服务。
 * 定时驱动 CryptoTailStrategyExecutionService.reconcilePendingMakerOrders()：
 * 查单判定成交 / 到期撤单 / 部分成交 / 回退 FAK，将 pending 触发记录推进至终态。
 * 仅在存在 entryOrderType=MAKER 的策略时才有 pending 记录；无 pending 时近乎零开销。
 */
@Service
class CryptoTailMakerOrderService(
    private val executionService: CryptoTailStrategyExecutionService
) {

    private val logger = LoggerFactory.getLogger(CryptoTailMakerOrderService::class.java)

    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + scopeJob)

    /** 跟踪上一轮对账任务，防止并发重叠 */
    @Volatile
    private var reconcileJob: Job? = null

    /** 每 3 秒对账一次；上一轮未结束则跳过 */
    @Scheduled(fixedDelay = 3_000)
    fun scheduledReconcile() {
        val previousJob = reconcileJob
        if (previousJob != null && previousJob.isActive) {
            logger.debug("上一轮 maker 挂单对账仍在执行，跳过本次调度")
            return
        }
        reconcileJob = scope.launch {
            try {
                executionService.reconcilePendingMakerOrders()
            } catch (e: Exception) {
                logger.error("maker 挂单对账定时任务异常: ${e.message}", e)
            } finally {
                reconcileJob = null
            }
        }
    }

    @PreDestroy
    fun destroy() {
        scopeJob.cancel()
    }
}
