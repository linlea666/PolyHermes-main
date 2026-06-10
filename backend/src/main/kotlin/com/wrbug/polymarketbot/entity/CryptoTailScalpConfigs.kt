package com.wrbug.polymarketbot.entity

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import java.math.BigDecimal

/**
 * 快进快出（SCALP_FLIP）配置的 JPA @Embeddable 值对象集合。
 *
 * 背景与目的：CryptoTailStrategy 字段众多，Kotlin data class 为其合成的 copy$default
 * 等方法的参数槽数会触碰 JVM 方法描述符 255 槽硬上限（启动期 ClassFormatError）。
 * 将 scalp* 配置按语义拆分为多个 @Embeddable 值对象内联到实体，可大幅降低实体主构造器
 * 参数数量、留出充足增长余量；后续 scalp 新增配置应加入对应值对象而非实体主构造器。
 *
 * 兼容性约束：所有 @Column 列名与拆分前完全一致（映射回 crypto_tail_strategy 同名列），
 * 因此无需任何数据库迁移；实体侧通过同名直通属性暴露这些字段，读取方（决策/日志/DTO 映射）零改动。
 */

/** 进场与统计闸配置（进场价区/窗口/反转率统计/EV 钳价/并发/退出模式）。 */
@Embeddable
data class CryptoTailScalpEntryConfig(
    /** 进场价格区间下限（按 bestAsk 判定）；bestAsk ∈ [min,max] 才进场 */
    @Column(name = "scalp_entry_min_price", nullable = false, precision = 20, scale = 8)
    val scalpEntryMinPrice: BigDecimal = BigDecimal("0.96"),

    /** 进场价格区间上限（按 bestAsk 判定） */
    @Column(name = "scalp_entry_max_price", nullable = false, precision = 20, scale = 8)
    val scalpEntryMaxPrice: BigDecimal = BigDecimal("0.97"),

    /** 买入限价封顶（FAK 限价 = bestAsk+entryFakSlippage，封顶此值），防滑点把成本买穿 */
    @Column(name = "scalp_max_fill_price", nullable = false, precision = 20, scale = 8)
    val scalpMaxFillPrice: BigDecimal = BigDecimal("0.975"),

    /**
     * SCALP 进场限价的 EV 钳价模式（仅作用于限价上限，绝不否决进场；进场与否由 [scalpEntryMinPwin] 等闸口决定）：
     *  - CLAMP（默认，现状）：追价上限 = max(evSafeLimit, ask)。因尾盘 evSafeLimit≈pWin 常 < ask，
     *    结果限价被钳到恰好 = ask，[entryFakSlippage] 完全失效、对盘口上跳零容忍。
     *  - GUARD：仅当市场 ask 显著高于 EV 安全价（ask - evSafeLimit > [scalpEvGuardMargin]，疑似坏数据/飞刀）才钳到 ask；
     *    正常分歧放行 [entryFakSlippage]（限价 = ask+滑点，封顶 [scalpMaxFillPrice]），既复活滑点又保留极端兜底。
     *  - OFF：完全不钳 EV 安全价，限价 = ask+[entryFakSlippage]（封顶 [scalpMaxFillPrice]）。
     */
    @Column(name = "scalp_ev_limit_mode", nullable = false, length = 16)
    val scalpEvLimitMode: String = "CLAMP",

    /** GUARD 模式安全阀：仅当 (ask - evSafeLimit) > 此阈值才钳到 EV 安全价；<=0 等价 CLAMP（任意背离即钳）。仅 [scalpEvLimitMode]=GUARD 时生效 */
    @Column(name = "scalp_ev_guard_margin", nullable = false, precision = 20, scale = 8)
    val scalpEvGuardMargin: BigDecimal = BigDecimal("0.10"),

    /** 进场窗口起点（距周期开始秒数，0=周期开始即可进） */
    @Column(name = "scalp_window_start_seconds", nullable = false)
    val scalpWindowStartSeconds: Int = 0,

    /** 进场窗口终点（距周期开始秒数，0=不设上限，仅由 minRemaining 收口尾盘） */
    @Column(name = "scalp_window_end_seconds", nullable = false)
    val scalpWindowEndSeconds: Int = 0,

    /** 进场最小剩余秒数（剩余<此值禁止新开，避免尾盘流动性枯竭/无法退出） */
    @Column(name = "scalp_min_remaining_seconds", nullable = false)
    val scalpMinRemainingSeconds: Int = 30,

    /** 进场时要求的最小卖盘(bid)深度 USDC，确保可退出；null=不检查 */
    @Column(name = "scalp_min_exit_bid_depth_usdc", precision = 20, scale = 8)
    val scalpMinExitBidDepthUsdc: BigDecimal? = null,

    /** 是否启用历史反转率门槛筛选（关闭=纯价格区间进场） */
    @Column(name = "scalp_reversal_gate_enabled", nullable = false)
    val scalpReversalGateEnabled: Boolean = true,

    /** 反转率门槛：领先方向维持到结算的历史概率需 >= 此值 */
    @Column(name = "scalp_min_model_prob", nullable = false, precision = 20, scale = 8)
    val scalpMinModelProb: BigDecimal = BigDecimal("0.95"),

    /** 反转率门槛附加 edge 要求（modelProb - 有效成本 >= 此值）；0=不检查 edge */
    @Column(name = "scalp_min_edge", nullable = false, precision = 20, scale = 8)
    val scalpMinEdge: BigDecimal = BigDecimal.ZERO,

    /** 反转率统计数据源：HYBRID（POLYMARKET 优先回退 BINANCE）/ POLYMARKET / BINANCE */
    @Column(name = "scalp_stats_source", nullable = false, length = 16)
    val scalpStatsSource: String = "HYBRID",

    /** 反转率统计回看天数 */
    @Column(name = "scalp_stats_lookback_days", nullable = false)
    val scalpStatsLookbackDays: Int = 180,

    /** 反转率统计最小样本数；低于此值视为统计不可用 */
    @Column(name = "scalp_stats_min_samples", nullable = false)
    val scalpStatsMinSamples: Int = 30,

    /** 统计不可用时是否拦截进场：true=拦截；false=降级为纯价格区间放行 */
    @Column(name = "scalp_require_stats", nullable = false)
    val scalpRequireStats: Boolean = false,

    /** 同方向最大并发未结算敞口；null=不限制（仍受全局 maxConcurrentPositions 约束） */
    @Column(name = "scalp_max_concurrent_same_direction")
    val scalpMaxConcurrentSameDirection: Int? = null,

    /** 退出模式：true=赢单持有到结算(拿 1.0，不挂止盈)；false=挂止盈单(scalpTpPrice)锁利 */
    @Column(name = "scalp_hold_winner_to_settle", nullable = false)
    val scalpHoldWinnerToSettle: Boolean = true
)

/** 止盈/止损/熔断核心配置（价位止损、标的方向止损、反抽速度、模型衰减、熔断地板）。 */
@Embeddable
data class CryptoTailScalpStopConfig(
    /** 止盈价（仅 scalpHoldWinnerToSettle=false 时生效，bestBid>=此值挂卖） */
    @Column(name = "scalp_tp_price", nullable = false, precision = 20, scale = 8)
    val scalpTpPrice: BigDecimal = BigDecimal("0.99"),

    /** 价位止损开关（相对入场价回撤 + 绝对地板） */
    @Column(name = "scalp_stop_enabled", nullable = false)
    val scalpStopEnabled: Boolean = true,

    /** 价位止损：相对入场价最大回撤比例（如 0.05=跌 5% 止损）。注：高价进场(0.96+)时需 < 1-minPrice/entry 才不被绝对地板覆盖 */
    @Column(name = "scalp_stop_offset", nullable = false, precision = 20, scale = 8)
    val scalpStopOffset: BigDecimal = BigDecimal("0.05"),

    /** 价位止损：绝对最低 bestBid 地板（与相对止损取较高者，越早触发） */
    @Column(name = "scalp_stop_min_price", nullable = false, precision = 20, scale = 8)
    val scalpStopMinPrice: BigDecimal = BigDecimal("0.90"),

    /** 持仓中 bestBid 跌破此值触发软止损（连续确认+地板防插针）；0=不启用。需 > 硬止损线才会先于硬止损触发 */
    @Column(name = "scalp_min_odds_after_entry", nullable = false, precision = 20, scale = 8)
    val scalpMinOddsAfterEntry: BigDecimal = BigDecimal("0.93"),

    /** 标的方向止损开关：持仓中 diff_sigma 跌破阈值即退出（领先优势消失=反转前兆） */
    @Column(name = "scalp_underlying_stop_enabled", nullable = false)
    val scalpUnderlyingStopEnabled: Boolean = true,

    /** 标的方向止损阈值：diff_sigma（领先优势 σ 数）跌破此值退出 */
    @Column(name = "scalp_underlying_stop_sigma", nullable = false, precision = 20, scale = 8)
    val scalpUnderlyingStopSigma: BigDecimal = BigDecimal("0.30"),

    /** 反抽速度止损开关：标的向反方向反抽速度过快即退出 */
    @Column(name = "scalp_reverse_velocity_stop_enabled", nullable = false)
    val scalpReverseVelocityStopEnabled: Boolean = true,

    /** 反抽速度止损阈值（σ/秒），超过即退出 */
    @Column(name = "scalp_max_reverse_velocity_sigma", nullable = false, precision = 20, scale = 8)
    val scalpMaxReverseVelocitySigma: BigDecimal = BigDecimal("0.40"),

    /** 反抽速度估算窗口秒数 */
    @Column(name = "scalp_reverse_velocity_window_seconds", nullable = false)
    val scalpReverseVelocityWindowSeconds: Int = 10,

    /** 持仓模型衰减软止损：持仓中 pWin 跌破此值即退出（0=不启用）。依赖价源可用。默认 0.80（防线A，反转保护，新建策略生效；存量需重新保存采用） */
    @Column(name = "scalp_min_model_prob_after_entry", nullable = false, precision = 20, scale = 8)
    val scalpMinModelProbAfterEntry: BigDecimal = BigDecimal("0.80"),

    /** 领先优势回撤软止损：持仓 diff_sigma 相对入场回撤比例超过此值即退出（0=不启用）。依赖价源+入场冻结 diff_sigma。默认 0.35（防线A，反转保护） */
    @Column(name = "scalp_max_diff_retrace_pct", nullable = false, precision = 20, scale = 8)
    val scalpMaxDiffRetracePct: BigDecimal = BigDecimal("0.35"),

    /** 熔断绝对地板：持仓 bestBid 跌破此值即发无地板市价止损（0=不启用）。作为最深兜底，应低于 scalp_stop_min_price */
    @Column(name = "scalp_catastrophe_bid_floor", nullable = false, precision = 20, scale = 8)
    val scalpCatastropheBidFloor: BigDecimal = BigDecimal("0.88"),

    /** 熔断即时砍：true=跌破地板时跳过 exitConfirmTicks 确认立即市价砍（应对急跌确认延迟放大亏损）；仅在地板>0 时有意义 */
    @Column(name = "scalp_catastrophe_immediate", nullable = false)
    val scalpCatastropheImmediate: Boolean = true,

    /**
     * 熔断相对地板比例（V84/WS2）：>0 时熔断地板 = 入场价×此比例，替代绝对线 scalp_catastrophe_bid_floor，
     * 使不同入场价下熔断口径一致。默认 0.85（≈入场 -15%）；须 > scalp_hard_floor_ratio(0.50) 深底线。
     * 0=关闭（沿用绝对线）。配合"熔断模型门控"：模型仍强挺时跌破地板只走短确认，模型翻转才即时砍。
     */
    @Column(name = "scalp_catastrophe_floor_ratio", nullable = false, precision = 20, scale = 8)
    val scalpCatastropheFloorRatio: BigDecimal = BigDecimal("0.85")
)

/** 执行与智能止损配置（WS 新鲜度、有界重报价、方向确认、智能硬止损旁路、无条件深底线）。 */
@Embeddable
data class CryptoTailScalpExecConfig(
    /**
     * 进场 WS 快照"够新跳过 REST 重拉"阈值（毫秒，V85/WS3）：>0 且 WS 盘口 quoteAgeMs<=此值时，
     * 跳过发单前的 REST 订单簿重拉（直接用 WS 快照复检进场闸），降低决策→执行 100~300ms 延迟、减少盘口上跳零成交。
     * 0=关闭（始终 REST 重拉，与旧行为一致）。仅 SCALP_FLIP 消费。
     */
    @Column(name = "scalp_ws_freshness_skip_rest_ms", nullable = false)
    val scalpWsFreshnessSkipRestMs: Int = 500,

    /**
     * 进场有界 re-quote 重试次数（V85/WS3）：FAK 零成交(可重试)后，重新拉盘口+复检闸+按 EV 安全上沿重定价+重签提交，
     * 最多重试这么多次，捕捉秒级盘口上跳。FAK 为成交即止无挂单，重试不会重复成交。0=关闭。仅 SCALP_FLIP 消费。
     */
    @Column(name = "scalp_entry_requote_max", nullable = false)
    val scalpEntryRequoteMax: Int = 2,

    /** 进场实时方向确认：true=要求标的模型方向(modelSide)与买入侧一致且 pWin 达标，过滤"下跌穿越"飞刀；价源不可用时降级放行 */
    @Column(name = "scalp_require_underlying_agreement", nullable = false)
    val scalpRequireUnderlyingAgreement: Boolean = true,

    /** 进场标的模型胜率下限：买入侧 pWin 须 >= 此值才放行（仅在 scalp_require_underlying_agreement=true 时生效） */
    @Column(name = "scalp_entry_min_pwin", nullable = false, precision = 20, scale = 8)
    val scalpEntryMinPwin: BigDecimal = BigDecimal("0.90"),

    /**
     * SCALP 智能硬止损"插针容忍 pWin"（V81）：智能硬止损旁路的 pWin 下限，与 holdToSettlePwin 解耦。
     * 机械硬止损命中时，若模型仍明显站我方（方向未反 + gap 顺 + safeRatio 达标 + 价源新鲜）且 pWin>=此值，
     * 则判定为盘口插针、放弃本次砍仓继续持有；不需 holdToSettlePwin(0.96) 级的"持有到结算"信心。默认 0.70。
     * 仅 SCALP_FLIP 退出链路消费；BARRIER 仍用 holdToSettlePwin，TAIL_DIFF 行为不变。
     */
    @Column(name = "scalp_smart_stop_min_pwin", nullable = false, precision = 20, scale = 8)
    val scalpSmartStopMinPwin: BigDecimal = BigDecimal("0.70"),

    /**
     * SCALP 智能硬止损/抗插针旁路的 safeRatio 下限（V82）：取代写死的 SMART_HARD_STOP_MIN_SAFE_RATIO(1.30)。
     * 旁路实际下限 = max(exitSafeRatio, 此值)。默认 1.30；调低（如 1.10）可避免"safeRatio 差 0.02 被误杀"。
     * 仅 SCALP_FLIP 消费；BARRIER/TAIL_DIFF 仍用 1.30 常量。
     */
    @Column(name = "scalp_smart_stop_min_safe_ratio", nullable = false, precision = 20, scale = 8)
    val scalpSmartStopMinSafeRatio: BigDecimal = BigDecimal("1.30"),

    /**
     * SCALP 无条件深底线比例（V82）：bestBid <= 入场价×此值即时市价全清止血，是唯一不可被智能复核豁免的硬线。
     * 默认 0.50（即 -50%）。调高（如 0.70）更早无条件止血但牺牲抗插针；调低则更能扛插针但单笔最深亏损更大。
     * 仅 SCALP_FLIP 消费；其余模式仍用 HARD_FLOOR_RATIO(0.50) 常量。
     */
    @Column(name = "scalp_hard_floor_ratio", nullable = false, precision = 20, scale = 8)
    val scalpHardFloorRatio: BigDecimal = BigDecimal("0.50")
)

/** 风控配置（当日已实现亏损熔断、连续亏损暂停/停笔、进场价差闸）。 */
@Embeddable
data class CryptoTailScalpRiskConfig(
    /** SCALP 专属当日已实现亏损熔断阈值（V83）：当日已实现亏损达此值即拦截进场，null=回退全局 dailyLossLimitUsdc。仅 SCALP_FLIP 消费 */
    @Column(name = "scalp_daily_loss_limit_usdc", precision = 20, scale = 8)
    val scalpDailyLossLimitUsdc: BigDecimal? = null,

    /**
     * SCALP 当日连续亏损暂停笔数（V83）：当日连亏达此笔数后在 pauseAfterLossMinutes 冷却窗内暂停进场（0=关）。仅 SCALP_FLIP 消费。
     * 默认 0（暂停默认关）：暂停依赖共享的 pauseAfterLossMinutes（默认 0），二者必须同时 >0 才生效；默认保护交由 scalpConsecLossStopCount(=3) 当日停。
     * 注：V83 迁移 DDL 默认仍为 2，仅 raw insert 生效；经服务/JPA 创建恒以此实体默认(0)为准。
     */
    @Column(name = "scalp_consec_loss_pause_count", nullable = false)
    val scalpConsecLossPauseCount: Int = 0,

    /** SCALP 当日连续亏损停笔数（V83）：当日连亏达此笔数后熔断到日终（0=关）。仅 SCALP_FLIP 消费 */
    @Column(name = "scalp_consec_loss_stop_count", nullable = false)
    val scalpConsecLossStopCount: Int = 3,

    /**
     * 进场价差闸总开关（V87）：true 时在方向闸之后增加一道"领先优势不足则拒单(SCALP_GAP_TOO_SMALL)"。
     * 默认 false（零回归，待复盘因子数据驱动调参后再开）。仅 SCALP_FLIP 消费。
     */
    @Column(name = "scalp_gap_gate_enabled", nullable = false)
    val scalpGapGateEnabled: Boolean = false,

    /** 进场最小 diff_sigma（safeRatio）：闸生效时当前 diff_sigma < 此值即拒单；0=该维度不检查 */
    @Column(name = "scalp_min_entry_diff_sigma", nullable = false, precision = 20, scale = 8)
    val scalpMinEntryDiffSigma: BigDecimal = BigDecimal.ZERO,

    /** 进场最小价差绝对值 |gap|：闸生效时当前 |gap| < 此值即拒单；0=该维度不检查 */
    @Column(name = "scalp_min_entry_gap_abs", nullable = false, precision = 30, scale = 8)
    val scalpMinEntryGapAbs: BigDecimal = BigDecimal.ZERO,

    /** 价差闸生效窗口下限（距结算剩余秒）：闸仅当 remaining>=lo 生效；lo=0 表示无下限 */
    @Column(name = "scalp_gap_gate_remaining_lo", nullable = false)
    val scalpGapGateRemainingLo: Int = 0,

    /** 价差闸生效窗口上限（距结算剩余秒）：hi<=0 表示无上界；与 lo 同为 0 即全周期生效；否则当 remaining ∈ [lo, hi] 生效 */
    @Column(name = "scalp_gap_gate_remaining_hi", nullable = false)
    val scalpGapGateRemainingHi: Int = 0
)

/** 尾盘韧性退出配置（V91/V92：尾盘动态止损、提速、紧急重试、忽略地板、主动减仓）。 */
@Embeddable
data class CryptoTailScalpLateExitConfig(
    /**
     * 尾盘动态止损总开关（V91）：true 时在尾盘窗口内对"从持仓峰值大回撤/跌破地板"立即 HARD_STOP 退出，
     * 并可禁止 WICK_GUARD 把它判为插针继续持有。默认 false（零回归，仅 UI 手动开启后测试）。仅 SCALP_FLIP 消费。
     */
    @Column(name = "scalp_late_stop_enabled", nullable = false)
    val scalpLateStopEnabled: Boolean = false,

    /** 尾盘止损生效窗口（距结算剩余秒）：仅当 remainingSeconds <= 此值时尾盘止损才生效 */
    @Column(name = "scalp_late_stop_seconds", nullable = false)
    val scalpLateStopSeconds: Int = 15,

    /** 峰值回撤止损阈值（绝对价差，非百分比）：(peakBid - currentBestBid) >= 此值即触发；0=该维度不检查 */
    @Column(name = "scalp_late_peak_drawdown", nullable = false, precision = 20, scale = 8)
    val scalpLatePeakDrawdown: BigDecimal = BigDecimal("0.18"),

    /** 尾盘 bid 地板：currentBestBid <= 此值即触发（peakBid 缺失也能触发，不要求模型转弱） */
    @Column(name = "scalp_late_bid_floor", nullable = false, precision = 20, scale = 8)
    val scalpLateBidFloor: BigDecimal = BigDecimal("0.70"),

    /** 尾盘止损命中后是否禁止 WICK_GUARD 豁免：true=禁止豁免立即退出（默认） */
    @Column(name = "scalp_disable_wick_guard_on_late_stop", nullable = false)
    val scalpDisableWickGuardOnLateStop: Boolean = true,

    /** 是否要求模型转弱后才触发尾盘止损：默认 false（盘口常先崩、模型滞后，要求转弱会使止损过慢） */
    @Column(name = "scalp_late_stop_require_weak_model", nullable = false)
    val scalpLateStopRequireWeakModel: Boolean = false,

    /**
     * 尾盘提速窗口（距结算剩余秒，V92 杠杆1）：remaining<=此值时把退出评估节流降到 scalpLateFastPollMs，
     * 避免常规 exitPollIntervalMs(默认3000) 把尾盘评估拖到下一 tick。0=关（零回归）。仅 SCALP_FLIP 消费。
     */
    @Column(name = "scalp_late_fast_poll_seconds", nullable = false)
    val scalpLateFastPollSeconds: Int = 0,

    /** 尾盘提速最小评估间隔（毫秒，V92 杠杆1）：与危险区间隔取更小者；实际仍受 500ms 调度上限约束 */
    @Column(name = "scalp_late_fast_poll_ms", nullable = false)
    val scalpLateFastPollMs: Int = 300,

    /**
     * 紧急退出 FAK 失败快速重试次数（V92 杠杆2）：紧急 marketable FAK 提交硬失败(orderId 为空)时，
     * 同次评估内重新签名并重试，以抓住瞬时回补的对手盘。0=关（仅沿用下一 tick 重试，行为不变）。仅 SCALP_FLIP 消费。
     */
    @Column(name = "scalp_emergency_retry_count", nullable = false)
    val scalpEmergencyRetryCount: Int = 0,

    /** 紧急退出 FAK 重试间隔（毫秒，V92 杠杆2） */
    @Column(name = "scalp_emergency_retry_interval_ms", nullable = false)
    val scalpEmergencyRetryIntervalMs: Int = 150,

    /**
     * 尾盘 marketable 窗口（距结算剩余秒，V92 杠杆3）：remaining<=此值时，连 softPriceExit(裸盘口跌破 minOdds)
     * 也忽略 worstPrice 地板、按 MIN_PRICE 直发市价扫单，避免该卖却被地板挡住骑到归零。0=关（零回归）。仅 SCALP_FLIP 消费。
     */
    @Column(name = "scalp_late_ignore_worst_price_seconds", nullable = false)
    val scalpLateIgnoreWorstPriceSeconds: Int = 0,

    /**
     * 尾盘主动减仓窗口（距结算剩余秒，V92 杠杆4）：remaining<=此值时，无视模型强挺(WICK_GUARD)按 scalpLateScaleOutRatio
     * 主动减仓一次(复用 FORCE，全局仅一次)，用确定性小滑点换掉尾盘穿价归零的尾部风险。0=关（零回归）。仅 SCALP_FLIP 消费。
     */
    @Column(name = "scalp_late_scale_out_seconds", nullable = false)
    val scalpLateScaleOutSeconds: Int = 0,

    /** 尾盘主动减仓比例（V92 杠杆4）：(0,1]，与 scalpLateScaleOutSeconds 同 >0 才生效；0=关 */
    @Column(name = "scalp_late_scale_out_ratio", nullable = false, precision = 20, scale = 8)
    val scalpLateScaleOutRatio: BigDecimal = BigDecimal.ZERO
)

/**
 * 现货价"领先早警"配置（V93），以 JPA @Embeddable 值对象承载，映射回 crypto_tail_strategy 同名列。
 * 抽出独立值对象的目的：降低 CryptoTailStrategy 主构造器参数数量，规避 data class 合成方法
 * （尤其是静态 copy$default）触碰 JVM 方法描述符 255 参数槽上限导致的 ClassFormatError。
 */
@Embeddable
data class CryptoTailScalpSpotLeadConfig(
    @Column(name = "scalp_spot_lead_enabled", nullable = false)
    val enabled: Boolean = false,

    @Column(name = "scalp_spot_lead_source", nullable = false, length = 16)
    val source: String = "BINANCE",

    @Column(name = "scalp_spot_lead_max_age_ms", nullable = false)
    val maxAgeMs: Int = 3000,

    @Column(name = "scalp_spot_lead_flip_distance_sigma", nullable = false, precision = 20, scale = 8)
    val flipDistanceSigma: BigDecimal = BigDecimal.ZERO,

    @Column(name = "scalp_spot_lead_wick_veto_enabled", nullable = false)
    val wickVetoEnabled: Boolean = false,

    @Column(name = "scalp_spot_lead_early_stop_seconds", nullable = false)
    val earlyStopSeconds: Int = 0,

    @Column(name = "scalp_spot_lead_scale_out_ratio", nullable = false, precision = 20, scale = 8)
    val scaleOutRatio: BigDecimal = BigDecimal.ZERO,

    @Column(name = "scalp_late_scale_out_require_spot_danger", nullable = false)
    val lateScaleOutRequireSpotDanger: Boolean = false,

    /**
     * 混合推送总开关（v2/Phase A4）：true 时实时现货 tick 在尾盘窗口内直接推送退出重评估（亚秒级），
     * 不再纯依赖 ~300ms 尾盘轮询。复用 WS 缓存盘口（零新增 REST）、复用退出在途守卫与时间防抖。默认 false（零回归）。
     */
    @Column(name = "scalp_spot_lead_push_enabled", nullable = false)
    val pushEnabled: Boolean = false,

    /** 推送尾盘窗口（距结算剩余秒）：remaining<=此值时每条现货 tick 触发一次退出重评估（边沿+防抖）。 */
    @Column(name = "scalp_spot_lead_push_tail_seconds", nullable = false)
    val pushTailSeconds: Int = 20,

    /** 推送最小间隔（毫秒，按订阅防抖）：同一持仓两次 tick 推送的最小间隔，防止崩盘高频 tick 触发评估风暴。 */
    @Column(name = "scalp_spot_lead_push_min_interval_ms", nullable = false)
    val pushMinIntervalMs: Int = 80,

    /**
     * 入场现货闸开关（Phase B）：true 时在 SCALP 进场闸链追加一道"现货已穿价/逆向则否决进场(SPOT_LEAD_ENTRY_VETO)"。
     * 仅在现货新鲜且明确不利时否决；现货缺失/不新鲜一律放行（fail-safe，不误拦）。默认 false（零回归）。
     */
    @Column(name = "scalp_spot_lead_entry_gate_enabled", nullable = false)
    val entryGateEnabled: Boolean = false,

    /**
     * 尾盘硬止损现货门控开关（Phase C）：true 时尾盘硬止损命中且模型仍强挺、而现货**未**判危险时，
     * 抑制硬止损改为减仓（修"尾盘误伤赢单"）；现货判危险则照常硬止损。默认 false（零回归）。
     */
    @Column(name = "scalp_spot_lead_late_stop_gate_enabled", nullable = false)
    val lateStopGateEnabled: Boolean = false
)
