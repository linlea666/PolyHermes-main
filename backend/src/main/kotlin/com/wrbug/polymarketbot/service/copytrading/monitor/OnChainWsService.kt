package com.wrbug.polymarketbot.service.copytrading.monitor

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.google.gson.JsonNull
import com.wrbug.polymarketbot.api.*
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.service.copytrading.statistics.CopyOrderTrackingService
import com.wrbug.polymarketbot.util.RetrofitFactory
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 链上 WebSocket 监听服务
 * 通过统一服务订阅 Leader 的链上交易
 */
@Service
class OnChainWsService(
    private val unifiedOnChainWsService: UnifiedOnChainWsService,
    private val retrofitFactory: RetrofitFactory,
    private val copyOrderTrackingService: CopyOrderTrackingService,
    private val leaderRepository: LeaderRepository
) {

    private val logger = LoggerFactory.getLogger(OnChainWsService::class.java)

    // 存储需要监听的Leader：leaderId -> Leader
    private val monitoredLeaders = ConcurrentHashMap<Long, Leader>()

    // 存储已处理的交易哈希，用于去重（LRU 缓存，保留最近 100 条）
    private val processedTxHashes: Cache<String, Long> = Caffeine.newBuilder()
        .maximumSize(100)
        .build()

    /**
     * 启动链上 WebSocket 监听
     * 通过统一服务订阅所有 Leader
     */
    fun start(leaders: List<Leader>) {
        // 如果没有 Leader，取消所有订阅
        if (leaders.isEmpty()) {
            logger.info("没有需要监听的 Leader，取消所有订阅")
            stop()
            return
        }

        // 更新 Leader 列表
        monitoredLeaders.clear()
        leaders.forEach { leader ->
            addLeader(leader)
        }
    }

    /**
     * 添加Leader监听
     * 通过统一服务订阅该 Leader 的地址
     */
    fun addLeader(leader: Leader) {
        if (leader.id == null) {
            logger.warn("Leader ID为空，跳过: ${leader.leaderAddress}")
            return
        }

        val leaderId = leader.id!!

        // 如果已经在监听列表中，不重复添加
        if (monitoredLeaders.containsKey(leaderId)) {
            logger.debug("Leader 已在监听列表中: ${leader.leaderName} (${leader.leaderAddress})")
            return
        }

        monitoredLeaders[leaderId] = leader

        // 通过统一服务订阅
        val subscriptionId = "LEADER_$leaderId"
        unifiedOnChainWsService.subscribe(
            subscriptionId = subscriptionId,
            address = leader.leaderAddress,
            entityType = "LEADER",
            entityId = leaderId,
            callback = { txHash, httpClient, rpcApi ->
                handleLeaderTransaction(leaderId, txHash, httpClient, rpcApi)
            }
        )

        logger.info("添加 Leader 监听: ${leader.leaderName} (${leader.leaderAddress})")
    }

    /**
     * 处理 Leader 的交易
     */
    private suspend fun handleLeaderTransaction(
        leaderId: Long,
        txHash: String,
        httpClient: OkHttpClient,
        rpcApi: EthereumRpcApi
    ) {
        val leader = monitoredLeaders[leaderId] ?: return

        // 根据 txHash 去重（使用原子操作避免竞态条件）
        val currentTime = System.currentTimeMillis()
        val existingTimestamp = processedTxHashes.asMap().putIfAbsent(txHash, currentTime)
        if (existingTimestamp != null) {
            logger.debug("交易已处理过，跳过: leaderId=$leaderId, txHash=$txHash, firstProcessedAt=$existingTimestamp")
            return
        }

        logger.debug("开始处理 Leader 交易: leaderId=$leaderId, txHash=$txHash, leaderAddress=${leader.leaderAddress}")

        try {
            // 获取交易 receipt
            val receiptRequest = JsonRpcRequest(
                method = "eth_getTransactionReceipt",
                params = listOf(txHash)
            )

            val receiptResponse = rpcApi.call(receiptRequest)
            if (!receiptResponse.isSuccessful || receiptResponse.body() == null) {
                logger.warn("获取交易 receipt 失败: leaderId=$leaderId, txHash=$txHash, code=${receiptResponse.code()}")
                return
            }

            val receiptRpcResponse = receiptResponse.body()!!
            if (receiptRpcResponse.error != null || receiptRpcResponse.result == null || receiptRpcResponse.result is JsonNull) {
                logger.warn("交易 receipt 错误: leaderId=$leaderId, txHash=$txHash, error=${receiptRpcResponse.error}")
                return
            }

            // 使用 Gson 解析 receipt JSON
            val receiptJson = receiptRpcResponse.result.asJsonObject

            // 获取区块号和时间戳
            val blockNumber = receiptJson.get("blockNumber")?.asString
            val blockTimestamp = if (blockNumber != null) {
                OnChainWsUtils.getBlockTimestamp(blockNumber, rpcApi)
            } else {
                null
            }

            // 解析 receipt 中的 Transfer 日志
            val logs = receiptJson.getAsJsonArray("logs") ?: run {
                logger.warn("交易 receipt 中没有日志: leaderId=$leaderId, txHash=$txHash")
                return
            }
            val (erc20Transfers, erc1155Transfers) = OnChainWsUtils.parseReceiptTransfers(logs)
            logger.debug("解析交易日志: leaderId=$leaderId, txHash=$txHash, erc20Transfers=${erc20Transfers.size}, erc1155Transfers=${erc1155Transfers.size}")

            // 解析交易信息
            val trade = OnChainWsUtils.parseTradeFromTransfers(
                txHash = txHash,
                timestamp = blockTimestamp,
                walletAddress = leader.leaderAddress,
                erc20Transfers = erc20Transfers,
                erc1155Transfers = erc1155Transfers,
                retrofitFactory = retrofitFactory
            )

            if (trade != null) {
                logger.info("成功解析交易: leaderId=$leaderId, txHash=$txHash, side=${trade.side}, market=${trade.market}, size=${trade.size}")
                // 调用 processTrade 处理交易
                copyOrderTrackingService.processTrade(
                    leaderId = leaderId,
                    trade = trade,
                    source = "onchain-ws"
                )
            } else {
                logger.warn("无法解析交易（返回 null）: leaderId=$leaderId, txHash=$txHash, erc20Transfers=${erc20Transfers.size}, erc1155Transfers=${erc1155Transfers.size}")
            }
        } catch (e: Exception) {
            logger.error("处理 Leader 交易失败: leaderId=$leaderId, txHash=$txHash, ${e.message}", e)
        }
    }

    /**
     * 移除Leader监听
     * 取消该 Leader 的订阅
     */
    fun removeLeader(leaderId: Long) {
        monitoredLeaders.remove(leaderId)

        // 通过统一服务取消订阅
        val subscriptionId = "LEADER_$leaderId"
        unifiedOnChainWsService.unsubscribe(subscriptionId)

        logger.info("移除 Leader 监听: leaderId=$leaderId")
    }

    /**
     * 停止监听
     */
    fun stop() {
        // 取消所有 Leader 的订阅
        val leaderIds = monitoredLeaders.keys.toList()
        for (leaderId in leaderIds) {
            removeLeader(leaderId)
        }
        monitoredLeaders.clear()
    }

    @PreDestroy
    fun destroy() {
        stop()
    }
}
