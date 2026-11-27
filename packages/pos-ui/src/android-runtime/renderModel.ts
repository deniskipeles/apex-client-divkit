import { toRenderActions, type RenderAction } from './actions.ts'
import { isChartType, parseChart, type ChartSpec } from './chart.ts'
import { evalExpr } from './expression.ts'
import { isValidVariableName } from './scope.ts'
import {
  display,
  parseRows,
  toNumber,
  type DivValue,
  type DivVars,
  type RowContext,
} from './values.ts'

/**
 * Pure render tree of the Android SDUI renderer.
 *
 * `DivKitMultiplatform.RenderDivNode` is a Compose function, so the preview
 * cannot call it; this module is the same dispatch expressed as data. It is the
 * artifact a conformance fixture compares: types, resolved props, children and
 * the actions each node carries. Anything this function drops is dropped on the
 * device too — an unknown type renders nothing, it does not get a placeholder.
 */

export type Visibility = 'visible' | 'invisible' | 'gone'

export interface Spacing {
  left: number
  top: number
  right: number
  bottom: number
}

export type SizeSpec =
  | { type: 'fixed'; value: number }
  | { type: 'match_parent' }
  | { type: 'wrap_content' }
  | { type: 'weighted'; weight: number }

export interface BoxStyle {
  background?: string
  border?: { color: string; width: number; radius: number }
  radius: number
  padding?: Spacing
  margin?: Spacing
  width?: SizeSpec
  height?: SizeSpec
  weight?: number
}

interface NodeBase {
  key: string
  id?: string
  visibility: Visibility
  box: BoxStyle
  actions: RenderAction[]
}

export interface NothingNode extends NodeBase {
  kind: 'nothing'
  type: string
}

export interface ContainerNode extends NodeBase {
  kind: 'container'
  layout: 'vertical' | 'horizontal'
  spacing: number
  children: RenderNode[]
}

export interface GalleryNode extends NodeBase {
  kind: 'gallery'
  spacing: number
  children: RenderNode[]
}

export interface GridNode extends NodeBase {
  kind: 'grid'
  columnCount: number
  spacing: number
  children: RenderNode[]
}

export interface TextNode extends NodeBase {
  kind: 'text'
  text: string
  fontSize: number
  fontWeight: 'light' | 'regular' | 'medium' | 'bold'
  color: string
  align: 'left' | 'center' | 'right'
  monospace: boolean
  maxLines?: number
}

export interface ButtonNode extends NodeBase {
  kind: 'button'
  text: string
  subtitle?: string
  background: string
  textColor: string
  radius: number
  icon?: string
  align: 'left' | 'center'
  fontSize: number
}

export interface InputNode extends NodeBase {
  kind: 'input'
  variable: string
  hint: string
  value: string
}

export interface SeparatorNode extends NodeBase {
  kind: 'separator'
}

export interface ImageNode extends NodeBase {
  kind: 'image'
  source: string
  bitmap: boolean
  size: number
  tint?: string
  align: 'left' | 'center' | 'right'
  contentDescription?: string
}

export interface JsonListNode extends NodeBase {
  kind: 'json_list'
  dataVar: string
  emptyText: string
  rows: RenderNode[]
}

export interface ChartNode extends NodeBase {
  kind: 'chart'
  spec: ChartSpec
}

export interface BlockNode extends NodeBase {
  kind: 'block'
  block: BlockProps
}

export type BlockProps =
  | { type: 'sticky_footer'; body: RenderNode[]; footer: RenderNode[] }
  | { type: 'segmented' | 'bottom_tabs'; items: SegmentItem[]; activeValue: string }
  | { type: 'progress_ring'; fraction: number; label: string; caption: string }
  | { type: 'text_field'; label: string; variable: string; placeholder: string }
  | { type: 'scan_chips'; chunks: string[]; expected: number }
  | { type: 'qr_controls'; index: number; count: number; prev: RenderAction[]; next: RenderAction[] }
  | { type: 'fab'; label: string; actions: RenderAction[] }
  | { type: 'toast'; text: string }
  | {
      type: 'form_modal' | 'bottom_sheet'
      visible: boolean
      title: string
      body: RenderNode[]
      primaryLabel?: string
      primaryActions: RenderAction[]
      secondaryLabel?: string
      secondaryActions: RenderAction[]
      dismissActions: RenderAction[]
    }
  | { type: 'camera_viewfinder'; hint: string }
  | { type: 'inline_banner'; text: string; tone: string }

export interface SegmentItem {
  value: string
  label: string
  actions: RenderAction[]
}

export type RenderNode =
  | NothingNode
  | ContainerNode
  | GalleryNode
  | GridNode
  | TextNode
  | ButtonNode
  | InputNode
  | SeparatorNode
  | ImageNode
  | JsonListNode
  | ChartNode
  | BlockNode

const BLOCK_TYPES = new Set([
  'sticky_footer',
  'segmented',
  'bottom_tabs',
  'progress_ring',
  'text_field',
  'scan_chips',
  'qr_controls',
  'fab',
  'toast',
  'form_modal',
  'bottom_sheet',
  'camera_viewfinder',
  'inline_banner',
])

/** One card document → its first state's div. */
export function cardRoot(card: unknown): Record<string, unknown> | null {
  if (!card || typeof card !== 'object') return null
  const root = card as Record<string, unknown>
  const cardObj = (root.card as Record<string, unknown>) ?? root
  const states = cardObj.states
  if (Array.isArray(states) && states.length > 0) {
    const first = states[0] as Record<string, unknown>
    const div = first?.div
    if (div && typeof div === 'object') return div as Record<string, unknown>
  }
  const div = cardObj.div
  return div && typeof div === 'object' ? (div as Record<string, unknown>) : cardObj
}

export function buildCardModel(card: unknown, vars: DivVars): RenderNode | null {
  const root = cardRoot(card)
  if (!root) return null
  const node = buildNode(root, vars, {}, '0')
  return node.kind === 'nothing' ? null : node
}

function buildNode(
  node: Record<string, unknown>,
  vars: DivVars,
  row: RowContext,
  path: string,
): RenderNode {
  const rawType = typeof node.type === 'string' ? node.type : 'container'
  const visibility = resolveVisibility(node, vars, row)
  const base: NodeBase = {
    key: path,
    id: typeof node.id === 'string' ? node.id : undefined,
    visibility,
    box: boxStyle(node, vars, row),
    actions: toRenderActions(node, row),
  }

  if (visibility === 'gone') return { ...base, kind: 'nothing', type: rawType }

  switch (rawType) {
    case 'container': {
      const orientation = str(node.orientation) ?? 'vertical'
      return {
        ...base,
        kind: 'container',
        layout: orientation === 'horizontal' ? 'horizontal' : 'vertical',
        spacing: int(node.item_spacing) ?? 8,
        children: children(node.items, vars, row, path),
      }
    }
    case 'gallery':
      return {
        ...base,
        kind: 'gallery',
        spacing: int(node.item_spacing) ?? 10,
        children: children(node.items, vars, row, path),
      }
    case 'grid':
      return {
        ...base,
        kind: 'grid',
        columnCount: clamp(int(node.column_count) ?? 2, 1, 4),
        spacing: int(node.item_spacing) ?? 10,
        children: children(node.items, vars, row, path),
      }
    case 'text':
      return { ...base, kind: 'text', ...textProps(node, vars, row) }
    case 'button':
      return { ...base, kind: 'button', ...buttonProps(node, vars, row) }
    case 'input': {
      const variable = str(node.text_variable) ?? 'user.input_text'
      return {
        ...base,
        kind: 'input',
        variable,
        hint: str(node.hint_text) ?? 'Enter value...',
        value: display(vars[variable] ?? ''),
      }
    }
    case 'separator':
      return { ...base, kind: 'separator' }
    case 'image': {
      const source = str(node.image_url) ?? str(node.icon) ?? str((node.custom_props as Record<string, unknown>)?.icon)
      const resolved = source ? evalExpr(source, vars, row) : ''
      if (!resolved) return { ...base, kind: 'nothing', type: rawType }
      return {
        ...base,
        kind: 'image',
        source: resolved,
        bitmap: /^(file:|content:|data:)/.test(resolved),
        size: int((node.width as Record<string, unknown>)?.value) ?? int((node.height as Record<string, unknown>)?.value) ?? int(node.size) ?? 24,
        tint: str(node.tint) ?? str(node.text_color) ?? undefined,
        align: alignOf(str(node.alignment_horizontal), ['left', 'start'], ['right', 'end']),
        contentDescription: str(node.content_description) ?? resolved,
      }
    }
    case 'json_list':
      return { ...base, kind: 'json_list', ...jsonListProps(node, vars, row, path) }
    default:
      break
  }

  const customType = str(node.custom_type) ?? rawType
  if (isChartType(customType) || isChartType(rawType)) {
    return { ...base, kind: 'chart', spec: parseChart(node, vars, row) }
  }
  if (customType === 'json_list') {
    return { ...base, kind: 'json_list', ...jsonListProps(node, vars, row, path) }
  }
  if (BLOCK_TYPES.has(rawType)) {
    return { ...base, kind: 'block', block: blockProps(rawType, node, vars, row, path) }
  }
  // Unknown type, or a `custom` block Android does not implement (sdui_signature,
  // sdui_numpad, plugin types): the device renders nothing.
  return { ...base, kind: 'nothing', type: rawType }
}

function jsonListProps(
  node: Record<string, unknown>,
  vars: DivVars,
  row: RowContext,
  path: string,
): Omit<JsonListNode, keyof NodeBase | 'kind'> {
  const props = (node.custom_props as Record<string, unknown>) ?? node
  const dataVar = str(props.data_var) ?? 'user.items'
  const emptyText = str(props.empty_text) ?? 'No records'
  const rowCard = props.row_card as Record<string, unknown> | undefined
  const rows = parseRows(vars[dataVar])
  return {
    dataVar,
    emptyText,
    rows: rowCard
      ? rows
          .map((item, index) => buildNode(rowCard, vars, item, `${path}/row${index}`))
          .filter((node) => node.visibility !== 'gone')
      : [],
  }
}

function textProps(node: Record<string, unknown>, vars: DivVars, row: RowContext) {
  const weight = str(node.font_weight) ?? 'regular'
  return {
    text: evalExpr(str(node.text) ?? '', vars, row),
    fontSize: int(node.font_size) ?? 14,
    fontWeight: weight === 'bold' ? ('bold' as const) : weight === 'medium' || weight === 'semibold' ? ('medium' as const) : weight === 'light' ? ('light' as const) : ('regular' as const),
    color: evalExpr(str(node.text_color) ?? '#F8FAFC', vars, row),
    align: alignOf(str(node.alignment_horizontal), ['center', 'middle'], ['right', 'end']),
    monospace: str(node.font_family) === 'monospace',
    maxLines: int(node.max_lines) ?? undefined,
  }
}

function buttonProps(node: Record<string, unknown>, vars: DivVars, row: RowContext) {
  const background = evalExpr(str(node.background_color) ?? '#2563EB', vars, row)
  return {
    text: evalExpr(str(node.text) ?? 'Action', vars, row),
    subtitle: node.subtitle === undefined ? undefined : evalExpr(str(node.subtitle) ?? '', vars, row),
    background,
    textColor: evalExpr(str(node.text_color) ?? '#FFFFFF', vars, row),
    radius: int(node.corner_radius) ?? 10,
    icon: str(node.icon) ?? undefined,
    align: ['left', 'start'].includes(str(node.text_alignment_horizontal) ?? '') ? ('left' as const) : ('center' as const),
    fontSize: int(node.font_size) ?? 14,
  }
}

function blockProps(
  type: string,
  node: Record<string, unknown>,
  vars: DivVars,
  row: RowContext,
  path: string,
): BlockProps {
  const props = (node.custom_props as Record<string, unknown>) ?? node
  const text = (key: string, fallback = '') => evalExpr(str(props[key]) ?? fallback, vars, row)

  switch (type) {
    case 'sticky_footer':
      return {
        type: 'sticky_footer',
        body: children(props.body, vars, row, `${path}/body`),
        footer: children(props.footer, vars, row, `${path}/footer`),
      }
    case 'segmented':
    case 'bottom_tabs':
      return {
        type,
        items: segmentItems(props.items, row),
        activeValue: text('active_value'),
      }
    case 'progress_ring':
      return {
        type: 'progress_ring',
        fraction: toNumber(text('value', '0')),
        label: text('label'),
        caption: text('caption'),
      }
    case 'text_field':
      return {
        type: 'text_field',
        label: text('label'),
        variable: text('variable'),
        placeholder: text('placeholder'),
      }
    case 'scan_chips': {
      const chunksVar = text('chunks_var', 'chunks')
      const chunks = parseRows(vars[chunksVar] ?? null).map((item) => display(item.chunk ?? ''))
      return { type: 'scan_chips', chunks, expected: Math.trunc(toNumber(text('expected', '0'))) }
    }
    case 'qr_controls':
      return {
        type: 'qr_controls',
        index: Math.trunc(toNumber(text('index', '0'))),
        count: Math.trunc(toNumber(text('count', '0'))),
        prev: toRenderActions((props.prev as Record<string, unknown>) ?? {}, row),
        next: toRenderActions((props.next as Record<string, unknown>) ?? {}, row),
      }
    case 'fab':
      return { type: 'fab', label: text('label'), actions: toRenderActions(props, row) }
    case 'toast':
      return { type: 'toast', text: text('text') }
    case 'camera_viewfinder':
      return { type: 'camera_viewfinder', hint: text('hint') }
    case 'inline_banner':
      return { type: 'inline_banner', text: text('text'), tone: text('tone', 'info') }
    case 'form_modal':
    case 'bottom_sheet': {
      const primary = props.primary as Record<string, unknown> | undefined
      const secondary = props.secondary as Record<string, unknown> | undefined
      const dismiss = props.dismiss as Record<string, unknown> | undefined
      return {
        type,
        // Kotlin `flag()`: only the literal "true" is true.
        visible: text('visible', 'false') === 'true',
        title: text('title'),
        body: children(props.body, vars, row, `${path}/body`),
        primaryLabel: text('primary_label') || undefined,
        primaryActions: toRenderActions(primary ?? {}, row),
        secondaryLabel: text('secondary_label') || undefined,
        secondaryActions: toRenderActions(secondary ?? {}, row),
        dismissActions: toRenderActions(dismiss ?? {}, row),
      }
    }
    default:
      return { type: 'toast', text: '' }
  }
}

function segmentItems(raw: unknown, row: RowContext): SegmentItem[] {
  if (!Array.isArray(raw)) return []
  return raw
    .filter((item): item is Record<string, unknown> => !!item && typeof item === 'object')
    .map((item) => ({
      value: str(item.value) ?? '',
      label: str(item.label) ?? str(item.value) ?? '',
      actions: toRenderActions(item, row),
    }))
}

function children(raw: unknown, vars: DivVars, row: RowContext, path: string): RenderNode[] {
  if (!Array.isArray(raw)) return []
  const out: RenderNode[] = []
  raw.forEach((child, index) => {
    if (!child || typeof child !== 'object' || Array.isArray(child)) return
    const node = buildNode(child as Record<string, unknown>, vars, row, `${path}/${index}`)
    // `gone` removes the node from layout, so it is not part of the children list.
    if (node.visibility !== 'gone') out.push(node)
  })
  return out
}

function resolveVisibility(node: Record<string, unknown>, vars: DivVars, row: RowContext): Visibility {
  const raw = str(node.visibility)
  if (!raw) return 'visible'
  const resolved = evalExpr(raw, vars, row)
  return resolved === 'gone' || resolved === 'invisible' ? resolved : 'visible'
}

function boxStyle(node: Record<string, unknown>, vars: DivVars, row: RowContext): BoxStyle {
  const backgroundNode = node.background
  const arrayColor = Array.isArray(backgroundNode)
    ? str((backgroundNode[0] as Record<string, unknown>)?.color)
    : undefined
  const rawBackground = str(node.background_color) ?? arrayColor
  const borderNode = (node.border as Record<string, unknown>) ?? undefined
  const stroke = (borderNode?.stroke as Record<string, unknown>) ?? undefined
  const radius = int(borderNode?.corner_radius) ?? int(node.corner_radius) ?? 0

  return {
    background: rawBackground ? evalExpr(rawBackground, vars, row) : undefined,
    border: stroke
      ? {
          color: evalExpr(str(stroke.color) ?? '#000000', vars, row),
          width: int(stroke.width) ?? 1,
          radius,
        }
      : undefined,
    radius,
    padding: spacing(node.paddings),
    margin: spacing(node.margins),
    width: size(node.width),
    height: size(node.height),
    weight: numberOrNull(node.weight) ?? undefined,
  }
}

function spacing(raw: unknown): Spacing | undefined {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return undefined
  const obj = raw as Record<string, unknown>
  return {
    left: int(obj.left) ?? 0,
    top: int(obj.top) ?? 0,
    right: int(obj.right) ?? 0,
    bottom: int(obj.bottom) ?? 0,
  }
}

function size(raw: unknown): SizeSpec | undefined {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return undefined
  const obj = raw as Record<string, unknown>
  const type = str(obj.type)
  if (type === 'fixed') return { type: 'fixed', value: int(obj.value) ?? 0 }
  if (type === 'weighted') return { type: 'weighted', weight: numberOrNull(obj.weight) ?? 1 }
  if (type === 'match_parent') return { type: 'match_parent' }
  if (type === 'wrap_content') return { type: 'wrap_content' }
  return undefined
}

/** Card variable declarations, coerced like the device's initial-variable batch. */
export function seedVariables(card: unknown, vars: DivVars): DivVars {
  const root = (card as Record<string, unknown>)?.card as Record<string, unknown> | undefined
  const declarations = root?.variables
  if (!Array.isArray(declarations)) return vars
  const next = { ...vars }
  for (const declaration of declarations) {
    if (!declaration || typeof declaration !== 'object') continue
    const obj = declaration as Record<string, unknown>
    const name = str(obj.name)
    if (!name || !isValidVariableName(name) || name in next) continue
    next[name] = coerceDeclared(obj.type, obj.value)
  }
  return next
}

function coerceDeclared(type: unknown, value: unknown): DivValue {
  switch (str(type) ?? 'string') {
    case 'integer':
      return Math.trunc(toNumber(value))
    case 'number':
      return toNumber(value)
    case 'boolean':
      return value === true || value === 'true'
    case 'array':
      return Array.isArray(value) ? value.map((item) => (item && typeof item === 'object' ? JSON.stringify(item) : display(item))) : []
    default:
      return typeof value === 'string' ? value : display(value)
  }
}

function alignOf(
  raw: string | null,
  [centerA, centerB]: [string, string],
  [rightA, rightB]: [string, string],
): 'left' | 'center' | 'right' {
  if (raw === centerA || raw === centerB) return 'center'
  if (raw === rightA || raw === rightB) return 'right'
  return 'left'
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

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}
