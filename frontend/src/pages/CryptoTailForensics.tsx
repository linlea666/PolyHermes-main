import { useEffect, useRef, useState, useCallback, useMemo } from 'react'
import { Card, message, DatePicker, Space, Button, Typography, Select, Switch, Table, Empty, Tag, Tooltip } from 'antd'
import { ReloadOutlined, ExperimentOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { useMediaQuery } from 'react-responsive'
import dayjs, { type Dayjs } from 'dayjs'
import * as echarts from 'echarts'
import type { EChartsOption } from 'echarts'
import type { ColumnsType } from 'antd/es/table'
import { apiService } from '../services/api'
import { formatUSDC } from '../utils'
import type {
  CryptoTailStrategyDto,
  ForensicsDto,
  ForensicsAggregateRow,
  ForensicsAggregateRequest
} from '../types'

const { RangePicker } = DatePicker
const { Title, Text } = Typography

/** 预置报表：每个报表 = 维度组合 + 图表类型 + 主指标(用于图表着色/排序) */
type RateKey = 'winRate' | 'directionAccuracy' | 'cutRate' | 'cutButWouldWinRate' | 'reversalRate' | 'recoverRate'
interface PresetDef {
  id: string
  dim1: string
  dim2?: string
  chart: 'bar' | 'heatmap'
  rateKey: RateKey
  category?: string
}

const PRESETS: PresetDef[] = [
  { id: 'oddsDist', dim1: 'entryOddsBucket', chart: 'bar', rateKey: 'winRate' },
  { id: 'hourDist', dim1: 'entryWallHour', chart: 'bar', rateKey: 'winRate' },
  { id: 'diffSigmaDist', dim1: 'entryDiffSigmaBucket', chart: 'bar', rateKey: 'reversalRate' },
  { id: 'remainingDist', dim1: 'entryRemainingBucket', chart: 'bar', rateKey: 'reversalRate' },
  { id: 'exitAttribution', dim1: 'exitKind', chart: 'bar', rateKey: 'cutButWouldWinRate' },
  { id: 'overCutCheck', dim1: 'outcomeCategory', chart: 'bar', rateKey: 'cutButWouldWinRate' },
  { id: 'configAb', dim1: 'cfgFingerprint', chart: 'bar', rateKey: 'winRate' },
  { id: 'heatOddsRemaining', dim1: 'entryOddsBucket', dim2: 'entryRemainingBucket', chart: 'heatmap', rateKey: 'reversalRate' },
  { id: 'heatSigmaRemaining', dim1: 'entryDiffSigmaBucket', dim2: 'entryRemainingBucket', chart: 'heatmap', rateKey: 'reversalRate' }
]

const CATEGORY_COLORS: Record<string, string> = {
  WON_HELD: 'green',
  WON_TP: 'cyan',
  CUT_BUT_WOULD_WIN: 'volcano',
  LOST_REVERSED: 'red',
  LOST_WRONG_FROM_START: 'magenta',
  UNFILLED: 'default',
  ABANDONED: 'orange',
  UNKNOWN: 'default'
}

const pnlColor = (value: string | null | undefined): string | undefined => {
  if (value == null || value === '') return undefined
  const num = parseFloat(value)
  if (Number.isNaN(num)) return undefined
  if (num > 0) return '#3f8600'
  if (num < 0) return '#cf1322'
  return undefined
}

const pct = (rate: string | null | undefined): string => {
  if (rate == null || rate === '') return '-'
  const n = parseFloat(rate)
  if (Number.isNaN(n)) return '-'
  return `${(n * 100).toFixed(1)}%`
}

const CryptoTailForensics: React.FC = () => {
  const { t } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })

  const [strategies, setStrategies] = useState<CryptoTailStrategyDto[]>([])
  const [strategyId, setStrategyId] = useState<number | undefined>(undefined)
  const [marketSlug, setMarketSlug] = useState<string | undefined>(undefined)
  const [intervalSeconds, setIntervalSeconds] = useState<number | undefined>(undefined)
  const [onlySettled, setOnlySettled] = useState(true)
  const [dateRange, setDateRange] = useState<[Dayjs | null, Dayjs | null]>([dayjs().subtract(29, 'day'), dayjs()])

  const [presetId, setPresetId] = useState<string>('oddsDist')
  const [aggRows, setAggRows] = useState<ForensicsAggregateRow[]>([])
  const [aggLoading, setAggLoading] = useState(false)

  const [detail, setDetail] = useState<ForensicsDto[]>([])
  const [detailTotal, setDetailTotal] = useState(0)
  const [detailPage, setDetailPage] = useState(1)
  const [detailPageSize, setDetailPageSize] = useState(20)
  const [detailCategory, setDetailCategory] = useState<string | undefined>(undefined)
  const [detailLoading, setDetailLoading] = useState(false)
  const [backfilling, setBackfilling] = useState(false)

  const chartRef = useRef<HTMLDivElement>(null)
  const chartInstance = useRef<echarts.ECharts | null>(null)

  const preset = useMemo(() => PRESETS.find((p) => p.id === presetId) ?? PRESETS[0], [presetId])

  const baseFilter = useCallback((): Pick<ForensicsAggregateRequest, 'strategyId' | 'marketSlug' | 'intervalSeconds' | 'onlySettled' | 'startTs' | 'endTs'> => ({
    strategyId: strategyId ?? null,
    marketSlug: marketSlug ?? null,
    intervalSeconds: intervalSeconds ?? null,
    onlySettled,
    startTs: dateRange[0] ? dateRange[0].startOf('day').valueOf() : null,
    endTs: dateRange[1] ? dateRange[1].endOf('day').valueOf() : null
  }), [strategyId, marketSlug, intervalSeconds, onlySettled, dateRange])

  const loadStrategies = useCallback(async () => {
    try {
      const res = await apiService.cryptoTailStrategy.list({})
      if (res.data.code === 0 && res.data.data) setStrategies(res.data.data.list ?? [])
    } catch {
      // 不阻塞
    }
  }, [])

  const loadAggregate = useCallback(async () => {
    setAggLoading(true)
    try {
      const req: ForensicsAggregateRequest = { ...baseFilter(), dim1: preset.dim1, dim2: preset.dim2 ?? null }
      const res = preset.chart === 'heatmap'
        ? await apiService.cryptoTailStrategy.forensicsAggregate2d(req)
        : await apiService.cryptoTailStrategy.forensicsAggregate(req)
      if (res.data.code === 0 && res.data.data) {
        setAggRows(res.data.data.rows ?? [])
      } else {
        message.error(res.data.msg || t('cryptoTailForensics.fetchFailed'))
        setAggRows([])
      }
    } catch (e) {
      message.error((e as Error).message || t('cryptoTailForensics.fetchFailed'))
      setAggRows([])
    } finally {
      setAggLoading(false)
    }
  }, [baseFilter, preset, t])

  const loadDetail = useCallback(async (opts?: { page?: number; pageSize?: number }) => {
    const p = opts?.page ?? detailPage
    const ps = opts?.pageSize ?? detailPageSize
    setDetailLoading(true)
    try {
      const f = baseFilter()
      const res = await apiService.cryptoTailStrategy.forensicsList({
        strategyId: f.strategyId,
        marketSlug: f.marketSlug,
        intervalSeconds: f.intervalSeconds,
        outcomeCategory: detailCategory ?? null,
        onlySettled: f.onlySettled,
        startTs: f.startTs,
        endTs: f.endTs,
        page: p,
        pageSize: ps
      })
      if (res.data.code === 0 && res.data.data) {
        setDetail(res.data.data.list ?? [])
        setDetailTotal(res.data.data.total ?? 0)
      } else {
        message.error(res.data.msg || t('cryptoTailForensics.fetchFailed'))
      }
    } catch (e) {
      message.error((e as Error).message || t('cryptoTailForensics.fetchFailed'))
    } finally {
      setDetailLoading(false)
    }
  }, [baseFilter, detailPage, detailPageSize, detailCategory, t])

  useEffect(() => { loadStrategies() }, [loadStrategies])
  useEffect(() => { loadAggregate() }, [loadAggregate])
  useEffect(() => { loadDetail({ page: 1 }); setDetailPage(1) /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [strategyId, marketSlug, intervalSeconds, onlySettled, dateRange, detailCategory])

  const handleBackfill = useCallback(async () => {
    if (!strategyId) {
      message.warning(t('cryptoTailForensics.selectStrategyFirst'))
      return
    }
    setBackfilling(true)
    try {
      const f = baseFilter()
      const res = await apiService.cryptoTailStrategy.forensicsBackfill({ strategyId, startTs: f.startTs, endTs: f.endTs })
      if (res.data.code === 0 && res.data.data) {
        message.success(t('cryptoTailForensics.backfillDone', { count: res.data.data.processed }))
        loadAggregate()
        loadDetail({ page: 1 })
        setDetailPage(1)
      } else {
        message.error(res.data.msg || t('cryptoTailForensics.fetchFailed'))
      }
    } catch (e) {
      message.error((e as Error).message || t('cryptoTailForensics.fetchFailed'))
    } finally {
      setBackfilling(false)
    }
  }, [strategyId, baseFilter, loadAggregate, loadDetail, t])

  // 渲染图表
  useEffect(() => {
    if (!chartRef.current) return
    if (!aggRows.length) {
      chartInstance.current?.clear()
      return
    }
    if (chartInstance.current) {
      const dom = chartInstance.current.getDom()
      if (!dom || !document.contains(dom)) {
        chartInstance.current.dispose()
        chartInstance.current = null
      }
    }
    if (!chartInstance.current) chartInstance.current = echarts.init(chartRef.current)

    const rateLabel = t(`cryptoTailForensics.metric.${preset.rateKey}`)
    let option: EChartsOption
    if (preset.chart === 'heatmap') {
      const xs = Array.from(new Set(aggRows.map((r) => r.key1 ?? '-')))
      const ys = Array.from(new Set(aggRows.map((r) => r.key2 ?? '-')))
      const data = aggRows.map((r) => [
        xs.indexOf(r.key1 ?? '-'),
        ys.indexOf(r.key2 ?? '-'),
        Math.round(parseFloat(r[preset.rateKey] || '0') * 1000) / 10
      ])
      const maxV = Math.max(10, ...data.map((d) => d[2] as number))
      option = {
        tooltip: {
          position: 'top',
          formatter: (p: unknown) => {
            const item = p as { data: [number, number, number] }
            const r = aggRows.find((row) => xs.indexOf(row.key1 ?? '-') === item.data[0] && ys.indexOf(row.key2 ?? '-') === item.data[1])
            return `${xs[item.data[0]]} × ${ys[item.data[1]]}<br/>${rateLabel}: ${item.data[2]}%<br/>${t('cryptoTailForensics.col.count')}: ${r?.count ?? 0}`
          }
        },
        grid: { left: '3%', right: '6%', bottom: '12%', top: '6%', containLabel: true },
        xAxis: { type: 'category', data: xs, axisLabel: { rotate: 30 } },
        yAxis: { type: 'category', data: ys },
        visualMap: { min: 0, max: maxV, calculable: true, orient: 'horizontal', left: 'center', bottom: 0, inRange: { color: ['#52c41a', '#fadb14', '#ff4d4f'] } },
        series: [{ type: 'heatmap', data, label: { show: true, formatter: (p: unknown) => `${(p as { data: [number, number, number] }).data[2]}` } }]
      }
    } else {
      const labels = aggRows.map((r) => r.key1 ?? '-')
      option = {
        tooltip: { trigger: 'axis' },
        legend: { data: [t('cryptoTailForensics.col.count'), rateLabel], top: 0 },
        grid: { left: '3%', right: '4%', bottom: '3%', top: '14%', containLabel: true },
        xAxis: { type: 'category', data: labels, axisLabel: { rotate: labels.length > 6 ? 30 : 0 } },
        yAxis: [
          { type: 'value', name: t('cryptoTailForensics.col.count') },
          { type: 'value', name: '%', min: 0, max: 100, axisLabel: { formatter: '{value}%' } }
        ],
        series: [
          { name: t('cryptoTailForensics.col.count'), type: 'bar', data: aggRows.map((r) => r.count), itemStyle: { color: '#1677ff' }, barMaxWidth: 36 },
          { name: rateLabel, type: 'line', yAxisIndex: 1, data: aggRows.map((r) => Math.round(parseFloat(r[preset.rateKey] || '0') * 1000) / 10), itemStyle: { color: '#fa541c' } }
        ]
      }
    }
    chartInstance.current.setOption(option, true)
    chartInstance.current.resize()
  }, [aggRows, preset, t])

  useEffect(() => {
    const onResize = () => chartInstance.current?.resize()
    window.addEventListener('resize', onResize)
    return () => {
      window.removeEventListener('resize', onResize)
      chartInstance.current?.dispose()
      chartInstance.current = null
    }
  }, [])

  const aggColumns: ColumnsType<ForensicsAggregateRow> = useMemo(() => {
    const cols: ColumnsType<ForensicsAggregateRow> = [
      { title: preset.dim2 ? `${t(`cryptoTailForensics.dim.${preset.dim1}`)} / ${t(`cryptoTailForensics.dim.${preset.dim2}`)}` : t(`cryptoTailForensics.dim.${preset.dim1}`), key: 'k', fixed: 'left', width: 180, render: (_: unknown, r) => preset.dim2 ? `${r.key1 ?? '-'} × ${r.key2 ?? '-'}` : (r.key1 ?? '-') },
      { title: t('cryptoTailForensics.col.count'), dataIndex: 'count', key: 'count', width: 80, sorter: (a, b) => a.count - b.count, defaultSortOrder: 'descend' },
      { title: t('cryptoTailForensics.metric.winRate'), key: 'winRate', width: 90, render: (_: unknown, r) => pct(r.winRate) },
      { title: t('cryptoTailForensics.metric.directionAccuracy'), key: 'dir', width: 90, render: (_: unknown, r) => pct(r.directionAccuracy) },
      { title: t('cryptoTailForensics.metric.reversalRate'), key: 'rev', width: 90, render: (_: unknown, r) => pct(r.reversalRate) },
      { title: t('cryptoTailForensics.metric.cutRate'), key: 'cut', width: 90, render: (_: unknown, r) => pct(r.cutRate) },
      { title: t('cryptoTailForensics.metric.cutButWouldWinRate'), key: 'cww', width: 110, render: (_: unknown, r) => pct(r.cutButWouldWinRate) },
      { title: t('cryptoTailForensics.col.avgDiffSigma'), key: 'ads', width: 100, render: (_: unknown, r) => r.avgDiffSigma ? parseFloat(r.avgDiffSigma).toFixed(2) : '-' },
      { title: t('cryptoTailForensics.col.avgBestAsk'), key: 'aba', width: 100, render: (_: unknown, r) => r.avgBestAsk ? parseFloat(r.avgBestAsk).toFixed(3) : '-' },
      { title: t('cryptoTailForensics.col.avgFirstReversalRemaining'), key: 'afr', width: 120, render: (_: unknown, r) => r.avgFirstReversalRemaining ? `${parseFloat(r.avgFirstReversalRemaining).toFixed(0)}s` : '-' },
      { title: t('cryptoTailForensics.col.sumPnl'), key: 'sp', width: 110, render: (_: unknown, r) => <span style={{ color: pnlColor(r.sumPnl) }}>{r.sumPnl ? formatUSDC(r.sumPnl) : '-'}</span> },
      { title: t('cryptoTailForensics.col.sumCutVsHold'), key: 'scvh', width: 120, render: (_: unknown, r) => <span style={{ color: pnlColor(r.sumCutVsHold) }}>{r.sumCutVsHold ? formatUSDC(r.sumCutVsHold) : '-'}</span> }
    ]
    return cols
  }, [preset, t])

  const detailColumns: ColumnsType<ForensicsDto> = useMemo(() => [
    { title: t('cryptoTailForensics.col.entryTime'), dataIndex: 'entryTs', key: 'entryTs', width: 150, fixed: 'left', render: (v: number | null) => v ? dayjs(v).format('MM-DD HH:mm:ss') : '-' },
    { title: t('cryptoTailForensics.col.category'), dataIndex: 'outcomeCategory', key: 'cat', width: 150, render: (v: string | null) => v ? <Tag color={CATEGORY_COLORS[v] ?? 'default'}>{t(`cryptoTailForensics.category.${v}`)}</Tag> : '-' },
    { title: t('cryptoTailForensics.col.side'), dataIndex: 'outcomeIndex', key: 'side', width: 70, render: (v: number | null) => v == null ? '-' : (v === 0 ? 'Up' : 'Down') },
    { title: t('cryptoTailForensics.col.entryOdds'), dataIndex: 'entryFillPrice', key: 'odds', width: 90, render: (v: string | null) => v ? parseFloat(v).toFixed(3) : '-' },
    { title: t('cryptoTailForensics.col.entryDiffSigma'), dataIndex: 'entryDiffSigma', key: 'eds', width: 90, render: (v: string | null) => v ? parseFloat(v).toFixed(2) : '-' },
    { title: t('cryptoTailForensics.col.entryRemaining'), dataIndex: 'entryRemainingSeconds', key: 'erem', width: 90, render: (v: number | null) => v == null ? '-' : `${v}s` },
    { title: t('cryptoTailForensics.col.maxRetrace'), dataIndex: 'maxDiffRetracePct', key: 'mr', width: 90, render: (v: string | null) => pct(v) },
    { title: t('cryptoTailForensics.col.firstRevRemaining'), dataIndex: 'firstReversalRemainingSeconds', key: 'frr', width: 100, render: (v: number | null) => v == null ? '-' : `${v}s` },
    { title: t('cryptoTailForensics.col.exitKind'), dataIndex: 'exitKind', key: 'ek', width: 130, render: (v: string | null) => v ?? '-' },
    { title: t('cryptoTailForensics.col.realizedPnl'), dataIndex: 'realizedPnl', key: 'rp', width: 100, render: (v: string | null) => <span style={{ color: pnlColor(v) }}>{v ? formatUSDC(v) : '-'}</span> },
    { title: t('cryptoTailForensics.col.cutVsHold'), dataIndex: 'cutVsHoldDelta', key: 'cvh', width: 110, render: (v: string | null) => <span style={{ color: pnlColor(v) }}>{v ? formatUSDC(v) : '-'}</span> },
    { title: t('cryptoTailForensics.col.holdSeconds'), dataIndex: 'holdSeconds', key: 'hs', width: 90, render: (v: number | null) => v == null ? '-' : `${v}s` }
  ], [t])

  const marketOptions = useMemo(() => Array.from(new Set(strategies.map((s) => s.marketSlugPrefix).filter(Boolean))), [strategies])

  return (
    <div style={{ padding: isMobile ? 12 : 24 }}>
      <Title level={isMobile ? 4 : 3}>
        <ExperimentOutlined /> {t('cryptoTailForensics.title')}
      </Title>
      <Text type="secondary">{t('cryptoTailForensics.subtitle')}</Text>

      <Card size="small" style={{ marginTop: 16 }}>
        <Space wrap size={[12, 12]}>
          <Select
            allowClear
            style={{ minWidth: 200 }}
            placeholder={t('cryptoTailForensics.allStrategies')}
            value={strategyId}
            onChange={(v) => setStrategyId(v)}
            options={strategies.map((s) => ({ label: `${s.name} (#${s.id})`, value: s.id }))}
          />
          <Select
            allowClear
            style={{ minWidth: 160 }}
            placeholder={t('cryptoTailForensics.allMarkets')}
            value={marketSlug}
            onChange={(v) => setMarketSlug(v)}
            options={marketOptions.map((m) => ({ label: m, value: m }))}
          />
          <Select
            allowClear
            style={{ minWidth: 120 }}
            placeholder={t('cryptoTailForensics.allIntervals')}
            value={intervalSeconds}
            onChange={(v) => setIntervalSeconds(v)}
            options={[{ label: '5m', value: 300 }, { label: '15m', value: 900 }]}
          />
          <RangePicker
            value={dateRange}
            onChange={(v) => setDateRange((v as [Dayjs | null, Dayjs | null]) ?? [null, null])}
            allowEmpty={[true, true]}
          />
          <Space size={6}>
            <Text>{t('cryptoTailForensics.onlySettled')}</Text>
            <Switch checked={onlySettled} onChange={setOnlySettled} />
          </Space>
          <Button icon={<ReloadOutlined />} onClick={() => { loadAggregate(); loadDetail() }} loading={aggLoading}>
            {t('cryptoTailForensics.refresh')}
          </Button>
          <Tooltip title={t('cryptoTailForensics.backfillHint')}>
            <Button onClick={handleBackfill} loading={backfilling}>{t('cryptoTailForensics.backfill')}</Button>
          </Tooltip>
        </Space>
      </Card>

      <Card
        size="small"
        style={{ marginTop: 16 }}
        title={
          <Space wrap>
            <span>{t('cryptoTailForensics.reportTitle')}</span>
            <Select
              style={{ minWidth: 220 }}
              value={presetId}
              onChange={setPresetId}
              options={PRESETS.map((p) => ({ label: t(`cryptoTailForensics.preset.${p.id}`), value: p.id }))}
            />
          </Space>
        }
      >
        {aggRows.length ? (
          <>
            <div ref={chartRef} style={{ width: '100%', height: isMobile ? 260 : 340 }} />
            <Table
              size="small"
              style={{ marginTop: 12 }}
              rowKey={(r) => `${r.key1 ?? ''}__${r.key2 ?? ''}`}
              loading={aggLoading}
              columns={aggColumns}
              dataSource={aggRows}
              pagination={false}
              scroll={{ x: 1100 }}
            />
          </>
        ) : (
          <Empty description={t('cryptoTailForensics.noData')} />
        )}
      </Card>

      <Card
        size="small"
        style={{ marginTop: 16 }}
        title={t('cryptoTailForensics.detailTitle')}
        extra={
          <Select
            allowClear
            style={{ minWidth: 180 }}
            placeholder={t('cryptoTailForensics.allCategories')}
            value={detailCategory}
            onChange={(v) => setDetailCategory(v)}
            options={Object.keys(CATEGORY_COLORS).map((c) => ({ label: t(`cryptoTailForensics.category.${c}`), value: c }))}
          />
        }
      >
        <Table
          size="small"
          rowKey="id"
          loading={detailLoading}
          columns={detailColumns}
          dataSource={detail}
          scroll={{ x: 1300 }}
          pagination={{
            current: detailPage,
            pageSize: detailPageSize,
            total: detailTotal,
            showSizeChanger: !isMobile,
            showTotal: (total) => t('cryptoTailForensics.totalRecords', { total }),
            onChange: (p, ps) => { setDetailPage(p); setDetailPageSize(ps); loadDetail({ page: p, pageSize: ps }) }
          }}
        />
      </Card>
    </div>
  )
}

export default CryptoTailForensics
