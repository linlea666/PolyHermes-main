package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.CryptoTailTradeSnapshot
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface CryptoTailTradeSnapshotRepository : JpaRepository<CryptoTailTradeSnapshot, Long> {

    fun findByStrategyIdAndPeriodStartUnix(strategyId: Long, periodStartUnix: Long): CryptoTailTradeSnapshot?

    fun findAllByStrategyIdOrderByPeriodStartUnixDesc(strategyId: Long, pageable: Pageable): Page<CryptoTailTradeSnapshot>

    fun findAllByStrategyIdAndSubmitTsBetweenOrderByPeriodStartUnixDesc(
        strategyId: Long,
        startInclusive: Long,
        endInclusive: Long,
        pageable: Pageable
    ): Page<CryptoTailTradeSnapshot>

    /** 导出用：按时间范围取全部（不分页），按周期升序便于回测时间序列分析 */
    fun findAllByStrategyIdAndSubmitTsBetweenOrderByPeriodStartUnixAsc(
        strategyId: Long,
        startInclusive: Long,
        endInclusive: Long
    ): List<CryptoTailTradeSnapshot>

    fun findAllByStrategyIdOrderByPeriodStartUnixAsc(strategyId: Long): List<CryptoTailTradeSnapshot>

    /** 校准统计：取已结算且 pWin/won 均已知的快照（实际成交样本），用于可靠性分箱与净 EV */
    fun findAllByStrategyIdAndSettledTrueAndWonIsNotNullAndPWinIsNotNull(strategyId: Long): List<CryptoTailTradeSnapshot>
}
