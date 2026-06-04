import { useMemo, useState } from 'react'
import { Modal, Form, Select, InputNumber, Button, Table, Space, message, Tag, Typography, Alert, Statistic, Row, Col } from 'antd'
import { useTranslation } from 'react-i18next'
import { useMediaQuery } from 'react-responsive'
import { apiService } from '../services/api'
import type { ReversalStatDto, PolymarketReversalBackfillResponse } from '../types'

interface Props {
  open: boolean
  onClose: () => void
}

/** 样本不足阈值：低于此值的分桶统计可信度低，UI 标记提醒 */
const LOW_SAMPLE_THRESHOLD = 30

interface ReversalSummary {
  totalSample: number
  buckets: number
  lowSampleBuckets: number
  avgModelProb: number
  avgVirtualWin: number | null
}

/** 以 sampleCount 加权汇总一组分桶的维持概率与虚拟胜率（评测对比与单次查询共用） */
const computeSummary = (rows: ReversalStatDto[]): ReversalSummary => {
  let totalSample = 0
  let weightedModelProb = 0
  let vWinSample = 0
  let weightedVWin = 0
  let lowSampleBuckets = 0
  for (const r of rows) {
    totalSample += r.sampleCount
    weightedModelProb += Number(r.modelProb || 0) * r.sampleCount
    if (r.sampleCount < LOW_SAMPLE_THRESHOLD) lowSampleBuckets++
    if (r.virtualWinRate !== '' && r.virtualWinRate != null) {
      vWinSample += r.sampleCount
      weightedVWin += Number(r.virtualWinRate) * r.sampleCount
    }
  }
  return {
    totalSample,
    buckets: rows.length,
    lowSampleBuckets,
    avgModelProb: totalSample > 0 ? weightedModelProb / totalSample : 0,
    avgVirtualWin: vWinSample > 0 ? weightedVWin / vWinSample : null
  }
}

interface CompareRow extends ReversalSummary {
  key: string
  dataSource: string
  lookbackDays: number
}

const COMPARE_LOOKBACK_OPTIONS = [30, 60, 90, 180, 365]

/**
 * 历史反转率研究弹窗：一键回填（基于 Binance 1m K 线）、查看分桶反转率、导出 CSV。
 * 仅查询/回填聚合统计，不影响交易；结果用于 Tail Diff 模型概率来源（HYBRID/STATS）。
 */
const CryptoTailReversalResearchModal: React.FC<Props> = ({ open, onClose }) => {
  const { t } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [form] = Form.useForm()
  const [rows, setRows] = useState<ReversalStatDto[]>([])
  const [loading, setLoading] = useState(false)
  const [backfilling, setBackfilling] = useState(false)
  const [backfillingPm, setBackfillingPm] = useState(false)
  const [dataSource, setDataSource] = useState<string>('BINANCE')
  const [cmpLookbacks, setCmpLookbacks] = useState<number[]>([90, 180])
  const [cmpSources, setCmpSources] = useState<string[]>(['BINANCE'])
  const [cmpRows, setCmpRows] = useState<CompareRow[]>([])
  const [comparing, setComparing] = useState(false)
  const [pmDiag, setPmDiag] = useState<PolymarketReversalBackfillResponse | null>(null)

  const currentParams = (): { coin: string; intervalSeconds: number; lookbackDays: number } => {
    const v = form.getFieldsValue()
    return {
      coin: String(v.coin ?? 'BTC'),
      intervalSeconds: Number(v.intervalSeconds ?? 300),
      lookbackDays: Number(v.lookbackDays ?? 180)
    }
  }

  const runList = async () => {
    setLoading(true)
    const src = String(form.getFieldValue('dataSource') ?? 'BINANCE')
    setDataSource(src)
    try {
      const res = await apiService.cryptoTailStrategy.reversalList({ ...currentParams(), dataSource: src })
      if (res.data.code !== 0 || !res.data.data) {
        message.error(res.data.msg || t('common.failed'))
        return
      }
      setRows(res.data.data.list)
      if (res.data.data.list.length === 0) {
        message.info(t('cryptoTailStrategy.reversal.emptyHint'))
      }
    } catch {
      message.error(t('common.failed'))
    } finally {
      setLoading(false)
    }
  }

  const runBackfill = async () => {
    setBackfilling(true)
    try {
      const samplingSeconds = Number(form.getFieldValue('samplingSeconds') ?? 60)
      const res = await apiService.cryptoTailStrategy.reversalBackfill({ ...currentParams(), samplingSeconds })
      if (res.data.code !== 0 || !res.data.data) {
        message.error(res.data.msg || t('common.failed'))
        return
      }
      const d = res.data.data
      message.success(t('cryptoTailStrategy.reversal.backfillDone', { buckets: d.bucketsWritten, periods: d.periodsProcessed, obs: d.observations }))
      form.setFieldValue('dataSource', 'BINANCE')
      await runList()
    } catch {
      message.error(t('common.failed'))
    } finally {
      setBackfilling(false)
    }
  }

  const runBackfillPolymarket = async () => {
    setBackfillingPm(true)
    try {
      const v = form.getFieldsValue()
      const res = await apiService.cryptoTailStrategy.reversalBackfillPolymarket({
        ...currentParams(),
        maxPeriods: Number(v.maxPeriods ?? 300)
      })
      if (res.data.code !== 0 || !res.data.data) {
        message.error(res.data.msg || t('common.failed'))
        return
      }
      const d = res.data.data
      setPmDiag(d)
      if (d.periodsResolved === 0) {
        message.warning(t('cryptoTailStrategy.reversal.backfillPmZero', { requested: d.periodsRequested }))
      } else {
        message.success(t('cryptoTailStrategy.reversal.backfillPmDone', {
          buckets: d.bucketsWritten,
          resolved: d.periodsResolved,
          requested: d.periodsRequested,
          obs: d.observations
        }))
      }
      form.setFieldValue('dataSource', 'POLYMARKET')
      await runList()
    } catch {
      message.error(t('common.failed'))
    } finally {
      setBackfillingPm(false)
    }
  }

  const runExport = async () => {
    try {
      const res = await apiService.cryptoTailStrategy.reversalExport({ ...currentParams(), dataSource })
      if (res.data.code !== 0 || !res.data.data) {
        message.error(res.data.msg || t('common.failed'))
        return
      }
      const { filename, csv } = res.data.data
      const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = filename
      a.click()
      URL.revokeObjectURL(url)
    } catch {
      message.error(t('common.failed'))
    }
  }

  const pct = (v: string | number) => {
    const n = Number(v)
    return Number.isFinite(n) && String(v) !== '' ? `${(n * 100).toFixed(2)}%` : '-'
  }
  const num = (v: string | number) => {
    const n = Number(v)
    return Number.isFinite(n) && String(v) !== '' ? n.toFixed(4) : '-'
  }
  const samplingLabel = (s: number) => (s === 1 ? '1s' : s === 60 ? '1m' : s === 0 ? 'API' : `${s}s`)

  // 加权命中率汇总：以 sampleCount 加权的 model_prob 与虚拟胜率（仅当前已加载分桶）
  const summary = useMemo(() => computeSummary(rows), [rows])

  // 评测对比：对所选 (数据源 × 回溯天数) 组合并排查询，取加权虚拟胜率（无则维持概率）最高者为最优
  const runCompare = async () => {
    const { coin, intervalSeconds, lookbackDays } = currentParams()
    const lbs = cmpLookbacks.length > 0 ? cmpLookbacks : [lookbackDays]
    const srcs = cmpSources.length > 0 ? cmpSources : ['BINANCE']
    const combos: { dataSource: string; lookbackDays: number }[] = []
    for (const s of srcs) for (const lb of lbs) combos.push({ dataSource: s, lookbackDays: lb })
    setComparing(true)
    try {
      const results = await Promise.all(
        combos.map(async (c): Promise<CompareRow> => {
          let list: ReversalStatDto[] = []
          try {
            const res = await apiService.cryptoTailStrategy.reversalList({ coin, intervalSeconds, lookbackDays: c.lookbackDays, dataSource: c.dataSource })
            if (res.data.code === 0 && res.data.data) list = res.data.data.list
          } catch {
            list = []
          }
          return { key: `${c.dataSource}-${c.lookbackDays}`, dataSource: c.dataSource, lookbackDays: c.lookbackDays, ...computeSummary(list) }
        })
      )
      setCmpRows(results)
      if (results.every((r) => r.totalSample === 0)) {
        message.info(t('cryptoTailStrategy.reversal.compareEmpty'))
      }
    } finally {
      setComparing(false)
    }
  }

  // 最优组合 key：仅在有样本的组合中比较，优先加权虚拟胜率，回退加权维持概率
  const bestCompareKey = useMemo(() => {
    const valid = cmpRows.filter((r) => r.totalSample > 0)
    if (valid.length === 0) return null
    const score = (r: CompareRow) => (r.avgVirtualWin != null ? r.avgVirtualWin : r.avgModelProb)
    return valid.reduce((best, r) => (score(r) > score(best) ? r : best)).key
  }, [cmpRows])

  const columns = [
    {
      title: t('cryptoTailStrategy.reversal.outcomeIndex'),
      dataIndex: 'outcomeIndex',
      render: (v: number) => <Tag color={v === 0 ? 'green' : 'red'}>{v === 0 ? 'Up' : 'Down'}</Tag>
    },
    { title: t('cryptoTailStrategy.reversal.diffSigmaBucket'), dataIndex: 'diffSigmaBucket' },
    { title: t('cryptoTailStrategy.reversal.oddsBucket'), dataIndex: 'oddsBucket' },
    { title: t('cryptoTailStrategy.reversal.remainingBucket'), dataIndex: 'remainingBucket' },
    {
      title: t('cryptoTailStrategy.reversal.samplingSeconds'),
      dataIndex: 'samplingSeconds',
      render: (v: number) => <Tag>{samplingLabel(v)}</Tag>
    },
    {
      title: t('cryptoTailStrategy.reversal.sampleCount'),
      dataIndex: 'sampleCount',
      render: (v: number) =>
        v < LOW_SAMPLE_THRESHOLD
          ? <Tag color="orange">{v} ⚠</Tag>
          : <span>{v}</span>
    },
    { title: t('cryptoTailStrategy.reversal.reversedCount'), dataIndex: 'reversedCount' },
    {
      title: t('cryptoTailStrategy.reversal.modelProb'),
      dataIndex: 'modelProb',
      render: (v: string, r: ReversalStatDto) => (
        <Typography.Text strong type={r.sampleCount < LOW_SAMPLE_THRESHOLD ? 'warning' : undefined}>
          {pct(v)}
        </Typography.Text>
      )
    },
    {
      title: t('cryptoTailStrategy.reversal.reversalRate'),
      dataIndex: 'reversalRate',
      render: (v: string) => pct(v)
    },
    { title: t('cryptoTailStrategy.reversal.maeAvg'), dataIndex: 'maeAvg', render: (v: string) => pct(v) },
    { title: t('cryptoTailStrategy.reversal.mfeAvg'), dataIndex: 'mfeAvg', render: (v: string) => pct(v) },
    {
      title: t('cryptoTailStrategy.reversal.virtualWinRate'),
      dataIndex: 'virtualWinRate',
      render: (v: string) => (v === '' || v == null ? '-' : <Typography.Text strong>{pct(v)}</Typography.Text>)
    },
    { title: t('cryptoTailStrategy.reversal.virtualTpRate'), dataIndex: 'virtualTpRate', render: (v: string) => pct(v) },
    { title: t('cryptoTailStrategy.reversal.virtualStopRate'), dataIndex: 'virtualStopRate', render: (v: string) => pct(v) },
    { title: t('cryptoTailStrategy.reversal.virtualPnlAvg'), dataIndex: 'virtualPnlAvg', render: (v: string) => num(v) }
  ]

  return (
    <Modal
      open={open}
      onCancel={onClose}
      width={isMobile ? '100%' : 900}
      title={t('cryptoTailStrategy.reversal.title')}
      footer={null}
      destroyOnClose
    >
      {dataSource === 'POLYMARKET' && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message={t('cryptoTailStrategy.reversal.pocBannerTitle')}
          description={t('cryptoTailStrategy.reversal.pocBannerDesc')}
        />
      )}
      <Form form={form} layout="inline" initialValues={{ coin: 'BTC', intervalSeconds: 300, lookbackDays: 180, dataSource: 'BINANCE', maxPeriods: 300, samplingSeconds: 60 }} style={{ marginBottom: 16, rowGap: 8, flexWrap: 'wrap' }}>
        <Form.Item name="coin" label={t('cryptoTailStrategy.reversal.coin')}>
          <Select style={{ width: 100 }} options={[{ value: 'BTC', label: 'BTC' }, { value: 'ETH', label: 'ETH' }]} />
        </Form.Item>
        <Form.Item name="intervalSeconds" label={t('cryptoTailStrategy.reversal.interval')}>
          <Select style={{ width: 110 }} options={[{ value: 300, label: '5m' }, { value: 900, label: '15m' }]} />
        </Form.Item>
        <Form.Item name="lookbackDays" label={t('cryptoTailStrategy.reversal.lookbackDays')}>
          <InputNumber min={1} max={730} step={30} style={{ width: 100 }} addonAfter="d" />
        </Form.Item>
        <Form.Item name="samplingSeconds" label={t('cryptoTailStrategy.reversal.samplingSeconds')} tooltip={t('cryptoTailStrategy.reversal.samplingSecondsHint')}>
          <Select style={{ width: 120 }} options={[{ value: 60, label: '1m' }, { value: 1, label: '1s' }]} />
        </Form.Item>
        <Form.Item name="dataSource" label={t('cryptoTailStrategy.reversal.dataSource')}>
          <Select
            style={{ width: 140 }}
            onChange={(v) => setDataSource(String(v))}
            options={[{ value: 'BINANCE', label: 'BINANCE' }, { value: 'POLYMARKET', label: 'POLYMARKET' }]}
          />
        </Form.Item>
        <Form.Item name="maxPeriods" label={t('cryptoTailStrategy.reversal.maxPeriods')} tooltip={t('cryptoTailStrategy.reversal.maxPeriodsHint')}>
          <InputNumber min={1} max={2000} step={50} style={{ width: 110 }} />
        </Form.Item>
      </Form>
      <Space wrap style={{ marginBottom: 16 }}>
        <Button type="primary" loading={backfilling} onClick={runBackfill}>{t('cryptoTailStrategy.reversal.backfillBtn')}</Button>
        <Button loading={backfillingPm} onClick={runBackfillPolymarket}>{t('cryptoTailStrategy.reversal.backfillPmBtn')}</Button>
        <Button onClick={runList} loading={loading}>{t('cryptoTailStrategy.reversal.queryBtn')}</Button>
        <Button onClick={runExport} disabled={rows.length === 0}>{t('cryptoTailStrategy.reversal.exportBtn')}</Button>
      </Space>

      {pmDiag && (
        <Alert
          type={pmDiag.periodsResolved === 0 ? 'warning' : pmDiag.coverageCapped ? 'info' : 'success'}
          showIcon
          style={{ marginBottom: 16 }}
          message={t('cryptoTailStrategy.reversal.pmDiagTitle', {
            resolved: pmDiag.periodsResolved,
            requested: pmDiag.periodsRequested,
            buckets: pmDiag.bucketsWritten
          })}
          description={
            <div>
              <div>
                {t('cryptoTailStrategy.reversal.pmDiagDetail', {
                  slugNotFound: pmDiag.slugNotFound,
                  historyEmpty: pmDiag.historyEmpty,
                  tooFewPoints: pmDiag.tooFewPoints,
                  fetchError: pmDiag.fetchError
                })}
              </div>
              {pmDiag.coverageCapped && (
                <div style={{ marginTop: 4 }}>
                  {t('cryptoTailStrategy.reversal.pmDiagCoverage', {
                    days: pmDiag.coverageDays.toFixed(2),
                    periods: pmDiag.periodsRequested
                  })}
                </div>
              )}
            </div>
          }
        />
      )}

      <div style={{ marginBottom: 16, padding: 12, background: 'rgba(0,0,0,0.02)', borderRadius: 8 }}>
        <Typography.Text strong style={{ display: 'block', marginBottom: 8 }}>
          {t('cryptoTailStrategy.reversal.compareTitle')}
        </Typography.Text>
        <Space wrap align="center">
          <span>{t('cryptoTailStrategy.reversal.compareDataSource')}</span>
          <Select
            mode="multiple"
            style={{ minWidth: 200 }}
            value={cmpSources}
            onChange={setCmpSources}
            options={[{ value: 'BINANCE', label: 'BINANCE' }, { value: 'POLYMARKET', label: 'POLYMARKET' }]}
          />
          <span>{t('cryptoTailStrategy.reversal.compareLookbacks')}</span>
          <Select
            mode="multiple"
            style={{ minWidth: 220 }}
            value={cmpLookbacks}
            onChange={setCmpLookbacks}
            options={COMPARE_LOOKBACK_OPTIONS.map((d) => ({ value: d, label: `${d}d` }))}
          />
          <Button type="primary" ghost loading={comparing} onClick={runCompare}>
            {t('cryptoTailStrategy.reversal.compareBtn')}
          </Button>
        </Space>
        {cmpRows.length > 0 && (
          <Table<CompareRow>
            style={{ marginTop: 12 }}
            rowKey="key"
            size="small"
            pagination={false}
            dataSource={cmpRows}
            columns={[
              {
                title: t('cryptoTailStrategy.reversal.dataSource'),
                dataIndex: 'dataSource',
                render: (v: string, r: CompareRow) => (
                  <Space size={4}>
                    <Tag color={v === 'BINANCE' ? 'blue' : 'gold'}>{v}</Tag>
                    {r.key === bestCompareKey && <Tag color="green">{t('cryptoTailStrategy.reversal.compareBest')}</Tag>}
                  </Space>
                )
              },
              { title: t('cryptoTailStrategy.reversal.lookbackDays'), dataIndex: 'lookbackDays', render: (v: number) => `${v}d` },
              { title: t('cryptoTailStrategy.reversal.summaryAvgModelProb'), dataIndex: 'avgModelProb', render: (v: number) => pct(v) },
              {
                title: t('cryptoTailStrategy.reversal.summaryVirtualWin'),
                dataIndex: 'avgVirtualWin',
                render: (v: number | null) => (v == null ? '-' : pct(v))
              },
              { title: t('cryptoTailStrategy.reversal.summaryTotalSample'), dataIndex: 'totalSample' },
              {
                title: t('cryptoTailStrategy.reversal.summaryLowSample'),
                key: 'lowSample',
                render: (_: unknown, r: CompareRow) => (
                  <span style={r.lowSampleBuckets > 0 ? { color: '#fa8c16' } : undefined}>
                    {r.lowSampleBuckets} / {r.buckets}
                  </span>
                )
              }
            ]}
          />
        )}
      </div>
      {rows.length > 0 && (
        <Row gutter={16} style={{ marginBottom: 12 }}>
          <Col span={isMobile ? 12 : 6}>
            <Statistic title={t('cryptoTailStrategy.reversal.summaryAvgModelProb')} value={summary.avgModelProb * 100} precision={2} suffix="%" />
          </Col>
          <Col span={isMobile ? 12 : 6}>
            <Statistic
              title={t('cryptoTailStrategy.reversal.summaryVirtualWin')}
              value={summary.avgVirtualWin == null ? '-' : summary.avgVirtualWin * 100}
              precision={summary.avgVirtualWin == null ? undefined : 2}
              suffix={summary.avgVirtualWin == null ? '' : '%'}
            />
          </Col>
          <Col span={isMobile ? 12 : 6}>
            <Statistic title={t('cryptoTailStrategy.reversal.summaryTotalSample')} value={summary.totalSample} />
          </Col>
          <Col span={isMobile ? 12 : 6}>
            <Statistic
              title={t('cryptoTailStrategy.reversal.summaryLowSample')}
              value={summary.lowSampleBuckets}
              suffix={`/ ${summary.buckets}`}
              valueStyle={summary.lowSampleBuckets > 0 ? { color: '#fa8c16' } : undefined}
            />
          </Col>
        </Row>
      )}
      <Table
        rowKey={(r) => `${r.dataSource}-${r.outcomeIndex}-${r.diffSigmaBucket}-${r.oddsBucket}-${r.remainingBucket}`}
        dataSource={rows}
        columns={columns}
        size="small"
        loading={loading}
        pagination={{ pageSize: isMobile ? 10 : 20, showSizeChanger: !isMobile }}
        scroll={{ x: isMobile ? 1300 : 1100 }}
        onRow={(r) => (r.sampleCount < LOW_SAMPLE_THRESHOLD ? { style: { opacity: 0.7 } } : {})}
      />
    </Modal>
  )
}

export default CryptoTailReversalResearchModal
