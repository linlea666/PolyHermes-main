/**
 * API 统一响应格式
 */
export interface ApiResponse<T> {
  code: number
  data: T | null
  msg: string
}

/**
 * 账户信息
 */
export interface Account {
  id: number
  walletAddress: string
  proxyAddress: string  // Polymarket 代理钱包地址
  accountName?: string
  isEnabled?: boolean  // 是否启用
  walletType?: string  // 钱包类型：magic（邮箱/OAuth登录）或 safe（MetaMask浏览器钱包）
  apiKeyConfigured: boolean
  apiSecretConfigured: boolean
  apiPassphraseConfigured: boolean
  balance?: string
  totalOrders?: number
  totalPnl?: string
  activeOrders?: number
  completedOrders?: number
  positionCount?: number
}

/**
 * 账户列表响应
 */
export interface AccountListResponse {
  list: Account[]
  total: number
}

/**
 * 账户导入请求
 */
export interface AccountImportRequest {
  privateKey: string
  walletAddress: string
  accountName?: string
  walletType?: string  // 钱包类型：magic（邮箱/OAuth登录）或 safe（MetaMask浏览器钱包）
}

/**
 * 检查代理地址选项请求
 */
export interface CheckProxyOptionsRequest {
  walletAddress: string  // EOA 地址
  privateKey?: string  // 私钥（加密，私钥导入时提供）
  mnemonic?: string  // 助记词（加密，助记词导入时提供）
}

/**
 * 代理地址选项信息
 */
export interface ProxyOption {
  walletType: string  // "magic" 或 "safe"
  proxyAddress: string  // 代理地址
  descriptionKey: string  // 说明文案的多语言 key
  availableBalance: string  // 可用余额
  positionBalance: string  // 仓位余额
  totalBalance: string  // 总余额
  positionCount: number  // 持仓数量
  hasAssets: boolean  // 是否有资产
  error?: string  // 获取失败时的错误信息
}

/**
 * 检查代理地址选项响应
 */
export interface CheckProxyOptionsResponse {
  options: ProxyOption[]  // 代理地址选项列表
}

/**
 * 账户更新请求
 */
export interface AccountUpdateRequest {
  accountId: number
  accountName?: string
}

/**
 * Leader 信息
 */
export interface Leader {
  id: number
  leaderAddress: string
  leaderName?: string
  category?: string
  remark?: string  // Leader 备注（可选）
  website?: string  // Leader 网站（可选）
  copyTradingCount: number
  backtestCount: number  // 回测数量
  totalOrders?: number
  totalPnl?: string
  createdAt: number
  updatedAt: number
}

/**
 * Leader 列表响应
 */
export interface LeaderListResponse {
  list: Leader[]
  total: number
}

/**
 * 持仓信息
 */
export interface PositionDto {
  marketId: string
  title: string  // 市场名称
  side: string  // YES 或 NO
  quantity: string
  avgPrice: string
  currentValue: string
  pnl?: string
}

/**
 * 钱包余额响应（通用类，用于 Account 和 Leader）
 */
export interface WalletBalanceResponse {
  availableBalance: string  // 可用余额（RPC 查询的 USDC 余额）
  positionBalance: string  // 仓位余额（持仓总价值）
  totalBalance: string  // 总余额 = 可用余额 + 仓位余额
  positions?: PositionDto[]
}

/**
 * 账户余额响应
 */
export interface AccountBalanceResponse {
  availableBalance: string  // 可用余额（RPC 查询的 USDC 余额）
  positionBalance: string  // 仓位余额（持仓总价值）
  totalBalance: string  // 总余额 = 可用余额 + 仓位余额
  positions?: PositionDto[]
}

/**
 * Leader 余额响应
 */
export interface LeaderBalanceResponse {
  leaderId: number
  leaderAddress: string
  leaderName?: string
  availableBalance: string  // 可用余额（RPC 查询的 USDC 余额）
  positionBalance: string  // 仓位余额（持仓总价值）
  totalBalance: string  // 总余额 = 可用余额 + 仓位余额
  positions?: PositionDto[]
}


/**
 * Leader 添加请求
 */
export interface LeaderAddRequest {
  leaderAddress: string
  leaderName?: string
  category?: string
}

/**
 * Leader 更新请求
 */
export interface LeaderUpdateRequest {
  leaderId: number
  leaderName?: string
  category?: string
}

/**
 * 跟单模板
 */
export interface CopyTradingTemplate {
  id: number
  templateName: string
  copyMode: 'RATIO' | 'FIXED'
  copyRatio: string
  fixedAmount?: string
  maxOrderSize?: string
  minOrderSize?: string
  maxDailyOrders: number
  priceTolerance: string
  supportSell: boolean
  // 过滤条件
  minOrderDepth?: string
  maxSpread?: string
  minPrice?: string  // 最低价格（可选），NULL表示不限制最低价
  maxPrice?: string  // 最高价格（可选），NULL表示不限制最高价
  pushFilteredOrders?: boolean  // 推送已过滤订单（默认关闭）
  createdAt: number
  updatedAt: number
}

/**
 * 模板列表响应
 */
export interface TemplateListResponse {
  list: CopyTradingTemplate[]
  total: number
}

/**
 * 模板创建请求
 */
export interface TemplateCreateRequest {
  templateName: string
  copyMode?: 'RATIO' | 'FIXED'
  copyRatio?: string
  fixedAmount?: string
  maxOrderSize?: string
  minOrderSize?: string
  maxDailyOrders?: number
  priceTolerance?: string
  supportSell?: boolean
}

/**
 * 模板更新请求
 */
export interface TemplateUpdateRequest {
  templateId: number
  templateName?: string
  copyMode?: 'RATIO' | 'FIXED'
  copyRatio?: string
  fixedAmount?: string
  maxOrderSize?: string
  minOrderSize?: string
  maxDailyOrders?: number
  priceTolerance?: string
  supportSell?: boolean
}

/**
 * 模板复制请求
 */
export interface TemplateCopyRequest {
  templateId: number
  templateName: string
  copyMode?: 'RATIO' | 'FIXED'
  copyRatio?: string
  fixedAmount?: string
  maxOrderSize?: string
  minOrderSize?: string
  maxDailyOrders?: number
  priceTolerance?: string
  supportSell?: boolean
}

/**
 * 跟单配置（独立配置，不再绑定模板）
 */
export interface CopyTrading {
  id: number
  accountId: number
  accountName?: string
  walletAddress: string
  leaderId: number
  leaderName?: string
  leaderAddress: string
  enabled: boolean
  // 跟单配置参数
  copyMode: 'RATIO' | 'FIXED'
  copyRatio: string
  fixedAmount?: string
  maxOrderSize: string
  minOrderSize: string
  maxDailyLoss: string
  maxDailyOrders: number
  priceTolerance: string
  delaySeconds: number
  pollIntervalSeconds: number
  useWebSocket: boolean
  websocketReconnectInterval: number
  websocketMaxRetries: number
  supportSell: boolean
  // 过滤条件
  minOrderDepth?: string
  maxSpread?: string
  minPrice?: string  // 最低价格（可选），NULL表示不限制最低价
  maxPrice?: string  // 最高价格（可选），NULL表示不限制最高价
  // 最大仓位配置
  maxPositionValue?: string  // 最大仓位金额（USDC），NULL表示不启用
  // 关键字过滤配置
  keywordFilterMode?: 'DISABLED' | 'WHITELIST' | 'BLACKLIST'  // 关键字过滤模式
  keywords?: string[]  // 关键字列表，当keywordFilterMode为DISABLED时为null
  // 新增配置字段
  configName?: string  // 配置名（可选，但提供时必须非空）
  pushFailedOrders: boolean  // 推送失败订单（默认关闭）
  maxMarketEndDate?: number  // 市场截止时间限制（毫秒时间戳），仅跟单截止时间小于此时间的订单，NULL表示不启用
  createdAt: number
  updatedAt: number
}

/**
 * 跟单列表响应
 */
export interface CopyTradingListResponse {
  list: CopyTrading[]
  total: number
}

export type LeaderPoolStatus = 'CANDIDATE' | 'WATCH' | 'PAPER' | 'TRIAL' | 'ACTIVE' | 'COOLDOWN' | 'RETIRED'

export interface LeaderPoolSummary {
  totalCount: number
  trialCount: number
  estimatedWorstExposure: string
  pendingRiskCount: number
  defaultExperimentBudget: string
}

export interface LeaderPoolItem {
  id: number
  leaderId: number
  leaderName?: string
  leaderAddress: string
  category?: string
  profileUrl: string
  status: LeaderPoolStatus
  source: string
  sourceRank?: number
  score?: string
  reason?: string
  notes?: string
  suggestedFixedAmount: string
  suggestedMaxDailyOrders: number
  suggestedMaxDailyLoss: string
  suggestedMinPrice?: string
  suggestedMaxPrice?: string
  suggestedMaxPositionValue?: string
  copyTradingCount: number
  hasEnabledCopyTrading: boolean
  estimatedWorstExposure: string
  lastReviewedAt?: number
  lastPromotedAt?: number
  cooldownUntil?: number
  locked: boolean
  researchCandidateId?: number
  researchState?: LeaderResearchState
  researchBadge?: string
  researchSummary?: string
  researchScore?: string
  researchUpdatedAt?: number
  createdAt: number
  updatedAt: number
}

export interface LeaderPoolListResponse {
  summary: LeaderPoolSummary
  list: LeaderPoolItem[]
  total: number
}

export interface LeaderPoolListRequest {
  status?: LeaderPoolStatus
}

export interface LeaderPoolAddRequest {
  leaderId: number
  source?: string
  reason?: string
  notes?: string
}

export interface LeaderPoolUpdateStatusRequest {
  poolId: number
  status: LeaderPoolStatus
  cooldownUntil?: number
  locked?: boolean
}

export interface LeaderPoolUpdatePlanRequest {
  poolId: number
  suggestedFixedAmount?: string
  suggestedMaxDailyOrders?: number
  suggestedMaxDailyLoss?: string
  suggestedMinPrice?: string
  suggestedMaxPrice?: string
  suggestedMaxPositionValue?: string
  reason?: string
  notes?: string
}

export interface LeaderPoolCreateTrialConfigRequest {
  poolId: number
  accountId: number
  enableImmediately?: boolean
  confirm?: boolean
}

export type LeaderResearchState = 'DISCOVERED' | 'CANDIDATE' | 'PAPER' | 'TRIAL_READY' | 'COOLDOWN' | 'RETIRED'

export interface LeaderResearchRunRequest {
  dryRun?: boolean
  triggerType?: 'MANUAL' | 'SCHEDULED' | 'PREVIEW'
}

export interface LeaderResearchRun {
  id: number
  status: string
  triggerType: string
  dryRun: boolean
  startedAt: number
  finishedAt?: number
  durationMs?: number
  sourceCountsJson?: string
  candidateCountsJson?: string
  partialFailure: boolean
  skippedReason?: string
  errorClass?: string
  errorMessage?: string
}

export interface LeaderResearchSummary {
  discoveredCount: number
  candidateCount: number
  paperCount: number
  trialReadyCount: number
  cooldownCount: number
  retiredCount: number
  activePaperSessions: number
  pendingRiskCount: number
  lastRun?: LeaderResearchRun
  sourceLimitations: string[]
}

export interface LeaderResearchCandidateListRequest {
  page?: number
  size?: number
  state?: LeaderResearchState
  query?: string
}

export interface LeaderResearchCandidateListResponse {
  list: LeaderResearchCandidate[]
  total: number
  summary: LeaderResearchSummary
}

export interface LeaderResearchCandidate {
  id: number
  normalizedWallet: string
  leaderId?: number
  leaderName?: string
  poolId?: number
  poolStatus?: string
  suggestedFixedAmount?: string
  suggestedMaxDailyLoss?: string
  suggestedMaxDailyOrders?: number
  suggestedMinPrice?: string
  suggestedMaxPrice?: string
  suggestedMaxPositionValue?: string
  researchState: LeaderResearchState
  source: string
  sourceRank?: number
  score?: string
  scoreVersion?: string
  reason?: string
  riskFlags: string[]
  locked: boolean
  agentOwned: boolean
  provenance: string
  sourceEvidence?: string
  firstSeenAt: number
  lastSourceSeenAt?: number
  lastScoredAt?: number
  cooldownUntil?: number
  cooldownCount: number
  trialReadyAt?: number
  retiredAt?: number
  lastPaperSessionId?: number
  latestPaperSession?: LeaderPaperSession
}

export interface LeaderResearchCandidateDetail {
  candidate: LeaderResearchCandidate
  latestScore?: LeaderResearchScore
  paperSessions: LeaderPaperSession[]
  paperTrades: LeaderPaperTrade[]
  paperPositions: LeaderPaperPosition[]
  events: LeaderResearchEvent[]
}

export interface LeaderResearchScore {
  id: number
  candidateId: number
  runId?: number
  scoreVersion: string
  totalScore: string
  profitSignal: string
  repeatability: string
  liquidityFit: string
  entryPriceFit: string
  slippageRisk: string
  holdingPeriodFit: string
  marketTypeRisk: string
  drawdownRisk: string
  exitLiquidityRisk: string
  dataFreshness: string
  filterPassRate: string
  sampleTradeCount: number
  reason?: string
  createdAt: number
}

export interface LeaderPaperSession {
  id: number
  candidateId: number
  status: string
  startedAt: number
  endedAt?: number
  tradeCount: number
  filteredCount: number
  openExposure: string
  totalRealizedPnl: string
  totalUnrealizedPnl: string
  copyablePnl: string
  maxDrawdown: string
  unknownValuationExposure: string
  confirmedZeroExposure: string
  filteredRatio: string
  lastProcessedEventTime?: number
  scoreSnapshot?: string
}

export interface LeaderPaperTrade {
  id: number
  sessionId: number
  candidateId: number
  activityEventId?: number
  leaderTradeId: string
  marketId: string
  marketTitle?: string
  marketSlug?: string
  side: string
  outcome?: string
  outcomeIndex?: number
  leaderPrice?: string
  leaderSize?: string
  simulatedPrice?: string
  simulatedSize?: string
  simulatedAmount?: string
  fillAssumption: string
  quoteConfidence: string
  quoteSource?: string
  quoteTimestamp?: number
  filterResult: string
  filterReason?: string
  valuationStatus: string
  realizedPnl?: string
  eventTime: number
  createdAt: number
}

export interface LeaderPaperPosition {
  id: number
  sessionId: number
  candidateId: number
  marketId: string
  outcome?: string
  outcomeIndex?: number
  quantity: string
  cost: string
  avgPrice: string
  currentPrice?: string
  currentValue: string
  realizedPnl: string
  unrealizedPnl: string
  valuationStatus: string
  quoteConfidence: string
  quoteSource?: string
  quoteTimestamp?: number
  updatedAt: number
}

export interface LeaderResearchSourceState {
  sourceType: string
  status: string
  lastSuccessAt?: number
  lastFailureAt?: number
  lastRunAt?: number
  lastCandidateCount: number
  errorClass?: string
  errorMessage?: string
  stale: boolean
  disabledReason?: string
  lastCursor?: string
  updatedAt: number
}

export interface LeaderResearchEvent {
  id: number
  candidateId?: number
  runId?: number
  eventType: string
  reason?: string
  payloadSummary?: string
  notificationStatus: string
  notificationError?: string
  dedupeKey?: string
  createdAt: number
  notifiedAt?: number
}

export interface LeaderResearchApprovalRequest {
  candidateId: number
  accountId: number
  confirm?: boolean
}

export interface LeaderResearchApprovalResponse {
  copyTrading: CopyTrading
  warning: string
}

/**
 * 跟单创建请求
 * 所有配置参数都需要手动输入，模板仅用于前端快速填充表单
 */
export interface CopyTradingCreateRequest {
  accountId: number
  leaderId: number
  enabled?: boolean
  // 跟单配置参数
  copyMode?: 'RATIO' | 'FIXED'
  copyRatio?: string
  fixedAmount?: string
  maxOrderSize?: string
  minOrderSize?: string
  maxDailyLoss?: string
  maxDailyOrders?: number
  priceTolerance?: string
  delaySeconds?: number
  pollIntervalSeconds?: number
  useWebSocket?: boolean
  websocketReconnectInterval?: number
  websocketMaxRetries?: number
  supportSell?: boolean
  // 过滤条件
  minOrderDepth?: string
  maxSpread?: string
  minPrice?: string  // 最低价格（可选），NULL表示不限制最低价
  maxPrice?: string  // 最高价格（可选），NULL表示不限制最高价
  // 最大仓位配置
  maxPositionValue?: string  // 最大仓位金额（USDC），NULL表示不启用
  // 关键字过滤配置
  keywordFilterMode?: 'DISABLED' | 'WHITELIST' | 'BLACKLIST'  // 关键字过滤模式
  keywords?: string[]  // 关键字列表，当keywordFilterMode为DISABLED时为null
  // 新增配置字段
  configName?: string  // 配置名（可选，但提供时必须非空）
  pushFailedOrders?: boolean  // 推送失败订单（可选）
  pushFilteredOrders?: boolean  // 推送已过滤订单（可选）
  maxMarketEndDate?: number  // 市场截止时间限制（毫秒时间戳），仅跟单截止时间小于此时间的订单，NULL表示不启用
}

/**
 * 跟单更新请求
 */
export interface CopyTradingUpdateRequest {
  copyTradingId: number
  enabled?: boolean
  // 跟单配置参数（可选，只更新提供的字段）
  copyMode?: 'RATIO' | 'FIXED'
  copyRatio?: string
  fixedAmount?: string
  maxOrderSize?: string
  minOrderSize?: string
  maxDailyLoss?: string
  maxDailyOrders?: number
  priceTolerance?: string
  delaySeconds?: number
  pollIntervalSeconds?: number
  useWebSocket?: boolean
  websocketReconnectInterval?: number
  websocketMaxRetries?: number
  supportSell?: boolean
  // 过滤条件
  minOrderDepth?: string
  maxSpread?: string
  minPrice?: string  // 最低价格（可选），NULL表示不限制最低价
  maxPrice?: string  // 最高价格（可选），NULL表示不限制最高价
  // 最大仓位配置
  maxPositionValue?: string  // 最大仓位金额（USDC），NULL表示不启用
  // 关键字过滤配置
  keywordFilterMode?: 'DISABLED' | 'WHITELIST' | 'BLACKLIST'  // 关键字过滤模式
  keywords?: string[]  // 关键字列表，当keywordFilterMode为DISABLED时为null
  // 新增配置字段
  configName?: string  // 配置名（可选，但提供时必须非空）
  pushFailedOrders?: boolean  // 推送失败订单（可选）
  pushFilteredOrders?: boolean  // 推送已过滤订单（可选）
  maxMarketEndDate?: number  // 市场截止时间限制（毫秒时间戳），仅跟单截止时间小于此时间的订单，NULL表示不启用
}

/**
 * 钱包绑定的模板信息
 */
export interface AccountTemplate {
  templateId: number
  templateName: string
  copyTradingId: number
  leaderId: number
  leaderName?: string
  leaderAddress: string
  enabled: boolean
}

/**
 * 钱包绑定的模板列表响应
 */
export interface AccountTemplatesResponse {
  list: AccountTemplate[]
  total: number
}

/**
 * 跟单订单
 */
export interface CopyOrder {
  id: number
  accountId: number
  templateId: number
  copyTradingId: number
  leaderId: number
  leaderAddress: string
  leaderName?: string
  marketId: string
  category: string
  side: 'BUY' | 'SELL'
  price: string
  size: string
  copyRatio: string
  orderId?: string
  status: string
  filledSize: string
  pnl?: string
  createdAt: number
}

/**
 * 订单列表响应
 */
export interface OrderListResponse {
  list: CopyOrder[]
  total: number
  page: number
  limit: number
}

/**
 * 统计信息
 */
export interface Statistics {
  totalOrders: number
  totalPnl: string
  winRate: string
  avgPnl: string
  maxProfit: string
  maxLoss: string
}

/**
 * 账户仓位信息
 */
export interface AccountPosition {
  accountId: number
  accountName?: string
  walletAddress: string
  proxyAddress: string
  marketId: string
  marketTitle?: string
  marketSlug?: string  // 显示用的 slug
  eventSlug?: string  // 跳转用的 slug（从 events[0].slug 获取）
  marketIcon?: string  // 市场图标 URL
  side: string  // 结果名称（如 "YES", "NO", "Pakistan" 等）
  outcomeIndex?: number  // 结果索引（0, 1, 2...），用于计算 tokenId
  quantity: string  // 显示用的数量（可能被截位）
  originalQuantity?: string  // 原始数量（保留完整精度，用于100%出售）
  avgPrice: string
  currentPrice: string
  currentValue: string
  initialValue: string
  pnl: string
  percentPnl: string
  realizedPnl?: string
  percentRealizedPnl?: string
  redeemable: boolean
  mergeable: boolean
  endDate?: string
  isCurrent: boolean  // true: 当前仓位（有持仓），false: 历史仓位（已平仓）
}

/**
 * 仓位列表响应
 */
export interface PositionListResponse {
  currentPositions: AccountPosition[]
  historyPositions: AccountPosition[]
}

/**
 * 仓位卖出请求
 */
export interface PositionSellRequest {
  accountId: number
  marketId: string
  side: string  // 结果名称（如 "YES", "NO", "Pakistan" 等）
  outcomeIndex?: number  // 结果索引（0, 1, 2...），用于计算 tokenId（推荐提供）
  orderType: 'MARKET' | 'LIMIT'
  quantity?: string  // 卖出数量（可选，手动输入时使用）
  percent?: string  // 卖出百分比（可选，BigDecimal字符串，支持小数，0-100之间，选择百分比按钮时使用）
  price?: string  // 限价订单必需
}

/**
 * 仓位卖出响应
 */
export interface PositionSellResponse {
  orderId: string
  marketId: string
  side: string
  orderType: string
  quantity: string
  price?: string
  status: string
  createdAt: number
}

/**
 * 市场价格请求
 */
export interface MarketPriceRequest {
  marketId: string
}

/**
 * 市场当前价格响应
 */
export interface MarketPriceResponse {
  marketId: string
  currentPrice: string
}

/**
 * 仓位推送消息类型
 */
export type PositionPushMessageType = 'FULL' | 'INCREMENTAL'

/**
 * 仓位推送消息
 */
export interface PositionPushMessage {
  type: PositionPushMessageType  // 消息类型：FULL（全量）或 INCREMENTAL（增量）
  timestamp: number  // 消息时间戳
  currentPositions?: AccountPosition[]  // 当前仓位列表（全量或增量）
  historyPositions?: AccountPosition[]  // 历史仓位列表（全量或增量）
  removedPositionKeys?: string[]  // 已删除的仓位键（仅增量推送时使用）
}

/**
 * 获取仓位唯一键
 */
export function getPositionKey(position: AccountPosition): string {
  return `${position.accountId}-${position.marketId}-${position.side}`
}

/**
 * Polymarket 订单消息（来自 WebSocket User Channel）
 */
export interface OrderMessage {
  asset_id: string
  associate_trades?: string[]
  event_type: string  // "order"
  id: string  // order id
  market: string  // condition ID of market
  order_owner: string  // owner of order
  original_size: string  // original order size
  outcome: string  // outcome
  owner: string  // owner of orders
  price: string  // price of order
  side: string  // BUY/SELL
  size_matched: string  // size of order that has been matched
  timestamp: string  // time of event
  type: string  // PLACEMENT/UPDATE/CANCELLATION
}

/**
 * 订单详情（通过 API 获取）
 * price 为订单限价，avgFilledPrice 为平均成交价（有成交时优先用于展示）
 */
export interface OrderDetail {
  id: string  // 订单 ID
  market: string  // 市场 ID (condition ID)
  side: string  // BUY/SELL
  price: string  // 订单限价
  size: string  // 订单大小
  filled: string  // 已成交数量
  status: string  // 订单状态
  createdAt: string  // 创建时间（ISO 8601 格式）
  marketName?: string  // 市场名称
  marketSlug?: string  // 市场 slug
  marketIcon?: string  // 市场图标
  avgFilledPrice?: string  // 平均成交价（有成交时优先展示）
}

/**
 * 订单推送消息
 */
export interface OrderPushMessage {
  accountId: number
  accountName: string
  order: OrderMessage  // 订单信息（来自 WebSocket）
  orderDetail?: OrderDetail  // 订单详情（通过 API 获取）
  timestamp?: number  // 推送时间戳
  // 跟单相关字段（可选，仅在跟单触发的订单时提供）
  leaderName?: string  // Leader 名称（备注）
  configName?: string  // 跟单配置名
}

/**
 * 账户赎回仓位项（包含账户ID）
 */
export interface AccountRedeemPositionItem {
  accountId: number
  marketId: string
  outcomeIndex: number
  side?: string
}

/**
 * 仓位赎回请求（支持多账户）
 */
export interface PositionRedeemRequest {
  positions: AccountRedeemPositionItem[]
}

/**
 * 赎回的仓位信息
 */
export interface RedeemedPositionInfo {
  marketId: string
  side: string
  outcomeIndex: number
  quantity: string
  value: string
}

/**
 * 账户赎回交易信息
 */
export interface AccountRedeemTransaction {
  accountId: number
  accountName?: string
  transactionHash: string
  positions: RedeemedPositionInfo[]
}

/**
 * 仓位赎回响应
 */
export interface PositionRedeemResponse {
  transactions: AccountRedeemTransaction[]
  totalRedeemedValue: string
  createdAt: number
}

/**
 * 可赎回仓位信息
 */
export interface RedeemablePositionInfo {
  accountId: number
  accountName?: string
  marketId: string
  marketTitle?: string
  side: string
  outcomeIndex: number
  quantity: string
  value: string
}

/**
 * 可赎回仓位统计响应
 */
export interface RedeemablePositionsSummary {
  totalCount: number
  totalValue: string
  positions: RedeemablePositionInfo[]
}

/**
 * 跟单关系统计信息
 */
export interface CopyTradingStatistics {
  copyTradingId: number
  accountId: number
  accountName: string | null
  leaderId: number
  leaderName: string | null
  enabled: boolean
  
  // 买入统计
  totalBuyQuantity: string
  totalBuyOrders: number
  totalBuyAmount: string
  avgBuyPrice: string
  
  // 卖出统计
  totalSellQuantity: string
  totalSellOrders: number
  totalSellAmount: string
  
  // 持仓统计
  currentPositionQuantity: string
  currentPositionCost: string
  currentPositionValue: string  // 按当前价格估算的持仓市值
  zeroValuePositionCost?: string
  confirmedZeroValuePositionCost?: string
  quoteOverallStatus?: 'AVAILABLE' | 'NO_MATCH' | 'UNAVAILABLE'
  quoteAvailableCount?: number
  quoteNoMatchCount?: number
  quoteUnavailableCount?: number
  quoteIncomplete?: boolean
  riskDiagnosis?: CopyTradingRiskDiagnosis | null
  
  // 盈亏统计
  totalRealizedPnl: string
  totalUnrealizedPnl: string
  totalPnl: string
  totalPnlPercent: string
}

export interface CopyTradingRiskDiagnosis {
  copyTradingId: number
  totalRealizedPnl: string
  totalUnrealizedPnl: string
  totalPnl: string
  currentPositionCost: string
  currentPositionValue: string
  zeroValuePositionCost: string
  confirmedZeroValuePositionCost: string
  zeroSellLoss: string
  openPositionQuantity: string
  totalBuyOrders: number
  totalSellRecords: number
  totalMatchDetails: number
  filteredOrderCount: number
  sampleSize: number
  lowConfidence: boolean
  confidenceReason: string
  quoteOverallStatus: 'AVAILABLE' | 'NO_MATCH' | 'UNAVAILABLE'
  quoteAvailableCount: number
  quoteNoMatchCount: number
  quoteUnavailableCount: number
  dataIncomplete: boolean
  missingSources: string[]
  topLosingMarkets: Array<{
    marketId: string
    realizedPnl: string
    matchedOrders: number
  }>
  riskWarnings: Array<{
    field: string
    currentValue: string | null
    suggestedValue: string
    severity: 'LOW' | 'MEDIUM' | 'HIGH'
    reason: string
  }>
  generatedAt: number
}

/**
 * 买入订单信息
 */
export interface BuyOrderInfo {
  orderId: string
  leaderTradeId: string
  marketId: string
  marketTitle?: string  // 市场名称
  marketSlug?: string  // 市场 slug（用于显示）
  eventSlug?: string  // 跳转用的 slug（从 events[0].slug 获取）
  marketCategory?: string  // 市场分类（sports, crypto 等）
  side: string
  quantity: string
  price: string
  amount: string
  matchedQuantity: string
  remainingQuantity: string
  status: 'filled' | 'partially_matched' | 'fully_matched'
  createdAt: number
}

/**
 * 卖出订单信息
 */
export interface SellOrderInfo {
  orderId: string
  leaderTradeId: string
  marketId: string
  marketTitle?: string  // 市场名称
  marketSlug?: string  // 市场 slug（用于显示）
  eventSlug?: string  // 跳转用的 slug（从 events[0].slug 获取）
  marketCategory?: string  // 市场分类（sports, crypto 等）
  side: string
  quantity: string
  price: string
  amount: string
  realizedPnl: string
  status?: string  // 卖出状态（filled, partially_matched, fully_matched）
  createdAt: number
}

/**
 * 匹配订单信息
 */
export interface MatchedOrderInfo {
  sellOrderId: string
  buyOrderId: string
  marketId?: string  // 市场ID
  marketTitle?: string  // 市场名称
  marketSlug?: string  // 市场 slug（用于显示）
  eventSlug?: string  // 跳转用的 slug（从 events[0].slug 获取）
  marketCategory?: string  // 市场分类（sports, crypto 等）
  matchedQuantity: string
  buyPrice: string
  sellPrice: string
  realizedPnl: string
  matchedAt: number
}

/**
 * 订单跟踪列表响应
 */
export interface OrderTrackingListResponse {
  list: BuyOrderInfo[] | SellOrderInfo[] | MatchedOrderInfo[]
  total: number
  page: number
  limit: number
}

/**
 * 订单跟踪查询请求
 */
export interface OrderTrackingRequest {
  copyTradingId: number
  type: 'buy' | 'sell' | 'matched'
  page?: number
  limit?: number
  marketId?: string
  marketTitle?: string
  status?: string
  sellOrderId?: string
  buyOrderId?: string
}

/**
 * 按市场分组的订单查询请求
 */
export interface MarketGroupedOrdersRequest {
  copyTradingId: number
  type: 'buy' | 'sell'
  page?: number
  limit?: number
  marketId?: string
  marketTitle?: string
}

/**
 * 单个市场的订单统计信息
 */
export interface MarketOrderStats {
  count: number
  totalAmount: string  // 总金额
  totalPnl?: string  // 总盈亏（买入订单未实现盈亏，此字段为空）
  fullyMatched: boolean  // 是否全部成交
  fullyMatchedCount: number  // 完全成交的订单数
  partiallyMatchedCount: number  // 部分成交的订单数
  filledCount: number  // 未成交的订单数
}

/**
 * 单个市场分组的响应数据
 */
export interface MarketOrderGroup {
  marketId: string
  marketTitle?: string
  marketSlug?: string  // 显示用的 slug
  eventSlug?: string  // 跳转用的 slug（从 events[0].slug 获取）
  marketCategory?: string
  stats: MarketOrderStats
  orders: BuyOrderInfo[] | SellOrderInfo[]  // 订单列表
}

/**
 * 按市场分组的订单列表响应
 */
export interface MarketGroupedOrdersResponse {
  list: MarketOrderGroup[]
  total: number  // 市场总数
  page: number
  limit: number
}

/**
 * 被过滤订单信息
 */
export interface FilteredOrder {
  id: number
  copyTradingId: number
  accountId: number
  accountName?: string
  leaderId: number
  leaderName?: string
  leaderTradeId: string
  marketId: string
  marketTitle?: string
  marketSlug?: string
  side: 'BUY' | 'SELL'
  outcomeIndex?: number
  outcome?: string
  price: string
  size: string
  calculatedQuantity?: string
  filterReason: string
  filterType: string
  createdAt: number
}

/**
 * 被过滤订单列表请求
 */
export interface FilteredOrderListRequest {
  copyTradingId: number
  filterType?: string
  page?: number
  limit?: number
  startTime?: number
  endTime?: number
}

/**
 * 被过滤订单列表响应
 */
export interface FilteredOrderListResponse {
  list: FilteredOrder[]
  total: number
  page: number
  limit: number
}

/**
 * 消息推送配置
 */
export interface NotificationConfig {
  id?: number
  type: string  // telegram、discord、slack 等
  name: string  // 配置名称
  enabled: boolean  // 是否启用
  config: {
    botToken?: string  // Telegram Bot Token
    chatIds?: string[]  // Telegram Chat IDs
    [key: string]: any  // 其他配置字段
  }
  createdAt?: number
  updatedAt?: number
}

/**
 * 通知配置请求
 */
export interface NotificationConfigRequest {
  type: string
  name: string
  enabled?: boolean
  config: {
    botToken?: string
    chatIds?: string[] | string  // 支持数组或逗号分隔的字符串
    [key: string]: any
  }
}

/**
 * 通知配置更新请求
 */
/**
 * 系统配置响应
 */
export interface SystemConfig {
  builderApiKeyConfigured: boolean
  builderSecretConfigured: boolean
  builderPassphraseConfigured: boolean
  builderApiKeyDisplay?: string  // Builder API Key 显示值（完整）
  builderSecretDisplay?: string  // Builder Secret 显示值（完整）
  builderPassphraseDisplay?: string  // Builder Passphrase 显示值（完整）
  autoRedeemEnabled: boolean  // 自动赎回（系统级别配置，默认开启）
  // Chainlink Data Streams（crypto-tail 障碍模式价源）
  chainlinkApiKeyConfigured?: boolean
  chainlinkApiSecretConfigured?: boolean
  chainlinkApiKeyDisplay?: string
  chainlinkRestBase?: string
  chainlinkFeedBtc?: string
  chainlinkFeedEth?: string
  chainlinkFeedSol?: string
  chainlinkFeedXrp?: string
  cryptoTailPriceSource?: string  // 障碍模式价源：RTDS（默认，免凭证）| CHAINLINK（自建直连）
}

/**
 * Builder API Key 更新请求
 */
export interface BuilderApiKeyUpdateRequest {
  builderApiKey?: string
  builderSecret?: string
  builderPassphrase?: string
}

/**
 * Chainlink Data Streams 配置更新请求
 */
export interface ChainlinkConfigUpdateRequest {
  apiKey?: string
  apiSecret?: string
  restBase?: string
  feedBtc?: string
  feedEth?: string
  feedSol?: string
  feedXrp?: string
  priceSource?: string  // 障碍模式价源：RTDS（默认，免凭证）| CHAINLINK（自建直连）
}

export interface NotificationConfigUpdateRequest {
  id: number
  type: string
  name: string
  enabled?: boolean
  config: {
    botToken?: string
    chatIds?: string[] | string
    [key: string]: any
  }
}


/**
 * RPC 节点配置类型
 */
export interface RpcNodeConfig {
  id: number
  providerType: 'ALCHEMY' | 'INFURA' | 'QUICKNODE' | 'CHAINSTACK' | 'GETBLOCK' | 'CUSTOM' | 'PUBLIC'
  name: string
  httpUrl: string
  wsUrl?: string
  apiKeyMasked?: string  // 脱敏后的 API Key
  enabled: boolean
  priority: number
  lastCheckTime?: number
  lastCheckStatus?: 'HEALTHY' | 'UNHEALTHY' | 'UNKNOWN'
  responseTimeMs?: number
  createdAt: number
  updatedAt: number
}

/**
 * 添加 RPC 节点请求
 */
export interface RpcNodeAddRequest {
  providerType: string
  name: string
  apiKey?: string  // 主流服务商需要
  httpUrl?: string  // CUSTOM 需要
  wsUrl?: string
}

/**
 * 更新 RPC 节点请求
 */
export interface RpcNodeUpdateRequest {
  id: number
  name?: string
  enabled?: boolean
  priority?: number
}

/**
 * 节点健康检查结果
 */
export interface NodeCheckResult {
  status: 'HEALTHY' | 'UNHEALTHY' | 'UNKNOWN'
  message: string
  checkTime: number
  responseTimeMs?: number
  blockNumber?: string
}

/**
 * 回测任务 DTO
 */
export interface BacktestTaskDto {
  id: number
  taskName: string
  leaderId: number
  leaderName?: string
  leaderAddress?: string
  initialBalance: string
  finalBalance?: string
  profitAmount?: string
  profitRate?: string
  backtestDays: number
  startTime: number
  endTime?: number
  status: string  // PENDING/RUNNING/COMPLETED/STOPPED/FAILED
  progress: number
  totalTrades: number
  createdAt: number
  executionStartedAt?: number
  executionFinishedAt?: number
}

/**
 * 加密价差策略
 */
export interface CryptoTailStrategyDto {
  id: number
  accountId: number
  name?: string
  marketSlugPrefix: string
  marketTitle?: string
  intervalSeconds: number
  windowStartSeconds: number
  windowEndSeconds: number
  minPrice: string
  maxPrice: string
  amountMode: string
  amountValue: string
  /** 价差模式: NONE, FIXED, AUTO */
  spreadMode?: string
  /** 价差数值 */
  spreadValue?: string | null
  /** 价差方向: MIN=最小价差（价差>=配置值触发）, MAX=最大价差（价差<=配置值触发） */
  spreadDirection?: string
  enabled: boolean
  /** 障碍（终值概率）模式开关 */
  barrierEnabled?: boolean
  /** 进场胜率阈值 0~1 */
  entryProb?: string
  /** 扣费 EV 边际阈值 */
  entryEdge?: string
  /** 障碍模式最高买入限价 0~1 */
  maxEntryPrice?: string
  /** bestAsk 缺失成本缓冲 */
  costBuffer?: string
  /** 市场隐含概率下限 0~1，0=关 */
  barrierMinMarketProb?: string
  /** σ 校准系数 */
  sigmaScale?: string
  /** 当日已实现亏损熔断阈值 USDC，空=关 */
  dailyLossLimitUsdc?: string | null
  /** 最大并发未结算敞口笔数，空=关 */
  maxConcurrentPositions?: number | null
  /** taker 手续费(基点bps) */
  takerFeeBps?: number
  /** maker 返佣(基点bps) */
  makerRebateBps?: number
  /** 单笔 gas 成本 USDC */
  gasCostUsdc?: string
  /** 进场订单类型: FAK吃单 / MAKER挂单 */
  entryOrderType?: string
  /** FAK 进场限价滑点（V53）：limit = effectiveCost + 此值, 封顶 maxEntryPrice/bracketMaxEntryPrice */
  entryFakSlippage?: string
  exitFakSlippage?: string
  /** maker 挂单相对 bestBid 价格偏移(可负) */
  makerPriceOffset?: string
  /** maker 距结算多少秒未成交触发撤单决策 */
  makerCancelBeforeSettleSeconds?: number
  /** maker 到期未成交是否回退 FAK */
  makerFallbackTaker?: boolean
  /** 放量闸开关 */
  calibrationGateEnabled?: boolean
  /** 校准期小额 USDC */
  probeAmountUsdc?: string
  /** 放量达标最少样本数 */
  calibrationMinSamples?: number
  /** 放量达标最大校准误差 */
  calibrationMaxError?: string
  /** σ 估计方法: MAD/EWMA/GARMAN_KLASS */
  sigmaMethod?: string
  /** EWMA 衰减系数 λ */
  ewmaLambda?: string
  /** 分数 Kelly 动态仓位开关 */
  kellyEnabled?: boolean
  /** Kelly 分数 0~1 */
  kellyFraction?: string
  /** 是否允许同账户同 market/period/outcome 重复开仓 */
  allowDuplicateMarketPosition?: boolean
  /** Strong Gap Boost（强价差放量，V60）总开关 */
  enableStrongGapBoost?: boolean
  /** Boost shadow 模式：只记日志不真正放大 */
  strongGapBoostShadow?: boolean
  /** 一档放量最小 pWin */
  strongGapMinPwin?: string
  /** 一档放量最小 safeRatio */
  strongGapMinSafeRatio?: string
  /** 一档放量倍数 */
  strongGapStakeMultiplier?: string
  /** 二档放量最小 pWin */
  ultraGapMinPwin?: string
  /** 二档放量最小 safeRatio */
  ultraGapMinSafeRatio?: string
  /** 二档放量倍数 */
  ultraGapStakeMultiplier?: string
  /** 放量倍数总上限 */
  maxStrongGapStakeMultiplier?: string
  /** 放量后单笔金额上限 USDC（空=不额外限制） */
  maxBoostedAmountUsdc?: string
  /** 放量后同周期累计敞口上限 USDC（空=不额外限制） */
  maxBoostedPeriodExposureUsdc?: string
  /** 是否允许在 Kelly 之上叠加放量 */
  allowBoostWithKelly?: boolean
  /** 交易模式（V52）: 0=LEGACY_SPREAD 旧价差, 1=BARRIER_HOLD 障碍, 2=BRACKET_DYNAMIC 概率阶梯止盈 */
  mode?: number
  /** 阶梯模式入场胜率阈值 */
  bracketEntryProb?: string
  /** 阶梯模式入场扣费 EV 阈值 */
  bracketEntryEdge?: string
  /** 阶梯模式入场最高买价 */
  bracketMaxEntryPrice?: string
  /** 止盈1: bestBid 阈值 */
  tp1Price?: string
  /** 止盈1: 卖出剩余比例 0~1 */
  tp1Ratio?: string
  /** 止盈1跳过条件: pWin>=此值则不卖 */
  tp1HoldPwin?: string
  /** 止盈2: bestBid 阈值 */
  tp2Price?: string
  /** 止盈2: 卖出剩余比例 0~1 */
  tp2Ratio?: string
  /** 止盈2跳过条件: pWin>=此值则不卖 */
  tp2HoldPwin?: string
  /** 持有到结算的 pWin 阈值 */
  holdToSettlePwin?: string
  /** 持有到结算的剩余秒数阈值 */
  holdToSettleSeconds?: number
  /** 止损 pWin 阈值 */
  stopProb?: string
  /** 止损价格阈值 */
  stopPrice?: string
  /** 距结算 N 秒未触发任何 exit 则强制平仓 */
  forceExitBeforeSettleSeconds?: number
  /** 退出订单类型: FAK / MAKER */
  exitOrderType?: string
  minSafeRatio?: string
  minSafeRatioUp?: string
  minSafeRatioDown?: string
  highPriceThreshold?: string
  highPriceMinPWin?: string
  highPriceMinSafeRatio?: string
  enableExitManager?: boolean
  maxLossPct?: string
  exitPWin?: string
  exitSafeRatio?: string
  exitConfirmTicks?: number
  takeProfitDelta1?: string
  takeProfitSellPct1?: string
  takeProfitBid2?: string
  takeProfitSellPct2?: string
  enableSmartHardStop?: boolean
  emergencyExitOnModelFlip?: boolean
  emergencyExitOnGapFlip?: boolean
  exitPollIntervalMs?: number
  enableWickFilter?: boolean
  wickFilterMode?: string
  wickLookbackMinutes?: number
  wickMinBodyRatio?: string
  wickRejectionRatio?: string
  wickMaWindow?: number
  wickEntryBlockScore?: number
  wickExitScore?: number
  wickHoldProfitScore?: number
  wickUseBinanceVolume?: boolean
  wickVolumeSpikeRatio?: string
  wickMinTicksPerCandle?: number
  wickMinRangeSigmaRatio?: string
  wickClosePositionUpMax?: string
  wickClosePositionDownMin?: string
  maxHoldTp1DelaySeconds?: number
  holdTp1PeakDrawdown?: string
  maxEntrySpread?: string
  maxOrderbookAgeMs?: number
  maxPriceAgeMs?: number
  minRemainingSeconds?: number
  maxRemainingSeconds?: number
  minExitBidDepthUsdc?: string
  maxExitSpread?: string
  enableTrailingStop?: boolean
  trailingStartDelta?: string
  trailingDrawdown?: string
  trailingSellPct?: string
  maxOrdersPerDay?: number | null
  maxConsecutiveLosses?: number | null
  pauseAfterLossMinutes?: number
  lastTriggerAt?: number
  /** 已实现总收益 USDC */
  totalRealizedPnl?: string
  settledCount?: number
  winCount?: number
  /** 胜率 0~1 */
  winRate?: string
  // ===== 尾盘价差模式（TAIL_DIFF, V62）=====
  tailDiffDirection?: number
  tailDiffWindowStartSeconds?: number
  tailDiffWindowEndSeconds?: number
  tailDiffMinRemainingSeconds?: number
  tailDiffConfirmTicks?: number
  tailDiffMinPrice?: string
  tailDiffMaxPrice?: string
  tailDiffHardMaxPrice?: string
  tailDiffMinModelProb?: string
  tailDiffMinEdge?: string
  tailDiffCostBuffer?: string
  tailDiffMinDiffSigma?: string
  tailDiffModelProbSource?: string
  tailDiffStatsMinSamples?: number
  tailDiffStatsLookbackDays?: number
  tailDiffStatsDataSource?: string
  tailDiffMaxSpread?: string
  tailDiffDepthMultiplier?: string
  tailDiffMaxOrderbookAgeMs?: number
  tailDiffMaxPriceAgeMs?: number
  tailDiffReverseVelocityWindowSeconds?: number
  tailDiffMaxReverseVelocitySigma?: string
  tailDiffWeightDiff?: number
  tailDiffWeightTime?: number
  tailDiffWeightOddsUnderprice?: number
  tailDiffWeightOddsLag?: number
  tailDiffWeightHistory?: number
  tailDiffWeightBook?: number
  tailDiffWeightData?: number
  tailDiffMinEntryScore?: number
  tailDiffPremiumScore?: number
  tailDiffTopScore?: number
  tailDiffBaseAmount?: string
  tailDiffTierNormalMult?: string
  tailDiffTierPremiumMult?: string
  tailDiffTierTopMult?: string
  tailDiffMaxAmountPerOrder?: string
  tailDiffExitPresetNormalJson?: string
  tailDiffExitPresetPremiumJson?: string
  tailDiffExitPresetTopJson?: string
  tailDiffDailyLossLimitUsdc?: string
  tailDiffConsecLossPauseCount?: number
  tailDiffConsecLossStopCount?: number
  tailDiffEntrySegmentsJson?: string
  // ===== 尾盘价差评分增强（V72）=====
  tailDiffOddsLagMode?: string
  tailDiffOddsLagWindowSeconds?: number
  tailDiffOddsLagStrongEdgeBypass?: boolean
  tailDiffLagPriceMoveFullScaleSigma?: string
  tailDiffLagOddsMoveFullScale?: string
  tailDiffEdgeFullScale?: string
  tailDiffLagFullScale?: string
  tailDiffHistoryProbFloor?: string
  tailDiffHistoryProbCeil?: string
  tailDiffSigmaScoreMultiple?: string
  tailDiffEnableKellyCap?: boolean
  tailDiffKellyFraction?: string
  tailDiffDepthFillRatio?: string
  // ===== 快进快出模式（SCALP_FLIP, V77）=====
  scalpEntryMinPrice?: string
  scalpEntryMaxPrice?: string
  scalpMaxFillPrice?: string
  scalpWindowStartSeconds?: number
  scalpWindowEndSeconds?: number
  scalpMinRemainingSeconds?: number
  scalpMinExitBidDepthUsdc?: string | null
  scalpReversalGateEnabled?: boolean
  scalpMinModelProb?: string
  scalpMinEdge?: string
  scalpStatsSource?: string
  scalpStatsLookbackDays?: number
  scalpStatsMinSamples?: number
  scalpRequireStats?: boolean
  scalpMaxConcurrentSameDirection?: number | null
  scalpHoldWinnerToSettle?: boolean
  scalpTpPrice?: string
  scalpStopEnabled?: boolean
  scalpStopOffset?: string
  scalpStopMinPrice?: string
  scalpMinOddsAfterEntry?: string
  scalpUnderlyingStopEnabled?: boolean
  scalpUnderlyingStopSigma?: string
  scalpReverseVelocityStopEnabled?: boolean
  scalpMaxReverseVelocitySigma?: string
  scalpReverseVelocityWindowSeconds?: number
  scalpMinModelProbAfterEntry?: string
  scalpMaxDiffRetracePct?: string
  createdAt: number
  updatedAt: number
}

/** 快进快出模式（SCALP_FLIP, V77）参数集合；create/update 共用 */
export interface CryptoTailScalpParams {
  scalpEntryMinPrice?: string
  scalpEntryMaxPrice?: string
  scalpMaxFillPrice?: string
  scalpWindowStartSeconds?: number
  scalpWindowEndSeconds?: number
  scalpMinRemainingSeconds?: number
  scalpMinExitBidDepthUsdc?: string | null
  scalpReversalGateEnabled?: boolean
  scalpMinModelProb?: string
  scalpMinEdge?: string
  scalpStatsSource?: string
  scalpStatsLookbackDays?: number
  scalpStatsMinSamples?: number
  scalpRequireStats?: boolean
  scalpMaxConcurrentSameDirection?: number | null
  scalpHoldWinnerToSettle?: boolean
  scalpTpPrice?: string
  scalpStopEnabled?: boolean
  scalpStopOffset?: string
  scalpStopMinPrice?: string
  scalpMinOddsAfterEntry?: string
  scalpUnderlyingStopEnabled?: boolean
  scalpUnderlyingStopSigma?: string
  scalpReverseVelocityStopEnabled?: boolean
  scalpMaxReverseVelocitySigma?: string
  scalpReverseVelocityWindowSeconds?: number
  scalpMinModelProbAfterEntry?: string
  scalpMaxDiffRetracePct?: string
}

/** 尾盘价差模式（TAIL_DIFF, V62）参数集合；create/update 共用 */
export interface CryptoTailTailDiffParams {
  tailDiffDirection?: number
  tailDiffWindowStartSeconds?: number
  tailDiffWindowEndSeconds?: number
  tailDiffMinRemainingSeconds?: number
  tailDiffConfirmTicks?: number
  tailDiffMinPrice?: string
  tailDiffMaxPrice?: string
  tailDiffHardMaxPrice?: string
  tailDiffMinModelProb?: string
  tailDiffMinEdge?: string
  tailDiffCostBuffer?: string
  tailDiffMinDiffSigma?: string
  tailDiffModelProbSource?: string
  tailDiffStatsMinSamples?: number
  tailDiffStatsLookbackDays?: number
  tailDiffStatsDataSource?: string
  tailDiffMaxSpread?: string
  tailDiffDepthMultiplier?: string
  tailDiffMaxOrderbookAgeMs?: number
  tailDiffMaxPriceAgeMs?: number
  tailDiffReverseVelocityWindowSeconds?: number
  tailDiffMaxReverseVelocitySigma?: string
  tailDiffWeightDiff?: number
  tailDiffWeightTime?: number
  tailDiffWeightOddsUnderprice?: number
  tailDiffWeightOddsLag?: number
  tailDiffWeightHistory?: number
  tailDiffWeightBook?: number
  tailDiffWeightData?: number
  tailDiffMinEntryScore?: number
  tailDiffPremiumScore?: number
  tailDiffTopScore?: number
  tailDiffBaseAmount?: string
  tailDiffTierNormalMult?: string
  tailDiffTierPremiumMult?: string
  tailDiffTierTopMult?: string
  tailDiffMaxAmountPerOrder?: string
  tailDiffExitPresetNormalJson?: string
  tailDiffExitPresetPremiumJson?: string
  tailDiffExitPresetTopJson?: string
  tailDiffDailyLossLimitUsdc?: string
  tailDiffConsecLossPauseCount?: number
  tailDiffConsecLossStopCount?: number
  tailDiffEntrySegmentsJson?: string
  // ===== 尾盘价差评分增强（V72）=====
  tailDiffOddsLagMode?: string
  tailDiffOddsLagWindowSeconds?: number
  tailDiffOddsLagStrongEdgeBypass?: boolean
  tailDiffLagPriceMoveFullScaleSigma?: string
  tailDiffLagOddsMoveFullScale?: string
  tailDiffEdgeFullScale?: string
  tailDiffLagFullScale?: string
  tailDiffHistoryProbFloor?: string
  tailDiffHistoryProbCeil?: string
  tailDiffSigmaScoreMultiple?: string
  tailDiffEnableKellyCap?: boolean
  tailDiffKellyFraction?: string
  tailDiffDepthFillRatio?: string
}

/** 历史反转回填请求（samplingSeconds：1=尾盘 1s 细采样，60=1m 默认） */
export interface ReversalBackfillRequest {
  coin: string
  intervalSeconds: number
  lookbackDays: number
  samplingSeconds?: number
}

/** 历史反转回填响应 */
export interface ReversalBackfillResponse {
  coin: string
  intervalSeconds: number
  lookbackDays: number
  samplingSeconds: number
  periodsProcessed: number
  observations: number
  bucketsWritten: number
}

/** TAIL_DIFF 参数建议请求 */
export interface TailDiffAdvisorRequest {
  strategyId: number
  minSamples?: number
}

/** 参数建议分桶统计行 */
export interface TailDiffAdvisorBucket {
  bucket: string
  sampleCount: number
  winCount: number
  winRate: string
  totalPnl: string
  avgPnl: string
}

/** 单条参数建议 */
export interface TailDiffAdvisorRecommendation {
  param: string
  labelKey: string
  currentValue: string
  suggestedValue: string
  changed: boolean
  sampleCount: number
  confidence: string
  rationale: string
}

/** 参数建议响应（仅推荐，不自动写入） */
export interface TailDiffAdvisorResponse {
  strategyId: number
  totalSettled: number
  winCount: number
  winRate: string
  totalPnl: string
  avgPnl: string
  sufficientSamples: boolean
  minSamples: number
  scoreBuckets: TailDiffAdvisorBucket[]
  priceBuckets: TailDiffAdvisorBucket[]
  diffSigmaBuckets: TailDiffAdvisorBucket[]
  remainingBuckets: TailDiffAdvisorBucket[]
  tierBuckets: TailDiffAdvisorBucket[]
  recommendations: TailDiffAdvisorRecommendation[]
}

/** Polymarket 历史反转回填请求（PoC） */
export interface PolymarketReversalBackfillRequest {
  coin: string
  intervalSeconds: number
  lookbackDays: number
  maxPeriods?: number
}

/** Polymarket 历史反转回填响应（PoC） */
export interface PolymarketReversalBackfillResponse {
  coin: string
  intervalSeconds: number
  lookbackDays: number
  periodsRequested: number
  periodsResolved: number
  observations: number
  bucketsWritten: number
  dataSource: string
  /** 诊断分类计数：定位"为什么没数据" */
  slugNotFound: number
  historyEmpty: number
  tooFewPoints: number
  fetchError: number
  /** 覆盖范围：仍有未采集周期（pending>0）时为 true；coverageDays 为已覆盖天数 */
  coverageCapped: boolean
  coverageDays: number
  /** 增量进度：本次复用缓存 / 新联网采集 / 受预算限制尚未采集（再次执行可续跑） */
  reused: number
  newlyFetched: number
  pending: number
}

/** 历史反转研究列表请求 */
export interface ReversalResearchListRequest {
  coin: string
  intervalSeconds: number
  lookbackDays: number
  dataSource?: string
}

/** 单个分桶反转统计 */
export interface ReversalStatDto {
  coin: string
  intervalSeconds: number
  outcomeIndex: number
  diffSigmaBucket: string
  oddsBucket: string
  remainingBucket: string
  lookbackDays: number
  dataSource: string
  sampleCount: number
  reversedCount: number
  modelProb: string
  reversalRate: string
  samplingSeconds: number
  distinctPeriodCount: number
  maeAvg: string
  mfeAvg: string
  virtualTpRate: string
  virtualStopRate: string
  virtualWinRate: string
  virtualPnlAvg: string
  computedAt: number
}

/** 历史反转研究列表响应 */
export interface ReversalResearchListResponse {
  list: ReversalStatDto[]
  total: number
}

/** 历史反转 CSV 导出响应 */
export interface ReversalResearchCsvResponse {
  filename: string
  csv: string
  total: number
}

/** 尾盘价差评分预览请求（以实盘为准：仅需策略 ID + 评估方向，其余取实时盘口/余额/价源） */
export interface CryptoTailTailDiffPreviewRequest {
  strategyId: number
  /** 评估方向 0=Up 1=Down */
  outcomeIndex?: number
}

/** 尾盘价差评分预览响应 */
export interface CryptoTailTailDiffPreviewResponse {
  /** 实盘决策结果：BUY/WATCH/SKIP */
  outcome: string
  /** 决策原因（解释为何未入场） */
  reason: string
  score: number
  tier?: string | null
  passed: boolean
  vetoes: string[]
  componentWeighted: Record<string, string>
  componentRaw: Record<string, string>
  diffSigma: string
  rawDiff: string
  diffPct: string
  modelSide: number
  effectiveCost: string
  edge: string
  midImpliedProb: string
  modelProb: string
  modelProbSource: string
  recommendedAmountUsdc: string
  limitPriceCap: string
  exitPresetNormal?: Record<string, unknown> | null
  exitPresetPremium?: Record<string, unknown> | null
  exitPresetTop?: Record<string, unknown> | null
}

/** 全链路决策日志事件 */
export interface CryptoTailDecisionEventDto {
  id: number
  strategyId: number
  /** 策略名（跨策略汇总页展示用；单策略查询可能为 null） */
  strategyName?: string | null
  periodStartUnix: number
  correlationId: string
  /** EVAL_STARTED/GATE_PASSED/GATE_FAILED/ORDER_SUBMITTED/ORDER_RESULT/SETTLED */
  eventType: string
  /** 闸名: PRICE_RANGE/TIME_WINDOW/SPREAD/DIRECTION/PWIN/MARKET_PROB/EV/RISK_DAILY_LOSS/RISK_CONCURRENCY/ALL */
  gateName?: string | null
  passed?: boolean | null
  reason?: string | null
  /** 决策快照 JSON 字符串 */
  payloadJson?: string | null
  outcomeIndex?: number | null
  triggerId?: number | null
  createdAt: number
}

/** 决策日志查询请求 */
export interface CryptoTailDecisionLogListRequest {
  strategyId: number
  page?: number
  pageSize?: number
  startDate?: number
  endDate?: number
}

/** 决策日志查询响应 */
export interface CryptoTailDecisionLogListResponse {
  list: CryptoTailDecisionEventDto[]
  total: number
}

/** 决策日志导出请求（按时间区间整段导出，strategyId<=0 表示全部策略） */
export interface CryptoTailDecisionLogExportRequest {
  strategyId?: number
  startDate?: number
  endDate?: number
}

/** 决策日志导出响应 */
export interface CryptoTailDecisionLogExportResponse {
  list: CryptoTailDecisionEventDto[]
  total: number
  exportedAt: number
}

/** 周期生命周期汇总查询请求（startDate/endDate 毫秒，strategyId<=0 表示全部策略） */
export interface CryptoTailPeriodSummaryListRequest {
  strategyId: number
  page?: number
  pageSize?: number
  startDate?: number
  endDate?: number
}

/** 周期生命周期汇总 DTO */
export interface CryptoTailPeriodSummaryDto {
  id: number
  strategyId: number
  strategyName?: string | null
  periodStartUnix: number
  periodEndUnix: number
  marketSlug?: string | null
  firstChosenOutcomeIndex?: number | null
  lastChosenOutcomeIndex?: number | null
  directionFlipCount: number
  bestScore: number
  dominantVeto?: string | null
  scoreEventCount: number
  skipEventCount: number
  buyEventCount: number
  traded: boolean
  triggerId?: number | null
  officialOpen?: string | null
  officialClose?: string | null
  officialGap?: string | null
  settledWinnerOutcomeIndex?: number | null
  directionCorrect?: boolean | null
  realizedPnl?: string | null
  status: string
  settledAt?: number | null
  createdAt: number
  updatedAt: number
}

/** 周期汇总分页响应 + 方向准确率汇总 */
export interface CryptoTailPeriodSummaryListResponse {
  list: CryptoTailPeriodSummaryDto[]
  total: number
  settledCount: number
  directionCorrectCount: number
  tradedCount: number
  directionAccuracy?: string | null
}

/** 决策日志批量删除请求（按 id 集合） */
export interface CryptoTailDecisionLogBatchDeleteRequest {
  ids: number[]
}

/** 决策日志按时间清理请求（strategyId<=0 表示全部策略，beforeDate 毫秒） */
export interface CryptoTailDecisionLogPurgeRequest {
  strategyId?: number
  beforeDate: number
}

/** 决策日志删除/清理响应 */
export interface CryptoTailDecisionLogDeleteResponse {
  deleted: number
}

/** 单笔成交全链路分析快照（数值以字符串返回） */
export interface CryptoTailTradeSnapshotDto {
  id: number
  strategyId: number
  triggerId?: number | null
  periodStartUnix: number
  marketSlug?: string | null
  conditionId?: string | null
  outcomeIndex?: number | null
  intervalSeconds: number
  openPrice?: string | null
  entryMarkPrice?: string | null
  entryGap?: string | null
  sigmaPerSqrtS?: string | null
  pWin?: string | null
  safeRatio?: string | null
  modelSide?: number | null
  remainingSecondsAtEntry?: number | null
  bestBid?: string | null
  bestAsk?: string | null
  midPrice?: string | null
  effectiveCost?: string | null
  entryEdge?: string | null
  entryProbThreshold?: string | null
  entryEdgeThreshold?: string | null
  barrierMinMarketProb?: string | null
  sigmaScale?: string | null
  maxEntryPrice?: string | null
  costBuffer?: string | null
  orderType?: string | null
  targetPrice?: string | null
  requestedAmount?: string | null
  submitTs?: number | null
  fillStatus?: string | null
  fillPrice?: string | null
  fillSize?: string | null
  fillAmount?: string | null
  slippage?: string | null
  orderId?: string | null
  execError?: string | null
  settled: boolean
  winnerOutcomeIndex?: number | null
  won?: boolean | null
  realizedPnl?: string | null
  settleTs?: number | null
  holdSeconds?: number | null
  finalOpen?: string | null
  finalClose?: string | null
  finalGap?: string | null
  reversed?: boolean | null
  settleSource?: string | null
  lossReason?: string | null
  pwinBucket?: number | null
  createdAt: number
  updatedAt: number
}

/** 单笔成交快照查询请求 */
export interface CryptoTailTradeSnapshotListRequest {
  strategyId: number
  page?: number
  pageSize?: number
  startDate?: number | null
  endDate?: number | null
}

/** 单笔成交快照查询响应 */
export interface CryptoTailTradeSnapshotListResponse {
  list: CryptoTailTradeSnapshotDto[]
  total: number
}

/** 单笔成交快照导出请求 */
export interface CryptoTailTradeSnapshotExportRequest {
  strategyId: number
  startDate?: number | null
  endDate?: number | null
}

/** 单笔成交快照 CSV 导出响应 */
export interface CryptoTailTradeSnapshotExportResponse {
  filename: string
  csv: string
  total: number
}

/** 自动最小价差计算响应 */
export interface CryptoTailAutoMinSpreadResponse {
  minSpreadUp: string
  minSpreadDown: string
}

/**
 * 加密价差策略触发记录
 */
export interface CryptoTailStrategyTriggerDto {
  id: number
  strategyId: number
  periodStartUnix: number
  marketTitle?: string
  outcomeIndex: number
  triggerPrice: string
  amountUsdc: string
  /** 实际成交份额(shares)，未成交为空 */
  filledSize?: string
  /** 实际成交金额 USDC，未成交为空 */
  filledAmount?: string
  /** 订单类型: FAK / GTC_POST_ONLY 等 */
  orderType?: string
  orderId?: string
  status: string
  failReason?: string
  resolved?: boolean
  /** 已实现盈亏 USDC（结算后有值） */
  realizedPnl?: string
  winnerOutcomeIndex?: number
  settledAt?: number
  createdAt: number
  /** 触发时模式: 0=LEGACY_SPREAD, 1=BARRIER_HOLD, 2=BRACKET_DYNAMIC */
  mode?: number
  /** 阶梯模式剩余仓位（shares） */
  remainingSize?: string
  /** 阶梯模式仓位状态: NONE/OPEN/PARTIAL_EXIT/FULLY_EXITED/HELD_TO_SETTLE */
  exitStatus?: string
}

/** 阶梯模式单条退出明细 */
export interface CryptoTailStrategyExitDto {
  id: number
  triggerId: number
  strategyId: number
  /** 退出类型: TP1/TP2/STOP/FORCE/SETTLE */
  exitKind: string
  targetSize: string
  filledSize?: string | null
  filledAmount?: string | null
  exitPrice?: string | null
  orderId?: string | null
  orderType?: string | null
  /** pending/success/failed/cancelled/unfilled */
  status: string
  pwinAtDecision?: string | null
  bestBidAtDecision?: string | null
  remainingSeconds?: number | null
  decisionReason?: string | null
  failReason?: string | null
  createdAt: number
  settledAt?: number | null
}

/** 阶梯模式退出明细查询响应 */
export interface CryptoTailStrategyExitListResponse {
  triggerId: number
  list: CryptoTailStrategyExitDto[]
}

/** 收益曲线请求 */
export interface CryptoTailPnlCurveRequest {
  strategyId: number
  startDate?: number
  endDate?: number
}

/** 校准请求 */
export interface CryptoTailCalibrationRequest {
  strategyId: number
}

/** 可靠性分箱单元 */
export interface CryptoTailCalibrationBin {
  bucket: number
  rangeLow: string
  rangeHigh: string
  sampleCount: number
  predictedProb: string
  actualWinRate: string
  netPnl: string
}

/** 校准统计响应 */
export interface CryptoTailCalibrationResponse {
  strategyId: number
  sampleCount: number
  winRate?: string | null
  calibrationError?: string | null
  totalNetPnl: string
  avgNetPnl?: string | null
  gateEnabled: boolean
  qualified: boolean
  /** PROBE 小额 / FULL 正常 */
  scalingMode: string
  probeAmountUsdc: string
  minSamples: number
  maxError: string
  reason?: string | null
  bins: CryptoTailCalibrationBin[]
}

/** sigmaScale 自动校准推荐请求 */
export interface CryptoTailRecommendSigmaScaleRequest {
  strategyId: number
}

/** sigmaScale 自动校准推荐响应 */
export interface CryptoTailRecommendSigmaScaleResponse {
  strategyId: number
  sampleCount: number
  minSamples: number
  enough: boolean
  currentSigmaScale: string
  recommendedSigmaScale?: string | null
  currentError?: string | null
  recommendedError?: string | null
  sigmaMethod: string
  reason: string
}

/** 收益曲线单点 */
export interface CryptoTailPnlCurvePoint {
  timestamp: number
  cumulativePnl: string
  pointPnl: string
  settledCount: number
}

/** 收益曲线响应 */
export interface CryptoTailPnlCurveResponse {
  strategyId: number
  strategyName: string
  totalRealizedPnl: string
  settledCount: number
  winCount: number
  winRate: string | null
  maxDrawdown: string | null
  curveData: CryptoTailPnlCurvePoint[]
}

/**
 * 加密价差策略市场选项
 */
export interface CryptoTailMarketOptionDto {
  slug: string
  title: string
  intervalSeconds: number
  periodStartUnix: number
  endDate?: string
}

/**
 * 加密价差策略监控初始化响应
 */
export interface CryptoTailMonitorInitResponse {
  /** 策略ID */
  strategyId: number
  /** 策略名称 */
  name: string
  /** 账户ID */
  accountId: number
  /** 账户名称 */
  accountName: string
  /** 市场 slug 前缀 */
  marketSlugPrefix: string
  /** 市场标题 */
  marketTitle: string
  /** 周期秒数 (300=5m, 900=15m) */
  intervalSeconds: number
  /** 当前周期开始时间 (Unix 秒) */
  periodStartUnix: number
  /** 时间窗口开始秒数 */
  windowStartSeconds: number
  /** 时间窗口结束秒数 */
  windowEndSeconds: number
  /** 最低价格 */
  minPrice: string
  /** 最高价格 */
  maxPrice: string
  /** 最小价差模式: NONE, FIXED, AUTO */
  minSpreadMode: string
  /** 价差方向: MIN（显示周期内最小价差）, MAX（显示周期内最大价差） */
  spreadDirection?: string
  /** 最小价差数值 (FIXED 时有值) */
  minSpreadValue?: string
  /** 自动计算的最小价差 (Up方向) */
  autoMinSpreadUp?: string
  /** 自动计算的最小价差 (Down方向) */
  autoMinSpreadDown?: string
  /** BTC 开盘价 USDC（来自币安 K 线） */
  openPriceBtc?: string
  officialOpen?: string
  officialClose?: string
  officialPriceSource?: string
  officialPriceAgeMs?: number | null
  priceMode?: string
  lastSnapshotAt?: number | null
  lastRealtimeUpdateAt?: number | null
  latestPriceAgeMs?: number | null
  latestSampleTime?: number | null
  priceReadyReason?: string
  coin?: string
  fallbackUsed?: boolean
  legacyOpen?: string
  legacyClose?: string
  legacyPriceSource?: string
  /** Up tokenId */
  tokenIdUp?: string
  /** Down tokenId */
  tokenIdDown?: string
  /** 当前时间 (毫秒时间戳) */
  currentTimestamp: number
  /** 是否启用 */
  enabled: boolean
  /** 投入金额模式: FIXED or RATIO */
  amountMode?: string
  /** 投入金额数值 */
  amountValue?: string
}

/**
 * 加密价差策略监控实时推送数据
 */
export interface CryptoTailMonitorPushData {
  /** 策略ID */
  strategyId: number
  /** 推送时间 (毫秒时间戳) */
  timestamp: number
  /** 当前周期开始时间 (Unix 秒) */
  periodStartUnix: number
  /** 当前周期市场标题（周期切换时更新） */
  marketTitle?: string
  /** 当前价格 (Up方向，来自订单簿) */
  currentPriceUp?: string
  /** 当前价格 (Down方向，来自订单簿) */
  currentPriceDown?: string
  /** 当前价差 (Up方向: 1 - currentPriceUp) */
  spreadUp?: string
  /** 当前价差 (Down方向: currentPriceUp) */
  spreadDown?: string
  /** 最小价差线 (Up方向，USDC) */
  minSpreadLineUp?: string
  /** 最小价差线 (Down方向，USDC) */
  minSpreadLineDown?: string
  /** BTC 开盘价 USDC */
  openPriceBtc?: string
  /** BTC 最新价 USDC */
  currentPriceBtc?: string
  /** BTC 价差 USDC（currentPriceBtc - openPriceBtc） */
  spreadBtc?: string
  officialOpen?: string
  officialClose?: string
  officialPriceSource?: string
  officialPriceAgeMs?: number | null
  priceMode?: string
  lastSnapshotAt?: number | null
  lastRealtimeUpdateAt?: number | null
  latestPriceAgeMs?: number | null
  latestSampleTime?: number | null
  priceReadyReason?: string
  coin?: string
  fallbackUsed?: boolean
  outcomeBestBidUp?: string
  outcomeBestBidDown?: string
  outcomeBestAskUp?: string
  outcomeBestAskDown?: string
  outcomeSpreadUp?: string
  outcomeSpreadDown?: string
  outcomeDirection?: string
  legacyOpen?: string
  legacyClose?: string
  legacyPriceSource?: string
  /** 周期剩余秒数 */
  remainingSeconds: number
  /** 是否在时间窗口内 */
  inTimeWindow: boolean
  /** 是否在价格区间内 (Up方向) */
  inPriceRangeUp: boolean
  /** 是否在价格区间内 (Down方向) */
  inPriceRangeDown: boolean
  /** 是否已触发 */
  triggered: boolean
  /** 触发方向: UP, DOWN, null */
  triggerDirection?: string
  /** 周期是否已结束 */
  periodEnded: boolean
  positionId?: number | null
  positionOutcomeIndex?: number | null
  entryFillPrice?: string | null
  currentBestBid?: string | null
  floatingPnl?: string | null
  peakBid?: string | null
  drawdownFromPeak?: string | null
  stopLossLine?: string | null
  trailingStartLine?: string | null
  trailingStopLine?: string | null
  takeProfitLine1?: string | null
  takeProfitLine2?: string | null
  exitReason?: string | null
  wickUpperRatio?: string | null
  wickLowerRatio?: string | null
  wickBodyRatio?: string | null
  wickCloseVsMa?: string | null
  wickClosePosition?: string | null
  wickRangeSigmaRatio?: string | null
  wickTickCount?: number | null
  wickQualityReason?: string | null
  wickReversalScore?: number | null
  wickContinuationScore?: number | null
  wickRejectionSide?: string | null
}

export interface CryptoTailManualOrderResponse {
  /** 是否成功 */
  success: boolean
  /** 订单ID */
  orderId?: string
  /** 提示消息 */
  message: string
  /** 下单详情 */
  orderDetails?: ManualOrderDetails
}

export interface ManualOrderDetails {
  /** 策略ID */
  strategyId: number
  /** 方向 */
  direction: string
  /** 下单价格 */
  price: string
  /** 下单数量 */
  size: string
  /** 总金额 */
  totalAmount: string
}

// ==================== 消息模板相关类型 ====================

/**
 * 消息模板
 */
export interface NotificationTemplate {
  id?: number
  templateType: string  // 模板类型
  templateContent: string  // 模板内容
  isDefault: boolean  // 是否使用默认模板
  createdAt?: number
  updatedAt?: number
}

/**
 * 模板类型信息
 */
export interface TemplateTypeInfo {
  type: string  // 模板类型
  name: string  // 类型名称
  description: string  // 类型描述
}

/**
 * 模板变量
 */
export interface TemplateVariable {
  key: string  // 变量名
  category: string  // 分类
  sortOrder: number  // 排序顺序
}

/**
 * 模板变量分类
 */
export interface TemplateVariableCategory {
  key: string  // 分类 key
  sortOrder: number  // 排序顺序
}

/**
 * 模板变量列表响应
 */
export interface TemplateVariablesResponse {
  templateType: string  // 模板类型
  categories: TemplateVariableCategory[]  // 分类列表
  variables: TemplateVariable[]  // 变量列表
}
