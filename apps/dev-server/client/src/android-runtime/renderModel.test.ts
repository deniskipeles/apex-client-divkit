import { describe, expect, it } from 'vitest'
import {
  buildCardModel,
  findIcon,
  parseChart,
  seedVariables,
  validateCardDocument,
  type DivVars,
  type RenderNode,
} from '@apex/pos-ui/android-runtime'

const card = (div: Record<string, unknown>, variables: unknown[] = []) => ({
  card: { log_id: 'test', variables, states: [{ state_id: 0, div }] },
})

const collect = (node: RenderNode | null, out: RenderNode[] = []): RenderNode[] => {
  if (!node) return out
  out.push(node)
  if (node.kind === 'container' || node.kind === 'gallery' || node.kind === 'grid') {
    for (const child of node.children) collect(child, out)
  }
  if (node.kind === 'json_list') for (const row of node.rows) collect(row, out)
  if (node.kind === 'block' && 'body' in node.block) for (const child of node.block.body) collect(child, out)
  return out
}

describe('render model: standard nodes', () => {
  it('resolves text, weight, colour and alignment', () => {
    const model = buildCardModel(
      card({
        type: 'text',
        text: 'Hi @{user.name}',
        font_size: 18,
        font_weight: 'semibold',
        text_color: '#123456',
        alignment_horizontal: 'center',
        max_lines: 2,
      }),
      { 'user.name': 'Ada' } as DivVars,
    )
    expect(model).toMatchObject({
      kind: 'text',
      text: 'Hi Ada',
      fontSize: 18,
      fontWeight: 'medium',
      color: '#123456',
      align: 'center',
      maxLines: 2,
    })
  })

  it('drops a node whose visibility expression is "gone"', () => {
    const model = buildCardModel(
      card({
        type: 'container',
        items: [
          { type: 'text', text: 'kept' },
          { type: 'text', text: 'hidden', visibility: '@{user.hide}' },
        ],
      }),
      { 'user.hide': 'gone' } as DivVars,
    )
    const texts = collect(model).filter((node) => node.kind === 'text')
    expect(texts).toHaveLength(1)
    expect(texts[0]).toMatchObject({ text: 'kept' })
  })

  it('renders nothing for an unknown type, like the device', () => {
    // A card whose root renders nothing has no model at all.
    expect(buildCardModel(card({ type: 'carousel_v2', items: [] }), {})).toBeNull()
    expect(buildCardModel(card({ type: 'custom', custom_type: 'sdui_numpad' }), {})).toBeNull()
    expect(buildCardModel(card({ type: 'custom', custom_type: 'sdui_signature' }), {})).toBeNull()
    expect(buildCardModel(card({ type: 'custom', custom_type: 'sdui_chart', custom_props: {} }), {})?.kind).toBe('chart')
    const nested = buildCardModel(
      card({ type: 'container', items: [{ type: 'custom', custom_type: 'sdui_numpad' }, { type: 'text', text: 'x' }] }),
      {},
    )
    expect(collect(nested).filter((node) => node.kind === 'nothing')).toHaveLength(1)
  })

  it('keeps container orientation, spacing and grid columns', () => {
    const row = buildCardModel(card({ type: 'container', orientation: 'horizontal', item_spacing: 12, items: [] }), {})
    expect(row).toMatchObject({ kind: 'container', layout: 'horizontal', spacing: 12 })
    const grid = buildCardModel(card({ type: 'grid', column_count: 9, items: [] }), {})
    expect(grid).toMatchObject({ kind: 'grid', columnCount: 4 })
  })

  it('binds an input to its variable and shows the live value', () => {
    const model = buildCardModel(
      card({ type: 'input', text_variable: 'user.qty', hint_text: 'Qty' }),
      { 'user.qty': 3 } as DivVars,
    )
    expect(model).toMatchObject({ kind: 'input', variable: 'user.qty', hint: 'Qty', value: '3' })
  })
})

describe('render model: json_list', () => {
  const listCard = card({
    type: 'json_list',
    data_var: 'user.rows',
    empty_text: 'No records',
    row_card: {
      type: 'container',
      items: [
        { type: 'text', text: '@{item.name}' },
        { type: 'button', text: 'Add', actions: [{ type: 'set_variable', variable_name: 'user.pick', value: '@{item.sku}' }] },
      ],
    },
  })

  it('expands one row per record and binds the row context', () => {
    const model = buildCardModel(listCard, {
      'user.rows': JSON.stringify([
        { name: 'Water', sku: 'P1' },
        { name: 'Coffee', sku: 'P2' },
      ]),
    } as DivVars)
    expect(model).toMatchObject({ kind: 'json_list', dataVar: 'user.rows' })
    if (model?.kind !== 'json_list') throw new Error('expected json_list')
    expect(model.rows).toHaveLength(2)
    expect(collect(model.rows[0]).find((node) => node.kind === 'text')).toMatchObject({ text: 'Water' })
    const button = collect(model.rows[1]).find((node) => node.kind === 'button')
    expect(button?.actions[0].node.value).toBe('@{item.sku}')
  })

  it('re-expands from the live variable, so a tool write is visible', () => {
    const before = buildCardModel(listCard, { 'user.rows': '[]' } as DivVars)
    if (before?.kind !== 'json_list') throw new Error('expected json_list')
    expect(before.rows).toHaveLength(0)

    const after = buildCardModel(listCard, { 'user.rows': JSON.stringify([{ name: 'Tea', sku: 'P3' }]) } as DivVars)
    if (after?.kind !== 'json_list') throw new Error('expected json_list')
    expect(collect(after.rows[0]).find((node) => node.kind === 'text')).toMatchObject({ text: 'Tea' })
  })
})

describe('render model: native blocks', () => {
  it('reads block props from custom_props', () => {
    const model = buildCardModel(
      card({
        type: 'segmented',
        custom_props: {
          active_value: '@{user.tab}',
          items: [
            { value: 'day', label: 'Day', actions: [{ url: 'nav://push?id=day' }] },
            { value: 'week', label: 'Week' },
          ],
        },
      }),
      { 'user.tab': 'day' } as DivVars,
    )
    expect(model).toMatchObject({ kind: 'block' })
    if (model?.kind !== 'block' || model.block.type !== 'segmented') throw new Error('expected segmented')
    expect(model.block.activeValue).toBe('day')
    expect(model.block.items.map((item) => item.label)).toEqual(['Day', 'Week'])
    expect(model.block.items[0].actions[0].node.url).toBe('nav://push?id=day')
  })

  it('exposes qr controls, scan chips and modal state', () => {
    const qr = buildCardModel(
      card({
        type: 'qr_controls',
        custom_props: { index: 2, count: 5, prev: { actions: [{ url: 'nav://pop' }] }, next: { actions: [{ url: 'nav://push?id=x' }] } },
      }),
      {},
    )
    expect(qr).toMatchObject({ kind: 'block' })
    if (qr?.kind !== 'block' || qr.block.type !== 'qr_controls') throw new Error('expected qr_controls')
    expect(qr.block).toMatchObject({ index: 2, count: 5 })
    expect(qr.block.next[0].node.url).toBe('nav://push?id=x')

    const chips = buildCardModel(
      card({ type: 'scan_chips', custom_props: { chunks_var: 'user.chunks', expected: 3 } }),
      { 'user.chunks': JSON.stringify([{ chunk: 'a' }, { chunk: 'b' }]) } as DivVars,
    )
    if (chips?.kind !== 'block' || chips.block.type !== 'scan_chips') throw new Error('expected scan_chips')
    expect(chips.block).toMatchObject({ chunks: ['a', 'b'], expected: 3 })

    const modal = buildCardModel(
      card({
        type: 'form_modal',
        custom_props: {
          visible: '@{user.open}',
          title: 'Expense',
          body: [{ type: 'text', text: 'Body' }],
          primary_label: 'Save',
          primary: { actions: [{ url: 'tools://files/write' }] },
        },
      }),
      { 'user.open': 'true' } as DivVars,
    )
    if (modal?.kind !== 'block' || modal.block.type !== 'form_modal') throw new Error('expected form_modal')
    expect(modal.block).toMatchObject({ visible: true, title: 'Expense', primaryLabel: 'Save' })
    expect(modal.block.body).toHaveLength(1)
  })

  it('only treats the literal "true" as visible, like Kotlin flag()', () => {
    const modal = buildCardModel(card({ type: 'bottom_sheet', custom_props: { visible: 'yes' } }), {})
    if (modal?.kind !== 'block' || modal.block.type !== 'bottom_sheet') throw new Error('expected bottom_sheet')
    expect(modal.block.visible).toBe(false)
  })
})

describe('chart', () => {
  it('aggregates repeated labels and applies qty to a price series', () => {
    const spec = parseChart(
      {
        type: 'custom',
        custom_type: 'sdui_chart',
        custom_props: {
          data_var: 'user.sales',
          label_field: 'sku',
          value_field: 'price',
          value_prefix: '$',
        },
      },
      {
        'user.sales': JSON.stringify([
          { sku: 'A', price: 10, qty: 2 },
          { sku: 'A', price: 5, qty: 1 },
          { sku: 'B', price: 4, qty: 1 },
        ]),
      } as DivVars,
    )
    expect(spec.points.map((point) => [point.label, point.value])).toEqual([
      ['A', 25],
      ['B', 4],
    ])
  })

  it('falls back to the six demo points when there is no data', () => {
    const spec = parseChart({ type: 'custom', custom_type: 'sdui_chart', custom_props: {} }, {})
    expect(spec.points).toHaveLength(6)
    expect(spec.points[0]).toMatchObject({ label: '09:00', value: 420 })
  })

  it('reads inline points when the variable is empty', () => {
    const spec = parseChart(
      {
        type: 'custom',
        custom_type: 'bar_chart',
        custom_props: { points: [{ label: 'x', value: 3 }] },
      },
      {},
    )
    expect(spec.points).toHaveLength(1)
    expect(spec.points[0].value).toBe(3)
  })
})

describe('icons', () => {
  it('resolves canonical names, aliases and Material ids', () => {
    for (const reference of ['icons://native/cart', 'icons://cart', 'cart', 'shopping_cart', 'ShoppingCart']) {
      expect(findIcon(reference)?.name).toBe('cart')
    }
    expect(findIcon('icons://native/not_an_icon')).toBeNull()
    expect(findIcon('https://example.com/x.png')).toBeNull()
  })
})

describe('validation', () => {
  it('fails a card the device would render blank (strict)', () => {
    const report = validateCardDocument(card({ type: 'unknown_thing' }), 'x.json')
    expect(report.errors.join('\n')).toContain('unknown node type')
    const custom = validateCardDocument(card({ type: 'custom', custom_type: 'sdui_signature' }), 'x.json')
    expect(custom.errors.join('\n')).toContain('not implemented by the Android renderer')
  })

  it('downgrades the same findings to warnings for a web-only workspace', () => {
    const report = validateCardDocument(card({ type: 'custom', custom_type: 'sdui_signature' }), 'x.json', { strict: false })
    expect(report.errors).toEqual([])
    expect(report.warnings.join('\n')).toContain('not implemented')
  })

  it('rejects an action scheme the device does not dispatch', () => {
    const report = validateCardDocument(
      card({ type: 'button', text: 'x', actions: [{ url: 'https://evil.example' }] }),
      'x.json',
    )
    expect(report.errors.join('\n')).toContain('not a scheme the device dispatches')
  })

  it('warns when an action writes an undeclared variable', () => {
    const report = validateCardDocument(
      card(
        {
          type: 'button',
          text: 'x',
          actions: [{ type: 'set_variable', variable_name: 'user.nope', value: '1' }],
        },
        [{ name: 'user.yes', type: 'string', value: '' }],
      ),
      'x.json',
    )
    expect(report.errors).toEqual([])
    expect(report.warnings.join('\n')).toContain('does not declare')
  })
})
