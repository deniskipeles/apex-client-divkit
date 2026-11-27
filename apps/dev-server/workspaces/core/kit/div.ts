import {
  DivContainer,
  DivGallery,
  DivGrid,
  DivImage,
  DivInput,
  DivSeparator,
  DivText,
  fixed,
  matchParent,
  weighted,
  wrapContent,
} from '@divkitframework/jsonbuilder'
import type { DivSize } from '@divkitframework/jsonbuilder'
import { palette, radius, space, type as ramp } from './tokens.ts'

export { fixed, matchParent, weighted, wrapContent }

/** DivKit expression literal: `@{user.total > 0 ? 'a' : 'b'}`. */
export const expression = (body: string): string => `@{${body}}`
export type { DivSize }

/** Workspace cards are serialized to JSON by the generator; the runtime shape is free-form. */
export type DivNode = any

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
  const props: Record<string, unknown> = {
    text: opts.text,
    font_size: opts.size ?? ramp.body,
    font_weight: opts.weight ?? 'regular',
    text_color: opts.color ?? palette.text,
  }
  if (opts.align) props.text_alignment_horizontal = opts.align
  if (opts.lines) props.max_lines = opts.lines
  if (opts.fontFamily) props.font_family = opts.fontFamily
  if (opts.width) props.width = opts.width
  if (opts.id) props.id = opts.id
  if (opts.marginTop !== undefined || opts.marginBottom !== undefined) {
    props.margins = { top: opts.marginTop ?? 0, bottom: opts.marginBottom ?? 0 }
  }
  return new DivText(props as any)
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
  const props: Record<string, unknown> = {
    orientation: opts.orientation ?? 'vertical',
    items: opts.items,
  }
  if (opts.spacing !== undefined) props.item_spacing = opts.spacing
  if (opts.background) props.background_color = opts.background
  if (opts.align) props.content_alignment_horizontal = opts.align
  if (opts.width) props.width = opts.width
  if (opts.height) props.height = opts.height
  if (opts.weight !== undefined) props.weight = opts.weight
  if (opts.id) props.id = opts.id
  const pad = opts.padding
  if (pad !== undefined) {
    props.paddings = typeof pad === 'number'
      ? { left: pad, top: pad, right: pad, bottom: pad }
      : { left: pad.left ?? 0, top: pad.top ?? 0, right: pad.right ?? 0, bottom: pad.bottom ?? 0 }
  }
  if (opts.radius !== undefined || opts.stroke) {
    props.border = {
      corner_radius: opts.radius ?? radius.md,
      ...(opts.stroke ? { stroke: { color: opts.stroke.color, width: opts.stroke.width } } : {}),
    }
  }
  return new DivContainer(props as any)
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
  subtitle?: string
  actions: DivNode[]
  tone?: 'primary' | 'secondary' | 'danger' | 'ghost'
  width?: DivSize
  height?: number
  size?: number
  id?: string
  contentDescription?: string
}

/** The runtime's native button block; jsonbuilder has no `DivButton`. */
export const button = (opts: ButtonOptions): DivNode => {
  const tone = opts.tone ?? 'primary'
  const bg =
    tone === 'primary' ? palette.primary
    : tone === 'danger' ? palette.danger
    : tone === 'secondary' ? palette.surfaceAlt
    : 'transparent'
  const fg = tone === 'ghost' ? palette.bg : tone === 'secondary' ? palette.text : palette.onPrimary
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
  if (opts.subtitle) node.subtitle = opts.subtitle
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
  const props: Record<string, unknown> = {
    text_variable: opts.textVariable,
    text_color: palette.text,
    hint_color: palette.textFaint,
    background_color: palette.surfaceAlt,
    corner_radius: radius.md,
    padding: { left: space(3), top: space(2), right: space(3), bottom: space(2) },
  }
  if (opts.hint) props.hint_text = opts.hint
  if (opts.id) props.id = opts.id
  return new DivInput(props as any)
}

export const image = (url: string, opts: { width?: DivSize; height?: DivSize; id?: string } = {}): DivNode => {
  const props: Record<string, unknown> = { image_url: url, scale: 'fit' }
  if (opts.width) props.width = opts.width
  if (opts.height) props.height = opts.height
  if (opts.id) props.id = opts.id
  return new DivImage(props as any)
}

export const separator = (marginTop = space(2), marginBottom = space(2)): DivNode =>
  new DivSeparator({
    delimiter_style: { color: palette.line, orientation: 'horizontal' },
    margins: { top: marginTop, bottom: marginBottom },
  } as any)

export const gallery = (items: DivNode[], id?: string): DivNode => {
  const props: Record<string, unknown> = { items, orientation: 'horizontal' }
  if (id) props.id = id
  return new DivGallery(props as any)
}

export const grid = (items: DivNode[], columnCount: number, id?: string): DivNode => {
  const props: Record<string, unknown> = { items, column_count: columnCount, orientation: 'horizontal' }
  if (id) props.id = id
  return new DivGrid(props as any)
}
