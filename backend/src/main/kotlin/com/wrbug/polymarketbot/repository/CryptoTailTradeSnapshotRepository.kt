package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.CryptoTailTradeSnapshot
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

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

    /**
     * 校准统计：取已结算且 pWin/won 均已知的快照（实际成交样本），用于可靠性分箱与净 EV。
     * 使用显式 JPQL 而非派生查询：实体字段 pWin（小写 p+大写 W）会让派生查询把属性名解析成 PWin，
     * 与 Hibernate 注册的 pWin 不一致导致启动报 "Unable to locate Attribute [PWin]"。
     */
    @Query(
        "SELECT s FROM CryptoTailTradeSnapshot s " +
            "WHERE s.strategyId = :strategyId AND s.settled = true AND s.won IS NOT NULL AND s.pWin IS NOT NULL"
    )
    fun findAllByStrategyIdAndSettledTrueAndWonIsNotNullAndPWinIsNotNull(@Param("strategyId") strategyId: Long): List<CryptoTailTradeSnapshot>
}
