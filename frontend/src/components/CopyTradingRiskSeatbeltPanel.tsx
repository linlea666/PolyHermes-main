import { Alert, Button, Card, Col, Modal, Row, Space, Statistic, Table, Tag, message } from 'antd'
import { useState } from 'react'
import { apiService } from '../services/api'
import type { CopyTradingStatistics } from '../types'
import { formatUSDC } from '../utils'

interface Props {
  statistics: CopyTradingStatistics
  onApplied?: () => void
  compact?: boolean
}

const fieldLabels: Record<string, string> = {
  maxDailyOrders: '每日最大订单数',
  maxDailyLoss: '每日最大亏损',
  minPrice: '最低价格',
  maxPrice: '最高价格',
  maxPositionValue: '单市场最大仓位',
  minOrderDepth: '最小深度',
  maxSpread: '最大价差',
  priceTolerance: '价格容忍度'
}

const statusText: Record<string, string> = {
  AVAILABLE: '报价可用',
  NO_MATCH: '有持仓未匹配到报价',
  UNAVAILABLE: '报价不可用'
}

const statusColor: Record<string, string> = {
  AVAILABLE: 'green',
  NO_MATCH: 'orange',
  UNAVAILABLE: 'red'
}

type ApplyConservativeConfigPayload = Parameters<typeof apiService.safetyConfig.applyConservative>[0]

const CopyTradingRiskSeatbeltPanel: React.FC<Props> = ({ statistics, onApplied, compact = false }) => {
  const diagnosis = statistics.riskDiagnosis
  const [applying, setApplying] = useState(false)

  if (!diagnosis) {
    return (
      <Card title="风险安全带" style={{ marginTop: 16 }}>
        <Alert type="info" showIcon message="暂无诊断数据" description="旧接口仍可展示基础统计，诊断数据生成后这里会显示亏损归因和风控建议。" />
      </Card>
    )
  }

  const riskWarnings = diagnosis.riskWarnings || []
  const dangerousWarnings = riskWarnings.filter(item => item.severity === 'HIGH' || item.severity === 'MEDIUM')

  const buildApplyPayload = (): ApplyConservativeConfigPayload => {
    const payload: ApplyConservativeConfigPayload = {
      copyTradingId: statistics.copyTradingId,
      confirm: true
    }
    riskWarnings.forEach(item => {
      if (item.suggestedValue === undefined || item.suggestedValue === null) {
        return
      }

      switch (item.field) {
        case 'maxDailyOrders':
          payload.maxDailyOrders = Number(item.suggestedValue)
          break
        case 'maxDailyLoss':
          payload.maxDailyLoss = item.suggestedValue
          break
        case 'minPrice':
          payload.minPrice = item.suggestedValue
          break
        case 'maxPrice':
          payload.maxPrice = item.suggestedValue
          break
        case 'maxPositionValue':
          payload.maxPositionValue = item.suggestedValue
          break
        case 'minOrderDepth':
          payload.minOrderDepth = item.suggestedValue
          break
        case 'maxSpread':
          payload.maxSpread = item.suggestedValue
          break
        case 'priceTolerance':
          payload.priceTolerance = item.suggestedValue
          break
      }
    })
    return payload
  }

  const applyConservativeConfig = () => {
    if (dangerousWarnings.length === 0) {
      message.info('当前配置已经比较保守，无需应用安全带建议')
      return
    }

    Modal.confirm({
      title: '确认应用保守配置？',
      content: (
        <div>
          <p>这只会修改风控白名单字段，不会启用/停用跟单，也不会更换 leader。</p>
          <Table
            size="small"
            pagination={false}
            rowKey="field"
            dataSource={dangerousWarnings}
            columns={[
              { title: '字段', dataIndex: 'field', render: (field: string) => fieldLabels[field] || field },
              { title: '当前值', dataIndex: 'currentValue', render: (value: string | null) => value ?? '未设置' },
              { title: '建议值', dataIndex: 'suggestedValue' }
            ]}
          />
        </div>
      ),
      okText: '确认应用',
      cancelText: '取消',
      onOk: async () => {
        setApplying(true)
        try {
          const response = await apiService.safetyConfig.applyConservative(buildApplyPayload())
          if (response.data.code === 0) {
            message.success('已应用保守配置')
            onApplied?.()
          } else {
            message.error(response.data.msg || '应用保守配置失败')
          }
        } finally {
          setApplying(false)
        }
      }
    })
  }

  return (
    <Card title="亏损归因与风险安全带" style={{ marginTop: compact ? 12 : 16 }}>
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        {diagnosis.dataIncomplete && (
          <Alert
            type="warning"
            showIcon
            message="诊断数据不完整"
            description={`缺失来源：${diagnosis.missingSources.join('、') || '未知'}。系统不会把未知估值当作已确认归零。`}
          />
        )}
        {diagnosis.lowConfidence && (
          <Alert type="info" showIcon message="低置信度" description={diagnosis.confidenceReason} />
        )}

        <Row gutter={[16, 16]}>
          <Col xs={24} sm={12} md={6}>
            <Statistic title="归零/未知持仓成本" value={formatUSDC(diagnosis.zeroValuePositionCost)} prefix="$" />
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Statistic title="已确认归零成本" value={formatUSDC(diagnosis.confirmedZeroValuePositionCost)} prefix="$" />
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Statistic title="卖出归零亏损" value={formatUSDC(diagnosis.zeroSellLoss)} prefix="$" />
          </Col>
          <Col xs={24} sm={12} md={6}>
            <div style={{ color: '#999', fontSize: 14, marginBottom: 4 }}>报价状态</div>
            <Tag color={statusColor[diagnosis.quoteOverallStatus] || 'default'}>
              {statusText[diagnosis.quoteOverallStatus] || diagnosis.quoteOverallStatus}
            </Tag>
            <div style={{ color: '#999', marginTop: 8, fontSize: 12 }}>
              可用 {diagnosis.quoteAvailableCount}，未匹配 {diagnosis.quoteNoMatchCount}，不可用 {diagnosis.quoteUnavailableCount}
            </div>
          </Col>
        </Row>

        <div>
          <h4 style={{ marginBottom: 8 }}>亏损最大的市场</h4>
          {diagnosis.topLosingMarkets.length === 0 ? (
            <Alert type="success" showIcon message="暂无已实现亏损市场" />
          ) : (
            <Table
              size="small"
              pagination={false}
              rowKey="marketId"
              dataSource={diagnosis.topLosingMarkets}
              columns={[
                { title: '市场', dataIndex: 'marketId' },
                { title: '已实现 PnL', dataIndex: 'realizedPnl', render: (value: string) => `$${formatUSDC(value)}` },
                { title: '匹配数', dataIndex: 'matchedOrders' }
              ]}
            />
          )}
        </div>

        <div>
          <h4 style={{ marginBottom: 8 }}>风险配置体检</h4>
          {riskWarnings.length === 0 ? (
            <Alert type="success" showIcon message="当前配置已经比较保守" />
          ) : (
            <Table
              size="small"
              pagination={false}
              rowKey="field"
              dataSource={riskWarnings}
              columns={[
                { title: '字段', dataIndex: 'field', render: (field: string) => fieldLabels[field] || field },
                { title: '当前值', dataIndex: 'currentValue', render: (value: string | null) => value ?? '未设置' },
                { title: '建议值', dataIndex: 'suggestedValue' },
                { title: '级别', dataIndex: 'severity', render: (severity: string) => <Tag color={severity === 'HIGH' ? 'red' : 'orange'}>{severity}</Tag> },
                { title: '原因', dataIndex: 'reason' }
              ]}
            />
          )}
        </div>

        <Button type="primary" danger disabled={dangerousWarnings.length === 0} loading={applying} onClick={applyConservativeConfig}>
          应用保守配置
        </Button>
      </Space>
    </Card>
  )
}

export default CopyTradingRiskSeatbeltPanel
