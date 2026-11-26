import { palette, radius, space, type as ramp } from './tokens.ts'

export type DivNode = Record<string, unknown>
export type DivSize =
  | { type: 'match_parent' }
  | { type: 'wrap_content' }
  | { type: 'fixed'; value: number }
  | { type: 'weighted'; weight: number }

export const matchParent = (): DivSize => ({ type: 'match_parent' })
export const wrapContent = (): DivSize => ({ type: 'wrap_content' })
export const fixed = (value: number): DivSize => ({ type: 'fixed', value })
export const weighted = (weight = 1): DivSize => ({ type: 'weighted', weight })

/** DivKit expression literal: `@{user.total > 0 ? 'a' : 'b'}`. */
export const expression = (body: string): string => `@{${body}}`

export interface TextOptions {
  text: string
  size?: number
  weight?: 'light' | 'regular' | 'medium' | 'bold'
  color?: string
  align?: 'left' | 'center' | 'right'
  lines?: number
  fontFamily?: 'monospace'
  marginTop?: number
  marginBottom?: number
  width?: DivSize
  id?: string
}

export const text = (opts: TextOptions): DivNode => {
  const node: DivNode = {
    type: 'text',
    text: opts.text,
    font_size: opts.size ?? ramp.body,
    font_weight: opts.weight ?? 'regular',
    text_color: opts.color ?? palette.text,
  }
  if (opts.align) node.text_alignment_horizontal = opts.align
  if (opts.lines) node.max_lines = opts.lines
  if (opts.fontFamily) node.font_family = opts.fontFamily
  if (opts.width) node.width = opts.width
  if (opts.id) node.id = opts.id
  if (opts.marginTop !== undefined || opts.marginBottom !== undefined) {
    node.margins = { top: opts.marginTop ?? 0, bottom: opts.marginBottom ?? 0 }
  }
  return node
}

export interface ContainerOptions {
  items: DivNode[]
  orientation?: 'vertical' | 'horizontal' | 'overlap'
  spacing?: number
  padding?: number | { left?: number; top?: number; right?: number; bottom?: number }
  background?: string
  radius?: number
  stroke?: { color: string; width: number }
  align?: 'start' | 'center' | 'end'
  width?: DivSize
  height?: DivSize
  weight?: number
  id?: string
}

export const container = (opts: ContainerOptions): DivNode => {
  const node: DivNode = {
    type: 'container',
    orientation: opts.orientation ?? 'vertical',
    items: opts.items,
  }
  if (opts.spacing !== undefined) node.item_spacing = opts.spacing
  if (opts.background) node.background_color = opts.background
  if (opts.align) node.content_alignment_horizontal = opts.align
  if (opts.width) node.width = opts.width
  if (opts.height) node.height = opts.height
  if (opts.weight !== undefined) node.weight = opts.weight
  if (opts.id) node.id = opts.id
  const pad = opts.padding
  if (pad !== undefined) {
    node.paddings = typeof pad === 'number'
      ? { left: pad, top: pad, right: pad, bottom: pad }
      : { left: pad.left ?? 0, top: pad.top ?? 0, right: pad.right ?? 0, bottom: pad.bottom ?? 0 }
  }
  if (opts.radius !== undefined || opts.stroke) {
    node.border = {
      corner_radius: opts.radius ?? radius.md,
      ...(opts.stroke ? { stroke: { color: opts.stroke.color, width: opts.stroke.width } } : {}),
    }
  }
  return node
}

/** Card surface: the repeated panel shape of every generated workspace. */
export const card = (items: DivNode[], opts: { padding?: number; id?: string } = {}): DivNode =>
  container({
    items,
    spacing: space(2),
    padding: opts.padding ?? space(3),
    background: palette.surface,
    radius: radius.lg,
    stroke: { color: palette.line, width: 1 },
    ...(opts.id ? { id: opts.id } : {}),
  })

export interface ButtonOptions {
  text: string
  actions: DivNode[]
  tone?: 'primary' | 'secondary' | 'danger' | 'ghost'
  width?: DivSize
  height?: number
  size?: number
  id?: string
  contentDescription?: string
}

export const button = (opts: ButtonOptions): DivNode => {
  const tone = opts.tone ?? 'primary'
  const bg =
    tone === 'primary' ? palette.primary
    : tone === 'danger' ? palette.danger
    : tone === 'secondary' ? palette.surfaceAlt
    : 'transparent'
  const fg = tone === 'secondary' || tone === 'ghost' ? palette.text : palette.onPrimary
  const node: DivNode = {
    type: 'button',
    text: opts.text,
    font_size: opts.size ?? ramp.body,
    font_weight: 'bold',
    text_color: fg,
    background_color: bg,
    corner_radius: radius.md,
    height: fixed(opts.height ?? space(11)),
    width: opts.width ?? matchParent(),
    actions: opts.actions,
  }
  if (tone === 'secondary' || tone === 'ghost') {
    node.border = { corner_radius: radius.md, stroke: { color: palette.line, width: 1 } }
  }
  if (opts.id) node.id = opts.id
  if (opts.contentDescription) node.content_description = opts.contentDescription
  return node
}

export interface InputOptions {
  textVariable: string
  hint?: string
  id?: string
}

export const input = (opts: InputOptions): DivNode => {
  const node: DivNode = {
    type: 'input',
    text_variable: opts.textVariable,
    text_color: palette.text,
    hint_color: palette.textFaint,
    background_color: palette.surfaceAlt,
    corner_radius: radius.md,
    padding: { left: space(3), top: space(2), right: space(3), bottom: space(2) },
  }
  if (opts.hint) node.hint_text = opts.hint
  if (opts.id) node.id = opts.id
  return node
}

export const image = (url: string, opts: { width?: DivSize; height?: DivSize; id?: string } = {}): DivNode => {
  const node: DivNode = { type: 'image', image_url: url, scale: 'fit' }
  if (opts.width) node.width = opts.width
  if (opts.height) node.height = opts.height
  if (opts.id) node.id = opts.id
  return node
}

export const separator = (marginTop = space(2), marginBottom = space(2)): DivNode => ({
  type: 'separator',
  delimiter_style: { color: palette.line, orientation: 'horizontal' },
  margins: { top: marginTop, bottom: marginBottom },
})

export const gallery = (items: DivNode[], id?: string): DivNode => {
  const node: DivNode = { type: 'gallery', items, orientation: 'horizontal' }
  if (id) node.id = id
  return node
}

export const grid = (items: DivNode[], columnCount: number, id?: string): DivNode => {
  const node: DivNode = { type: 'grid', items, column_count: columnCount, orientation: 'horizontal' }
  if (id) node.id = id
  return node
}
