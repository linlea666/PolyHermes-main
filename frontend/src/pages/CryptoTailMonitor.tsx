import { useEffect, useState, useRef, useCallback } from 'react'
import {
  Card,
  Select,
  Space,
  Statistic,
  Row,
  Col,
  Typography,
  Spin,
  Empty,
  Alert,
  Radio,
  Button,
  Tooltip,
  Modal,
  InputNumber,
  message,
  Table,
  Tag
} from 'antd'
import { Popup as AntdMobilePopup } from 'antd-mobile'
import { ClockCircleOutlined, SyncOutlined, InfoCircleOutlined, ShoppingCartOutlined, DownloadOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { useMediaQuery } from 'react-responsive'
import * as echarts from 'echarts'
import type { EChartsOption } from 'echarts'
import { apiService } from '../services/api'
import { useWebSocketSubscription } from '../hooks/useWebSocket'
import { formatNumber, formatUSDC } from '../utils'
import type {
  CryptoTailStrategyDto,
  CryptoTailMonitorInitResponse,
  CryptoTailMonitorPushData,
  CryptoTailDecisionEventDto,
  CryptoTailCalibrationResponse,
  CryptoTailRecommendSigmaScaleResponse
} from '../types'

const { Title, Text } = Typography

// localStorage keys
const PERIOD_SWITCH_MODE_KEY = 'cryptoTailMonitor_periodSwitchMode'
const SELECTED_STRATEGY_ID_KEY = 'cryptoTailMonitor_selectedStrategyId'

/** 决策事件行键：优先用持久化 id，WS 推送未落库时（id=0）回退为复合键 */
const decisionRowKey = (e: CryptoTailDecisionEventDto): string =>
  e.id && e.id > 0
    ? `id-${e.id}`
    : `${e.correlationId}-${e.eventType}-${e.gateName ?? ''}-${e.createdAt}`

/** 分时图数据点：时间戳、BTC 价格 USDC、市场 Up/Down 价格 0-1 */
interface PriceDataPoint {
  time: number
  btcPrice: number | null
  marketPriceUp: number | null
  marketPriceDown: number | null
}

const CryptoTailMonitor: React.FC = () => {
  const { t } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })

  // 策略列表
  const [strategies, setStrategies] = useState<CryptoTailStrategyDto[]>([])
  const [strategiesLoading, setStrategiesLoading] = useState(false)

  // 选中的策略（从 localStorage 恢复）
  const [selectedStrategyId, setSelectedStrategyId] = useState<number | null>(() => {
    const cached = localStorage.getItem(SELECTED_STRATEGY_ID_KEY)
    return cached != null ? parseInt(cached, 10) : null
  })

  // 监控数据
  const [initData, setInitData] = useState<CryptoTailMonitorInitResponse | null>(null)
  const [pushData, setPushData] = useState<CryptoTailMonitorPushData | null>(null)
  const [initLoading, setInitLoading] = useState(false)

  // 实时决策时间线（仅障碍模式策略有数据）
  const [decisionEvents, setDecisionEvents] = useState<CryptoTailDecisionEventDto[]>([])
  const [calibration, setCalibration] = useState<CryptoTailCalibrationResponse | null>(null)
  const [exportingSnapshots, setExportingSnapshots] = useState(false)
  // sigmaScale 校准推荐（监控页一键应用）
  const [sigmaRec, setSigmaRec] = useState<CryptoTailRecommendSigmaScaleResponse | null>(null)
  const [sigmaRecLoading, setSigmaRecLoading] = useState(false)
  const selectedStrategy = strategies.find(s => s.id === selectedStrategyId)
  const selectedMode = typeof selectedStrategy?.mode === 'number'
    ? selectedStrategy?.mode
    : (selectedStrategy?.barrierEnabled === true ? 1 : 0)
  // 障碍模式与阶梯模式共用 pWin/校准/决策日志等监控面板（barrierEnabled 字段保持兼容）
  const barrierEnabled = selectedMode === 1 || selectedMode === 2

  // 导出单笔成交分析快照 CSV（用于离线回测/复盘）
  const handleExportSnapshots = async () => {
    if (!selectedStrategyId) return
    try {
      setExportingSnapshots(true)
      const res = await apiService.cryptoTailStrategy.tradeSnapshotExport({ strategyId: selectedStrategyId })
      if (res.data.code === 0 && res.data.data) {
        const { filename, csv, total } = res.data.data
        if (total === 0) {
          message.info(t('cryptoTailStrategy.decisionLog.exportEmpty'))
          return
        }
        const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' })
        const url = URL.createObjectURL(blob)
        const a = document.createElement('a')
        a.href = url
        a.download = filename || 'crypto_tail_snapshot.csv'
        document.body.appendChild(a)
        a.click()
        document.body.removeChild(a)
        URL.revokeObjectURL(url)
        message.success(t('cryptoTailStrategy.decisionLog.exportSuccess', { count: total }))
      } else {
        message.error(res.data.msg || t('cryptoTailStrategy.decisionLog.exportFailed'))
      }
    } catch {
      message.error(t('cryptoTailStrategy.decisionLog.exportFailed'))
    } finally {
      setExportingSnapshots(false)
    }
  }

  // 按已结算样本推荐 sigmaScale（监控页）
  const handleRecommendSigma = async () => {
    if (!selectedStrategyId) return
    setSigmaRecLoading(true)
    try {
      const res = await apiService.cryptoTailStrategy.recommendSigmaScale({ strategyId: selectedStrategyId })
      if (res.data.code !== 0 || !res.data.data) {
        message.error(res.data.msg || t('cryptoTailStrategy.form.sigmaRecommendFailed'))
        return
      }
      const data = res.data.data
      setSigmaRec(data)
      if (!data.enough || !data.recommendedSigmaScale) {
        message.warning(data.reason || t('cryptoTailStrategy.form.sigmaRecommendNotEnough'))
      }
    } catch {
      message.error(t('cryptoTailStrategy.form.sigmaRecommendFailed'))
    } finally {
      setSigmaRecLoading(false)
    }
  }

  // 一键应用推荐 sigmaScale：直接持久化到策略（onOk 返回 Promise，按钮自动显示 loading）
  const handleApplySigma = () => {
    if (!selectedStrategyId || !sigmaRec?.recommendedSigmaScale) return
    const recommended = sigmaRec.recommendedSigmaScale
    Modal.confirm({
      title: t('cryptoTailStrategy.calibration.applySigmaConfirmTitle'),
      content: t('cryptoTailStrategy.calibration.applySigmaConfirmContent', {
        current: sigmaRec.currentSigmaScale,
        recommended
      }),
      okText: t('common.confirm'),
      cancelText: t('common.cancel'),
      onOk: async () => {
        const res = await apiService.cryptoTailStrategy.update({ strategyId: selectedStrategyId, sigmaScale: recommended })
        if (res.data.code !== 0) {
          message.error(res.data.msg || t('common.failed'))
          throw new Error('apply sigma failed')
        }
        message.success(t('cryptoTailStrategy.calibration.applySigmaDone', { recommended }))
        // 本地更新当前值显示（避免额外请求），并清空推荐态
        setStrategies(prev => prev.map(s => (s.id === selectedStrategyId ? { ...s, sigmaScale: recommended } : s)))
        setSigmaRec(null)
      }
    })
  }

  // 价格历史数据（用于分时图）
  const [priceHistory, setPriceHistory] = useState<PriceDataPoint[]>([])
  const chartRef = useRef<HTMLDivElement>(null)
  const chartInstance = useRef<echarts.ECharts | null>(null)
  const marketChartRef = useRef<HTMLDivElement>(null)
  const marketChartInstance = useRef<echarts.ECharts | null>(null)
  const lastPeriodStartRef = useRef<number | null>(null)
  const selectedStrategyIdRef = useRef<number | null>(null)
  useEffect(() => {
    selectedStrategyIdRef.current = selectedStrategyId
  }, [selectedStrategyId])

  // 记录首次数据进入时间（用于中途进入时的横轴起点）
  const [firstDataTime, setFirstDataTime] = useState<number | null>(null)
  // 标记是否已切换过周期（切换后使用完整周期）
  const [hasSwitchedPeriod, setHasSwitchedPeriod] = useState<boolean>(false)
  // 周期切换模式：auto（自动切换）| manual（手动切换），从 localStorage 读取缓存
  const [periodSwitchMode, setPeriodSwitchMode] = useState<'auto' | 'manual'>(() => {
    const cached = localStorage.getItem(PERIOD_SWITCH_MODE_KEY)
    return (cached === 'auto' || cached === 'manual') ? cached : 'auto'
  })
  // 手动模式下，存储最新周期的数据（用户未切换时）
  const [pendingPeriodData, setPendingPeriodData] = useState<{
    periodStartUnix: number
    priceHistory: PriceDataPoint[]
    initData: CryptoTailMonitorInitResponse | null
    pushData: CryptoTailMonitorPushData | null
  } | null>(null)
  // 标记当前是否在查看旧周期（手动模式下）
  const [isViewingOldPeriod, setIsViewingOldPeriod] = useState<boolean>(false)

  // 手动下单状态
  const [manualOrderModal, setManualOrderModal] = useState<{
    visible: boolean
    direction: 'UP' | 'DOWN'
    price: string
    size: string
    totalAmount: string
    bestBid: string
    availableBalance: string
    periodStartUnix: number | null
  }>({
    visible: false,
    direction: 'UP',
    price: '',
    size: '',
    totalAmount: '',
    bestBid: '',
    availableBalance: '',
    periodStartUnix: null
  })
  const [ordering, setOrdering] = useState(false)

  // 检测周期切换，关闭弹窗并提示用户
  useEffect(() => {
    if (manualOrderModal.visible && manualOrderModal.periodStartUnix != null && pushData) {
      if (pushData.periodStartUnix !== manualOrderModal.periodStartUnix) {
        message.warning(t('cryptoTailMonitor.manualOrder.periodChanged'))
        handleCloseManualOrderModal()
      }
    }
  }, [pushData?.periodStartUnix, manualOrderModal.visible, manualOrderModal.periodStartUnix])

  // 获取策略列表
  useEffect(() => {
    const fetchStrategies = async () => {
      setStrategiesLoading(true)
      try {
        const res = await apiService.cryptoTailStrategy.list({ enabled: true })
        if (res.data.code === 0 && res.data.data) {
          const strategyList = res.data.data.list ?? []
          setStrategies(strategyList)
          // 从 localStorage 恢复选中的策略
          const cachedId = localStorage.getItem(SELECTED_STRATEGY_ID_KEY)
          const cachedStrategyId = cachedId != null ? parseInt(cachedId, 10) : null
          // 检查缓存的策略是否在列表中
          const isValidCached = cachedStrategyId != null && strategyList.some(s => s.id === cachedStrategyId)
          if (isValidCached) {
            setSelectedStrategyId(cachedStrategyId)
          } else if (strategyList.length > 0) {
            // 自动选择第一个策略
            setSelectedStrategyId(strategyList[0].id)
          }
        }
      } catch (e) {
        console.error('Failed to fetch strategies:', e)
      } finally {
        setStrategiesLoading(false)
      }
    }
    fetchStrategies()
  }, [])

  // 保存选中的策略到 localStorage
  useEffect(() => {
    if (selectedStrategyId != null) {
      localStorage.setItem(SELECTED_STRATEGY_ID_KEY, String(selectedStrategyId))
    }
  }, [selectedStrategyId])

  // 初始化监控数据
  useEffect(() => {
    if (!selectedStrategyId) {
      setInitData(null)
      setPushData(null)
      setPriceHistory([])
      setFirstDataTime(null)
      setHasSwitchedPeriod(false)
      setPendingPeriodData(null)
      setIsViewingOldPeriod(false)
      return
    }

    const initMonitor = async () => {
      setInitLoading(true)
      setPriceHistory([])
      setFirstDataTime(null)
      setHasSwitchedPeriod(false)
      setPendingPeriodData(null)
      setIsViewingOldPeriod(false)
      try {
        const res = await apiService.cryptoTailStrategy.monitorInit({ strategyId: selectedStrategyId })
        if (res.data.code === 0 && res.data.data) {
          setInitData(res.data.data)
        } else {
          setInitData(null)
        }
      } catch (e) {
        console.error('Failed to init monitor:', e)
        setInitData(null)
      } finally {
        setInitLoading(false)
      }
    }
    initMonitor()
  }, [selectedStrategyId])

  // WebSocket 订阅
  const handlePushData = useCallback((data: CryptoTailMonitorPushData) => {
    if (data.strategyId !== selectedStrategyId) return

    const btcPrice = data.currentPriceBtc != null && data.currentPriceBtc !== ''
      ? parseFloat(data.currentPriceBtc)
      : null
    const marketUp = data.currentPriceUp != null && data.currentPriceUp !== ''
      ? parseFloat(data.currentPriceUp)
      : null
    const marketDown = data.currentPriceDown != null && data.currentPriceDown !== ''
      ? parseFloat(data.currentPriceDown)
      : null
    const hasBtc = btcPrice != null && !Number.isNaN(btcPrice)
    const hasMarket = (marketUp != null && !Number.isNaN(marketUp)) || (marketDown != null && !Number.isNaN(marketDown))
    if (!hasBtc && !hasMarket) return

    const newPoint: PriceDataPoint = {
      time: data.timestamp,
      btcPrice: hasBtc ? btcPrice : null,
      marketPriceUp: hasMarket && marketUp != null && !Number.isNaN(marketUp) ? marketUp : null,
      marketPriceDown: hasMarket && marketDown != null && !Number.isNaN(marketDown) ? marketDown : null
    }

    // 用 ref 检测周期切换，避免因依赖 initData 导致回调频繁重建
    const pushPeriod = data.periodStartUnix
    const lastPeriod = lastPeriodStartRef.current

    if (pushPeriod != null && pushPeriod !== lastPeriod) {
      // 新周期到来：重新拉取 init（含 tokenIds），再更新状态
      lastPeriodStartRef.current = pushPeriod
      const marketTitle = (data as { marketTitle?: string }).marketTitle
      const applyFreshInit = (fresh: CryptoTailMonitorInitResponse) => {
        if (selectedStrategyIdRef.current !== fresh.strategyId) return
        const merged: CryptoTailMonitorInitResponse = {
          ...fresh,
          periodStartUnix: pushPeriod,
          marketTitle: marketTitle ?? fresh.marketTitle ?? ''
        }
        if (periodSwitchMode === 'manual' && lastPeriod != null) {
          setPendingPeriodData(p => p ? { ...p, initData: merged } : null)
        } else {
          setInitData(merged)
        }
      }
      if (periodSwitchMode === 'manual' && lastPeriod != null) {
        setPendingPeriodData({
          periodStartUnix: pushPeriod,
          priceHistory: [newPoint],
          initData: null,
          pushData: data
        })
        setIsViewingOldPeriod(true)
        apiService.cryptoTailStrategy.monitorInit({ strategyId: selectedStrategyId!, periodStartUnix: pushPeriod }).then(res => {
          if (res.data?.code === 0 && res.data?.data) applyFreshInit(res.data.data)
          else {
            setInitData(prev => {
              if (prev) setPendingPeriodData(p => p ? { ...p, initData: { ...prev, periodStartUnix: pushPeriod, marketTitle: marketTitle ?? prev.marketTitle ?? '' } } : null)
              return prev ?? null
            })
          }
        }).catch(() => {
          setInitData(prev => {
            if (prev) setPendingPeriodData(p => p ? { ...p, initData: { ...prev, periodStartUnix: pushPeriod, marketTitle: marketTitle ?? prev.marketTitle ?? '' } } : null)
            return prev ?? null
          })
        })
      } else {
        if (lastPeriod != null) setHasSwitchedPeriod(true)
        setFirstDataTime(newPoint.time)
        setPriceHistory([newPoint])
        setPushData(data)
        setIsViewingOldPeriod(false)
        setPendingPeriodData(null)
        apiService.cryptoTailStrategy.monitorInit({ strategyId: selectedStrategyId!, periodStartUnix: pushPeriod }).then(res => {
          if (res.data?.code === 0 && res.data?.data) applyFreshInit(res.data.data)
          else setInitData(prev => prev ? { ...prev, periodStartUnix: pushPeriod, marketTitle: marketTitle ?? prev.marketTitle ?? '' } : null)
        }).catch(() => {
          setInitData(prev => prev ? { ...prev, periodStartUnix: pushPeriod, marketTitle: marketTitle ?? prev.marketTitle ?? '' } : null)
        })
      }
      return
    } else {
      // 同周期：追加数据
      if (periodSwitchMode === 'manual' && isViewingOldPeriod && pendingPeriodData) {
        // 手动模式下，更新 pending 数据
        const minIntervalMs = 1_000
        setPendingPeriodData(prev => {
          if (!prev) return null
          const lastTime = prev.priceHistory.length > 0 ? prev.priceHistory[prev.priceHistory.length - 1].time : 0
          if (prev.priceHistory.length > 0 && newPoint.time - lastTime < minIntervalMs) {
            return { ...prev, pushData: data }
          }
          const maxPoints = 300
          const newHistory = [...prev.priceHistory, newPoint].slice(-maxPoints)
          return { ...prev, priceHistory: newHistory, pushData: data }
        })
      } else {
        setFirstDataTime(prev => {
          if (prev == null) {
            return newPoint.time
          }
          return prev
        })
        const minIntervalMs = 1_000
        setPriceHistory(prev => {
          const lastTime = prev.length > 0 ? prev[prev.length - 1].time : 0
          if (prev.length > 0 && newPoint.time - lastTime < minIntervalMs) {
            return prev
          }
          const maxPoints = 300
          const newHistory = [...prev, newPoint]
          return newHistory.slice(-maxPoints)
        })
        setPushData(data)
      }
    }
  }, [selectedStrategyId, periodSwitchMode, isViewingOldPeriod, pendingPeriodData])

  // 手动切换到最新周期
  const handleSwitchToLatestPeriod = useCallback(() => {
    if (pendingPeriodData) {
      setPriceHistory(pendingPeriodData.priceHistory)
      setFirstDataTime(pendingPeriodData.priceHistory[0]?.time ?? null)
      if (pendingPeriodData.initData) {
        setInitData(pendingPeriodData.initData)
      }
      if (pendingPeriodData.pushData) {
        setPushData(pendingPeriodData.pushData)
      }
      setHasSwitchedPeriod(true)
      setIsViewingOldPeriod(false)
      setPendingPeriodData(null)
    }
  }, [pendingPeriodData])

  const channel = selectedStrategyId ? `crypto_tail_monitor_${selectedStrategyId}` : ''
  useWebSocketSubscription(channel, handlePushData)

  // 决策时间线：切换策略时拉取最近历史（仅障碍模式）
  useEffect(() => {
    if (!selectedStrategyId || !barrierEnabled) {
      setDecisionEvents([])
      return
    }
    let cancelled = false
    apiService.cryptoTailStrategy.decisionLog({ strategyId: selectedStrategyId, page: 1, pageSize: 50 })
      .then(res => {
        if (cancelled) return
        if (res.data.code === 0 && res.data.data) {
          setDecisionEvents(res.data.data.list ?? [])
        }
      })
      .catch(() => { /* 决策日志拉取失败不影响监控 */ })
    return () => { cancelled = true }
  }, [selectedStrategyId, barrierEnabled])

  // 校准统计 + 放量闸状态：切换策略时拉取（仅障碍模式）；结算后随轮询刷新
  useEffect(() => {
    setSigmaRec(null)
    if (!selectedStrategyId || !barrierEnabled) {
      setCalibration(null)
      return
    }
    let cancelled = false
    const fetchCalibration = () => {
      apiService.cryptoTailStrategy.calibration({ strategyId: selectedStrategyId })
        .then(res => {
          if (cancelled) return
          if (res.data.code === 0 && res.data.data) {
            setCalibration(res.data.data)
          }
        })
        .catch(() => { /* 校准统计拉取失败不影响监控 */ })
    }
    fetchCalibration()
    const timer = window.setInterval(fetchCalibration, 30000)
    return () => { cancelled = true; window.clearInterval(timer) }
  }, [selectedStrategyId, barrierEnabled])

  // 决策时间线：实时增量订阅，前插去重，最多保留 100 条
  // WS 推送的事件在异步落库前 id 可能为 0，故用复合键去重，避免漏显或 key 冲突
  const handleDecisionPush = useCallback((data: CryptoTailDecisionEventDto) => {
    if (data.strategyId !== selectedStrategyId) return
    setDecisionEvents(prev => {
      const key = decisionRowKey(data)
      if (prev.some(e => decisionRowKey(e) === key)) return prev
      return [data, ...prev].slice(0, 100)
    })
  }, [selectedStrategyId])

  const decisionChannel = selectedStrategyId && barrierEnabled ? `crypto_tail_decision_${selectedStrategyId}` : ''
  useWebSocketSubscription(decisionChannel, handleDecisionPush)

  // 图表容器仅在 initData 存在时渲染，故在更新图表时懒初始化
  useEffect(() => {
    const handleResize = () => {
      chartInstance.current?.resize()
      marketChartInstance.current?.resize()
    }
    window.addEventListener('resize', handleResize)
    return () => {
      window.removeEventListener('resize', handleResize)
      chartInstance.current?.dispose()
      chartInstance.current = null
      marketChartInstance.current?.dispose()
      marketChartInstance.current = null
    }
  }, [])

  // 切换策略时销毁并重新初始化图表实例
  useEffect(() => {
    if (chartInstance.current) {
      chartInstance.current.dispose()
      chartInstance.current = null
    }
    if (marketChartInstance.current) {
      marketChartInstance.current.dispose()
      marketChartInstance.current = null
    }
  }, [selectedStrategyId])

  // 更新图表：分时图为 BTC 价格 USDC
  useEffect(() => {
    if (!initData) return
    if (chartRef.current && !chartInstance.current) {
      chartInstance.current = echarts.init(chartRef.current)
    }
    if (!chartInstance.current) return

    const periodStartMs = (initData.periodStartUnix ?? 0) * 1000
    const periodEndMs = periodStartMs + (initData.intervalSeconds ?? 300) * 1000

    // data.timestamp 为毫秒，firstDataTime 已是 ms，无需再乘 1000
    const firstDataMs = firstDataTime != null ? firstDataTime : null
    const isMidEntry = firstDataMs != null && !hasSwitchedPeriod && firstDataMs > periodStartMs
    // 中途进入时横轴起点为进入时刻，否则为周期起点
    const xAxisMin = isMidEntry ? firstDataMs : periodStartMs

    const btcData: [number, number | null][] = priceHistory.length > 0
      ? priceHistory.map(p => [p.time, p.btcPrice])
      : []
    const openBtc = pushData?.openPriceBtc ?? initData.openPriceBtc
    const openBtcNum = openBtc != null ? parseFloat(openBtc) : null

    const hasAnyBtcData = btcData.some(([, v]) => v != null && !Number.isNaN(v))
    const btcPlaceholderTime = xAxisMin
    const displayBtcData: [number, number | null][] = hasAnyBtcData
      ? btcData
      : (openBtcNum != null ? [[btcPlaceholderTime, openBtcNum]] : [])
    const minSpreadUpRaw = pushData?.minSpreadLineUp ?? initData.autoMinSpreadUp
    const minSpreadDownRaw = pushData?.minSpreadLineDown ?? initData.autoMinSpreadDown
    const minSpreadUp = minSpreadUpRaw != null && minSpreadUpRaw !== '' ? parseFloat(minSpreadUpRaw) : null
    const minSpreadDown = minSpreadDownRaw != null && minSpreadDownRaw !== '' ? parseFloat(minSpreadDownRaw) : null

    const validPrices = displayBtcData.flatMap(([, v]) => (v != null && !Number.isNaN(v) ? [v] : []))
    const defaultRange = 500
    let yMin: number | undefined
    let yMax: number | undefined
    if (validPrices.length > 0) {
      const dataMin = Math.min(...validPrices)
      const dataMax = Math.max(...validPrices)
      const dataRange = dataMax - dataMin
      const minRange = Math.max(Math.abs(dataMax) * 0.01, 10)
      const range = Math.max(dataRange, minRange)
      const padding = range * 0.25
      yMin = dataMin - padding
      yMax = dataMax + padding
    } else if (openBtcNum != null) {
      const spread = minSpreadUp ?? minSpreadDown ?? defaultRange
      const halfRange = spread * 1.5
      yMin = openBtcNum - halfRange
      yMax = openBtcNum + halfRange
    }

    const markLineData: Array<{ name?: string; yAxis?: number; xAxis?: number; lineStyle: { type: 'dashed' | 'solid'; color: string }; label?: { show: boolean; formatter?: string }; emphasis?: { label?: { show?: boolean; formatter?: string } } }> = []
    if (openBtcNum != null && !Number.isNaN(openBtcNum)) {
      markLineData.push({
        name: t('cryptoTailMonitor.chart.openPrice'),
        yAxis: openBtcNum,
        lineStyle: { type: 'dashed', color: '#999' }
      })
    }
    const isMaxSpread = (initData.spreadDirection ?? 'MIN') === 'MAX'
    const spreadLineLabelKey = isMaxSpread ? 'cryptoTailMonitor.chart.maxSpreadLine' : 'cryptoTailMonitor.chart.minSpreadLine'
    if (openBtcNum != null && minSpreadUp != null && !Number.isNaN(minSpreadUp)) {
      markLineData.push({
        name: t(spreadLineLabelKey) + ' Up',
        yAxis: openBtcNum + minSpreadUp,
        lineStyle: { type: 'dashed', color: '#ff4d4f' }
      })
    }
    if (openBtcNum != null && minSpreadDown != null && !Number.isNaN(minSpreadDown)) {
      markLineData.push({
        name: t(spreadLineLabelKey) + ' Down',
        yAxis: openBtcNum - minSpreadDown,
        lineStyle: { type: 'dashed', color: '#ff4d4f' }
      })
    }
    // 时间窗口两条竖线：灰色虚线，悬停时显示标签
    const windowStartMs = periodStartMs + (initData.windowStartSeconds ?? 0) * 1000
    const windowEndMs = periodStartMs + (initData.windowEndSeconds ?? 0) * 1000
    if (windowStartMs > xAxisMin && windowStartMs < periodEndMs) {
      markLineData.push({
        xAxis: windowStartMs,
        lineStyle: { type: 'dashed', color: 'rgba(128, 128, 128, 0.9)' },
        label: { show: false },
        emphasis: { label: { show: true, formatter: t('cryptoTailMonitor.chart.timeWindowStart') } }
      })
    }
    if (windowEndMs > xAxisMin && windowEndMs < periodEndMs && windowEndMs !== windowStartMs) {
      markLineData.push({
        xAxis: windowEndMs,
        lineStyle: { type: 'dashed', color: 'rgba(128, 128, 128, 0.9)' },
        label: { show: false },
        emphasis: { label: { show: true, formatter: t('cryptoTailMonitor.chart.timeWindowEnd') } }
      })
    }

    const periodStartUnixSec = initData.periodStartUnix ?? 0
    const option: EChartsOption = {
      tooltip: {
        trigger: 'axis',
        confine: true,
        padding: [6, 8],
        formatter: (params: unknown) => {
          const arr = params as Array<{ seriesName: string; name: string | number; value: number | [number, number]; axisValue?: number }>
          const priceParam = arr.find(p => p.seriesName === t('cryptoTailMonitor.chart.price'))
          if (!priceParam) return ''
          const val = Array.isArray(priceParam.value) ? priceParam.value[1] : priceParam.value
          if (val == null || Number.isNaN(val)) return ''
          // 优先从 value[0] 取时间戳（毫秒），否则用 axisValue 或 name
          const rawTime = Array.isArray(priceParam.value)
            ? priceParam.value[0]
            : (priceParam.axisValue ?? priceParam.name)
          let timeStr = ''
          if (typeof rawTime === 'number' && !Number.isNaN(rawTime)) {
            const offsetSec = Math.floor(rawTime / 1000) - periodStartUnixSec
            const mins = Math.floor(offsetSec / 60)
            const secs = Math.abs(offsetSec) % 60
            timeStr = `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`
          } else if (rawTime != null && rawTime !== '') {
            timeStr = String(rawTime)
          } else {
            timeStr = '--'
          }
          return `<span style="font-size:12px">${timeStr} &nbsp; $${Number(val).toFixed(2)}</span>`
        }
      },
      legend: {
        show: true,
        top: 0
      },
      grid: {
        left: '3%',
        right: '4%',
        bottom: '3%',
        top: '12%',
        containLabel: true
      },
      xAxis: {
        type: 'time',
        min: xAxisMin,
        max: periodEndMs,
        axisLabel: {
          formatter: (val: number) => {
            const offsetSec = Math.floor(val / 1000) - periodStartUnixSec
            const mins = Math.floor(offsetSec / 60)
            const secs = Math.abs(offsetSec) % 60
            return `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`
          }
        }
      },
      yAxis: {
        type: 'value',
        scale: true,
        min: yMin,
        max: yMax,
        axisLabel: {
          formatter: (value: number) => value.toFixed(0)
        }
      },
      series: [
        {
          name: t('cryptoTailMonitor.chart.price'),
          type: 'line',
          data: displayBtcData,
          smooth: true,
          symbol: displayBtcData.length === 1 ? 'circle' : 'none',
          symbolSize: 4,
          lineStyle: { width: 2, color: '#1890ff' },
          areaStyle: {
            color: {
              type: 'linear',
              x: 0,
              y: 0,
              x2: 0,
              y2: 1,
              colorStops: [
                { offset: 0, color: 'rgba(24, 144, 255, 0.3)' },
                { offset: 1, color: 'rgba(24, 144, 255, 0.05)' }
              ]
            }
          },
          markLine: markLineData.length > 0 ? { symbol: ['none', 'none'], data: markLineData } : undefined,
          // 添加满足条件的价差区域（浅绿色背景）
          markArea: (() => {
            if (openBtcNum == null) return undefined
            const areas: Array<[{ yAxis: number }, { yAxis: number }]> = []
            if (isMaxSpread) {
              // 最大价差：价差 <= 配置值触发，满足条件为靠近开盘价的带状区域
              if (minSpreadUp != null && !Number.isNaN(minSpreadUp)) {
                areas.push([
                  { yAxis: openBtcNum },
                  { yAxis: openBtcNum + minSpreadUp }
                ])
              }
              if (minSpreadDown != null && !Number.isNaN(minSpreadDown)) {
                areas.push([
                  { yAxis: openBtcNum - minSpreadDown },
                  { yAxis: openBtcNum }
                ])
              }
            } else {
              // 最小价差：价差 >= 配置值触发，满足条件为远离开盘价的两侧
              if (minSpreadUp != null && !Number.isNaN(minSpreadUp)) {
                areas.push([
                  { yAxis: openBtcNum + minSpreadUp },
                  { yAxis: yMax ?? openBtcNum + minSpreadUp * 2 }
                ])
              }
              if (minSpreadDown != null && !Number.isNaN(minSpreadDown)) {
                areas.push([
                  { yAxis: yMin ?? openBtcNum - minSpreadDown * 2 },
                  { yAxis: openBtcNum - minSpreadDown }
                ])
              }
            }
            return areas.length > 0 ? {
              silent: true,
              data: areas,
              itemStyle: { color: 'rgba(82, 196, 26, 0.12)' }
            } : undefined
          })()
        }
      ]
    }

    chartInstance.current.setOption(option, true)
    chartInstance.current.resize()
  }, [priceHistory, initData, pushData, firstDataTime, hasSwitchedPeriod, t])

  // 更新市场分时图：Polymarket 价格 0-1
  useEffect(() => {
    if (!initData) return
    if (marketChartRef.current && !marketChartInstance.current) {
      marketChartInstance.current = echarts.init(marketChartRef.current)
    }
    if (!marketChartInstance.current) return

    const periodStartMs = (initData.periodStartUnix ?? 0) * 1000
    const periodEndMs = periodStartMs + (initData.intervalSeconds ?? 300) * 1000

    // data.timestamp 为毫秒，firstDataTime 已是 ms，无需再乘 1000
    const firstDataMs = firstDataTime != null ? firstDataTime : null
    const isMidEntry = firstDataMs != null && !hasSwitchedPeriod && firstDataMs > periodStartMs
    const xAxisMin = isMidEntry ? firstDataMs : periodStartMs

    const toMs = (t: number) => (t > 0 && t < 1e12 ? t * 1000 : t)
    let marketUpData: [number, number | null][] = priceHistory.length > 0
      ? priceHistory.map(p => [toMs(p.time), p.marketPriceUp])
      : []
    let marketDownData: [number, number | null][] = priceHistory.length > 0
      ? priceHistory.map(p => [toMs(p.time), p.marketPriceDown])
      : []

    // 若推送有最新价且与当前周期一致，追加到末端使曲线显示到最新价格
    if (pushData && pushData.periodStartUnix === (initData.periodStartUnix ?? 0)) {
      const ts = pushData.timestamp
      const lastTime = marketUpData.length > 0 ? marketUpData[marketUpData.length - 1][0] : 0
      const tsMs = ts > 0 && ts < 1e12 ? ts * 1000 : ts
      if (tsMs >= lastTime) {
        const up = pushData.currentPriceUp != null && pushData.currentPriceUp !== '' ? parseFloat(pushData.currentPriceUp) : null
        const down = pushData.currentPriceDown != null && pushData.currentPriceDown !== '' ? parseFloat(pushData.currentPriceDown) : null
        const upVal = up != null && !Number.isNaN(up) ? up : (down != null && !Number.isNaN(down) ? 1 - down : null)
        const downVal = down != null && !Number.isNaN(down) ? down : (up != null && !Number.isNaN(up) ? 1 - up : null)
        if (upVal != null) marketUpData = [...marketUpData, [tsMs, upVal]]
        if (downVal != null) marketDownData = [...marketDownData, [tsMs, downVal]]
      }
    }

    const minPrice = parseFloat(initData.minPrice)
    const maxPrice = parseFloat(initData.maxPrice)
    const midPrice = (minPrice + maxPrice) / 2
    const isValid = (v: number | null): v is number => v != null && !Number.isNaN(v)
    const validUp: [number, number][] = marketUpData.filter((point): point is [number, number] => isValid(point[1]))
    const validDown: [number, number][] = marketDownData.filter((point): point is [number, number] => isValid(point[1]))
    const hasAnyMarketData = validUp.length > 0 || validDown.length > 0
    const placeholderTime = xAxisMin
    const finalMarketUp: [number, number][] = hasAnyMarketData ? validUp : [[placeholderTime, midPrice]]
    const finalMarketDown: [number, number][] = hasAnyMarketData ? validDown : [[placeholderTime, midPrice]]

    const periodStartUnixSec = initData.periodStartUnix ?? 0
    const windowStartMs = periodStartMs + (initData.windowStartSeconds ?? 0) * 1000
    const windowEndMs = periodStartMs + (initData.windowEndSeconds ?? 0) * 1000
    const timeWindowMarkLine: Array<{ xAxis: number; lineStyle: { type: 'dashed'; color: string }; label: { show: boolean }; emphasis: { label: { show: boolean; formatter: string } } }> = []
    if (windowStartMs > xAxisMin && windowStartMs < periodEndMs) {
      timeWindowMarkLine.push({
        xAxis: windowStartMs,
        lineStyle: { type: 'dashed', color: 'rgba(128, 128, 128, 0.9)' },
        label: { show: false },
        emphasis: { label: { show: true, formatter: t('cryptoTailMonitor.chart.timeWindowStart') } }
      })
    }
    if (windowEndMs > xAxisMin && windowEndMs < periodEndMs && windowEndMs !== windowStartMs) {
      timeWindowMarkLine.push({
        xAxis: windowEndMs,
        lineStyle: { type: 'dashed', color: 'rgba(128, 128, 128, 0.9)' },
        label: { show: false },
        emphasis: { label: { show: true, formatter: t('cryptoTailMonitor.chart.timeWindowEnd') } }
      })
    }
    const option: EChartsOption = {
      tooltip: {
        trigger: 'axis',
        formatter: (params: unknown) => {
          const arr = params as Array<{ seriesName: string; name: string | number; value: number | [number, number]; axisValue?: number }>
          const upParam = arr.find(p => p.seriesName === t('cryptoTailMonitor.chart.marketUp'))
          const downParam = arr.find(p => p.seriesName === t('cryptoTailMonitor.chart.marketDown'))
          const firstParam = arr[0]
          const rawTime = firstParam && Array.isArray(firstParam.value)
            ? firstParam.value[0]
            : (firstParam?.axisValue ?? firstParam?.name)
          let timeStr = ''
          if (typeof rawTime === 'number' && !Number.isNaN(rawTime)) {
            const offsetSec = Math.floor(rawTime / 1000) - periodStartUnixSec
            const mins = Math.floor(offsetSec / 60)
            const secs = Math.abs(offsetSec) % 60
            timeStr = `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`
          } else if (rawTime != null && rawTime !== '') {
            timeStr = String(rawTime)
          } else {
            timeStr = '--'
          }
          let html = `<div><div>${t('cryptoTailMonitor.chart.time')}: ${timeStr}</div>`
          const upVal = Array.isArray(upParam?.value) ? upParam?.value[1] : upParam?.value
          const downVal = Array.isArray(downParam?.value) ? downParam?.value[1] : downParam?.value
          if (upVal != null && !Number.isNaN(upVal)) html += `<div>Up: ${Number(upVal).toFixed(4)}</div>`
          if (downVal != null && !Number.isNaN(downVal)) html += `<div>Down: ${Number(downVal).toFixed(4)}</div>`
          html += '</div>'
          return html
        }
      },
      legend: {
        show: true,
        top: 0,
        data: [t('cryptoTailMonitor.chart.marketUp'), t('cryptoTailMonitor.chart.marketDown')]
      },
      grid: {
        left: '3%',
        right: '4%',
        bottom: '3%',
        top: '15%',
        containLabel: true
      },
      xAxis: {
        type: 'time',
        min: xAxisMin,
        max: periodEndMs,
        axisLabel: {
          formatter: (val: number) => {
            const offsetSec = Math.floor(val / 1000) - periodStartUnixSec
            const mins = Math.floor(offsetSec / 60)
            const secs = Math.abs(offsetSec) % 60
            return `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`
          }
        }
      },
      yAxis: {
        type: 'value',
        min: 0,
        max: 1,
        interval: 0.2,
        axisLabel: { formatter: (v: number) => v.toFixed(1) }
      },
      series: [
        {
          name: t('cryptoTailMonitor.chart.marketUp'),
          type: 'line',
          data: finalMarketUp,
          smooth: true,
          symbol: 'circle',
          symbolSize: 4,
          showSymbol: true,
          connectNulls: true,
          lineStyle: { width: 2, color: '#1890ff' },
          itemStyle: { color: '#1890ff' },
          markArea: {
            silent: true,
            itemStyle: { color: 'rgba(82, 196, 26, 0.12)' },
            data: [[{ yAxis: minPrice }, { yAxis: maxPrice }]]
          },
          markLine: timeWindowMarkLine.length > 0 ? { symbol: ['none', 'none'], data: timeWindowMarkLine } : undefined
        },
        {
          name: t('cryptoTailMonitor.chart.marketDown'),
          type: 'line',
          data: finalMarketDown,
          smooth: true,
          symbol: 'circle',
          symbolSize: 4,
          showSymbol: true,
          connectNulls: true,
          lineStyle: { width: 2, color: '#fa8c16' },
          itemStyle: { color: '#fa8c16' }
        }
      ]
    }

    marketChartInstance.current.setOption(option, true)
    marketChartInstance.current.resize()
  }, [priceHistory, initData, pushData, firstDataTime, hasSwitchedPeriod, t])

  // 格式化剩余时间
  const formatRemainingTime = (seconds: number): string => {
    const mins = Math.floor(seconds / 60)
    const secs = seconds % 60
    return `${mins}:${secs.toString().padStart(2, '0')}`
  }

  // 官方标的价格：只展示 RTDS/Chainlink 等结算同源价源；legacy/debug 单独显示。
  const openPrice = pushData?.officialOpen ?? initData?.officialOpen
  const currentPrice = pushData?.officialClose ?? initData?.officialClose
  const legacyOpen = pushData?.legacyOpen ?? initData?.legacyOpen ?? initData?.openPriceBtc
  const legacyClose = pushData?.legacyClose ?? initData?.legacyClose ?? pushData?.currentPriceBtc
  const legacySource = pushData?.legacyPriceSource ?? initData?.legacyPriceSource
  const currentSpread = pushData?.spreadBtc
  const officialSource = pushData?.officialPriceSource ?? initData?.officialPriceSource
  const officialAge = pushData?.officialPriceAgeMs ?? initData?.officialPriceAgeMs
  const priceReadyReason = pushData?.priceReadyReason ?? initData?.priceReadyReason
  const fallbackUsed = pushData?.fallbackUsed ?? initData?.fallbackUsed
  const minSpreadUpStr = pushData?.minSpreadLineUp ?? initData?.autoMinSpreadUp
  const minSpreadDownStr = pushData?.minSpreadLineDown ?? initData?.autoMinSpreadDown
  const minSpreadUpVal = minSpreadUpStr != null && minSpreadUpStr !== '' ? parseFloat(minSpreadUpStr) : null
  const minSpreadDownVal = minSpreadDownStr != null && minSpreadDownStr !== '' ? parseFloat(minSpreadDownStr) : null
  const minSpreadLineNum = [minSpreadUpVal, minSpreadDownVal].filter((v): v is number => v != null && !Number.isNaN(v))
  const spreadBelowThreshold = currentSpread != null && currentSpread !== '' && minSpreadLineNum.length > 0 &&
    parseFloat(currentSpread) < Math.min(...minSpreadLineNum)

  // 手动下单：打开弹窗
  const handleOpenManualOrderModal = async (direction: 'UP' | 'DOWN') => {
    if (!pushData) {
      message.warning(t('cryptoTailMonitor.manualOrder.priceNotLoaded'))
      return
    }
    const bestBid = direction === 'UP' ? pushData.currentPriceUp : pushData.currentPriceDown
    if (!bestBid) {
      message.warning(t('cryptoTailMonitor.manualOrder.priceNotLoaded'))
      return
    }
    // 计算默认价格：最优 bid × 1.1，限制在 0~0.99 之间
    const rawPrice = parseFloat(bestBid) * 1.1
    const defaultPrice = Math.min(0.99, Math.max(0, rawPrice))
    
    // 获取账户余额
    let availableBalance = '0'
    if (initData?.accountId) {
      try {
        const balanceRes = await apiService.accounts.balance({ accountId: initData.accountId })
        if (balanceRes.data.code === 0 && balanceRes.data.data?.availableBalance) {
          availableBalance = balanceRes.data.data.availableBalance
        }
      } catch (e) {
        console.error('获取账户余额失败:', e)
      }
    }
    
    // 使用策略配置的金额
    let defaultAmountUsdc = 10
    if (initData?.amountMode === 'FIXED' && initData?.amountValue) {
      defaultAmountUsdc = parseFloat(initData.amountValue)
    } else if (initData?.amountMode === 'RATIO' && initData?.amountValue) {
      // RATIO 模式：按比例计算
      const balanceNum = parseFloat(availableBalance)
      const ratio = parseFloat(initData.amountValue || '10')
      defaultAmountUsdc = balanceNum * ratio / 100
      // 至少保留 1 USDC
      if (defaultAmountUsdc < 1) {
        message.warning(t('cryptoTailMonitor.manualOrder.insufficientBalance'))
        return
      }
    }
    
    // 计算默认数量（保留2位小数，用于手动下单）
    let defaultSize = (defaultAmountUsdc / defaultPrice).toFixed(2)
    // 确保至少 1 张
    if (parseFloat(defaultSize) < 1) {
      defaultSize = '1.00'
    }
    
    // 重新计算总金额（基于实际数量）
    const defaultTotalAmount = (defaultPrice * parseFloat(defaultSize)).toFixed(2)
    
    setManualOrderModal({
      visible: true,
      direction,
      price: defaultPrice.toFixed(4),
      size: defaultSize,
      totalAmount: defaultTotalAmount,
      bestBid,
      availableBalance,
      periodStartUnix: pushData.periodStartUnix
    })
  }

  // 获取最新价
  const handleFetchLatestPrice = async () => {
    if (!pushData) {
      message.warning(t('cryptoTailMonitor.manualOrder.priceNotLoaded'))
      return
    }
    const latestPrice = manualOrderModal.direction === 'UP' 
      ? pushData.currentPriceUp 
      : pushData.currentPriceDown
    if (!latestPrice) {
      message.warning(t('cryptoTailMonitor.manualOrder.priceNotLoaded'))
      return
    }
    const price = Math.min(0.99, parseFloat(latestPrice))
    const size = parseFloat(manualOrderModal.size)
    const totalAmount = (price * size).toFixed(2)
    setManualOrderModal({ 
      ...manualOrderModal, 
      price: price.toFixed(4),
      totalAmount 
    })
    message.success(t('cryptoTailMonitor.manualOrder.priceUpdated'))
  }

  const handleCloseManualOrderModal = () => {
    setManualOrderModal({
      visible: false,
      direction: 'UP',
      price: '',
      size: '',
      totalAmount: '',
      bestBid: '',
      availableBalance: '',
      periodStartUnix: null
    })
  }

  const handlePriceChange = (value: number | null) => {
    if (value === null) return
    const clamped = Math.min(0.99, Math.max(0, value))
    const price = clamped.toFixed(4)
    const size = parseFloat(manualOrderModal.size)
    const totalAmount = (clamped * size).toFixed(2)
    setManualOrderModal({ ...manualOrderModal, price, totalAmount })
  }

  const handleSizeChange = (value: number | null) => {
    if (value === null) return
    const size = value.toFixed(2)
    const priceRaw = parseFloat(manualOrderModal.price)
    const price = Math.min(0.99, Math.max(0, priceRaw))
    const totalAmount = (price * value).toFixed(2)
    setManualOrderModal({ ...manualOrderModal, size, totalAmount, price: price.toFixed(4) })
  }

  // 计算最大数量（截位处理）
  const handleMaxSize = () => {
    const price = parseFloat(manualOrderModal.price)
    const balance = parseFloat(manualOrderModal.availableBalance)
    
    if (price <= 0 || balance <= 0) {
      message.warning(t('cryptoTailMonitor.manualOrder.invalidPriceOrBalance'))
      return
    }
    
    // 最大数量 = 余额 / 价格，保留2位小数
    let maxSize = Math.floor((balance / price) * 100) / 100
    
    // 确保至少 1 张
    if (maxSize < 1) {
      message.warning(t('cryptoTailMonitor.manualOrder.insufficientBalanceForMax'))
      return
    }
    
    const totalAmount = (price * maxSize).toFixed(2)
    setManualOrderModal({ 
      ...manualOrderModal, 
      size: maxSize.toFixed(2),
      totalAmount 
    })
    message.success(t('cryptoTailMonitor.manualOrder.maxSizeUpdated'))
  }

  const handleManualOrder = async () => {
    if (!initData || !pushData) return
    try {
      setOrdering(true)
      const tokenIds: string[] = []
      if (initData.tokenIdUp) tokenIds.push(initData.tokenIdUp)
      if (initData.tokenIdDown) tokenIds.push(initData.tokenIdDown)
      const request = {
        strategyId: initData.strategyId,
        periodStartUnix: pushData.periodStartUnix,
        direction: manualOrderModal.direction,
        price: Math.min(0.99, Math.max(0, parseFloat(manualOrderModal.price) || 0)).toFixed(4),
        size: manualOrderModal.size,
        marketTitle: pushData.marketTitle || initData.marketTitle,
        tokenIds
      }
      const res = await apiService.cryptoTailStrategy.manualOrder(request)
      if (res.data.code === 0 && res.data.data?.success) {
        message.success(t('cryptoTailMonitor.manualOrder.success'))
        handleCloseManualOrderModal()
      } else {
        const reason = res.data.msg?.trim() || 'unknown'
        message.error(t('cryptoTailMonitor.manualOrder.failed', { reason }))
      }
    } catch (error: unknown) {
      const err = error as { response?: { data?: { msg?: string } }; message?: string }
      const reason = err?.response?.data?.msg?.trim() ?? err?.message?.trim() ?? 'unknown'
      message.error(t('cryptoTailMonitor.manualOrder.failed', { reason }))
    } finally {
      setOrdering(false)
    }
  }

  return (
    <div style={{ padding: isMobile ? 12 : 24 }}>
      <Title level={2} style={{ marginBottom: 16, fontSize: isMobile ? 20 : 24 }}>
        {t('cryptoTailMonitor.title')}
      </Title>

      {/* 顶部控制区 */}
      <Card style={{ marginBottom: 16 }}>
        <Space direction={isMobile ? 'vertical' : 'horizontal'} size="middle" style={{ width: '100%' }}>
          <Space direction={isMobile ? 'vertical' : 'horizontal'} size="small" style={{ width: isMobile ? '100%' : 'auto' }}>
            <Text strong>{t('cryptoTailMonitor.selectStrategy')}</Text>
            <Select
              style={{ minWidth: isMobile ? '100%' : 300, width: isMobile ? '100%' : 'auto' }}
              loading={strategiesLoading}
              value={selectedStrategyId}
              onChange={(id) => setSelectedStrategyId(id)}
              placeholder={t('cryptoTailMonitor.selectStrategyPlaceholder')}
              popupMatchSelectWidth={false}
              dropdownStyle={{ minWidth: isMobile ? 280 : 'auto', wordWrap: 'break-word', whiteSpace: 'normal' }}
              optionLabelProp="label"
              options={strategies.map(s => ({
                label: `${s.name || s.marketSlugPrefix} (${s.intervalSeconds === 300 ? '5m' : '15m'})`,
                value: s.id,
                style: { whiteSpace: 'normal', wordWrap: 'break-word' }
              }))}
            />
          </Space>
          {selectedStrategyId && (
            <Space>
              <Text strong>{t('cryptoTailMonitor.periodSwitch.mode')}</Text>
              <Radio.Group
                value={periodSwitchMode}
                onChange={(e) => {
                  const newMode = e.target.value
                  setPeriodSwitchMode(newMode)
                  localStorage.setItem(PERIOD_SWITCH_MODE_KEY, newMode)
                  if (newMode === 'auto' && isViewingOldPeriod && pendingPeriodData) {
                    handleSwitchToLatestPeriod()
                  }
                }}
                optionType="button"
                buttonStyle="solid"
                size="small"
              >
                <Tooltip title={t('cryptoTailMonitor.periodSwitch.autoDesc')}>
                  <Radio.Button value="auto">{t('cryptoTailMonitor.periodSwitch.auto')}</Radio.Button>
                </Tooltip>
                <Tooltip title={t('cryptoTailMonitor.periodSwitch.manualDesc')}>
                  <Radio.Button value="manual">{t('cryptoTailMonitor.periodSwitch.manual')}</Radio.Button>
                </Tooltip>
              </Radio.Group>
            </Space>
          )}
        </Space>
      </Card>

      {initLoading ? (
        <Spin spinning style={{ display: 'flex', justifyContent: 'center', padding: 100 }} />
      ) : !initData ? (
        <Empty description={t('cryptoTailMonitor.noData')} />
      ) : (
        <>
          {/* 状态卡片：最小宽度填满整行，间距 16 */}
          <Row gutter={16} style={{ marginBottom: 16 }}>
            <Col flex="1" style={{ minWidth: 140 }}>
              <Card size="small" style={{ width: '100%', minWidth: 0 }}>
                <Statistic
                  title={t('cryptoTailMonitor.stat.openPrice')}
                  value={openPrice ? formatNumber(openPrice, 2) : '-'}
                  precision={2}
                />
              </Card>
            </Col>
            <Col flex="1" style={{ minWidth: 140 }}>
              <Card size="small" style={{ width: '100%', minWidth: 0 }}>
                <Statistic
                  title={t('cryptoTailMonitor.stat.currentPrice')}
                  value={currentPrice ? formatNumber(currentPrice, 2) : '-'}
                  precision={2}
                  valueStyle={{ color: isMobile ? undefined : '#1890ff' }}
                />
              </Card>
            </Col>
            <Col flex="1" style={{ minWidth: 140 }}>
              <Card size="small" style={{ width: '100%', minWidth: 0 }}>
                <Statistic
                  title={t('cryptoTailMonitor.stat.spread')}
                  value={(() => {
                    if (currentSpread == null || currentSpread === '') return '-'
                    const num = parseFloat(currentSpread)
                    if (Number.isNaN(num)) return '-'
                    const formatted = formatNumber(currentSpread, 2)
                    return num >= 0 ? `+${formatted}` : formatted
                  })()}
                  precision={2}
                  valueStyle={{
                    color: spreadBelowThreshold ? '#ff4d4f' : undefined
                  }}
                />
              </Card>
            </Col>
            <Col flex="1" style={{ minWidth: 140 }}>
              <Card size="small" style={{ width: '100%', minWidth: 0 }}>
                <Statistic
                  title={t('cryptoTailMonitor.stat.remainingTime')}
                  value={pushData ? formatRemainingTime(pushData.remainingSeconds) : '-'}
                  prefix={<ClockCircleOutlined />}
                  valueStyle={{
                    color: pushData && pushData.remainingSeconds < 60 ? '#ff4d4f' : undefined
                  }}
                />
              </Card>
            </Col>
            <Col flex="1" style={{ minWidth: 140 }}>
              <Card size="small" style={{ width: '100%', minWidth: 0 }}>
                <Statistic
                  title={(initData.spreadDirection ?? 'MIN') === 'MAX' ? t('cryptoTailMonitor.stat.configuredSpreadMax') : t('cryptoTailMonitor.stat.configuredSpreadMin')}
                  valueRender={() => {
                    const mode = initData.minSpreadMode ?? 'NONE'
                    if (mode === 'NONE') return <Text type="secondary">-</Text>
                    if (mode === 'FIXED') {
                      const v = initData.minSpreadValue
                      return v != null && v !== '' ? formatNumber(v, 2) : '-'
                    }
                    const up = minSpreadUpStr != null && minSpreadUpStr !== '' ? formatNumber(minSpreadUpStr, 2) : null
                    const down = minSpreadDownStr != null && minSpreadDownStr !== '' ? formatNumber(minSpreadDownStr, 2) : null
                    if (up == null && down == null) return <Text type="secondary">-</Text>
                    return (
                      <Text style={{ fontSize: 13, lineHeight: 1.4 }}>
                        {up != null && <span style={{ display: 'block' }}>Up: {up}</span>}
                        {down != null && <span style={{ display: 'block' }}>Down: {down}</span>}
                      </Text>
                    )
                  }}
                />
              </Card>
            </Col>
          </Row>

          <Card size="small" style={{ marginBottom: 16 }}>
            <Space wrap>
              <Text strong>官方价源</Text>
              <Tag color={fallbackUsed ? 'orange' : (priceReadyReason === 'OK' ? 'green' : 'red')}>
                {officialSource || '-'}
              </Tag>
              <Text type="secondary">状态: {priceReadyReason || '-'}</Text>
              <Text type="secondary">年龄: {officialAge != null ? `${officialAge}ms` : '-'}</Text>
              <Text type="secondary">Open: {openPrice ? formatNumber(openPrice, 2) : '-'}</Text>
              <Text type="secondary">Close: {currentPrice ? formatNumber(currentPrice, 2) : '-'}</Text>
            </Space>
          </Card>

          <Card size="small" style={{ marginBottom: 16 }}>
            <Space wrap>
              <Text strong>Legacy / Debug</Text>
              <Tag color="default">{legacySource || '-'}</Tag>
              <Text type="secondary">Open: {legacyOpen ? formatNumber(legacyOpen, 2) : '-'}</Text>
              <Text type="secondary">Close: {legacyClose ? formatNumber(legacyClose, 2) : '-'}</Text>
              <Text type="secondary">Spread: {currentSpread ? formatNumber(currentSpread, 2) : '-'}</Text>
            </Space>
          </Card>

          <Card size="small" style={{ marginBottom: 16 }}>
            <Space wrap>
              <Text strong>Outcome 盘口</Text>
              <Tag color={pushData?.outcomeDirection === 'UP' ? 'green' : 'default'}>UP</Tag>
              <Text type="secondary">Bid: {pushData?.outcomeBestBidUp ?? '-'}</Text>
              <Text type="secondary">Ask: {pushData?.outcomeBestAskUp ?? '-'}</Text>
              <Text type="secondary">Spread: {pushData?.outcomeSpreadUp ?? '-'}</Text>
              <Tag color={pushData?.outcomeDirection === 'DOWN' ? 'green' : 'default'}>DOWN</Tag>
              <Text type="secondary">Bid: {pushData?.outcomeBestBidDown ?? '-'}</Text>
              <Text type="secondary">Ask: {pushData?.outcomeBestAskDown ?? '-'}</Text>
              <Text type="secondary">Spread: {pushData?.outcomeSpreadDown ?? '-'}</Text>
            </Space>
          </Card>

          {/* 手动模式下：周期结束提示 */}
          {periodSwitchMode === 'manual' && isViewingOldPeriod && (
            <Alert
              type="warning"
              showIcon
              icon={<InfoCircleOutlined />}
              style={{ marginBottom: 16 }}
              message={t('cryptoTailMonitor.periodSwitch.periodEnded')}
              description={
                <Space direction="vertical" size="small">
                  <Text>{t('cryptoTailMonitor.periodSwitch.newPeriodAvailable')}</Text>
                  <Button
                    type="primary"
                    icon={<SyncOutlined />}
                    onClick={handleSwitchToLatestPeriod}
                    size="small"
                  >
                    {t('cryptoTailMonitor.periodSwitch.switchToLatest')}
                  </Button>
                </Space>
              }
            />
          )}

          {/* 价格区间提示 */}
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message={`${t('cryptoTailMonitor.priceRange')}: ${formatNumber(initData.minPrice, 2)} ~ ${formatNumber(initData.maxPrice, 2)} | ${t('cryptoTailMonitor.timeWindow')}: ${Math.floor(initData.windowStartSeconds / 60)}:${(initData.windowStartSeconds % 60).toString().padStart(2, '0')} ~ ${Math.floor(initData.windowEndSeconds / 60)}:${(initData.windowEndSeconds % 60).toString().padStart(2, '0')}`}
          />

          {(pushData?.positionId || pushData?.wickReversalScore != null) && (
            <Card title="持仓 / 影线状态" style={{ marginBottom: 16 }}>
              <Row gutter={[16, 12]}>
                <Col xs={12} md={6}>
                  <Text type="secondary">当前持仓</Text>
                  <div>{pushData?.positionId ? `#${pushData.positionId} ${pushData.positionOutcomeIndex === 0 ? 'UP' : 'DOWN'}` : '-'}</div>
                </Col>
                <Col xs={12} md={6}>
                  <Text type="secondary">买入价 / 可卖价</Text>
                  <div>{pushData?.entryFillPrice ?? '-'} / {pushData?.currentBestBid ?? '-'}</div>
                </Col>
                <Col xs={12} md={6}>
                  <Text type="secondary">浮动盈亏</Text>
                  <div style={{ color: Number(pushData?.floatingPnl ?? 0) < 0 ? '#cf1322' : '#3f8600' }}>
                    {pushData?.floatingPnl != null ? formatUSDC(pushData.floatingPnl) : '-'}
                  </div>
                </Col>
                <Col xs={12} md={6}>
                  <Text type="secondary">最高可卖 / 回撤</Text>
                  <div>{pushData?.peakBid ?? '-'} / {pushData?.drawdownFromPeak ?? '-'}</div>
                </Col>
                <Col xs={12} md={6}>
                  <Text type="secondary">止损线</Text>
                  <div>{pushData?.stopLossLine ?? '-'}</div>
                </Col>
                <Col xs={12} md={6}>
                  <Text type="secondary">移动止损线</Text>
                  <div>{pushData?.trailingStartLine ?? '-'} / {pushData?.trailingStopLine ?? '-'}</div>
                </Col>
                <Col xs={12} md={6}>
                  <Text type="secondary">止盈线</Text>
                  <div>{pushData?.takeProfitLine1 ?? '-'} / {pushData?.takeProfitLine2 ?? '-'}</div>
                </Col>
                <Col xs={12} md={6}>
                  <Text type="secondary">退出原因</Text>
                  <div>{pushData?.exitReason ?? '-'}</div>
                </Col>
                <Col xs={12} md={6}>
                  <Text type="secondary">影线评分</Text>
                  <div>{pushData?.wickReversalScore ?? '-'} / {pushData?.wickContinuationScore ?? '-'} {pushData?.wickRejectionSide ?? ''}</div>
                </Col>
                <Col xs={12} md={6}>
                  <Text type="secondary">影线质量</Text>
                  <div>{pushData?.wickTickCount ?? '-'} ticks / {pushData?.wickRangeSigmaRatio ?? '-'} sigma</div>
                </Col>
                <Col xs={12} md={6}>
                  <Text type="secondary">收盘位置</Text>
                  <div>{pushData?.wickClosePosition ?? '-'} {pushData?.wickQualityReason ?? ''}</div>
                </Col>
              </Row>
            </Card>
          )}

          {/* 价格分时图 */}
          <Card title={`${initData.marketSlugPrefix || 'BTC'} ${t('cryptoTailMonitor.chart.priceChart')}`}>
            <div
              ref={chartRef}
              style={{
                width: '100%',
                height: isMobile ? 200 : 240
              }}
            />
          </Card>

          {/* 市场分时图 */}
          <Card
            title={t('cryptoTailMonitor.chart.marketTitle')}
            extra={
              pushData?.currentPriceUp != null || pushData?.currentPriceDown != null ? (
                <Space size="middle">
                  <Text type="secondary">{t('cryptoTailMonitor.chart.latestPrice')}:</Text>
                  {pushData.currentPriceUp != null && pushData.currentPriceUp !== '' && (
                    <Text>Up {formatNumber(pushData.currentPriceUp, 4)}</Text>
                  )}
                  {pushData.currentPriceDown != null && pushData.currentPriceDown !== '' && (
                    <Text>Down {formatNumber(pushData.currentPriceDown, 4)}</Text>
                  )}
                </Space>
              ) : null
            }
            style={{ marginTop: 16 }}
          >
            <div
              ref={marketChartRef}
              style={{
                width: '100%',
                height: isMobile ? 200 : 240
              }}
            />
          </Card>

          {/* 手动下单 */}
          {!isMobile ? (
            <Card title={t('cryptoTailMonitor.manualOrder.title')} style={{ marginTop: 16 }}>
              <Row gutter={16}>
                <Col span={12}>
                  <Button
                    type="primary"
                    icon={<ShoppingCartOutlined />}
                    disabled={!pushData || pushData.triggered || pushData.periodEnded}
                    onClick={() => handleOpenManualOrderModal('UP')}
                    loading={ordering}
                    block
                    style={{ backgroundColor: '#1890ff', borderColor: '#1890ff' }}
                  >
                    {t('cryptoTailMonitor.manualOrder.buttonUp')} {pushData?.currentPriceUp ? `(${formatNumber(pushData.currentPriceUp, 2)})` : ''}
                  </Button>
                </Col>
                <Col span={12}>
                  <Button
                    type="primary"
                    icon={<ShoppingCartOutlined />}
                    disabled={!pushData || pushData.triggered || pushData.periodEnded}
                    onClick={() => handleOpenManualOrderModal('DOWN')}
                    loading={ordering}
                    block
                    style={{ backgroundColor: '#fa8c16', borderColor: '#fa8c16' }}
                  >
                    {t('cryptoTailMonitor.manualOrder.buttonDown')} {pushData?.currentPriceDown ? `(${formatNumber(pushData.currentPriceDown, 2)})` : ''}
                  </Button>
                </Col>
              </Row>
            </Card>
          ) : null}

          {/* 校准统计 + 放量闸（仅障碍模式） */}
          {barrierEnabled && calibration && (
            <Card
              title={t('cryptoTailStrategy.calibration.title')}
              style={{ marginTop: 16 }}
              extra={
                calibration.gateEnabled ? (
                  <Tag color={calibration.scalingMode === 'PROBE' ? 'orange' : 'green'}>
                    {calibration.scalingMode === 'PROBE'
                      ? t('cryptoTailStrategy.calibration.modeProbe', { amount: formatUSDC(calibration.probeAmountUsdc) })
                      : t('cryptoTailStrategy.calibration.modeFull')}
                  </Tag>
                ) : (
                  <Tag>{t('cryptoTailStrategy.calibration.gateOff')}</Tag>
                )
              }
            >
              <Row gutter={[16, 16]}>
                <Col xs={12} sm={8} md={4}>
                  <Statistic title={t('cryptoTailStrategy.calibration.sampleCount')} value={calibration.sampleCount} />
                </Col>
                <Col xs={12} sm={8} md={4}>
                  <Statistic
                    title={t('cryptoTailStrategy.calibration.winRate')}
                    value={calibration.winRate != null ? `${(Number(calibration.winRate) * 100).toFixed(1)}%` : '-'}
                  />
                </Col>
                <Col xs={12} sm={8} md={4}>
                  <Statistic
                    title={t('cryptoTailStrategy.calibration.calibrationError')}
                    value={calibration.calibrationError != null ? (Number(calibration.calibrationError) * 100).toFixed(2) : '-'}
                    suffix={calibration.calibrationError != null ? 'pp' : ''}
                  />
                </Col>
                <Col xs={12} sm={8} md={4}>
                  <Statistic
                    title={t('cryptoTailStrategy.calibration.totalNetPnl')}
                    value={formatUSDC(calibration.totalNetPnl)}
                    suffix="USDC"
                    valueStyle={{ color: Number(calibration.totalNetPnl) >= 0 ? '#3f8600' : '#cf1322' }}
                  />
                </Col>
                <Col xs={12} sm={8} md={4}>
                  <Statistic
                    title={t('cryptoTailStrategy.calibration.avgNetPnl')}
                    value={calibration.avgNetPnl != null ? formatUSDC(calibration.avgNetPnl) : '-'}
                    suffix={calibration.avgNetPnl != null ? 'USDC' : ''}
                    valueStyle={{ color: Number(calibration.avgNetPnl ?? 0) >= 0 ? '#3f8600' : '#cf1322' }}
                  />
                </Col>
              </Row>
              {calibration.reason && (
                <Text type="secondary" style={{ display: 'block', marginTop: 8 }}>{calibration.reason}</Text>
              )}
              {/* sigmaScale 一键校准推荐 + 应用 */}
              <div style={{ marginTop: 12, paddingTop: 12, borderTop: '1px solid #f0f0f0' }}>
                <Space size={8} wrap>
                  <Text strong>{t('cryptoTailStrategy.calibration.sigmaCurrent')}</Text>
                  <Text>{selectedStrategy?.sigmaScale ?? '-'}</Text>
                  <Button size="small" loading={sigmaRecLoading} onClick={handleRecommendSigma}>
                    {t('cryptoTailStrategy.form.sigmaRecommendBtn')}
                  </Button>
                  <Tooltip title={t('cryptoTailStrategy.calibration.sigmaApplyHint')}>
                    <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                  </Tooltip>
                </Space>
                {sigmaRec && sigmaRec.enough && sigmaRec.recommendedSigmaScale && (
                  <Space size={8} wrap style={{ display: 'flex', marginTop: 8 }}>
                    <Text type="secondary">
                      {t('cryptoTailStrategy.calibration.sigmaRecResult', {
                        recommended: sigmaRec.recommendedSigmaScale,
                        before: sigmaRec.currentError != null ? (Number(sigmaRec.currentError) * 100).toFixed(2) : '-',
                        after: sigmaRec.recommendedError != null ? (Number(sigmaRec.recommendedError) * 100).toFixed(2) : '-',
                        count: sigmaRec.sampleCount
                      })}
                    </Text>
                    <Button size="small" type="primary" onClick={handleApplySigma}>
                      {t('cryptoTailStrategy.calibration.sigmaApplyBtn')}
                    </Button>
                  </Space>
                )}
              </div>
              <Table
                rowKey="bucket"
                size="small"
                style={{ marginTop: 12 }}
                pagination={false}
                dataSource={calibration.bins}
                scroll={{ x: 'max-content' }}
                locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('cryptoTailStrategy.calibration.empty')} /> }}
                columns={[
                  {
                    title: t('cryptoTailStrategy.calibration.binRange'),
                    key: 'range',
                    render: (_: unknown, r: import('../types').CryptoTailCalibrationBin) =>
                      `${(Number(r.rangeLow) * 100).toFixed(0)}%~${(Number(r.rangeHigh) * 100).toFixed(0)}%`
                  },
                  {
                    title: t('cryptoTailStrategy.calibration.sampleCount'),
                    dataIndex: 'sampleCount',
                    key: 'sampleCount'
                  },
                  {
                    title: t('cryptoTailStrategy.calibration.predictedProb'),
                    key: 'predictedProb',
                    render: (_: unknown, r: import('../types').CryptoTailCalibrationBin) => `${(Number(r.predictedProb) * 100).toFixed(1)}%`
                  },
                  {
                    title: t('cryptoTailStrategy.calibration.actualWinRate'),
                    key: 'actualWinRate',
                    render: (_: unknown, r: import('../types').CryptoTailCalibrationBin) => `${(Number(r.actualWinRate) * 100).toFixed(1)}%`
                  },
                  {
                    title: t('cryptoTailStrategy.calibration.netPnl'),
                    key: 'netPnl',
                    render: (_: unknown, r: import('../types').CryptoTailCalibrationBin) => (
                      <Text style={{ color: Number(r.netPnl) >= 0 ? '#3f8600' : '#cf1322' }}>{formatUSDC(r.netPnl)}</Text>
                    )
                  }
                ]}
              />
            </Card>
          )}

          {/* 实时决策时间线（仅障碍模式） */}
          {barrierEnabled && (
            <Card
              title={t('cryptoTailStrategy.decisionLog.title')}
              style={{ marginTop: 16 }}
              extra={
                <Button
                  icon={<DownloadOutlined />}
                  size="small"
                  loading={exportingSnapshots}
                  onClick={handleExportSnapshots}
                >
                  {t('cryptoTailStrategy.decisionLog.exportCsv')}
                </Button>
              }
            >
              <Table
                rowKey={decisionRowKey}
                size="small"
                dataSource={decisionEvents}
                locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('cryptoTailStrategy.decisionLog.empty')} /> }}
                columns={[
                  {
                    title: t('cryptoTailStrategy.decisionLog.time'),
                    dataIndex: 'createdAt',
                    key: 'createdAt',
                    width: 172,
                    render: (ts: number) => <Text>{new Date(ts).toLocaleString()}</Text>
                  },
                  {
                    title: t('cryptoTailStrategy.decisionLog.eventType'),
                    dataIndex: 'eventType',
                    key: 'eventType',
                    width: 110,
                    render: (v: string) => {
                      const label = t(`cryptoTailStrategy.decisionLog.eventType_${v}`, { defaultValue: v })
                      const color = v === 'SETTLED' ? 'purple' : v === 'GATE_PASSED' || v === 'ORDER_RESULT' ? 'green' : v === 'GATE_FAILED' ? 'volcano' : 'blue'
                      return <Tag color={color}>{label}</Tag>
                    }
                  },
                  {
                    title: t('cryptoTailStrategy.decisionLog.gate'),
                    dataIndex: 'gateName',
                    key: 'gateName',
                    width: 110,
                    render: (v: string | null | undefined) => v ? <Text code>{v}</Text> : <Text type="secondary">-</Text>
                  },
                  {
                    title: t('cryptoTailStrategy.decisionLog.result'),
                    dataIndex: 'passed',
                    key: 'passed',
                    width: 80,
                    align: 'center',
                    render: (v: boolean | null | undefined) =>
                      v == null ? <Text type="secondary">-</Text>
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
                      i == null ? <Text type="secondary">-</Text>
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
                      return r.payloadJson ? (
                        <Tooltip title={<pre style={{ margin: 0, whiteSpace: 'pre-wrap', maxWidth: 360 }}>{r.payloadJson}</pre>}>
                          <Text ellipsis style={{ maxWidth: 260 }}>{text}</Text>
                        </Tooltip>
                      ) : (
                        <Text type={text === '-' ? 'secondary' : undefined}>{text}</Text>
                      )
                    }
                  }
                ]}
                pagination={{ pageSize: 10, size: 'small', showSizeChanger: false }}
                scroll={{ x: 640 }}
              />
            </Card>
          )}

          {/* 策略信息 */}
          <Card title={t('cryptoTailMonitor.strategyInfo.title')} style={{ marginTop: 16 }}>
            <Row gutter={[16, isMobile ? 12 : 8]}>
              <Col xs={24} sm={24} md={12}>
                <Text type="secondary">{t('cryptoTailMonitor.strategyInfo.market')}: </Text>
                <Text>{pushData?.marketTitle ?? initData.marketTitle}</Text>
              </Col>
              <Col xs={24} sm={24} md={12}>
                <Text type="secondary">{t('cryptoTailMonitor.strategyInfo.interval')}: </Text>
                <Text>{initData.intervalSeconds === 300 ? '5m' : '15m'}</Text>
              </Col>
              <Col xs={24} sm={24} md={12}>
                <Text type="secondary">{t('cryptoTailMonitor.strategyInfo.account')}: </Text>
                <Text>{initData.accountName || `#${initData.accountId}`}</Text>
              </Col>
              <Col xs={24} sm={24} md={12}>
                <Text type="secondary">{t('cryptoTailMonitor.strategyInfo.spreadMode')}: </Text>
                <Text>{initData.minSpreadMode}</Text>
                {initData.minSpreadMode === 'FIXED' && initData.minSpreadValue && (
                  <Text> ({formatNumber(initData.minSpreadValue, 4)})</Text>
                )}
              </Col>
              <Col xs={24} sm={24} md={12}>
                <Text type="secondary">{t('cryptoTailMonitor.strategyInfo.spreadDirection')}: </Text>
                <Text>{(initData.spreadDirection ?? 'MIN') === 'MAX' ? t('cryptoTailMonitor.stat.configuredSpreadMax') : t('cryptoTailMonitor.stat.configuredSpreadMin')}</Text>
              </Col>
            </Row>
          </Card>
        </>
      )}

      {/* 手动下单确认弹窗 - 桌面端使用 Modal */}
      {!isMobile && (
        <Modal
          title={t('cryptoTailMonitor.manualOrder.confirmTitle')}
          open={manualOrderModal.visible}
          onCancel={handleCloseManualOrderModal}
          footer={[
            <Button key="cancel" onClick={handleCloseManualOrderModal}>
              {t('cryptoTailMonitor.manualOrder.cancel')}
            </Button>,
            <Button
              key="confirm"
              type="primary"
              onClick={handleManualOrder}
              loading={ordering}
              style={
                manualOrderModal.direction === 'UP'
                  ? { backgroundColor: '#1890ff', borderColor: '#1890ff' }
                  : { backgroundColor: '#fa8c16', borderColor: '#fa8c16' }
              }
            >
              {t('cryptoTailMonitor.manualOrder.confirm')}
            </Button>
          ]}
          width={480}
        >
          {initData && (
            <Space direction="vertical" style={{ width: '100%' }} size={16}>
              <Row gutter={[12, 8]}>
                <Col span={24}>
                  <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>
                    {t('cryptoTailMonitor.manualOrder.marketTitle')}
                  </Text>
                  <Text>{pushData?.marketTitle ?? initData.marketTitle}</Text>
                </Col>
                <Col span={24}>
                  <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>
                    {t('cryptoTailMonitor.manualOrder.direction')}
                  </Text>
                  <Text
                    strong
                    style={{
                      color: manualOrderModal.direction === 'UP' ? '#1890ff' : '#fa8c16'
                    }}
                  >
                    {manualOrderModal.direction === 'UP'
                      ? t('cryptoTailMonitor.manualOrder.directionUp')
                      : t('cryptoTailMonitor.manualOrder.directionDown')}
                  </Text>
                </Col>
                <Col span={24}>
                  <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>
                    {t('cryptoTailMonitor.manualOrder.availableBalance')}
                  </Text>
                  <Text strong style={{ fontSize: 16, color: '#52c41a' }}>
                    {manualOrderModal.availableBalance ? formatNumber(manualOrderModal.availableBalance, 2) : '-'} {t('cryptoTailMonitor.manualOrder.orderUnit')}
                  </Text>
                </Col>
                <Col span={24}>
                  <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>
                    {t('cryptoTailMonitor.manualOrder.orderPrice')} ({t('cryptoTailMonitor.manualOrder.orderUnit')})
                  </Text>
                  <Space.Compact style={{ width: '100%' }}>
                    <InputNumber
                      style={{ width: '100%' }}
                      value={manualOrderModal.price ? parseFloat(manualOrderModal.price) : undefined}
                      onChange={handlePriceChange}
                      min={0}
                      max={1}
                      step={0.0001}
                      precision={4}
                      placeholder="0.0000"
                    />
                    <Button onClick={handleFetchLatestPrice} icon={<SyncOutlined />}>
                      {t('cryptoTailMonitor.manualOrder.fetchLatestPrice')}
                    </Button>
                  </Space.Compact>
                </Col>
                <Col span={24}>
                  <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>
                    {t('cryptoTailMonitor.manualOrder.orderSize')} ({t('cryptoTailMonitor.manualOrder.sizeUnit')})
                  </Text>
                  <Space.Compact style={{ width: '100%' }}>
                    <InputNumber
                      style={{ width: '100%' }}
                      value={manualOrderModal.size ? parseFloat(manualOrderModal.size) : undefined}
                      onChange={handleSizeChange}
                      min={1}
                      precision={2}
                      placeholder="1"
                    />
                    <Button onClick={handleMaxSize} type="primary" ghost>
                      {t('cryptoTailMonitor.manualOrder.maxSize')}
                    </Button>
                  </Space.Compact>
                </Col>
                <Col span={24}>
                  <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>
                    {t('cryptoTailMonitor.manualOrder.totalAmount')}
                  </Text>
                  <Text strong style={{ fontSize: 16 }}>
                    {manualOrderModal.totalAmount} {t('cryptoTailMonitor.manualOrder.orderUnit')}
                  </Text>
                </Col>
                <Col span={24}>
                  <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>
                    {t('cryptoTailMonitor.manualOrder.account')}
                  </Text>
                  <Text>{initData.accountName || `#${initData.accountId}`}</Text>
                </Col>
              </Row>
            </Space>
          )}
        </Modal>
      )}

      {/* 移动端 BottomSheet 弹窗 */}
      {isMobile && (
        <AntdMobilePopup
          visible={manualOrderModal.visible}
          onMaskClick={handleCloseManualOrderModal}
          onClose={handleCloseManualOrderModal}
          bodyStyle={{
            borderRadius: '16px 16px 0 0',
            padding: '12px 16px',
            maxHeight: '70vh',
            overflow: 'auto'
          }}
        >
          {initData && (
            <div>
              {/* 标题栏 */}
              <div style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                marginBottom: 12
              }}>
                <Text strong style={{ fontSize: 16 }}>
                  {t('cryptoTailMonitor.manualOrder.confirmTitle')}
                </Text>
                <Button type="text" onClick={handleCloseManualOrderModal} style={{ padding: 0, fontSize: 18 }}>
                  ✕
                </Button>
              </div>

              {/* 市场信息 + 方向 + 余额（一行） */}
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10, flexWrap: 'wrap', gap: 8 }}>
                <div style={{ flex: '1 1 auto', minWidth: 0 }}>
                  <Text type="secondary" style={{ fontSize: 12 }}>{t('cryptoTailMonitor.manualOrder.marketTitle')}: </Text>
                  <Text style={{ fontSize: 13 }} ellipsis>{pushData?.marketTitle ?? initData.marketTitle}</Text>
                </div>
                <Text
                  strong
                  style={{
                    fontSize: 14,
                    color: manualOrderModal.direction === 'UP' ? '#1890ff' : '#fa8c16'
                  }}
                >
                  {manualOrderModal.direction === 'UP'
                    ? t('cryptoTailMonitor.manualOrder.directionUp')
                    : t('cryptoTailMonitor.manualOrder.directionDown')}
                </Text>
              </div>

              {/* 可用余额 */}
              <div style={{ marginBottom: 10 }}>
                <Text type="secondary" style={{ fontSize: 12 }}>
                  {t('cryptoTailMonitor.manualOrder.availableBalance')}: 
                </Text>
                <Text strong style={{ fontSize: 14, color: '#52c41a', marginLeft: 4 }}>
                  {manualOrderModal.availableBalance ? formatNumber(manualOrderModal.availableBalance, 2) : '-'} {t('cryptoTailMonitor.manualOrder.orderUnit')}
                </Text>
              </div>

              {/* 价格输入 */}
              <div style={{ marginBottom: 10 }}>
                <Text type="secondary" style={{ display: 'block', marginBottom: 2, fontSize: 12 }}>
                  {t('cryptoTailMonitor.manualOrder.orderPrice')}
                </Text>
                <Space.Compact style={{ width: '100%' }}>
                  <InputNumber
                    style={{ width: '100%', height: 36 }}
                    value={manualOrderModal.price ? parseFloat(manualOrderModal.price) : undefined}
                    onChange={handlePriceChange}
                    min={0}
                    max={1}
                    step={0.0001}
                    precision={4}
                    placeholder="0.0000"
                  />
                  <Button onClick={handleFetchLatestPrice} icon={<SyncOutlined />} style={{ height: 36, fontSize: 12 }}>
                    {t('cryptoTailMonitor.manualOrder.fetchLatestPrice')}
                  </Button>
                </Space.Compact>
              </div>

              {/* 数量输入 */}
              <div style={{ marginBottom: 10 }}>
                <Text type="secondary" style={{ display: 'block', marginBottom: 2, fontSize: 12 }}>
                  {t('cryptoTailMonitor.manualOrder.orderSize')}
                </Text>
                <Space.Compact style={{ width: '100%' }}>
                  <InputNumber
                    style={{ width: '100%', height: 36 }}
                    value={manualOrderModal.size ? parseFloat(manualOrderModal.size) : undefined}
                    onChange={handleSizeChange}
                    min={1}
                    precision={2}
                    placeholder="1"
                  />
                  <Button onClick={handleMaxSize} type="primary" ghost style={{ height: 36, fontSize: 12 }}>
                    {t('cryptoTailMonitor.manualOrder.maxSize')}
                  </Button>
                </Space.Compact>
              </div>

              {/* 总金额 + 账户（一行） */}
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
                <div>
                  <Text type="secondary" style={{ fontSize: 12 }}>{t('cryptoTailMonitor.manualOrder.totalAmount')}: </Text>
                  <Text strong style={{ fontSize: 16, marginLeft: 4 }}>
                    {manualOrderModal.totalAmount} {t('cryptoTailMonitor.manualOrder.orderUnit')}
                  </Text>
                </div>
                <div>
                  <Text type="secondary" style={{ fontSize: 12 }}>{t('cryptoTailMonitor.manualOrder.account')}: </Text>
                  <Text style={{ fontSize: 12 }}>{initData.accountName || `#${initData.accountId}`}</Text>
                </div>
              </div>

              {/* 确认按钮 */}
              <Button
                type="primary"
                block
                onClick={handleManualOrder}
                loading={ordering}
                style={{
                  height: 44,
                  borderRadius: 8,
                  backgroundColor: manualOrderModal.direction === 'UP' ? '#1890ff' : '#fa8c16',
                  borderColor: manualOrderModal.direction === 'UP' ? '#1890ff' : '#fa8c16'
                }}
              >
                {t('cryptoTailMonitor.manualOrder.confirm')}
              </Button>
            </div>
          )}
        </AntdMobilePopup>
      )}

      {/* 移动端底部悬浮按钮 */}
      {isMobile && (
        <div
          style={{
            position: 'fixed',
            bottom: 0,
            left: 0,
            right: 0,
            zIndex: 1000,
            padding: '12px 16px',
            background: 'rgba(255, 255, 255, 0.95)',
            backdropFilter: 'blur(10px)',
            borderTop: '1px solid #f0f0f0',
            boxShadow: '0 -2px 8px rgba(0, 0, 0, 0.06)'
          }}
        >
          <div style={{ display: 'flex', width: '100%', gap: 0 }}>
            <Button
              type="primary"
              icon={<ShoppingCartOutlined />}
              disabled={!pushData || pushData.triggered || pushData.periodEnded}
              onClick={() => handleOpenManualOrderModal('UP')}
              loading={ordering}
              style={{ flex: 1, backgroundColor: '#1890ff', borderColor: '#1890ff', height: 44, borderRadius: '6px 0 0 6px' }}
            >
              {t('cryptoTailMonitor.manualOrder.buttonUp')} {pushData?.currentPriceUp ? `(${formatNumber(pushData.currentPriceUp, 2)})` : ''}
            </Button>
            <Button
              type="primary"
              icon={<ShoppingCartOutlined />}
              disabled={!pushData || pushData.triggered || pushData.periodEnded}
              onClick={() => handleOpenManualOrderModal('DOWN')}
              loading={ordering}
              style={{ flex: 1, backgroundColor: '#fa8c16', borderColor: '#fa8c16', height: 44, borderRadius: '0 6px 6px 0' }}
            >
              {t('cryptoTailMonitor.manualOrder.buttonDown')} {pushData?.currentPriceDown ? `(${formatNumber(pushData.currentPriceDown, 2)})` : ''}
            </Button>
          </div>
        </div>
      )}
    </div>
  )
}

export default CryptoTailMonitor
