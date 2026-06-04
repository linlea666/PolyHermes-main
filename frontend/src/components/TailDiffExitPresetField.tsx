import { useEffect, useRef, useState } from 'react'
import { Card, InputNumber, Radio, Space, Switch, Typography, Input } from 'antd'
import { useTranslation } from 'react-i18next'

/**
 * 三档退出预设（tailDiffExitPreset{Normal|Premium|Top}Json）双模式编辑器：受控表单控件。
 * - value：JSON 字符串（与后端字段一致，空串 = 沿用该档代码默认）。
 * - onChange：序列化后的 JSON 字符串。
 * - tier：用于空值时按档位预填合理默认（与后端 defaultForTier 对齐），仅作展示初值。
 *
 * 复用决策：不改后端字段与校验器（TailDiffExitPresetResolver）；可视化值序列化为同一字段名提交。
 * 后端 asBigDecimal 接受 Number/String，本组件数值以 String 提交；未识别的顶层 key 原样保留，零回归。
 */

type Tier = 'NORMAL' | 'PREMIUM' | 'TOP'

interface PresetState {
  hold_to_expiry: boolean
  tp_limit: { enabled: boolean; price: string; ratio: string }
  stop_loss: { enabled: boolean; offset: string; min_price: string; ratio: string }
  dynamic_exit: {
    enabled: boolean
    min_diff_sigma_after_entry: string
    max_diff_retrace_pct: string
    min_model_prob_after_entry: string
    min_odds_after_entry: string
    max_reverse_velocity_sigma: string
  }
  execution: { tp_slippage: string; stop_slippage: string; worst_price: string }
  _extra: Record<string, unknown>
}

// 与后端 TailDiffExitPresetResolver.defaultForTier 对齐，空值时按档位预填
const TIER_DEFAULTS: Record<Tier, PresetState> = {
  NORMAL: {
    hold_to_expiry: false,
    tp_limit: { enabled: true, price: '0.98', ratio: '1' },
    stop_loss: { enabled: true, offset: '0.20', min_price: '0.70', ratio: '1' },
    dynamic_exit: { enabled: true, min_diff_sigma_after_entry: '1.3', max_diff_retrace_pct: '0.50', min_model_prob_after_entry: '0.88', min_odds_after_entry: '0.80', max_reverse_velocity_sigma: '0.40' },
    execution: { tp_slippage: '', stop_slippage: '', worst_price: '' },
    _extra: {}
  },
  PREMIUM: {
    hold_to_expiry: false,
    tp_limit: { enabled: true, price: '0.99', ratio: '1' },
    stop_loss: { enabled: false, offset: '0.30', min_price: '0.60', ratio: '1' },
    dynamic_exit: { enabled: true, min_diff_sigma_after_entry: '1.0', max_diff_retrace_pct: '0.60', min_model_prob_after_entry: '0.85', min_odds_after_entry: '0.75', max_reverse_velocity_sigma: '0.50' },
    execution: { tp_slippage: '', stop_slippage: '', worst_price: '' },
    _extra: {}
  },
  TOP: {
    hold_to_expiry: true,
    tp_limit: { enabled: false, price: '1', ratio: '1' },
    stop_loss: { enabled: false, offset: '0.40', min_price: '0.50', ratio: '1' },
    dynamic_exit: { enabled: true, min_diff_sigma_after_entry: '0.5', max_diff_retrace_pct: '0.80', min_model_prob_after_entry: '0.80', min_odds_after_entry: '0.70', max_reverse_velocity_sigma: '0.70' },
    execution: { tp_slippage: '', stop_slippage: '', worst_price: '' },
    _extra: {}
  }
}

const KNOWN_TOP_KEYS = new Set([
  'hold_to_expiry', 'holdToExpiry',
  'tp_limit', 'tpLimit',
  'stop_loss', 'stopLoss',
  'dynamic_exit', 'dynamicExit',
  'execution'
])

const pick = (obj: Record<string, unknown>, snake: string, camel: string): unknown =>
  obj[snake] !== undefined ? obj[snake] : obj[camel]

const asObj = (v: unknown): Record<string, unknown> =>
  v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : {}

const asBool = (v: unknown, def: boolean): boolean =>
  typeof v === 'boolean' ? v : typeof v === 'string' ? v.toLowerCase() === 'true' : def

const asStr = (v: unknown, def: string): string =>
  v == null || v === '' ? def : String(v)

/** 解析 JSON 字符串为预设状态；失败返回 null（提示切换 JSON 模式修正） */
const parsePreset = (raw: string | undefined, def: PresetState): PresetState | null => {
  const text = (raw ?? '').trim()
  if (!text) return { ...def, _extra: {} }
  let obj: unknown
  try {
    obj = JSON.parse(text)
  } catch {
    return null
  }
  if (typeof obj !== 'object' || obj === null || Array.isArray(obj)) return null
  const o = obj as Record<string, unknown>
  const tp = asObj(pick(o, 'tp_limit', 'tpLimit'))
  const sl = asObj(pick(o, 'stop_loss', 'stopLoss'))
  const dyn = asObj(pick(o, 'dynamic_exit', 'dynamicExit'))
  const exec = asObj(pick(o, 'execution', 'execution'))
  const extra: Record<string, unknown> = {}
  Object.keys(o).forEach((k) => {
    if (!KNOWN_TOP_KEYS.has(k)) extra[k] = o[k]
  })
  return {
    hold_to_expiry: asBool(pick(o, 'hold_to_expiry', 'holdToExpiry'), def.hold_to_expiry),
    tp_limit: {
      enabled: asBool(tp.enabled, def.tp_limit.enabled),
      price: asStr(tp.price, def.tp_limit.price),
      ratio: asStr(tp.ratio, def.tp_limit.ratio)
    },
    stop_loss: {
      enabled: asBool(sl.enabled, def.stop_loss.enabled),
      offset: asStr(sl.offset, def.stop_loss.offset),
      min_price: asStr(pick(sl, 'min_price', 'minPrice'), def.stop_loss.min_price),
      ratio: asStr(sl.ratio, def.stop_loss.ratio)
    },
    dynamic_exit: {
      enabled: asBool(dyn.enabled, def.dynamic_exit.enabled),
      min_diff_sigma_after_entry: asStr(pick(dyn, 'min_diff_sigma_after_entry', 'minDiffSigmaAfterEntry'), def.dynamic_exit.min_diff_sigma_after_entry),
      max_diff_retrace_pct: asStr(pick(dyn, 'max_diff_retrace_pct', 'maxDiffRetracePct'), def.dynamic_exit.max_diff_retrace_pct),
      min_model_prob_after_entry: asStr(pick(dyn, 'min_model_prob_after_entry', 'minModelProbAfterEntry'), def.dynamic_exit.min_model_prob_after_entry),
      min_odds_after_entry: asStr(pick(dyn, 'min_odds_after_entry', 'minOddsAfterEntry'), def.dynamic_exit.min_odds_after_entry),
      max_reverse_velocity_sigma: asStr(pick(dyn, 'max_reverse_velocity_sigma', 'maxReverseVelocitySigma'), def.dynamic_exit.max_reverse_velocity_sigma)
    },
    execution: {
      tp_slippage: asStr(pick(exec, 'tp_slippage', 'tpSlippage'), ''),
      stop_slippage: asStr(pick(exec, 'stop_slippage', 'stopSlippage'), ''),
      worst_price: asStr(pick(exec, 'worst_price', 'worstPrice'), '')
    },
    _extra: extra
  }
}

/** 预设状态序列化为 JSON 字符串（snake_case，与后端 toMap 一致） */
const serializePreset = (s: PresetState): string => {
  const execution: Record<string, string> = {}
  if (s.execution.tp_slippage !== '') execution.tp_slippage = s.execution.tp_slippage
  if (s.execution.stop_slippage !== '') execution.stop_slippage = s.execution.stop_slippage
  if (s.execution.worst_price !== '') execution.worst_price = s.execution.worst_price
  const out: Record<string, unknown> = {
    ...s._extra,
    hold_to_expiry: s.hold_to_expiry,
    tp_limit: { enabled: s.tp_limit.enabled, price: s.tp_limit.price, ratio: s.tp_limit.ratio },
    stop_loss: { enabled: s.stop_loss.enabled, offset: s.stop_loss.offset, min_price: s.stop_loss.min_price, ratio: s.stop_loss.ratio },
    dynamic_exit: {
      enabled: s.dynamic_exit.enabled,
      min_diff_sigma_after_entry: s.dynamic_exit.min_diff_sigma_after_entry,
      max_diff_retrace_pct: s.dynamic_exit.max_diff_retrace_pct,
      min_model_prob_after_entry: s.dynamic_exit.min_model_prob_after_entry,
      min_odds_after_entry: s.dynamic_exit.min_odds_after_entry,
      max_reverse_velocity_sigma: s.dynamic_exit.max_reverse_velocity_sigma
    },
    execution
  }
  return JSON.stringify(out)
}

interface Props {
  value?: string
  onChange?: (v: string) => void
  tier: Tier
}

const TailDiffExitPresetField: React.FC<Props> = ({ value, onChange, tier }) => {
  const { t } = useTranslation()
  const def = TIER_DEFAULTS[tier]
  const [mode, setMode] = useState<'visual' | 'json'>('visual')
  const [state, setState] = useState<PresetState>(def)
  const [parseError, setParseError] = useState(false)
  // 记录本组件最后一次向外 emit 的字符串，用于区分"外部重置"与"自身编辑"。
  // 空值未编辑时不 emit（表单值保持 ""，沿用该档代码默认）；一旦编辑则 emit 完整 JSON。
  const lastEmittedRef = useRef<string | undefined>(undefined)

  useEffect(() => {
    if (value === lastEmittedRef.current) return
    const parsed = parsePreset(value, def)
    if (parsed == null) {
      setParseError(true)
      setMode('json')
    } else {
      setParseError(false)
      setState(parsed)
    }
    lastEmittedRef.current = value
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value])

  const emit = (next: PresetState) => {
    setState(next)
    const str = serializePreset(next)
    lastEmittedRef.current = str
    onChange?.(str)
  }

  const onModeChange = (next: 'visual' | 'json') => {
    if (next === 'visual') {
      const parsed = parsePreset(value, def)
      if (parsed == null) {
        setParseError(true)
        return
      }
      setParseError(false)
      setState(parsed)
    }
    setMode(next)
  }

  const numCol = (
    label: string,
    val: string,
    on: (v: string) => void,
    opts?: { min?: number; max?: number; step?: number }
  ) => (
    <div>
      <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block' }}>{label}</Typography.Text>
      <InputNumber<string>
        stringMode
        value={val === '' ? undefined : val}
        onChange={(v) => on(v == null ? '' : String(v))}
        min={opts?.min == null ? undefined : String(opts.min)}
        max={opts?.max == null ? undefined : String(opts.max)}
        step={opts?.step == null ? undefined : String(opts.step)}
        style={{ width: 120 }}
      />
    </div>
  )

  return (
    <Space direction="vertical" size="small" style={{ width: '100%' }}>
      <Radio.Group value={mode} onChange={(e) => onModeChange(e.target.value)} size="small">
        <Radio.Button value="visual">{t('cryptoTailStrategy.form.exitEditor.visualMode')}</Radio.Button>
        <Radio.Button value="json">{t('cryptoTailStrategy.form.exitEditor.jsonMode')}</Radio.Button>
      </Radio.Group>

      {mode === 'json' ? (
        <Input.TextArea
          rows={4}
          value={value}
          onChange={(e) => {
            lastEmittedRef.current = e.target.value
            onChange?.(e.target.value)
          }}
          placeholder={t('cryptoTailStrategy.form.tailDiffExitPresetPlaceholder')}
          status={parseError ? 'error' : undefined}
        />
      ) : (
        <Space direction="vertical" size="small" style={{ width: '100%' }}>
          <Space>
            <Switch
              checked={state.hold_to_expiry}
              onChange={(v) => emit({ ...state, hold_to_expiry: v })}
            />
            <Typography.Text>{t('cryptoTailStrategy.form.exitEditor.holdToExpiry')}</Typography.Text>
          </Space>

          <Card
            size="small"
            title={
              <Space>
                <Switch
                  size="small"
                  checked={state.tp_limit.enabled}
                  onChange={(v) => emit({ ...state, tp_limit: { ...state.tp_limit, enabled: v } })}
                />
                {t('cryptoTailStrategy.form.exitEditor.tpLimit')}
              </Space>
            }
          >
            <Space wrap>
              {numCol(t('cryptoTailStrategy.form.exitEditor.tpPrice'), state.tp_limit.price, (v) => emit({ ...state, tp_limit: { ...state.tp_limit, price: v } }), { min: 0, max: 1, step: 0.01 })}
              {numCol(t('cryptoTailStrategy.form.exitEditor.tpRatio'), state.tp_limit.ratio, (v) => emit({ ...state, tp_limit: { ...state.tp_limit, ratio: v } }), { min: 0, max: 1, step: 0.05 })}
            </Space>
          </Card>

          <Card
            size="small"
            title={
              <Space>
                <Switch
                  size="small"
                  checked={state.stop_loss.enabled}
                  onChange={(v) => emit({ ...state, stop_loss: { ...state.stop_loss, enabled: v } })}
                />
                {t('cryptoTailStrategy.form.exitEditor.stopLoss')}
              </Space>
            }
          >
            <Space wrap>
              {numCol(t('cryptoTailStrategy.form.exitEditor.slOffset'), state.stop_loss.offset, (v) => emit({ ...state, stop_loss: { ...state.stop_loss, offset: v } }), { min: 0, max: 1, step: 0.01 })}
              {numCol(t('cryptoTailStrategy.form.exitEditor.slMinPrice'), state.stop_loss.min_price, (v) => emit({ ...state, stop_loss: { ...state.stop_loss, min_price: v } }), { min: 0, max: 1, step: 0.01 })}
              {numCol(t('cryptoTailStrategy.form.exitEditor.slRatio'), state.stop_loss.ratio, (v) => emit({ ...state, stop_loss: { ...state.stop_loss, ratio: v } }), { min: 0, max: 1, step: 0.05 })}
            </Space>
          </Card>

          <Card
            size="small"
            title={
              <Space>
                <Switch
                  size="small"
                  checked={state.dynamic_exit.enabled}
                  onChange={(v) => emit({ ...state, dynamic_exit: { ...state.dynamic_exit, enabled: v } })}
                />
                {t('cryptoTailStrategy.form.exitEditor.dynamicExit')}
              </Space>
            }
          >
            <Space wrap>
              {numCol(t('cryptoTailStrategy.form.exitEditor.dynMinDiffSigma'), state.dynamic_exit.min_diff_sigma_after_entry, (v) => emit({ ...state, dynamic_exit: { ...state.dynamic_exit, min_diff_sigma_after_entry: v } }), { min: 0, step: 0.1 })}
              {numCol(t('cryptoTailStrategy.form.exitEditor.dynMaxRetrace'), state.dynamic_exit.max_diff_retrace_pct, (v) => emit({ ...state, dynamic_exit: { ...state.dynamic_exit, max_diff_retrace_pct: v } }), { min: 0, max: 1, step: 0.05 })}
              {numCol(t('cryptoTailStrategy.form.exitEditor.dynMinModelProb'), state.dynamic_exit.min_model_prob_after_entry, (v) => emit({ ...state, dynamic_exit: { ...state.dynamic_exit, min_model_prob_after_entry: v } }), { min: 0, max: 1, step: 0.01 })}
              {numCol(t('cryptoTailStrategy.form.exitEditor.dynMinOdds'), state.dynamic_exit.min_odds_after_entry, (v) => emit({ ...state, dynamic_exit: { ...state.dynamic_exit, min_odds_after_entry: v } }), { min: 0, max: 1, step: 0.01 })}
              {numCol(t('cryptoTailStrategy.form.exitEditor.dynMaxReverseVel'), state.dynamic_exit.max_reverse_velocity_sigma, (v) => emit({ ...state, dynamic_exit: { ...state.dynamic_exit, max_reverse_velocity_sigma: v } }), { min: 0, step: 0.05 })}
            </Space>
          </Card>

          <Card size="small" title={t('cryptoTailStrategy.form.exitEditor.execution')}>
            <Space wrap>
              {numCol(t('cryptoTailStrategy.form.exitEditor.tpSlippage'), state.execution.tp_slippage, (v) => emit({ ...state, execution: { ...state.execution, tp_slippage: v } }), { min: 0, step: 0.005 })}
              {numCol(t('cryptoTailStrategy.form.exitEditor.stopSlippage'), state.execution.stop_slippage, (v) => emit({ ...state, execution: { ...state.execution, stop_slippage: v } }), { min: 0, step: 0.005 })}
              {numCol(t('cryptoTailStrategy.form.exitEditor.worstPrice'), state.execution.worst_price, (v) => emit({ ...state, execution: { ...state.execution, worst_price: v } }), { min: 0, max: 1, step: 0.01 })}
            </Space>
            <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block', marginTop: 4 }}>
              {t('cryptoTailStrategy.form.exitEditor.executionHint')}
            </Typography.Text>
          </Card>
        </Space>
      )}
    </Space>
  )
}

export default TailDiffExitPresetField
