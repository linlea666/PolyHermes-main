import { useEffect, useState } from 'react'
import { Card, Row, Col, Statistic, message, DatePicker, Space, Button, Typography } from 'antd'
import { ArrowUpOutlined, ArrowDownOutlined, ReloadOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import type { Dayjs } from 'dayjs'
import { apiService } from '../services/api'
import type { Statistics as StatisticsType } from '../types'
import { formatUSDC, formatNumber } from '../utils'
import { useMediaQuery } from 'react-responsive'

const { RangePicker } = DatePicker
const { Title } = Typography

const Statistics: React.FC = () => {
  const { t } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [stats, setStats] = useState<StatisticsType | null>(null)
  const [loading, setLoading] = useState(false)
  const [dateRange, setDateRange] = useState<[Dayjs | null, Dayjs | null]>([null, null])

  useEffect(() => {
    fetchStatistics()
  }, [])

  const fetchStatistics = async () => {
    setLoading(true)
    try {
      const startTime = dateRange[0] ? dateRange[0].valueOf() : undefined
      const endTime = dateRange[1] ? dateRange[1].valueOf() : undefined

      const response = await apiService.statistics.global({ startTime, endTime })
      if (response.data.code === 0 && response.data.data) {
        setStats(response.data.data)
      } else {
        message.error(response.data.msg || t('statistics.fetchFailed') || '获取统计信息失败')
      }
    } catch (error: any) {
      message.error(error.message || t('statistics.fetchFailed') || '获取统计信息失败')
    } finally {
      setLoading(false)
    }
  }

  const handleDateRangeChange = (dates: [Dayjs | null, Dayjs | null] | null) => {
    setDateRange(dates || [null, null])
  }

  const handleReset = () => {
    setDateRange([null, null])
    // 重置后自动刷新
    setTimeout(() => {
      fetchStatistics()
    }, 100)
  }

  return (
    <div>
      <div style={{ marginBottom: '16px', display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: '12px' }}>
        <Title level={2} style={{ margin: 0 }}>{t('statistics.title') || '统计信息'}</Title>
        <Space size="middle" wrap>
          <RangePicker
            value={dateRange}
            onChange={handleDateRangeChange}
            format="YYYY-MM-DD"
            placeholder={[t('statistics.startDate') || '开始日期', t('statistics.endDate') || '结束日期']}
            size={isMobile ? 'middle' : 'large'}
            allowClear
          />
          <Button
            type="primary"
            icon={<ReloadOutlined />}
            onClick={fetchStatistics}
            loading={loading}
            size={isMobile ? 'middle' : 'large'}
          >
            {t('statistics.refresh') || '刷新'}
          </Button>
          {(dateRange[0] || dateRange[1]) && (
            <Button
              onClick={handleReset}
              size={isMobile ? 'middle' : 'large'}
            >
              {t('statistics.reset') || '重置'}
            </Button>
          )}
        </Space>
      </div>

      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} md={8}>
          <Card>
            <Statistic
              title={t('statistics.totalOrders') || '总订单数'}
              value={formatNumber(stats?.totalOrders || 0)}
              loading={loading}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} md={8}>
          <Card>
            <Statistic
              title={t('statistics.totalPnl') || '总盈亏'}
              value={formatUSDC(stats?.totalPnl || '0')}
              prefix={<>{stats?.totalPnl && parseFloat(stats.totalPnl) >= 0 ? <ArrowUpOutlined /> : <ArrowDownOutlined />} $</>}
              valueStyle={{ color: stats?.totalPnl && parseFloat(stats.totalPnl || '0') >= 0 ? '#3f8600' : '#cf1322' }}
              loading={loading}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} md={8}>
          <Card>
            <Statistic
              title={t('statistics.winRate') || '胜率'}
              value={stats?.winRate || '0'}
              precision={2}
              suffix="%"
              loading={loading}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} md={8}>
          <Card>
            <Statistic
              title={t('statistics.avgPnl') || '平均盈亏'}
              value={formatUSDC(stats?.avgPnl || '0')}
              prefix={<>{stats?.avgPnl && parseFloat(stats.avgPnl || '0') >= 0 ? <ArrowUpOutlined /> : <ArrowDownOutlined />} $</>}
              valueStyle={{ color: stats?.avgPnl && parseFloat(stats.avgPnl || '0') >= 0 ? '#3f8600' : '#cf1322' }}
              loading={loading}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} md={8}>
          <Card>
            <Statistic
              title={t('statistics.maxProfit') || '最大盈利'}
              value={formatUSDC(stats?.maxProfit || '0')}
              prefix={<><ArrowUpOutlined /> $</>}
              valueStyle={{ color: '#3f8600' }}
              loading={loading}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} md={8}>
          <Card>
            <Statistic
              title={t('statistics.maxLoss') || '最大亏损'}
              value={formatUSDC(stats?.maxLoss || '0')}
              prefix={<><ArrowDownOutlined /> $</>}
              valueStyle={{ color: '#cf1322' }}
              loading={loading}
            />
          </Card>
        </Col>
      </Row>
    </div>
  )
}

export default Statistics

