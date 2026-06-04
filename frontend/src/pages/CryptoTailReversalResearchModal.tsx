import { useMemo, useState } from 'react'
import { Modal, Form, Select, InputNumber, Button, Table, Space, message, Tag, Typography, Alert, Statistic, Row, Col } from 'antd'
import { useTranslation } from 'react-i18next'
import { useMediaQuery } from 'react-responsive'
import { apiService } from '../services/api'
import type { ReversalStatDto } from '../types'

interface Props {
  open: boolean
  onClose: () => void
}

/** 样本不足阈值：低于此值的分桶统计可信度低，UI 标记提醒 */
const LOW_SAMPLE_THRESHOLD = 30

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
      message.success(t('cryptoTailStrategy.reversal.backfillPmDone', {
        buckets: d.bucketsWritten,
        resolved: d.periodsResolved,
        requested: d.periodsRequested,
        obs: d.observations
      }))
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
  const summary = useMemo(() => {
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
  }, [rows])

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
