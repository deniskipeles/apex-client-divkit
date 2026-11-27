import { DivCustom } from '@divkitframework/jsonbuilder'
import type { DivNode } from '../kit/div.ts'
import { palette } from '../kit/tokens.ts'

export interface ChartOptions {
  id?: string
  chartType?: 'bar' | 'line' | 'area' | 'donut' | 'pie'
  /** Alias for `chartType`, kept for the generators written against the first kit. */
  kind?: 'bar' | 'line' | 'area' | 'donut' | 'pie'
  title?: string
  subtitle?: string
  dataVar?: string
  /** Alias for `dataVar`. */
  data?: string
  labelField?: string
  valueField?: string
  valuePrefix?: string
  valueSuffix?: string
  accentColor?: string
  height?: number
  selectionVar?: string
  points?: Array<{ label: string; value: number; category?: string }>
}

/**
 * `sdui_chart` custom div. Prop names are the ones `DivChartParser` and the
 * browser `chart.ts` read; the runtime owns the drawing.
 */
export const chart = (opts: ChartOptions): DivNode => {
  const props: Record<string, unknown> = {
    chart_type: opts.chartType ?? opts.kind ?? 'bar',
    title: opts.title ?? 'POS Analytics Chart',
    subtitle: opts.subtitle ?? '',
    label_field: opts.labelField ?? 'label',
    value_field: opts.valueField ?? 'value',
    value_prefix: opts.valuePrefix ?? '',
    value_suffix: opts.valueSuffix ?? '',
    accent_hex: opts.accentColor ?? palette.info,
    height: opts.height ?? 200,
    show_grid: true,
    show_legend: true,
    interactive: true,
  }
  const dataVar = opts.dataVar ?? opts.data
  if (dataVar) props.data_var = dataVar
  if (opts.selectionVar) props.selection_var = opts.selectionVar
  if (opts.points) props.points = opts.points

  const node = new DivCustom({ custom_type: 'sdui_chart', custom_props: props })
  if (opts.id) (node as unknown as { id: string }).id = opts.id
  return JSON.parse(JSON.stringify(node)) as DivNode
}
