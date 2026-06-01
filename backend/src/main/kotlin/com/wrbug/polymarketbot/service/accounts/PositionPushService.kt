package com.wrbug.polymarketbot.service.accounts

import com.wrbug.polymarketbot.dto.AccountPositionDto
import com.wrbug.polymarketbot.dto.PositionListResponse
import com.wrbug.polymarketbot.dto.PositionPushMessage
import com.wrbug.polymarketbot.dto.PositionPushMessageType
import com.wrbug.polymarketbot.dto.getPositionKey
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * 仓位推送服务
 * 订阅 PositionPollingService 的事件，推送给 WebSocket 客户端
 */
@Service
class PositionPushService(
    private val positionPollingService: PositionPollingService,
    private val accountService: AccountService
) {
    
    private val logger = LoggerFactory.getLogger(PositionPushService::class.java)
    
    // 存储客户端会话和对应的推送回调
    private val clientCallbacks = ConcurrentHashMap<String, (PositionPushMessage) -> Unit>()
    
    // 存储上一次的仓位数据快照（用于比较差异）
    private var lastCurrentPositions: Map<String, AccountPositionDto> = emptyMap()
    private var lastHistoryPositions: Map<String, AccountPositionDto> = emptyMap()
    
    // 协程作用域和任务
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var subscriptionJob: Job? = null
    
    // 同步锁，确保订阅任务的启动和停止是线程安全的
    private val lock = Any()
    
    /**
     * 初始化服务（订阅 PositionPollingService 的事件）
     */
    @PostConstruct
    fun init() {
        logger.info("PositionPushService 初始化，订阅仓位轮训事件")
        startSubscription()
    }
    
    /**
     * 清理资源
     */
    @PreDestroy
    fun destroy() {
        synchronized(lock) {
            subscriptionJob?.cancel()
            subscriptionJob = null
        }
        scope.cancel()
    }
    
    /**
     * 订阅仓位推送（新接口）
     */
    fun subscribe(sessionId: String, callback: (PositionPushMessage) -> Unit) {
        registerSession(sessionId, callback)
    }
    
    /**
     * 取消订阅仓位推送（新接口）
     */
    fun unsubscribe(sessionId: String) {
        unregisterSession(sessionId)
    }
    
    /**
     * 注册客户端会话（兼容旧接口）
     */
    fun registerSession(sessionId: String, callback: (PositionPushMessage) -> Unit) {
        logger.info("注册仓位推送客户端会话: $sessionId")
        
        synchronized(lock) {
            clientCallbacks[sessionId] = callback
        }
    }
    
    /**
     * 注销客户端会话（兼容旧接口）
     */
    fun unregisterSession(sessionId: String) {
        logger.info("注销仓位推送客户端会话: $sessionId")
        
        synchronized(lock) {
            clientCallbacks.remove(sessionId)
        }
    }
    
    /**
     * 发送全量数据给指定客户端
     */
    suspend fun sendFullData(sessionId: String) {
        try {
            val result = accountService.getAllPositions()
            if (result.isSuccess) {
                val positions = result.getOrNull()
                if (positions != null) {
                    val message = PositionPushMessage(
                        type = PositionPushMessageType.FULL,
                        timestamp = System.currentTimeMillis(),
                        currentPositions = positions.currentPositions,
                        historyPositions = positions.historyPositions
                    )
                    
                    // 更新快照
                    lastCurrentPositions = positions.currentPositions.associateBy { it.getPositionKey() }
                    lastHistoryPositions = positions.historyPositions.associateBy { it.getPositionKey() }
                    
                    // 发送给指定客户端
                    clientCallbacks[sessionId]?.invoke(message)
                }
            } else {
                logger.warn("获取仓位数据失败，无法发送全量数据: ${result.exceptionOrNull()?.message}")
            }
        } catch (e: Exception) {
            logger.error("发送全量仓位数据失败: $sessionId, ${e.message}", e)
        }
    }
    
    /**
     * 启动订阅任务（订阅 PositionPollingService 的事件）
     */
    private fun startSubscription() {
        synchronized(lock) {
            // 如果已经有订阅任务在运行，先取消
            subscriptionJob?.cancel()
            
            // 启动新的订阅任务（使用专门的线程，避免阻塞）
            subscriptionJob = scope.launch(Dispatchers.IO) {
                try {
                    // 订阅仓位轮训事件
                    positionPollingService.subscribe { positions ->
                        try {
                            handlePositionUpdate(positions)
                        } catch (e: Exception) {
                            logger.error("处理仓位更新事件失败: ${e.message}", e)
                        }
                    }
                } catch (e: Exception) {
                    logger.error("订阅仓位轮训事件失败: ${e.message}", e)
                }
            }
        }
    }
    
    /**
     * 处理仓位更新事件
     * 根据文档要求：每次轮训完成后向订阅者发送全量数据
     */
    private fun handlePositionUpdate(positions: PositionListResponse) {
        // 更新快照
        lastCurrentPositions = positions.currentPositions.associateBy { it.getPositionKey() }
        lastHistoryPositions = positions.historyPositions.associateBy { it.getPositionKey() }
        
        // 向所有订阅者发送全量数据
        if (clientCallbacks.isNotEmpty()) {
            val message = PositionPushMessage(
                type = PositionPushMessageType.FULL,
                timestamp = System.currentTimeMillis(),
                currentPositions = positions.currentPositions,
                historyPositions = positions.historyPositions
            )
            
            // 推送给所有连接的客户端（在专门的线程中执行，避免阻塞）
            scope.launch(Dispatchers.IO) {
                clientCallbacks.values.forEach { callback ->
                    try {
                        callback(message)
                    } catch (e: Exception) {
                        logger.error("推送全量数据失败: ${e.message}", e)
                    }
                }
            }
        }
    }
}
