package com.wrbug.polymarketbot.entity

import com.wrbug.polymarketbot.enums.SpreadDirection
import com.wrbug.polymarketbot.enums.SpreadDirectionConverter
import com.wrbug.polymarketbot.enums.SpreadMode
import com.wrbug.polymarketbot.enums.SpreadModeConverter
import com.wrbug.polymarketbot.enums.TradingMode
import com.wrbug.polymarketbot.enums.TradingModeConverter
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

    /** 障碍（终值概率）模式开关；false 时完全走旧逻辑，旧行为不变。
     *  注：自 V52 起新增 [mode] 字段统一承载三种模式，本字段保留作为兼容字段（前端不再写入），
     *  但 mode/barrierEnabled 在历史数据迁移后保持一致：mode==BARRIER_HOLD ⇔ barrierEnabled=true。 */
    @Column(name = "barrier_enabled", nullable = false)
    val barrierEnabled: Boolean = false,

    /** 交易模式（V52 引入）：0=LEGACY_SPREAD 旧价差, 1=BARRIER_HOLD 障碍持有, 2=BRACKET_DYNAMIC 概率阶梯止盈 */
    @Convert(converter = TradingModeConverter::class)
    @Column(name = "mode", nullable = false, columnDefinition = "TINYINT")
    val mode: TradingMode = TradingMode.LEGACY_SPREAD,

    /** 阶梯模式进场胜率阈值 pWin>=此值才进场，默认 0.80（高于障碍模式默认 0.55，更严苛） */
    @Column(name = "bracket_entry_prob", nullable = false, precision = 20, scale = 8)
    val bracketEntryProb: BigDecimal = BigDecimal("0.80"),

    /** 阶梯模式扣费 EV 边际阈值 edge=pWin-有效成本>=此值，默认 0.04 */
    @Column(name = "bracket_entry_edge", nullable = false, precision = 20, scale = 8)
    val bracketEntryEdge: BigDecimal = BigDecimal("0.04"),

    /** 阶梯模式入场最高买价（封顶 bestAsk/有效成本），默认 0.90 */
    @Column(name = "bracket_max_entry_price", nullable = false, precision = 20, scale = 8)
    val bracketMaxEntryPrice: BigDecimal = BigDecimal("0.90"),

    /** 止盈1: bestBid 价格阈值；bestBid>=此值且 pWin<tp1HoldPwin 触发卖出 tp1Ratio 的剩余仓位 */
    @Column(name = "tp1_price", nullable = false, precision = 20, scale = 8)
    val tp1Price: BigDecimal = BigDecimal("0.90"),

    /** 止盈1: 卖出剩余仓位比例 0~1，默认 0.50（卖一半） */
    @Column(name = "tp1_ratio", nullable = false, precision = 20, scale = 8)
    val tp1Ratio: BigDecimal = BigDecimal("0.50"),

    /** 止盈1跳过条件: pWin>=此值则不卖（系统认为还能涨，继续持有），默认 0.95 */
    @Column(name = "tp1_hold_pwin", nullable = false, precision = 20, scale = 8)
    val tp1HoldPwin: BigDecimal = BigDecimal("0.95"),

    /** 止盈2: bestBid 价格阈值，默认 0.95 */
    @Column(name = "tp2_price", nullable = false, precision = 20, scale = 8)
    val tp2Price: BigDecimal = BigDecimal("0.95"),

    /** 止盈2: 卖出剩余仓位比例，默认 1.00（全部清仓） */
    @Column(name = "tp2_ratio", nullable = false, precision = 20, scale = 8)
    val tp2Ratio: BigDecimal = BigDecimal("1.00"),

    /** 止盈2跳过条件: pWin>=此值则不卖（极高概率冲到 0.99），默认 0.99 */
    @Column(name = "tp2_hold_pwin", nullable = false, precision = 20, scale = 8)
    val tp2HoldPwin: BigDecimal = BigDecimal("0.99"),

    /** 持有到结算的 pWin 阈值；pWin>=此值且剩余时间 <=holdToSettleSeconds 才允许放弃 TP/STOP 拿到结算 */
    @Column(name = "hold_to_settle_pwin", nullable = false, precision = 20, scale = 8)
    val holdToSettlePwin: BigDecimal = BigDecimal("0.97"),

    /** 持有到结算允许的剩余秒数阈值，默认 30s */
    @Column(name = "hold_to_settle_seconds", nullable = false)
    val holdToSettleSeconds: Int = 30,

    /** 止损 pWin 阈值: pWin<=此值触发 FAK 平仓（与 stopPrice OR 关系），默认 0.55 */
    @Column(name = "stop_prob", nullable = false, precision = 20, scale = 8)
    val stopProb: BigDecimal = BigDecimal("0.55"),

    /** 止损价格阈值: bestBid<=此值触发 FAK 平仓（与 stopProb OR 关系），默认 0.70 */
    @Column(name = "stop_price", nullable = false, precision = 20, scale = 8)
    val stopPrice: BigDecimal = BigDecimal("0.70"),

    /** 距结算 N 秒未触发任何 exit 时强制 FAK 平仓（避免尾盘流动性枯竭被困死），默认 15s */
    @Column(name = "force_exit_before_settle_seconds", nullable = false)
    val forceExitBeforeSettleSeconds: Int = 15,

    /** 退出订单类型: FAK=吃单(默认,确保成交); MAKER=挂单(可省滑点,需配合超时回退) */
    @Column(name = "exit_order_type", nullable = false, length = 8)
    val exitOrderType: String = "FAK",

    @Column(name = "min_safe_ratio", nullable = false, precision = 20, scale = 8)
    val minSafeRatio: BigDecimal = BigDecimal("1.20"),

    @Column(name = "min_safe_ratio_up", nullable = false, precision = 20, scale = 8)
    val minSafeRatioUp: BigDecimal = BigDecimal("1.50"),

    @Column(name = "min_safe_ratio_down", nullable = false, precision = 20, scale = 8)
    val minSafeRatioDown: BigDecimal = BigDecimal("1.20"),

    @Column(name = "high_price_threshold", nullable = false, precision = 20, scale = 8)
    val highPriceThreshold: BigDecimal = BigDecimal("0.90"),

    @Column(name = "high_price_min_pwin", nullable = false, precision = 20, scale = 8)
    val highPriceMinPWin: BigDecimal = BigDecimal("0.97"),

    @Column(name = "high_price_min_safe_ratio", nullable = false, precision = 20, scale = 8)
    val highPriceMinSafeRatio: BigDecimal = BigDecimal("2.50"),

    @Column(name = "enable_exit_manager", nullable = false)
    val enableExitManager: Boolean = true,

    @Column(name = "max_loss_pct", nullable = false, precision = 20, scale = 8)
    val maxLossPct: BigDecimal = BigDecimal("0.20"),

    @Column(name = "exit_pwin", nullable = false, precision = 20, scale = 8)
    val exitPWin: BigDecimal = BigDecimal("0.70"),

    @Column(name = "exit_safe_ratio", nullable = false, precision = 20, scale = 8)
    val exitSafeRatio: BigDecimal = BigDecimal("0.80"),

    @Column(name = "exit_confirm_ticks", nullable = false)
    val exitConfirmTicks: Int = 2,

    @Column(name = "take_profit_delta1", nullable = false, precision = 20, scale = 8)
    val takeProfitDelta1: BigDecimal = BigDecimal("0.08"),

    @Column(name = "take_profit_sell_pct1", nullable = false, precision = 20, scale = 8)
    val takeProfitSellPct1: BigDecimal = BigDecimal("0.50"),

    @Column(name = "take_profit_bid2", nullable = false, precision = 20, scale = 8)
    val takeProfitBid2: BigDecimal = BigDecimal("0.93"),

    @Column(name = "take_profit_sell_pct2", nullable = false, precision = 20, scale = 8)
    val takeProfitSellPct2: BigDecimal = BigDecimal("0.80"),

    /**
     * 智能硬止损（Smart Hard Stop）开关（V61）：默认 false=关闭，行为与历史一致。
     * 开启后，HARD_STOP 命中时先复核：若价源新鲜、模型方向未反、gap 仍顺、临近结算且 pWin/safeRatio 达标，
     * 则放弃机械硬止损、继续持有到结算（记 HARD_STOP_BYPASSED_BY_HOLD_TO_SETTLE）。复用 holdToSettlePwin/holdToSettleSeconds/exitSafeRatio 阈值。
     */
    @Column(name = "enable_smart_hard_stop", nullable = false)
    val enableSmartHardStop: Boolean = false,

    @Column(name = "emergency_exit_on_model_flip", nullable = false)
    val emergencyExitOnModelFlip: Boolean = true,

    @Column(name = "emergency_exit_on_gap_flip", nullable = false)
    val emergencyExitOnGapFlip: Boolean = true,

    @Column(name = "exit_poll_interval_ms", nullable = false)
    val exitPollIntervalMs: Int = 3000,

    @Column(name = "enable_wick_filter", nullable = false)
    val enableWickFilter: Boolean = true,

    @Column(name = "wick_filter_mode", nullable = false, length = 8)
    val wickFilterMode: String = "SHADOW",

    @Column(name = "wick_lookback_minutes", nullable = false)
    val wickLookbackMinutes: Int = 2,

    @Column(name = "wick_min_body_ratio", nullable = false, precision = 20, scale = 8)
    val wickMinBodyRatio: BigDecimal = BigDecimal("0.20"),

    @Column(name = "wick_rejection_ratio", nullable = false, precision = 20, scale = 8)
    val wickRejectionRatio: BigDecimal = BigDecimal("0.55"),

    @Column(name = "wick_ma_window", nullable = false)
    val wickMaWindow: Int = 3,

    @Column(name = "wick_entry_block_score", nullable = false)
    val wickEntryBlockScore: Int = 70,

    @Column(name = "wick_exit_score", nullable = false)
    val wickExitScore: Int = 75,

    @Column(name = "wick_hold_profit_score", nullable = false)
    val wickHoldProfitScore: Int = 65,

    @Column(name = "wick_use_binance_volume", nullable = false)
    val wickUseBinanceVolume: Boolean = false,

    @Column(name = "wick_volume_spike_ratio", nullable = false, precision = 20, scale = 8)
    val wickVolumeSpikeRatio: BigDecimal = BigDecimal("1.50"),

    @Column(name = "wick_min_ticks_per_candle", nullable = false)
    val wickMinTicksPerCandle: Int = 5,

    @Column(name = "wick_min_range_sigma_ratio", nullable = false, precision = 20, scale = 8)
    val wickMinRangeSigmaRatio: BigDecimal = BigDecimal("0.25"),

    @Column(name = "wick_close_position_up_max", nullable = false, precision = 20, scale = 8)
    val wickClosePositionUpMax: BigDecimal = BigDecimal("0.35"),

    @Column(name = "wick_close_position_down_min", nullable = false, precision = 20, scale = 8)
    val wickClosePositionDownMin: BigDecimal = BigDecimal("0.65"),

    @Column(name = "max_hold_tp1_delay_seconds", nullable = false)
    val maxHoldTp1DelaySeconds: Int = 45,

    @Column(name = "hold_tp1_peak_drawdown", nullable = false, precision = 20, scale = 8)
    val holdTp1PeakDrawdown: BigDecimal = BigDecimal("0.03"),

    @Column(name = "max_entry_spread", nullable = false, precision = 20, scale = 8)
    val maxEntrySpread: BigDecimal = BigDecimal("0.03"),

    @Column(name = "max_orderbook_age_ms", nullable = false)
    val maxOrderbookAgeMs: Int = 3000,

    @Column(name = "max_price_age_ms", nullable = false)
    val maxPriceAgeMs: Int = 3000,

    @Column(name = "min_remaining_seconds", nullable = false)
    val minRemainingSeconds: Int = 90,

    @Column(name = "max_remaining_seconds", nullable = false)
    val maxRemainingSeconds: Int = 420,

    @Column(name = "min_exit_bid_depth_usdc", nullable = false, precision = 20, scale = 8)
    val minExitBidDepthUsdc: BigDecimal = BigDecimal("2.00"),

    @Column(name = "max_exit_spread", nullable = false, precision = 20, scale = 8)
    val maxExitSpread: BigDecimal = BigDecimal("0.05"),

    @Column(name = "enable_trailing_stop", nullable = false)
    val enableTrailingStop: Boolean = true,

    @Column(name = "trailing_start_delta", nullable = false, precision = 20, scale = 8)
    val trailingStartDelta: BigDecimal = BigDecimal("0.08"),

    @Column(name = "trailing_drawdown", nullable = false, precision = 20, scale = 8)
    val trailingDrawdown: BigDecimal = BigDecimal("0.06"),

    @Column(name = "trailing_sell_pct", nullable = false, precision = 20, scale = 8)
    val trailingSellPct: BigDecimal = BigDecimal.ONE,

    @Column(name = "max_orders_per_day")
    val maxOrdersPerDay: Int? = null,

    @Column(name = "max_consecutive_losses")
    val maxConsecutiveLosses: Int? = null,

    @Column(name = "pause_after_loss_minutes", nullable = false)
    val pauseAfterLossMinutes: Int = 0,

    /**
     * 进场胜率阈值 pWin≥entryProb 才进场（0~1），默认 0.55。
     * 该闸仅作"方向倾向下限"防误判，真正的盈亏过滤交给扣费 EV 闸（entryEdge）。
     * 注：阈值过高（如 0.95）会迫使只在 pWin 极端区间下注，而正态近似在极端区间最易高估，
     * 反而拦掉中等倾向但正EV的机会；故默认设为略高于五五开的 0.55。
     */
    @Column(name = "entry_prob", nullable = false, precision = 20, scale = 8)
    val entryProb: BigDecimal = BigDecimal("0.55"),

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

    /** σ 校准系数：sigmaPerSqrtS = baseSpread * sigmaScale / √interval；GARMAN_KLASS/EWMA 已是标准差量纲建议 1.0，MAD 建议 1.2533（√(π/2) 平均绝对位移→标准差修正），默认 1.0 */
    @Column(name = "sigma_scale", nullable = false, precision = 20, scale = 8)
    val sigmaScale: BigDecimal = BigDecimal("1.0"),

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

    /**
     * FAK 进场限价滑点上限（V53 引入）：limit = min(effectiveCost + entryFakSlippage, EV安全最高价, maxEntryPrice/bracketMaxEntryPrice)。
     * 解决 BARRIER/BRACKET 模式下 limit 紧贴 bestAsk 在 0.5–1.5s 网络延迟内被吃空 → FAK KILL 的根因。
     * FAK 实际成交价由对手盘决定；最终限价仍受扣费 EV 边际约束，避免为成交率追成低EV/负EV单。
     * 取值范围 [0, 0.10]，默认 0.02。高流动性档位可调到 0.01；低流动性可上调到 0.03。
     */
    @Column(name = "entry_fak_slippage", nullable = false, precision = 20, scale = 8)
    val entryFakSlippage: BigDecimal = BigDecimal("0.02"),

    /**
     * FAK 退出限价滑点（V66 引入，全局生效，所有模式）：FAK 卖出限价 = bestBid - exitFakSlippage（向下取整到 tick）。
     * 镜像 entryFakSlippage：滑点越大越易立即成交。取值范围 [0, 0.10]，默认 0.02。
     */
    @Column(name = "exit_fak_slippage", nullable = false, precision = 20, scale = 8)
    val exitFakSlippage: BigDecimal = BigDecimal("0.02"),

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

    /** σ 估计方法: GARMAN_KLASS=GK 区间估计(用币安OHLC作波动幅度代理,历史深、冷启动即可用,默认)；MAD=平均绝对位移；EWMA=指数加权(更敏感)。注：MAD/EWMA 取边界价于价源内存历史，冷启动需累积多周期 */
    @Column(name = "sigma_method", nullable = false, length = 16)
    val sigmaMethod: String = "GARMAN_KLASS",

    /** EWMA 衰减系数 λ（0~1，越大越平滑），仅 sigmaMethod=EWMA 生效，默认 0.94（RiskMetrics 经典值） */
    @Column(name = "ewma_lambda", nullable = false, precision = 20, scale = 8)
    val ewmaLambda: BigDecimal = BigDecimal("0.94"),

    /** 分数 Kelly 动态仓位开关：开启后下注额按 edge×置信度的分数 Kelly 计算（仅校准达标后生效），默认 false=固定金额 */
    @Column(name = "kelly_enabled", nullable = false)
    val kellyEnabled: Boolean = false,

    /** Kelly 分数（0~1），实际下注 = 本金 × kellyFraction × f*，¼ Kelly 防误差/尾部风险，默认 0.25 */
    @Column(name = "kelly_fraction", nullable = false, precision = 20, scale = 8)
    val kellyFraction: BigDecimal = BigDecimal("0.25"),

    /** 是否允许同账户在同一 market+period+outcome 上重复开仓，默认 false 防止多策略重复持仓 */
    @Column(name = "allow_duplicate_market_position", nullable = false)
    val allowDuplicateMarketPosition: Boolean = false,

    // ===== Strong Gap Boost（强价差放量，V60）=====
    // 高置信（pWin/safeRatio 远超入场门槛）时按倍数放大下注；只改 amount，不改方向、不放宽任何风控/限价。
    // 默认关闭；开启但仅 shadow 时只写 BOOST_* 决策日志、不真正放大（由 strongGapBoostShadow 控制）。

    /** Strong Gap Boost 总开关，默认 false=不放量 */
    @Column(name = "enable_strong_gap_boost", nullable = false)
    val enableStrongGapBoost: Boolean = false,

    /** shadow 模式：true=只记录 BOOST_* 日志、不真正放大实盘仓位（默认 true，先观察再开实盘） */
    @Column(name = "strong_gap_boost_shadow", nullable = false)
    val strongGapBoostShadow: Boolean = true,

    /** 一档（strong）放量触发的最小 pWin（0~1），默认 0.90 */
    @Column(name = "strong_gap_min_pwin", nullable = false, precision = 20, scale = 8)
    val strongGapMinPwin: BigDecimal = BigDecimal("0.90"),

    /** 一档（strong）放量触发的最小 safeRatio，默认 1.50 */
    @Column(name = "strong_gap_min_safe_ratio", nullable = false, precision = 20, scale = 8)
    val strongGapMinSafeRatio: BigDecimal = BigDecimal("1.50"),

    /** 一档（strong）放量倍数（≥1），默认 1.50 */
    @Column(name = "strong_gap_stake_multiplier", nullable = false, precision = 20, scale = 8)
    val strongGapStakeMultiplier: BigDecimal = BigDecimal("1.50"),

    /** 二档（ultra）放量触发的最小 pWin（0~1），默认 0.95 */
    @Column(name = "ultra_gap_min_pwin", nullable = false, precision = 20, scale = 8)
    val ultraGapMinPwin: BigDecimal = BigDecimal("0.95"),

    /** 二档（ultra）放量触发的最小 safeRatio，默认 2.00 */
    @Column(name = "ultra_gap_min_safe_ratio", nullable = false, precision = 20, scale = 8)
    val ultraGapMinSafeRatio: BigDecimal = BigDecimal("2.00"),

    /** 二档（ultra）放量倍数（≥1），默认 2.00 */
    @Column(name = "ultra_gap_stake_multiplier", nullable = false, precision = 20, scale = 8)
    val ultraGapStakeMultiplier: BigDecimal = BigDecimal("2.00"),

    /** 放量倍数总上限（兜底，防配置失误），默认 2.00 */
    @Column(name = "max_strong_gap_stake_multiplier", nullable = false, precision = 20, scale = 8)
    val maxStrongGapStakeMultiplier: BigDecimal = BigDecimal("2.00"),

    /** 放量后单笔金额上限 USDC，null=不额外限制（仍受余额/EV 约束） */
    @Column(name = "max_boosted_amount_usdc", precision = 20, scale = 8)
    val maxBoostedAmountUsdc: BigDecimal? = null,

    /** 放量后同周期累计敞口上限 USDC，null=不额外限制 */
    @Column(name = "max_boosted_period_exposure_usdc", precision = 20, scale = 8)
    val maxBoostedPeriodExposureUsdc: BigDecimal? = null,

    /** 是否允许在 Kelly 动态仓位之上叠加放量，默认 false（Kelly 已含仓位管理，默认不叠加） */
    @Column(name = "allow_boost_with_kelly", nullable = false)
    val allowBoostWithKelly: Boolean = false,

    // ===== 尾盘价差模式（TAIL_DIFF, V62）=====
    // 仅在 mode==TAIL_DIFF 时生效，其他模式完全忽略，行为不变。
    // 默认值与计划书 §六 一致，BTC 5m 起步参数。

    /** 方向选择：0=自动（UP/DOWN 都做），1=只 Up，2=只 Down */
    @Column(name = "tail_diff_direction", nullable = false, columnDefinition = "TINYINT")
    val tailDiffDirection: Int = 0,

    /** 入场窗口起点（距离 periodStart 多少秒后允许入场） */
    @Column(name = "tail_diff_window_start_seconds", nullable = false)
    val tailDiffWindowStartSeconds: Int = 150,

    /** 入场窗口终点（距离 periodStart 多少秒后停止入场） */
    @Column(name = "tail_diff_window_end_seconds", nullable = false)
    val tailDiffWindowEndSeconds: Int = 60,

    /** 距结算最少剩余秒数；< 该值禁止新开 */
    @Column(name = "tail_diff_min_remaining_seconds", nullable = false)
    val tailDiffMinRemainingSeconds: Int = 50,

    /** 连续 N 次 tick 命中候选才真正下单（防瞬时抖动） */
    @Column(name = "tail_diff_confirm_ticks", nullable = false)
    val tailDiffConfirmTicks: Int = 2,

    /** 候选价格区间下限 */
    @Column(name = "tail_diff_min_price", nullable = false, precision = 20, scale = 8)
    val tailDiffMinPrice: BigDecimal = BigDecimal("0.88"),

    /** 候选价格区间上限 */
    @Column(name = "tail_diff_max_price", nullable = false, precision = 20, scale = 8)
    val tailDiffMaxPrice: BigDecimal = BigDecimal("0.93"),

    /** 极限最高价：触发 ASK_TOO_HIGH 硬否决 */
    @Column(name = "tail_diff_hard_max_price", nullable = false, precision = 20, scale = 8)
    val tailDiffHardMaxPrice: BigDecimal = BigDecimal("0.94"),

    /** 入场最小 modelProb（pWin/统计反转概率） */
    @Column(name = "tail_diff_min_model_prob", nullable = false, precision = 20, scale = 8)
    val tailDiffMinModelProb: BigDecimal = BigDecimal("0.95"),

    /** 入场最小 edge = modelProb - 有效成本 */
    @Column(name = "tail_diff_min_edge", nullable = false, precision = 20, scale = 8)
    val tailDiffMinEdge: BigDecimal = BigDecimal("0.025"),

    /** 有效成本缓冲（bestAsk 缺失时） */
    @Column(name = "tail_diff_cost_buffer", nullable = false, precision = 20, scale = 8)
    val tailDiffCostBuffer: BigDecimal = BigDecimal("0.01"),

    /** 入场最小 diff_sigma（即 safeRatio） */
    @Column(name = "tail_diff_min_diff_sigma", nullable = false, precision = 20, scale = 8)
    val tailDiffMinDiffSigma: BigDecimal = BigDecimal("1.8"),

    /** modelProb 来源策略：STATS / FALLBACK / HYBRID */
    @Column(name = "tail_diff_model_prob_source", nullable = false, length = 16)
    val tailDiffModelProbSource: String = "HYBRID",

    /** 统计样本阈值：低于此值视为不可信，HYBRID 模式回退 BarrierProbability */
    @Column(name = "tail_diff_stats_min_samples", nullable = false)
    val tailDiffStatsMinSamples: Int = 50,

    /** 历史反转统计回看天数（180/365） */
    @Column(name = "tail_diff_stats_lookback_days", nullable = false)
    val tailDiffStatsLookbackDays: Int = 180,

    /** 历史反转统计数据源：BINANCE / POLYMARKET */
    @Column(name = "tail_diff_stats_data_source", nullable = false, length = 16)
    val tailDiffStatsDataSource: String = "BINANCE",

    /** 入场最大盘口价差（bestAsk-bestBid） */
    @Column(name = "tail_diff_max_spread", nullable = false, precision = 20, scale = 8)
    val tailDiffMaxSpread: BigDecimal = BigDecimal("0.02"),

    /** 盘口深度需 >= 下单金额 × 此倍数 */
    @Column(name = "tail_diff_depth_multiplier", nullable = false, precision = 20, scale = 8)
    val tailDiffDepthMultiplier: BigDecimal = BigDecimal("3.0"),

    /** 盘口快照最大年龄 ms */
    @Column(name = "tail_diff_max_orderbook_age_ms", nullable = false)
    val tailDiffMaxOrderbookAgeMs: Int = 2000,

    /** 价源最大年龄 ms */
    @Column(name = "tail_diff_max_price_age_ms", nullable = false)
    val tailDiffMaxPriceAgeMs: Int = 2000,

    /** 反抽速度估算窗口秒数 */
    @Column(name = "tail_diff_reverse_velocity_window_seconds", nullable = false)
    val tailDiffReverseVelocityWindowSeconds: Int = 10,

    /** 反抽速度上限（每秒 σ 数），超过触发 PRICE_RETRACING_FAST 硬否决 */
    @Column(name = "tail_diff_max_reverse_velocity_sigma", nullable = false, precision = 20, scale = 8)
    val tailDiffMaxReverseVelocitySigma: BigDecimal = BigDecimal("0.30"),

    /** 评分权重：价差优势分 */
    @Column(name = "tail_diff_weight_diff", nullable = false)
    val tailDiffWeightDiff: Int = 25,

    /** 评分权重：时间优势分 */
    @Column(name = "tail_diff_weight_time", nullable = false)
    val tailDiffWeightTime: Int = 15,

    /** 评分权重：赔率低估分 */
    @Column(name = "tail_diff_weight_odds_underprice", nullable = false)
    val tailDiffWeightOddsUnderprice: Int = 20,

    /** 评分权重：赔率滞后分 */
    @Column(name = "tail_diff_weight_odds_lag", nullable = false)
    val tailDiffWeightOddsLag: Int = 10,

    /** 评分权重：历史胜率分 */
    @Column(name = "tail_diff_weight_history", nullable = false)
    val tailDiffWeightHistory: Int = 15,

    /** 评分权重：盘口质量分 */
    @Column(name = "tail_diff_weight_book", nullable = false)
    val tailDiffWeightBook: Int = 10,

    /** 评分权重：数据可靠性分 */
    @Column(name = "tail_diff_weight_data", nullable = false)
    val tailDiffWeightData: Int = 5,

    /** 最低入场评分（普通档下限） */
    @Column(name = "tail_diff_min_entry_score", nullable = false)
    val tailDiffMinEntryScore: Int = 70,

    /** 优质档评分下限 */
    @Column(name = "tail_diff_premium_score", nullable = false)
    val tailDiffPremiumScore: Int = 80,

    /** 顶级档评分下限 */
    @Column(name = "tail_diff_top_score", nullable = false)
    val tailDiffTopScore: Int = 90,

    /** 基础下注金额 USDC */
    @Column(name = "tail_diff_base_amount", nullable = false, precision = 20, scale = 8)
    val tailDiffBaseAmount: BigDecimal = BigDecimal.ONE,

    /** 普通档金额倍率 */
    @Column(name = "tail_diff_tier_normal_mult", nullable = false, precision = 20, scale = 8)
    val tailDiffTierNormalMult: BigDecimal = BigDecimal("1.0"),

    /** 优质档金额倍率 */
    @Column(name = "tail_diff_tier_premium_mult", nullable = false, precision = 20, scale = 8)
    val tailDiffTierPremiumMult: BigDecimal = BigDecimal("1.5"),

    /** 顶级档金额倍率 */
    @Column(name = "tail_diff_tier_top_mult", nullable = false, precision = 20, scale = 8)
    val tailDiffTierTopMult: BigDecimal = BigDecimal("2.0"),

    /** 单笔最大下注 USDC（硬上限） */
    @Column(name = "tail_diff_max_amount_per_order", nullable = false, precision = 20, scale = 8)
    val tailDiffMaxAmountPerOrder: BigDecimal = BigDecimal("5"),

    /** 普通档退出预设 JSON（结构见 V62 注释） */
    @Column(name = "tail_diff_exit_preset_normal_json", columnDefinition = "TEXT")
    val tailDiffExitPresetNormalJson: String? = null,

    /** 优质档退出预设 JSON */
    @Column(name = "tail_diff_exit_preset_premium_json", columnDefinition = "TEXT")
    val tailDiffExitPresetPremiumJson: String? = null,

    /** 顶级档退出预设 JSON */
    @Column(name = "tail_diff_exit_preset_top_json", columnDefinition = "TEXT")
    val tailDiffExitPresetTopJson: String? = null,

    /** TAIL_DIFF 专属日亏熔断阈值，null=复用 dailyLossLimitUsdc */
    @Column(name = "tail_diff_daily_loss_limit_usdc", precision = 20, scale = 8)
    val tailDiffDailyLossLimitUsdc: BigDecimal? = null,

    /** 连续 N 笔亏损后暂停 1h */
    @Column(name = "tail_diff_consec_loss_pause_count", nullable = false)
    val tailDiffConsecLossPauseCount: Int = 2,

    /** 连续 N 笔亏损后熔断到日终 */
    @Column(name = "tail_diff_consec_loss_stop_count", nullable = false)
    val tailDiffConsecLossStopCount: Int = 3,

    /**
     * 入场分段 JSON（数组）：每段定义剩余时间窗口 [remaining_lo, remaining_hi] 及阈值覆盖
     * (min_score/min_diff_sigma/min_edge/max_ask) 与可选 exit_tier_bias。
     * 为空/NULL 时退化为单窗口（windowStart/End），行为完全不变。
     */
    @Column(name = "tail_diff_entry_segments_json", columnDefinition = "TEXT")
    val tailDiffEntrySegmentsJson: String? = null,

    @Column(name = "enabled", nullable = false)
    val enabled: Boolean = true,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)
