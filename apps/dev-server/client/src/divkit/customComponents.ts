import type { CustomComponentDescription } from '@divkitframework/divkit/client'

// ---------------------------------------------------------------------------
// Custom component registry — the extension slot of this playground.
//
// DivKit's web runtime renders a `div custom`
//   { type: 'custom', custom_type: '<key>', custom_props: {...}, items: [...] }
// by mounting the element registered here under `key`, putting the HTML
// produced by `template({ props, variables })` into a
// <template shadowrootmode="open"> child, and mounting the card's `items` as
// regular (fully reactive) DivKit children after it.
//
// Two rendering contexts, one contract:
//   * SSR  — the serialized HTML is parsed by the browser, so the declarative
//            shadow root is created automatically.
//   * CSR  — the runtime builds the <template> via DOM APIs (no parser), so
//            the element definition below adopts the template content into a
//            real shadow root on upgrade (and falls back to light DOM if the
//            runtime ever stops emitting the wrapper template).
//
// To extend the playground with a new custom div component:
//   1. implement + define the web component (defineCustomHost below)
//   2. add a CustomComponentDescription to the registry
//   3. reference it from any card via `custom_type`
// No playground code changes required — render() receives this map as-is.
// ---------------------------------------------------------------------------

/**
 * Resolves "@{var}" expression refs against the variables snapshot the
 * runtime hands to the template function. Plain values pass through.
 */
export function resolveRef<T = unknown>(ref: unknown, variables: Map<string, unknown>): T | undefined {
  if (typeof ref === 'string') {
    const m = /^@\{(.+)\}$/.exec(ref.trim())
    if (m) return variables.get(m[1]) as T
    const n = Number(ref)
    return (Number.isFinite(n) ? n : (ref as unknown)) as T
  }
  return ref as T
}

function topLevelSplit(source: string, separator: string): string[] {
  const parts: string[] = []
  let depth = 0
  let quote: string | null = null
  let current = ''
  for (const ch of source) {
    if (quote) {
      current += ch
      if (ch === quote) quote = null
      continue
    }
    if (ch === "'" || ch === '"') {
      quote = ch
      current += ch
      continue
    }
    if (ch === '(') depth += 1
    if (ch === ')') depth -= 1
    if (ch === separator && depth === 0) {
      parts.push(current)
      current = ''
      continue
    }
    current += ch
  }
  parts.push(current)
  return parts
}

/** Drops parentheses that wrap the whole expression, so `(a ? b : c)` splits like `a ? b : c`. */
function stripOuterParens(source: string): string {
  let out = source.trim()
  while (out.startsWith('(') && out.endsWith(')')) {
    let depth = 0
    let quote: string | null = null
    let wraps = true
    for (let i = 0; i < out.length; i += 1) {
      const ch = out[i]
      if (quote) {
        if (ch === quote) quote = null
        continue
      }
      if (ch === "'" || ch === '"') quote = ch
      else if (ch === '(') depth += 1
      else if (ch === ')') {
        depth -= 1
        if (depth === 0 && i !== out.length - 1) {
          wraps = false
          break
        }
      }
    }
    if (!wraps) break
    out = out.slice(1, -1).trim()
  }
  return out
}

/** Evaluates the `a == 'b'` / `a != ''` comparisons a generated card writes in ternaries. */
function evalCondition(condition: string, variables: Map<string, unknown>): boolean {
  const test = condition.trim().replace(/^\(+|\)+$/g, '')
  const any = topLevelSplit(test, '|')
  if (any.length > 1) return any.some((part) => evalCondition(part, variables))
  const all = topLevelSplit(test, '&')
  if (all.length > 1) return all.every((part) => evalCondition(part, variables))
  const comparison = /^([A-Za-z0-9_.]+)\s*(==|!=|>=|<=|>|<)\s*(.*)$/.exec(test)
  if (!comparison) return Boolean(variables.get(test))
  const left = variables.get(comparison[1])
  const rightRaw = comparison[3].trim()
  const quoted = /^'(.*)'$/.exec(rightRaw) ?? /^"(.*)"$/.exec(rightRaw)
  const right = quoted ? quoted[1] : (variables.get(rightRaw) ?? rightRaw)
  const leftNum = Number(left)
  const rightNum = Number(right)
  const numeric = left !== undefined && left !== '' && Number.isFinite(leftNum) && Number.isFinite(rightNum)
  switch (comparison[2]) {
    case '==':
      return numeric ? leftNum === rightNum : String(left ?? '') === String(right ?? '')
    case '!=':
      return numeric ? leftNum !== rightNum : String(left ?? '') !== String(right ?? '')
    case '>':
      return numeric && leftNum > rightNum
    case '<':
      return numeric && leftNum < rightNum
    case '>=':
      return numeric && leftNum >= rightNum
    default:
      return numeric && leftNum <= rightNum
  }
}

/**
 * Resolves the prop shapes a generated card uses: `@{var}` refs, simple
 * `@{'prefix ' + var}` concatenations and `@{cond ? a : b}` ternaries. Anything
 * else falls back to the raw body.
 */
export function evalProp(ref: unknown, variables: Map<string, unknown>): unknown {
  const direct = resolveRef(ref, variables)
  if (typeof ref !== 'string') return direct
  const m = /^@\{(.+)\}$/.exec(ref.trim())
  if (!m) return ref
  const body = stripOuterParens(m[1])
  const ternary = topLevelSplit(body, '?')
  if (ternary.length === 2) {
    const branches = topLevelSplit(ternary[1], ':')
    if (branches.length === 2) {
      const chosen = evalCondition(ternary[0], variables) ? branches[0] : branches[1]
      return evalProp(`@{${chosen.trim()}}`, variables)
    }
  }
  const literal = /^'(.*)'$/.exec(body.trim()) ?? /^"(.*)"$/.exec(body.trim())
  if (literal) return literal[1]
  if (!body.includes('+')) return direct
  const parts = body.split('+').map((part) => part.trim())
  const rendered = parts.map((part) => {
    const literal = /^'(.*)'$/.exec(part) ?? /^"(.*)"$/.exec(part)
    if (literal) return literal[1]
    const value = variables.get(part)
    if (value !== undefined) return String(value)
    return ''
  })
  return rendered.join('')
}

function num(v: unknown, fallback = 0): number {
  const n = typeof v === 'number' ? v : parseFloat(String(v))
  return Number.isFinite(n) ? n : fallback
}

function esc(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

// ---------------------------------------------------------------------------
// Element host — adopts the runtime's <template shadowrootmode> into a real
// shadow root when the element upgrades (CSR path). Idempotent.
// ---------------------------------------------------------------------------
function defineCustomHost(tag: string) {
  if (customElements.get(tag)) return
  class DivKitCustomHost extends HTMLElement {
    connectedCallback() {
      console.info(`[host:${this.tagName}] connectedCallback`)
      this.#adopt(0)
    }

    /**
     * The runtime inserts the element first and fills the template content
     * a tick later — retry within the same few frames until the content
     * lands, then move it into a real shadow root.
     */
    #adopt(attempt: number) {
      // SSR: the parser already created the shadow root from the declarative
      // template — nothing to do.
      if (this.shadowRoot || !this.isConnected) return
      const tpl = this.querySelector(':scope > template[shadowrootmode]')
      // Chrome keeps template.content.innerHTML as the authoritative
      // serialization even when it holds the parsed nodes elsewhere, so read
      // the string (never the fragment's childNodes) and re-parse it.
      // DocumentFragment.innerHTML exists in Chromium but is not in TS's DOM lib.
      const frag = tpl instanceof HTMLTemplateElement ? (tpl.content as unknown as { innerHTML: string }) : null
      const html = frag ? frag.innerHTML : ''
      if (html.trim()) {
        const root = this.attachShadow({ mode: (tpl!.getAttribute('shadowrootmode') as 'open') || 'open' })
        root.innerHTML = html
        tpl!.remove()
        return
      }
      // Element connects before the runtime inserts the template and fills
      // its content — keep polling a few frames, then give up and fall back
      // to light DOM (DivKit children stay visible).
      if (attempt < 90) setTimeout(() => this.#adopt(attempt + 1), 16)
    }
  }
  customElements.define(tag, DivKitCustomHost)
}

const HOST_STYLE = `<style>:host{display:block;width:100%;}</style>`

// ---------------------------------------------------------------------------
// <x-stat-ring> — template snapshot → SVG progress ring.
// custom_props: { value: "@{progress}", caption, sub }
// ---------------------------------------------------------------------------
const STAT_RING_HTML = ({ props, variables }: { props?: object; variables: Map<string, unknown> }) => {
  const p = (props ?? {}) as Record<string, unknown>
  const value = Math.max(0, Math.min(100, num(resolveRef(p.value, variables), 0)))
  const caption = esc(String(resolveRef(p.caption, variables) ?? ''))
  const sub = esc(String(resolveRef(p.sub, variables) ?? ''))

  const r = 52
  const c = 2 * Math.PI * r
  const dash = (c * value) / 100
  const tone = value >= 90 ? '#6D28D9' : value >= 50 ? '#7C3AED' : '#A78BFA'

  return `${HOST_STYLE}
  <div style="display:flex;align-items:center;gap:18px;margin-top:20px;padding:16px 18px;border-radius:18px;background:linear-gradient(135deg,#EDE9FE,#F5F3FF);border:1px solid #DDD6FE;">
    <div style="position:relative;width:120px;height:120px;flex:none;">
      <svg viewBox="0 0 120 120" width="120" height="120" style="display:block;transform:rotate(-90deg);">
        <circle cx="60" cy="60" r="${r}" fill="none" stroke="#E9E4FB" stroke-width="11"/>
        <circle cx="60" cy="60" r="${r}" fill="none" stroke="${tone}" stroke-width="11"
                stroke-linecap="round" stroke-dasharray="${dash.toFixed(1)} ${c.toFixed(1)}"/>
      </svg>
      <div style="position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;">
        <div style="font-size:26px;font-weight:800;color:#1E1B4B;line-height:1;">${Math.round(value)}%</div>
        <div style="font-size:10px;color:#7C3AED;font-weight:600;letter-spacing:1px;margin-top:3px;">OF TARGET</div>
      </div>
    </div>
    <div style="min-width:0;">
      <div style="font-size:17px;font-weight:700;color:#1E1B4B;">${caption}</div>
      <div style="font-size:12px;color:#6D28D9;line-height:1.45;margin-top:4px;">${sub}</div>
      <div style="display:inline-flex;align-items:center;gap:5px;margin-top:10px;padding:3px 9px;border-radius:999px;background:#7C3AED;color:#fff;font-size:10px;font-weight:700;letter-spacing:.6px;">
        <span style="width:6px;height:6px;border-radius:999px;background:#DDD6FE;display:inline-block;"></span>
        CUSTOM · x-stat-ring
      </div>
    </div>
  </div>`
}

// ---------------------------------------------------------------------------
// <x-note-frame> — template chrome + <slot> for the card's live items.
// custom_props: { title, badge }
// ---------------------------------------------------------------------------
const NOTE_FRAME_HTML = ({ props }: { props?: object; variables: Map<string, unknown> }) => {
  const p = (props ?? {}) as Record<string, unknown>
  const title = esc(String(p.title ?? ''))
  const badge = esc(String(p.badge ?? ''))
  return `${HOST_STYLE}
  <div style="margin-top:16px;padding:14px 16px;border-radius:18px;background:#FFFFFF;border:1px solid #DDD6FE;box-shadow:0 4px 14px rgba(124,58,237,.08);">
    <div style="display:flex;align-items:center;justify-content:space-between;gap:10px;padding-bottom:10px;margin-bottom:10px;border-bottom:1px dashed #DDD6FE;">
      <div style="font-size:11px;font-weight:700;letter-spacing:1.2px;color:#7C3AED;">${title}</div>
      <div style="padding:2px 8px;border-radius:999px;background:#1E1B4B;color:#DDD6FE;font-size:9px;font-weight:800;letter-spacing:1px;">${badge}</div>
    </div>
    <slot></slot>
  </div>`
}

// ---------------------------------------------------------------------------
// Android block vocabulary — preview renderers.
//
// `androidProjection.ts` turns the Android-only block types into `custom` divs
// with the block's props under `custom_props`; these templates draw them so a
// generated card can be reviewed before it ships to the device. They are
// read-only previews: taps are handled by the DivKit children the runtime
// mounts around the template, not by these elements.
// ---------------------------------------------------------------------------
const CARD_STYLE =
  'margin-top:12px;padding:14px 16px;border-radius:16px;background:#111C2E;border:1px solid #1E293B;font-family:ui-sans-serif,system-ui,sans-serif;'

const label = (text: string) =>
  `<div style="font-size:10px;font-weight:700;letter-spacing:1.1px;color:#94A3B8;">${esc(text)}</div>`

function rowsFrom(dataVar: unknown, variables: Map<string, unknown>): Array<Record<string, unknown>> {
  const raw = typeof dataVar === 'string' ? variables.get(dataVar) : dataVar
  if (Array.isArray(raw)) return raw.filter((row) => row && typeof row === 'object') as Array<Record<string, unknown>>
  if (typeof raw === 'string' && raw.trim().startsWith('[')) {
    try {
      const parsed = JSON.parse(raw)
      return Array.isArray(parsed) ? (parsed as Array<Record<string, unknown>>) : []
    } catch {
      return []
    }
  }
  return []
}

const CHART_PALETTE = ['#0284C7', '#10B981', '#6366F1', '#F59E0B', '#EC4899', '#8B5CF6', '#14B8A6', '#F97316']

/** sdui_chart → inline SVG (bar / line / area / donut / pie). */
const SDUI_CHART_HTML = ({ props, variables }: { props?: object; variables: Map<string, unknown> }) => {
  const p = (props ?? {}) as Record<string, unknown>
  const chartType = String(resolveRef(p.chart_type, variables) ?? 'bar')
  const title = esc(String(resolveRef(p.title, variables) ?? 'Chart'))
  const subtitle = esc(String(resolveRef(p.subtitle, variables) ?? ''))
  const prefix = String(resolveRef(p.value_prefix, variables) ?? '')
  const labelField = String(p.label_field ?? 'label')
  const valueField = String(p.value_field ?? 'value')
  const inline = Array.isArray(p.points) ? (p.points as Array<Record<string, unknown>>) : []
  const rows = inline.length > 0 ? inline : rowsFrom(p.data_var, variables)
  const points = rows
    .map((row, index) => ({
      label: String(row[labelField] ?? `#${index + 1}`),
      value: num(row[valueField], 0),
    }))
    .filter((point) => Number.isFinite(point.value))

  if (points.length === 0) {
    return `${HOST_STYLE}<div style="${CARD_STYLE}">${label(title)}<div style="margin-top:8px;font-size:12px;color:#64748B;">No data in ${esc(String(p.data_var ?? 'points'))}</div></div>`
  }

  const max = Math.max(...points.map((point) => point.value), 1)
  const width = 300
  const height = 150
  const pad = 26
  let svg = ''

  if (chartType === 'donut' || chartType === 'pie') {
    const total = points.reduce((sum, point) => sum + Math.max(0, point.value), 0) || 1
    let angle = -Math.PI / 2
    const cx = 80
    const cy = 78
    const r = 58
    const slices = points.map((point, index) => {
      const sweep = (Math.max(0, point.value) / total) * Math.PI * 2
      const x1 = cx + r * Math.cos(angle)
      const y1 = cy + r * Math.sin(angle)
      const x2 = cx + r * Math.cos(angle + sweep)
      const y2 = cy + r * Math.sin(angle + sweep)
      const large = sweep > Math.PI ? 1 : 0
      const path =
        chartType === 'pie'
          ? `<path d="M${cx} ${cy} L${x1.toFixed(1)} ${y1.toFixed(1)} A${r} ${r} 0 ${large} 1 ${x2.toFixed(1)} ${y2.toFixed(1)} Z" fill="${CHART_PALETTE[index % CHART_PALETTE.length]}" opacity="0.9"/>`
          : `<path d="M${x1.toFixed(1)} ${y1.toFixed(1)} A${r} ${r} 0 ${large} 1 ${x2.toFixed(1)} ${y2.toFixed(1)}" fill="none" stroke="${CHART_PALETTE[index % CHART_PALETTE.length]}" stroke-width="20" stroke-linecap="butt"/>`
      angle += sweep
      return path
    })
    svg = `<svg viewBox="0 0 300 156" width="100%" height="156" style="display:block;">${slices.join('')}${
      chartType === 'donut'
        ? `<text x="${cx}" y="${cy + 4}" text-anchor="middle" font-size="14" font-weight="700" fill="#F8FAFC">${esc(String(total))}</text>`
        : ''
    }</svg>`
  } else {
    const innerW = width - pad * 2
    const innerH = height - pad
    const step = points.length > 1 ? innerW / (points.length - 1) : innerW
    const barW = Math.max(8, Math.min(34, innerW / Math.max(points.length, 1) - 8))
    const coords = points.map((point, index) => ({
      x: pad + (points.length > 1 ? index * step : innerW / 2),
      y: height - pad - (point.value / max) * (innerH - 12),
    }))
    const bars = coords
      .map((c, index) =>
        chartType === 'bar'
          ? `<rect x="${(c.x - barW / 2).toFixed(1)}" y="${c.y.toFixed(1)}" width="${barW}" height="${(height - pad - c.y).toFixed(1)}" rx="4" fill="${CHART_PALETTE[index % CHART_PALETTE.length]}" opacity="0.92"/>`
          : '',
      )
      .join('')
    const linePoints = coords.map((c) => `${c.x.toFixed(1)},${c.y.toFixed(1)}`).join(' ')
    const area =
      chartType === 'area'
        ? `<polygon points="${pad},${height - pad} ${linePoints} ${(pad + (points.length - 1) * step).toFixed(1)},${height - pad}" fill="url(#chartFill)" opacity="0.5"/>`
        : ''
    const line =
      chartType === 'bar'
        ? ''
        : `<polyline points="${linePoints}" fill="none" stroke="#38BDF8" stroke-width="2.5" stroke-linejoin="round" stroke-linecap="round"/>`
    const dots = coords
      .map((c) => `<circle cx="${c.x.toFixed(1)}" cy="${c.y.toFixed(1)}" r="3" fill="#0B1120" stroke="#38BDF8" stroke-width="2"/>`)
      .join('')
    const labels = coords
      .map((c, index) => `<text x="${c.x.toFixed(1)}" y="${height - 6}" text-anchor="middle" font-size="9" fill="#94A3B8">${esc(points[index].label.slice(0, 6))}</text>`)
      .join('')
    svg = `<svg viewBox="0 0 ${width} ${height}" width="100%" height="${height}" style="display:block;">
      <defs><linearGradient id="chartFill" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stop-color="#38BDF8" stop-opacity="0.45"/><stop offset="100%" stop-color="#38BDF8" stop-opacity="0"/></linearGradient></defs>
      <line x1="${pad}" y1="${height - pad}" x2="${width - pad}" y2="${height - pad}" stroke="#1E293B" stroke-width="1"/>
      ${bars}${area}${line}${dots}${labels}
    </svg>`
  }

  const legend = points
    .slice(0, 6)
    .map(
      (point, index) =>
        `<span style="display:inline-flex;align-items:center;gap:4px;font-size:10px;color:#94A3B8;margin-right:8px;">
          <span style="width:7px;height:7px;border-radius:2px;background:${CHART_PALETTE[index % CHART_PALETTE.length]};display:inline-block;"></span>
          ${esc(point.label)} ${esc(prefix)}${point.value}
        </span>`,
    )
    .join('')

  return `${HOST_STYLE}<div style="${CARD_STYLE}">
    ${label(title)}
    <div style="font-size:11px;color:#64748B;margin-top:2px;">${subtitle}</div>
    ${svg}
    <div style="margin-top:6px;line-height:1.6;">${legend}</div>
  </div>`
}

/** sdui_signature → pad placeholder bound to a variable. */
const SDUI_SIGNATURE_HTML = ({ props, variables }: { props?: object; variables: Map<string, unknown> }) => {
  const p = (props ?? {}) as Record<string, unknown>
  const text = esc(String(resolveRef(p.label, variables) ?? 'Sign here'))
  const variable = esc(String(p.variable ?? ''))
  return `${HOST_STYLE}<div style="${CARD_STYLE}">
    ${label('SIGNATURE')}
    <div style="margin-top:8px;height:96px;border-radius:12px;border:1px dashed #334155;background:#0B1120;display:flex;align-items:center;justify-content:center;color:#475569;font-size:12px;">
      ${text}
    </div>
    <div style="margin-top:6px;font-family:ui-monospace,monospace;font-size:10px;color:#64748B;">→ ${variable}</div>
  </div>`
}

/** sdui_numpad → 0-9 keypad placeholder. */
const SDUI_NUMPAD_HTML = ({ props, variables }: { props?: object; variables: Map<string, unknown> }) => {
  const p = (props ?? {}) as Record<string, unknown>
  const text = esc(String(resolveRef(p.label, variables) ?? 'Amount'))
  const keys = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '.', '0', '⌫']
  return `${HOST_STYLE}<div style="${CARD_STYLE}">
    ${label(text.toUpperCase())}
    <div style="display:grid;grid-template-columns:repeat(3,1fr);gap:6px;margin-top:8px;">
      ${keys
        .map(
          (key) =>
            `<div style="height:38px;border-radius:10px;background:#16233A;border:1px solid #1E293B;display:flex;align-items:center;justify-content:center;font-size:15px;font-weight:600;color:#F8FAFC;">${key}</div>`,
        )
        .join('')}
    </div>
    <div style="margin-top:6px;font-family:ui-monospace,monospace;font-size:10px;color:#64748B;">→ ${esc(String(p.variable ?? ''))}</div>
  </div>`
}

/** progress_ring → SVG ring (value is a 0..1 fraction on Android). */
const PROGRESS_RING_HTML = ({ props, variables }: { props?: object; variables: Map<string, unknown> }) => {
  const p = (props ?? {}) as Record<string, unknown>
  const raw = num(resolveRef(p.value, variables), 0)
  const fraction = Math.max(0, Math.min(1, raw > 1 ? raw / 100 : raw))
  const caption = esc(String(resolveRef(p.label, variables) ?? ''))
  const sub = esc(String(resolveRef(p.caption, variables) ?? ''))
  const r = 40
  const c = 2 * Math.PI * r
  return `${HOST_STYLE}<div style="${CARD_STYLE};display:flex;align-items:center;gap:16px;">
    <div style="position:relative;width:96px;height:96px;flex:none;">
      <svg viewBox="0 0 96 96" width="96" height="96" style="transform:rotate(-90deg);">
        <circle cx="48" cy="48" r="${r}" fill="none" stroke="#1E293B" stroke-width="9"/>
        <circle cx="48" cy="48" r="${r}" fill="none" stroke="#34D399" stroke-width="9" stroke-linecap="round"
                stroke-dasharray="${(c * fraction).toFixed(1)} ${c.toFixed(1)}"/>
      </svg>
      <div style="position:absolute;inset:0;display:flex;align-items:center;justify-content:center;font-size:18px;font-weight:800;color:#F8FAFC;">
        ${Math.round(fraction * 100)}%
      </div>
    </div>
    <div>
      <div style="font-size:14px;font-weight:700;color:#F8FAFC;">${caption}</div>
      <div style="font-size:11px;color:#94A3B8;margin-top:3px;">${sub}</div>
    </div>
  </div>`
}

/** segmented / bottom_tabs → pill row. */
const SEGMENTED_HTML = ({ props }: { props?: object; variables: Map<string, unknown> }) => {
  const p = (props ?? {}) as Record<string, unknown>
  const items = Array.isArray(p.items) ? (p.items as Array<Record<string, unknown>>) : []
  const active = String(p.active_value ?? '')
  return `${HOST_STYLE}<div style="display:flex;gap:6px;flex-wrap:wrap;margin-top:12px;">
    ${items
      .map((item) => {
        const value = String(item.value ?? '')
        const on = value === active
        return `<div style="padding:7px 12px;border-radius:999px;font-size:11px;font-weight:700;border:1px solid ${on ? '#7C3AED' : '#1E293B'};background:${on ? '#7C3AED' : '#111C2E'};color:${on ? '#fff' : '#94A3B8'};">${esc(String(item.label ?? value))}</div>`
      })
      .join('')}
  </div>`
}

/** scan_chips → chunk chips. */
const SCAN_CHIPS_HTML = ({ props, variables }: { props?: object; variables: Map<string, unknown> }) => {
  const p = (props ?? {}) as Record<string, unknown>
  const rows = rowsFrom(p.chunks_var ?? 'user.chunks', variables)
  const expected = num(p.expected, 0)
  const chips = Array.from({ length: Math.max(expected, rows.length) }, (_unused, index) => {
    const chunk = rows[index]?.chunk
    const filled = chunk !== undefined && chunk !== null && String(chunk).length > 0
    return `<div style="padding:5px 9px;border-radius:8px;font-family:ui-monospace,monospace;font-size:10px;border:1px solid ${filled ? '#10B981' : '#1E293B'};background:${filled ? 'rgba(16,185,129,.12)' : '#0B1120'};color:${filled ? '#34D399' : '#475569'};">${index + 1}</div>`
  })
  return `${HOST_STYLE}<div style="${CARD_STYLE}">${label(`CHUNKS ${rows.length}/${expected || '?'}`)}<div style="display:flex;gap:5px;flex-wrap:wrap;margin-top:8px;">${chips.join('')}</div></div>`
}

/** toast / inline_banner → tone banner. */
const BANNER_HTML = ({ props, variables }: { props?: object; variables: Map<string, unknown> }) => {
  const p = (props ?? {}) as Record<string, unknown>
  const text = esc(String(evalProp(p.text, variables) ?? ''))
  const tone = String(evalProp(p.tone, variables) ?? 'info')
  const tones: Record<string, [string, string]> = {
    info: ['rgba(56,189,248,.12)', '#38BDF8'],
    success: ['rgba(16,185,129,.12)', '#34D399'],
    warn: ['rgba(245,158,11,.14)', '#F59E0B'],
    warning: ['rgba(245,158,11,.14)', '#F59E0B'],
    error: ['rgba(239,68,68,.14)', '#EF4444'],
    danger: ['rgba(239,68,68,.14)', '#EF4444'],
  }
  const [bg, fg] = tones[tone] ?? tones.info
  return `${HOST_STYLE}<div style="margin-top:10px;padding:9px 12px;border-radius:10px;background:${bg};border:1px solid ${fg}33;color:${fg};font-size:11.5px;font-weight:600;">${text}</div>`
}

/** camera_viewfinder → dashed viewport placeholder. */
const VIEWFINDER_HTML = ({ props, variables }: { props?: object; variables: Map<string, unknown> }) => {
  const p = (props ?? {}) as Record<string, unknown>
  const hint = esc(String(resolveRef(p.hint, variables) ?? 'Point the camera'))
  return `${HOST_STYLE}<div style="margin-top:12px;height:140px;border-radius:14px;border:1px dashed #334155;background:repeating-linear-gradient(45deg,#0B1120,#0B1120 8px,#0F172A 8px,#0F172A 16px);display:flex;align-items:center;justify-content:center;color:#64748B;font-size:12px;">${hint}</div>`
}

// ---------------------------------------------------------------------------
// Registry passed to render({ customComponents })
// ---------------------------------------------------------------------------
export const customComponentRegistry = new Map<string, CustomComponentDescription>([
  ['stat-ring', { element: 'x-stat-ring', template: STAT_RING_HTML, shadowRootMode: 'open' }],
  ['note-frame', { element: 'x-note-frame', template: NOTE_FRAME_HTML, shadowRootMode: 'open' }],
  ['sdui_chart', { element: 'x-sdui-chart', template: SDUI_CHART_HTML, shadowRootMode: 'open' }],
  ['sdui_signature', { element: 'x-sdui-signature', template: SDUI_SIGNATURE_HTML, shadowRootMode: 'open' }],
  ['sdui_numpad', { element: 'x-sdui-numpad', template: SDUI_NUMPAD_HTML, shadowRootMode: 'open' }],
  ['progress_ring', { element: 'x-progress-ring', template: PROGRESS_RING_HTML, shadowRootMode: 'open' }],
  ['segmented', { element: 'x-segmented', template: SEGMENTED_HTML, shadowRootMode: 'open' }],
  ['bottom_tabs', { element: 'x-segmented', template: SEGMENTED_HTML, shadowRootMode: 'open' }],
  ['scan_chips', { element: 'x-scan-chips', template: SCAN_CHIPS_HTML, shadowRootMode: 'open' }],
  ['toast', { element: 'x-inline-banner', template: BANNER_HTML, shadowRootMode: 'open' }],
  ['inline_banner', { element: 'x-inline-banner', template: BANNER_HTML, shadowRootMode: 'open' }],
  ['camera_viewfinder', { element: 'x-camera-viewfinder', template: VIEWFINDER_HTML, shadowRootMode: 'open' }],
])

for (const desc of customComponentRegistry.values()) {
  defineCustomHost(desc.element)
}

export function getCustomComponents(): Map<string, CustomComponentDescription> {
  return customComponentRegistry
}

export const CUSTOM_COMPONENT_IDS = [...customComponentRegistry.keys()]
