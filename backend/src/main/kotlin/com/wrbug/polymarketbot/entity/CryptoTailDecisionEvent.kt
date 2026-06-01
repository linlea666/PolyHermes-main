package com.wrbug.polymarketbot.entity

import jakarta.persistence.*

/**
 * 加密尾盘策略全链路决策日志（append-only）。
 * 记录从评估（gap/σ/pWin/EV/各闸）→ 下单 → 结算 的每个关键节点；与主流程解耦，仅作观测/审计。
 */
@Entity
@Table(name = "crypto_tail_decision_event")
data class CryptoTailDecisionEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "strategy_id", nullable = false)
    val strategyId: Long = 0L,

    @Column(name = "period_start_unix", nullable = false)
    val periodStartUnix: Long = 0L,

    /** 一次评估会话关联ID（同一周期同一次评估的多条事件共享） */
    @Column(name = "correlation_id", nullable = false, length = 64)
    val correlationId: String = "",

    /** 事件类型: EVAL_STARTED/GATE_PASSED/GATE_FAILED/ORDER_SUBMITTED/ORDER_RESULT/SETTLED */
    @Column(name = "event_type", nullable = false, length = 40)
    val eventType: String = "",

    /** 闸名（GATE_* 事件有值） */
    @Column(name = "gate_name", length = 40)
    val gateName: String? = null,

    /** 该闸是否通过（GATE_* 事件有值） */
    @Column(name = "passed")
    val passed: Boolean? = null,

    @Column(name = "reason", columnDefinition = "TEXT")
    val reason: String? = null,

    /** 决策快照 JSON（gap/sigma/pWin/edge/bestBid/bestAsk 等） */
    @Column(name = "payload_json", columnDefinition = "TEXT")
    val payloadJson: String? = null,

    @Column(name = "outcome_index")
    val outcomeIndex: Int? = null,

    /** 关联触发记录ID（下单/结算阶段有值） */
    @Column(name = "trigger_id")
    val triggerId: Long? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis()
)
