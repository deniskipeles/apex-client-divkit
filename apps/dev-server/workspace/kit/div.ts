import { palette, radius, space, tones, type as ramp } from './tokens.ts'

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
  letterSpacing?: number
  lineHeight?: number
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
  if (opts.align) {
    // The web runtime reads `text_alignment_horizontal`; the Android renderer reads
    // `alignment_horizontal`. Both are emitted so the preview matches the device.
    node.text_alignment_horizontal = opts.align
    node.alignment_horizontal = opts.align
  }
  if (opts.lines) node.max_lines = opts.lines
  if (opts.fontFamily) node.font_family = opts.fontFamily
  // `letter_spacing` / `line_height` are intentionally NOT emitted: the web
  // runtime logs a validation warning for them and the Android renderer ignores
  // them — tracked spacing would make the preview drift from the device.
  if (opts.width) node.width = opts.width
  if (opts.id) node.id = opts.id
  if (opts.marginTop !== undefined || opts.marginBottom !== undefined) {
    node.margins = { top: opts.marginTop ?? 0, bottom: opts.marginBottom ?? 0 }
  }
  return node
}

export interface ContainerOptions {
  items?: DivNode[]
  orientation?: 'vertical' | 'horizontal' | 'overlap'
  spacing?: number
  padding?: number | { left?: number; top?: number; right?: number; bottom?: number }
  background?: string
  radius?: number
  stroke?: { color: string; width: number }
  align?: 'start' | 'center' | 'end'
  valign?: 'top' | 'center' | 'bottom'
  contentAlign?: 'start' | 'center' | 'end'
  width?: DivSize
  height?: DivSize
  weight?: number
  id?: string
}

export const container = (opts: ContainerOptions): DivNode => {
  const node: DivNode = {
    type: 'container',
    orientation: opts.orientation ?? 'vertical',
    items: opts.items ?? [],
  }
  if (opts.spacing !== undefined) node.item_spacing = opts.spacing
  if (opts.background) node.background_color = opts.background
  if (opts.align) node.content_alignment_horizontal = opts.align
  if (opts.contentAlign) node.content_alignment_horizontal = opts.contentAlign
  if (opts.valign) node.content_alignment_vertical = opts.valign
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

export interface SurfaceOptions {
  items: DivNode[]
  padding?: number | { left?: number; top?: number; right?: number; bottom?: number }
  spacing?: number
  /** `raised` lifts the card with a stronger hairline; `inset` sinks it into the canvas. */
  elevation?: 'flat' | 'raised' | 'inset'
  radius?: number
  id?: string
}

/** Card surface: the repeated panel shape of every generated workspace. */
export const card = (items: DivNode[], opts: Omit<SurfaceOptions, 'items'> & { items?: DivNode[] } = {}): DivNode => {
  // Accepts both `card([nodes], opts)` and the shorthand `card({ items: [...] })`.
  const body = items.length > 0 ? items : ((opts as { items?: DivNode[] }).items ?? [])
  const pad = opts.padding ?? space(4)
  return container({
    items: body,
    spacing: opts.spacing ?? space(3),
    padding: pad,
    background: opts.elevation === 'inset' ? palette.surfaceAlt : palette.surface,
    radius: opts.radius ?? radius.lg,
    stroke: {
      color: opts.elevation === 'raised' ? palette.lineStrong : palette.line,
      width: 1,
    },
    ...(opts.id ? { id: opts.id } : {}),
  })
}

/** Section header row: tracked label plus an optional trailing hairline. */
export const sectionTitle = (label: string, opts: { color?: string; marginTop?: number; id?: string } = {}): DivNode =>
  text({
    text: label,
    size: ramp.label,
    weight: 'bold',
    color: opts.color ?? palette.textFaint,
    letterSpacing: 1.4,
    marginTop: opts.marginTop ?? space(1),
    width: matchParent(),
    id: opts.id,
  })

export interface HeroOptions {
  title: string
  subtitle?: string
  accent?: string
  back?: boolean
  badge?: string
  id?: string
}

/**
 * Page header: tracked kicker, display title, muted subtitle and a 4dp accent
 * bar. The bar gives every card one unmistakable anchor point, which is what
 * the small POS screen density needs.
 */
export const hero = (opts: HeroOptions): DivNode => {
  const accent = opts.accent ?? palette.primary
  const kicker: DivNode[] = []
  if (opts.back) {
    kicker.push(button({ text: 'Back', actions: [{ url: 'nav://pop' }], tone: 'ghost', width: fixed(92), height: space(9), size: ramp.small, id: 'btn_back' }))
  }
  if (opts.badge) {
    kicker.push(pill(opts.badge, 'accent', { id: `${opts.id ?? 'hero'}_badge` }))
  }
  return container({
    spacing: space(2),
    padding: { left: space(5), top: space(6), right: space(5), bottom: space(2) },
    items: [
      ...(kicker.length > 0 ? [container({ orientation: 'horizontal', spacing: space(2), items: kicker, valign: 'center' })] : []),
      container({
        orientation: 'horizontal',
        spacing: space(3),
        items: [
          container({
            width: fixed(4),
            height: fixed(46),
            radius: radius.pill,
            background: accent,
            items: [],
            id: `${opts.id ?? 'hero'}_bar`,
          }),
          container({
            weight: 1,
            spacing: space(1),
            items: [
              text({ text: opts.title, size: ramp.display, weight: 'bold', lines: 2, lineHeight: 1.1 }),
              ...(opts.subtitle ? [text({ text: opts.subtitle, size: ramp.small, color: palette.textMuted, lines: 2, lineHeight: 1.35 })] : []),
            ],
          }),
        ],
      }),
    ],
    id: opts.id,
  })
}

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
  const fg = tone === 'primary' || tone === 'danger' ? palette.onPrimary : tone === 'secondary' ? palette.text : palette.textMuted
  const node: DivNode = {
    type: 'button',
    text: opts.text,
    font_size: opts.size ?? ramp.body,
    font_weight: 'bold',
    text_color: fg,
    background_color: bg,
    corner_radius: radius.md,
    height: fixed(opts.height ?? space(12)),
    width: opts.width ?? matchParent(),
    actions: opts.actions,
  }
  if (tone === 'secondary' || tone === 'ghost') {
    node.border = { corner_radius: radius.md, stroke: { color: tone === 'ghost' ? palette.line : palette.lineStrong, width: 1 } }
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
    padding: { left: space(3), top: space(3), right: space(3), bottom: space(3) },
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

// ---------------------------------------------------------------------------
// Composite pieces shared by every generated workspace
// ---------------------------------------------------------------------------

export type ToneName = keyof typeof tones

/**
 * Status pill: tracked uppercase chip on a deep tint. The Android subset has
 * no shadows, so tint + tracking carry the whole affordance.
 */
export const pill = (
  label: string,
  tone: ToneName = 'neutral',
  opts: { id?: string; size?: number } = {},
): DivNode => {
  const t = tones[tone]
  return container({
    orientation: 'horizontal',
    padding: { left: space(3), top: space(1), right: space(3), bottom: space(1) },
    background: t.bg,
    radius: radius.pill,
    stroke: { color: t.fg, width: 1 },
    width: wrapContent(),
    items: [
      text({
        text: label,
        size: opts.size ?? ramp.label,
        weight: 'bold',
        color: t.fg,
        letterSpacing: 1.1,
        align: 'center',
      }),
    ],
    ...(opts.id ? { id: opts.id } : {}),
  })
}

/**
 * Rounded icon tile backed by the native icon scheme. Android renders the real
 * vector from `icons://native/<name>`; the web preview projects the node to a
 * glyph badge (see androidProjection.ts). Same JSON ships to both.
 */
export const iconBadge = (
  icon: string,
  tone: ToneName = 'primary',
  opts: { size?: number; glyphSize?: number; id?: string } = {},
): DivNode => {
  const t = tones[tone]
  const size = opts.size ?? 44
  return container({
    orientation: 'overlap',
    width: fixed(size),
    height: fixed(size),
    background: t.bg,
    radius: radius.md,
    stroke: { color: t.fg, width: 1 },
    align: 'center',
    valign: 'center',
    items: [
      {
        type: 'image',
        image_url: `icons://native/${icon}`,
        scale: 'fit',
        width: fixed(Math.round(size * 0.56)),
        height: fixed(Math.round(size * 0.56)),
        id: opts.id ? `${opts.id}_img` : undefined,
      },
    ],
    ...(opts.id ? { id: opts.id } : {}),
  })
}

export interface QuickTileOptions {
  label: string
  subtitle?: string
  icon: string
  tone?: ToneName
  actions: DivNode[]
  id?: string
}

/**
 * Dashboard action tile for a 2-column grid.
 *
 * Emitted as a `button` (not a tappable container) because the Android renderer
 * only wires tap actions on `button` and `text` nodes — the button branch also
 * renders `icon` and `subtitle`, so the device draws the same tile the preview
 * projects. `text_alignment_horizontal: left` keeps the label start-aligned on
 * both platforms.
 */
export const quickTile = (opts: QuickTileOptions): DivNode => {
  const t = tones[opts.tone ?? 'primary']
  const node: DivNode = {
    type: 'button',
    text: opts.label,
    subtitle: opts.subtitle ?? '',
    icon: opts.icon,
    icon_tint: t.fg,
    font_size: ramp.body,
    text_color: palette.text,
    background_color: palette.surface,
    corner_radius: radius.lg,
    height: fixed(space(19)),
    actions: opts.actions,
    text_alignment_horizontal: 'left',
    content_alignment_horizontal: 'start',
    border: { corner_radius: radius.lg, stroke: { color: palette.line, width: 1 } },
    paddings: { left: space(3), top: space(3), right: space(3), bottom: space(3) },
  }
  if (opts.id) node.id = opts.id
  return node
}

/** KeyValue row used by settings/receipt-style lists. */
export const keyValue = (key: string, value: string, opts: { valueTone?: string; id?: string } = {}): DivNode =>
  container({
    orientation: 'horizontal',
    spacing: space(2),
    padding: { left: space(3), top: space(2), right: space(3), bottom: space(2) },
    background: palette.surfaceAlt,
    radius: radius.sm,
    stroke: { color: palette.line, width: 1 },
    width: matchParent(),
    items: [
      text({ text: key, size: ramp.small, color: palette.textMuted, weight: 'medium', width: weighted(1) }),
      text({ text: value, size: ramp.small, weight: 'bold', color: opts.valueTone ?? palette.text, align: 'right', width: weighted(1) }),
    ],
    ...(opts.id ? { id: opts.id } : {}),
  })
