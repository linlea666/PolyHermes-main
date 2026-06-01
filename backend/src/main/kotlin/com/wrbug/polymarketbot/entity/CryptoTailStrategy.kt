package com.wrbug.polymarketbot.entity

import com.wrbug.polymarketbot.enums.SpreadDirection
import com.wrbug.polymarketbot.enums.SpreadDirectionConverter
import com.wrbug.polymarketbot.enums.SpreadMode
import com.wrbug.polymarketbot.enums.SpreadModeConverter
import jakarta.persistence.*
import java.math.BigDecimal

/**
 * 加密价差策略实体
 * 5/15 分钟 Up or Down 市场，在周期内时间窗口、价格进入区间时市价买入
 */
@Entity
@Table(name = "crypto_tail_strategy")
data class CryptoTailStrategy(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "account_id", nullable = false)
    val accountId: Long = 0L,

    @Column(name = "name", length = 255)
    val name: String? = null,

    @Column(name = "market_slug_prefix", nullable = false, length = 64)
    val marketSlugPrefix: String = "",

    @Column(name = "interval_seconds", nullable = false)
    val intervalSeconds: Int = 300,

    @Column(name = "window_start_seconds", nullable = false)
    val windowStartSeconds: Int = 0,

    @Column(name = "window_end_seconds", nullable = false)
    val windowEndSeconds: Int = 0,

    @Column(name = "min_price", nullable = false, precision = 20, scale = 8)
    val minPrice: BigDecimal = BigDecimal.ONE,

    @Column(name = "max_price", nullable = false, precision = 20, scale = 8)
    val maxPrice: BigDecimal = BigDecimal.ONE,

    @Column(name = "amount_mode", nullable = false, length = 10)
    val amountMode: String = "RATIO",

    @Column(name = "amount_value", nullable = false, precision = 20, scale = 8)
    val amountValue: BigDecimal = BigDecimal.ZERO,

    /** 价差模式: NONE=不校验, FIXED=固定值, AUTO=历史计算 */
    @Convert(converter = SpreadModeConverter::class)
    @Column(name = "spread_mode", nullable = false, columnDefinition = "TINYINT")
    val spreadMode: SpreadMode = SpreadMode.NONE,

    /** 价差数值（FIXED 时必填；AUTO 时可存计算值） */
    @Column(name = "spread_value", precision = 20, scale = 8)
    val spreadValue: BigDecimal? = null,

    /** 价差方向: MIN=最小价差（价差>=配置值触发），MAX=最大价差（价差<=配置值触发） */
    @Convert(converter = SpreadDirectionConverter::class)
    @Column(name = "spread_direction", nullable = false, columnDefinition = "TINYINT")
    val spreadDirection: SpreadDirection = SpreadDirection.MIN,

    /** 障碍（终值概率）模式开关；false 时完全走旧逻辑，旧行为不变 */
    @Column(name = "barrier_enabled", nullable = false)
    val barrierEnabled: Boolean = false,

    /** 进场胜率阈值 pWin≥entryProb 才进场（0~1），默认 0.95 */
    @Column(name = "entry_prob", nullable = false, precision = 20, scale = 8)
    val entryProb: BigDecimal = BigDecimal("0.95"),

    /** 扣费 EV 边际阈值 edge = pWin - 有效成本 ≥ entryEdge，默认 0.02 */
    @Column(name = "entry_edge", nullable = false, precision = 20, scale = 8)
    val entryEdge: BigDecimal = BigDecimal("0.02"),

    /** 障碍模式最高买入限价（0~1），默认 0.99 */
    @Column(name = "max_entry_price", nullable = false, precision = 20, scale = 8)
    val maxEntryPrice: BigDecimal = BigDecimal("0.99"),

    /** bestAsk 缺失时的成本缓冲：有效成本 = bestBid + costBuffer（封顶 maxEntryPrice），默认 0.02 */
    @Column(name = "cost_buffer", nullable = false, precision = 20, scale = 8)
    val costBuffer: BigDecimal = BigDecimal("0.02"),

    /** 市场隐含概率下限：该 outcome 的 bestBid ≥ 此值才进场（0~1），默认 0 = 关闭 */
    @Column(name = "barrier_min_market_prob", nullable = false, precision = 20, scale = 8)
    val barrierMinMarketProb: BigDecimal = BigDecimal.ZERO,

    /** σ 校准系数：sigmaPerSqrtS = baseSpread * sigmaScale / √interval，默认 1.2533（√(π/2) 平均绝对位移→标准差修正） */
    @Column(name = "sigma_scale", nullable = false, precision = 20, scale = 8)
    val sigmaScale: BigDecimal = BigDecimal("1.2533"),

    /** 当日已实现亏损熔断阈值 USDC（正数），null/<=0 = 关闭该闸 */
    @Column(name = "daily_loss_limit_usdc", precision = 20, scale = 8)
    val dailyLossLimitUsdc: BigDecimal? = null,

    /** 最大并发未结算敞口笔数，null/<=0 = 关闭该闸 */
    @Column(name = "max_concurrent_positions")
    val maxConcurrentPositions: Int? = null,

    /** taker 手续费（基点 bps，1bps=0.01%），用于扣费 EV：有效成本=价格×(1+takerFeeBps/10000)，默认 0 */
    @Column(name = "taker_fee_bps", nullable = false)
    val takerFeeBps: Int = 0,

    /** maker 返佣（基点 bps），maker 挂单成交可降低有效成本，默认 0 */
    @Column(name = "maker_rebate_bps", nullable = false)
    val makerRebateBps: Int = 0,

    /** 单笔 gas 成本 USDC，Builder Relayer 免 gas 时为 0；用于净 EV 归因与校准，默认 0 */
    @Column(name = "gas_cost_usdc", nullable = false, precision = 20, scale = 8)
    val gasCostUsdc: BigDecimal = BigDecimal.ZERO,

    /** 进场订单类型: FAK=吃单(taker, 默认, 与原行为一致)；MAKER=挂单(GTC+postOnly@bid+offset, 赚返佣/省价差) */
    @Column(name = "entry_order_type", nullable = false, length = 8)
    val entryOrderType: String = "FAK",

    /** maker 挂单相对 bestBid 的价格偏移(可负)，挂单价=bestBid+offset(不越过bestAsk 以保持 maker, 并封顶 maxEntryPrice)，默认 0=平 bestBid */
    @Column(name = "maker_price_offset", nullable = false, precision = 20, scale = 8)
    val makerPriceOffset: BigDecimal = BigDecimal.ZERO,

    /** maker 挂单在距结算多少秒时仍未成交则触发撤单决策(撤单/回退FAK/放弃)，默认 5 秒 */
    @Column(name = "maker_cancel_before_settle_seconds", nullable = false)
    val makerCancelBeforeSettleSeconds: Int = 5,

    /** maker 撤单时点仍未成交时是否回退为 FAK 吃单(@bestAsk, 封顶 maxEntryPrice)，默认 false=放弃本周期 */
    @Column(name = "maker_fallback_taker", nullable = false)
    val makerFallbackTaker: Boolean = false,

    /** 放量闸开关：开启后，校准未达标期间下注被钳制为 probeAmountUsdc（小额实盘校准），达标后才放大到 amountValue；默认 false=直接按 amountValue */
    @Column(name = "calibration_gate_enabled", nullable = false)
    val calibrationGateEnabled: Boolean = false,

    /** 校准期/未达标时每笔下注的小额 USDC，默认 1 */
    @Column(name = "probe_amount_usdc", nullable = false, precision = 20, scale = 8)
    val probeAmountUsdc: BigDecimal = BigDecimal.ONE,

    /** 放量达标所需最少已结算样本数，默认 30 */
    @Column(name = "calibration_min_samples", nullable = false)
    val calibrationMinSamples: Int = 30,

    /** 放量达标的最大允许校准误差（样本量加权 |预测pWin-实际胜率|），默认 0.10 */
    @Column(name = "calibration_max_error", nullable = false, precision = 20, scale = 8)
    val calibrationMaxError: BigDecimal = BigDecimal("0.10"),

    /** σ 估计方法: MAD=平均绝对位移(默认,与原口径一致)；EWMA=指数加权(更敏感)；GARMAN_KLASS=GK 区间估计(用币安OHLC作波动幅度代理) */
    @Column(name = "sigma_method", nullable = false, length = 16)
    val sigmaMethod: String = "MAD",

    /** EWMA 衰减系数 λ（0~1，越大越平滑），仅 sigmaMethod=EWMA 生效，默认 0.94（RiskMetrics 经典值） */
    @Column(name = "ewma_lambda", nullable = false, precision = 20, scale = 8)
    val ewmaLambda: BigDecimal = BigDecimal("0.94"),

    @Column(name = "enabled", nullable = false)
    val enabled: Boolean = true,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)
