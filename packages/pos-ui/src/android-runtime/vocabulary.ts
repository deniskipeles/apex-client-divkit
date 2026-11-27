/**
 * The DivKit surface `apps/android` actually renders.
 *
 * Mirrors the dispatch statements in `DivKitMultiplatform.kt` (node types and
 * the layout-block branch) and `engine/ui/DivChartSpec.kt` (chart aliases).
 * `packages/divkit-subset/schema.json` is generated from this file, so the
 * authoring validator cannot advertise a type the device ignores.
 */

/** Top-level `type` values handled by `RenderDivNode`. */
export const NODE_TYPES = [
  'container',
  'gallery',
  'grid',
  'text',
  'button',
  'input',
  'separator',
  'image',
  'json_list',
  'custom',
] as const

/** Native layout blocks (`RenderLayoutBlock`). */
export const BLOCK_TYPES = [
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
] as const

/** `custom_type` values that reach the chart renderer. */
export const CHART_TYPES = [
  'sdui_chart',
  'chart',
  'bar_chart',
  'line_chart',
  'area_chart',
  'donut_chart',
  'pie_chart',
] as const

/**
 * `custom_type` values the subset schema used to advertise. Android only routes
 * `json_list` and the chart aliases through the `custom` branch, so anything
 * else under `type: "custom"` renders nothing.
 */
export const UNRENDERED_CUSTOM_TYPES = ['sdui_signature', 'sdui_numpad'] as const

/** Action object types folded by `dispatchAllActions`. */
export const ACTION_TYPES = [
  'set_variable',
  'array_insert_value',
  'array_remove_value',
  'array_set_value',
  'array_delete_value',
] as const

/** URL schemes `DivActionRouter` / `EngineRouter` / `RenderDiv` own. */
export const URL_SCHEMES = [
  'tools',
  'local-db',
  'icons',
  'div-action',
  'nav',
  'sheet',
  'hardware',
  'workspace',
] as const

/** Props each node type reads, for the authoring validator's typo check. */
export const NODE_PROPS: Record<string, string[]> = {
  container: ['orientation', 'items', 'item_spacing', 'weight'],
  gallery: ['items', 'item_spacing'],
  grid: ['items', 'column_count', 'item_spacing'],
  text: ['text', 'font_size', 'font_weight', 'text_color', 'alignment_horizontal', 'font_family', 'max_lines'],
  button: [
    'text',
    'subtitle',
    'background_color',
    'text_color',
    'corner_radius',
    'icon',
    'text_alignment_horizontal',
    'font_size',
    'actions',
    'action',
  ],
  input: ['text_variable', 'hint_text'],
  separator: [],
  image: ['image_url', 'icon', 'width', 'height', 'size', 'tint', 'text_color', 'alignment_horizontal', 'content_description'],
  json_list: ['data_var', 'empty_text', 'row_card'],
  custom: ['custom_type', 'custom_props'],
  sticky_footer: ['body', 'footer'],
  segmented: ['items', 'active_value'],
  bottom_tabs: ['items', 'active_value'],
  progress_ring: ['value', 'label', 'caption'],
  text_field: ['label', 'variable', 'placeholder'],
  scan_chips: ['chunks_var', 'expected'],
  qr_controls: ['index', 'count', 'prev', 'next'],
  fab: ['label', 'actions', 'action'],
  toast: ['text'],
  form_modal: ['visible', 'title', 'body', 'primary_label', 'primary', 'secondary_label', 'secondary', 'dismiss'],
  bottom_sheet: ['visible', 'title', 'body', 'dismiss'],
  camera_viewfinder: ['hint'],
  inline_banner: ['text', 'tone'],
}

/** Props every node honours regardless of type. */
export const COMMON_PROPS = [
  'type',
  'id',
  'visibility',
  'actions',
  'action',
  'background_color',
  'background',
  'border',
  'corner_radius',
  'paddings',
  'margins',
  'width',
  'height',
  'weight',
  'custom_props',
  // Accepted by the authoring kit on any node; the device simply does not read
  // them everywhere (a button is always bold, for example).
  'font_weight',
  'content_description',
  'log_id',
  // Host directive, not a renderer prop: `DemoCardRepository` splices the tool
  // catalog into the hub container that carries it.
  'tool_catalog_after',
] as const

export function isNodeType(value: string): boolean {
  return (NODE_TYPES as readonly string[]).includes(value) || isBlockType(value)
}

export function isBlockType(value: string): boolean {
  return (BLOCK_TYPES as readonly string[]).includes(value)
}

export function isChartCustomType(value: string): boolean {
  return (CHART_TYPES as readonly string[]).includes(value.trim().toLowerCase())
}

export type Renderability = 'rendered' | 'renders-nothing'

/**
 * What the device does with a node, used by the validator and the parity badge.
 * `renders-nothing` is a hard error for authored content: the card will be
 * blank on the device even though the JSON is well formed.
 */
export function renderability(type: string, customType?: string | null): Renderability {
  if (isNodeType(type)) {
    if (type !== 'custom') return 'rendered'
    const custom = customType ?? ''
    if (custom === 'json_list' || isChartCustomType(custom)) return 'rendered'
    return 'renders-nothing'
  }
  return 'renders-nothing'
}
