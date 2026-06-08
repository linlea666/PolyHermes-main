import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Card,
  Table,
  Button,
  Space,
  Tag,
  Popconfirm,
  Switch,
  message,
  Select,
  Modal,
  Alert,
  Form,
  Input,
  InputNumber,
  Radio,
  Spin,
  Tooltip,
  Tabs,
  DatePicker,
  Empty,
  Typography,
  Upload
} from 'antd'
import type { FormInstance } from 'antd'
import type { Dayjs } from 'dayjs'
import dayjs from 'dayjs'
import { PlusOutlined, EditOutlined, UnorderedListOutlined, LineChartOutlined, InfoCircleOutlined, WarningOutlined, CalendarOutlined, FileTextOutlined, DeleteOutlined, ExportOutlined, ImportOutlined, UploadOutlined, BulbOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { useMediaQuery } from 'react-responsive'
import { apiService } from '../services/api'
import { useAccountStore } from '../store/accountStore'
import type { CryptoTailStrategyDto, CryptoTailStrategyTriggerDto, CryptoTailMarketOptionDto, CryptoTailPnlCurveResponse, CryptoTailDecisionEventDto } from '../types'
import { formatUSDC, formatNumber } from '../utils'
import { getVersionInfo } from '../utils/version'
import CryptoTailPnlCurveModal from './CryptoTailPnlCurveModal'
import CryptoTailReversalResearchModal from './CryptoTailReversalResearchModal'
import CryptoTailTailDiffAdvisorModal from './CryptoTailTailDiffAdvisorModal'
import TailDiffEntrySegmentsField from '../components/TailDiffEntrySegmentsField'
import TailDiffExitPresetField from '../components/TailDiffExitPresetField'
import type { CryptoTailTailDiffParams, CryptoTailScalpParams, CryptoTailTailDiffPreviewResponse } from '../types'

/** 尾盘价差模式（TAIL_DIFF, V62）表单默认值，与后端 V62 SQL / 实体默认保持一致 */
const TAIL_DIFF_DEFAULTS = {
  tailDiffDirection: 0,
  tailDiffWindowStartSeconds: 150,
  tailDiffWindowEndSeconds: 60,
  tailDiffMinRemainingSeconds: 50,
  tailDiffConfirmTicks: 2,
  tailDiffMinPrice: '0.88',
  tailDiffMaxPrice: '0.93',
  tailDiffHardMaxPrice: '0.94',
  tailDiffMinModelProb: '0.95',
  tailDiffMinEdge: '0.025',
  tailDiffCostBuffer: '0.01',
  tailDiffMinDiffSigma: '1.8',
  tailDiffModelProbSource: 'HYBRID',
  tailDiffStatsMinSamples: 50,
  tailDiffStatsLookbackDays: 180,
  tailDiffStatsDataSource: 'BINANCE',
  tailDiffMaxSpread: '0.02',
  tailDiffDepthMultiplier: '3.0',
  tailDiffMaxOrderbookAgeMs: 5000,
  tailDiffMaxPriceAgeMs: 6000,
  tailDiffReverseVelocityWindowSeconds: 10,
  tailDiffMaxReverseVelocitySigma: '0.30',
  tailDiffWeightDiff: 25,
  tailDiffWeightTime: 15,
  tailDiffWeightOddsUnderprice: 20,
  tailDiffWeightOddsLag: 10,
  tailDiffWeightHistory: 15,
  tailDiffWeightBook: 10,
  tailDiffWeightData: 5,
  tailDiffMinEntryScore: 70,
  tailDiffPremiumScore: 80,
  tailDiffTopScore: 90,
  tailDiffBaseAmount: '1',
  tailDiffTierNormalMult: '1.0',
  tailDiffTierPremiumMult: '1.5',
  tailDiffTierTopMult: '2.0',
  tailDiffMaxAmountPerOrder: '5',
  tailDiffExitPresetNormalJson: '',
  tailDiffExitPresetPremiumJson: '',
  tailDiffExitPresetTopJson: '',
  tailDiffDailyLossLimitUsdc: undefined as string | undefined,
  tailDiffConsecLossPauseCount: 2,
  tailDiffConsecLossStopCount: 3,
  tailDiffEntrySegmentsJson: '',
  // ===== 评分增强（V72）：默认值 = 后端默认（零回归）=====
  tailDiffOddsLagMode: 'STATIC',
  tailDiffOddsLagWindowSeconds: 5,
  tailDiffOddsLagStrongEdgeBypass: false,
  tailDiffLagPriceMoveFullScaleSigma: '0.5',
  tailDiffLagOddsMoveFullScale: '0.05',
  tailDiffEdgeFullScale: '0.10',
  tailDiffLagFullScale: '0.15',
  tailDiffHistoryProbFloor: '0.90',
  tailDiffHistoryProbCeil: '1.00',
  tailDiffSigmaScoreMultiple: '1.8',
  tailDiffEnableKellyCap: false,
  tailDiffKellyFraction: '0.10',
  tailDiffDepthFillRatio: '0'
}

/** 快进快出模式（SCALP_FLIP, V77）表单默认值，与后端 V77 SQL / 实体默认保持一致 */
const SCALP_DEFAULTS = {
  scalpEntryMinPrice: '0.96',
  scalpEntryMaxPrice: '0.97',
  scalpMaxFillPrice: '0.975',
  scalpWindowStartSeconds: 0,
  scalpWindowEndSeconds: 0,
  scalpMinRemainingSeconds: 30,
  scalpMinExitBidDepthUsdc: undefined as string | undefined,
  scalpReversalGateEnabled: true,
  scalpMinModelProb: '0.95',
  scalpMinEdge: '0',
  scalpStatsSource: 'HYBRID',
  scalpStatsLookbackDays: 180,
  scalpStatsMinSamples: 30,
  scalpRequireStats: false,
  scalpMaxConcurrentSameDirection: undefined as number | undefined,
  scalpHoldWinnerToSettle: true,
  scalpTpPrice: '0.99',
  scalpStopEnabled: true,
  scalpStopOffset: '0.05',
  scalpStopMinPrice: '0.90',
  scalpMinOddsAfterEntry: '0.93',
  scalpUnderlyingStopEnabled: true,
  scalpUnderlyingStopSigma: '0.30',
  scalpReverseVelocityStopEnabled: true,
  scalpMaxReverseVelocitySigma: '0.40',
  scalpReverseVelocityWindowSeconds: 10,
  scalpMinModelProbAfterEntry: '0',
  scalpMaxDiffRetracePct: '0',
  scalpCatastropheBidFloor: '0.88',
  scalpCatastropheImmediate: true,
  scalpRequireUnderlyingAgreement: true,
  scalpEntryMinPwin: '0.90',
  scalpSmartStopMinPwin: '0.70'
}

/** 编辑态：用 record 已存值回填表单，缺失走默认值 */
const buildScalpFormValues = (record: CryptoTailStrategyDto): typeof SCALP_DEFAULTS => ({
  scalpEntryMinPrice: record.scalpEntryMinPrice ?? SCALP_DEFAULTS.scalpEntryMinPrice,
  scalpEntryMaxPrice: record.scalpEntryMaxPrice ?? SCALP_DEFAULTS.scalpEntryMaxPrice,
  scalpMaxFillPrice: record.scalpMaxFillPrice ?? SCALP_DEFAULTS.scalpMaxFillPrice,
  scalpWindowStartSeconds: record.scalpWindowStartSeconds ?? SCALP_DEFAULTS.scalpWindowStartSeconds,
  scalpWindowEndSeconds: record.scalpWindowEndSeconds ?? SCALP_DEFAULTS.scalpWindowEndSeconds,
  scalpMinRemainingSeconds: record.scalpMinRemainingSeconds ?? SCALP_DEFAULTS.scalpMinRemainingSeconds,
  scalpMinExitBidDepthUsdc: record.scalpMinExitBidDepthUsdc ?? SCALP_DEFAULTS.scalpMinExitBidDepthUsdc,
  scalpReversalGateEnabled: record.scalpReversalGateEnabled ?? SCALP_DEFAULTS.scalpReversalGateEnabled,
  scalpMinModelProb: record.scalpMinModelProb ?? SCALP_DEFAULTS.scalpMinModelProb,
  scalpMinEdge: record.scalpMinEdge ?? SCALP_DEFAULTS.scalpMinEdge,
  scalpStatsSource: record.scalpStatsSource ?? SCALP_DEFAULTS.scalpStatsSource,
  scalpStatsLookbackDays: record.scalpStatsLookbackDays ?? SCALP_DEFAULTS.scalpStatsLookbackDays,
  scalpStatsMinSamples: record.scalpStatsMinSamples ?? SCALP_DEFAULTS.scalpStatsMinSamples,
  scalpRequireStats: record.scalpRequireStats ?? SCALP_DEFAULTS.scalpRequireStats,
  scalpMaxConcurrentSameDirection: record.scalpMaxConcurrentSameDirection ?? SCALP_DEFAULTS.scalpMaxConcurrentSameDirection,
  scalpHoldWinnerToSettle: record.scalpHoldWinnerToSettle ?? SCALP_DEFAULTS.scalpHoldWinnerToSettle,
  scalpTpPrice: record.scalpTpPrice ?? SCALP_DEFAULTS.scalpTpPrice,
  scalpStopEnabled: record.scalpStopEnabled ?? SCALP_DEFAULTS.scalpStopEnabled,
  scalpStopOffset: record.scalpStopOffset ?? SCALP_DEFAULTS.scalpStopOffset,
  scalpStopMinPrice: record.scalpStopMinPrice ?? SCALP_DEFAULTS.scalpStopMinPrice,
  scalpMinOddsAfterEntry: record.scalpMinOddsAfterEntry ?? SCALP_DEFAULTS.scalpMinOddsAfterEntry,
  scalpUnderlyingStopEnabled: record.scalpUnderlyingStopEnabled ?? SCALP_DEFAULTS.scalpUnderlyingStopEnabled,
  scalpUnderlyingStopSigma: record.scalpUnderlyingStopSigma ?? SCALP_DEFAULTS.scalpUnderlyingStopSigma,
  scalpReverseVelocityStopEnabled: record.scalpReverseVelocityStopEnabled ?? SCALP_DEFAULTS.scalpReverseVelocityStopEnabled,
  scalpMaxReverseVelocitySigma: record.scalpMaxReverseVelocitySigma ?? SCALP_DEFAULTS.scalpMaxReverseVelocitySigma,
  scalpReverseVelocityWindowSeconds: record.scalpReverseVelocityWindowSeconds ?? SCALP_DEFAULTS.scalpReverseVelocityWindowSeconds,
  scalpMinModelProbAfterEntry: record.scalpMinModelProbAfterEntry ?? SCALP_DEFAULTS.scalpMinModelProbAfterEntry,
  scalpMaxDiffRetracePct: record.scalpMaxDiffRetracePct ?? SCALP_DEFAULTS.scalpMaxDiffRetracePct,
  scalpCatastropheBidFloor: record.scalpCatastropheBidFloor ?? SCALP_DEFAULTS.scalpCatastropheBidFloor,
  scalpCatastropheImmediate: record.scalpCatastropheImmediate ?? SCALP_DEFAULTS.scalpCatastropheImmediate,
  scalpRequireUnderlyingAgreement: record.scalpRequireUnderlyingAgreement ?? SCALP_DEFAULTS.scalpRequireUnderlyingAgreement,
  scalpEntryMinPwin: record.scalpEntryMinPwin ?? SCALP_DEFAULTS.scalpEntryMinPwin,
  scalpSmartStopMinPwin: record.scalpSmartStopMinPwin ?? SCALP_DEFAULTS.scalpSmartStopMinPwin
})

/** 编辑态：用 record 已存值回填表单，缺失走默认值 */
const buildTailDiffFormValues = (record: CryptoTailStrategyDto): typeof TAIL_DIFF_DEFAULTS => ({
  tailDiffDirection: record.tailDiffDirection ?? TAIL_DIFF_DEFAULTS.tailDiffDirection,
  tailDiffWindowStartSeconds: record.tailDiffWindowStartSeconds ?? TAIL_DIFF_DEFAULTS.tailDiffWindowStartSeconds,
  tailDiffWindowEndSeconds: record.tailDiffWindowEndSeconds ?? TAIL_DIFF_DEFAULTS.tailDiffWindowEndSeconds,
  tailDiffMinRemainingSeconds: record.tailDiffMinRemainingSeconds ?? TAIL_DIFF_DEFAULTS.tailDiffMinRemainingSeconds,
  tailDiffConfirmTicks: record.tailDiffConfirmTicks ?? TAIL_DIFF_DEFAULTS.tailDiffConfirmTicks,
  tailDiffMinPrice: record.tailDiffMinPrice ?? TAIL_DIFF_DEFAULTS.tailDiffMinPrice,
  tailDiffMaxPrice: record.tailDiffMaxPrice ?? TAIL_DIFF_DEFAULTS.tailDiffMaxPrice,
  tailDiffHardMaxPrice: record.tailDiffHardMaxPrice ?? TAIL_DIFF_DEFAULTS.tailDiffHardMaxPrice,
  tailDiffMinModelProb: record.tailDiffMinModelProb ?? TAIL_DIFF_DEFAULTS.tailDiffMinModelProb,
  tailDiffMinEdge: record.tailDiffMinEdge ?? TAIL_DIFF_DEFAULTS.tailDiffMinEdge,
  tailDiffCostBuffer: record.tailDiffCostBuffer ?? TAIL_DIFF_DEFAULTS.tailDiffCostBuffer,
  tailDiffMinDiffSigma: record.tailDiffMinDiffSigma ?? TAIL_DIFF_DEFAULTS.tailDiffMinDiffSigma,
  tailDiffModelProbSource: record.tailDiffModelProbSource ?? TAIL_DIFF_DEFAULTS.tailDiffModelProbSource,
  tailDiffStatsMinSamples: record.tailDiffStatsMinSamples ?? TAIL_DIFF_DEFAULTS.tailDiffStatsMinSamples,
  tailDiffStatsLookbackDays: record.tailDiffStatsLookbackDays ?? TAIL_DIFF_DEFAULTS.tailDiffStatsLookbackDays,
  tailDiffStatsDataSource: record.tailDiffStatsDataSource ?? TAIL_DIFF_DEFAULTS.tailDiffStatsDataSource,
  tailDiffMaxSpread: record.tailDiffMaxSpread ?? TAIL_DIFF_DEFAULTS.tailDiffMaxSpread,
  tailDiffDepthMultiplier: record.tailDiffDepthMultiplier ?? TAIL_DIFF_DEFAULTS.tailDiffDepthMultiplier,
  tailDiffMaxOrderbookAgeMs: record.tailDiffMaxOrderbookAgeMs ?? TAIL_DIFF_DEFAULTS.tailDiffMaxOrderbookAgeMs,
  tailDiffMaxPriceAgeMs: record.tailDiffMaxPriceAgeMs ?? TAIL_DIFF_DEFAULTS.tailDiffMaxPriceAgeMs,
  tailDiffReverseVelocityWindowSeconds: record.tailDiffReverseVelocityWindowSeconds ?? TAIL_DIFF_DEFAULTS.tailDiffReverseVelocityWindowSeconds,
  tailDiffMaxReverseVelocitySigma: record.tailDiffMaxReverseVelocitySigma ?? TAIL_DIFF_DEFAULTS.tailDiffMaxReverseVelocitySigma,
  tailDiffWeightDiff: record.tailDiffWeightDiff ?? TAIL_DIFF_DEFAULTS.tailDiffWeightDiff,
  tailDiffWeightTime: record.tailDiffWeightTime ?? TAIL_DIFF_DEFAULTS.tailDiffWeightTime,
  tailDiffWeightOddsUnderprice: record.tailDiffWeightOddsUnderprice ?? TAIL_DIFF_DEFAULTS.tailDiffWeightOddsUnderprice,
  tailDiffWeightOddsLag: record.tailDiffWeightOddsLag ?? TAIL_DIFF_DEFAULTS.tailDiffWeightOddsLag,
  tailDiffWeightHistory: record.tailDiffWeightHistory ?? TAIL_DIFF_DEFAULTS.tailDiffWeightHistory,
  tailDiffWeightBook: record.tailDiffWeightBook ?? TAIL_DIFF_DEFAULTS.tailDiffWeightBook,
  tailDiffWeightData: record.tailDiffWeightData ?? TAIL_DIFF_DEFAULTS.tailDiffWeightData,
  tailDiffMinEntryScore: record.tailDiffMinEntryScore ?? TAIL_DIFF_DEFAULTS.tailDiffMinEntryScore,
  tailDiffPremiumScore: record.tailDiffPremiumScore ?? TAIL_DIFF_DEFAULTS.tailDiffPremiumScore,
  tailDiffTopScore: record.tailDiffTopScore ?? TAIL_DIFF_DEFAULTS.tailDiffTopScore,
  tailDiffBaseAmount: record.tailDiffBaseAmount ?? TAIL_DIFF_DEFAULTS.tailDiffBaseAmount,
  tailDiffTierNormalMult: record.tailDiffTierNormalMult ?? TAIL_DIFF_DEFAULTS.tailDiffTierNormalMult,
  tailDiffTierPremiumMult: record.tailDiffTierPremiumMult ?? TAIL_DIFF_DEFAULTS.tailDiffTierPremiumMult,
  tailDiffTierTopMult: record.tailDiffTierTopMult ?? TAIL_DIFF_DEFAULTS.tailDiffTierTopMult,
  tailDiffMaxAmountPerOrder: record.tailDiffMaxAmountPerOrder ?? TAIL_DIFF_DEFAULTS.tailDiffMaxAmountPerOrder,
  tailDiffExitPresetNormalJson: record.tailDiffExitPresetNormalJson ?? TAIL_DIFF_DEFAULTS.tailDiffExitPresetNormalJson,
  tailDiffExitPresetPremiumJson: record.tailDiffExitPresetPremiumJson ?? TAIL_DIFF_DEFAULTS.tailDiffExitPresetPremiumJson,
  tailDiffExitPresetTopJson: record.tailDiffExitPresetTopJson ?? TAIL_DIFF_DEFAULTS.tailDiffExitPresetTopJson,
  tailDiffDailyLossLimitUsdc: record.tailDiffDailyLossLimitUsdc ?? TAIL_DIFF_DEFAULTS.tailDiffDailyLossLimitUsdc,
  tailDiffConsecLossPauseCount: record.tailDiffConsecLossPauseCount ?? TAIL_DIFF_DEFAULTS.tailDiffConsecLossPauseCount,
  tailDiffConsecLossStopCount: record.tailDiffConsecLossStopCount ?? TAIL_DIFF_DEFAULTS.tailDiffConsecLossStopCount,
  tailDiffEntrySegmentsJson: record.tailDiffEntrySegmentsJson ?? TAIL_DIFF_DEFAULTS.tailDiffEntrySegmentsJson,
  tailDiffOddsLagMode: record.tailDiffOddsLagMode ?? TAIL_DIFF_DEFAULTS.tailDiffOddsLagMode,
  tailDiffOddsLagWindowSeconds: record.tailDiffOddsLagWindowSeconds ?? TAIL_DIFF_DEFAULTS.tailDiffOddsLagWindowSeconds,
  tailDiffOddsLagStrongEdgeBypass: record.tailDiffOddsLagStrongEdgeBypass ?? TAIL_DIFF_DEFAULTS.tailDiffOddsLagStrongEdgeBypass,
  tailDiffLagPriceMoveFullScaleSigma: record.tailDiffLagPriceMoveFullScaleSigma ?? TAIL_DIFF_DEFAULTS.tailDiffLagPriceMoveFullScaleSigma,
  tailDiffLagOddsMoveFullScale: record.tailDiffLagOddsMoveFullScale ?? TAIL_DIFF_DEFAULTS.tailDiffLagOddsMoveFullScale,
  tailDiffEdgeFullScale: record.tailDiffEdgeFullScale ?? TAIL_DIFF_DEFAULTS.tailDiffEdgeFullScale,
  tailDiffLagFullScale: record.tailDiffLagFullScale ?? TAIL_DIFF_DEFAULTS.tailDiffLagFullScale,
  tailDiffHistoryProbFloor: record.tailDiffHistoryProbFloor ?? TAIL_DIFF_DEFAULTS.tailDiffHistoryProbFloor,
  tailDiffHistoryProbCeil: record.tailDiffHistoryProbCeil ?? TAIL_DIFF_DEFAULTS.tailDiffHistoryProbCeil,
  tailDiffSigmaScoreMultiple: record.tailDiffSigmaScoreMultiple ?? TAIL_DIFF_DEFAULTS.tailDiffSigmaScoreMultiple,
  tailDiffEnableKellyCap: record.tailDiffEnableKellyCap ?? TAIL_DIFF_DEFAULTS.tailDiffEnableKellyCap,
  tailDiffKellyFraction: record.tailDiffKellyFraction ?? TAIL_DIFF_DEFAULTS.tailDiffKellyFraction,
  tailDiffDepthFillRatio: record.tailDiffDepthFillRatio ?? TAIL_DIFF_DEFAULTS.tailDiffDepthFillRatio
})

const numOrUndef = (x: unknown): number | undefined => (x != null && x !== '' ? Number(x) : undefined)
const strOrUndef = (x: unknown): string | undefined => (x != null && x !== '' ? String(x) : undefined)
// 可空字段清空语义：非空发原值，空发 ''（后端三态：''=显式清空置 NULL，undefined=保留旧值）。
const strOrEmpty = (x: unknown): string => (x != null && x !== '' ? String(x) : '')

// 三档退出预设 JSON 的已知顶层段（snake_case 与 camelCase 别名，与后端 TailDiffExitPreset 解析一致）
const EXIT_PRESET_SECTION_KEYS = new Set<string>([
  'hold_to_expiry', 'holdToExpiry',
  'tp_limit', 'tpLimit',
  'stop_loss', 'stopLoss',
  'dynamic_exit', 'dynamicExit',
  'execution'
])

/** 校验退出预设 JSON：空=允许（用默认档）；否则须为对象且至少含一个已知段。返回 true=合法。 */
const isExitPresetJsonValid = (value: unknown): boolean => {
  const raw = value == null ? '' : String(value).trim()
  if (!raw) return true
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return false
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) return false
  return Object.keys(parsed as Record<string, unknown>).some((k) => EXIT_PRESET_SECTION_KEYS.has(k))
}

/** 提交态：把表单值转换为后端 create/update 接受的 TAIL_DIFF 参数 */
const buildTailDiffPayload = (v: Record<string, unknown>): CryptoTailTailDiffParams => ({
  tailDiffDirection: numOrUndef(v.tailDiffDirection),
  tailDiffWindowStartSeconds: numOrUndef(v.tailDiffWindowStartSeconds),
  tailDiffWindowEndSeconds: numOrUndef(v.tailDiffWindowEndSeconds),
  tailDiffMinRemainingSeconds: numOrUndef(v.tailDiffMinRemainingSeconds),
  tailDiffConfirmTicks: numOrUndef(v.tailDiffConfirmTicks),
  tailDiffMinPrice: strOrUndef(v.tailDiffMinPrice),
  tailDiffMaxPrice: strOrUndef(v.tailDiffMaxPrice),
  tailDiffHardMaxPrice: strOrUndef(v.tailDiffHardMaxPrice),
  tailDiffMinModelProb: strOrUndef(v.tailDiffMinModelProb),
  tailDiffMinEdge: strOrUndef(v.tailDiffMinEdge),
  tailDiffCostBuffer: strOrUndef(v.tailDiffCostBuffer),
  tailDiffMinDiffSigma: strOrUndef(v.tailDiffMinDiffSigma),
  tailDiffModelProbSource: strOrUndef(v.tailDiffModelProbSource),
  tailDiffStatsMinSamples: numOrUndef(v.tailDiffStatsMinSamples),
  tailDiffStatsLookbackDays: numOrUndef(v.tailDiffStatsLookbackDays),
  tailDiffStatsDataSource: strOrUndef(v.tailDiffStatsDataSource),
  tailDiffMaxSpread: strOrUndef(v.tailDiffMaxSpread),
  tailDiffDepthMultiplier: strOrUndef(v.tailDiffDepthMultiplier),
  tailDiffMaxOrderbookAgeMs: numOrUndef(v.tailDiffMaxOrderbookAgeMs),
  tailDiffMaxPriceAgeMs: numOrUndef(v.tailDiffMaxPriceAgeMs),
  tailDiffReverseVelocityWindowSeconds: numOrUndef(v.tailDiffReverseVelocityWindowSeconds),
  tailDiffMaxReverseVelocitySigma: strOrUndef(v.tailDiffMaxReverseVelocitySigma),
  tailDiffWeightDiff: numOrUndef(v.tailDiffWeightDiff),
  tailDiffWeightTime: numOrUndef(v.tailDiffWeightTime),
  tailDiffWeightOddsUnderprice: numOrUndef(v.tailDiffWeightOddsUnderprice),
  tailDiffWeightOddsLag: numOrUndef(v.tailDiffWeightOddsLag),
  tailDiffWeightHistory: numOrUndef(v.tailDiffWeightHistory),
  tailDiffWeightBook: numOrUndef(v.tailDiffWeightBook),
  tailDiffWeightData: numOrUndef(v.tailDiffWeightData),
  tailDiffMinEntryScore: numOrUndef(v.tailDiffMinEntryScore),
  tailDiffPremiumScore: numOrUndef(v.tailDiffPremiumScore),
  tailDiffTopScore: numOrUndef(v.tailDiffTopScore),
  tailDiffBaseAmount: strOrUndef(v.tailDiffBaseAmount),
  tailDiffTierNormalMult: strOrUndef(v.tailDiffTierNormalMult),
  tailDiffTierPremiumMult: strOrUndef(v.tailDiffTierPremiumMult),
  tailDiffTierTopMult: strOrUndef(v.tailDiffTierTopMult),
  tailDiffMaxAmountPerOrder: strOrUndef(v.tailDiffMaxAmountPerOrder),
  tailDiffExitPresetNormalJson: strOrEmpty(v.tailDiffExitPresetNormalJson),
  tailDiffExitPresetPremiumJson: strOrEmpty(v.tailDiffExitPresetPremiumJson),
  tailDiffExitPresetTopJson: strOrEmpty(v.tailDiffExitPresetTopJson),
  tailDiffDailyLossLimitUsdc: strOrEmpty(v.tailDiffDailyLossLimitUsdc),
  tailDiffConsecLossPauseCount: numOrUndef(v.tailDiffConsecLossPauseCount),
  tailDiffConsecLossStopCount: numOrUndef(v.tailDiffConsecLossStopCount),
  tailDiffEntrySegmentsJson: strOrEmpty(v.tailDiffEntrySegmentsJson),
  tailDiffOddsLagMode: strOrUndef(v.tailDiffOddsLagMode),
  tailDiffOddsLagWindowSeconds: numOrUndef(v.tailDiffOddsLagWindowSeconds),
  tailDiffOddsLagStrongEdgeBypass: v.tailDiffOddsLagStrongEdgeBypass === true,
  tailDiffLagPriceMoveFullScaleSigma: strOrUndef(v.tailDiffLagPriceMoveFullScaleSigma),
  tailDiffLagOddsMoveFullScale: strOrUndef(v.tailDiffLagOddsMoveFullScale),
  tailDiffEdgeFullScale: strOrUndef(v.tailDiffEdgeFullScale),
  tailDiffLagFullScale: strOrUndef(v.tailDiffLagFullScale),
  tailDiffHistoryProbFloor: strOrUndef(v.tailDiffHistoryProbFloor),
  tailDiffHistoryProbCeil: strOrUndef(v.tailDiffHistoryProbCeil),
  tailDiffSigmaScoreMultiple: strOrUndef(v.tailDiffSigmaScoreMultiple),
  tailDiffEnableKellyCap: typeof v.tailDiffEnableKellyCap === 'boolean' ? v.tailDiffEnableKellyCap : undefined,
  tailDiffKellyFraction: strOrUndef(v.tailDiffKellyFraction),
  tailDiffDepthFillRatio: strOrUndef(v.tailDiffDepthFillRatio)
})

/** 提交态：把表单值转换为后端 create/update 接受的 SCALP_FLIP 参数 */
const buildScalpPayload = (v: Record<string, unknown>): CryptoTailScalpParams => ({
  scalpEntryMinPrice: strOrUndef(v.scalpEntryMinPrice),
  scalpEntryMaxPrice: strOrUndef(v.scalpEntryMaxPrice),
  scalpMaxFillPrice: strOrUndef(v.scalpMaxFillPrice),
  scalpWindowStartSeconds: numOrUndef(v.scalpWindowStartSeconds),
  scalpWindowEndSeconds: numOrUndef(v.scalpWindowEndSeconds),
  scalpMinRemainingSeconds: numOrUndef(v.scalpMinRemainingSeconds),
  // 可空：空发 ''（后端三态显式清空置 NULL）
  scalpMinExitBidDepthUsdc: strOrEmpty(v.scalpMinExitBidDepthUsdc),
  scalpReversalGateEnabled: typeof v.scalpReversalGateEnabled === 'boolean' ? v.scalpReversalGateEnabled : undefined,
  scalpMinModelProb: strOrUndef(v.scalpMinModelProb),
  scalpMinEdge: strOrUndef(v.scalpMinEdge),
  scalpStatsSource: strOrUndef(v.scalpStatsSource),
  scalpStatsLookbackDays: numOrUndef(v.scalpStatsLookbackDays),
  scalpStatsMinSamples: numOrUndef(v.scalpStatsMinSamples),
  scalpRequireStats: typeof v.scalpRequireStats === 'boolean' ? v.scalpRequireStats : undefined,
  // 可空：空发 null（后端 resolveNullableIntUpdate 显式清空）
  scalpMaxConcurrentSameDirection: v.scalpMaxConcurrentSameDirection != null && v.scalpMaxConcurrentSameDirection !== '' ? Number(v.scalpMaxConcurrentSameDirection) : null,
  scalpHoldWinnerToSettle: typeof v.scalpHoldWinnerToSettle === 'boolean' ? v.scalpHoldWinnerToSettle : undefined,
  scalpTpPrice: strOrUndef(v.scalpTpPrice),
  scalpStopEnabled: typeof v.scalpStopEnabled === 'boolean' ? v.scalpStopEnabled : undefined,
  scalpStopOffset: strOrUndef(v.scalpStopOffset),
  scalpStopMinPrice: strOrUndef(v.scalpStopMinPrice),
  scalpMinOddsAfterEntry: strOrUndef(v.scalpMinOddsAfterEntry),
  scalpUnderlyingStopEnabled: typeof v.scalpUnderlyingStopEnabled === 'boolean' ? v.scalpUnderlyingStopEnabled : undefined,
  scalpUnderlyingStopSigma: strOrUndef(v.scalpUnderlyingStopSigma),
  scalpReverseVelocityStopEnabled: typeof v.scalpReverseVelocityStopEnabled === 'boolean' ? v.scalpReverseVelocityStopEnabled : undefined,
  scalpMaxReverseVelocitySigma: strOrUndef(v.scalpMaxReverseVelocitySigma),
  scalpReverseVelocityWindowSeconds: numOrUndef(v.scalpReverseVelocityWindowSeconds),
  scalpMinModelProbAfterEntry: strOrUndef(v.scalpMinModelProbAfterEntry),
  scalpMaxDiffRetracePct: strOrUndef(v.scalpMaxDiffRetracePct),
  scalpCatastropheBidFloor: strOrUndef(v.scalpCatastropheBidFloor),
  scalpCatastropheImmediate: typeof v.scalpCatastropheImmediate === 'boolean' ? v.scalpCatastropheImmediate : undefined,
  scalpRequireUnderlyingAgreement: typeof v.scalpRequireUnderlyingAgreement === 'boolean' ? v.scalpRequireUnderlyingAgreement : undefined,
  scalpEntryMinPwin: strOrUndef(v.scalpEntryMinPwin),
  scalpSmartStopMinPwin: strOrUndef(v.scalpSmartStopMinPwin)
})

/** 从市场 slug 推断币种（与后端 CryptoTailCoinResolver 一致：仅 BTC/ETH 有反转研究数据） */
const inferCoinFromSlug = (slug?: string): 'BTC' | 'ETH' | null => {
  const s = (slug ?? '').toLowerCase()
  if (s.includes('btc')) return 'BTC'
  if (s.includes('eth')) return 'ETH'
  return null
}

/**
 * 反转研究数据覆盖提示：TAIL_DIFF 策略保存/编辑时，按当前 coin/周期/回溯天数/数据源 查询是否已有回填数据。
 * 无数据 → 警示 HYBRID 将退化为解析解；非 BTC/ETH → 提示无统计数据。复用 reversal/list，不新建后端管线。
 */
const TailDiffStatsCoverageAlert: React.FC<{ form: FormInstance; marketOptions: CryptoTailMarketOptionDto[] }> = ({ form, marketOptions }) => {
  const { t } = useTranslation()
  const mode = Form.useWatch('mode', form)
  const marketSlugPrefix = Form.useWatch('marketSlugPrefix', form) as string | undefined
  const lookbackDays = Form.useWatch('tailDiffStatsLookbackDays', form) as number | undefined
  const statsDataSource = Form.useWatch('tailDiffStatsDataSource', form) as string | undefined
  const modelProbSource = Form.useWatch('tailDiffModelProbSource', form) as string | undefined
  const [status, setStatus] = useState<'idle' | 'loading' | 'ok' | 'empty'>('idle')

  const coin = inferCoinFromSlug(marketSlugPrefix)
  const intervalSeconds = marketOptions.find((m) => m.slug === marketSlugPrefix)?.intervalSeconds
  const active = mode === 3 && (modelProbSource ?? 'HYBRID') !== 'FALLBACK'

  useEffect(() => {
    if (!active || coin == null || !intervalSeconds || !lookbackDays) {
      setStatus('idle')
      return
    }
    let cancelled = false
    setStatus('loading')
    const timer = setTimeout(async () => {
      try {
        const res = await apiService.cryptoTailStrategy.reversalList({
          coin,
          intervalSeconds,
          lookbackDays: Number(lookbackDays),
          dataSource: statsDataSource ?? 'BINANCE'
        })
        if (cancelled) return
        const list = res.data.code === 0 && res.data.data ? res.data.data.list : []
        setStatus(list.length > 0 ? 'ok' : 'empty')
      } catch {
        if (!cancelled) setStatus('idle')
      }
    }, 400)
    return () => {
      cancelled = true
      clearTimeout(timer)
    }
  }, [active, coin, intervalSeconds, lookbackDays, statsDataSource])

  if (!active) return null
  if (coin == null) {
    return (
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message={t('cryptoTailStrategy.form.statsCoverage.coinUnsupported')}
      />
    )
  }
  if (status === 'empty') {
    return (
      <Alert
        type="warning"
        showIcon
        style={{ marginBottom: 12 }}
        message={t('cryptoTailStrategy.form.statsCoverage.noDataTitle')}
        description={t('cryptoTailStrategy.form.statsCoverage.noDataDesc', {
          coin,
          lookback: lookbackDays,
          source: statsDataSource ?? 'BINANCE'
        })}
      />
    )
  }
  if (status === 'ok') {
    return (
      <Alert
        type="success"
        showIcon
        style={{ marginBottom: 12 }}
        message={t('cryptoTailStrategy.form.statsCoverage.ok', {
          coin,
          lookback: lookbackDays,
          source: statsDataSource ?? 'BINANCE'
        })}
      />
    )
  }
  return null
}

/**
 * 反转研究数据覆盖提示（SCALP_FLIP）：开启反转门槛时，按当前 coin/周期/回溯天数/数据源查询是否已有回填数据。
 * scalpStatsSource=HYBRID 时依次探测 POLYMARKET→BINANCE（与后端 HYBRID 查表顺序一致）；命中即提示可用，否则警示。
 * 反转数据为全局共享池，与 TAIL_DIFF 同源，复用 reversal/list，不新建后端管线。
 */
const ScalpStatsCoverageAlert: React.FC<{ form: FormInstance; marketOptions: CryptoTailMarketOptionDto[] }> = ({ form, marketOptions }) => {
  const { t } = useTranslation()
  const mode = Form.useWatch('mode', form)
  const gateEnabled = Form.useWatch('scalpReversalGateEnabled', form) as boolean | undefined
  const marketSlugPrefix = Form.useWatch('marketSlugPrefix', form) as string | undefined
  const lookbackDays = Form.useWatch('scalpStatsLookbackDays', form) as number | undefined
  const statsSource = Form.useWatch('scalpStatsSource', form) as string | undefined
  const [status, setStatus] = useState<'idle' | 'loading' | 'ok' | 'empty'>('idle')
  const [hitSource, setHitSource] = useState<string>('BINANCE')

  const coin = inferCoinFromSlug(marketSlugPrefix)
  const intervalSeconds = marketOptions.find((m) => m.slug === marketSlugPrefix)?.intervalSeconds
  const active = mode === 4 && gateEnabled !== false

  useEffect(() => {
    if (!active || coin == null || !intervalSeconds || !lookbackDays) {
      setStatus('idle')
      return
    }
    let cancelled = false
    setStatus('loading')
    const timer = setTimeout(async () => {
      // HYBRID 解析为依次探测 POLYMARKET→BINANCE，任一命中即可用（reversal/list 仅接受具体源）
      const candidates = (statsSource ?? 'HYBRID') === 'HYBRID' ? ['POLYMARKET', 'BINANCE'] : [statsSource ?? 'BINANCE']
      try {
        for (const ds of candidates) {
          const res = await apiService.cryptoTailStrategy.reversalList({
            coin,
            intervalSeconds,
            lookbackDays: Number(lookbackDays),
            dataSource: ds
          })
          if (cancelled) return
          const list = res.data.code === 0 && res.data.data ? res.data.data.list : []
          if (list.length > 0) {
            setHitSource(ds)
            setStatus('ok')
            return
          }
        }
        if (!cancelled) setStatus('empty')
      } catch {
        if (!cancelled) setStatus('idle')
      }
    }, 400)
    return () => {
      cancelled = true
      clearTimeout(timer)
    }
  }, [active, coin, intervalSeconds, lookbackDays, statsSource])

  if (!active) return null
  if (coin == null) {
    return (
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message={t('cryptoTailStrategy.form.statsCoverage.coinUnsupported')}
      />
    )
  }
  if (status === 'empty') {
    return (
      <Alert
        type="warning"
        showIcon
        style={{ marginBottom: 12 }}
        message={t('cryptoTailStrategy.form.statsCoverage.noDataTitle')}
        description={t('cryptoTailStrategy.form.statsCoverage.noDataDescScalp', {
          coin,
          lookback: lookbackDays,
          source: statsSource ?? 'HYBRID'
        })}
      />
    )
  }
  if (status === 'ok') {
    return (
      <Alert
        type="success"
        showIcon
        style={{ marginBottom: 12 }}
        message={t('cryptoTailStrategy.form.statsCoverage.ok', {
          coin,
          lookback: lookbackDays,
          source: hitSource
        })}
      />
    )
  }
  return null
}

const CryptoTailStrategyList: React.FC = () => {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const { accounts, fetchAccounts } = useAccountStore()
  const [list, setList] = useState<CryptoTailStrategyDto[]>([])
  const [loading, setLoading] = useState(false)
  const [filters, setFilters] = useState<{ accountId?: number; enabled?: boolean }>({})
  const [systemConfig, setSystemConfig] = useState<{ builderApiKeyConfigured?: boolean; autoRedeemEnabled?: boolean } | null>(null)
  const [redeemModalOpen, setRedeemModalOpen] = useState(false)
  const [formModalOpen, setFormModalOpen] = useState(false)
  const [importModalOpen, setImportModalOpen] = useState(false)
  const [importText, setImportText] = useState('')
  const [editingId, setEditingId] = useState<number | null>(null)
  const [recommendingSigma, setRecommendingSigma] = useState(false)
  const [tailDiffPreview, setTailDiffPreview] = useState<CryptoTailTailDiffPreviewResponse | null>(null)
  const [tailDiffPreviewLoading, setTailDiffPreviewLoading] = useState(false)
  const [reversalModalOpen, setReversalModalOpen] = useState(false)
  // 研究弹窗是否处于「采纳」模式（从策略编辑打开，对比后可一键回填该策略的统计字段）
  const [reversalAdoptMode, setReversalAdoptMode] = useState(false)
  const [advisorModalOpen, setAdvisorModalOpen] = useState(false)
  const [advisorStrategyId, setAdvisorStrategyId] = useState<number | null>(null)
  const [advisorStrategyName, setAdvisorStrategyName] = useState<string>('')
  const [marketOptions, setMarketOptions] = useState<CryptoTailMarketOptionDto[]>([])
  const [triggersModalOpen, setTriggersModalOpen] = useState(false)
  const [triggersStrategyId, setTriggersStrategyId] = useState<number | null>(null)
  const [triggerTab, setTriggerTab] = useState<'success' | 'fail' | 'decision'>('success')
  const [triggerDateRange, setTriggerDateRange] = useState<[Dayjs | null, Dayjs | null]>([null, null])
  const [triggerPage, setTriggerPage] = useState(1)
  const [triggerPageSize, setTriggerPageSize] = useState(20)
  const [triggers, setTriggers] = useState<CryptoTailStrategyTriggerDto[]>([])
  const [triggersTotal, setTriggersTotal] = useState(0)
  const [triggersLoading, setTriggersLoading] = useState(false)
  const [decisionEvents, setDecisionEvents] = useState<CryptoTailDecisionEventDto[]>([])
  const [decisionTotal, setDecisionTotal] = useState(0)
  const [decisionLoading, setDecisionLoading] = useState(false)
  const [decisionPage, setDecisionPage] = useState(1)
  const [decisionPageSize, setDecisionPageSize] = useState(20)
  const [form] = Form.useForm()

  const [pnlCurveModalOpen, setPnlCurveModalOpen] = useState(false)
  const [pnlCurveStrategyId, setPnlCurveStrategyId] = useState<number | null>(null)
  const [pnlCurveStrategyName, setPnlCurveStrategyName] = useState('')
  const [pnlCurvePreset, setPnlCurvePreset] = useState<'today' | '7d' | '30d' | 'all'>('all')
  const [pnlCurveCustomRange, setPnlCurveCustomRange] = useState<[Dayjs | null, Dayjs | null]>([null, null])
  const [pnlCurveData, setPnlCurveData] = useState<CryptoTailPnlCurveResponse | null>(null)
  const [pnlCurveLoading, setPnlCurveLoading] = useState(false)

  /** 币安 API 健康状态：仅保留「不可用」的项，用于强提醒 */
  const [binanceUnhealthy, setBinanceUnhealthy] = useState<Array<{ name: string; message: string }>>([])
  const [binanceCheckLoading, setBinanceCheckLoading] = useState(false)

  const BINANCE_API_NAMES = ['币安 API', '币安 WebSocket']

  const fetchBinanceApiStatus = async () => {
    setBinanceCheckLoading(true)
    try {
      const res = await apiService.proxyConfig.checkApiHealth()
      if (res.data.code === 0 && res.data.data?.apis) {
        const unhealthy = res.data.data.apis.filter(
          (api) => BINANCE_API_NAMES.includes(api.name) && api.status !== 'success'
        )
        setBinanceUnhealthy(unhealthy.map((api) => ({ name: api.name, message: api.message })))
      } else {
        setBinanceUnhealthy([])
      }
    } catch {
      setBinanceUnhealthy([{ name: '币安 API', message: '' }, { name: '币安 WebSocket', message: '' }])
    } finally {
      setBinanceCheckLoading(false)
    }
  }

  useEffect(() => {
    fetchAccounts()
    fetchSystemConfig()
    fetchMarketOptions()
  }, [])

  useEffect(() => {
    fetchBinanceApiStatus()
  }, [])

  useEffect(() => {
    fetchList()
  }, [filters])

  const fetchSystemConfig = async () => {
    try {
      const res = await apiService.systemConfig.get()
      if (res.data.code === 0 && res.data.data) {
        setSystemConfig(res.data.data)
      }
    } catch {
      setSystemConfig(null)
    }
  }

  const fetchMarketOptions = async () => {
    try {
      const res = await apiService.cryptoTailStrategy.marketOptions()
      if (res.data.code === 0 && res.data.data) {
        setMarketOptions(res.data.data)
      }
    } catch {
      setMarketOptions([])
    }
  }

  const fetchList = async () => {
    setLoading(true)
    try {
      const res = await apiService.cryptoTailStrategy.list(filters)
      if (res.data.code === 0 && res.data.data) {
        setList(res.data.data.list ?? [])
      } else {
        message.error(res.data.msg || t('cryptoTailStrategy.list.fetchFailed'))
      }
    } catch (e) {
      message.error((e as Error).message || t('cryptoTailStrategy.list.fetchFailed'))
    } finally {
      setLoading(false)
    }
  }

  const openAddModal = () => {
    const needApiKey = !systemConfig?.builderApiKeyConfigured
    const needAutoRedeem = !systemConfig?.autoRedeemEnabled
    if (needApiKey || needAutoRedeem) {
      setRedeemModalOpen(true)
      return
    }
    setEditingId(null)
    form.resetFields()
    form.setFieldsValue({
      enabled: true,
      amountMode: 'RATIO',
      maxPrice: '1',
      spreadMode: 'AUTO',
      spreadDirection: 'MIN',
      windowStartMinutes: 0,
      windowStartSeconds: 0,
      barrierEnabled: false,
      mode: 0,
      entryProb: '0.55',
      entryEdge: '0.02',
      maxEntryPrice: '0.99',
      costBuffer: '0.02',
      barrierMinMarketProb: '0',
      sigmaScale: '1.0',
      dailyLossLimitUsdc: undefined,
      maxConcurrentPositions: undefined,
      takerFeeBps: 0,
      makerRebateBps: 0,
      gasCostUsdc: '0',
      entryOrderType: 'FAK',
      entryFakSlippage: '0.02',
      exitFakSlippage: '0.02',
      makerPriceOffset: '0',
      makerCancelBeforeSettleSeconds: 5,
      makerFallbackTaker: false,
      calibrationGateEnabled: false,
      probeAmountUsdc: '1',
      calibrationMinSamples: 30,
      calibrationMaxError: '0.10',
      sigmaMethod: 'GARMAN_KLASS',
      ewmaLambda: '0.94',
      kellyEnabled: false,
      kellyFraction: '0.25',
      allowDuplicateMarketPosition: false,
      // Strong Gap Boost（V60）默认值：默认关闭、shadow 开启
      enableStrongGapBoost: false,
      strongGapBoostShadow: true,
      strongGapMinPwin: '0.90',
      strongGapMinSafeRatio: '1.50',
      strongGapStakeMultiplier: '1.50',
      ultraGapMinPwin: '0.95',
      ultraGapMinSafeRatio: '2.00',
      ultraGapStakeMultiplier: '2.00',
      maxStrongGapStakeMultiplier: '2.00',
      maxBoostedAmountUsdc: undefined,
      maxBoostedPeriodExposureUsdc: undefined,
      allowBoostWithKelly: false,
      // 阶梯模式默认值（与后端 V52 默认一致）
      bracketEntryProb: '0.80',
      bracketEntryEdge: '0.04',
      bracketMaxEntryPrice: '0.90',
      holdToSettlePwin: '0.97',
      holdToSettleSeconds: 30,
      stopProb: '0.55',
      stopPrice: '0.70',
      forceExitBeforeSettleSeconds: 15,
      exitOrderType: 'FAK',
      minSafeRatio: '1.20',
      minSafeRatioUp: '1.50',
      minSafeRatioDown: '1.20',
      highPriceThreshold: '0.90',
      highPriceMinPWin: '0.97',
      highPriceMinSafeRatio: '2.50',
      enableExitManager: true,
      maxLossPct: '0.20',
      exitPWin: '0.70',
      exitSafeRatio: '0.80',
      exitConfirmTicks: 2,
      takeProfitDelta1: '0.08',
      takeProfitSellPct1: '0.50',
      takeProfitBid2: '0.93',
      takeProfitSellPct2: '0.80',
      enableSmartHardStop: false,
      emergencyExitOnModelFlip: true,
      emergencyExitOnGapFlip: true,
      exitPollIntervalMs: 3000,
      enableWickFilter: true,
      wickFilterMode: 'SHADOW',
      wickLookbackMinutes: 2,
      wickMinBodyRatio: '0.20',
      wickRejectionRatio: '0.55',
      wickMaWindow: 3,
      wickEntryBlockScore: 70,
      wickExitScore: 75,
      wickHoldProfitScore: 65,
      wickUseBinanceVolume: false,
      wickVolumeSpikeRatio: '1.50',
      wickMinTicksPerCandle: 5,
      wickMinRangeSigmaRatio: '0.25',
      wickClosePositionUpMax: '0.35',
      wickClosePositionDownMin: '0.65',
      maxHoldTp1DelaySeconds: 45,
      holdTp1PeakDrawdown: '0.03',
      maxEntrySpread: '0.03',
      maxOrderbookAgeMs: 3000,
      maxPriceAgeMs: 3000,
      minRemainingSeconds: 90,
      maxRemainingSeconds: 420,
      minExitBidDepthUsdc: '2.00',
      maxExitSpread: '0.05',
      enableTrailingStop: true,
      trailingStartDelta: '0.08',
      trailingDrawdown: '0.06',
      trailingSellPct: '1.00',
      maxOrdersPerDay: undefined,
      maxConsecutiveLosses: undefined,
      pauseAfterLossMinutes: 0,
      ...TAIL_DIFF_DEFAULTS,
      ...SCALP_DEFAULTS
    })
    setFormModalOpen(true)
  }

  const openEditModal = (record: CryptoTailStrategyDto) => {
    setEditingId(record.id)
    form.setFieldsValue({
      accountId: record.accountId,
      name: record.name,
      marketSlugPrefix: record.marketSlugPrefix,
      windowStartMinutes: Math.floor(record.windowStartSeconds / 60),
      windowStartSeconds: record.windowStartSeconds % 60,
      windowEndMinutes: Math.floor(record.windowEndSeconds / 60),
      windowEndSeconds: record.windowEndSeconds % 60,
      minPrice: record.minPrice,
      maxPrice: record.maxPrice,
      amountMode: record.amountMode,
      amountValue: record.amountValue,
      spreadMode: record.spreadMode ?? 'AUTO',
      spreadValue: record.spreadValue ?? undefined,
      spreadDirection: record.spreadDirection ?? 'MIN',
      enabled: record.enabled,
      barrierEnabled: record.barrierEnabled ?? false,
      mode: typeof record.mode === 'number' ? record.mode : (record.barrierEnabled ? 1 : 0),
      entryProb: record.entryProb ?? '0.55',
      entryEdge: record.entryEdge ?? '0.02',
      maxEntryPrice: record.maxEntryPrice ?? '0.99',
      costBuffer: record.costBuffer ?? '0.02',
      barrierMinMarketProb: record.barrierMinMarketProb ?? '0',
      sigmaScale: record.sigmaScale ?? '1.0',
      dailyLossLimitUsdc: record.dailyLossLimitUsdc ?? undefined,
      maxConcurrentPositions: record.maxConcurrentPositions ?? undefined,
      takerFeeBps: record.takerFeeBps ?? 0,
      makerRebateBps: record.makerRebateBps ?? 0,
      gasCostUsdc: record.gasCostUsdc ?? '0',
      entryOrderType: record.entryOrderType ?? 'FAK',
      entryFakSlippage: record.entryFakSlippage ?? '0.02',
      exitFakSlippage: record.exitFakSlippage ?? '0.02',
      makerPriceOffset: record.makerPriceOffset ?? '0',
      makerCancelBeforeSettleSeconds: record.makerCancelBeforeSettleSeconds ?? 5,
      makerFallbackTaker: record.makerFallbackTaker ?? false,
      calibrationGateEnabled: record.calibrationGateEnabled ?? false,
      probeAmountUsdc: record.probeAmountUsdc ?? '1',
      calibrationMinSamples: record.calibrationMinSamples ?? 30,
      calibrationMaxError: record.calibrationMaxError ?? '0.10',
      sigmaMethod: record.sigmaMethod ?? 'GARMAN_KLASS',
      ewmaLambda: record.ewmaLambda ?? '0.94',
      kellyEnabled: record.kellyEnabled ?? false,
      kellyFraction: record.kellyFraction ?? '0.25',
      allowDuplicateMarketPosition: record.allowDuplicateMarketPosition ?? false,
      // Strong Gap Boost（V60）
      enableStrongGapBoost: record.enableStrongGapBoost ?? false,
      strongGapBoostShadow: record.strongGapBoostShadow ?? true,
      strongGapMinPwin: record.strongGapMinPwin ?? '0.90',
      strongGapMinSafeRatio: record.strongGapMinSafeRatio ?? '1.50',
      strongGapStakeMultiplier: record.strongGapStakeMultiplier ?? '1.50',
      ultraGapMinPwin: record.ultraGapMinPwin ?? '0.95',
      ultraGapMinSafeRatio: record.ultraGapMinSafeRatio ?? '2.00',
      ultraGapStakeMultiplier: record.ultraGapStakeMultiplier ?? '2.00',
      maxStrongGapStakeMultiplier: record.maxStrongGapStakeMultiplier ?? '2.00',
      maxBoostedAmountUsdc: record.maxBoostedAmountUsdc ?? undefined,
      maxBoostedPeriodExposureUsdc: record.maxBoostedPeriodExposureUsdc ?? undefined,
      allowBoostWithKelly: record.allowBoostWithKelly ?? false,
      // 阶梯模式
      bracketEntryProb: record.bracketEntryProb ?? '0.80',
      bracketEntryEdge: record.bracketEntryEdge ?? '0.04',
      bracketMaxEntryPrice: record.bracketMaxEntryPrice ?? '0.90',
      holdToSettlePwin: record.holdToSettlePwin ?? '0.97',
      holdToSettleSeconds: record.holdToSettleSeconds ?? 30,
      stopProb: record.stopProb ?? '0.55',
      stopPrice: record.stopPrice ?? '0.70',
      forceExitBeforeSettleSeconds: record.forceExitBeforeSettleSeconds ?? 15,
      exitOrderType: record.exitOrderType ?? 'FAK',
      minSafeRatio: record.minSafeRatio ?? '1.20',
      minSafeRatioUp: record.minSafeRatioUp ?? '1.50',
      minSafeRatioDown: record.minSafeRatioDown ?? '1.20',
      highPriceThreshold: record.highPriceThreshold ?? '0.90',
      highPriceMinPWin: record.highPriceMinPWin ?? '0.97',
      highPriceMinSafeRatio: record.highPriceMinSafeRatio ?? '2.50',
      enableExitManager: record.enableExitManager ?? true,
      maxLossPct: record.maxLossPct ?? '0.20',
      exitPWin: record.exitPWin ?? '0.70',
      exitSafeRatio: record.exitSafeRatio ?? '0.80',
      exitConfirmTicks: record.exitConfirmTicks ?? 2,
      takeProfitDelta1: record.takeProfitDelta1 ?? '0.08',
      takeProfitSellPct1: record.takeProfitSellPct1 ?? '0.50',
      takeProfitBid2: record.takeProfitBid2 ?? '0.93',
      takeProfitSellPct2: record.takeProfitSellPct2 ?? '0.80',
      enableSmartHardStop: record.enableSmartHardStop ?? false,
      emergencyExitOnModelFlip: record.emergencyExitOnModelFlip ?? true,
      emergencyExitOnGapFlip: record.emergencyExitOnGapFlip ?? true,
      exitPollIntervalMs: record.exitPollIntervalMs ?? 3000,
      enableWickFilter: record.enableWickFilter ?? true,
      wickFilterMode: record.wickFilterMode ?? 'SHADOW',
      wickLookbackMinutes: record.wickLookbackMinutes ?? 2,
      wickMinBodyRatio: record.wickMinBodyRatio ?? '0.20',
      wickRejectionRatio: record.wickRejectionRatio ?? '0.55',
      wickMaWindow: record.wickMaWindow ?? 3,
      wickEntryBlockScore: record.wickEntryBlockScore ?? 70,
      wickExitScore: record.wickExitScore ?? 75,
      wickHoldProfitScore: record.wickHoldProfitScore ?? 65,
      wickUseBinanceVolume: record.wickUseBinanceVolume ?? false,
      wickVolumeSpikeRatio: record.wickVolumeSpikeRatio ?? '1.50',
      wickMinTicksPerCandle: record.wickMinTicksPerCandle ?? 5,
      wickMinRangeSigmaRatio: record.wickMinRangeSigmaRatio ?? '0.25',
      wickClosePositionUpMax: record.wickClosePositionUpMax ?? '0.35',
      wickClosePositionDownMin: record.wickClosePositionDownMin ?? '0.65',
      maxHoldTp1DelaySeconds: record.maxHoldTp1DelaySeconds ?? 45,
      holdTp1PeakDrawdown: record.holdTp1PeakDrawdown ?? '0.03',
      maxEntrySpread: record.maxEntrySpread ?? '0.03',
      maxOrderbookAgeMs: record.maxOrderbookAgeMs ?? 3000,
      maxPriceAgeMs: record.maxPriceAgeMs ?? 3000,
      minRemainingSeconds: record.minRemainingSeconds ?? 90,
      maxRemainingSeconds: record.maxRemainingSeconds ?? 420,
      minExitBidDepthUsdc: record.minExitBidDepthUsdc ?? '2.00',
      maxExitSpread: record.maxExitSpread ?? '0.05',
      enableTrailingStop: record.enableTrailingStop ?? true,
      trailingStartDelta: record.trailingStartDelta ?? '0.08',
      trailingDrawdown: record.trailingDrawdown ?? '0.06',
      trailingSellPct: record.trailingSellPct ?? '1.00',
      maxOrdersPerDay: record.maxOrdersPerDay ?? undefined,
      maxConsecutiveLosses: record.maxConsecutiveLosses ?? undefined,
      pauseAfterLossMinutes: record.pauseAfterLossMinutes ?? 0,
      ...buildTailDiffFormValues(record),
      ...buildScalpFormValues(record)
    })
    setFormModalOpen(true)
  }

  // 风控一键补全：并发恒为 2（跨周期未结算敞口上限，与"每周期只下单一次"正交）；
  // 日亏熔断仅在固定金额模式下按"金额×5"给出，比例模式无法换算 USDC，不臆造数值
  const fillRiskDefaults = () => {
    const updates: { maxConcurrentPositions: number; dailyLossLimitUsdc?: string } = {
      maxConcurrentPositions: 2
    }
    const amountMode = form.getFieldValue('amountMode')
    const amount = Number(form.getFieldValue('amountValue'))
    if (amountMode === 'FIXED' && Number.isFinite(amount) && amount > 0) {
      updates.dailyLossLimitUsdc = String(amount * 5)
      form.setFieldsValue(updates)
      message.success(t('cryptoTailStrategy.form.riskAutofillDone'))
    } else {
      form.setFieldsValue(updates)
      message.info(t('cryptoTailStrategy.form.riskAutofillNoAmount'))
    }
  }

  // 按已结算样本推荐 sigmaScale：仅编辑态可用（需 strategyId + 已结算样本），推荐后回填由用户保存确认
  const recommendSigmaScale = async () => {
    if (editingId == null) {
      message.info(t('cryptoTailStrategy.form.sigmaRecommendNeedSaved'))
      return
    }
    setRecommendingSigma(true)
    try {
      const res = await apiService.cryptoTailStrategy.recommendSigmaScale({ strategyId: editingId })
      if (res.data.code !== 0 || !res.data.data) {
        message.error(res.data.msg || t('cryptoTailStrategy.form.sigmaRecommendFailed'))
        return
      }
      const data = res.data.data
      if (!data.enough || !data.recommendedSigmaScale) {
        message.warning(data.reason || t('cryptoTailStrategy.form.sigmaRecommendNotEnough'))
        return
      }
      form.setFieldsValue({ sigmaScale: data.recommendedSigmaScale })
      message.success(
        t('cryptoTailStrategy.form.sigmaRecommendDone', {
          scale: data.recommendedSigmaScale,
          before: data.currentError ?? '-',
          after: data.recommendedError ?? '-',
          count: data.sampleCount
        })
      )
    } catch {
      message.error(t('cryptoTailStrategy.form.sigmaRecommendFailed'))
    } finally {
      setRecommendingSigma(false)
    }
  }

  // 尾盘价差评分预览：以实盘为准——后端用该策略当前实时盘口/余额/价源走与实盘一致的决策链算分。
  // 需先保存策略（依赖后端已存阈值），且策略需已启用并处于订阅周期内（否则后端返回"实时盘口未就绪"）。
  const runTailDiffPreview = async () => {
    if (editingId == null) {
      message.info(t('cryptoTailStrategy.form.tailDiffPreviewNeedSaved'))
      return
    }
    const v = form.getFieldsValue()
    setTailDiffPreviewLoading(true)
    try {
      const res = await apiService.cryptoTailStrategy.tailDiffPreview({
        strategyId: editingId,
        outcomeIndex: Number(v.tailDiffPreviewOutcomeIndex ?? 0)
      })
      if (res.data.code !== 0 || !res.data.data) {
        message.error(res.data.msg || t('common.failed'))
        return
      }
      setTailDiffPreview(res.data.data)
    } catch {
      message.error(t('common.failed'))
    } finally {
      setTailDiffPreviewLoading(false)
    }
  }

  const handleFormSubmit = async () => {
    try {
      const v = await form.validateFields()
      // 新建与编辑均按当前选择的市场 slug 取周期，编辑时无 Form.Item 的 intervalSeconds 不会在 v 中
      const interval = marketOptions.find((m) => m.slug === v.marketSlugPrefix)?.intervalSeconds ?? 300
      const windowStartSeconds = (v.windowStartMinutes ?? 0) * 60 + (v.windowStartSeconds ?? 0)
      const windowEndSeconds = (v.windowEndMinutes ?? 0) * 60 + (v.windowEndSeconds ?? 0)
      if (windowStartSeconds > windowEndSeconds) {
        message.error(t('cryptoTailStrategy.form.timeWindowStartLEEnd'))
        return
      }
      const maxWindow = interval
      if (windowEndSeconds > maxWindow) {
        message.error(t('cryptoTailStrategy.form.timeWindowExceed'))
        return
      }
      const resolvedMode: number = typeof v.mode === 'number' ? v.mode : (v.barrierEnabled === true ? 1 : 0)
      const tailDiffOn = resolvedMode === 3
      const scalpOn = resolvedMode === 4
      // 概率/价差等非旧价差模式：旧价格区间/价差闸不生效，由各模式自有阈值管控（SCALP 用 scalp_* 价格区间）
      const probabilityMode = resolvedMode === 1 || resolvedMode === 2 || resolvedMode === 3 || resolvedMode === 4
      const barrierOn = resolvedMode === 1
      const bracketOn = resolvedMode === 2
      // TAIL_DIFF 入场前权重之和必须为 100，前端先校验，避免后端 4xxx 报错难定位
      if (tailDiffOn) {
        const weights = [
          v.tailDiffWeightDiff, v.tailDiffWeightTime, v.tailDiffWeightOddsUnderprice, v.tailDiffWeightOddsLag,
          v.tailDiffWeightHistory, v.tailDiffWeightBook, v.tailDiffWeightData
        ].map((x) => Number(x ?? 0))
        const sum = weights.reduce((a, b) => a + b, 0)
        if (sum !== 100) {
          message.error(t('cryptoTailStrategy.form.tailDiffWeightSumError', { sum }))
          return
        }
      }
      const tailDiffParams = tailDiffOn ? buildTailDiffPayload(v) : {}
      // SCALP_FLIP：退出复用 BRACKET/TAIL_DIFF 引擎，必须开启持仓退出管理器，否则止盈/止损不评估。
      // holdToSettlePwin 仅在 bracketParams 发送，SCALP 需在此补发（enableSmartHardStop/exitPollIntervalMs 已由 barrierParams 无条件发送）。
      const scalpParams = scalpOn
        ? {
            ...buildScalpPayload(v),
            enableExitManager: true,
            holdToSettlePwin: v.holdToSettlePwin != null ? String(v.holdToSettlePwin) : undefined
          }
        : {}
      // 障碍/阶梯模式：旧价格区间/价差闸不生效，统一存默认值
      const bracketParams = bracketOn ? {
        bracketEntryProb: v.bracketEntryProb != null ? String(v.bracketEntryProb) : undefined,
        bracketEntryEdge: v.bracketEntryEdge != null ? String(v.bracketEntryEdge) : undefined,
        bracketMaxEntryPrice: v.bracketMaxEntryPrice != null ? String(v.bracketMaxEntryPrice) : undefined,
        holdToSettlePwin: v.holdToSettlePwin != null ? String(v.holdToSettlePwin) : undefined,
        holdToSettleSeconds: v.holdToSettleSeconds != null ? Number(v.holdToSettleSeconds) : undefined,
        stopProb: v.stopProb != null ? String(v.stopProb) : undefined,
        stopPrice: v.stopPrice != null ? String(v.stopPrice) : undefined,
        forceExitBeforeSettleSeconds: v.forceExitBeforeSettleSeconds != null ? Number(v.forceExitBeforeSettleSeconds) : undefined,
        exitOrderType: v.exitOrderType != null ? String(v.exitOrderType) : undefined
      } : {}
      const barrierParams = {
        mode: resolvedMode,
        barrierEnabled: barrierOn,
        // Strong Gap Boost（强价差放量，V60）
        enableStrongGapBoost: v.enableStrongGapBoost === true,
        strongGapBoostShadow: v.strongGapBoostShadow !== false,
        strongGapMinPwin: v.strongGapMinPwin != null ? String(v.strongGapMinPwin) : undefined,
        strongGapMinSafeRatio: v.strongGapMinSafeRatio != null ? String(v.strongGapMinSafeRatio) : undefined,
        strongGapStakeMultiplier: v.strongGapStakeMultiplier != null ? String(v.strongGapStakeMultiplier) : undefined,
        ultraGapMinPwin: v.ultraGapMinPwin != null ? String(v.ultraGapMinPwin) : undefined,
        ultraGapMinSafeRatio: v.ultraGapMinSafeRatio != null ? String(v.ultraGapMinSafeRatio) : undefined,
        ultraGapStakeMultiplier: v.ultraGapStakeMultiplier != null ? String(v.ultraGapStakeMultiplier) : undefined,
        maxStrongGapStakeMultiplier: v.maxStrongGapStakeMultiplier != null ? String(v.maxStrongGapStakeMultiplier) : undefined,
        maxBoostedAmountUsdc: v.maxBoostedAmountUsdc != null && v.maxBoostedAmountUsdc !== '' ? String(v.maxBoostedAmountUsdc) : undefined,
        maxBoostedPeriodExposureUsdc: v.maxBoostedPeriodExposureUsdc != null && v.maxBoostedPeriodExposureUsdc !== '' ? String(v.maxBoostedPeriodExposureUsdc) : undefined,
        allowBoostWithKelly: v.allowBoostWithKelly === true,
        costBuffer: v.costBuffer != null ? String(v.costBuffer) : undefined,
        sigmaScale: v.sigmaScale != null ? String(v.sigmaScale) : undefined,
        dailyLossLimitUsdc: v.dailyLossLimitUsdc != null && v.dailyLossLimitUsdc !== '' ? String(v.dailyLossLimitUsdc) : null,
        maxConcurrentPositions: v.maxConcurrentPositions != null ? Number(v.maxConcurrentPositions) : null,
        takerFeeBps: v.takerFeeBps != null ? Number(v.takerFeeBps) : undefined,
        gasCostUsdc: v.gasCostUsdc != null ? String(v.gasCostUsdc) : undefined,
        entryFakSlippage: v.entryFakSlippage != null ? String(v.entryFakSlippage) : undefined,
        exitFakSlippage: v.exitFakSlippage != null ? String(v.exitFakSlippage) : undefined,
        sigmaMethod: v.sigmaMethod != null ? String(v.sigmaMethod) : undefined,
        ewmaLambda: v.ewmaLambda != null ? String(v.ewmaLambda) : undefined,
        minSafeRatio: v.minSafeRatio != null ? String(v.minSafeRatio) : undefined,
        minSafeRatioUp: v.minSafeRatioUp != null ? String(v.minSafeRatioUp) : undefined,
        minSafeRatioDown: v.minSafeRatioDown != null ? String(v.minSafeRatioDown) : undefined,
        highPriceThreshold: v.highPriceThreshold != null ? String(v.highPriceThreshold) : undefined,
        highPriceMinPWin: v.highPriceMinPWin != null ? String(v.highPriceMinPWin) : undefined,
        highPriceMinSafeRatio: v.highPriceMinSafeRatio != null ? String(v.highPriceMinSafeRatio) : undefined,
        enableExitManager: v.enableExitManager === true,
        maxLossPct: v.maxLossPct != null ? String(v.maxLossPct) : undefined,
        exitPWin: v.exitPWin != null ? String(v.exitPWin) : undefined,
        exitSafeRatio: v.exitSafeRatio != null ? String(v.exitSafeRatio) : undefined,
        exitConfirmTicks: v.exitConfirmTicks != null ? Number(v.exitConfirmTicks) : undefined,
        takeProfitDelta1: v.takeProfitDelta1 != null ? String(v.takeProfitDelta1) : undefined,
        takeProfitSellPct1: v.takeProfitSellPct1 != null ? String(v.takeProfitSellPct1) : undefined,
        takeProfitBid2: v.takeProfitBid2 != null ? String(v.takeProfitBid2) : undefined,
        takeProfitSellPct2: v.takeProfitSellPct2 != null ? String(v.takeProfitSellPct2) : undefined,
        enableSmartHardStop: v.enableSmartHardStop === true,
        emergencyExitOnModelFlip: v.emergencyExitOnModelFlip === true,
        emergencyExitOnGapFlip: v.emergencyExitOnGapFlip === true,
        exitPollIntervalMs: v.exitPollIntervalMs != null ? Number(v.exitPollIntervalMs) : undefined,
        enableWickFilter: v.enableWickFilter === true,
        wickFilterMode: v.wickFilterMode != null ? String(v.wickFilterMode) : undefined,
        wickLookbackMinutes: v.wickLookbackMinutes != null ? Number(v.wickLookbackMinutes) : undefined,
        wickMinBodyRatio: v.wickMinBodyRatio != null ? String(v.wickMinBodyRatio) : undefined,
        wickRejectionRatio: v.wickRejectionRatio != null ? String(v.wickRejectionRatio) : undefined,
        wickMaWindow: v.wickMaWindow != null ? Number(v.wickMaWindow) : undefined,
        wickEntryBlockScore: v.wickEntryBlockScore != null ? Number(v.wickEntryBlockScore) : undefined,
        wickExitScore: v.wickExitScore != null ? Number(v.wickExitScore) : undefined,
        wickHoldProfitScore: v.wickHoldProfitScore != null ? Number(v.wickHoldProfitScore) : undefined,
        wickUseBinanceVolume: v.wickUseBinanceVolume === true,
        wickVolumeSpikeRatio: v.wickVolumeSpikeRatio != null ? String(v.wickVolumeSpikeRatio) : undefined,
        wickMinTicksPerCandle: v.wickMinTicksPerCandle != null ? Number(v.wickMinTicksPerCandle) : undefined,
        wickMinRangeSigmaRatio: v.wickMinRangeSigmaRatio != null ? String(v.wickMinRangeSigmaRatio) : undefined,
        wickClosePositionUpMax: v.wickClosePositionUpMax != null ? String(v.wickClosePositionUpMax) : undefined,
        wickClosePositionDownMin: v.wickClosePositionDownMin != null ? String(v.wickClosePositionDownMin) : undefined,
        maxHoldTp1DelaySeconds: v.maxHoldTp1DelaySeconds != null ? Number(v.maxHoldTp1DelaySeconds) : undefined,
        holdTp1PeakDrawdown: v.holdTp1PeakDrawdown != null ? String(v.holdTp1PeakDrawdown) : undefined,
        maxEntrySpread: v.maxEntrySpread != null ? String(v.maxEntrySpread) : undefined,
        maxOrderbookAgeMs: v.maxOrderbookAgeMs != null ? Number(v.maxOrderbookAgeMs) : undefined,
        maxPriceAgeMs: v.maxPriceAgeMs != null ? Number(v.maxPriceAgeMs) : undefined,
        minRemainingSeconds: v.minRemainingSeconds != null ? Number(v.minRemainingSeconds) : undefined,
        maxRemainingSeconds: v.maxRemainingSeconds != null ? Number(v.maxRemainingSeconds) : undefined,
        minExitBidDepthUsdc: v.minExitBidDepthUsdc != null ? String(v.minExitBidDepthUsdc) : undefined,
        maxExitSpread: v.maxExitSpread != null ? String(v.maxExitSpread) : undefined,
        enableTrailingStop: v.enableTrailingStop === true,
        trailingStartDelta: v.trailingStartDelta != null ? String(v.trailingStartDelta) : undefined,
        trailingDrawdown: v.trailingDrawdown != null ? String(v.trailingDrawdown) : undefined,
        trailingSellPct: v.trailingSellPct != null ? String(v.trailingSellPct) : undefined,
        maxOrdersPerDay: v.maxOrdersPerDay != null && v.maxOrdersPerDay !== '' ? Number(v.maxOrdersPerDay) : null,
        maxConsecutiveLosses: v.maxConsecutiveLosses != null && v.maxConsecutiveLosses !== '' ? Number(v.maxConsecutiveLosses) : null,
        pauseAfterLossMinutes: v.pauseAfterLossMinutes != null ? Number(v.pauseAfterLossMinutes) : undefined,
        allowDuplicateMarketPosition: v.allowDuplicateMarketPosition === true,
        ...(barrierOn ? {
          entryProb: v.entryProb != null ? String(v.entryProb) : undefined,
          entryEdge: v.entryEdge != null ? String(v.entryEdge) : undefined,
          maxEntryPrice: v.maxEntryPrice != null ? String(v.maxEntryPrice) : undefined,
          barrierMinMarketProb: v.barrierMinMarketProb != null ? String(v.barrierMinMarketProb) : undefined,
          makerRebateBps: v.makerRebateBps != null ? Number(v.makerRebateBps) : undefined,
          entryOrderType: v.entryOrderType != null ? String(v.entryOrderType) : undefined,
          makerPriceOffset: v.makerPriceOffset != null ? String(v.makerPriceOffset) : undefined,
          makerCancelBeforeSettleSeconds: v.makerCancelBeforeSettleSeconds != null ? Number(v.makerCancelBeforeSettleSeconds) : undefined,
          makerFallbackTaker: v.makerFallbackTaker === true,
          calibrationGateEnabled: v.calibrationGateEnabled === true,
          probeAmountUsdc: v.probeAmountUsdc != null ? String(v.probeAmountUsdc) : undefined,
          calibrationMinSamples: v.calibrationMinSamples != null ? Number(v.calibrationMinSamples) : undefined,
          calibrationMaxError: v.calibrationMaxError != null ? String(v.calibrationMaxError) : undefined,
          kellyEnabled: v.kellyEnabled === true,
          kellyFraction: v.kellyFraction != null ? String(v.kellyFraction) : undefined
        } : {}),
        ...bracketParams,
        ...tailDiffParams,
        ...scalpParams
      }
      const payload = {
        accountId: v.accountId as number,
        name: v.name as string | undefined,
        marketSlugPrefix: v.marketSlugPrefix as string,
        intervalSeconds: interval,
        windowStartSeconds,
        windowEndSeconds,
        minPrice: probabilityMode ? '0' : String(v.minPrice ?? 0),
        maxPrice: probabilityMode ? '1' : (v.maxPrice != null ? String(v.maxPrice) : undefined),
        amountMode: v.amountMode as string,
        amountValue: String(v.amountValue ?? 0),
        spreadMode: probabilityMode ? 'NONE' : ((v.spreadMode as string) || 'AUTO'),
        spreadValue: probabilityMode ? undefined : (v.spreadMode === 'FIXED' && v.spreadValue != null ? String(v.spreadValue) : (v.spreadMode === 'AUTO' && v.spreadValue != null ? String(v.spreadValue) : undefined)),
        spreadDirection: probabilityMode ? 'MIN' : (v.spreadDirection as string || 'MIN'),
        enabled: v.enabled !== false,
        ...barrierParams
      }
      if (editingId) {
        const res = await apiService.cryptoTailStrategy.update({
          strategyId: editingId,
          name: payload.name,
          windowStartSeconds: payload.windowStartSeconds,
          windowEndSeconds: payload.windowEndSeconds,
          minPrice: payload.minPrice,
          maxPrice: payload.maxPrice,
          amountMode: payload.amountMode,
          amountValue: payload.amountValue,
          spreadMode: payload.spreadMode,
          spreadValue: payload.spreadValue,
          spreadDirection: payload.spreadDirection,
          enabled: payload.enabled,
          ...barrierParams
        })
        if (res.data.code === 0) {
          message.success(t('common.success'))
          setFormModalOpen(false)
          fetchList()
        } else {
          message.error(res.data.msg || t('common.failed'))
        }
      } else {
        const res = await apiService.cryptoTailStrategy.create({
          ...payload,
          spreadValue: payload.spreadMode === 'FIXED' ? payload.spreadValue : undefined
        })
        if (res.data.code === 0) {
          message.success(t('common.success'))
          setFormModalOpen(false)
          fetchList()
        } else {
          message.error(res.data.msg || t('common.failed'))
        }
      }
    } catch (e) {
      if ((e as { errorFields?: unknown[] })?.errorFields) {
        return
      }
      message.error((e as Error).message)
    }
  }

  // 导出/导入仅携带策略参数，刻意排除与账户/行情强绑定的字段，避免导入到新策略时错配
  const CONFIG_EXCLUDED_FIELDS = ['accountId', 'marketSlugPrefix', 'name']

  const handleExportConfig = () => {
    const all = form.getFieldsValue(true) as Record<string, unknown>
    const params: Record<string, unknown> = {}
    Object.keys(all).forEach((key) => {
      if (!CONFIG_EXCLUDED_FIELDS.includes(key) && all[key] !== undefined) {
        params[key] = all[key]
      }
    })
    if (Object.keys(params).length === 0) {
      message.warning(t('cryptoTailStrategy.form.exportEmpty'))
      return
    }
    const payload = { _type: 'cryptoTailStrategy', _v: 1, params }
    const json = JSON.stringify(payload, null, 2)
    try {
      const blob = new Blob([json], { type: 'application/json' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `crypto-tail-strategy-config-${dayjs().format('YYYYMMDD-HHmmss')}.json`
      document.body.appendChild(a)
      a.click()
      document.body.removeChild(a)
      URL.revokeObjectURL(url)
    } catch {
      // 下载失败不阻断，下面仍尝试复制到剪贴板
    }
    if (navigator.clipboard?.writeText) {
      navigator.clipboard.writeText(json).then(
        () => message.success(t('cryptoTailStrategy.form.exportSuccess')),
        () => message.success(t('cryptoTailStrategy.form.exportSuccess'))
      )
    } else {
      message.success(t('cryptoTailStrategy.form.exportSuccess'))
    }
  }

  const openImportModal = () => {
    setImportText('')
    setImportModalOpen(true)
  }

  const handleApplyImport = () => {
    const text = importText.trim()
    if (!text) {
      message.warning(t('cryptoTailStrategy.form.importEmpty'))
      return
    }
    let parsed: unknown
    try {
      parsed = JSON.parse(text)
    } catch {
      message.error(t('cryptoTailStrategy.form.importInvalid'))
      return
    }
    const obj = parsed as { _type?: string; params?: Record<string, unknown> }
    if (!obj || obj._type !== 'cryptoTailStrategy' || typeof obj.params !== 'object' || obj.params === null) {
      message.error(t('cryptoTailStrategy.form.importInvalid'))
      return
    }
    const params: Record<string, unknown> = {}
    Object.keys(obj.params).forEach((key) => {
      if (!CONFIG_EXCLUDED_FIELDS.includes(key)) {
        params[key] = (obj.params as Record<string, unknown>)[key]
      }
    })
    form.setFieldsValue(params)
    setImportModalOpen(false)
    message.success(t('cryptoTailStrategy.form.importSuccess'))
  }

  const handleToggle = async (record: CryptoTailStrategyDto) => {
    try {
      const res = await apiService.cryptoTailStrategy.update({
        strategyId: record.id,
        enabled: !record.enabled
      })
      if (res.data.code === 0) {
        message.success(record.enabled ? t('cryptoTailStrategy.list.disable') : t('cryptoTailStrategy.list.enable'))
        fetchList()
      } else {
        message.error(res.data.msg)
      }
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const handleDelete = async (strategyId: number) => {
    try {
      const res = await apiService.cryptoTailStrategy.delete({ strategyId })
      if (res.data.code === 0) {
        message.success(t('common.success'))
        fetchList()
      } else {
        message.error(res.data.msg)
      }
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  type TriggerLoadOpts = { page?: number; pageSize?: number; dateRange?: [Dayjs | null, Dayjs | null] }

  const loadTriggerRecords = async (
    strategyId: number,
    status: 'success' | 'fail',
    opts?: TriggerLoadOpts
  ) => {
    const page = opts?.page ?? triggerPage
    const pageSize = opts?.pageSize ?? triggerPageSize
    const range = opts?.dateRange ?? triggerDateRange
    const startDate =
      range[0] != null ? range[0].startOf('day').valueOf() : undefined
    const endDate =
      range[1] != null ? range[1].endOf('day').valueOf() : undefined
    setTriggersLoading(true)
    try {
      const res = await apiService.cryptoTailStrategy.triggers({
        strategyId,
        page,
        pageSize,
        status,
        startDate,
        endDate
      })
      if (res.data.code === 0 && res.data.data) {
        setTriggers(res.data.data.list ?? [])
        setTriggersTotal(res.data.data.total ?? 0)
      }
    } finally {
      setTriggersLoading(false)
    }
  }

  const loadDecisionLog = async (
    strategyId: number,
    opts?: { page?: number; pageSize?: number; dateRange?: [Dayjs | null, Dayjs | null] }
  ) => {
    const page = opts?.page ?? decisionPage
    const pageSize = opts?.pageSize ?? decisionPageSize
    const range = opts?.dateRange ?? triggerDateRange
    const startDate = range[0] != null ? range[0].startOf('day').valueOf() : undefined
    const endDate = range[1] != null ? range[1].endOf('day').valueOf() : undefined
    setDecisionLoading(true)
    try {
      const res = await apiService.cryptoTailStrategy.decisionLog({
        strategyId,
        page,
        pageSize,
        startDate,
        endDate
      })
      if (res.data.code === 0 && res.data.data) {
        setDecisionEvents(res.data.data.list ?? [])
        setDecisionTotal(res.data.data.total ?? 0)
      }
    } finally {
      setDecisionLoading(false)
    }
  }

  const openTriggers = async (strategyId: number) => {
    setTriggersStrategyId(strategyId)
    setTriggerTab('success')
    setTriggerDateRange([null, null])
    setTriggerPage(1)
    setTriggerPageSize(20)
    setTriggersModalOpen(true)
    await loadTriggerRecords(strategyId, 'success', { page: 1, pageSize: 20, dateRange: [null, null] })
  }

  const getPnlCurveTimeRange = (): { startDate?: number; endDate?: number } => {
    if (pnlCurvePreset === 'all') return {}
    const now = dayjs()
    if (pnlCurvePreset === 'today') {
      return { startDate: now.startOf('day').valueOf(), endDate: now.valueOf() }
    }
    if (pnlCurvePreset === '7d') {
      return { startDate: now.subtract(7, 'day').startOf('day').valueOf(), endDate: now.valueOf() }
    }
    if (pnlCurvePreset === '30d') {
      return { startDate: now.subtract(30, 'day').startOf('day').valueOf(), endDate: now.valueOf() }
    }
    if (pnlCurveCustomRange[0] != null && pnlCurveCustomRange[1] != null) {
      return {
        startDate: pnlCurveCustomRange[0].startOf('day').valueOf(),
        endDate: pnlCurveCustomRange[1].endOf('day').valueOf()
      }
    }
    return {}
  }

  const loadPnlCurve = async () => {
    if (pnlCurveStrategyId == null) return
    setPnlCurveLoading(true)
    try {
      const { startDate, endDate } = getPnlCurveTimeRange()
      const res = await apiService.cryptoTailStrategy.pnlCurve({
        strategyId: pnlCurveStrategyId,
        startDate,
        endDate
      })
      if (res.data.code === 0 && res.data.data) {
        setPnlCurveData(res.data.data)
      }
    } catch (e) {
      console.error('Failed to load PnL curve:', e)
    } finally {
      setPnlCurveLoading(false)
    }
  }

  const openPnlCurve = (record: CryptoTailStrategyDto) => {
    setPnlCurveStrategyId(record.id)
    setPnlCurveStrategyName(record.name ?? record.marketTitle ?? record.marketSlugPrefix ?? '')
    setPnlCurvePreset('all')
    setPnlCurveCustomRange([null, null])
    setPnlCurveModalOpen(true)
  }

  const openAdvisor = (record: CryptoTailStrategyDto) => {
    setAdvisorStrategyId(record.id)
    setAdvisorStrategyName(record.name ?? record.marketTitle ?? record.marketSlugPrefix ?? '')
    setAdvisorModalOpen(true)
  }

  useEffect(() => {
    if (pnlCurveModalOpen && pnlCurveStrategyId != null) {
      loadPnlCurve()
    }
  }, [pnlCurveModalOpen, pnlCurveStrategyId, pnlCurvePreset, pnlCurveCustomRange])

  const onTriggerTabChange = (key: string) => {
    const next = key === 'success' ? 'success' : key === 'decision' ? 'decision' : 'fail'
    setTriggerTab(next)
    if (triggersStrategyId == null) return
    if (next === 'decision') {
      setDecisionEvents([])
      setDecisionPage(1)
      loadDecisionLog(triggersStrategyId, { page: 1 })
    } else {
      setTriggers([])
      setTriggerPage(1)
      loadTriggerRecords(triggersStrategyId, next, { page: 1 })
    }
  }

  const onTriggerDateRangeChange = (dates: [Dayjs | null, Dayjs | null] | null) => {
    const next = dates ?? [null, null]
    setTriggerDateRange(next)
    if (triggersStrategyId == null) return
    if (triggerTab === 'decision') {
      setDecisionPage(1)
      loadDecisionLog(triggersStrategyId, { page: 1, dateRange: next })
    } else {
      setTriggerPage(1)
      loadTriggerRecords(triggersStrategyId, triggerTab, { page: 1, dateRange: next })
    }
  }

  const onTriggerPageChange = (page: number, pageSize: number) => {
    setTriggerPage(page)
    setTriggerPageSize(pageSize)
    if (triggersStrategyId != null) {
      loadTriggerRecords(triggersStrategyId, triggerTab === 'decision' ? 'success' : triggerTab, { page, pageSize })
    }
  }

  const onDecisionPageChange = (page: number, pageSize: number) => {
    setDecisionPage(page)
    setDecisionPageSize(pageSize)
    if (triggersStrategyId != null) {
      loadDecisionLog(triggersStrategyId, { page, pageSize })
    }
  }

  const disabledTriggerEndDate = (current: Dayjs) => current && current.isAfter(dayjs(), 'day')

  const formatTimeWindow = (startSec: number, endSec: number, wrap = true): string => {
    const sm = Math.floor(startSec / 60)
    const ss = startSec % 60
    const em = Math.floor(endSec / 60)
    const es = endSec % 60
    const sep = wrap ? '\n~ ' : ' ~ '
    return `${sm} ${t('cryptoTailStrategy.form.minute')} ${ss} ${t('cryptoTailStrategy.form.second')}${sep}${em} ${t('cryptoTailStrategy.form.minute')} ${es} ${t('cryptoTailStrategy.form.second')}`
  }

  const formatPriceRange = (minPrice: string, maxPrice: string): string => {
    const min = formatNumber(minPrice, 2)
    const max = formatNumber(maxPrice, 2)
    if (min === '' || max === '') return '-'
    return `${min} ~ ${max}`
  }

  const pnlColor = (value: string | number | null | undefined): string | undefined => {
    if (value == null || value === '') return undefined
    const num = typeof value === 'string' ? Number(value) : value
    if (Number.isNaN(num)) return undefined
    if (num > 0) return '#52c41a'
    if (num < 0) return '#ff4d4f'
    return undefined
  }

  const getAccountLabel = (accountId: number) => accounts.find((a) => a.id === accountId)?.accountName || `#${accountId}`

  const columns = [
    {
      title: t('common.status'),
      dataIndex: 'enabled',
      key: 'enabled',
      width: 72,
      render: (enabled: boolean, record: CryptoTailStrategyDto) => (
        <Switch
          checked={enabled}
          onChange={() => handleToggle(record)}
          size="small"
        />
      )
    },
    {
      title: t('cryptoTailStrategy.list.strategyName'),
      dataIndex: 'name',
      key: 'name',
      width: isMobile ? 100 : 160,
      ellipsis: true,
      render: (name: string | undefined, r: CryptoTailStrategyDto) => {
        const recordMode = typeof r.mode === 'number' ? r.mode : (r.barrierEnabled ? 1 : 0)
        const tagColor = recordMode === 4 ? 'gold' : recordMode === 3 ? 'volcano' : recordMode === 2 ? 'purple' : recordMode === 1 ? 'geekblue' : 'default'
        const tagKey = recordMode === 4
          ? 'cryptoTailStrategy.form.modeScalp'
          : recordMode === 3
            ? 'cryptoTailStrategy.form.modeTailDiff'
            : recordMode === 2
              ? 'cryptoTailStrategy.form.modeBracket'
              : recordMode === 1
                ? 'cryptoTailStrategy.form.modeBarrier'
                : 'cryptoTailStrategy.form.modeLegacy'
        return (
          <Space size={4} direction="vertical" style={{ display: 'inline-flex', maxWidth: '100%' }}>
            <Typography.Text strong style={{ wordBreak: 'break-word', whiteSpace: 'normal' }}>
              {name || (r.marketTitle ?? r.marketSlugPrefix) || '-'}
            </Typography.Text>
            <Tag color={tagColor} style={{ marginInlineEnd: 0, fontSize: 11, lineHeight: '16px', padding: '0 6px' }}>
              {t(tagKey)}
            </Tag>
          </Space>
        )
      }
    },
    {
      title: t('cryptoTailStrategy.list.account'),
      dataIndex: 'accountId',
      key: 'accountId',
      width: isMobile ? 90 : 100,
      ellipsis: true,
      render: (_: unknown, r: CryptoTailStrategyDto) => (
        <Typography.Text type="secondary" style={{ wordBreak: 'break-word', whiteSpace: 'normal' }}>
          {getAccountLabel(r.accountId)}
        </Typography.Text>
      )
    },
    {
      title: t('cryptoTailStrategy.list.marketAndTime'),
      key: 'marketAndTime',
      width: isMobile ? 120 : 180,
      render: (_: unknown, r: CryptoTailStrategyDto) => (
        <div>
          <Typography.Text style={{ wordBreak: 'break-word', whiteSpace: 'normal', display: 'block' }}>
            {marketOptions.find((m) => m.slug === r.marketSlugPrefix)?.title ?? r.marketTitle ?? r.marketSlugPrefix ?? '-'}
          </Typography.Text>
          <Typography.Text type="secondary" style={{ fontSize: 12, wordBreak: 'break-word', whiteSpace: 'pre-line' }}>
            {formatTimeWindow(r.windowStartSeconds, r.windowEndSeconds, false)}
          </Typography.Text>
        </div>
      )
    },
    {
      title: t('cryptoTailStrategy.list.config'),
      key: 'config',
      width: isMobile ? 100 : 140,
      render: (_: unknown, r: CryptoTailStrategyDto) => (
        <div>
          <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block' }}>
            {formatPriceRange(r.minPrice, r.maxPrice)}
          </Typography.Text>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {(r.amountMode?.toUpperCase() ?? '') === 'RATIO'
              ? `${t('cryptoTailStrategy.list.ratio')} ${formatNumber(r.amountValue, 2) || '0'}%`
              : `${t('cryptoTailStrategy.list.fixed')} ${formatUSDC(r.amountValue)}`}
          </Typography.Text>
        </div>
      )
    },
    {
      title: t('cryptoTailStrategy.list.totalRealizedPnl'),
      key: 'pnl',
      width: isMobile ? 90 : 120,
      render: (_: unknown, r: CryptoTailStrategyDto) => {
        const text = r.totalRealizedPnl != null ? formatUSDC(r.totalRealizedPnl) : '-'
        const color = pnlColor(r.totalRealizedPnl)
        return (
          <div>
            {color ? (
              <Typography.Text style={{ color, fontWeight: 500 }}>{text}</Typography.Text>
            ) : (
              <Typography.Text type="secondary">{text}</Typography.Text>
            )}
            <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block' }}>
              {r.winRate != null ? `${(Number(r.winRate) * 100).toFixed(1)}%` : '-'}
            </Typography.Text>
          </div>
        )
      }
    },
    {
      title: t('cryptoTailStrategy.list.actions'),
      key: 'actions',
      width: isMobile ? 120 : 140,
      fixed: 'right' as const,
      render: (_: unknown, record: CryptoTailStrategyDto) => (
        <Space size={4}>
          <Tooltip title={t('cryptoTailStrategy.list.edit')}>
            <div
              onClick={() => openEditModal(record)}
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                width: '32px',
                height: '32px',
                cursor: 'pointer',
                borderRadius: '6px',
                transition: 'background-color 0.2s'
              }}
              onMouseEnter={(e) => { e.currentTarget.style.backgroundColor = '#f0f0f0' }}
              onMouseLeave={(e) => { e.currentTarget.style.backgroundColor = 'transparent' }}
            >
              <EditOutlined style={{ fontSize: '16px', color: '#1890ff' }} />
            </div>
          </Tooltip>

          <Tooltip title={t('cryptoTailStrategy.list.viewTriggers')}>
            <div
              onClick={() => openTriggers(record.id)}
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                width: '32px',
                height: '32px',
                cursor: 'pointer',
                borderRadius: '6px',
                transition: 'background-color 0.2s'
              }}
              onMouseEnter={(e) => { e.currentTarget.style.backgroundColor = '#f0f0f0' }}
              onMouseLeave={(e) => { e.currentTarget.style.backgroundColor = 'transparent' }}
            >
              <UnorderedListOutlined style={{ fontSize: '16px', color: '#1890ff' }} />
            </div>
          </Tooltip>

          <Tooltip title={t('cryptoTailStrategy.list.viewPnlCurve')}>
            <div
              onClick={() => openPnlCurve(record)}
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                width: '32px',
                height: '32px',
                cursor: 'pointer',
                borderRadius: '6px',
                transition: 'background-color 0.2s'
              }}
              onMouseEnter={(e) => { e.currentTarget.style.backgroundColor = '#f0f0f0' }}
              onMouseLeave={(e) => { e.currentTarget.style.backgroundColor = 'transparent' }}
            >
              <LineChartOutlined style={{ fontSize: '16px', color: '#1890ff' }} />
            </div>
          </Tooltip>

          {record.mode === 3 && (
            <Tooltip title={t('cryptoTailStrategy.advisor.entryBtn')}>
              <div
                onClick={() => openAdvisor(record)}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  width: '32px',
                  height: '32px',
                  cursor: 'pointer',
                  borderRadius: '6px',
                  transition: 'background-color 0.2s'
                }}
                onMouseEnter={(e) => { e.currentTarget.style.backgroundColor = '#fff7e6' }}
                onMouseLeave={(e) => { e.currentTarget.style.backgroundColor = 'transparent' }}
              >
                <BulbOutlined style={{ fontSize: '16px', color: '#fa8c16' }} />
              </div>
            </Tooltip>
          )}

          <Popconfirm
            title={t('cryptoTailStrategy.list.deleteConfirm')}
            onConfirm={() => handleDelete(record.id)}
            okText={t('common.confirm')}
            cancelText={t('common.cancel')}
          >
            <Tooltip title={t('cryptoTailStrategy.list.delete')}>
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  width: '32px',
                  height: '32px',
                  cursor: 'pointer',
                  borderRadius: '6px',
                  transition: 'background-color 0.2s'
                }}
                onMouseEnter={(e) => { e.currentTarget.style.backgroundColor = '#fff1f0' }}
                onMouseLeave={(e) => { e.currentTarget.style.backgroundColor = 'transparent' }}
              >
                <DeleteOutlined style={{ fontSize: '16px', color: '#ff4d4f' }} />
              </div>
            </Tooltip>
          </Popconfirm>
        </Space>
      )
    }
  ]

  const selectedMarket = Form.useWatch('marketSlugPrefix', form)
  const mode = Form.useWatch('mode', form) as number | undefined
  const isBarrierMode = mode === 1
  const isBracketMode = mode === 2
  const isTailDiffMode = mode === 3
  const isScalpMode = mode === 4
  const barrierEnabled = isBarrierMode || isBracketMode
  // 旧价差/价格区间字段仅在 LEGACY 模式展示；障碍/阶梯/尾盘价差/快进快出均有各自的入场闸
  const legacyMode = !barrierEnabled && !isTailDiffMode && !isScalpMode
  const entryOrderType = Form.useWatch('entryOrderType', form)
  const calibrationGateEnabled = Form.useWatch('calibrationGateEnabled', form)
  const sigmaMethod = Form.useWatch('sigmaMethod', form)
  const kellyEnabled = Form.useWatch('kellyEnabled', form)
  const exitManagerEnabled = Form.useWatch('enableExitManager', form)
  const intervalSeconds = marketOptions.find((m) => m.slug === selectedMarket)?.intervalSeconds ?? 300
  const maxMinutes = Math.floor(intervalSeconds / 60)

  // 新建时：选择市场后，区间开始默认 0分0秒，区间结束默认 x分0秒（x=周期）
  useEffect(() => {
    if (!formModalOpen || editingId != null || !selectedMarket) return
    const intervalMin = Math.floor(intervalSeconds / 60)
    form.setFieldsValue({
      windowStartMinutes: 0,
      windowStartSeconds: 0,
      windowEndMinutes: intervalMin,
      windowEndSeconds: 0
    })
  }, [formModalOpen, editingId, selectedMarket, intervalSeconds])

  const getGuideUrl = () => {
    const { githubRepoUrl } = getVersionInfo()
    const lang = i18n.language === 'zh-CN' || i18n.language === 'zh-TW' ? 'zh' : 'en'
    return `${githubRepoUrl}/blob/main/docs/crypto-tail-strategy/${lang}/crypto-tail-strategy-user-guide.md`
  }

  return (
    <div style={{ padding: isMobile ? 0 : 24 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '20px', flexWrap: 'wrap', gap: '12px', padding: isMobile ? '0 8px' : 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
          <h2 style={{ margin: 0, fontSize: isMobile ? '20px' : '24px' }}>{t('cryptoTailStrategy.list.title')}</h2>
          <Button
            type="link"
            icon={<FileTextOutlined />}
            onClick={() => window.open(getGuideUrl(), '_blank')}
            style={{ padding: 0, height: 'auto', fontSize: isMobile ? 14 : 16 }}
          >
            {t('cryptoTailStrategy.list.configGuide')}
          </Button>
          <Button
            type="link"
            icon={<LineChartOutlined />}
            onClick={() => { setReversalAdoptMode(false); setReversalModalOpen(true) }}
            style={{ padding: 0, height: 'auto', fontSize: isMobile ? 14 : 16 }}
          >
            {t('cryptoTailStrategy.reversal.entryBtn')}
          </Button>
        </div>
        <Tooltip title={t('cryptoTailStrategy.list.addStrategy')}>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={openAddModal}
            size={isMobile ? 'middle' : 'large'}
            style={{ borderRadius: '8px', height: isMobile ? '40px' : '48px', fontSize: isMobile ? '14px' : '16px' }}
          />
        </Tooltip>
      </div>
      {binanceUnhealthy.length > 0 && list.some((s) => s.enabled) && (
        <Alert
          type="error"
          showIcon
          icon={<WarningOutlined />}
          message={t('cryptoTailStrategy.binanceApiAlert.title')}
          description={
            <div>
              <p style={{ marginBottom: 8 }}>{t('cryptoTailStrategy.binanceApiAlert.description')}</p>
              <ul style={{ marginBottom: 8, paddingLeft: 20 }}>
                {binanceUnhealthy.map((item, i) => (
                  <li key={i}>
                    <strong>{item.name}</strong>
                    {item.message ? `: ${item.message}` : ''}
                  </li>
                ))}
              </ul>
              <Button
                type="primary"
                danger
                size="small"
                loading={binanceCheckLoading}
                onClick={fetchBinanceApiStatus}
              >
                {t('cryptoTailStrategy.binanceApiAlert.recheck')}
              </Button>
            </div>
          }
          style={{ marginBottom: 16, margin: isMobile ? '0 8px 16px' : undefined }}
        />
      )}
      <Alert
        type="warning"
        showIcon
        message={t('cryptoTailStrategy.list.walletTip')}
        style={{ marginBottom: 16, margin: isMobile ? '0 8px 16px' : undefined }}
      />
      <Card style={{ borderRadius: isMobile ? 0 : '12px', boxShadow: '0 2px 8px rgba(0,0,0,0.08)', border: isMobile ? 'none' : '1px solid #e8e8e8' }} styles={{ body: { padding: isMobile ? 12 : 24 } }}>
        <div style={{ marginBottom: 16, display: 'flex', flexWrap: 'wrap', gap: 8, alignItems: 'center' }}>
          <Select
            placeholder={t('cryptoTailStrategy.form.selectAccount')}
            allowClear
            style={{ minWidth: 160 }}
            onChange={(id) => setFilters((f) => ({ ...f, accountId: id ?? undefined }))}
            value={filters.accountId}
            options={accounts.map((a) => ({ label: a.accountName || `#${a.id}`, value: a.id }))}
          />
          <Select
            placeholder={t('common.status')}
            allowClear
            style={{ width: 100 }}
            onChange={(en) => setFilters((f) => ({ ...f, enabled: en }))}
            value={filters.enabled}
            options={[
              { label: t('common.enabled'), value: true },
              { label: t('common.disabled'), value: false }
            ]}
          />
        </div>
        <Spin spinning={loading}>
          {isMobile ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
              {list.map((item) => {
                const accountLabel = getAccountLabel(item.accountId)
                const marketTitle = marketOptions.find((m) => m.slug === item.marketSlugPrefix)?.title ?? item.marketTitle ?? item.marketSlugPrefix ?? '-'
                return (
                  <Card
                    key={item.id}
                    style={{
                      marginBottom: 0,
                      borderRadius: '10px',
                      boxShadow: '0 1px 3px rgba(0,0,0,0.08)',
                      border: '1px solid #e8e8e8',
                      overflow: 'hidden'
                    }}
                    bodyStyle={{ padding: 0 }}
                  >
                    <div style={{
                      padding: '10px 12px',
                      background: item.enabled ? 'var(--ant-color-primary, #1677ff)' : 'var(--ant-color-fill-secondary, #f0f0f0)',
                      color: item.enabled ? '#fff' : 'var(--ant-color-text-secondary, #666)'
                    }}>
                      <div style={{ fontSize: '15px', fontWeight: '600', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                        <span style={{ wordBreak: 'break-word', whiteSpace: 'normal' }}>{item.name || marketTitle || '-'}</span>
                        <Switch checked={item.enabled} onChange={() => handleToggle(item)} size="small" />
                      </div>
                    </div>
                    <div style={{ padding: '8px 12px', backgroundColor: '#fafafa', borderBottom: '1px solid #f0f0f0' }}>
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                        <div>
                          <div style={{ fontSize: '10px', color: '#8c8c8c' }}>{t('cryptoTailStrategy.list.totalRealizedPnl')}</div>
                          {item.totalRealizedPnl != null ? (
                            <div style={{ fontSize: '14px', fontWeight: '600', color: pnlColor(item.totalRealizedPnl) }}>${formatUSDC(item.totalRealizedPnl)}</div>
                          ) : (
                            <div style={{ fontSize: '14px', color: '#8c8c8c' }}>-</div>
                          )}
                        </div>
                        <div style={{ textAlign: 'right' }}>
                          <div style={{ fontSize: '10px', color: '#8c8c8c' }}>{t('cryptoTailStrategy.list.winRate')}</div>
                          {item.winRate != null ? <Tag color="blue" style={{ margin: 0 }}>{(Number(item.winRate) * 100).toFixed(1)}%</Tag> : <span style={{ fontSize: '12px', color: '#8c8c8c' }}>-</span>}
                        </div>
                      </div>
                    </div>
                    <div style={{ padding: '6px 12px', fontSize: '11px', color: '#8c8c8c', borderBottom: '1px solid #f0f0f0' }}>
                      <div>{t('cryptoTailStrategy.list.account')}: {accountLabel}</div>
                      <div>{t('cryptoTailStrategy.list.market')}: {marketTitle}</div>
                    </div>
                    <div style={{ padding: '6px 12px', fontSize: '11px', color: '#8c8c8c', borderBottom: '1px solid #f0f0f0' }}>
                      {t('cryptoTailStrategy.list.timeWindow')}: {formatTimeWindow(item.windowStartSeconds, item.windowEndSeconds, false)} · {t('cryptoTailStrategy.list.priceRange')}: {formatPriceRange(item.minPrice, item.maxPrice)}
                    </div>
                    <div style={{ padding: '8px 12px', display: 'flex', justifyContent: 'space-around', alignItems: 'center' }}>
                      <Tooltip title={t('cryptoTailStrategy.list.edit')}>
                        <div onClick={() => openEditModal(item)} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', cursor: 'pointer', padding: '4px 8px' }}>
                          <EditOutlined style={{ fontSize: '18px', color: '#52c41a' }} />
                          <span style={{ fontSize: '10px', color: '#8c8c8c', marginTop: '2px' }}>{t('cryptoTailStrategy.list.edit')}</span>
                        </div>
                      </Tooltip>
                      <Tooltip title={t('cryptoTailStrategy.list.viewTriggers')}>
                        <div onClick={() => openTriggers(item.id)} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', cursor: 'pointer', padding: '4px 8px' }}>
                          <UnorderedListOutlined style={{ fontSize: '18px', color: '#1890ff' }} />
                          <span style={{ fontSize: '10px', color: '#8c8c8c', marginTop: '2px' }}>{t('cryptoTailStrategy.list.viewTriggers')}</span>
                        </div>
                      </Tooltip>
                      <Tooltip title={t('cryptoTailStrategy.list.viewPnlCurve')}>
                        <div onClick={() => openPnlCurve(item)} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', cursor: 'pointer', padding: '4px 8px' }}>
                          <LineChartOutlined style={{ fontSize: '18px', color: '#1890ff' }} />
                          <span style={{ fontSize: '10px', color: '#8c8c8c', marginTop: '2px' }}>{t('cryptoTailStrategy.list.viewPnlCurve')}</span>
                        </div>
                      </Tooltip>
                      <Popconfirm title={t('cryptoTailStrategy.list.deleteConfirm')} onConfirm={() => handleDelete(item.id)} okText={t('common.confirm')} cancelText={t('common.cancel')}>
                        <Tooltip title={t('cryptoTailStrategy.list.delete')}>
                          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', cursor: 'pointer', padding: '4px 8px' }}>
                            <DeleteOutlined style={{ fontSize: '18px', color: '#ff4d4f' }} />
                            <span style={{ fontSize: '10px', color: '#8c8c8c', marginTop: '2px' }}>{t('cryptoTailStrategy.list.delete')}</span>
                          </div>
                        </Tooltip>
                      </Popconfirm>
                    </div>
                  </Card>
                )
              })}
            </div>
          ) : (
            <Table
              rowKey="id"
              columns={columns}
              dataSource={list}
              pagination={{ pageSize: 20 }}
              scroll={{ x: 720 }}
            />
          )}
        </Spin>
      </Card>

      <Modal
        title={t('cryptoTailStrategy.redeemRequiredModal.title')}
        open={redeemModalOpen}
        onCancel={() => setRedeemModalOpen(false)}
        footer={[
          <Button key="cancel" onClick={() => setRedeemModalOpen(false)}>
            {t('cryptoTailStrategy.redeemRequiredModal.cancel')}
          </Button>,
          <Button
            key="go"
            type="primary"
            onClick={() => {
              setRedeemModalOpen(false)
              navigate('/system-settings')
            }}
          >
            {t('cryptoTailStrategy.redeemRequiredModal.goToSettings')}
          </Button>
        ]}
      >
        <p>{t('cryptoTailStrategy.redeemRequiredModal.description')}</p>
      </Modal>

      <CryptoTailPnlCurveModal
        open={pnlCurveModalOpen}
        onClose={() => setPnlCurveModalOpen(false)}
        data={pnlCurveData}
        loading={pnlCurveLoading}
        strategyName={pnlCurveStrategyName}
        preset={pnlCurvePreset}
        onPresetChange={setPnlCurvePreset}
        onRefresh={loadPnlCurve}
      />

      <CryptoTailReversalResearchModal
        open={reversalModalOpen}
        onClose={() => setReversalModalOpen(false)}
        defaultCoin={reversalAdoptMode ? (inferCoinFromSlug(form.getFieldValue('marketSlugPrefix')) ?? undefined) : undefined}
        defaultIntervalSeconds={reversalAdoptMode ? marketOptions.find((m) => m.slug === form.getFieldValue('marketSlugPrefix'))?.intervalSeconds : undefined}
        onAdopt={reversalAdoptMode ? (ds, lb) => {
          // 采纳按当前模式写回对应字段：SCALP_FLIP(mode=4)→scalp* 字段并确保反转门槛开启；其余→tailDiff* 字段
          if (form.getFieldValue('mode') === 4) {
            form.setFieldsValue({
              scalpStatsSource: ds,
              scalpStatsLookbackDays: lb,
              scalpReversalGateEnabled: true
            })
          } else {
            const patch: Record<string, unknown> = {
              tailDiffStatsDataSource: ds,
              tailDiffStatsLookbackDays: lb
            }
            // 采纳统计来源时，若当前为 FALLBACK（不查表）则提升为 HYBRID，否则采纳无效
            if ((form.getFieldValue('tailDiffModelProbSource') ?? 'HYBRID') === 'FALLBACK') {
              patch.tailDiffModelProbSource = 'HYBRID'
            }
            form.setFieldsValue(patch)
          }
          message.success(t('cryptoTailStrategy.form.statsCoverage.adoptSuccess', { source: ds, lookback: lb }))
          setReversalModalOpen(false)
        } : undefined}
      />

      <CryptoTailTailDiffAdvisorModal
        open={advisorModalOpen}
        strategyId={advisorStrategyId}
        strategyName={advisorStrategyName}
        onClose={() => setAdvisorModalOpen(false)}
      />

      <Modal
        title={editingId ? t('cryptoTailStrategy.form.update') : t('cryptoTailStrategy.form.create')}
        open={formModalOpen}
        onCancel={() => setFormModalOpen(false)}
        onOk={handleFormSubmit}
        width={isMobile ? '100%' : 520}
        destroyOnClose
      >
        <Alert type="warning" showIcon message={t('cryptoTailStrategy.form.walletTip')} style={{ marginBottom: 16 }} />
        <Space wrap style={{ marginBottom: 16 }}>
          <Button icon={<ExportOutlined />} onClick={handleExportConfig}>
            {t('cryptoTailStrategy.form.exportConfig')}
          </Button>
          <Button icon={<ImportOutlined />} onClick={openImportModal}>
            {t('cryptoTailStrategy.form.importConfig')}
          </Button>
        </Space>
        <Form
          form={form}
          layout="vertical"
          initialValues={{ amountMode: 'RATIO', maxPrice: '1', spreadMode: 'AUTO', spreadDirection: 'MIN', enabled: true }}
          onValuesChange={(changed) => {
            // 新建时切到 SCALP(4)：把退出引擎三参设为本次修复推荐值（智能硬止损=开/pWin=0.96/巡检=1000ms）。
            // 仅新建生效（editingId 为空）；编辑态由 record 回填，setFieldsValue 不会触发本回调，故不会覆盖已存值。
            if (changed.mode === 4 && !editingId) {
              form.setFieldsValue({ enableSmartHardStop: true, holdToSettlePwin: '0.96', exitPollIntervalMs: 1000 })
            }
          }}
        >
          <Form.Item name="accountId" label={t('cryptoTailStrategy.form.selectAccount')} rules={[{ required: true }]}>
            <Select
              placeholder={t('cryptoTailStrategy.form.selectAccount')}
              options={accounts.map((a) => ({ label: a.accountName || `#${a.id}`, value: a.id }))}
              disabled={!!editingId}
            />
          </Form.Item>
          <Form.Item name="name" label={t('cryptoTailStrategy.form.strategyName')}>
            <Input placeholder={t('cryptoTailStrategy.form.strategyNamePlaceholder')} />
          </Form.Item>
          <Form.Item name="marketSlugPrefix" label={t('cryptoTailStrategy.form.selectMarket')} rules={[{ required: true }]}>
            <Select
              placeholder={t('cryptoTailStrategy.form.selectMarket')}
              options={marketOptions.map((m) => ({ label: m.title, value: m.slug }))}
              disabled={!!editingId}
            />
          </Form.Item>
          {selectedMarket && (
            <>
              <Form.Item
                label={t('cryptoTailStrategy.form.timeWindowStart')}
                required
                style={{ marginBottom: 8 }}
              >
                <Space>
                  <Form.Item name="windowStartMinutes" noStyle rules={[{ required: true }]}>
                    <Select
                      style={{ width: 70 }}
                      options={Array.from({ length: maxMinutes + 1 }, (_, i) => ({ label: `${i}`, value: i }))}
                    />
                  </Form.Item>
                  <span>{t('cryptoTailStrategy.form.minute')}</span>
                  <Form.Item name="windowStartSeconds" noStyle rules={[{ required: true }]}>
                    <Select
                      style={{ width: 70 }}
                      options={Array.from({ length: 60 }, (_, i) => ({ label: `${i}`, value: i }))}
                    />
                  </Form.Item>
                  <span>{t('cryptoTailStrategy.form.second')}</span>
                </Space>
              </Form.Item>
              <Form.Item
                label={t('cryptoTailStrategy.form.timeWindowEnd')}
                required
              >
                <Space>
                  <Form.Item name="windowEndMinutes" noStyle rules={[{ required: true }]}>
                    <Select
                      style={{ width: 70 }}
                      options={Array.from({ length: maxMinutes + 1 }, (_, i) => ({ label: `${i}`, value: i }))}
                    />
                  </Form.Item>
                  <span>{t('cryptoTailStrategy.form.minute')}</span>
                  <Form.Item name="windowEndSeconds" noStyle rules={[{ required: true }]}>
                    <Select
                      style={{ width: 70 }}
                      options={Array.from({ length: 60 }, (_, i) => ({ label: `${i}`, value: i }))}
                    />
                  </Form.Item>
                  <span>{t('cryptoTailStrategy.form.second')}</span>
                </Space>
              </Form.Item>
            </>
          )}
          <Form.Item
            name="mode"
            label={
              <Space size={4}>
                <span>{t('cryptoTailStrategy.form.mode')}</span>
                <Tooltip title={t('cryptoTailStrategy.form.modeTip')}>
                  <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                </Tooltip>
              </Space>
            }
            rules={[{ required: true }]}
          >
            <Radio.Group>
              <Radio value={0}>{t('cryptoTailStrategy.form.modeLegacy')}</Radio>
              <Radio value={1}>{t('cryptoTailStrategy.form.modeBarrier')}</Radio>
              <Radio value={2}>{t('cryptoTailStrategy.form.modeBracket')}</Radio>
              <Radio value={3}>{t('cryptoTailStrategy.form.modeTailDiff')}</Radio>
              <Radio value={4}>{t('cryptoTailStrategy.form.modeScalp')}</Radio>
            </Radio.Group>
          </Form.Item>
          <Form.Item name="barrierEnabled" hidden valuePropName="checked">
            <Switch />
          </Form.Item>
          {legacyMode && (
            <>
              <Form.Item name="minPrice" label={t('cryptoTailStrategy.form.minPrice')} rules={[{ required: true }]}>
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="maxPrice" label={t('cryptoTailStrategy.form.maxPrice')}>
                <InputNumber min={0} max={1} step={0.01} placeholder={t('cryptoTailStrategy.form.maxPricePlaceholder')} style={{ width: '100%' }} stringMode />
              </Form.Item>
            </>
          )}
          <Form.Item name="amountMode" label={t('cryptoTailStrategy.form.amountMode')} rules={[{ required: true }]}>
            <Radio.Group>
              <Radio value="RATIO">{t('cryptoTailStrategy.list.ratio')}</Radio>
              <Radio value="FIXED">{t('cryptoTailStrategy.list.fixed')}</Radio>
            </Radio.Group>
          </Form.Item>
          <Form.Item
            noStyle
            shouldUpdate={(prev, curr) => prev.amountMode !== curr.amountMode}
          >
            {({ getFieldValue }) =>
              getFieldValue('amountMode') === 'RATIO' ? (
                <Form.Item name="amountValue" label={t('cryptoTailStrategy.form.ratioPercent')} rules={[{ required: true }]}>
                  <InputNumber min={0} max={100} step={1} style={{ width: '100%' }} addonAfter="%" stringMode />
                </Form.Item>
              ) : (
                <Form.Item name="amountValue" label={t('cryptoTailStrategy.form.fixedUsdc')} rules={[{ required: true }]}>
                  <InputNumber min={1} style={{ width: '100%' }} addonBefore="$" stringMode />
                </Form.Item>
              )
            }
          </Form.Item>
          {barrierEnabled && (
            <>
              {isBracketMode && (
                <Form.Item style={{ marginBottom: 12 }}>
                  <Typography.Text strong>{t('cryptoTailStrategy.form.probabilitySharedSection')}</Typography.Text>
                </Form.Item>
              )}
              <Form.Item>
                <Space size={8} wrap>
                  <Button size="small" onClick={fillRiskDefaults}>
                    {t('cryptoTailStrategy.form.riskAutofillBtn')}
                  </Button>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {t('cryptoTailStrategy.form.riskAutofillHint')}
                  </Typography.Text>
                </Space>
              </Form.Item>
            </>
          )}
          {legacyMode && (
            <>
              <Form.Item
                name="spreadMode"
                label={
                  <Space size={4}>
                    <span>{t('cryptoTailStrategy.form.spreadMode')}</span>
                    <Tooltip title={t('cryptoTailStrategy.form.spreadModeTip')}>
                      <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                    </Tooltip>
                  </Space>
                }
              >
                <Radio.Group>
                  <Radio value="AUTO">{t('cryptoTailStrategy.form.spreadModeAuto')}</Radio>
                  <Radio value="FIXED">{t('cryptoTailStrategy.form.spreadModeFixed')}</Radio>
                  <Radio value="NONE">{t('cryptoTailStrategy.form.spreadModeNone')}</Radio>
                </Radio.Group>
              </Form.Item>
              <Form.Item
                noStyle
                shouldUpdate={(prev, curr) => prev.spreadMode !== curr.spreadMode}
              >
                {({ getFieldValue }) =>
                  getFieldValue('spreadMode') === 'FIXED' ? (
                    <Form.Item
                      name="spreadValue"
                      label={t('cryptoTailStrategy.form.spreadValue')}
                      rules={[{ required: true }]}
                    >
                      <InputNumber
                        min={0}
                        step={1}
                        placeholder={t('cryptoTailStrategy.form.spreadValuePlaceholder')}
                        style={{ width: '100%' }}
                        stringMode
                      />
                    </Form.Item>
                  ) : null
                }
              </Form.Item>
              <Form.Item
                name="spreadDirection"
                label={
                  <Space size={4}>
                    <span>{t('cryptoTailStrategy.form.spreadDirection')}</span>
                    <Tooltip title={t('cryptoTailStrategy.form.spreadDirectionTip')}>
                      <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                    </Tooltip>
                  </Space>
                }
              >
                <Radio.Group>
                  <Radio value="MIN">{t('cryptoTailStrategy.form.spreadDirectionMin')}</Radio>
                  <Radio value="MAX">{t('cryptoTailStrategy.form.spreadDirectionMax')}</Radio>
                </Radio.Group>
              </Form.Item>
            </>
          )}
          {barrierEnabled && (
            <>
              {isBarrierMode && (
                <>
                  <Form.Item style={{ marginBottom: 12 }}>
                    <Typography.Text strong>{t('cryptoTailStrategy.form.barrierEntrySection')}</Typography.Text>
                  </Form.Item>
                  <Alert type="info" showIcon style={{ marginBottom: 16 }} message={t('cryptoTailStrategy.form.barrierInfo')} />
                  <Form.Item
                    name="entryProb"
                    label={
                      <Space size={4}>
                        <span>{t('cryptoTailStrategy.form.entryProb')}</span>
                        <Tooltip title={t('cryptoTailStrategy.form.entryProbTip')}>
                          <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                        </Tooltip>
                      </Space>
                    }
                    rules={[{ required: true }]}
                  >
                    <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
                  </Form.Item>
                  <Form.Item
                    name="entryEdge"
                    label={
                      <Space size={4}>
                        <span>{t('cryptoTailStrategy.form.entryEdge')}</span>
                        <Tooltip title={t('cryptoTailStrategy.form.entryEdgeTip')}>
                          <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                        </Tooltip>
                      </Space>
                    }
                    rules={[{ required: true }]}
                  >
                    <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
                  </Form.Item>
                  <Form.Item
                    name="barrierMinMarketProb"
                    label={
                      <Space size={4}>
                        <span>{t('cryptoTailStrategy.form.barrierMinMarketProb')}</span>
                        <Tooltip title={t('cryptoTailStrategy.form.barrierMinMarketProbTip')}>
                          <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                        </Tooltip>
                      </Space>
                    }
                  >
                    <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
                  </Form.Item>
                  <Form.Item
                    name="maxEntryPrice"
                    label={
                      <Space size={4}>
                        <span>{t('cryptoTailStrategy.form.maxEntryPrice')}</span>
                        <Tooltip title={t('cryptoTailStrategy.form.maxEntryPriceTip')}>
                          <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                        </Tooltip>
                      </Space>
                    }
                    rules={[{ required: true }]}
                  >
                    <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
                  </Form.Item>
                  <Form.Item style={{ marginBottom: 12 }}>
                    <Typography.Text strong>入场过滤增强</Typography.Text>
                  </Form.Item>
                  <Form.Item name="minSafeRatio" label="safeRatio 下限" rules={[{ required: true }]}>
                    <InputNumber min={0} step={0.01} style={{ width: '100%' }} stringMode />
                  </Form.Item>
                  <Form.Item name="minSafeRatioUp" label="UP safeRatio 下限" rules={[{ required: true }]}>
                    <InputNumber min={0} step={0.01} style={{ width: '100%' }} stringMode />
                  </Form.Item>
                  <Form.Item name="minSafeRatioDown" label="DOWN safeRatio 下限" rules={[{ required: true }]}>
                    <InputNumber min={0} step={0.01} style={{ width: '100%' }} stringMode />
                  </Form.Item>
                  <Form.Item name="highPriceThreshold" label="高价保护阈值" rules={[{ required: true }]}>
                    <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
                  </Form.Item>
                  <Form.Item name="highPriceMinPWin" label="高价保护 pWin 下限" rules={[{ required: true }]}>
                    <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
                  </Form.Item>
                  <Form.Item name="highPriceMinSafeRatio" label="高价保护 safeRatio 下限" rules={[{ required: true }]}>
                    <InputNumber min={0} step={0.01} style={{ width: '100%' }} stringMode />
                  </Form.Item>
                </>
              )}
              {isBarrierMode && (
                <Form.Item style={{ marginBottom: 12 }}>
                  <Typography.Text strong>{t('cryptoTailStrategy.form.probabilitySharedSection')}</Typography.Text>
                </Form.Item>
              )}
              <Form.Item
                name="costBuffer"
                label={
                  <Space size={4}>
                    <span>{t('cryptoTailStrategy.form.costBuffer')}</span>
                    <Tooltip title={t('cryptoTailStrategy.form.costBufferTip')}>
                      <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                    </Tooltip>
                  </Space>
                }
              >
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item
                name="sigmaScale"
                label={
                  <Space size={4}>
                    <span>{t('cryptoTailStrategy.form.sigmaScale')}</span>
                    <Tooltip title={t('cryptoTailStrategy.form.sigmaScaleTip')}>
                      <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                    </Tooltip>
                  </Space>
                }
              >
                <InputNumber min={0} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              {editingId != null && (
                <Form.Item>
                  <Space size={8} wrap>
                    <Button size="small" loading={recommendingSigma} onClick={recommendSigmaScale}>
                      {t('cryptoTailStrategy.form.sigmaRecommendBtn')}
                    </Button>
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      {t('cryptoTailStrategy.form.sigmaRecommendHint')}
                    </Typography.Text>
                  </Space>
                </Form.Item>
              )}
              <Form.Item
                name="sigmaMethod"
                label={
                  <Space size={4}>
                    <span>{t('cryptoTailStrategy.form.sigmaMethod')}</span>
                    <Tooltip title={t('cryptoTailStrategy.form.sigmaMethodTip')}>
                      <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                    </Tooltip>
                  </Space>
                }
              >
                <Select
                  options={[
                    { value: 'MAD', label: t('cryptoTailStrategy.form.sigmaMethodMad') },
                    { value: 'EWMA', label: t('cryptoTailStrategy.form.sigmaMethodEwma') },
                    { value: 'GARMAN_KLASS', label: t('cryptoTailStrategy.form.sigmaMethodGk') }
                  ]}
                />
              </Form.Item>
              {sigmaMethod === 'EWMA' && (
                <Form.Item
                  name="ewmaLambda"
                  label={
                    <Space size={4}>
                      <span>{t('cryptoTailStrategy.form.ewmaLambda')}</span>
                      <Tooltip title={t('cryptoTailStrategy.form.ewmaLambdaTip')}>
                        <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                      </Tooltip>
                    </Space>
                  }
                  rules={[
                    {
                      validator: (_, value) => {
                        if (value == null || value === '') return Promise.resolve()
                        const n = Number(value)
                        if (Number.isNaN(n) || n <= 0 || n >= 1) {
                          return Promise.reject(new Error(t('cryptoTailStrategy.form.ewmaLambdaTip')))
                        }
                        return Promise.resolve()
                      }
                    }
                  ]}
                >
                  <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
                </Form.Item>
              )}
              <Form.Item style={{ marginBottom: 12 }}>
                <Typography.Text strong>持仓退出 / 止损止盈</Typography.Text>
              </Form.Item>
              <Form.Item name="enableExitManager" label="启用持仓退出" valuePropName="checked">
                <Switch />
              </Form.Item>
              <Form.Item name="maxLossPct" label="最大亏损比例">
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="exitPWin" label="模型失效 pWin">
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="exitSafeRatio" label="模型失效 safeRatio">
                <InputNumber min={0} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="exitConfirmTicks" label="连续确认次数">
                <InputNumber min={1} max={10} step={1} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="takeProfitDelta1" label="第一档止盈价差">
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="takeProfitSellPct1" label="第一档卖出比例">
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="takeProfitBid2" label="第二档止盈 bestBid">
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="takeProfitSellPct2" label="第二档卖出比例">
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item
                name="enableSmartHardStop"
                label="智能硬止损(临近结算强势时持有到结算)"
                valuePropName="checked"
                extra="开启后:硬止损命中时,若价源新鲜、模型方向未反、gap 仍顺、临近结算(<=holdToSettleSeconds)且 pWin>=holdToSettlePwin、safeRatio>=max(exitSafeRatio,1.30),则放弃机械止损、持有到结算。默认关闭,行为与历史一致。"
              >
                <Switch />
              </Form.Item>
              <Form.Item name="emergencyExitOnModelFlip" label="模型方向反转立即退出" valuePropName="checked">
                <Switch />
              </Form.Item>
              <Form.Item name="emergencyExitOnGapFlip" label="gap 反转立即退出" valuePropName="checked">
                <Switch />
              </Form.Item>
              <Form.Item name="exitPollIntervalMs" label="退出检查间隔(ms)">
                <InputNumber min={500} step={500} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="enableTrailingStop" label="启用移动止损" valuePropName="checked">
                <Switch />
              </Form.Item>
              <Form.Item name="trailingStartDelta" label="移动止损启动价差">
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="trailingDrawdown" label="移动止损回撤">
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="trailingSellPct" label="移动止损卖出比例">
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item style={{ marginBottom: 12 }}>
                <Typography.Text strong>盘口质量 / 行情新鲜度</Typography.Text>
              </Form.Item>
              <Form.Item name="maxEntrySpread" label="入场最大盘口价差" extra="0=关闭限制">
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="maxOrderbookAgeMs" label="订单簿最大年龄(ms)" extra="0=关闭限制">
                <InputNumber min={0} step={500} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="maxPriceAgeMs" label="价源最大年龄(ms)" extra="0=关闭限制">
                <InputNumber min={0} step={500} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="minRemainingSeconds" label="最小入场剩余秒数" extra="0=关闭限制">
                <InputNumber min={0} step={1} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="maxRemainingSeconds" label="最大入场剩余秒数" extra="0=关闭限制">
                <InputNumber min={0} step={1} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="minExitBidDepthUsdc" label="止盈最小bid深度(USDC)" extra="0=关闭限制">
                <InputNumber min={0} step={0.5} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="maxExitSpread" label="止盈最大盘口价差" extra="0=关闭限制">
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item style={{ marginBottom: 12 }}>
                <Typography.Text strong>影线 / 反转过滤</Typography.Text>
              </Form.Item>
              <Form.Item name="enableWickFilter" label="启用影线过滤" valuePropName="checked">
                <Switch />
              </Form.Item>
              <Form.Item name="wickFilterMode" label="影线模式">
                <Select
                  options={[
                    { label: 'SHADOW 只记录', value: 'SHADOW' },
                    { label: 'ENFORCE 拦截/退出', value: 'ENFORCE' },
                    { label: 'OFF 关闭', value: 'OFF' }
                  ]}
                />
              </Form.Item>
              <Form.Item name="wickLookbackMinutes" label="回看分钟数">
                <InputNumber min={1} max={10} step={1} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="wickMinBodyRatio" label="实体最小比例">
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="wickRejectionRatio" label="影线反转比例">
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="wickMaWindow" label="短均线窗口">
                <InputNumber min={1} max={20} step={1} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="wickEntryBlockScore" label="入场禁止分数">
                <InputNumber min={0} max={100} step={1} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="wickExitScore" label="退出分数">
                <InputNumber min={0} max={100} step={1} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="wickHoldProfitScore" label="止盈暂缓分数">
                <InputNumber min={0} max={100} step={1} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="wickUseBinanceVolume" label="使用 Binance 成交量辅助" valuePropName="checked">
                <Switch />
              </Form.Item>
              <Form.Item name="wickVolumeSpikeRatio" label="成交量尖峰倍数">
                <InputNumber min={1} step={0.1} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="wickMinTicksPerCandle" label="单根K最小样本数" extra="0=关闭限制">
                <InputNumber min={0} max={60} step={1} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="wickMinRangeSigmaRatio" label="最小 range/sigma" extra="0=关闭限制">
                <InputNumber min={0} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="wickClosePositionUpMax" label="UP收盘位置上限">
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="wickClosePositionDownMin" label="DOWN收盘位置下限">
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="maxHoldTp1DelaySeconds" label="TP1最长暂缓秒数">
                <InputNumber min={0} step={1} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="holdTp1PeakDrawdown" label="TP1暂缓回撤触发">
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item
                name="dailyLossLimitUsdc"
                label={
                  <Space size={4}>
                    <span>{t('cryptoTailStrategy.form.dailyLossLimitUsdc')}</span>
                    <Tooltip title={t('cryptoTailStrategy.form.dailyLossLimitUsdcTip')}>
                      <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                    </Tooltip>
                  </Space>
                }
              >
                <InputNumber min={0} step={1} placeholder={t('cryptoTailStrategy.form.optionalPlaceholder')} style={{ width: '100%' }} addonBefore="$" stringMode />
              </Form.Item>
              <Form.Item
                name="maxConcurrentPositions"
                label={
                  <Space size={4}>
                    <span>{t('cryptoTailStrategy.form.maxConcurrentPositions')}</span>
                    <Tooltip title={t('cryptoTailStrategy.form.maxConcurrentPositionsTip')}>
                      <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                    </Tooltip>
                  </Space>
                }
              >
                <InputNumber min={0} step={1} precision={0} placeholder={t('cryptoTailStrategy.form.optionalPlaceholder')} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item
                name="allowDuplicateMarketPosition"
                label="允许同市场重复开仓"
                valuePropName="checked"
                tooltip="默认关闭：同账户、同 market、同周期、同 outcome 已有 success/pending 入场时，新策略不再重复开仓"
              >
                <Switch />
              </Form.Item>
              <Form.Item name="maxOrdersPerDay" label="单日最大入场笔数">
                <InputNumber min={0} step={1} precision={0} placeholder={t('cryptoTailStrategy.form.optionalPlaceholder')} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="maxConsecutiveLosses" label="最大连续亏损笔数">
                <InputNumber min={0} step={1} precision={0} placeholder={t('cryptoTailStrategy.form.optionalPlaceholder')} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="pauseAfterLossMinutes" label="亏损后暂停分钟数">
                <InputNumber min={0} step={1} precision={0} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item
                name="takerFeeBps"
                label={
                  <Space size={4}>
                    <span>{t('cryptoTailStrategy.form.takerFeeBps')}</span>
                    <Tooltip title={t('cryptoTailStrategy.form.takerFeeBpsTip')}>
                      <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                    </Tooltip>
                  </Space>
                }
              >
                <InputNumber min={0} max={10000} step={1} precision={0} style={{ width: '100%' }} addonAfter="bps" />
              </Form.Item>
              {isBarrierMode && (
                <Form.Item
                  name="makerRebateBps"
                  label={
                    <Space size={4}>
                      <span>{t('cryptoTailStrategy.form.makerRebateBps')}</span>
                      <Tooltip title={t('cryptoTailStrategy.form.makerRebateBpsTip')}>
                        <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                      </Tooltip>
                    </Space>
                  }
                >
                  <InputNumber min={0} max={10000} step={1} precision={0} style={{ width: '100%' }} addonAfter="bps" />
                </Form.Item>
              )}
              <Form.Item
                name="gasCostUsdc"
                label={
                  <Space size={4}>
                    <span>{t('cryptoTailStrategy.form.gasCostUsdc')}</span>
                    <Tooltip title={t('cryptoTailStrategy.form.gasCostUsdcTip')}>
                      <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                    </Tooltip>
                  </Space>
                }
              >
                <InputNumber min={0} step={0.01} style={{ width: '100%' }} addonBefore="$" stringMode />
              </Form.Item>
              {isBarrierMode && (
                <>
                  <Form.Item
                    name="entryOrderType"
                    label={
                      <Space size={4}>
                        <span>{t('cryptoTailStrategy.form.entryOrderType')}</span>
                        <Tooltip title={t('cryptoTailStrategy.form.entryOrderTypeTip')}>
                          <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                        </Tooltip>
                      </Space>
                    }
                  >
                    <Select
                      options={[
                        { value: 'FAK', label: t('cryptoTailStrategy.form.entryOrderTypeFak') },
                        { value: 'MAKER', label: t('cryptoTailStrategy.form.entryOrderTypeMaker') }
                      ]}
                    />
                  </Form.Item>
                  {entryOrderType === 'MAKER' && (
                    <>
                      <Form.Item
                        name="makerPriceOffset"
                        label={
                          <Space size={4}>
                            <span>{t('cryptoTailStrategy.form.makerPriceOffset')}</span>
                            <Tooltip title={t('cryptoTailStrategy.form.makerPriceOffsetTip')}>
                              <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                            </Tooltip>
                          </Space>
                        }
                        rules={[
                          {
                            validator: (_, value) => {
                              if (value == null || value === '') return Promise.resolve()
                              const n = Number(value)
                              if (Number.isNaN(n) || n <= -1 || n >= 1) {
                                return Promise.reject(new Error(t('cryptoTailStrategy.form.makerPriceOffsetTip')))
                              }
                              return Promise.resolve()
                            }
                          }
                        ]}
                      >
                        <InputNumber min={-1} max={1} step={0.01} style={{ width: '100%' }} stringMode />
                      </Form.Item>
                      <Form.Item
                        name="makerCancelBeforeSettleSeconds"
                        label={
                          <Space size={4}>
                            <span>{t('cryptoTailStrategy.form.makerCancelBeforeSettleSeconds')}</span>
                            <Tooltip title={t('cryptoTailStrategy.form.makerCancelBeforeSettleSecondsTip')}>
                              <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                            </Tooltip>
                          </Space>
                        }
                      >
                        <InputNumber min={0} step={1} precision={0} style={{ width: '100%' }} addonAfter="s" />
                      </Form.Item>
                      <Form.Item
                        name="makerFallbackTaker"
                        valuePropName="checked"
                        label={
                          <Space size={4}>
                            <span>{t('cryptoTailStrategy.form.makerFallbackTaker')}</span>
                            <Tooltip title={t('cryptoTailStrategy.form.makerFallbackTakerTip')}>
                              <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                            </Tooltip>
                          </Space>
                        }
                      >
                        <Switch checkedChildren={t('common.enabled')} unCheckedChildren={t('common.disabled')} />
                      </Form.Item>
                    </>
                  )}
                </>
              )}
              <Form.Item
                name="entryFakSlippage"
                label={
                  <Space size={4}>
                    <span>{t('cryptoTailStrategy.form.entryFakSlippage')}</span>
                    <Tooltip title={t('cryptoTailStrategy.form.entryFakSlippageTip')}>
                      <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                    </Tooltip>
                  </Space>
                }
                rules={[
                  {
                    validator: (_, value) => {
                      if (value == null || value === '') return Promise.resolve()
                      const n = Number(value)
                      if (Number.isNaN(n) || n < 0 || n > 0.1) {
                        return Promise.reject(new Error(t('cryptoTailStrategy.form.entryFakSlippageTip')))
                      }
                      return Promise.resolve()
                    }
                  }
                ]}
              >
                <InputNumber min={0} max={0.1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item
                name="exitFakSlippage"
                label={
                  <Space size={4}>
                    <span>{t('cryptoTailStrategy.form.exitFakSlippage')}</span>
                    <Tooltip title={t('cryptoTailStrategy.form.exitFakSlippageTip')}>
                      <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                    </Tooltip>
                  </Space>
                }
                rules={[
                  {
                    validator: (_, value) => {
                      if (value == null || value === '') return Promise.resolve()
                      const n = Number(value)
                      if (Number.isNaN(n) || n < 0 || n > 0.1) {
                        return Promise.reject(new Error(t('cryptoTailStrategy.form.exitFakSlippageTip')))
                      }
                      return Promise.resolve()
                    }
                  }
                ]}
              >
                <InputNumber min={0} max={0.1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              {isBarrierMode && (
                <>
                  <Form.Item
                    name="calibrationGateEnabled"
                    valuePropName="checked"
                    label={
                      <Space size={4}>
                        <span>{t('cryptoTailStrategy.form.calibrationGateEnabled')}</span>
                        <Tooltip title={t('cryptoTailStrategy.form.calibrationGateEnabledTip')}>
                          <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                        </Tooltip>
                      </Space>
                    }
                  >
                    <Switch checkedChildren={t('common.enabled')} unCheckedChildren={t('common.disabled')} />
                  </Form.Item>
                  {calibrationGateEnabled && (
                    <>
                      <Form.Item
                        name="probeAmountUsdc"
                        label={
                          <Space size={4}>
                            <span>{t('cryptoTailStrategy.form.probeAmountUsdc')}</span>
                            <Tooltip title={t('cryptoTailStrategy.form.probeAmountUsdcTip')}>
                              <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                            </Tooltip>
                          </Space>
                        }
                        rules={[
                          {
                            validator: (_, value) => {
                              if (value == null || value === '') return Promise.resolve()
                              if (Number(value) < 1) return Promise.reject(new Error(t('cryptoTailStrategy.form.probeAmountUsdcTip')))
                              return Promise.resolve()
                            }
                          }
                        ]}
                      >
                        <InputNumber min={1} step={1} style={{ width: '100%' }} addonBefore="$" stringMode />
                      </Form.Item>
                      <Form.Item
                        name="calibrationMinSamples"
                        label={
                          <Space size={4}>
                            <span>{t('cryptoTailStrategy.form.calibrationMinSamples')}</span>
                            <Tooltip title={t('cryptoTailStrategy.form.calibrationMinSamplesTip')}>
                              <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                            </Tooltip>
                          </Space>
                        }
                      >
                        <InputNumber min={1} step={1} precision={0} style={{ width: '100%' }} />
                      </Form.Item>
                      <Form.Item
                        name="calibrationMaxError"
                        label={
                          <Space size={4}>
                            <span>{t('cryptoTailStrategy.form.calibrationMaxError')}</span>
                            <Tooltip title={t('cryptoTailStrategy.form.calibrationMaxErrorTip')}>
                              <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                            </Tooltip>
                          </Space>
                        }
                        rules={[
                          {
                            validator: (_, value) => {
                              if (value == null || value === '') return Promise.resolve()
                              const n = Number(value)
                              if (Number.isNaN(n) || n <= 0 || n >= 1) {
                                return Promise.reject(new Error(t('cryptoTailStrategy.form.calibrationMaxErrorTip')))
                              }
                              return Promise.resolve()
                            }
                          }
                        ]}
                      >
                        <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
                      </Form.Item>
                    </>
                  )}
                  <Form.Item
                    name="kellyEnabled"
                    valuePropName="checked"
                    label={
                      <Space size={4}>
                        <span>{t('cryptoTailStrategy.form.kellyEnabled')}</span>
                        <Tooltip title={t('cryptoTailStrategy.form.kellyEnabledTip')}>
                          <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                        </Tooltip>
                      </Space>
                    }
                  >
                    <Switch checkedChildren={t('common.enabled')} unCheckedChildren={t('common.disabled')} />
                  </Form.Item>
                  {kellyEnabled && (
                    <Form.Item
                      name="kellyFraction"
                      label={
                        <Space size={4}>
                          <span>{t('cryptoTailStrategy.form.kellyFraction')}</span>
                          <Tooltip title={t('cryptoTailStrategy.form.kellyFractionTip')}>
                            <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                          </Tooltip>
                        </Space>
                      }
                      rules={[
                        {
                          validator: (_, value) => {
                            if (value == null || value === '') return Promise.resolve()
                            const n = Number(value)
                            if (Number.isNaN(n) || n <= 0 || n > 1) {
                              return Promise.reject(new Error(t('cryptoTailStrategy.form.kellyFractionTip')))
                            }
                            return Promise.resolve()
                          }
                        }
                      ]}
                    >
                      <InputNumber min={0} max={1} step={0.05} style={{ width: '100%' }} stringMode />
                    </Form.Item>
                  )}
                </>
              )}
            </>
          )}
          {isBracketMode && (
            <>
              <Form.Item style={{ marginBottom: 12 }}>
                <Typography.Text strong>{t('cryptoTailStrategy.form.bracketEntryExitSection')}</Typography.Text>
              </Form.Item>
              <Alert type="info" showIcon style={{ marginBottom: 16 }} message={t('cryptoTailStrategy.form.bracketInfo')} />
              <Form.Item
                name="bracketEntryProb"
                label={
                  <Space size={4}>
                    <span>{t('cryptoTailStrategy.form.bracketEntryProb')}</span>
                    <Tooltip title={t('cryptoTailStrategy.form.bracketEntryProbTip')}>
                      <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                    </Tooltip>
                  </Space>
                }
                rules={[{ required: true }]}
              >
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item
                name="bracketEntryEdge"
                label={
                  <Space size={4}>
                    <span>{t('cryptoTailStrategy.form.bracketEntryEdge')}</span>
                    <Tooltip title={t('cryptoTailStrategy.form.bracketEntryEdgeTip')}>
                      <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                    </Tooltip>
                  </Space>
                }
                rules={[{ required: true }]}
              >
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item
                name="bracketMaxEntryPrice"
                label={
                  <Space size={4}>
                    <span>{t('cryptoTailStrategy.form.bracketMaxEntryPrice')}</span>
                    <Tooltip title={t('cryptoTailStrategy.form.bracketMaxEntryPriceTip')}>
                      <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                    </Tooltip>
                  </Space>
                }
                rules={[{ required: true }]}
              >
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              {/* tp1Price/tp1Ratio/tp1HoldPwin/tp2Price/tp2Ratio/tp2HoldPwin（V52 旧 bracket 原生止盈字段）已废弃：
                  退出实际由退出管理（V54 takeProfitDelta1/takeProfitBid2/takeProfitSellPct1/2）驱动，这些字段不被退出逻辑读取。
                  为避免误配（前端有字段但后端不生效），从表单移除；DB 列保留以兼容历史数据，退出阶梯请在"退出管理"区配置。 */}
              <Form.Item
                name="holdToSettlePwin"
                label={
                  <Space size={4}>
                    <span>{t('cryptoTailStrategy.form.holdToSettlePwin')}</span>
                    <Tooltip title={t('cryptoTailStrategy.form.holdToSettlePwinTip')}>
                      <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                    </Tooltip>
                  </Space>
                }
              >
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item
                name="holdToSettleSeconds"
                label={
                  <Space size={4}>
                    <span>{t('cryptoTailStrategy.form.holdToSettleSeconds')}</span>
                    <Tooltip title={t('cryptoTailStrategy.form.holdToSettleSecondsTip')}>
                      <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                    </Tooltip>
                  </Space>
                }
              >
                <InputNumber min={0} step={1} precision={0} style={{ width: '100%' }} addonAfter="s" />
              </Form.Item>
              <Form.Item
                name="stopProb"
                label={
                  <Space size={4}>
                    <span>{t('cryptoTailStrategy.form.stopProb')}</span>
                    <Tooltip title={t('cryptoTailStrategy.form.stopProbTip')}>
                      <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                    </Tooltip>
                  </Space>
                }
              >
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item
                name="stopPrice"
                label={
                  <Space size={4}>
                    <span>{t('cryptoTailStrategy.form.stopPrice')}</span>
                    <Tooltip title={t('cryptoTailStrategy.form.stopPriceTip')}>
                      <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                    </Tooltip>
                  </Space>
                }
              >
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item
                name="forceExitBeforeSettleSeconds"
                label={
                  <Space size={4}>
                    <span>{t('cryptoTailStrategy.form.forceExitBeforeSettleSeconds')}</span>
                    <Tooltip title={t('cryptoTailStrategy.form.forceExitBeforeSettleSecondsTip')}>
                      <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                    </Tooltip>
                  </Space>
                }
              >
                <InputNumber min={0} step={1} precision={0} style={{ width: '100%' }} addonAfter="s" />
              </Form.Item>
              <Form.Item
                name="exitOrderType"
                label={
                  <Space size={4}>
                    <span>{t('cryptoTailStrategy.form.exitOrderType')}</span>
                    <Tooltip title={t('cryptoTailStrategy.form.exitOrderTypeTip')}>
                      <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                    </Tooltip>
                  </Space>
                }
              >
                <Select
                  options={[
                    { value: 'FAK', label: t('cryptoTailStrategy.form.exitOrderTypeFak') },
                    { value: 'MAKER', label: t('cryptoTailStrategy.form.exitOrderTypeMaker') }
                  ]}
                />
              </Form.Item>
            </>
          )}
          {isTailDiffMode && (
            <>
              <Form.Item style={{ marginBottom: 12 }}>
                <Typography.Text strong>{t('cryptoTailStrategy.form.tailDiffSection')}</Typography.Text>
              </Form.Item>
              <Alert type="info" showIcon style={{ marginBottom: 16 }} message={t('cryptoTailStrategy.form.tailDiffInfo')} />

              <Form.Item name="tailDiffDirection" label={t('cryptoTailStrategy.form.tailDiffDirection')}>
                <Radio.Group>
                  <Radio value={0}>{t('cryptoTailStrategy.form.tailDiffDirectionAuto')}</Radio>
                  <Radio value={1}>{t('cryptoTailStrategy.form.tailDiffDirectionUp')}</Radio>
                  <Radio value={2}>{t('cryptoTailStrategy.form.tailDiffDirectionDown')}</Radio>
                </Radio.Group>
              </Form.Item>

              <Form.Item style={{ marginBottom: 8 }}>
                <Typography.Text type="secondary">{t('cryptoTailStrategy.form.tailDiffWindowSubsection')}</Typography.Text>
              </Form.Item>
              <Form.Item name="tailDiffWindowStartSeconds" label={t('cryptoTailStrategy.form.tailDiffWindowStartSeconds')} rules={[{ required: true }]}>
                <InputNumber min={0} step={1} precision={0} style={{ width: '100%' }} addonAfter="s" />
              </Form.Item>
              <Form.Item name="tailDiffWindowEndSeconds" label={t('cryptoTailStrategy.form.tailDiffWindowEndSeconds')} rules={[{ required: true }]}>
                <InputNumber min={0} step={1} precision={0} style={{ width: '100%' }} addonAfter="s" />
              </Form.Item>
              <Form.Item name="tailDiffMinRemainingSeconds" label={t('cryptoTailStrategy.form.tailDiffMinRemainingSeconds')} rules={[{ required: true }]}>
                <InputNumber min={0} step={1} precision={0} style={{ width: '100%' }} addonAfter="s" />
              </Form.Item>
              <Form.Item name="tailDiffConfirmTicks" label={t('cryptoTailStrategy.form.tailDiffConfirmTicks')} rules={[{ required: true }]}>
                <InputNumber min={0} step={1} precision={0} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item
                name="tailDiffEntrySegmentsJson"
                label={t('cryptoTailStrategy.form.tailDiffEntrySegmentsJson')}
                extra={t('cryptoTailStrategy.form.tailDiffEntrySegmentsHelp')}
                rules={[{
                  validator: (_rule, value) => {
                    if (!value || !String(value).trim()) return Promise.resolve()
                    try {
                      const arr = JSON.parse(value)
                      if (!Array.isArray(arr) || arr.length === 0) {
                        return Promise.reject(new Error(t('cryptoTailStrategy.form.tailDiffEntrySegmentsInvalid')))
                      }
                      for (const seg of arr) {
                        const hi = Number(seg?.remaining_hi)
                        const lo = Number(seg?.remaining_lo)
                        if (!Number.isFinite(hi) || !Number.isFinite(lo) || lo < 0 || hi < lo) {
                          return Promise.reject(new Error(t('cryptoTailStrategy.form.tailDiffEntrySegmentsInvalid')))
                        }
                      }
                      return Promise.resolve()
                    } catch {
                      return Promise.reject(new Error(t('cryptoTailStrategy.form.tailDiffEntrySegmentsInvalid')))
                    }
                  }
                }]}
              >
                <TailDiffEntrySegmentsField />
              </Form.Item>

              <Form.Item style={{ marginBottom: 8 }}>
                <Typography.Text type="secondary">{t('cryptoTailStrategy.form.tailDiffPriceSubsection')}</Typography.Text>
              </Form.Item>
              <Form.Item name="tailDiffMinPrice" label={t('cryptoTailStrategy.form.tailDiffMinPrice')} rules={[{ required: true }]}>
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="tailDiffMaxPrice" label={t('cryptoTailStrategy.form.tailDiffMaxPrice')} rules={[{ required: true }]}>
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="tailDiffHardMaxPrice" label={t('cryptoTailStrategy.form.tailDiffHardMaxPrice')} rules={[{ required: true }]}>
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="tailDiffCostBuffer" label={t('cryptoTailStrategy.form.tailDiffCostBuffer')} rules={[{ required: true }]}>
                <InputNumber min={0} max={1} step={0.005} style={{ width: '100%' }} stringMode />
              </Form.Item>

              <Form.Item style={{ marginBottom: 8 }}>
                <Typography.Text type="secondary">{t('cryptoTailStrategy.form.tailDiffEdgeSubsection')}</Typography.Text>
              </Form.Item>
              <Form.Item name="tailDiffMinModelProb" label={t('cryptoTailStrategy.form.tailDiffMinModelProb')} rules={[{ required: true }]}>
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="tailDiffMinEdge" label={t('cryptoTailStrategy.form.tailDiffMinEdge')} rules={[{ required: true }]}>
                <InputNumber min={0} max={1} step={0.005} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="tailDiffMinDiffSigma" label={t('cryptoTailStrategy.form.tailDiffMinDiffSigma')} rules={[{ required: true }]}>
                <InputNumber min={0} step={0.1} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="tailDiffModelProbSource" label={t('cryptoTailStrategy.form.tailDiffModelProbSource')}>
                <Select
                  options={[
                    { value: 'HYBRID', label: t('cryptoTailStrategy.form.tailDiffSourceHybrid') },
                    { value: 'STATS', label: t('cryptoTailStrategy.form.tailDiffSourceStats') },
                    { value: 'FALLBACK', label: t('cryptoTailStrategy.form.tailDiffSourceFallback') }
                  ]}
                />
              </Form.Item>
              <Form.Item name="tailDiffStatsMinSamples" label={t('cryptoTailStrategy.form.tailDiffStatsMinSamples')}>
                <InputNumber min={0} step={1} precision={0} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="tailDiffStatsLookbackDays" label={t('cryptoTailStrategy.form.tailDiffStatsLookbackDays')}>
                <InputNumber min={1} step={1} precision={0} style={{ width: '100%' }} addonAfter="d" />
              </Form.Item>
              <Form.Item name="tailDiffStatsDataSource" label={t('cryptoTailStrategy.form.tailDiffStatsDataSource')}>
                <Select
                  options={[
                    { value: 'BINANCE', label: t('cryptoTailStrategy.form.tailDiffDataSourceBinance') },
                    { value: 'POLYMARKET', label: t('cryptoTailStrategy.form.tailDiffDataSourcePolymarket') }
                  ]}
                />
              </Form.Item>
              <TailDiffStatsCoverageAlert form={form} marketOptions={marketOptions} />
              <Form.Item>
                <Button
                  icon={<LineChartOutlined />}
                  onClick={() => { setReversalAdoptMode(true); setReversalModalOpen(true) }}
                >
                  {t('cryptoTailStrategy.form.statsCoverage.compareAndAdopt')}
                </Button>
              </Form.Item>

              <Form.Item style={{ marginBottom: 8 }}>
                <Typography.Text type="secondary">{t('cryptoTailStrategy.form.tailDiffBookSubsection')}</Typography.Text>
              </Form.Item>
              <Form.Item name="tailDiffMaxSpread" label={t('cryptoTailStrategy.form.tailDiffMaxSpread')} rules={[{ required: true }]}>
                <InputNumber min={0} max={1} step={0.005} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="tailDiffDepthMultiplier" label={t('cryptoTailStrategy.form.tailDiffDepthMultiplier')} rules={[{ required: true }]}>
                <InputNumber min={0} step={0.5} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="tailDiffMaxOrderbookAgeMs" label={t('cryptoTailStrategy.form.tailDiffMaxOrderbookAgeMs')}>
                <InputNumber min={1} step={100} precision={0} style={{ width: '100%' }} addonAfter="ms" />
              </Form.Item>
              <Form.Item name="tailDiffMaxPriceAgeMs" label={t('cryptoTailStrategy.form.tailDiffMaxPriceAgeMs')}>
                <InputNumber min={1} step={100} precision={0} style={{ width: '100%' }} addonAfter="ms" />
              </Form.Item>
              <Form.Item name="tailDiffReverseVelocityWindowSeconds" label={t('cryptoTailStrategy.form.tailDiffReverseVelocityWindowSeconds')}>
                <InputNumber min={1} step={1} precision={0} style={{ width: '100%' }} addonAfter="s" />
              </Form.Item>
              <Form.Item name="tailDiffMaxReverseVelocitySigma" label={t('cryptoTailStrategy.form.tailDiffMaxReverseVelocitySigma')}>
                <InputNumber min={0} step={0.05} style={{ width: '100%' }} stringMode />
              </Form.Item>

              <Form.Item style={{ marginBottom: 8 }}>
                <Typography.Text type="secondary">{t('cryptoTailStrategy.form.tailDiffWeightSubsection')}</Typography.Text>
              </Form.Item>
              <Alert type="warning" showIcon style={{ marginBottom: 12 }} message={t('cryptoTailStrategy.form.tailDiffWeightHint')} />
              <Form.Item name="tailDiffWeightDiff" label={t('cryptoTailStrategy.form.tailDiffWeightDiff')}>
                <InputNumber min={0} max={100} step={1} precision={0} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="tailDiffWeightTime" label={t('cryptoTailStrategy.form.tailDiffWeightTime')}>
                <InputNumber min={0} max={100} step={1} precision={0} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="tailDiffWeightOddsUnderprice" label={t('cryptoTailStrategy.form.tailDiffWeightOddsUnderprice')}>
                <InputNumber min={0} max={100} step={1} precision={0} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="tailDiffWeightOddsLag" label={t('cryptoTailStrategy.form.tailDiffWeightOddsLag')}>
                <InputNumber min={0} max={100} step={1} precision={0} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="tailDiffWeightHistory" label={t('cryptoTailStrategy.form.tailDiffWeightHistory')}>
                <InputNumber min={0} max={100} step={1} precision={0} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="tailDiffWeightBook" label={t('cryptoTailStrategy.form.tailDiffWeightBook')}>
                <InputNumber min={0} max={100} step={1} precision={0} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="tailDiffWeightData" label={t('cryptoTailStrategy.form.tailDiffWeightData')}>
                <InputNumber min={0} max={100} step={1} precision={0} style={{ width: '100%' }} />
              </Form.Item>

              <Form.Item style={{ marginBottom: 8 }}>
                <Typography.Text type="secondary">{t('cryptoTailStrategy.form.tailDiffScoreTuneSubsection')}</Typography.Text>
              </Form.Item>
              <Alert type="info" showIcon style={{ marginBottom: 12 }} message={t('cryptoTailStrategy.form.tailDiffScoreTuneHint')} />
              <Form.Item name="tailDiffOddsLagMode" label={t('cryptoTailStrategy.form.tailDiffOddsLagMode')}>
                <Select
                  options={[
                    { value: 'STATIC', label: t('cryptoTailStrategy.form.tailDiffOddsLagModeStatic') },
                    { value: 'DYNAMIC', label: t('cryptoTailStrategy.form.tailDiffOddsLagModeDynamic') },
                    { value: 'HYBRID', label: t('cryptoTailStrategy.form.tailDiffOddsLagModeHybrid') }
                  ]}
                />
              </Form.Item>
              <Form.Item name="tailDiffOddsLagWindowSeconds" label={t('cryptoTailStrategy.form.tailDiffOddsLagWindowSeconds')}>
                <InputNumber min={1} step={1} precision={0} style={{ width: '100%' }} addonAfter="s" />
              </Form.Item>
              <Form.Item
                name="tailDiffOddsLagStrongEdgeBypass"
                label={t('cryptoTailStrategy.form.tailDiffOddsLagStrongEdgeBypass')}
                tooltip={t('cryptoTailStrategy.form.tailDiffOddsLagStrongEdgeBypassHint')}
                valuePropName="checked"
              >
                <Switch />
              </Form.Item>
              <Form.Item name="tailDiffLagPriceMoveFullScaleSigma" label={t('cryptoTailStrategy.form.tailDiffLagPriceMoveFullScaleSigma')}>
                <InputNumber min={0} step={0.1} style={{ width: '100%' }} addonAfter="σ" stringMode />
              </Form.Item>
              <Form.Item name="tailDiffLagOddsMoveFullScale" label={t('cryptoTailStrategy.form.tailDiffLagOddsMoveFullScale')}>
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="tailDiffEdgeFullScale" label={t('cryptoTailStrategy.form.tailDiffEdgeFullScale')}>
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="tailDiffLagFullScale" label={t('cryptoTailStrategy.form.tailDiffLagFullScale')}>
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="tailDiffHistoryProbFloor" label={t('cryptoTailStrategy.form.tailDiffHistoryProbFloor')}>
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="tailDiffHistoryProbCeil" label={t('cryptoTailStrategy.form.tailDiffHistoryProbCeil')}>
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="tailDiffSigmaScoreMultiple" label={t('cryptoTailStrategy.form.tailDiffSigmaScoreMultiple')}>
                <InputNumber min={1} step={0.5} style={{ width: '100%' }} stringMode />
              </Form.Item>

              <Form.Item style={{ marginBottom: 8 }}>
                <Typography.Text type="secondary">{t('cryptoTailStrategy.form.tailDiffTierSubsection')}</Typography.Text>
              </Form.Item>
              <Form.Item name="tailDiffMinEntryScore" label={t('cryptoTailStrategy.form.tailDiffMinEntryScore')} rules={[{ required: true }]}>
                <InputNumber min={1} max={100} step={1} precision={0} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="tailDiffPremiumScore" label={t('cryptoTailStrategy.form.tailDiffPremiumScore')} rules={[{ required: true }]}>
                <InputNumber min={1} max={100} step={1} precision={0} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="tailDiffTopScore" label={t('cryptoTailStrategy.form.tailDiffTopScore')} rules={[{ required: true }]}>
                <InputNumber min={1} max={100} step={1} precision={0} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="tailDiffBaseAmount" label={t('cryptoTailStrategy.form.tailDiffBaseAmount')} rules={[{ required: true }]}>
                <InputNumber min={0} step={1} style={{ width: '100%' }} addonBefore="$" stringMode />
              </Form.Item>
              <Form.Item name="tailDiffTierNormalMult" label={t('cryptoTailStrategy.form.tailDiffTierNormalMult')}>
                <InputNumber min={0} step={0.1} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="tailDiffTierPremiumMult" label={t('cryptoTailStrategy.form.tailDiffTierPremiumMult')}>
                <InputNumber min={0} step={0.1} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="tailDiffTierTopMult" label={t('cryptoTailStrategy.form.tailDiffTierTopMult')}>
                <InputNumber min={0} step={0.1} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="tailDiffMaxAmountPerOrder" label={t('cryptoTailStrategy.form.tailDiffMaxAmountPerOrder')} rules={[{ required: true }]}>
                <InputNumber min={0} step={1} style={{ width: '100%' }} addonBefore="$" stringMode />
              </Form.Item>
              <Alert type="info" showIcon style={{ marginBottom: 12 }} message={t('cryptoTailStrategy.form.tailDiffSizingCapHint')} />
              <Form.Item name="tailDiffEnableKellyCap" label={t('cryptoTailStrategy.form.tailDiffEnableKellyCap')} valuePropName="checked">
                <Switch />
              </Form.Item>
              <Form.Item name="tailDiffKellyFraction" label={t('cryptoTailStrategy.form.tailDiffKellyFraction')}>
                <InputNumber min={0} max={1} step={0.05} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="tailDiffDepthFillRatio" label={t('cryptoTailStrategy.form.tailDiffDepthFillRatio')}>
                <InputNumber min={0} step={0.1} style={{ width: '100%' }} stringMode />
              </Form.Item>

              <Form.Item style={{ marginBottom: 8 }}>
                <Typography.Text type="secondary">{t('cryptoTailStrategy.form.tailDiffExitSubsection')}</Typography.Text>
              </Form.Item>
              <Form.Item
                name="enableExitManager"
                label={t('cryptoTailStrategy.form.tailDiffEnableExitManager')}
                valuePropName="checked"
                extra={t('cryptoTailStrategy.form.tailDiffEnableExitManagerHint')}
              >
                <Switch />
              </Form.Item>
              {exitManagerEnabled === false && (
                <Alert
                  type="warning"
                  showIcon
                  style={{ marginBottom: 12 }}
                  message={t('cryptoTailStrategy.form.tailDiffEnableExitManagerOffWarning')}
                />
              )}
              <Form.Item name="exitPollIntervalMs" label={t('cryptoTailStrategy.form.tailDiffExitPollIntervalMs')} extra={t('cryptoTailStrategy.form.tailDiffExitPollIntervalMsHint')}>
                <InputNumber min={500} step={500} precision={0} style={{ width: '100%' }} />
              </Form.Item>
              <Alert type="info" showIcon style={{ marginBottom: 12 }} message={t('cryptoTailStrategy.form.tailDiffExitInfo')} />
              <Form.Item
                name="tailDiffExitPresetNormalJson"
                label={t('cryptoTailStrategy.form.tailDiffExitPresetNormalJson')}
                rules={[{ validator: (_r, v) => (isExitPresetJsonValid(v) ? Promise.resolve() : Promise.reject(new Error(t('cryptoTailStrategy.form.tailDiffExitPresetInvalid')))) }]}
              >
                <TailDiffExitPresetField tier="NORMAL" />
              </Form.Item>
              <Form.Item
                name="tailDiffExitPresetPremiumJson"
                label={t('cryptoTailStrategy.form.tailDiffExitPresetPremiumJson')}
                rules={[{ validator: (_r, v) => (isExitPresetJsonValid(v) ? Promise.resolve() : Promise.reject(new Error(t('cryptoTailStrategy.form.tailDiffExitPresetInvalid')))) }]}
              >
                <TailDiffExitPresetField tier="PREMIUM" />
              </Form.Item>
              <Form.Item
                name="tailDiffExitPresetTopJson"
                label={t('cryptoTailStrategy.form.tailDiffExitPresetTopJson')}
                rules={[{ validator: (_r, v) => (isExitPresetJsonValid(v) ? Promise.resolve() : Promise.reject(new Error(t('cryptoTailStrategy.form.tailDiffExitPresetInvalid')))) }]}
              >
                <TailDiffExitPresetField tier="TOP" />
              </Form.Item>

              <Form.Item style={{ marginBottom: 8 }}>
                <Typography.Text type="secondary">{t('cryptoTailStrategy.form.tailDiffRiskSubsection')}</Typography.Text>
              </Form.Item>
              <Form.Item name="tailDiffDailyLossLimitUsdc" label={t('cryptoTailStrategy.form.tailDiffDailyLossLimitUsdc')}>
                <InputNumber min={0} step={1} style={{ width: '100%' }} addonBefore="$" placeholder={t('cryptoTailStrategy.form.tailDiffDailyLossLimitPlaceholder')} stringMode />
              </Form.Item>
              <Form.Item name="tailDiffConsecLossPauseCount" label={t('cryptoTailStrategy.form.tailDiffConsecLossPauseCount')}>
                <InputNumber min={0} step={1} precision={0} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="tailDiffConsecLossStopCount" label={t('cryptoTailStrategy.form.tailDiffConsecLossStopCount')}>
                <InputNumber min={0} step={1} precision={0} style={{ width: '100%' }} />
              </Form.Item>

              <Form.Item style={{ marginBottom: 8 }}>
                <Typography.Text strong>{t('cryptoTailStrategy.form.tailDiffPreviewSubsection')}</Typography.Text>
              </Form.Item>
              <Alert type="info" showIcon style={{ marginBottom: 12 }} message={t('cryptoTailStrategy.form.tailDiffPreviewInfo')} />
              <Form.Item name="tailDiffPreviewOutcomeIndex" label={t('cryptoTailStrategy.form.tailDiffPreviewOutcomeIndex')} initialValue={0}>
                <Radio.Group>
                  <Radio value={0}>{t('cryptoTailStrategy.form.tailDiffDirectionUp')}</Radio>
                  <Radio value={1}>{t('cryptoTailStrategy.form.tailDiffDirectionDown')}</Radio>
                </Radio.Group>
              </Form.Item>
              <Form.Item>
                <Button onClick={runTailDiffPreview} loading={tailDiffPreviewLoading}>
                  {t('cryptoTailStrategy.form.tailDiffPreviewRun')}
                </Button>
              </Form.Item>
              {tailDiffPreview && (
                <Alert
                  type={tailDiffPreview.passed ? 'success' : 'warning'}
                  showIcon
                  style={{ marginBottom: 16 }}
                  message={t('cryptoTailStrategy.form.tailDiffPreviewResultTitle', { score: tailDiffPreview.score, tier: tailDiffPreview.tier ?? '-' })}
                  description={
                    <Space direction="vertical" size={2} style={{ width: '100%' }}>
                      <span>{t('cryptoTailStrategy.form.tailDiffPreviewOutcome')}: {tailDiffPreview.outcome}（{tailDiffPreview.reason}）</span>
                      <span>{t('cryptoTailStrategy.form.tailDiffPreviewDiffSigma')}: {tailDiffPreview.diffSigma}（{t('cryptoTailStrategy.form.tailDiffPreviewModelProb')}: {tailDiffPreview.modelProb} / {tailDiffPreview.modelProbSource}）</span>
                      <span>{t('cryptoTailStrategy.form.tailDiffPreviewEdge')}: {tailDiffPreview.edge}（{t('cryptoTailStrategy.form.tailDiffPreviewEffectiveCost')}: {tailDiffPreview.effectiveCost}）</span>
                      <span>{t('cryptoTailStrategy.form.tailDiffPreviewRecommendedAmount')}: ${tailDiffPreview.recommendedAmountUsdc}</span>
                      <span>{t('cryptoTailStrategy.form.tailDiffPreviewComponents')}: {Object.entries(tailDiffPreview.componentWeighted).map(([k, val]) => `${k}=${val}`).join(', ')}</span>
                      {tailDiffPreview.vetoes.length > 0 && (
                        <span style={{ color: '#cf1322' }}>{t('cryptoTailStrategy.form.tailDiffPreviewVetoes')}: {tailDiffPreview.vetoes.join(', ')}</span>
                      )}
                    </Space>
                  }
                />
              )}
            </>
          )}
          {isScalpMode && (
            <>
              <Form.Item style={{ marginBottom: 12 }}>
                <Typography.Text strong>{t('cryptoTailStrategy.form.scalpSection')}</Typography.Text>
              </Form.Item>
              <Alert type="info" showIcon style={{ marginBottom: 16 }} message={t('cryptoTailStrategy.form.scalpInfo')} />

              <Form.Item style={{ marginBottom: 8 }}>
                <Typography.Text type="secondary">{t('cryptoTailStrategy.form.scalpEntrySubsection')}</Typography.Text>
              </Form.Item>
              <Form.Item name="scalpEntryMinPrice" label={t('cryptoTailStrategy.form.scalpEntryMinPrice')} rules={[{ required: true }]}>
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="scalpEntryMaxPrice" label={t('cryptoTailStrategy.form.scalpEntryMaxPrice')} rules={[{ required: true }]}>
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="scalpMaxFillPrice" label={t('cryptoTailStrategy.form.scalpMaxFillPrice')} extra={t('cryptoTailStrategy.form.scalpMaxFillPriceHint')} rules={[{ required: true }]}>
                <InputNumber min={0} max={1} step={0.005} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="scalpMinExitBidDepthUsdc" label={t('cryptoTailStrategy.form.scalpMinExitBidDepthUsdc')} extra={t('cryptoTailStrategy.form.scalpMinExitBidDepthHint')}>
                <InputNumber min={0} step={1} style={{ width: '100%' }} addonBefore="$" placeholder={t('cryptoTailStrategy.form.scalpOptionalPlaceholder')} stringMode />
              </Form.Item>
              <Form.Item name="maxEntrySpread" label={t('cryptoTailStrategy.form.scalpMaxEntrySpread')} extra={t('cryptoTailStrategy.form.scalpMaxEntrySpreadHint')}>
                <InputNumber min={0} max={1} step={0.005} style={{ width: '100%' }} stringMode />
              </Form.Item>

              <Form.Item style={{ marginBottom: 8 }}>
                <Typography.Text type="secondary">{t('cryptoTailStrategy.form.scalpWindowSubsection')}</Typography.Text>
              </Form.Item>
              <Form.Item name="scalpWindowStartSeconds" label={t('cryptoTailStrategy.form.scalpWindowStartSeconds')} extra={t('cryptoTailStrategy.form.scalpWindowStartHint')} rules={[{ required: true }]}>
                <InputNumber min={0} step={1} precision={0} style={{ width: '100%' }} addonAfter="s" />
              </Form.Item>
              <Form.Item name="scalpWindowEndSeconds" label={t('cryptoTailStrategy.form.scalpWindowEndSeconds')} extra={t('cryptoTailStrategy.form.scalpWindowEndHint')} rules={[{ required: true }]}>
                <InputNumber min={0} step={1} precision={0} style={{ width: '100%' }} addonAfter="s" />
              </Form.Item>
              <Form.Item name="scalpMinRemainingSeconds" label={t('cryptoTailStrategy.form.scalpMinRemainingSeconds')} extra={t('cryptoTailStrategy.form.scalpMinRemainingHint')} rules={[{ required: true }]}>
                <InputNumber min={0} step={1} precision={0} style={{ width: '100%' }} addonAfter="s" />
              </Form.Item>

              <Form.Item style={{ marginBottom: 8 }}>
                <Typography.Text type="secondary">{t('cryptoTailStrategy.form.scalpReversalSubsection')}</Typography.Text>
              </Form.Item>
              <Form.Item name="scalpReversalGateEnabled" label={t('cryptoTailStrategy.form.scalpReversalGateEnabled')} valuePropName="checked" extra={t('cryptoTailStrategy.form.scalpReversalGateHint')}>
                <Switch />
              </Form.Item>
              <Form.Item name="scalpMinModelProb" label={t('cryptoTailStrategy.form.scalpMinModelProb')}>
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="scalpMinEdge" label={t('cryptoTailStrategy.form.scalpMinEdge')} extra={t('cryptoTailStrategy.form.scalpMinEdgeHint')}>
                <InputNumber min={0} max={1} step={0.005} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="scalpStatsSource" label={t('cryptoTailStrategy.form.scalpStatsSource')}>
                <Select
                  options={[
                    { label: t('cryptoTailStrategy.form.scalpStatsSourceHybrid'), value: 'HYBRID' },
                    { label: 'POLYMARKET', value: 'POLYMARKET' },
                    { label: 'BINANCE', value: 'BINANCE' }
                  ]}
                />
              </Form.Item>
              <Form.Item name="scalpStatsLookbackDays" label={t('cryptoTailStrategy.form.scalpStatsLookbackDays')}>
                <InputNumber min={1} step={1} precision={0} style={{ width: '100%' }} addonAfter="d" />
              </Form.Item>
              <Form.Item name="scalpStatsMinSamples" label={t('cryptoTailStrategy.form.scalpStatsMinSamples')}>
                <InputNumber min={0} step={1} precision={0} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="scalpRequireStats" label={t('cryptoTailStrategy.form.scalpRequireStats')} valuePropName="checked" extra={t('cryptoTailStrategy.form.scalpRequireStatsHint')}>
                <Switch />
              </Form.Item>
              <ScalpStatsCoverageAlert form={form} marketOptions={marketOptions} />
              <Form.Item>
                <Button
                  icon={<LineChartOutlined />}
                  onClick={() => { setReversalAdoptMode(true); setReversalModalOpen(true) }}
                >
                  {t('cryptoTailStrategy.form.statsCoverage.compareAndAdopt')}
                </Button>
              </Form.Item>

              <Form.Item style={{ marginBottom: 8 }}>
                <Typography.Text type="secondary">{t('cryptoTailStrategy.form.scalpDirectionSubsection')}</Typography.Text>
              </Form.Item>
              <Form.Item name="scalpRequireUnderlyingAgreement" label={t('cryptoTailStrategy.form.scalpRequireUnderlyingAgreement')} valuePropName="checked" extra={t('cryptoTailStrategy.form.scalpRequireUnderlyingAgreementHint')}>
                <Switch />
              </Form.Item>
              <Form.Item name="scalpEntryMinPwin" label={t('cryptoTailStrategy.form.scalpEntryMinPwin')} extra={t('cryptoTailStrategy.form.scalpEntryMinPwinHint')}>
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>

              <Form.Item style={{ marginBottom: 8 }}>
                <Typography.Text type="secondary">{t('cryptoTailStrategy.form.scalpConcurrencySubsection')}</Typography.Text>
              </Form.Item>
              <Form.Item name="scalpMaxConcurrentSameDirection" label={t('cryptoTailStrategy.form.scalpMaxConcurrentSameDirection')} extra={t('cryptoTailStrategy.form.scalpMaxConcurrentHint')}>
                <InputNumber min={1} step={1} precision={0} style={{ width: '100%' }} placeholder={t('cryptoTailStrategy.form.scalpOptionalPlaceholder')} />
              </Form.Item>

              <Form.Item style={{ marginBottom: 8 }}>
                <Typography.Text type="secondary">{t('cryptoTailStrategy.form.scalpExitModeSubsection')}</Typography.Text>
              </Form.Item>
              <Form.Item name="scalpHoldWinnerToSettle" label={t('cryptoTailStrategy.form.scalpHoldWinnerToSettle')} valuePropName="checked" extra={t('cryptoTailStrategy.form.scalpHoldWinnerToSettleHint')}>
                <Switch />
              </Form.Item>
              <Form.Item name="scalpTpPrice" label={t('cryptoTailStrategy.form.scalpTpPrice')} extra={t('cryptoTailStrategy.form.scalpTpPriceHint')}>
                <InputNumber min={0} max={1} step={0.005} style={{ width: '100%' }} stringMode />
              </Form.Item>

              <Form.Item style={{ marginBottom: 8 }}>
                <Typography.Text type="secondary">{t('cryptoTailStrategy.form.scalpStopSubsection')}</Typography.Text>
              </Form.Item>
              <Form.Item name="scalpStopEnabled" label={t('cryptoTailStrategy.form.scalpStopEnabled')} valuePropName="checked">
                <Switch />
              </Form.Item>
              <Form.Item name="scalpStopOffset" label={t('cryptoTailStrategy.form.scalpStopOffset')} extra={t('cryptoTailStrategy.form.scalpStopOffsetHint')}>
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="scalpStopMinPrice" label={t('cryptoTailStrategy.form.scalpStopMinPrice')} extra={t('cryptoTailStrategy.form.scalpStopMinPriceHint')}>
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="enableSmartHardStop" label={t('cryptoTailStrategy.form.scalpEnableSmartHardStop')} valuePropName="checked" extra={t('cryptoTailStrategy.form.scalpEnableSmartHardStopHint')}>
                <Switch />
              </Form.Item>
              <Form.Item name="holdToSettlePwin" label={t('cryptoTailStrategy.form.scalpHoldToSettlePwin')} extra={t('cryptoTailStrategy.form.scalpHoldToSettlePwinHint')}>
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="exitPollIntervalMs" label={t('cryptoTailStrategy.form.scalpExitPollIntervalMs')} extra={t('cryptoTailStrategy.form.scalpExitPollIntervalMsHint')}>
                <InputNumber min={500} step={500} style={{ width: '100%' }} addonAfter="ms" />
              </Form.Item>
              <Form.Item name="scalpMinOddsAfterEntry" label={t('cryptoTailStrategy.form.scalpMinOddsAfterEntry')} extra={t('cryptoTailStrategy.form.scalpMinOddsAfterEntryHint')}>
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="scalpUnderlyingStopEnabled" label={t('cryptoTailStrategy.form.scalpUnderlyingStopEnabled')} valuePropName="checked" extra={t('cryptoTailStrategy.form.scalpUnderlyingStopHint')}>
                <Switch />
              </Form.Item>
              <Form.Item name="scalpUnderlyingStopSigma" label={t('cryptoTailStrategy.form.scalpUnderlyingStopSigma')}>
                <InputNumber min={0} step={0.05} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="scalpReverseVelocityStopEnabled" label={t('cryptoTailStrategy.form.scalpReverseVelocityStopEnabled')} valuePropName="checked" extra={t('cryptoTailStrategy.form.scalpReverseVelocityHint')}>
                <Switch />
              </Form.Item>
              <Form.Item name="scalpMaxReverseVelocitySigma" label={t('cryptoTailStrategy.form.scalpMaxReverseVelocitySigma')}>
                <InputNumber min={0} step={0.05} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="scalpReverseVelocityWindowSeconds" label={t('cryptoTailStrategy.form.scalpReverseVelocityWindowSeconds')}>
                <InputNumber min={1} step={1} precision={0} style={{ width: '100%' }} addonAfter="s" />
              </Form.Item>
              <Form.Item name="scalpMinModelProbAfterEntry" label={t('cryptoTailStrategy.form.scalpMinModelProbAfterEntry')} extra={t('cryptoTailStrategy.form.scalpMinModelProbAfterEntryHint')}>
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="scalpMaxDiffRetracePct" label={t('cryptoTailStrategy.form.scalpMaxDiffRetracePct')} extra={t('cryptoTailStrategy.form.scalpMaxDiffRetracePctHint')}>
                <InputNumber min={0} max={1} step={0.05} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="scalpCatastropheBidFloor" label={t('cryptoTailStrategy.form.scalpCatastropheBidFloor')} extra={t('cryptoTailStrategy.form.scalpCatastropheBidFloorHint')}>
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item name="scalpCatastropheImmediate" label={t('cryptoTailStrategy.form.scalpCatastropheImmediate')} valuePropName="checked" extra={t('cryptoTailStrategy.form.scalpCatastropheImmediateHint')}>
                <Switch />
              </Form.Item>
              <Form.Item name="scalpSmartStopMinPwin" label={t('cryptoTailStrategy.form.scalpSmartStopMinPwin')} extra={t('cryptoTailStrategy.form.scalpSmartStopMinPwinHint')}>
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
            </>
          )}
          {barrierEnabled && (
            <>
              <Form.Item style={{ marginBottom: 12 }}>
                <Typography.Text strong>{t('cryptoTailStrategy.form.strongGapBoostSection')}</Typography.Text>
              </Form.Item>
              <Alert type="warning" showIcon style={{ marginBottom: 16 }} message={t('cryptoTailStrategy.form.strongGapBoostInfo')} />
              <Form.Item
                name="enableStrongGapBoost"
                valuePropName="checked"
                label={
                  <Space size={4}>
                    <span>{t('cryptoTailStrategy.form.enableStrongGapBoost')}</span>
                    <Tooltip title={t('cryptoTailStrategy.form.enableStrongGapBoostTip')}>
                      <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                    </Tooltip>
                  </Space>
                }
              >
                <Switch />
              </Form.Item>
              <Form.Item
                name="strongGapBoostShadow"
                valuePropName="checked"
                label={
                  <Space size={4}>
                    <span>{t('cryptoTailStrategy.form.strongGapBoostShadow')}</span>
                    <Tooltip title={t('cryptoTailStrategy.form.strongGapBoostShadowTip')}>
                      <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                    </Tooltip>
                  </Space>
                }
              >
                <Switch />
              </Form.Item>
              <Form.Item
                name="strongGapMinPwin"
                label={
                  <Space size={4}>
                    <span>{t('cryptoTailStrategy.form.strongGapMinPwin')}</span>
                    <Tooltip title={t('cryptoTailStrategy.form.strongGapMinPwinTip')}>
                      <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                    </Tooltip>
                  </Space>
                }
              >
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item
                name="strongGapMinSafeRatio"
                label={
                  <Space size={4}>
                    <span>{t('cryptoTailStrategy.form.strongGapMinSafeRatio')}</span>
                    <Tooltip title={t('cryptoTailStrategy.form.strongGapMinSafeRatioTip')}>
                      <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                    </Tooltip>
                  </Space>
                }
              >
                <InputNumber min={0} step={0.1} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item
                name="strongGapStakeMultiplier"
                label={
                  <Space size={4}>
                    <span>{t('cryptoTailStrategy.form.strongGapStakeMultiplier')}</span>
                    <Tooltip title={t('cryptoTailStrategy.form.strongGapStakeMultiplierTip')}>
                      <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                    </Tooltip>
                  </Space>
                }
              >
                <InputNumber min={1} step={0.1} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item
                name="ultraGapMinPwin"
                label={
                  <Space size={4}>
                    <span>{t('cryptoTailStrategy.form.ultraGapMinPwin')}</span>
                    <Tooltip title={t('cryptoTailStrategy.form.ultraGapMinPwinTip')}>
                      <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                    </Tooltip>
                  </Space>
                }
              >
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item
                name="ultraGapMinSafeRatio"
                label={
                  <Space size={4}>
                    <span>{t('cryptoTailStrategy.form.ultraGapMinSafeRatio')}</span>
                    <Tooltip title={t('cryptoTailStrategy.form.ultraGapMinSafeRatioTip')}>
                      <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                    </Tooltip>
                  </Space>
                }
              >
                <InputNumber min={0} step={0.1} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item
                name="ultraGapStakeMultiplier"
                label={
                  <Space size={4}>
                    <span>{t('cryptoTailStrategy.form.ultraGapStakeMultiplier')}</span>
                    <Tooltip title={t('cryptoTailStrategy.form.ultraGapStakeMultiplierTip')}>
                      <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                    </Tooltip>
                  </Space>
                }
              >
                <InputNumber min={1} step={0.1} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item
                name="maxStrongGapStakeMultiplier"
                label={
                  <Space size={4}>
                    <span>{t('cryptoTailStrategy.form.maxStrongGapStakeMultiplier')}</span>
                    <Tooltip title={t('cryptoTailStrategy.form.maxStrongGapStakeMultiplierTip')}>
                      <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                    </Tooltip>
                  </Space>
                }
              >
                <InputNumber min={1} step={0.1} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item
                name="maxBoostedAmountUsdc"
                label={
                  <Space size={4}>
                    <span>{t('cryptoTailStrategy.form.maxBoostedAmountUsdc')}</span>
                    <Tooltip title={t('cryptoTailStrategy.form.maxBoostedAmountUsdcTip')}>
                      <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                    </Tooltip>
                  </Space>
                }
              >
                <InputNumber min={0} step={1} style={{ width: '100%' }} stringMode addonAfter="USDC" />
              </Form.Item>
              <Form.Item
                name="maxBoostedPeriodExposureUsdc"
                label={
                  <Space size={4}>
                    <span>{t('cryptoTailStrategy.form.maxBoostedPeriodExposureUsdc')}</span>
                    <Tooltip title={t('cryptoTailStrategy.form.maxBoostedPeriodExposureUsdcTip')}>
                      <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                    </Tooltip>
                  </Space>
                }
              >
                <InputNumber min={0} step={1} style={{ width: '100%' }} stringMode addonAfter="USDC" />
              </Form.Item>
              <Form.Item
                name="allowBoostWithKelly"
                valuePropName="checked"
                label={
                  <Space size={4}>
                    <span>{t('cryptoTailStrategy.form.allowBoostWithKelly')}</span>
                    <Tooltip title={t('cryptoTailStrategy.form.allowBoostWithKellyTip')}>
                      <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                    </Tooltip>
                  </Space>
                }
              >
                <Switch />
              </Form.Item>
            </>
          )}
          <Form.Item name="enabled" valuePropName="checked">
            <Switch checkedChildren={t('common.enabled')} unCheckedChildren={t('common.disabled')} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={t('cryptoTailStrategy.form.importTitle')}
        open={importModalOpen}
        onCancel={() => setImportModalOpen(false)}
        onOk={handleApplyImport}
        okText={t('cryptoTailStrategy.form.importConfirm')}
        width={isMobile ? '100%' : 520}
        destroyOnClose
      >
        <Upload
          accept=".json,application/json"
          showUploadList={false}
          beforeUpload={(file) => {
            const reader = new FileReader()
            reader.onload = () => setImportText(String(reader.result ?? ''))
            reader.readAsText(file)
            return false
          }}
        >
          <Button icon={<UploadOutlined />} style={{ marginBottom: 12 }}>
            {t('cryptoTailStrategy.form.importUpload')}
          </Button>
        </Upload>
        <Input.TextArea
          value={importText}
          onChange={(e) => setImportText(e.target.value)}
          placeholder={t('cryptoTailStrategy.form.importPlaceholder')}
          autoSize={{ minRows: 8, maxRows: 16 }}
        />
      </Modal>

      <Modal
        title={
          <Space>
            <UnorderedListOutlined style={{ fontSize: 18, color: 'var(--ant-colorPrimary)' }} />
            <span>{t('cryptoTailStrategy.triggerRecords.title')}</span>
          </Space>
        }
        open={triggersModalOpen}
        onCancel={() => setTriggersModalOpen(false)}
        footer={null}
        width={Math.min(900, window.innerWidth - 48)}
        styles={{ body: { paddingTop: 16 } }}
      >
        <Card size="small" style={{ marginBottom: 16, background: 'var(--ant-colorFillQuaternary)' }}>
          <Space wrap align="center" size="middle">
            <Space align="center">
              <CalendarOutlined style={{ color: 'var(--ant-colorTextSecondary)' }} />
              <Typography.Text type="secondary">{t('cryptoTailStrategy.triggerRecords.timeRange')}</Typography.Text>
            </Space>
            <DatePicker.RangePicker
              value={triggerDateRange}
              onChange={onTriggerDateRangeChange}
              format="YYYY-MM-DD"
              placeholder={[t('cryptoTailStrategy.triggerRecords.startDate'), t('cryptoTailStrategy.triggerRecords.endDate')]}
              allowClear
              disabledDate={disabledTriggerEndDate}
              style={{ minWidth: 240 }}
            />
          </Space>
        </Card>
        <Tabs
          activeKey={triggerTab}
          onChange={onTriggerTabChange}
          items={[
            {
              key: 'success',
              label: t('cryptoTailStrategy.triggerRecords.successTab'),
              children: (
                <Spin spinning={triggersLoading}>
                  <Table
                    rowKey="id"
                    size="small"
                    dataSource={triggerTab === 'success' ? triggers : []}
                    locale={{
                      emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('cryptoTailStrategy.triggerRecords.emptySuccess')} />
                    }}
                    columns={[
                      {
                        title: t('cryptoTailStrategy.triggerRecords.triggerTime'),
                        dataIndex: 'createdAt',
                        key: 'createdAt',
                        width: 172,
                        render: (ts: number) => <Typography.Text>{new Date(ts).toLocaleString()}</Typography.Text>
                      },
                      {
                        title: t('cryptoTailStrategy.triggerRecords.direction'),
                        dataIndex: 'outcomeIndex',
                        key: 'outcomeIndex',
                        width: 80,
                        align: 'center',
                        render: (i: number) =>
                          i === 0 ? (
                            <Tag color="green">{t('cryptoTailStrategy.triggerRecords.up')}</Tag>
                          ) : (
                            <Tag color="volcano">{t('cryptoTailStrategy.triggerRecords.down')}</Tag>
                          )
                      },
                      {
                        title: t('cryptoTailStrategy.triggerRecords.triggerPrice'),
                        dataIndex: 'triggerPrice',
                        key: 'triggerPrice',
                        width: 100,
                        render: (v: string) => (formatNumber(v, 2) || '-')
                      },
                      {
                        title: t('cryptoTailStrategy.triggerRecords.amount'),
                        dataIndex: 'amountUsdc',
                        key: 'amountUsdc',
                        width: 110,
                        render: (v: string) => `$${formatUSDC(v)}`
                      },
                      {
                        title: t('cryptoTailStrategy.triggerRecords.realizedPnl'),
                        dataIndex: 'realizedPnl',
                        key: 'realizedPnl',
                        width: 100,
                        render: (v: string | undefined, r: CryptoTailStrategyTriggerDto) => {
                          if (v == null || v === '') return r.resolved ? formatUSDC('0') : '-'
                          const num = Number(v)
                          const formatted = formatUSDC(String(Math.abs(num)))
                          const text = num >= 0 ? `+${formatted}` : `-${formatted}`
                          const color = pnlColor(v)
                          return color ? <Typography.Text style={{ color, fontWeight: 500 }}>{text}</Typography.Text> : text
                        }
                      }
                    ]}
                    pagination={{
                      current: triggerPage,
                      pageSize: triggerPageSize,
                      total: triggersTotal,
                      showSizeChanger: true,
                      showTotal: (total) => t('cryptoTailStrategy.triggerRecords.totalCount').replace('{count}', String(total)),
                      onChange: onTriggerPageChange
                    }}
                    scroll={{ x: 540 }}
                  />
                </Spin>
              )
            },
            {
              key: 'fail',
              label: t('cryptoTailStrategy.triggerRecords.failTab'),
              children: (
                <Spin spinning={triggersLoading}>
                  <Table
                    rowKey="id"
                    size="small"
                    dataSource={triggerTab === 'fail' ? triggers : []}
                    locale={{
                      emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('cryptoTailStrategy.triggerRecords.emptyFail')} />
                    }}
                    columns={[
                      {
                        title: t('cryptoTailStrategy.triggerRecords.triggerTime'),
                        dataIndex: 'createdAt',
                        key: 'createdAt',
                        width: 172,
                        render: (ts: number) => <Typography.Text>{new Date(ts).toLocaleString()}</Typography.Text>
                      },
                      {
                        title: t('cryptoTailStrategy.triggerRecords.direction'),
                        dataIndex: 'outcomeIndex',
                        key: 'outcomeIndex',
                        width: 80,
                        align: 'center',
                        render: (i: number) =>
                          i === 0 ? (
                            <Tag color="green">{t('cryptoTailStrategy.triggerRecords.up')}</Tag>
                          ) : (
                            <Tag color="volcano">{t('cryptoTailStrategy.triggerRecords.down')}</Tag>
                          )
                      },
                      {
                        title: t('cryptoTailStrategy.triggerRecords.triggerPrice'),
                        dataIndex: 'triggerPrice',
                        key: 'triggerPrice',
                        width: 100,
                        render: (v: string) => (formatNumber(v, 2) || '-')
                      },
                      {
                        title: t('cryptoTailStrategy.triggerRecords.amount'),
                        dataIndex: 'amountUsdc',
                        key: 'amountUsdc',
                        width: 110,
                        render: (v: string) => `$${formatUSDC(v)}`
                      },
                      {
                        title: t('cryptoTailStrategy.triggerRecords.failReason'),
                        dataIndex: 'failReason',
                        key: 'failReason',
                        ellipsis: true,
                        render: (v: string | null | undefined) => {
                          const text = v ?? '-'
                          return text.length > 40 ? (
                            <Tooltip title={text}>
                              <Typography.Text ellipsis style={{ maxWidth: 280 }}>{text}</Typography.Text>
                            </Tooltip>
                          ) : (
                            <Typography.Text type={text === '-' ? 'secondary' : undefined}>{text}</Typography.Text>
                          )
                        }
                      }
                    ]}
                    pagination={{
                      current: triggerPage,
                      pageSize: triggerPageSize,
                      total: triggersTotal,
                      showSizeChanger: true,
                      showTotal: (total) => t('cryptoTailStrategy.triggerRecords.totalCount').replace('{count}', String(total)),
                      onChange: onTriggerPageChange
                    }}
                    scroll={{ x: 540 }}
                  />
                </Spin>
              )
            },
            {
              key: 'decision',
              label: t('cryptoTailStrategy.decisionLog.tab'),
              children: (
                <Spin spinning={decisionLoading}>
                  <Table
                    rowKey="id"
                    size="small"
                    dataSource={triggerTab === 'decision' ? decisionEvents : []}
                    locale={{
                      emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('cryptoTailStrategy.decisionLog.empty')} />
                    }}
                    columns={[
                      {
                        title: t('cryptoTailStrategy.decisionLog.time'),
                        dataIndex: 'createdAt',
                        key: 'createdAt',
                        width: 172,
                        render: (ts: number) => <Typography.Text>{new Date(ts).toLocaleString()}</Typography.Text>
                      },
                      {
                        title: t('cryptoTailStrategy.decisionLog.eventType'),
                        dataIndex: 'eventType',
                        key: 'eventType',
                        width: 110,
                        render: (v: string) => {
                          const label = t(`cryptoTailStrategy.decisionLog.eventType_${v}`, { defaultValue: v })
                          return <Tag color={v === 'SETTLED' ? 'purple' : v === 'GATE_PASSED' || v === 'ORDER_RESULT' ? 'green' : v === 'GATE_FAILED' ? 'volcano' : 'blue'}>{label}</Tag>
                        }
                      },
                      {
                        title: t('cryptoTailStrategy.decisionLog.gate'),
                        dataIndex: 'gateName',
                        key: 'gateName',
                        width: 110,
                        render: (v: string | null | undefined) => v ? <Typography.Text code>{v}</Typography.Text> : <Typography.Text type="secondary">-</Typography.Text>
                      },
                      {
                        title: t('cryptoTailStrategy.decisionLog.result'),
                        dataIndex: 'passed',
                        key: 'passed',
                        width: 80,
                        align: 'center',
                        render: (v: boolean | null | undefined) =>
                          v == null ? <Typography.Text type="secondary">-</Typography.Text>
                            : v ? <Tag color="green">{t('cryptoTailStrategy.decisionLog.passed')}</Tag>
                              : <Tag color="red">{t('cryptoTailStrategy.decisionLog.failed')}</Tag>
                      },
                      {
                        title: t('cryptoTailStrategy.decisionLog.direction'),
                        dataIndex: 'outcomeIndex',
                        key: 'outcomeIndex',
                        width: 70,
                        align: 'center',
                        render: (i: number | null | undefined) =>
                          i == null ? <Typography.Text type="secondary">-</Typography.Text>
                            : i === 0 ? <Tag color="green">{t('cryptoTailStrategy.triggerRecords.up')}</Tag>
                              : <Tag color="volcano">{t('cryptoTailStrategy.triggerRecords.down')}</Tag>
                      },
                      {
                        title: t('cryptoTailStrategy.decisionLog.reason'),
                        dataIndex: 'reason',
                        key: 'reason',
                        ellipsis: true,
                        render: (v: string | null | undefined, r: CryptoTailDecisionEventDto) => {
                          const text = v ?? '-'
                          const snapshot = r.payloadJson
                          const content = (
                            <Typography.Text ellipsis style={{ maxWidth: 260 }}>{text}</Typography.Text>
                          )
                          return snapshot ? (
                            <Tooltip title={<pre style={{ margin: 0, whiteSpace: 'pre-wrap', maxWidth: 360 }}>{snapshot}</pre>}>
                              {content}
                            </Tooltip>
                          ) : (
                            <Typography.Text type={text === '-' ? 'secondary' : undefined}>{text}</Typography.Text>
                          )
                        }
                      }
                    ]}
                    pagination={{
                      current: decisionPage,
                      pageSize: decisionPageSize,
                      total: decisionTotal,
                      showSizeChanger: true,
                      showTotal: (total) => t('cryptoTailStrategy.triggerRecords.totalCount').replace('{count}', String(total)),
                      onChange: onDecisionPageChange
                    }}
                    scroll={{ x: 640 }}
                  />
                </Spin>
              )
            }
          ]}
        />
      </Modal>
    </div>
  )
}

export default CryptoTailStrategyList
