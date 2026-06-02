import { useEffect, useState, useCallback } from 'react'
import {
  Card,
  Select,
  Space,
  Typography,
  Table,
  Tag,
  Tooltip,
  Empty,
  Button,
  DatePicker,
  Radio,
  message
} from 'antd'
import { DownloadOutlined, ReloadOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { useMediaQuery } from 'react-responsive'
import dayjs from 'dayjs'
import type { Dayjs } from 'dayjs'
import { apiService } from '../services/api'
import type { CryptoTailStrategyDto, CryptoTailDecisionEventDto } from '../types'

const { Title } = Typography
const { RangePicker } = DatePicker

type RangePreset = 'today' | 'week' | '7d' | 'custom'

const CryptoTailDecisionLog: React.FC = () => {
  const { t } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })

  const [strategies, setStrategies] = useState<CryptoTailStrategyDto[]>([])
  const [strategyId, setStrategyId] = useState<number>(0)
  const [preset, setPreset] = useState<RangePreset>('today')
  const [customRange, setCustomRange] = useState<[Dayjs | null, Dayjs | null]>([null, null])

  const [events, setEvents] = useState<CryptoTailDecisionEventDto[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(20)
  const [loading, setLoading] = useState(false)
  const [exporting, setExporting] = useState(false)

  // 计算当前时间区间（毫秒）
  const resolveRange = useCallback((): { startDate?: number; endDate?: number } => {
    const now = dayjs()
    if (preset === 'today') {
      return { startDate: now.startOf('day').valueOf(), endDate: now.endOf('day').valueOf() }
    }
    if (preset === 'week') {
      return { startDate: now.startOf('week').valueOf(), endDate: now.endOf('week').valueOf() }
    }
    if (preset === '7d') {
      return { startDate: now.subtract(6, 'day').startOf('day').valueOf(), endDate: now.endOf('day').valueOf() }
    }
    const [s, e] = customRange
    return {
      startDate: s != null ? s.startOf('day').valueOf() : undefined,
      endDate: e != null ? e.endOf('day').valueOf() : undefined
    }
  }, [preset, customRange])

  const loadStrategies = useCallback(async () => {
    try {
      const res = await apiService.cryptoTailStrategy.list({})
      if (res.data.code === 0 && res.data.data) {
        setStrategies(res.data.data.list ?? [])
      }
    } catch {
      // 列表加载失败不阻塞日志查询，下拉仅展示「全部」
    }
  }, [])

  const loadLogs = useCallback(async (opts?: { page?: number; pageSize?: number }) => {
    const p = opts?.page ?? page
    const ps = opts?.pageSize ?? pageSize
    const { startDate, endDate } = resolveRange()
    setLoading(true)
    try {
      const res = await apiService.cryptoTailStrategy.decisionLogAll({
        strategyId,
        page: p,
        pageSize: ps,
        startDate,
        endDate
      })
      if (res.data.code === 0 && res.data.data) {
        setEvents(res.data.data.list ?? [])
        setTotal(res.data.data.total ?? 0)
      } else {
        setEvents([])
        setTotal(0)
      }
    } finally {
      setLoading(false)
    }
  }, [strategyId, page, pageSize, resolveRange])

  useEffect(() => {
    loadStrategies()
  }, [loadStrategies])

  // 筛选条件变化后回到第一页并查询
  useEffect(() => {
    setPage(1)
    loadLogs({ page: 1 })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [strategyId, preset, customRange])

  const handleExport = async () => {
    const { startDate, endDate } = resolveRange()
    setExporting(true)
    try {
      const res = await apiService.cryptoTailStrategy.decisionLogExport({ strategyId, startDate, endDate })
      if (res.data.code === 0 && res.data.data) {
        const list = res.data.data.list ?? []
        if (list.length === 0) {
          message.info(t('cryptoTailStrategy.decisionLogPage.exportEmpty'))
          return
        }
        const blob = new Blob([JSON.stringify(list, null, 2)], { type: 'application/json' })
        const url = URL.createObjectURL(blob)
        const a = document.createElement('a')
        const stamp = dayjs().format('YYYYMMDD_HHmmss')
        a.href = url
        a.download = `decision-log_${stamp}.json`
        document.body.appendChild(a)
        a.click()
        document.body.removeChild(a)
        URL.revokeObjectURL(url)
        message.success(t('cryptoTailStrategy.decisionLogPage.exportSuccess', { count: list.length }))
      } else {
        message.error(t('cryptoTailStrategy.decisionLog.exportFailed'))
      }
    } catch {
      message.error(t('cryptoTailStrategy.decisionLog.exportFailed'))
    } finally {
      setExporting(false)
    }
  }

  const columns = [
    {
      title: t('cryptoTailStrategy.decisionLog.time'),
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 172,
      render: (ts: number) => <Typography.Text>{new Date(ts).toLocaleString()}</Typography.Text>
    },
    {
      title: t('cryptoTailStrategy.decisionLogPage.strategy'),
      dataIndex: 'strategyName',
      key: 'strategyName',
      width: 140,
      ellipsis: true,
      render: (v: string | null | undefined, r: CryptoTailDecisionEventDto) =>
        <Typography.Text>{v || `#${r.strategyId}`}</Typography.Text>
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
      align: 'center' as const,
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
      align: 'center' as const,
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
        const content = <Typography.Text ellipsis style={{ maxWidth: 260 }}>{text}</Typography.Text>
        return snapshot ? (
          <Tooltip title={<pre style={{ margin: 0, whiteSpace: 'pre-wrap', maxWidth: 360 }}>{snapshot}</pre>}>
            {content}
          </Tooltip>
        ) : (
          <Typography.Text type={text === '-' ? 'secondary' : undefined}>{text}</Typography.Text>
        )
      }
    }
  ]

  return (
    <div style={{ padding: isMobile ? 12 : 24 }}>
      <Card>
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Title level={isMobile ? 5 : 4} style={{ margin: 0 }}>
            {t('cryptoTailStrategy.decisionLogPage.title')}
          </Title>

          <Space wrap size="middle" style={{ width: '100%' }}>
            <Select
              value={strategyId}
              onChange={setStrategyId}
              style={{ minWidth: 200 }}
              options={[
                { value: 0, label: t('cryptoTailStrategy.decisionLogPage.allStrategies') },
                ...strategies.map((s) => ({ value: s.id, label: s.name || `#${s.id}` }))
              ]}
            />
            <Radio.Group value={preset} onChange={(e) => setPreset(e.target.value)} optionType="button" buttonStyle="solid">
              <Radio.Button value="today">{t('cryptoTailStrategy.decisionLogPage.presetToday')}</Radio.Button>
              <Radio.Button value="week">{t('cryptoTailStrategy.decisionLogPage.presetWeek')}</Radio.Button>
              <Radio.Button value="7d">{t('cryptoTailStrategy.decisionLogPage.preset7d')}</Radio.Button>
              <Radio.Button value="custom">{t('cryptoTailStrategy.decisionLogPage.presetCustom')}</Radio.Button>
            </Radio.Group>
            {preset === 'custom' && (
              <RangePicker
                value={customRange}
                onChange={(v) => setCustomRange((v as [Dayjs | null, Dayjs | null]) ?? [null, null])}
              />
            )}
            <Button icon={<ReloadOutlined />} onClick={() => loadLogs()} loading={loading}>
              {t('cryptoTailStrategy.decisionLogPage.refresh')}
            </Button>
            <Button type="primary" icon={<DownloadOutlined />} onClick={handleExport} loading={exporting}>
              {t('cryptoTailStrategy.decisionLogPage.exportJson')}
            </Button>
          </Space>

          <Table
            rowKey="id"
            size="small"
            loading={loading}
            dataSource={events}
            columns={columns}
            scroll={{ x: isMobile ? 900 : undefined }}
            locale={{
              emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('cryptoTailStrategy.decisionLog.empty')} />
            }}
            pagination={{
              current: page,
              pageSize,
              total,
              showSizeChanger: !isMobile,
              showTotal: (tt) => t('cryptoTailStrategy.decisionLogPage.totalCount', { count: tt }),
              onChange: (p, ps) => {
                setPage(p)
                setPageSize(ps)
                loadLogs({ page: p, pageSize: ps })
              }
            }}
          />
        </Space>
      </Card>
    </div>
  )
}

export default CryptoTailDecisionLog
