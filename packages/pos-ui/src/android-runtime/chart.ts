import { toNumber, type DivVars, type RowContext } from './values.ts'

/**
 * Port of `engine/ui/DivChartSpec.kt`.
 *
 * Points are aggregated by label (repeated SKUs sum), a `price` series is
 * multiplied by `qty`, and an empty `data_var` falls back to six fixed demo
 * points. Those fallbacks are part of the rendered output, so they must match.
 */

export type ChartType = 'bar' | 'line' | 'area' | 'donut' | 'pie'

export interface ChartPoint {
  id: string
  label: string
  value: number
  category: string
  colorHex: string
}

export interface ChartSpec {
  id: string
  chartType: ChartType
  title: string
  subtitle: string
  dataVar: string | null
  labelField: string
  valueField: string
  valuePrefix: string
  valueSuffix: string
  accentHex: string
  heightDp: number
  allowTypeSwitch: boolean
  selectionVar: string
  points: ChartPoint[]
}

const PALETTE = ['#0284C7', '#10B981', '#6366F1', '#F59E0B', '#EC4899', '#8B5CF6', '#14B8A6', '#F97316']

const CHART_TYPES = new Set([
  'sdui_chart',
  'chart',
  'bar_chart',
  'line_chart',
  'area_chart',
  'donut_chart',
  'pie_chart',
])

export function isChartType(value: string | undefined | null): boolean {
  return CHART_TYPES.has((value ?? '').trim().toLowerCase())
}

function chartTypeFromWire(raw: string): ChartType {
  switch (raw.trim().toLowerCase()) {
    case 'line':
    case 'line_chart':
      return 'line'
    case 'area':
    case 'area_chart':
    case 'spline':
      return 'area'
    case 'donut':
    case 'donut_chart':
    case 'doughnut':
      return 'donut'
    case 'pie':
    case 'pie_chart':
      return 'pie'
    default:
      return 'bar'
  }
}

export function parseChart(node: Record<string, unknown>, vars: DivVars, row: RowContext = {}): ChartSpec {
  const customType = str(node.custom_type) ?? str(node.type) ?? 'sdui_chart'
  const props = (node.custom_props as Record<string, unknown>) ?? node
  const rawChartType = str(props.chart_type) ?? customType.replace(/_chart$/, '')
  const chartType = chartTypeFromWire(rawChartType)

  const title = str(props.title) ?? 'POS Analytics Chart'
  const subtitle =
    str(props.subtitle) ?? 'KMP SDUI Chart • Native Android (Vico/Compose) & iOS (Swift Charts/DGCharts)'
  const dataVar = str(props.data_var)
  const labelField = str(props.label_field) ?? 'label'
  const valueField = str(props.value_field) ?? 'value'
  const valuePrefix = str(props.value_prefix) ?? ''
  const valueSuffix = str(props.value_suffix) ?? ''
  const accentHex = str(props.accent_hex) ?? '#0284C7'
  const heightDp = Math.min(360, Math.max(140, int(props.height) ?? 210))
  const allowTypeSwitch = props.allow_type_switch === undefined ? true : props.allow_type_switch !== false
  const selectionVar = (str(props.selection_var)?.startsWith('user.') ? str(props.selection_var) : null) ?? 'user.selected_chart_point'
  const chartId =
    str(node.id) ??
    str(props.id) ??
    `chart_${title
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '_')}`

  const reactive = dataVar ? pointsFromVariable(vars[dataVar], labelField, valueField) : []
  const inline = reactive.length > 0 ? reactive : pointsFromInline(props, labelField, valueField)

  return {
    id: chartId,
    chartType,
    // The device reads these as literal props; it does not run expressions on them.
    title,
    subtitle,
    dataVar,
    labelField,
    valueField,
    valuePrefix,
    valueSuffix,
    accentHex,
    heightDp,
    allowTypeSwitch,
    selectionVar,
    points: inline,
  }
}

function pointsFromVariable(value: unknown, labelField: string, valueField: string): ChartPoint[] {
  if (typeof value !== 'string' || value.trim().length === 0) return []
  try {
    const parsed = JSON.parse(value)
    return Array.isArray(parsed) ? aggregate(parsed, labelField, valueField) : []
  } catch {
    return []
  }
}

function pointsFromInline(
  props: Record<string, unknown>,
  labelField: string,
  valueField: string,
): ChartPoint[] {
  const inline = props.points ?? props.series
  if (Array.isArray(inline)) return aggregate(inline, labelField, valueField)
  return [
    { id: 'p1', label: '09:00', value: 420, category: '', colorHex: '#0284C7' },
    { id: 'p2', label: '11:00', value: 680, category: '', colorHex: '#10B981' },
    { id: 'p3', label: '13:00', value: 950, category: '', colorHex: '#6366F1' },
    { id: 'p4', label: '15:00', value: 610, category: '', colorHex: '#F59E0B' },
    { id: 'p5', label: '17:00', value: 1120, category: '', colorHex: '#EC4899' },
    { id: 'p6', label: '19:00', value: 890, category: '', colorHex: '#8B5CF6' },
  ]
}

function aggregate(rows: unknown[], labelField: string, valueField: string): ChartPoint[] {
  const totals = new Map<string, number>()
  const colors = new Map<string, string>()
  const categories = new Map<string, string>()

  rows.forEach((row, index) => {
    if (!row || typeof row !== 'object') return
    const obj = row as Record<string, unknown>
    const label =
      str(obj[labelField]) ??
      str(obj.label) ??
      str(obj.item_name) ??
      str(obj.name) ??
      str(obj.sku) ??
      `Item ${index + 1}`

    const multiplier =
      valueField === 'price' && 'qty' in obj ? Math.max(1, toNumber(obj.qty)) : 1
    const base =
      numberOrNull(obj[valueField]) ??
      numberOrNull(obj.value) ??
      numberOrNull(obj.price) ??
      numberOrNull(obj.stock) ??
      0
    totals.set(label, (totals.get(label) ?? 0) + base * multiplier)

    const customColor = str(obj.color_hex) ?? str(obj.color)
    if (customColor && !colors.has(label)) colors.set(label, customColor)
    const category = str(obj.category) ?? str(obj.status)
    if (category && !categories.has(label)) categories.set(label, category)
  })

  return [...totals.entries()].map(([label, value], index) => ({
    id: `pt_${index}`,
    label,
    value,
    category: categories.get(label) ?? '',
    colorHex: colors.get(label) ?? PALETTE[index % PALETTE.length],
  }))
}

function str(value: unknown): string | null {
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  return null
}

function int(value: unknown): number | null {
  if (typeof value === 'number' && Number.isFinite(value)) return Math.trunc(value)
  if (typeof value === 'string' && /^-?\d+$/.test(value.trim())) return Number(value)
  return null
}

function numberOrNull(value: unknown): number | null {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value === 'string' && value.trim() !== '' && Number.isFinite(Number(value))) return Number(value)
  return null
}
