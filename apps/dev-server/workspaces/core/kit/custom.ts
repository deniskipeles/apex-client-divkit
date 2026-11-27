import type { DivNode, DivSize } from './div.ts'
import { palette, radius, space, type as ramp } from './tokens.ts'
import type { DivAction } from './actions.ts'

/**
 * The Android-native block vocabulary.
 *
 * Every builder here emits the exact JSON `DivKitMultiplatform.kt` / `SduiLayoutBlocks.kt` read:
 * layout blocks keep their props under `custom_props` (Android's `SduiLayoutBlocks.props` prefers
 * it), while `json_list` keeps them at the node root like the hand-authored demo cards.
 *
 * The `sdui_chart` builder lives in `../divkit/chart.ts` (it is a `DivCustom` from
 * `@divkitframework/jsonbuilder`), so this file does not duplicate it.
 */

export interface JsonListOptions {
  id?: string
  dataVar?: string
  /** Alias for `dataVar`. */
  data?: string
  emptyText?: string
  rowCard?: DivNode
  /** Build a two-line row card from these record keys when `rowCard` is absent. */
  titleKey?: string
  subtitleKey?: string
}

export const jsonList = (opts: JsonListOptions): DivNode => {
  const dataVar = opts.dataVar ?? opts.data
  if (!dataVar) throw new Error('jsonList: dataVar (or data) is required')
  const node: DivNode = {
    type: 'json_list',
    data_var: dataVar,
    empty_text: opts.emptyText ?? 'No records',
    row_card: opts.rowCard ?? defaultRowCard(opts.titleKey ?? 'label', opts.subtitleKey),
  }
  if (opts.id) node.id = opts.id
  return node
}

/** Minimal record row: the title plus an optional second line. */
const defaultRowCard = (titleKey: string, subtitleKey?: string): DivNode => ({
  type: 'container',
  orientation: 'horizontal',
  item_spacing: space(2),
  paddings: { left: space(3), top: space(2), right: space(3), bottom: space(2) },
  items: [
    {
      type: 'text',
      text: `@{item.${titleKey}}`,
      font_size: ramp.body,
      font_weight: 'bold',
      text_color: palette.text,
    },
    ...(subtitleKey
      ? [
          {
            type: 'text' as const,
            text: `@{item.${subtitleKey}}`,
            font_size: ramp.small,
            text_color: palette.textMuted,
          },
        ]
      : []),
  ],
})

/** Web/desktop custom block: signature pad bound to a variable. */
export const signature = (opts: { variable: string; label?: string; id?: string }): DivNode => {
  const node: DivNode = {
    type: 'custom',
    custom_type: 'sdui_signature',
    custom_props: { variable: opts.variable, label: opts.label ?? 'Sign here' },
  }
  if (opts.id) node.id = opts.id
  return node
}

/** Web/desktop custom block: numeric keypad writing into a variable. */
export const numpad = (opts: { variable: string; label?: string; id?: string }): DivNode => {
  const node: DivNode = {
    type: 'custom',
    custom_type: 'sdui_numpad',
    custom_props: { variable: opts.variable, label: opts.label ?? 'Amount' },
  }
  if (opts.id) node.id = opts.id
  return node
}

export interface StickyFooterOptions {
  body: DivNode[]
  footer: DivNode[]
  id?: string
}

export const stickyFooter = (opts: StickyFooterOptions): DivNode => {
  const node: DivNode = {
    type: 'sticky_footer',
    custom_props: { body: opts.body, footer: opts.footer },
  }
  if (opts.id) node.id = opts.id
  return node
}

export interface SegmentItem {
  value: string
  label?: string
  actions?: DivAction[]
}

export const segmented = (opts: { items: SegmentItem[]; activeValue?: string; id?: string }): DivNode => {
  const node: DivNode = {
    type: 'segmented',
    custom_props: { items: opts.items, active_value: opts.activeValue ?? opts.items[0]?.value ?? '' },
  }
  if (opts.id) node.id = opts.id
  return node
}

export const bottomTabs = (opts: { items: SegmentItem[]; activeValue?: string; id?: string }): DivNode => {
  const node: DivNode = {
    type: 'bottom_tabs',
    custom_props: { items: opts.items, active_value: opts.activeValue ?? opts.items[0]?.value ?? '' },
  }
  if (opts.id) node.id = opts.id
  return node
}

export const progressRing = (opts: {
  value: string | number
  label?: string
  caption?: string
  id?: string
}): DivNode => {
  const node: DivNode = {
    type: 'progress_ring',
    custom_props: { value: opts.value, label: opts.label ?? '', caption: opts.caption ?? '' },
  }
  if (opts.id) node.id = opts.id
  return node
}

export const textField = (opts: {
  label: string
  variable: string
  placeholder?: string
  id?: string
}): DivNode => {
  const node: DivNode = {
    type: 'text_field',
    custom_props: { label: opts.label, variable: opts.variable, placeholder: opts.placeholder ?? '' },
  }
  if (opts.id) node.id = opts.id
  return node
}

export const scanChips = (opts: { chunksVar?: string; expected: number; id?: string }): DivNode => {
  const node: DivNode = {
    type: 'scan_chips',
    custom_props: { chunks_var: opts.chunksVar ?? 'user.chunks', expected: opts.expected },
  }
  if (opts.id) node.id = opts.id
  return node
}

export const qrControls = (opts: {
  index: string | number
  count: string | number
  prev?: DivAction
  next?: DivAction
  id?: string
}): DivNode => {
  const node: DivNode = {
    type: 'qr_controls',
    custom_props: {
      index: opts.index,
      count: opts.count,
      ...(opts.prev ? { prev: { actions: [opts.prev] } } : {}),
      ...(opts.next ? { next: { actions: [opts.next] } } : {}),
    },
  }
  if (opts.id) node.id = opts.id
  return node
}

export const fab = (opts: { label: string; actions: DivAction[]; id?: string }): DivNode => {
  const node: DivNode = { type: 'fab', custom_props: { label: opts.label, actions: opts.actions } }
  if (opts.id) node.id = opts.id
  return node
}

export const toast = (opts: { text: string; id?: string }): DivNode => {
  const node: DivNode = { type: 'toast', custom_props: { text: opts.text } }
  if (opts.id) node.id = opts.id
  return node
}

export const formModal = (opts: {
  visible: boolean | string
  title: string
  body: DivNode[]
  primaryLabel?: string
  primary?: DivAction
  secondaryLabel?: string
  secondary?: DivAction
  id?: string
}): DivNode => {
  const props: Record<string, unknown> = {
    visible: opts.visible,
    title: opts.title,
    body: opts.body,
  }
  if (opts.primaryLabel) props.primary_label = opts.primaryLabel
  if (opts.primary) props.primary = { actions: [opts.primary] }
  if (opts.secondaryLabel) props.secondary_label = opts.secondaryLabel
  if (opts.secondary) props.secondary = { actions: [opts.secondary] }
  const node: DivNode = { type: 'form_modal', custom_props: props }
  if (opts.id) node.id = opts.id
  return node
}

export const bottomSheet = (opts: {
  visible: boolean | string
  title: string
  body: DivNode[]
  dismiss?: DivAction
  id?: string
}): DivNode => {
  const props: Record<string, unknown> = { visible: opts.visible, title: opts.title, body: opts.body }
  if (opts.dismiss) props.dismiss = { actions: [opts.dismiss] }
  const node: DivNode = { type: 'bottom_sheet', custom_props: props }
  if (opts.id) node.id = opts.id
  return node
}

export const cameraViewfinder = (opts: { hint: string; id?: string }): DivNode => {
  const node: DivNode = { type: 'camera_viewfinder', custom_props: { hint: opts.hint } }
  if (opts.id) node.id = opts.id
  return node
}

export const inlineBanner = (opts: {
  text: string
  tone?: 'info' | 'success' | 'warn' | 'error'
  id?: string
}): DivNode => {
  const node: DivNode = { type: 'inline_banner', custom_props: { text: opts.text, tone: opts.tone ?? 'info' } }
  if (opts.id) node.id = opts.id
  return node
}

/** Shared "metric tile" used by generated dashboards. */
export const metric = (opts: {
  label: string
  value: string
  tone?: string
  width?: DivSize
  id?: string
}): DivNode => {
  const node: DivNode = {
    type: 'container',
    orientation: 'vertical',
    item_spacing: space(1),
    paddings: { left: space(3), top: space(2), right: space(3), bottom: space(2) },
    background_color: palette.surfaceAlt,
    border: { corner_radius: radius.md, stroke: { color: palette.line, width: 1 } },
    items: [
      { type: 'text', text: opts.label, font_size: ramp.label, font_weight: 'bold', text_color: palette.textMuted },
      { type: 'text', text: opts.value, font_size: ramp.title, font_weight: 'bold', text_color: opts.tone ?? palette.text },
    ],
  }
  if (opts.width) node.width = opts.width
  if (opts.id) node.id = opts.id
  return node
}
