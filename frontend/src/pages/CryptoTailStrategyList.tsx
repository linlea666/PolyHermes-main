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
  Typography
} from 'antd'
import type { Dayjs } from 'dayjs'
import dayjs from 'dayjs'
import { PlusOutlined, EditOutlined, UnorderedListOutlined, LineChartOutlined, InfoCircleOutlined, WarningOutlined, CalendarOutlined, FileTextOutlined, DeleteOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { useMediaQuery } from 'react-responsive'
import { apiService } from '../services/api'
import { useAccountStore } from '../store/accountStore'
import type { CryptoTailStrategyDto, CryptoTailStrategyTriggerDto, CryptoTailMarketOptionDto, CryptoTailPnlCurveResponse, CryptoTailDecisionEventDto } from '../types'
import { formatUSDC, formatNumber } from '../utils'
import { getVersionInfo } from '../utils/version'
import CryptoTailPnlCurveModal from './CryptoTailPnlCurveModal'

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
  const [editingId, setEditingId] = useState<number | null>(null)
  const [recommendingSigma, setRecommendingSigma] = useState(false)
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
      // 阶梯模式默认值（与后端 V52 默认一致）
      bracketEntryProb: '0.80',
      bracketEntryEdge: '0.04',
      bracketMaxEntryPrice: '0.90',
      tp1Price: '0.90',
      tp1Ratio: '0.50',
      tp1HoldPwin: '0.95',
      tp2Price: '0.95',
      tp2Ratio: '1.00',
      tp2HoldPwin: '0.99',
      holdToSettlePwin: '0.97',
      holdToSettleSeconds: 30,
      stopProb: '0.55',
      stopPrice: '0.70',
      forceExitBeforeSettleSeconds: 15,
      exitOrderType: 'FAK'
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
      // 阶梯模式
      bracketEntryProb: record.bracketEntryProb ?? '0.80',
      bracketEntryEdge: record.bracketEntryEdge ?? '0.04',
      bracketMaxEntryPrice: record.bracketMaxEntryPrice ?? '0.90',
      tp1Price: record.tp1Price ?? '0.90',
      tp1Ratio: record.tp1Ratio ?? '0.50',
      tp1HoldPwin: record.tp1HoldPwin ?? '0.95',
      tp2Price: record.tp2Price ?? '0.95',
      tp2Ratio: record.tp2Ratio ?? '1.00',
      tp2HoldPwin: record.tp2HoldPwin ?? '0.99',
      holdToSettlePwin: record.holdToSettlePwin ?? '0.97',
      holdToSettleSeconds: record.holdToSettleSeconds ?? 30,
      stopProb: record.stopProb ?? '0.55',
      stopPrice: record.stopPrice ?? '0.70',
      forceExitBeforeSettleSeconds: record.forceExitBeforeSettleSeconds ?? 15,
      exitOrderType: record.exitOrderType ?? 'FAK'
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
      const barrierOn = resolvedMode === 1 || resolvedMode === 2
      const bracketOn = resolvedMode === 2
      // 障碍/阶梯模式：旧价格区间/价差闸不生效，统一存默认值
      const bracketParams = bracketOn ? {
        bracketEntryProb: v.bracketEntryProb != null ? String(v.bracketEntryProb) : undefined,
        bracketEntryEdge: v.bracketEntryEdge != null ? String(v.bracketEntryEdge) : undefined,
        bracketMaxEntryPrice: v.bracketMaxEntryPrice != null ? String(v.bracketMaxEntryPrice) : undefined,
        tp1Price: v.tp1Price != null ? String(v.tp1Price) : undefined,
        tp1Ratio: v.tp1Ratio != null ? String(v.tp1Ratio) : undefined,
        tp1HoldPwin: v.tp1HoldPwin != null ? String(v.tp1HoldPwin) : undefined,
        tp2Price: v.tp2Price != null ? String(v.tp2Price) : undefined,
        tp2Ratio: v.tp2Ratio != null ? String(v.tp2Ratio) : undefined,
        tp2HoldPwin: v.tp2HoldPwin != null ? String(v.tp2HoldPwin) : undefined,
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
        entryProb: v.entryProb != null ? String(v.entryProb) : undefined,
        entryEdge: v.entryEdge != null ? String(v.entryEdge) : undefined,
        maxEntryPrice: v.maxEntryPrice != null ? String(v.maxEntryPrice) : undefined,
        costBuffer: v.costBuffer != null ? String(v.costBuffer) : undefined,
        barrierMinMarketProb: v.barrierMinMarketProb != null ? String(v.barrierMinMarketProb) : undefined,
        sigmaScale: v.sigmaScale != null ? String(v.sigmaScale) : undefined,
        dailyLossLimitUsdc: v.dailyLossLimitUsdc != null && v.dailyLossLimitUsdc !== '' ? String(v.dailyLossLimitUsdc) : null,
        maxConcurrentPositions: v.maxConcurrentPositions != null ? Number(v.maxConcurrentPositions) : null,
        takerFeeBps: v.takerFeeBps != null ? Number(v.takerFeeBps) : undefined,
        makerRebateBps: v.makerRebateBps != null ? Number(v.makerRebateBps) : undefined,
        gasCostUsdc: v.gasCostUsdc != null ? String(v.gasCostUsdc) : undefined,
        entryOrderType: v.entryOrderType != null ? String(v.entryOrderType) : undefined,
        entryFakSlippage: v.entryFakSlippage != null ? String(v.entryFakSlippage) : undefined,
        makerPriceOffset: v.makerPriceOffset != null ? String(v.makerPriceOffset) : undefined,
        makerCancelBeforeSettleSeconds: v.makerCancelBeforeSettleSeconds != null ? Number(v.makerCancelBeforeSettleSeconds) : undefined,
        makerFallbackTaker: v.makerFallbackTaker === true,
        calibrationGateEnabled: v.calibrationGateEnabled === true,
        probeAmountUsdc: v.probeAmountUsdc != null ? String(v.probeAmountUsdc) : undefined,
        calibrationMinSamples: v.calibrationMinSamples != null ? Number(v.calibrationMinSamples) : undefined,
        calibrationMaxError: v.calibrationMaxError != null ? String(v.calibrationMaxError) : undefined,
        sigmaMethod: v.sigmaMethod != null ? String(v.sigmaMethod) : undefined,
        ewmaLambda: v.ewmaLambda != null ? String(v.ewmaLambda) : undefined,
        kellyEnabled: v.kellyEnabled === true,
        kellyFraction: v.kellyFraction != null ? String(v.kellyFraction) : undefined,
        ...bracketParams
      }
      const payload = {
        accountId: v.accountId as number,
        name: v.name as string | undefined,
        marketSlugPrefix: v.marketSlugPrefix as string,
        intervalSeconds: interval,
        windowStartSeconds,
        windowEndSeconds,
        minPrice: barrierOn ? '0' : String(v.minPrice ?? 0),
        maxPrice: barrierOn ? '1' : (v.maxPrice != null ? String(v.maxPrice) : undefined),
        amountMode: v.amountMode as string,
        amountValue: String(v.amountValue ?? 0),
        spreadMode: barrierOn ? 'NONE' : ((v.spreadMode as string) || 'AUTO'),
        spreadValue: barrierOn ? undefined : (v.spreadMode === 'FIXED' && v.spreadValue != null ? String(v.spreadValue) : (v.spreadMode === 'AUTO' && v.spreadValue != null ? String(v.spreadValue) : undefined)),
        spreadDirection: barrierOn ? 'MIN' : (v.spreadDirection as string || 'MIN'),
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
        const tagColor = recordMode === 2 ? 'purple' : recordMode === 1 ? 'geekblue' : 'default'
        const tagKey = recordMode === 2
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
  const barrierEnabled = isBarrierMode || isBracketMode
  const entryOrderType = Form.useWatch('entryOrderType', form)
  const calibrationGateEnabled = Form.useWatch('calibrationGateEnabled', form)
  const sigmaMethod = Form.useWatch('sigmaMethod', form)
  const kellyEnabled = Form.useWatch('kellyEnabled', form)
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

      <Modal
        title={editingId ? t('cryptoTailStrategy.form.update') : t('cryptoTailStrategy.form.create')}
        open={formModalOpen}
        onCancel={() => setFormModalOpen(false)}
        onOk={handleFormSubmit}
        width={isMobile ? '100%' : 520}
        destroyOnClose
      >
        <Alert type="warning" showIcon message={t('cryptoTailStrategy.form.walletTip')} style={{ marginBottom: 16 }} />
        <Form form={form} layout="vertical" initialValues={{ amountMode: 'RATIO', maxPrice: '1', spreadMode: 'AUTO', spreadDirection: 'MIN', enabled: true }}>
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
            </Radio.Group>
          </Form.Item>
          <Form.Item name="barrierEnabled" hidden valuePropName="checked">
            <Switch />
          </Form.Item>
          {!barrierEnabled && (
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
          )}
          {!barrierEnabled && (
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
          {isBracketMode && (
            <>
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
              <Form.Item
                name="tp1Price"
                label={
                  <Space size={4}>
                    <span>{t('cryptoTailStrategy.form.tp1Price')}</span>
                    <Tooltip title={t('cryptoTailStrategy.form.tp1PriceTip')}>
                      <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                    </Tooltip>
                  </Space>
                }
              >
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item
                name="tp1Ratio"
                label={
                  <Space size={4}>
                    <span>{t('cryptoTailStrategy.form.tp1Ratio')}</span>
                    <Tooltip title={t('cryptoTailStrategy.form.tp1RatioTip')}>
                      <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                    </Tooltip>
                  </Space>
                }
              >
                <InputNumber min={0} max={1} step={0.05} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item
                name="tp1HoldPwin"
                label={
                  <Space size={4}>
                    <span>{t('cryptoTailStrategy.form.tp1HoldPwin')}</span>
                    <Tooltip title={t('cryptoTailStrategy.form.tp1HoldPwinTip')}>
                      <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                    </Tooltip>
                  </Space>
                }
              >
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item
                name="tp2Price"
                label={
                  <Space size={4}>
                    <span>{t('cryptoTailStrategy.form.tp2Price')}</span>
                    <Tooltip title={t('cryptoTailStrategy.form.tp2PriceTip')}>
                      <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                    </Tooltip>
                  </Space>
                }
              >
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item
                name="tp2Ratio"
                label={
                  <Space size={4}>
                    <span>{t('cryptoTailStrategy.form.tp2Ratio')}</span>
                    <Tooltip title={t('cryptoTailStrategy.form.tp2RatioTip')}>
                      <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                    </Tooltip>
                  </Space>
                }
              >
                <InputNumber min={0} max={1} step={0.05} style={{ width: '100%' }} stringMode />
              </Form.Item>
              <Form.Item
                name="tp2HoldPwin"
                label={
                  <Space size={4}>
                    <span>{t('cryptoTailStrategy.form.tp2HoldPwin')}</span>
                    <Tooltip title={t('cryptoTailStrategy.form.tp2HoldPwinTip')}>
                      <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                    </Tooltip>
                  </Space>
                }
              >
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
              </Form.Item>
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
                    { value: 'GTC', label: t('cryptoTailStrategy.form.exitOrderTypeGtc') },
                    { value: 'MAKER', label: t('cryptoTailStrategy.form.exitOrderTypeMaker') }
                  ]}
                />
              </Form.Item>
            </>
          )}
          <Form.Item name="enabled" valuePropName="checked">
            <Switch checkedChildren={t('common.enabled')} unCheckedChildren={t('common.disabled')} />
          </Form.Item>
        </Form>
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
