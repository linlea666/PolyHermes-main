package com.wrbug.polymarketbot.service.cryptotail

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * 加密尾盘策略 WS 全流程诊断日志（容器日志）。
 *
 * 统一输出单行 `CT-WS evt=<事件> k=v ...`，便于按 `cid`/`tk`/`evt` grep 定位：
 *  - [event]：INFO 级里程碑（连接、订阅、进场链路 TRIGGER→PICK→PRICE→SUBMIT→RESULT），受 [enabled] 控制。
 *  - [heartbeat]：INFO 级行情心跳，每 token 按 [heartbeatIntervalMs] 节流，受 [enabled] 控制；
 *    自愈剪档/交叉盘改为累计计数随心跳输出（selfHeal/crossed），避免逐帧 WARN 刷屏。
 *  - [warn]：WARN 级异常（冷启动/tick_size 切换/连接失败），**始终输出**，不受 [enabled] 控制。
 *
 * cid 约定复用 `"$strategyId-$periodStartUnix"`，与决策事件一致，可跨容器日志与决策日志关联。
 */
@Component
class CryptoTailWsDiag(
    @Value("\${crypto-tail.ws-diag.enabled:true}") private val enabled: Boolean,
    @Value("\${crypto-tail.ws-diag.heartbeat-interval-ms:5000}") private val heartbeatIntervalMs: Long
) {

    // logger 名必须落在 com.wrbug.polymarketbot 包层级下，否则裸名 logger 继承 ROOT(=WARN)，
    // 导致 event()/heartbeat() 的 INFO 全程被丢弃（全流程诊断形同虚设，仅 warn 可见）。用类名挂到包层级。
    private val logger = LoggerFactory.getLogger(CryptoTailWsDiag::class.java)
    private val lastHeartbeatAt = ConcurrentHashMap<String, Long>()

    val isEnabled: Boolean get() = enabled

    /** INFO 里程碑事件（受开关控制） */
    fun event(evt: String, vararg fields: Pair<String, Any?>) {
        if (!enabled) return
        logger.info(format(evt, fields))
    }

    /** WARN 异常事件（始终输出，便于线上第一时间发现丢包/冷启动/拒单根因） */
    fun warn(evt: String, vararg fields: Pair<String, Any?>) {
        logger.warn(format(evt, fields))
    }

    /** 每 token 行情心跳，按 [heartbeatIntervalMs] 节流（受开关控制） */
    fun heartbeat(tokenId: String, vararg fields: Pair<String, Any?>) {
        if (!enabled) return
        val now = System.currentTimeMillis()
        val last = lastHeartbeatAt[tokenId] ?: 0L
        if (now - last < heartbeatIntervalMs) return
        lastHeartbeatAt[tokenId] = now
        val merged = arrayOf<Pair<String, Any?>>("tk" to shortToken(tokenId)) + fields
        logger.info(format("WS_HEARTBEAT", merged))
    }

    /** 仅保留活跃 token 的心跳节流状态，淘汰已下线 token，防止内存无界增长 */
    fun retainTokens(activeTokenIds: Set<String>) {
        lastHeartbeatAt.keys.retainAll(activeTokenIds)
    }

    /** token 过长（70+ 位），日志显示首6+末6 便于辨识两腿，不影响 grep 定位 */
    fun shortToken(tokenId: String): String =
        if (tokenId.length <= 14) tokenId else "${tokenId.take(6)}..${tokenId.takeLast(6)}"

    private fun format(evt: String, fields: Array<out Pair<String, Any?>>): String {
        val sb = StringBuilder("CT-WS evt=").append(evt)
        for ((k, v) in fields) sb.append(' ').append(k).append('=').append(v ?: "")
        return sb.toString()
    }
}
