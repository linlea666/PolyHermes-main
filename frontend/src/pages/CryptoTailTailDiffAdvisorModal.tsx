import { useEffect, useState } from 'react'
import { Modal, Table, Tag, Typography, Statistic, Row, Col, Alert, Empty, InputNumber, Button, Space, Divider, message } from 'antd'
import { useTranslation } from 'react-i18next'
import { useMediaQuery } from 'react-responsive'
import { apiService } from '../services/api'
import type { TailDiffAdvisorResponse, TailDiffAdvisorBucket, TailDiffAdvisorRecommendation } from '../types'

interface Props {
  open: boolean
  strategyId: number | null
  strategyName?: string
  onClose: () => void
}

/**
 * TAIL_DIFF 参数建议弹窗：基于该策略已结算触发记录反推更优阈值。
 * 仅展示推荐与分桶对比，不自动写入策略（需用户手动到表单调整）。
 */
const CryptoTailTailDiffAdvisorModal: React.FC<Props> = ({ open, strategyId, strategyName, onClose }) => {
  const { t } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [loading, setLoading] = useState(false)
  const [minSamples, setMinSamples] = useState(30)
  const [data, setData] = useState<TailDiffAdvisorResponse | null>(null)

  const run = async (samples: number) => {
    if (!strategyId) return
    setLoading(true)
    try {
      const res = await apiService.cryptoTailStrategy.tailDiffAdvisor({ strategyId, minSamples: samples })
      if (res.data.code !== 0 || !res.data.data) {
        message.error(res.data.msg || t('common.failed'))
        return
      }
      setData(res.data.data)
    } catch {
      message.error(t('common.failed'))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    if (open && strategyId) {
      run(minSamples)
    } else if (!open) {
      setData(null)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, strategyId])

  const pct = (v: string) => `${(Number(v) * 100).toFixed(2)}%`

  const bucketColumns = [
    { title: t('cryptoTailStrategy.advisor.bucket'), dataIndex: 'bucket' },
    { title: t('cryptoTailStrategy.advisor.sampleCount'), dataIndex: 'sampleCount' },
    {
      title: t('cryptoTailStrategy.advisor.winRate'),
      dataIndex: 'winRate',
      render: (v: string) => pct(v)
    },
    {
      title: t('cryptoTailStrategy.advisor.avgPnl'),
      dataIndex: 'avgPnl',
      render: (v: string) => (
        <Typography.Text type={Number(v) >= 0 ? 'success' : 'danger'}>{Number(v).toFixed(4)}</Typography.Text>
      )
    },
    { title: t('cryptoTailStrategy.advisor.totalPnl'), dataIndex: 'totalPnl', render: (v: string) => Number(v).toFixed(4) }
  ]

  const recColumns = [
    {
      title: t('cryptoTailStrategy.advisor.param.col'),
      dataIndex: 'labelKey',
      render: (_: string, r: TailDiffAdvisorRecommendation) => t(r.labelKey)
    },
    { title: t('cryptoTailStrategy.advisor.currentValue'), dataIndex: 'currentValue' },
    {
      title: t('cryptoTailStrategy.advisor.suggestedValue'),
      dataIndex: 'suggestedValue',
      render: (v: string, r: TailDiffAdvisorRecommendation) =>
        r.changed ? <Typography.Text strong type="warning">{v}</Typography.Text> : <Typography.Text type="secondary">{v}</Typography.Text>
    },
    {
      title: t('cryptoTailStrategy.advisor.confidence'),
      dataIndex: 'confidence',
      render: (v: string) => {
        const color = v === 'HIGH' ? 'green' : v === 'MEDIUM' ? 'gold' : 'default'
        return <Tag color={color}>{v}</Tag>
      }
    },
    { title: t('cryptoTailStrategy.advisor.rationale'), dataIndex: 'rationale', ellipsis: true }
  ]

  const renderBucketTable = (title: string, rows: TailDiffAdvisorBucket[]) =>
    rows.length > 0 ? (
      <>
        <Divider orientation="left" plain>{title}</Divider>
        <Table
          rowKey="bucket"
          dataSource={rows}
          columns={bucketColumns}
          size="small"
          pagination={false}
          scroll={{ x: isMobile ? 500 : undefined }}
        />
      </>
    ) : null

  return (
    <Modal
      open={open}
      onCancel={onClose}
      width={isMobile ? '100%' : 960}
      title={`${t('cryptoTailStrategy.advisor.title')}${strategyName ? ` - ${strategyName}` : ''}`}
      footer={null}
      destroyOnClose
    >
      <Space style={{ marginBottom: 12 }} wrap>
        <span>{t('cryptoTailStrategy.advisor.minSamples')}</span>
        <InputNumber min={1} max={5000} value={minSamples} onChange={(v) => setMinSamples(Number(v ?? 30))} style={{ width: 100 }} />
        <Button type="primary" loading={loading} onClick={() => run(minSamples)}>{t('cryptoTailStrategy.advisor.refreshBtn')}</Button>
      </Space>

      <Alert type="info" showIcon style={{ marginBottom: 12 }} message={t('cryptoTailStrategy.advisor.recommendOnly')} />

      {!data ? (
        <Empty description={t('common.noData')} />
      ) : (
        <>
          <Row gutter={16} style={{ marginBottom: 12 }}>
            <Col span={isMobile ? 12 : 6}><Statistic title={t('cryptoTailStrategy.advisor.totalSettled')} value={data.totalSettled} /></Col>
            <Col span={isMobile ? 12 : 6}><Statistic title={t('cryptoTailStrategy.advisor.winRate')} value={pct(data.winRate)} /></Col>
            <Col span={isMobile ? 12 : 6}><Statistic title={t('cryptoTailStrategy.advisor.avgPnl')} value={Number(data.avgPnl).toFixed(4)} valueStyle={{ color: Number(data.avgPnl) >= 0 ? '#3f8600' : '#cf1322' }} /></Col>
            <Col span={isMobile ? 12 : 6}><Statistic title={t('cryptoTailStrategy.advisor.totalPnl')} value={Number(data.totalPnl).toFixed(4)} valueStyle={{ color: Number(data.totalPnl) >= 0 ? '#3f8600' : '#cf1322' }} /></Col>
          </Row>

          {!data.sufficientSamples && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 12 }}
              message={t('cryptoTailStrategy.advisor.insufficient', { total: data.totalSettled, min: data.minSamples })}
            />
          )}

          {data.recommendations.length > 0 && (
            <>
              <Divider orientation="left" plain>{t('cryptoTailStrategy.advisor.recommendations')}</Divider>
              <Table
                rowKey="param"
                dataSource={data.recommendations}
                columns={recColumns}
                size="small"
                pagination={false}
                scroll={{ x: isMobile ? 700 : undefined }}
              />
            </>
          )}

          {renderBucketTable(t('cryptoTailStrategy.advisor.scoreBuckets'), data.scoreBuckets)}
          {renderBucketTable(t('cryptoTailStrategy.advisor.tierBuckets'), data.tierBuckets)}
          {renderBucketTable(t('cryptoTailStrategy.advisor.priceBuckets'), data.priceBuckets)}
          {renderBucketTable(t('cryptoTailStrategy.advisor.diffSigmaBuckets'), data.diffSigmaBuckets)}
          {renderBucketTable(t('cryptoTailStrategy.advisor.remainingBuckets'), data.remainingBuckets)}
        </>
      )}
    </Modal>
  )
}

export default CryptoTailTailDiffAdvisorModal
