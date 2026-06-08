package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.CryptoTailStrategyTrigger
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

interface CryptoTailStrategyTriggerRepository : JpaRepository<CryptoTailStrategyTrigger, Long> {

    fun findByStrategyIdAndPeriodStartUnix(strategyId: Long, periodStartUnix: Long): CryptoTailStrategyTrigger?
    fun findAllByStrategyIdOrderByCreatedAtDesc(strategyId: Long, pageable: Pageable): Page<CryptoTailStrategyTrigger>
    fun findAllByStrategyIdAndStatusOrderByCreatedAtDesc(strategyId: Long, status: String, pageable: Pageable): Page<CryptoTailStrategyTrigger>
    fun countByStrategyIdAndStatus(strategyId: Long, status: String): Long

    fun findAllByStrategyIdAndCreatedAtBetweenOrderByCreatedAtDesc(strategyId: Long, startInclusive: Long, endInclusive: Long, pageable: Pageable): Page<CryptoTailStrategyTrigger>
    fun findAllByStrategyIdAndStatusAndCreatedAtBetweenOrderByCreatedAtDesc(strategyId: Long, status: String, startInclusive: Long, endInclusive: Long, pageable: Pageable): Page<CryptoTailStrategyTrigger>
    fun countByStrategyIdAndCreatedAtBetween(strategyId: Long, startInclusive: Long, endInclusive: Long): Long
    fun countByStrategyIdAndStatusAndCreatedAtBetween(strategyId: Long, status: String, startInclusive: Long, endInclusive: Long): Long

    @Query(
        "SELECT t FROM CryptoTailStrategyTrigger t WHERE t.strategyId = :strategyId AND t.resolved = true " +
            "ORDER BY COALESCE(t.settledAt, t.createdAt) DESC"
    )
    fun findLatestResolvedByStrategyId(
        @Param("strategyId") strategyId: Long,
        pageable: Pageable
    ): List<CryptoTailStrategyTrigger>

    /** 轮询结算：仅处理下单成功的订单（status=success 且 orderId 非空）、且未结算的触发记录 */
    fun findByStatusAndResolvedAndOrderIdIsNotNullOrderByCreatedAtAsc(status: String, resolved: Boolean): List<CryptoTailStrategyTrigger>

    /**
     * 参数建议：取某策略已结算、且带 TAIL_DIFF 入场快照（score 非空）的触发记录。
     * mode 用冻结值过滤，避免策略后续改 mode 污染语义。
     */
    @Query(
        "SELECT t FROM CryptoTailStrategyTrigger t WHERE t.strategyId = :strategyId AND t.resolved = true " +
            "AND t.mode = :mode AND t.score IS NOT NULL ORDER BY COALESCE(t.settledAt, t.createdAt) ASC"
    )
    fun findResolvedTailDiffByStrategyId(
        @Param("strategyId") strategyId: Long,
        @Param("mode") mode: com.wrbug.polymarketbot.enums.TradingMode
    ): List<CryptoTailStrategyTrigger>

    /** 根据订单 ID 查询加密价差策略触发记录 */
    fun findByOrderId(orderId: String): CryptoTailStrategyTrigger?

    /** maker 挂单生命周期对账：扫描 status=pending 且 orderId 非空的触发记录，按创建时间正序 */
    fun findByStatusAndOrderIdIsNotNullOrderByCreatedAtAsc(status: String): List<CryptoTailStrategyTrigger>

    /** 轮询发 TG：status=success、orderId 非空、未发过通知，按创建时间正序 */
    fun findByStatusAndOrderIdIsNotNullAndNotificationSentFalseOrderByCreatedAtAsc(status: String): List<CryptoTailStrategyTrigger>

    /** 策略已结算订单的总已实现盈亏（用于收益统计） */
    @Query("SELECT COALESCE(SUM(t.realizedPnl), 0) FROM CryptoTailStrategyTrigger t WHERE t.strategyId = :strategyId AND t.resolved = true")
    fun sumRealizedPnlByStrategyId(@Param("strategyId") strategyId: Long): BigDecimal?

    /** 策略已结算订单笔数（用于胜率分母） */
    @Query("SELECT COUNT(t) FROM CryptoTailStrategyTrigger t WHERE t.strategyId = :strategyId AND t.resolved = true")
    fun countResolvedByStrategyId(@Param("strategyId") strategyId: Long): Long

    /** 风控-日亏闸：指定结算时间点之后已结算订单的已实现盈亏之和（settledAt 为毫秒时间戳） */
    @Query("SELECT COALESCE(SUM(t.realizedPnl), 0) FROM CryptoTailStrategyTrigger t WHERE t.strategyId = :strategyId AND t.resolved = true AND t.settledAt >= :settledAtAfter")
    fun sumRealizedPnlByStrategyIdAndSettledAtAfter(@Param("strategyId") strategyId: Long, @Param("settledAtAfter") settledAtAfter: Long): BigDecimal?

    /** 账户级风控-日亏闸：同账户所有策略指定结算时间点之后已结算订单的已实现盈亏之和 */
    @Query(
        "SELECT COALESCE(SUM(t.realizedPnl), 0) FROM CryptoTailStrategyTrigger t, CryptoTailStrategy s " +
            "WHERE t.strategyId = s.id AND s.accountId = :accountId AND t.resolved = true AND t.settledAt >= :settledAtAfter"
    )
    fun sumRealizedPnlByAccountIdAndSettledAtAfter(@Param("accountId") accountId: Long, @Param("settledAtAfter") settledAtAfter: Long): BigDecimal?

    /** 风控-并发敞口闸：已成功下单但未结算的笔数 */
    fun countByStrategyIdAndStatusAndResolvedFalse(strategyId: Long, status: String): Long

    /** SCALP_FLIP 同方向并发上限闸：同策略同 outcome 方向、已成功下单但未结算的笔数 */
    fun countByStrategyIdAndOutcomeIndexAndStatusAndResolvedFalse(strategyId: Long, outcomeIndex: Int, status: String): Long

    /** 账户级风控-并发敞口闸：同账户所有策略已成功下单但未结算的笔数 */
    @Query(
        "SELECT COUNT(t) FROM CryptoTailStrategyTrigger t, CryptoTailStrategy s " +
            "WHERE t.strategyId = s.id AND s.accountId = :accountId AND t.status = :status AND t.resolved = false"
    )
    fun countByAccountIdAndStatusAndResolvedFalse(@Param("accountId") accountId: Long, @Param("status") status: String): Long

    /** 账户级风控-日订单上限：同账户所有策略指定创建时间范围内成功入场笔数 */
    @Query(
        "SELECT COUNT(t) FROM CryptoTailStrategyTrigger t, CryptoTailStrategy s " +
            "WHERE t.strategyId = s.id AND s.accountId = :accountId AND t.status = :status " +
            "AND t.createdAt >= :startInclusive AND t.createdAt <= :endInclusive"
    )
    fun countByAccountIdAndStatusAndCreatedAtBetween(
        @Param("accountId") accountId: Long,
        @Param("status") status: String,
        @Param("startInclusive") startInclusive: Long,
        @Param("endInclusive") endInclusive: Long
    ): Long

    /** 同账户 pending 买单占用的策略请求金额，用于下单前余额预留扣减 */
    @Query(
        "SELECT COALESCE(SUM(t.amountUsdc), 0) FROM CryptoTailStrategyTrigger t, CryptoTailStrategy s " +
            "WHERE t.strategyId = s.id AND s.accountId = :accountId AND t.status = 'pending' AND t.resolved = false"
    )
    fun sumPendingEntryAmountByAccountId(@Param("accountId") accountId: Long): BigDecimal?

    /** 同账户同 market+period+outcome 是否已有有效入场，防止多个策略重复开同一仓位 */
    @Query(
        "SELECT COUNT(t) FROM CryptoTailStrategyTrigger t, CryptoTailStrategy s " +
            "WHERE t.strategyId = s.id AND s.accountId = :accountId AND s.marketSlugPrefix = :marketSlugPrefix " +
            "AND t.periodStartUnix = :periodStartUnix AND t.outcomeIndex = :outcomeIndex " +
            "AND t.resolved = false AND t.status IN ('success', 'pending')"
    )
    fun countOpenMarketPositionByAccountMarketPeriodOutcome(
        @Param("accountId") accountId: Long,
        @Param("marketSlugPrefix") marketSlugPrefix: String,
        @Param("periodStartUnix") periodStartUnix: Long,
        @Param("outcomeIndex") outcomeIndex: Int
    ): Long

    /** 策略已结算中赢的笔数（outcome_index = winner_outcome_index） */
    @Query("SELECT COUNT(t) FROM CryptoTailStrategyTrigger t WHERE t.strategyId = :strategyId AND t.resolved = true AND t.outcomeIndex = t.winnerOutcomeIndex")
    fun countWinsByStrategyId(@Param("strategyId") strategyId: Long): Long

    /** 收益曲线：已结算记录，按结算时间（无则创建时间）在区间内升序 */
    @Query(
        "SELECT t FROM CryptoTailStrategyTrigger t WHERE t.strategyId = :strategyId AND t.resolved = true " +
            "AND COALESCE(t.settledAt, t.createdAt) >= :start AND COALESCE(t.settledAt, t.createdAt) <= :end " +
            "ORDER BY COALESCE(t.settledAt, t.createdAt) ASC"
    )
    fun findResolvedByStrategyIdAndTimeRangeOrderBySettledAsc(
        @Param("strategyId") strategyId: Long,
        @Param("start") start: Long,
        @Param("end") end: Long
    ): List<CryptoTailStrategyTrigger>

    /** 统计概览：全策略已结算记录，按结算时间（无则创建时间）在区间内升序（mode/账户/策略过滤在服务层完成） */
    @Query(
        "SELECT t FROM CryptoTailStrategyTrigger t WHERE t.resolved = true " +
            "AND COALESCE(t.settledAt, t.createdAt) >= :start AND COALESCE(t.settledAt, t.createdAt) <= :end " +
            "ORDER BY COALESCE(t.settledAt, t.createdAt) ASC"
    )
    fun findResolvedByTimeRangeOrderBySettledAsc(
        @Param("start") start: Long,
        @Param("end") end: Long
    ): List<CryptoTailStrategyTrigger>

    /**
     * 概率模式持仓退出扫描：查找指定策略+周期下所有 OPEN/PARTIAL_EXIT 状态的触发记录。
     * 由 WS onBestBid 回调驱动退出服务评估，BARRIER_HOLD 与 BRACKET_DYNAMIC 共用。
     * 注：未指定 mode 是因为 ExitStatus.OPEN/PARTIAL_EXIT 仅由启用退出管理的概率模式入场写入。
     */
    @Query(
        "SELECT t FROM CryptoTailStrategyTrigger t WHERE t.strategyId = :strategyId " +
            "AND t.periodStartUnix = :periodStartUnix " +
            "AND t.exitStatus IN ('OPEN', 'PARTIAL_EXIT')"
    )
    fun findOpenForBracket(
        @Param("strategyId") strategyId: Long,
        @Param("periodStartUnix") periodStartUnix: Long
    ): List<CryptoTailStrategyTrigger>

    /**
     * 退出 poller 兜底扫描：跨策略找 OPEN/PARTIAL_EXIT 持仓，策略启用、模式、周期有效性由服务层按最新策略配置过滤。
     */
    @Query(
        "SELECT t FROM CryptoTailStrategyTrigger t WHERE t.exitStatus IN ('OPEN', 'PARTIAL_EXIT') " +
            "AND t.tokenId IS NOT NULL AND t.remainingSize IS NOT NULL"
    )
    fun findAllOpenForExitPolling(): List<CryptoTailStrategyTrigger>

    @Modifying
    @Transactional
    @Query("UPDATE CryptoTailStrategyTrigger t SET t.lastExitCheckAt = :lastExitCheckAt WHERE t.id = :triggerId")
    fun updateLastExitCheckAt(
        @Param("triggerId") triggerId: Long,
        @Param("lastExitCheckAt") lastExitCheckAt: Long
    ): Int
}
