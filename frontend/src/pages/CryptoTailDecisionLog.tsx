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
  Popconfirm,
  Dropdown,
  Modal,
  Tabs,
  Statistic,
  Row,
  Col,
  message
} from 'antd'
import { DownloadOutlined, ReloadOutlined, DeleteOutlined, ClearOutlined, DownOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { useMediaQuery } from 'react-responsive'
import dayjs from 'dayjs'
import type { Dayjs } from 'dayjs'
import type { Key } from 'react'
import { apiService } from '../services/api'
import type { CryptoTailStrategyDto, CryptoTailDecisionEventDto, CryptoTailPeriodSummaryDto } from '../types'

const { Title } = Typography
const { RangePicker } = DatePicker

type RangePreset = 'today' | 'week' | '7d' | 'custom'
type PurgePreset = 'beforeToday' | 'beforeWeek' | 'before7d' | 'custom'
type ViewTab = 'event' | 'period'

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

  const [selectedRowKeys, setSelectedRowKeys] = useState<Key[]>([])
  const [deleting, setDeleting] = useState(false)
  const [purging, setPurging] = useState(false)
  const [purgeModalOpen, setPurgeModalOpen] = useState(false)
  const [purgeBefore, setPurgeBefore] = useState<Dayjs | null>(null)

  // 周期视图
  const [tab, setTab] = useState<ViewTab>('event')
  const [periods, setPeriods] = useState<CryptoTailPeriodSummaryDto[]>([])
  const [periodTotal, setPeriodTotal] = useState(0)
  const [periodPage, setPeriodPage] = useState(1)
  const [periodPageSize, setPeriodPageSize] = useState(20)
  const [periodLoading, setPeriodLoading] = useState(false)
  const [periodAccuracy, setPeriodAccuracy] = useState<{ accuracy: string | null; settled: number; correct: number; traded: number }>({ accuracy: null, settled: 0, correct: 0, traded: 0 })
  // 周期展开时按需加载的该周期决策事件
  const [periodEvents, setPeriodEvents] = useState<Record<number, CryptoTailDecisionEventDto[]>>({})
  const [periodEventsLoading, setPeriodEventsLoading] = useState<Record<number, boolean>>({})

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

  const loadPeriods = useCallback(async (opts?: { page?: number; pageSize?: number }) => {
    const p = opts?.page ?? periodPage
    const ps = opts?.pageSize ?? periodPageSize
    const { startDate, endDate } = resolveRange()
    setPeriodLoading(true)
    try {
      const res = await apiService.cryptoTailStrategy.periodSummaryList({
        strategyId,
        page: p,
        pageSize: ps,
        startDate,
        endDate
      })
      if (res.data.code === 0 && res.data.data) {
        const d = res.data.data
        setPeriods(d.list ?? [])
        setPeriodTotal(d.total ?? 0)
        setPeriodAccuracy({
          accuracy: d.directionAccuracy ?? null,
          settled: d.settledCount ?? 0,
          correct: d.directionCorrectCount ?? 0,
          traded: d.tradedCount ?? 0
        })
      } else {
        setPeriods([])
        setPeriodTotal(0)
        setPeriodAccuracy({ accuracy: null, settled: 0, correct: 0, traded: 0 })
      }
    } finally {
      setPeriodLoading(false)
    }
  }, [strategyId, periodPage, periodPageSize, resolveRange])

  // 展开某周期时按需加载该周期内的决策事件。
  // 结算(SETTLED)事件在周期结束后才落库，可能晚于周期末几分钟，故时间窗放宽到 +30min，
  // 再按 periodStartUnix 精确过滤，确保既不漏掉晚到的 SETTLED、又不混入相邻周期事件。
  const loadPeriodEvents = useCallback(async (row: CryptoTailPeriodSummaryDto) => {
    if (periodEvents[row.id]) return
    setPeriodEventsLoading((m) => ({ ...m, [row.id]: true }))
    try {
      const res = await apiService.cryptoTailStrategy.decisionLogAll({
        strategyId: row.strategyId,
        page: 1,
        pageSize: 200,
        startDate: row.periodStartUnix * 1000,
        endDate: row.periodEndUnix * 1000 + 30 * 60_000
      })
      if (res.data.code === 0 && res.data.data) {
        const list = (res.data.data?.list ?? []).filter((ev) => ev.periodStartUnix === row.periodStartUnix)
        setPeriodEvents((m) => ({ ...m, [row.id]: list }))
      }
    } finally {
      setPeriodEventsLoading((m) => ({ ...m, [row.id]: false }))
    }
  }, [periodEvents])

  useEffect(() => {
    loadStrategies()
  }, [loadStrategies])

  // 筛选条件变化后回到第一页并查询（两个视图都重置）
  useEffect(() => {
    setPage(1)
    setPeriodPage(1)
    setSelectedRowKeys([])
    setPeriodEvents({})
    if (tab === 'event') {
      loadLogs({ page: 1 })
    } else {
      loadPeriods({ page: 1 })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [strategyId, preset, customRange, tab])

  // 批量删除已勾选的决策日志
  const handleBatchDelete = async () => {
    if (selectedRowKeys.length === 0) return
    setDeleting(true)
    try {
      const ids = selectedRowKeys.map((k) => Number(k))
      const res = await apiService.cryptoTailStrategy.decisionLogBatchDelete({ ids })
      if (res.data.code === 0) {
        message.success(t('cryptoTailStrategy.decisionLogPage.deleteSuccess', { count: res.data.data?.deleted ?? ids.length }))
        setSelectedRowKeys([])
        loadLogs()
      } else {
        message.error(res.data.msg || t('cryptoTailStrategy.decisionLogPage.deleteFailed'))
      }
    } catch {
      message.error(t('cryptoTailStrategy.decisionLogPage.deleteFailed'))
    } finally {
      setDeleting(false)
    }
  }

  // 打开清理弹窗，按预设预填一个「之前」的日期
  const openPurgeModal = (p: PurgePreset) => {
    const now = dayjs()
    if (p === 'beforeToday') {
      setPurgeBefore(now.startOf('day'))
    } else if (p === 'beforeWeek') {
      setPurgeBefore(now.startOf('week'))
    } else if (p === 'before7d') {
      setPurgeBefore(now.subtract(7, 'day').startOf('day'))
    } else {
      setPurgeBefore(now.startOf('day'))
    }
    setPurgeModalOpen(true)
  }

  // 执行清理：删除 createdAt 严格小于所选日期的日志（按当前策略筛选范围）
  const handlePurgeConfirm = async () => {
    if (purgeBefore == null) {
      message.warning(t('cryptoTailStrategy.decisionLogPage.purgePickDate'))
      return
    }
    setPurging(true)
    try {
      const beforeDate = purgeBefore.startOf('day').valueOf()
      const res = await apiService.cryptoTailStrategy.decisionLogPurge({ strategyId, beforeDate })
      if (res.data.code === 0) {
        message.success(t('cryptoTailStrategy.decisionLogPage.purgeSuccess', { count: res.data.data?.deleted ?? 0 }))
        setPurgeModalOpen(false)
        setSelectedRowKeys([])
        loadLogs()
      } else {
        message.error(res.data.msg || t('cryptoTailStrategy.decisionLogPage.purgeFailed'))
      }
    } catch {
      message.error(t('cryptoTailStrategy.decisionLogPage.purgeFailed'))
    } finally {
      setPurging(false)
    }
  }

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

  const renderDir = (i: number | null | undefined) =>
    i == null ? <Typography.Text type="secondary">-</Typography.Text>
      : i === 0 ? <Tag color="green">{t('cryptoTailStrategy.triggerRecords.up')}</Tag>
        : <Tag color="volcano">{t('cryptoTailStrategy.triggerRecords.down')}</Tag>

  const periodColumns = [
    {
      title: t('cryptoTailStrategy.periodSummary.period'),
      key: 'period',
      width: 200,
      render: (_: unknown, r: CryptoTailPeriodSummaryDto) => (
        <Space direction="vertical" size={0}>
          <Typography.Text>{dayjs(r.periodStartUnix * 1000).format('MM-DD HH:mm')} ~ {dayjs(r.periodEndUnix * 1000).format('HH:mm')}</Typography.Text>
          {r.marketSlug && <Typography.Text type="secondary" style={{ fontSize: 12 }}>{r.marketSlug}</Typography.Text>}
        </Space>
      )
    },
    {
      title: t('cryptoTailStrategy.decisionLogPage.strategy'),
      dataIndex: 'strategyName',
      key: 'strategyName',
      width: 130,
      ellipsis: true,
      render: (v: string | null | undefined, r: CryptoTailPeriodSummaryDto) => <Typography.Text>{v || `#${r.strategyId}`}</Typography.Text>
    },
    {
      title: t('cryptoTailStrategy.periodSummary.firstChosen'),
      key: 'firstChosen',
      width: 90,
      align: 'center' as const,
      render: (_: unknown, r: CryptoTailPeriodSummaryDto) => renderDir(r.firstChosenOutcomeIndex)
    },
    {
      title: t('cryptoTailStrategy.periodSummary.flip'),
      dataIndex: 'directionFlipCount',
      key: 'flip',
      width: 80,
      align: 'center' as const,
      render: (v: number) => v > 0 ? <Tag color="orange">{t('cryptoTailStrategy.periodSummary.flipCount', { count: v })}</Tag> : <Typography.Text type="secondary">-</Typography.Text>
    },
    {
      title: t('cryptoTailStrategy.periodSummary.official'),
      key: 'official',
      width: 100,
      align: 'center' as const,
      render: (_: unknown, r: CryptoTailPeriodSummaryDto) =>
        r.status === 'SETTLED'
          ? renderDir(r.settledWinnerOutcomeIndex)
          : r.status === 'ABANDONED'
            ? <Tag color="default">{t('cryptoTailStrategy.periodSummary.abandoned')}</Tag>
            : <Tag>{t('cryptoTailStrategy.periodSummary.pending')}</Tag>
    },
    {
      title: t('cryptoTailStrategy.periodSummary.directionCorrect'),
      key: 'directionCorrect',
      width: 90,
      align: 'center' as const,
      render: (_: unknown, r: CryptoTailPeriodSummaryDto) =>
        r.directionCorrect == null ? <Typography.Text type="secondary">-</Typography.Text>
          : r.directionCorrect ? <Tag color="green">{t('cryptoTailStrategy.periodSummary.hit')}</Tag>
            : <Tag color="red">{t('cryptoTailStrategy.periodSummary.miss')}</Tag>
    },
    {
      title: t('cryptoTailStrategy.periodSummary.traded'),
      dataIndex: 'traded',
      key: 'traded',
      width: 80,
      align: 'center' as const,
      render: (v: boolean) => v ? <Tag color="blue">{t('cryptoTailStrategy.periodSummary.yes')}</Tag> : <Typography.Text type="secondary">{t('cryptoTailStrategy.periodSummary.no')}</Typography.Text>
    },
    {
      title: t('cryptoTailStrategy.periodSummary.bestScore'),
      dataIndex: 'bestScore',
      key: 'bestScore',
      width: 80,
      align: 'center' as const,
      render: (v: number) => <Typography.Text>{v}</Typography.Text>
    },
    {
      title: t('cryptoTailStrategy.periodSummary.dominantVeto'),
      dataIndex: 'dominantVeto',
      key: 'dominantVeto',
      ellipsis: true,
      render: (v: string | null | undefined) => v ? <Typography.Text code>{v}</Typography.Text> : <Typography.Text type="secondary">-</Typography.Text>
    }
  ]

  const renderPeriodDetail = (r: CryptoTailPeriodSummaryDto) => {
    const evs = periodEvents[r.id] ?? []
    return (
      <div style={{ background: '#fafafa', padding: 12 }}>
        <Space wrap size="large" style={{ marginBottom: 8 }}>
          <Typography.Text type="secondary">{t('cryptoTailStrategy.periodSummary.scoreEvents')}: {r.scoreEventCount}</Typography.Text>
          <Typography.Text type="secondary">{t('cryptoTailStrategy.periodSummary.skipEvents')}: {r.skipEventCount}</Typography.Text>
          <Typography.Text type="secondary">{t('cryptoTailStrategy.periodSummary.buyEvents')}: {r.buyEventCount}</Typography.Text>
          {r.officialOpen && <Typography.Text type="secondary">open: {r.officialOpen}</Typography.Text>}
          {r.officialClose && <Typography.Text type="secondary">close: {r.officialClose}</Typography.Text>}
          {r.realizedPnl != null && <Typography.Text type="secondary">PnL: {r.realizedPnl}</Typography.Text>}
        </Space>
        <Table
          rowKey="id"
          size="small"
          loading={periodEventsLoading[r.id]}
          dataSource={evs}
          pagination={false}
          scroll={{ x: isMobile ? 720 : undefined, y: 320 }}
          columns={[
            { title: t('cryptoTailStrategy.decisionLog.time'), dataIndex: 'createdAt', key: 'createdAt', width: 160, render: (ts: number) => <Typography.Text style={{ fontSize: 12 }}>{new Date(ts).toLocaleTimeString()}</Typography.Text> },
            { title: t('cryptoTailStrategy.decisionLog.eventType'), dataIndex: 'eventType', key: 'eventType', width: 150, render: (v: string) => <Tag>{t(`cryptoTailStrategy.decisionLog.eventType_${v}`, { defaultValue: v })}</Tag> },
            { title: t('cryptoTailStrategy.decisionLog.direction'), dataIndex: 'outcomeIndex', key: 'outcomeIndex', width: 70, align: 'center' as const, render: (i: number | null | undefined) => renderDir(i) },
            { title: t('cryptoTailStrategy.decisionLog.reason'), dataIndex: 'reason', key: 'reason', ellipsis: true, render: (v: string | null | undefined, ev: CryptoTailDecisionEventDto) => {
              const text = v ?? '-'
              return ev.payloadJson ? (
                <Tooltip title={<pre style={{ margin: 0, whiteSpace: 'pre-wrap', maxWidth: 360 }}>{ev.payloadJson}</pre>}>
                  <Typography.Text ellipsis style={{ maxWidth: 260 }}>{text}</Typography.Text>
                </Tooltip>
              ) : <Typography.Text type={text === '-' ? 'secondary' : undefined}>{text}</Typography.Text>
            } }
          ]}
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('cryptoTailStrategy.decisionLog.empty')} /> }}
        />
      </div>
    )
  }

  const eventView = (
    <>
      <Space wrap size="middle" style={{ width: '100%', marginBottom: 16 }}>
        <Button icon={<ReloadOutlined />} onClick={() => loadLogs()} loading={loading}>
          {t('cryptoTailStrategy.decisionLogPage.refresh')}
        </Button>
        <Button type="primary" icon={<DownloadOutlined />} onClick={handleExport} loading={exporting}>
          {t('cryptoTailStrategy.decisionLogPage.exportJson')}
        </Button>
        <Popconfirm
          title={t('cryptoTailStrategy.decisionLogPage.batchDeleteConfirmTitle')}
          description={t('cryptoTailStrategy.decisionLogPage.batchDeleteConfirmDesc', { count: selectedRowKeys.length })}
          okButtonProps={{ danger: true, loading: deleting }}
          onConfirm={handleBatchDelete}
          disabled={selectedRowKeys.length === 0}
        >
          <Button danger icon={<DeleteOutlined />} disabled={selectedRowKeys.length === 0}>
            {selectedRowKeys.length > 0
              ? t('cryptoTailStrategy.decisionLogPage.batchDeleteWithCount', { count: selectedRowKeys.length })
              : t('cryptoTailStrategy.decisionLogPage.batchDelete')}
          </Button>
        </Popconfirm>
        <Dropdown
          menu={{
            items: [
              { key: 'beforeToday', label: t('cryptoTailStrategy.decisionLogPage.purgeBeforeToday') },
              { key: 'beforeWeek', label: t('cryptoTailStrategy.decisionLogPage.purgeBeforeWeek') },
              { key: 'before7d', label: t('cryptoTailStrategy.decisionLogPage.purgeBefore7d') },
              { key: 'custom', label: t('cryptoTailStrategy.decisionLogPage.purgeCustom') }
            ],
            onClick: ({ key }) => openPurgeModal(key as PurgePreset)
          }}
        >
          <Button icon={<ClearOutlined />}>
            <Space size={4}>
              {t('cryptoTailStrategy.decisionLogPage.purge')}
              <DownOutlined />
            </Space>
          </Button>
        </Dropdown>
      </Space>

      <Table
        rowKey="id"
        size="small"
        loading={loading}
        dataSource={events}
        columns={columns}
        rowSelection={{
          selectedRowKeys,
          onChange: (keys) => setSelectedRowKeys(keys),
          preserveSelectedRowKeys: true
        }}
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
    </>
  )

  const periodView = (
    <>
      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={12} sm={6}>
          <Card size="small">
            <Statistic
              title={t('cryptoTailStrategy.periodSummary.directionAccuracy')}
              value={periodAccuracy.accuracy != null ? (Number(periodAccuracy.accuracy) * 100).toFixed(1) : '-'}
              suffix={periodAccuracy.accuracy != null ? '%' : ''}
            />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card size="small">
            <Statistic title={t('cryptoTailStrategy.periodSummary.settledCount')} value={periodAccuracy.settled} />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card size="small">
            <Statistic title={t('cryptoTailStrategy.periodSummary.correctCount')} value={periodAccuracy.correct} />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card size="small">
            <Statistic title={t('cryptoTailStrategy.periodSummary.tradedCount')} value={periodAccuracy.traded} />
          </Card>
        </Col>
      </Row>
      <Space wrap size="middle" style={{ width: '100%', marginBottom: 16 }}>
        <Button icon={<ReloadOutlined />} onClick={() => loadPeriods()} loading={periodLoading}>
          {t('cryptoTailStrategy.decisionLogPage.refresh')}
        </Button>
      </Space>
      <Table
        rowKey="id"
        size="small"
        loading={periodLoading}
        dataSource={periods}
        columns={periodColumns}
        expandable={{
          expandedRowRender: renderPeriodDetail,
          onExpand: (expanded, r) => { if (expanded) loadPeriodEvents(r) }
        }}
        scroll={{ x: isMobile ? 1000 : undefined }}
        locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('cryptoTailStrategy.decisionLog.empty')} /> }}
        pagination={{
          current: periodPage,
          pageSize: periodPageSize,
          total: periodTotal,
          showSizeChanger: !isMobile,
          showTotal: (tt) => t('cryptoTailStrategy.decisionLogPage.totalCount', { count: tt }),
          onChange: (p, ps) => {
            setPeriodPage(p)
            setPeriodPageSize(ps)
            loadPeriods({ page: p, pageSize: ps })
          }
        }}
      />
    </>
  )

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
          </Space>

          <Tabs
            activeKey={tab}
            onChange={(k) => setTab(k as ViewTab)}
            items={[
              { key: 'event', label: t('cryptoTailStrategy.decisionLogPage.tabEvent'), children: eventView },
              { key: 'period', label: t('cryptoTailStrategy.periodSummary.tab'), children: periodView }
            ]}
          />
        </Space>
      </Card>

      <Modal
        title={t('cryptoTailStrategy.decisionLogPage.purgeModalTitle')}
        open={purgeModalOpen}
        onOk={handlePurgeConfirm}
        onCancel={() => setPurgeModalOpen(false)}
        confirmLoading={purging}
        okButtonProps={{ danger: true }}
        okText={t('cryptoTailStrategy.decisionLogPage.purgeOk')}
        cancelText={t('cryptoTailStrategy.decisionLogPage.purgeCancel')}
      >
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Typography.Text>
            {strategyId > 0
              ? t('cryptoTailStrategy.decisionLogPage.purgeScopeStrategy', {
                  name: strategies.find((s) => s.id === strategyId)?.name || `#${strategyId}`
                })
              : t('cryptoTailStrategy.decisionLogPage.purgeScopeAll')}
          </Typography.Text>
          <Space>
            <Typography.Text>{t('cryptoTailStrategy.decisionLogPage.purgeBeforeLabel')}</Typography.Text>
            <DatePicker
              value={purgeBefore}
              onChange={(v) => setPurgeBefore(v)}
              allowClear={false}
            />
          </Space>
          <Typography.Text type="warning">
            {t('cryptoTailStrategy.decisionLogPage.purgeWarning')}
          </Typography.Text>
        </Space>
      </Modal>
    </div>
  )
}

export default CryptoTailDecisionLog
