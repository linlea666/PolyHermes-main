import { useState } from 'react'
import { Modal, Form, Select, InputNumber, Button, Table, Space, message, Tag, Typography } from 'antd'
import { useTranslation } from 'react-i18next'
import { useMediaQuery } from 'react-responsive'
import { apiService } from '../services/api'
import type { ReversalStatDto } from '../types'

interface Props {
  open: boolean
  onClose: () => void
}

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
      const res = await apiService.cryptoTailStrategy.reversalBackfill(currentParams())
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

  const columns = [
    {
      title: t('cryptoTailStrategy.reversal.outcomeIndex'),
      dataIndex: 'outcomeIndex',
      render: (v: number) => <Tag color={v === 0 ? 'green' : 'red'}>{v === 0 ? 'Up' : 'Down'}</Tag>
    },
    { title: t('cryptoTailStrategy.reversal.diffSigmaBucket'), dataIndex: 'diffSigmaBucket' },
    { title: t('cryptoTailStrategy.reversal.oddsBucket'), dataIndex: 'oddsBucket' },
    { title: t('cryptoTailStrategy.reversal.remainingBucket'), dataIndex: 'remainingBucket' },
    { title: t('cryptoTailStrategy.reversal.sampleCount'), dataIndex: 'sampleCount' },
    { title: t('cryptoTailStrategy.reversal.reversedCount'), dataIndex: 'reversedCount' },
    {
      title: t('cryptoTailStrategy.reversal.modelProb'),
      dataIndex: 'modelProb',
      render: (v: string) => <Typography.Text strong>{(Number(v) * 100).toFixed(2)}%</Typography.Text>
    },
    {
      title: t('cryptoTailStrategy.reversal.reversalRate'),
      dataIndex: 'reversalRate',
      render: (v: string) => `${(Number(v) * 100).toFixed(2)}%`
    }
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
      <Form form={form} layout="inline" initialValues={{ coin: 'BTC', intervalSeconds: 300, lookbackDays: 180, dataSource: 'BINANCE', maxPeriods: 300 }} style={{ marginBottom: 16, rowGap: 8, flexWrap: 'wrap' }}>
        <Form.Item name="coin" label={t('cryptoTailStrategy.reversal.coin')}>
          <Select style={{ width: 100 }} options={[{ value: 'BTC', label: 'BTC' }, { value: 'ETH', label: 'ETH' }]} />
        </Form.Item>
        <Form.Item name="intervalSeconds" label={t('cryptoTailStrategy.reversal.interval')}>
          <Select style={{ width: 110 }} options={[{ value: 300, label: '5m' }, { value: 900, label: '15m' }]} />
        </Form.Item>
        <Form.Item name="lookbackDays" label={t('cryptoTailStrategy.reversal.lookbackDays')}>
          <InputNumber min={1} max={730} step={30} style={{ width: 100 }} addonAfter="d" />
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
      <Table
        rowKey={(r) => `${r.dataSource}-${r.outcomeIndex}-${r.diffSigmaBucket}-${r.oddsBucket}-${r.remainingBucket}`}
        dataSource={rows}
        columns={columns}
        size="small"
        loading={loading}
        pagination={{ pageSize: isMobile ? 10 : 20, showSizeChanger: !isMobile }}
        scroll={{ x: isMobile ? 700 : undefined }}
      />
    </Modal>
  )
}

export default CryptoTailReversalResearchModal
