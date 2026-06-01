package com.wrbug.polymarketbot.service.accounts

import com.wrbug.polymarketbot.dto.PositionListResponse
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 仓位轮训服务
 * 独立负责轮训仓位数据，通过事件机制分发给订阅者
 * 使用专门的线程处理事件分发，避免阻塞轮训
 * 提供丢弃机制：如果消费者处理慢，只保留最新的一份数据
 */
@Service
class PositionPollingService(
    private val accountService: AccountService
) {

    private val logger = LoggerFactory.getLogger(PositionPollingService::class.java)

    @Value("\${position.polling.interval:2000}")
    private var pollingInterval: Long = 2000  // 轮训间隔（毫秒），默认2秒

    // 订阅者列表（支持多个订阅者）
    private val subscribers = CopyOnWriteArrayList<(PositionListResponse) -> Unit>()

    // 最新仓位数据（用于丢弃机制）
    @Volatile
    private var latestPositions: PositionListResponse? = null

    // 协程作用域和任务
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var pollingJob: Job? = null

    // 事件分发协程（使用专门的线程，避免阻塞轮训）
    private val eventDispatcherScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 同步锁，确保轮询任务的启动和停止是线程安全的
    private val lock = Any()

    /**
     * 初始化服务（后端启动时直接启动轮训）
     */
    @PostConstruct
    fun init() {
        logger.info("PositionPollingService 初始化，启动仓位轮训任务，轮训间隔: ${pollingInterval}ms")
        startPolling()
    }

    /**
     * 清理资源
     */
    @PreDestroy
    fun destroy() {
        synchronized(lock) {
            pollingJob?.cancel()
            pollingJob = null
        }
        subscribers.clear()
        scope.cancel()
        eventDispatcherScope.cancel()
    }

    /**
     * 订阅仓位事件
     * @param callback 回调函数，接收最新的仓位数据
     */
    fun subscribe(callback: (PositionListResponse) -> Unit) {
        synchronized(lock) {
            subscribers.add(callback)
            // 如果有最新数据，立即发送给新订阅者
            latestPositions?.let { callback(it) }
        }
    }

    /**
     * 取消订阅仓位事件
     */
    fun unsubscribe(callback: (PositionListResponse) -> Unit) {
        synchronized(lock) {
            subscribers.remove(callback)
        }
    }

    /**
     * 启动轮训任务
     */
    private fun startPolling() {
        synchronized(lock) {
            // 如果已经有轮训任务在运行，先取消
            pollingJob?.cancel()

            // 启动新的轮训任务
            pollingJob = scope.launch {
                while (isActive) {
                    try {
                        pollPositions()
                    } catch (e: Exception) {
                        logger.error("轮训仓位数据失败: ${e.message}", e)
                    }
                    delay(pollingInterval)
                }
            }
        }
    }

    /**
     * 轮训仓位数据并发布事件
     * 使用专门的线程分发事件，避免阻塞轮训
     * 实现丢弃机制：只保留最新的一份数据
     */
    private suspend fun pollPositions() {
        try {
            val result = accountService.getAllPositions()
            if (result.isSuccess) {
                val positions = result.getOrNull()
                if (positions != null) {
                    // 更新最新数据（丢弃旧数据，只保留最新的）
                    latestPositions = positions

                    // 在专门的线程中分发事件，避免阻塞轮训
                    eventDispatcherScope.launch {
                        try {
                            // 通知所有订阅者（在专门的线程中执行，避免阻塞）
                            val currentSubscribers = synchronized(lock) {
                                subscribers.toList()  // 复制列表，避免并发修改
                            }

                            currentSubscribers.forEach { callback ->
                                try {
                                    callback(positions)
                                } catch (e: Exception) {
                                    logger.error("通知订阅者失败: ${e.message}", e)
                                }
                            }
                        } catch (e: Exception) {
                            logger.error("分发仓位数据事件失败: ${e.message}", e)
                        }
                    }
                }
            } else {
                logger.warn("获取仓位数据失败: ${result.exceptionOrNull()?.message}")
            }
        } catch (e: Exception) {
            logger.error("轮训仓位数据异常: ${e.message}", e)
        }
    }
}
