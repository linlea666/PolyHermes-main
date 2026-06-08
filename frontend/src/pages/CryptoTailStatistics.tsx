import { useEffect, useRef, useState, useCallback } from 'react'
import { Card, Row, Col, Statistic, message, DatePicker, Space, Button, Typography, Select, Segmented, Table, Empty, Alert, Tag } from 'antd'
import { ArrowUpOutlined, ArrowDownOutlined, ReloadOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { useMediaQuery } from 'react-responsive'
import dayjs, { type Dayjs } from 'dayjs'
import * as echarts from 'echarts'
import type { EChartsOption } from 'echarts'
import type { ColumnsType } from 'antd/es/table'
import { apiService } from '../services/api'
import { useAccountStore } from '../store/accountStore'
import { formatUSDC, formatNumber } from '../utils'
import type { CryptoTailStatsResponse, CryptoTailStatsMarket, CryptoTailStatsTrade, CryptoTailStatsRequest } from '../types'

const { RangePicker } = DatePicker
const { Title } = Typography

type Granularity = 'day' | 'week' | 'month'

const pnlColor = (value: string | number | null | undefined): string | undefined => {
  if (value == null || value === '') return undefined
  const num = typeof value === 'number' ? value : parseFloat(value)
  if (Number.isNaN(num)) return undefined
  if (num > 0) return '#3f8600'
  if (num < 0) return '#cf1322'
  return undefined
}

const CryptoTailStatistics: React.FC = () => {
  const { t } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const { accounts, fetchAccounts } = useAccountStore()

  const [data, setData] = useState<CryptoTailStatsResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const [accountId, setAccountId] = useState<number | undefined>(undefined)
  const [mode, setMode] = useState<number | undefined>(4)
  const [granularity, setGranularity] = useState<Granularity>('day')
  // 首屏默认近 30 天，避免无区间全量加载已结算触发（扩展性保护）
  const [dateRange, setDateRange] = useState<[Dayjs | null, Dayjs | null]>([dayjs().subtract(29, 'day'), dayjs()])

  const chartRef = useRef<HTMLDivElement>(null)
  const chartInstance = useRef<echarts.ECharts | null>(null)

  useEffect(() => {
    fetchAccounts()
  }, [fetchAccounts])

  const fetchStats = useCallback(async () => {
    setLoading(true)
    try {
      const req: CryptoTailStatsRequest = {
        mode,
        accountId,
        granularity,
        startDate: dateRange[0] ? dateRange[0].startOf('day').valueOf() : undefined,
        endDate: dateRange[1] ? dateRange[1].endOf('day').valueOf() : undefined
      }
      const res = await apiService.cryptoTailStrategy.statsOverview(req)
      if (res.data.code === 0 && res.data.data) {
        setData(res.data.data)
      } else {
        message.error(res.data.msg || t('cryptoTailStatistics.fetchFailed'))
      }
    } catch (e) {
      message.error((e as Error).message || t('cryptoTailStatistics.fetchFailed'))
    } finally {
      setLoading(false)
    }
  }, [mode, accountId, granularity, dateRange, t])

  useEffect(() => {
    fetchStats()
  }, [fetchStats])

  useEffect(() => {
    const buckets = data?.buckets
    if (!buckets?.length || !chartRef.current) {
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
    const option: EChartsOption = {
      tooltip: {
        trigger: 'axis',
        formatter: (params: unknown) => {
          const arr = params as Array<{ name: string; dataIndex: number; value: number }>
          const p = arr[0]
          if (!p) return ''
          const b = buckets[p.dataIndex]
          const settled = b?.settledCount ?? 0
          const winRate = settled > 0 ? ((b.winCount / settled) * 100).toFixed(2) : '0'
          return `${p.name}<br/>` +
            `${t('cryptoTailStatistics.bucketPnl')}: $${formatUSDC(String(p.value))}<br/>` +
            `${t('cryptoTailStatistics.settledCount')}: ${formatNumber(settled)}<br/>` +
            `${t('cryptoTailStatistics.winRate')}: ${winRate}%`
        }
      },
      grid: { left: '3%', right: '4%', bottom: '3%', top: '12%', containLabel: true },
      xAxis: { type: 'category', data: buckets.map((b) => b.label) },
      yAxis: { type: 'value', axisLabel: { formatter: (val: number) => '$' + String(val) } },
      series: [{
        name: t('cryptoTailStatistics.bucketPnl'),
        type: 'bar',
        data: buckets.map((b) => ({
          value: parseFloat(b.pnl),
          itemStyle: { color: parseFloat(b.pnl) >= 0 ? '#52c41a' : '#ff4d4f' }
        })),
        barMaxWidth: 36
      }]
    }
    chartInstance.current.setOption(option, true)
    chartInstance.current.resize()
  }, [data, t])

  useEffect(() => {
    const handleResize = () => chartInstance.current?.resize()
    window.addEventListener('resize', handleResize)
    return () => window.removeEventListener('resize', handleResize)
  }, [])

  useEffect(() => () => {
    chartInstance.current?.dispose()
    chartInstance.current = null
  }, [])

  const summary = data?.summary

  const marketColumns: ColumnsType<CryptoTailStatsMarket> = [
    {
      title: t('cryptoTailStatistics.market'),
      dataIndex: 'marketTitle',
      key: 'market',
      render: (_: string, r) => r.marketTitle || r.marketSlugPrefix
    },
    {
      title: t('cryptoTailStatistics.totalPnl'),
      dataIndex: 'totalPnl',
      key: 'totalPnl',
      align: 'right',
      render: (v: string) => <span style={{ color: pnlColor(v) }}>${formatUSDC(v)}</span>
    },
    {
      title: t('cryptoTailStatistics.settledCount'),
      dataIndex: 'settledCount',
      key: 'settledCount',
      align: 'right',
      render: (v: number) => formatNumber(v)
    },
    {
      title: t('cryptoTailStatistics.winRate'),
      dataIndex: 'winRate',
      key: 'winRate',
      align: 'right',
      render: (v: string) => `${v}%`
    },
    {
      title: t('cryptoTailStatistics.avgPnl'),
      dataIndex: 'avgPnl',
      key: 'avgPnl',
      align: 'right',
      render: (v: string) => <span style={{ color: pnlColor(v) }}>${formatUSDC(v)}</span>
    }
  ]

  const exitStatusColor = (s?: string | null): string => {
    switch (s) {
      case 'FULLY_EXITED': return 'green'
      case 'HELD_TO_SETTLE': return 'orange'
      case 'PARTIAL_EXIT': return 'gold'
      case 'OPEN': return 'blue'
      default: return 'default'
    }
  }

  const tradeColumns: ColumnsType<CryptoTailStatsTrade> = [
    {
      title: t('cryptoTailStatistics.tradeTriggerId'),
      dataIndex: 'triggerId',
      key: 'triggerId',
      render: (v: number) => `#${v}`
    },
    {
      title: t('cryptoTailStatistics.market'),
      dataIndex: 'marketTitle',
      key: 'market',
      render: (_: string, r) => r.marketTitle || r.marketSlugPrefix
    },
    {
      title: t('cryptoTailStatistics.tradeRealizedPnl'),
      dataIndex: 'realizedPnl',
      key: 'realizedPnl',
      align: 'right',
      render: (v: string) => <span style={{ color: pnlColor(v) }}>${formatUSDC(v)}</span>
    },
    {
      title: t('cryptoTailStatistics.tradeExitStatus'),
      dataIndex: 'exitStatus',
      key: 'exitStatus',
      render: (v?: string | null) => v ? <Tag color={exitStatusColor(v)}>{v}</Tag> : '-'
    },
    {
      title: t('cryptoTailStatistics.tradeSettleSource'),
      dataIndex: 'settleSource',
      key: 'settleSource',
      render: (v?: string | null) => {
        if (!v) return '-'
        const reconciled = v.includes('RECONCILED')
        return <Tag color={reconciled ? 'gold' : 'default'}>{v}</Tag>
      }
    },
    {
      title: t('cryptoTailStatistics.tradeWon'),
      dataIndex: 'won',
      key: 'won',
      render: (v?: boolean | null) => v == null ? '-' : (v ? <Tag color="green">{t('cryptoTailStatistics.tradeWonYes')}</Tag> : <Tag color="red">{t('cryptoTailStatistics.tradeWonNo')}</Tag>)
    }
  ]

  const alerts = data?.alerts || []
  const trades = data?.trades || []

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 12 }}>
        <Title level={2} style={{ margin: 0 }}>{t('cryptoTailStatistics.title')}</Title>
        <Space size="middle" wrap>
          <Select
            allowClear
            style={{ minWidth: 160 }}
            placeholder={t('cryptoTailStatistics.allAccounts')}
            value={accountId}
            onChange={(v) => setAccountId(v)}
            options={accounts.map((a) => ({ label: a.accountName || `#${a.id}`, value: a.id }))}
            size={isMobile ? 'middle' : 'large'}
          />
          <Select
            allowClear
            style={{ minWidth: 140 }}
            placeholder={t('cryptoTailStatistics.allModes')}
            value={mode}
            onChange={(v) => setMode(v)}
            options={[
              { label: t('cryptoTailStatistics.modeScalp'), value: 4 },
              { label: t('cryptoTailStatistics.modeTailDiff'), value: 3 },
              { label: t('cryptoTailStatistics.modeBracket'), value: 2 },
              { label: t('cryptoTailStatistics.modeBarrier'), value: 1 }
            ]}
            size={isMobile ? 'middle' : 'large'}
          />
          <RangePicker
            value={dateRange}
            onChange={(d) => setDateRange((d as [Dayjs | null, Dayjs | null]) || [null, null])}
            format="YYYY-MM-DD"
            allowClear
            size={isMobile ? 'middle' : 'large'}
          />
          <Button type="primary" icon={<ReloadOutlined />} onClick={fetchStats} loading={loading} size={isMobile ? 'middle' : 'large'}>
            {t('common.refresh')}
          </Button>
        </Space>
      </div>

      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} md={8}>
          <Card>
            <Statistic title={t('cryptoTailStatistics.totalOrders')} value={formatNumber(summary?.totalOrders || 0)} loading={loading} />
          </Card>
        </Col>
        <Col xs={24} sm={12} md={8}>
          <Card>
            <Statistic
              title={t('cryptoTailStatistics.totalPnl')}
              value={formatUSDC(summary?.totalPnl || '0')}
              prefix={<>{summary?.totalPnl && parseFloat(summary.totalPnl) >= 0 ? <ArrowUpOutlined /> : <ArrowDownOutlined />} $</>}
              valueStyle={{ color: pnlColor(summary?.totalPnl) }}
              loading={loading}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} md={8}>
          <Card>
            <Statistic title={t('cryptoTailStatistics.winRate')} value={summary?.winRate || '0'} precision={2} suffix="%" loading={loading} />
          </Card>
        </Col>
        <Col xs={24} sm={12} md={8}>
          <Card>
            <Statistic
              title={t('cryptoTailStatistics.avgPnl')}
              value={formatUSDC(summary?.avgPnl || '0')}
              prefix={<>{summary?.avgPnl && parseFloat(summary.avgPnl) >= 0 ? <ArrowUpOutlined /> : <ArrowDownOutlined />} $</>}
              valueStyle={{ color: pnlColor(summary?.avgPnl) }}
              loading={loading}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} md={8}>
          <Card>
            <Statistic title={t('cryptoTailStatistics.maxProfit')} value={formatUSDC(summary?.maxProfit || '0')} prefix={<><ArrowUpOutlined /> $</>} valueStyle={{ color: '#3f8600' }} loading={loading} />
          </Card>
        </Col>
        <Col xs={24} sm={12} md={8}>
          <Card>
            <Statistic title={t('cryptoTailStatistics.maxLoss')} value={formatUSDC(summary?.maxLoss || '0')} prefix={<><ArrowDownOutlined /> $</>} valueStyle={{ color: '#cf1322' }} loading={loading} />
          </Card>
        </Col>
      </Row>

      <Card
        style={{ marginTop: 16 }}
        title={t('cryptoTailStatistics.bucketChartTitle')}
        extra={
          <Segmented
            value={granularity}
            onChange={(v) => setGranularity(v as Granularity)}
            options={[
              { label: t('cryptoTailStatistics.day'), value: 'day' },
              { label: t('cryptoTailStatistics.week'), value: 'week' },
              { label: t('cryptoTailStatistics.month'), value: 'month' }
            ]}
          />
        }
      >
        {!data?.buckets?.length
          ? <Empty description={t('cryptoTailStatistics.empty')} />
          : <div ref={chartRef} style={{ width: '100%', height: isMobile ? 260 : 340 }} />}
      </Card>

      {alerts.length > 0 && (
        <Card style={{ marginTop: 16 }} title={t('cryptoTailStatistics.alertsTitle')}>
          <Space direction="vertical" style={{ width: '100%' }} size={8}>
            {alerts.map((a, idx) => (
              <Alert
                key={`${a.triggerId}-${a.type}-${idx}`}
                type={a.severity === 'warning' ? 'warning' : 'info'}
                showIcon
                message={<span>{t(`cryptoTailStatistics.alertType.${a.type}`, a.type)}: {a.message}</span>}
              />
            ))}
          </Space>
        </Card>
      )}

      <Card style={{ marginTop: 16 }} title={t('cryptoTailStatistics.byMarketTitle')}>
        <Table
          rowKey="marketSlugPrefix"
          dataSource={data?.byMarket || []}
          columns={marketColumns}
          loading={loading}
          pagination={false}
          size={isMobile ? 'small' : 'middle'}
          scroll={{ x: isMobile ? 600 : undefined }}
          locale={{ emptyText: <Empty description={t('cryptoTailStatistics.empty')} /> }}
        />
      </Card>

      <Card style={{ marginTop: 16 }} title={t('cryptoTailStatistics.tradesTitle')}>
        <Table
          rowKey="triggerId"
          dataSource={trades}
          columns={tradeColumns}
          loading={loading}
          pagination={{ pageSize: isMobile ? 10 : 20, showSizeChanger: !isMobile }}
          size={isMobile ? 'small' : 'middle'}
          scroll={{ x: isMobile ? 700 : undefined }}
          locale={{ emptyText: <Empty description={t('cryptoTailStatistics.empty')} /> }}
        />
      </Card>
    </div>
  )
}

export default CryptoTailStatistics
