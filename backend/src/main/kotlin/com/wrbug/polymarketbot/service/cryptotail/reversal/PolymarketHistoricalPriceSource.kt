package com.wrbug.polymarketbot.service.cryptotail.reversal

import java.math.BigDecimal

/**
 * Polymarket 历史赔率数据源（PoC）。
 * 给定 (币种 updown 完整 slug 前缀, 周期, 周期起点) 返回该周期 Up 代币的历史赔率路径。
 *
 * 失败（市场不存在/无价格点/网络异常）一律返回 null，由调用方跳过该周期——PoC 失败不阻塞。
 */
interface PolymarketHistoricalPriceSource {

    data class PeriodPath(
        val periodStartUnix: Long,
        val periodSeconds: Int,
        /** Up/Yes 代币 id */
        val upTokenId: String,
        /** (t_unix, upPrice 0~1) 升序 */
        val points: List<Pair<Long, BigDecimal>>
    )

    /**
     * @param fullSlugPrefix 完整市场 slug 前缀，如 "btc-updown-5m"
     * @param intervalSeconds 300 / 900
     * @param periodStartUnix 周期起点 unix 秒（对齐周期）
     */
    fun fetchPeriodPath(fullSlugPrefix: String, intervalSeconds: Int, periodStartUnix: Long): PeriodPath?
}
