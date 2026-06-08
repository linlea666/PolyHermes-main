package com.wrbug.polymarketbot.service.cryptotail

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * 加密价差策略订单簿快照共享缓存（tokenId -> 最近一次盘口质量快照）。
 *
 * 从 [CryptoTailOrderbookWsService] 提取为独立组件：WS 服务收到 book/price_change 时写入，
 * 执行服务在发单前可读取"当前最新 WS 帧"（WS 主 / REST 兜底）。提取而非互相注入，
 * 是为了避免执行服务与 WS 服务的循环依赖（WS 服务已注入执行服务用于触发下单）。
 */
@Component
class CryptoTailOrderbookCache {

    private val cache = ConcurrentHashMap<String, OrderbookQualitySnapshot>()

    fun put(tokenId: String, snapshot: OrderbookQualitySnapshot) {
        cache[tokenId] = snapshot
    }

    fun get(tokenId: String): OrderbookQualitySnapshot? = cache[tokenId]

    /** 取该 token 当前最新 WS 盘口快照（持续随 WS 消息更新）；无则 null */
    fun latestSnapshot(tokenId: String): OrderbookQualitySnapshot? = cache[tokenId]
}
