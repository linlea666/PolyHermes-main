import { useEffect, useRef, useState } from 'react'
import { Button, Card, Input, InputNumber, Radio, Select, Space, Typography, Empty } from 'antd'
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'

/**
 * 入场分段（tailDiffEntrySegmentsJson）双模式编辑器：受控表单控件。
 * - value：JSON 字符串（与后端字段一致，空串 = 不分段 / 单窗口）。
 * - onChange：序列化后的 JSON 字符串。
 *
 * 复用决策：不改后端字段与校验器（TailDiffEntrySegmentResolver），可视化值序列化为同一字段名提交；
 * 后端 asInt/asBigDecimal 同时接受 Number 与 String，本组件数值以 String 提交，零回归。
 */

interface SegmentRow {
  name?: string
  remaining_hi?: string
  remaining_lo?: string
  min_score?: string
  min_diff_sigma?: string
  min_edge?: string
  min_model_prob?: string
  max_ask?: string
  exit_tier_bias?: string
  _extra?: Record<string, unknown>
}

const KNOWN_KEYS = new Set([
  'name', 'remaining_hi', 'remaining_lo', 'min_score',
  'min_diff_sigma', 'min_edge', 'min_model_prob', 'max_ask', 'exit_tier_bias'
])

const toStr = (v: unknown): string | undefined =>
  v == null || v === '' ? undefined : String(v)

/** 解析 JSON 字符串为行数组；失败返回 null（提示切换 JSON 模式修正） */
const parseSegments = (raw?: string): SegmentRow[] | null => {
  const text = (raw ?? '').trim()
  if (!text) return []
  let arr: unknown
  try {
    arr = JSON.parse(text)
  } catch {
    return null
  }
  if (!Array.isArray(arr)) return null
  return arr.map((item) => {
    const obj = (item && typeof item === 'object' ? item : {}) as Record<string, unknown>
    const extra: Record<string, unknown> = {}
    Object.keys(obj).forEach((k) => {
      if (!KNOWN_KEYS.has(k)) extra[k] = obj[k]
    })
    return {
      name: toStr(obj.name),
      remaining_hi: toStr(obj.remaining_hi),
      remaining_lo: toStr(obj.remaining_lo),
      min_score: toStr(obj.min_score),
      min_diff_sigma: toStr(obj.min_diff_sigma),
      min_edge: toStr(obj.min_edge),
      min_model_prob: toStr(obj.min_model_prob),
      max_ask: toStr(obj.max_ask),
      exit_tier_bias: toStr(obj.exit_tier_bias),
      _extra: Object.keys(extra).length > 0 ? extra : undefined
    }
  })
}

const numField = (out: Record<string, unknown>, key: string, v?: string) => {
  if (v == null || v === '') return
  const n = Number(v)
  if (Number.isFinite(n)) out[key] = n
}

/** 行数组序列化为 JSON 字符串；空数组 → 空串（保留"不分段"语义） */
const serializeSegments = (rows: SegmentRow[]): string => {
  if (rows.length === 0) return ''
  const arr = rows.map((r) => {
    const out: Record<string, unknown> = { ...(r._extra ?? {}) }
    if (r.name && r.name.trim()) out.name = r.name.trim()
    numField(out, 'remaining_hi', r.remaining_hi)
    numField(out, 'remaining_lo', r.remaining_lo)
    numField(out, 'min_score', r.min_score)
    numField(out, 'min_diff_sigma', r.min_diff_sigma)
    numField(out, 'min_edge', r.min_edge)
    numField(out, 'min_model_prob', r.min_model_prob)
    numField(out, 'max_ask', r.max_ask)
    if (r.exit_tier_bias) out.exit_tier_bias = r.exit_tier_bias
    return out
  })
  return JSON.stringify(arr)
}

interface Props {
  value?: string
  onChange?: (v: string) => void
}

const TailDiffEntrySegmentsField: React.FC<Props> = ({ value, onChange }) => {
  const { t } = useTranslation()
  const [mode, setMode] = useState<'visual' | 'json'>('visual')
  const [rows, setRows] = useState<SegmentRow[]>([])
  const [parseError, setParseError] = useState(false)
  // 记录本组件最后一次向外 emit 的字符串，用于区分"外部重置"与"自身编辑"
  const lastEmittedRef = useRef<string | undefined>(undefined)

  // 外部 value 变化（打开编辑/重置表单）时同步内部行；自身 emit 不重复解析
  useEffect(() => {
    if (value === lastEmittedRef.current) return
    const parsed = parseSegments(value)
    if (parsed == null) {
      setParseError(true)
      setMode('json')
    } else {
      setParseError(false)
      setRows(parsed)
    }
    lastEmittedRef.current = value
  }, [value])

  const emit = (next: SegmentRow[]) => {
    setRows(next)
    const str = serializeSegments(next)
    lastEmittedRef.current = str
    onChange?.(str)
  }

  const updateRow = (idx: number, patch: Partial<SegmentRow>) => {
    emit(rows.map((r, i) => (i === idx ? { ...r, ...patch } : r)))
  }

  const addRow = () => emit([...rows, {}])
  const removeRow = (idx: number) => emit(rows.filter((_, i) => i !== idx))

  const onModeChange = (next: 'visual' | 'json') => {
    if (next === 'visual') {
      const parsed = parseSegments(value)
      if (parsed == null) {
        setParseError(true)
        return // JSON 不合法时不允许切回可视化，先让用户修正
      }
      setParseError(false)
      setRows(parsed)
    }
    setMode(next)
  }

  return (
    <Space direction="vertical" size="small" style={{ width: '100%' }}>
      <Radio.Group value={mode} onChange={(e) => onModeChange(e.target.value)} size="small">
        <Radio.Button value="visual">{t('cryptoTailStrategy.form.segEditor.visualMode')}</Radio.Button>
        <Radio.Button value="json">{t('cryptoTailStrategy.form.segEditor.jsonMode')}</Radio.Button>
      </Radio.Group>

      {mode === 'json' ? (
        <Input.TextArea
          rows={4}
          value={value}
          onChange={(e) => {
            lastEmittedRef.current = e.target.value
            onChange?.(e.target.value)
          }}
          placeholder={t('cryptoTailStrategy.form.tailDiffEntrySegmentsPlaceholder')}
          status={parseError ? 'error' : undefined}
        />
      ) : (
        <Space direction="vertical" size="small" style={{ width: '100%' }}>
          {rows.length === 0 && (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={t('cryptoTailStrategy.form.segEditor.empty')}
            />
          )}
          {rows.map((row, idx) => (
            <Card
              key={idx}
              size="small"
              title={
                <Input
                  size="small"
                  value={row.name}
                  onChange={(e) => updateRow(idx, { name: e.target.value })}
                  placeholder={t('cryptoTailStrategy.form.segEditor.namePlaceholder')}
                  style={{ maxWidth: 200 }}
                />
              }
              extra={
                <Button
                  type="text"
                  danger
                  size="small"
                  icon={<DeleteOutlined />}
                  onClick={() => removeRow(idx)}
                />
              }
            >
              <Space direction="vertical" size={8} style={{ width: '100%' }}>
                <Space wrap>
                  <LabeledNumber
                    label={t('cryptoTailStrategy.form.segEditor.remainingHi')}
                    value={row.remaining_hi}
                    onChange={(v) => updateRow(idx, { remaining_hi: v })}
                    min={0}
                    precision={0}
                    addonAfter="s"
                    required
                  />
                  <LabeledNumber
                    label={t('cryptoTailStrategy.form.segEditor.remainingLo')}
                    value={row.remaining_lo}
                    onChange={(v) => updateRow(idx, { remaining_lo: v })}
                    min={0}
                    precision={0}
                    addonAfter="s"
                    required
                  />
                </Space>
                <Space wrap>
                  <LabeledNumber
                    label={t('cryptoTailStrategy.form.segEditor.minScore')}
                    value={row.min_score}
                    onChange={(v) => updateRow(idx, { min_score: v })}
                    min={0}
                    max={100}
                    precision={0}
                  />
                  <LabeledNumber
                    label={t('cryptoTailStrategy.form.segEditor.minDiffSigma')}
                    value={row.min_diff_sigma}
                    onChange={(v) => updateRow(idx, { min_diff_sigma: v })}
                    min={0}
                    step={0.1}
                  />
                  <LabeledNumber
                    label={t('cryptoTailStrategy.form.segEditor.minEdge')}
                    value={row.min_edge}
                    onChange={(v) => updateRow(idx, { min_edge: v })}
                    min={0}
                    max={1}
                    step={0.005}
                  />
                </Space>
                <Space wrap>
                  <LabeledNumber
                    label={t('cryptoTailStrategy.form.segEditor.minModelProb')}
                    value={row.min_model_prob}
                    onChange={(v) => updateRow(idx, { min_model_prob: v })}
                    min={0}
                    max={1}
                    step={0.01}
                  />
                  <LabeledNumber
                    label={t('cryptoTailStrategy.form.segEditor.maxAsk')}
                    value={row.max_ask}
                    onChange={(v) => updateRow(idx, { max_ask: v })}
                    min={0}
                    max={1}
                    step={0.01}
                  />
                  <div>
                    <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block' }}>
                      {t('cryptoTailStrategy.form.segEditor.exitTierBias')}
                    </Typography.Text>
                    <Select
                      allowClear
                      style={{ width: 130 }}
                      value={row.exit_tier_bias}
                      onChange={(v) => updateRow(idx, { exit_tier_bias: v })}
                      placeholder={t('cryptoTailStrategy.form.segEditor.exitTierBiasNone')}
                      options={[
                        { value: 'NORMAL', label: 'NORMAL' },
                        { value: 'PREMIUM', label: 'PREMIUM' },
                        { value: 'TOP', label: 'TOP' }
                      ]}
                    />
                  </div>
                </Space>
              </Space>
            </Card>
          ))}
          <Button type="dashed" icon={<PlusOutlined />} onClick={addRow} block>
            {t('cryptoTailStrategy.form.segEditor.addSegment')}
          </Button>
        </Space>
      )}
    </Space>
  )
}

interface LabeledNumberProps {
  label: string
  value?: string
  onChange: (v: string) => void
  min?: number
  max?: number
  step?: number
  precision?: number
  addonAfter?: string
  required?: boolean
}

const LabeledNumber: React.FC<LabeledNumberProps> = ({
  label, value, onChange, min, max, step, precision, addonAfter, required
}) => (
  <div>
    <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block' }}>
      {required ? <span style={{ color: '#ff4d4f', marginRight: 2 }}>*</span> : null}
      {label}
    </Typography.Text>
    <InputNumber<string>
      stringMode
      value={value}
      onChange={(v) => onChange(v == null ? '' : String(v))}
      min={min == null ? undefined : String(min)}
      max={max == null ? undefined : String(max)}
      step={step == null ? undefined : String(step)}
      precision={precision}
      addonAfter={addonAfter}
      style={{ width: addonAfter ? 130 : 110 }}
    />
  </div>
)

export default TailDiffEntrySegmentsField
