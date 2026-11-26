// ---------------------------------------------------------------------------
// androidProjection — rewrites Android-native blocks into standard DivKit divs
// (or registered web custom components) at render time.
//
// The generated JSON is the JSON Android ships, so the playground must never
// edit it: this projection runs on a deep copy right before render() and leaves
// the file on disk byte-identical to what the device consumes.
//
// Static-preview limitation: `json_list` rows are expanded from the card's
// initial variable value. A tool that rewrites that variable at runtime is not
// re-expanded here (DivKit's own `@{item.*}` binding does not exist on web), so
// the list shows the seed rows. The Android renderer re-reads the live variable.
// ---------------------------------------------------------------------------

type Node = Record<string, unknown>


function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T
}

/**
 * The Android expression runtime resolves `if(cond, then, else)`; the web runtime only knows
 * the ternary form and logs "Unknown function name: if" for every such string. Rewrite the
 * calls (top-level args only, quote-aware) so the preview shows the same conditional copy.
 */
function rewriteIfCalls(source: string): string {
  let out = source
  for (let pass = 0; pass < 5; pass += 1) {
    const start = out.indexOf('if(')
    if (start < 0) break
    let depth = 0
    let quote: string | null = null
    const args: string[] = []
    let current = ''
    let end = -1
    for (let i = start + 3; i < out.length; i += 1) {
      const ch = out[i]
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
      if (ch === ')') {
        if (depth === 0) {
          end = i
          args.push(current)
          break
        }
        depth -= 1
      }
      if (ch === ',' && depth === 0) {
        args.push(current)
        current = ''
        continue
      }
      current += ch
    }
    if (end < 0 || args.length !== 3) break
    const [cond, thenValue, elseValue] = args.map((arg) => arg.trim())
    out = `${out.slice(0, start)}((${cond}) ? ${thenValue} : ${elseValue})${out.slice(end + 1)}`
  }
  return out
}

function rewriteExpressions(value: unknown): unknown {
  if (typeof value === 'string') return value.includes('if(') ? rewriteIfCalls(value) : value
  if (Array.isArray(value)) return value.map(rewriteExpressions)
  if (value && typeof value === 'object') {
    const out: Node = {}
    for (const [key, item] of Object.entries(value as Node)) out[key] = rewriteExpressions(item)
    return out
  }
  return value
}

function parseRows(value: unknown): Node[] {
  if (Array.isArray(value)) return value.filter((row): row is Node => !!row && typeof row === 'object')
  if (typeof value === 'string' && value.trim().startsWith('[')) {
    try {
      const parsed = JSON.parse(value)
      return Array.isArray(parsed) ? parsed.filter((row): row is Node => !!row && typeof row === 'object') : []
    } catch {
      return []
    }
  }
  return []
}

function splitTopLevel(source: string, separator: string): string[] {
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

/** Drops the `)` a ternary branch keeps when the condition carried its own parens. */
function chopTrailingClose(source: string): string {
  const expr = source.trim()
  let depth = 0
  let quote: string | null = null
  for (let i = 0; i < expr.length; i += 1) {
    const ch = expr[i]
    if (quote) {
      if (ch === quote) quote = null
      continue
    }
    if (ch === "'" || ch === '"') quote = ch
    else if (ch === '(') depth += 1
    else if (ch === ')') depth -= 1
  }
  return depth < 0 ? expr.slice(0, expr.lastIndexOf(')')).trim() : expr
}

function itemValue(row: Node, field: string): string {
  const cell = row[field]
  if (cell === null || cell === undefined) return ''
  return typeof cell === 'object' ? JSON.stringify(cell) : String(cell)
}

function evalRowCondition(cond: string, row: Node): boolean {
  const test = cond.trim().replace(/^\(+|\)+$/g, '')
  const comparison = /^(item\.[A-Za-z0-9_]+)\s*(==|!=|>=|<=|>|<)\s*(.*)$/.exec(test)
  if (!comparison) return itemValue(row, test.replace(/^item\./, '')) !== ''
  const left = itemValue(row, comparison[1].replace(/^item\./, ''))
  const rightRaw = comparison[3].trim()
  const quoted = /^'(.*)'$/.exec(rightRaw) ?? /^"(.*)"$/.exec(rightRaw)
  const right = quoted ? quoted[1] : rightRaw
  const leftNum = Number(left)
  const rightNum = Number(right)
  const numeric = left !== '' && right !== '' && Number.isFinite(leftNum) && Number.isFinite(rightNum)
  switch (comparison[2]) {
    case '==':
      return numeric ? leftNum === rightNum : left === right
    case '!=':
      return numeric ? leftNum !== rightNum : left !== right
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

/** Evaluates the `item.*` expressions a `json_list` row template carries. */
function stripOuterParens(source: string): string {
  let expr = source.trim()
  while (expr.startsWith('(') && expr.endsWith(')')) {
    let depth = 0
    let quote: string | null = null
    let balanced = true
    for (let i = 0; i < expr.length; i += 1) {
      const ch = expr[i]
      if (quote) {
        if (ch === quote) quote = null
        continue
      }
      if (ch === "'" || ch === '"') {
        quote = ch
        continue
      }
      if (ch === '(') depth += 1
      if (ch === ')') {
        depth -= 1
        if (depth === 0 && i !== expr.length - 1) {
          balanced = false
          break
        }
      }
    }
    if (!balanced) break
    expr = expr.slice(1, -1).trim()
  }
  return expr
}

function evalRowExpr(body: string, row: Node): string | null {
  const expr = stripOuterParens(body)
  const ternary = splitTopLevel(expr, '?')
  if (ternary.length === 2) {
    const branches = splitTopLevel(ternary[1], ':')
    if (branches.length === 2) {
      const chosen = evalRowCondition(ternary[0], row) ? branches[0] : branches[1]
      return evalRowExpr(chopTrailingClose(chosen), row)
    }
  }
  const parts = splitTopLevel(expr, '+')
  if (parts.length > 1) {
    const rendered = parts.map((part) => evalRowExpr(part, row))
    if (rendered.some((part) => part === null)) return null
    return rendered.join('')
  }
  const literal = /^'(.*)'$/.exec(expr) ?? /^"(.*)"$/.exec(expr)
  if (literal) return literal[1]
  const field = /^item\.([A-Za-z0-9_]+)$/.exec(expr)
  if (field) return itemValue(row, field[1])
  if (/^-?\d+(\.\d+)?$/.test(expr)) return expr
  return null
}

function substitute(value: unknown, row: Node): unknown {
  if (typeof value === 'string') {
    return value.replace(/@\{([^{}]*)\}/g, (match, body: string) => {
      if (!body.includes('item.')) return match
      const rendered = evalRowExpr(body, row)
      return rendered ?? match
    })
  }
  if (Array.isArray(value)) return value.map((item) => substitute(item, row))
  if (value && typeof value === 'object') {
    const out: Node = {}
    for (const [key, item] of Object.entries(value as Node)) out[key] = substitute(item, row)
    return out
  }
  return value
}

function textNode(text: string, tone = '#94A3B8'): Node {
  return { type: 'text', text, font_size: 12, text_color: tone }
}

function props(node: Node): Node {
  return (node.custom_props as Node) ?? node
}

function containerOf(items: Node[], extra: Node = {}): Node {
  return { type: 'container', orientation: 'vertical', item_spacing: 8, items, ...extra }
}

/**
 * DivKit web dropped the legacy `background_color`/`corner_radius` shorthands; Android still
 * accepts them, so the preview rewrites them into `background`/`border` instead of the
 * generator emitting web-shaped JSON.
 */
function paint(node: Node): Node {
  const out: Node = { ...node }
  const color = out.background_color
  if (typeof color === 'string' && color && !out.background) {
    out.background = [{ type: 'solid', color }]
  }
  if (typeof out.corner_radius === 'number' && !out.border) {
    out.border = { corner_radius: out.corner_radius }
  }
  return out
}

/**
 * Projects one card for the browser. `variables` is the card's initial variable
 * map (name → value) so `json_list` can expand its rows.
 */
export function projectCardForWeb<T>(card: T, variables: Map<string, unknown>): T {
  card = rewriteExpressions(card) as T
  const walkRaw = (node: Node): Node => {
    const type = String(node.type ?? '')

    if (type === 'json_list' || (type === 'custom' && node.custom_type === 'json_list')) {
      const p = props(node)
      const dataVar = String(p.data_var ?? 'user.items')
      const rows = parseRows(variables.get(dataVar))
      const emptyText = String(p.empty_text ?? 'No records')
      const rowCard = (p.row_card as Node | undefined) ?? null
      if (rows.length === 0 || !rowCard) return containerOf([textNode(emptyText)], { id: node.id })
      return containerOf(rows.map((row) => walk(substitute(clone(rowCard), row) as Node)), { id: node.id })
    }

    if (type === 'sticky_footer') {
      const p = props(node)
      const body = (p.body as Node[] | undefined) ?? []
      const footer = (p.footer as Node[] | undefined) ?? []
      return containerOf([...body.map(walk), ...footer.map(walk)], { id: node.id, item_spacing: 12 })
    }

    if (type === 'form_modal' || type === 'bottom_sheet') {
      const p = props(node)
      const visible = String(p.visible ?? 'false') === 'true'
      const body = ((p.body as Node[] | undefined) ?? []).map(walk)
      const title = String(p.title ?? '')
      if (!visible) return containerOf([textNode(`${title} (hidden until shown)`, '#64748B')], { id: node.id })
      const actions: Node[] = []
      const primary = p.primary as { actions?: Node[] } | undefined
      const secondary = p.secondary as { actions?: Node[] } | undefined
      const dismiss = p.dismiss as { actions?: Node[] } | undefined
      if (p.primary_label) actions.push({ type: 'button', text: String(p.primary_label), actions: primary?.actions ?? [] })
      if (p.secondary_label) actions.push({ type: 'button', text: String(p.secondary_label), actions: secondary?.actions ?? [] })
      if (dismiss) actions.push({ type: 'button', text: 'Dismiss', actions: dismiss.actions ?? [] })
      return containerOf([{ type: 'text', text: title, font_size: 16, font_weight: 'bold' }, ...body, ...actions], { id: node.id })
    }

    if (type === 'text_field') {
      const p = props(node)
      return {
        type: 'input',
        id: node.id,
        text_variable: p.variable,
        hint_text: `${p.label ?? ''}${p.placeholder ? ` — ${p.placeholder}` : ''}`,
        text_color: '#F8FAFC',
        hint_color: '#64748B',
        background_color: '#16233A',
        corner_radius: 12,
        padding: { left: 12, top: 10, right: 12, bottom: 10 },
      }
    }

    // `div-button` is not accepted by the web runtime (it reports an invalid div and renders
    // nothing), so the preview draws it as a tappable container with the same visual props.
    if (type === 'button') {
      const border = (node.border as Node | undefined) ?? {}
      const radius = (node.corner_radius as number | undefined) ?? (border.corner_radius as number | undefined) ?? 12
      const stroke = border.stroke as Node | undefined
      return {
        type: 'container',
        id: node.id,
        orientation: 'horizontal',
        content_alignment_horizontal: 'center',
        content_alignment_vertical: 'center',
        background_color: node.background_color ?? '#1E293B',
        border: stroke ? { corner_radius: radius, stroke } : { corner_radius: radius },
        height: node.height ?? { type: 'fixed', value: 44 },
        width: node.width ?? { type: 'match_parent' },
        paddings: { left: 12, right: 12 },
        actions: node.actions,
        items: [
          {
            type: 'text',
            text: node.text,
            font_size: node.font_size ?? 14,
            font_weight: node.font_weight ?? 'bold',
            text_color: node.text_color ?? '#F8FAFC',
            text_alignment_horizontal: 'center',
            width: { type: 'match_parent' },
          },
        ],
      }
    }

    if (type === 'fab') {
      const p = props(node)
      return { type: 'button', id: node.id, text: String(p.label ?? 'Action'), actions: (p.actions as Node[] | undefined) ?? [] }
    }

    if (type === 'qr_controls') {
      const p = props(node)
      const prev = (p.prev as { actions?: Node[] } | undefined)?.actions ?? []
      const next = (p.next as { actions?: Node[] } | undefined)?.actions ?? []
      return containerOf(
        [
          textNode(`QR ${p.index ?? 1} of ${p.count ?? 1}`, '#94A3B8'),
          {
            type: 'container',
            orientation: 'horizontal',
            item_spacing: 8,
            items: [
              { type: 'button', text: 'Previous', actions: prev },
              { type: 'button', text: 'Next', actions: next },
            ],
          },
        ],
        { id: node.id },
      )
    }

    const customBlocks = new Set([
      'segmented', 'bottom_tabs', 'progress_ring', 'scan_chips', 'toast', 'inline_banner', 'camera_viewfinder',
    ])
    if (customBlocks.has(type)) {
      return { type: 'custom', custom_type: type, id: node.id, custom_props: props(node) }
    }

    if (type === 'custom') {
      const known = new Set([
        'sdui_chart', 'sdui_signature', 'sdui_numpad', 'segmented', 'bottom_tabs', 'progress_ring',
        'scan_chips', 'toast', 'inline_banner', 'camera_viewfinder',
      ])
      const customType = String(node.custom_type ?? '')
      if (known.has(customType)) return { ...node, custom_props: props(node) }
      return containerOf([textNode(`Unsupported block: ${customType || type}`, '#F59E0B')], { id: node.id })
    }

    const projected: Node = { ...node }
    if (Array.isArray(node.items)) projected.items = (node.items as Node[]).map(walk)
    return projected
  }

  const walk = (node: Node): Node => paint(walkRaw(node))

  const cardObj = (card as { card?: Node })?.card
  if (!cardObj) return card
  const states = (cardObj.states as Node[] | undefined) ?? []
  return {
    ...(card as Node),
    card: { ...cardObj, states: states.map((state) => ({ ...state, div: walk(state.div as Node) })) },
  } as T
}

/** Variables map from a DivKit card envelope (name → value) for `json_list` expansion. */
export function cardVariables(card: unknown): Map<string, unknown> {
  const out = new Map<string, unknown>()
  const list = ((card as { card?: { variables?: Array<{ name: string; value: unknown }> } })?.card?.variables) ?? []
  for (const variable of list) out.set(variable.name, variable.value)
  return out
}
