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

    /** 单周期采集结果分类（用于回填诊断，定位"为什么没数据"） */
    enum class FetchOutcome {
        /** 成功取到价格路径 */
        OK,

        /** slug 对应的事件/市场/代币未找到（Gamma 404 或无 clobTokenIds） */
        SLUG_NOT_FOUND,

        /** 找到代币但 CLOB 价格历史为空/无落在周期内的有效点 */
        HISTORY_EMPTY,

        /** 采集过程中发生异常 */
        FETCH_ERROR
    }

    /** 单周期采集结果：path 仅在 outcome=OK 时非空 */
    data class FetchResult(
        val outcome: FetchOutcome,
        val path: PeriodPath? = null
    )

    /**
     * 采集单周期 Up 代币历史赔率路径，并返回分类结果（便于回填诊断）。
     * @param fullSlugPrefix 完整市场 slug 前缀，如 "btc-updown-5m"
     * @param intervalSeconds 300 / 900
     * @param periodStartUnix 周期起点 unix 秒（对齐周期）
     */
    fun fetchPeriod(fullSlugPrefix: String, intervalSeconds: Int, periodStartUnix: Long): FetchResult

    /** 兼容旧调用：仅返回 path（成功时非空） */
    fun fetchPeriodPath(fullSlugPrefix: String, intervalSeconds: Int, periodStartUnix: Long): PeriodPath? =
        fetchPeriod(fullSlugPrefix, intervalSeconds, periodStartUnix).path
}
