import { describe, expect, it } from 'vitest'
import { validateCardDocument } from '@apex/pos-ui/android-runtime'
import { button, chart, jsonList } from '../../../workspace/kit/index.ts'

// The kit is what generates the workspaces, so a prop the renderer does not read
// is not a style problem: the card silently loses its data binding.
describe('kit builders emit what the Android renderer reads', () => {
  it('jsonList keeps data_var at the node root and builds a row card from keys', () => {
    const node = jsonList({ dataVar: 'user.samples_json', titleKey: 'name', subtitleKey: 'note' })
    expect(node).toMatchObject({ type: 'json_list', data_var: 'user.samples_json', empty_text: 'No records' })
    const rowCard = node.row_card as Record<string, unknown>
    const texts = (rowCard.items as Array<Record<string, unknown>>).map((item) => item.text)
    expect(texts).toEqual(['@{item.name}', '@{item.note}'])
  })

  it('chart writes chart_type, data_var and accent_hex', () => {
    const node = chart({ dataVar: 'user.chart_json', chartType: 'line', title: 'Sales' })
    const props = node.custom_props as Record<string, unknown>
    expect(props).toMatchObject({ chart_type: 'line', data_var: 'user.chart_json', accent_hex: expect.any(String) })
    expect(props.accent_color).toBeUndefined()
  })

  it('button carries a subtitle instead of dropping it', () => {
    const node = button({ text: 'Run', subtitle: 'tools://net/ping', actions: [] })
    expect(node.subtitle).toBe('tools://net/ping')
  })

  it('the validator rejects an expression where the renderer expects a variable name', () => {
    const card = {
      card: {
        variables: [{ name: 'user.rows', type: 'string', value: '[]' }],
        states: [{ state_id: 0, div: { type: 'json_list', data_var: '@{user.rows}' } }],
      },
    }
    const report = validateCardDocument(card, 'x.json')
    expect(report.errors.join('\n')).toContain('is a variable name, not an expression')
  })
})
