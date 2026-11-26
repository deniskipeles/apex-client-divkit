import { describe, expect, it } from 'vitest'
import { cardVariables, projectCardForWeb } from './androidProjection'

type AnyNode = Record<string, unknown>

const card = (div: AnyNode, variables: { name: string; value: unknown }[] = []) => ({
  card: { log_id: 'test', variables, states: [{ state_id: 0, div }] },
})

const find = (node: unknown, predicate: (n: AnyNode) => boolean): AnyNode | undefined => {
  if (!node || typeof node !== 'object') return undefined
  if (Array.isArray(node)) {
    for (const child of node) {
      const hit = find(child, predicate)
      if (hit) return hit
    }
    return undefined
  }
  const self = node as AnyNode
  if (predicate(self)) return self
  for (const value of Object.values(self)) {
    const hit = find(value, predicate)
    if (hit) return hit
  }
  return undefined
}

describe('json_list projection', () => {
  const jsonListCard = () =>
    card({
      type: 'json_list',
      data_var: 'user.rows',
      row_card: {
        type: 'container',
        items: [
          { type: 'text', text: '@{item.name}' },
          { type: 'text', text: '@{item.stock} in stock' },
          { type: 'button', text: 'Add', actions: [{ type: 'set_variable', variable_name: 'user.pick', value: '@{item.sku}' }] },
        ],
      },
    })

  it('expands a JSON string variable into one div per row', () => {
    const variables = new Map<string, unknown>([
      ['user.rows', JSON.stringify([{ name: 'Water', stock: 120, sku: 'P1001' }, { name: 'Coffee', stock: 80, sku: 'P1012' }])],
    ])
    const projected = projectCardForWeb(jsonListCard(), variables)
    const texts = collectTexts(projected)
    expect(texts).toEqual(['Water', '120 in stock', 'Add', 'Coffee', '80 in stock', 'Add'])
  })

  it('expands an already-parsed array variable', () => {
    const variables = new Map<string, unknown>([['user.rows', [{ name: 'Sandwich', stock: 40 }]]])
    const projected = projectCardForWeb(jsonListCard(), variables)
    expect(collectTexts(projected)).toEqual(['Sandwich', '40 in stock', 'Add'])
  })

  it('substitutes row fields inside action payloads', () => {
    const variables = new Map<string, unknown>([['user.rows', [{ name: 'Water', sku: 'P1001' }]]])
    const projected = projectCardForWeb(jsonListCard(), variables)
    const action = find(projected, (n) => n.type === 'set_variable')
    expect(action?.value).toBe('P1001')
  })

  it('leaves no @{item.*} placeholder behind', () => {
    const variables = new Map<string, unknown>([['user.rows', [{ name: 'Water', stock: 1, sku: 'x' }]]])
    expect(JSON.stringify(projectCardForWeb(jsonListCard(), variables))).not.toContain('@{item.')
  })

  it('falls back to the empty state for an empty or malformed variable', () => {
    for (const value of ['[]', 'not json', '', undefined]) {
      const projected = projectCardForWeb(jsonListCard(), new Map([['user.rows', value]]))
      expect(collectTexts(projected)).toEqual(['No records'])
    }
  })
})

describe('expression rewrite', () => {
  it('turns Android if() calls into web ternaries', () => {
    const projected = projectCardForWeb(
      card({ type: 'text', text: "@{if(user.state == 'idle', 'Ready', 'Busy')}" }),
      new Map<string, unknown>([['user.state', 'idle']]),
    )
    const node = find(projected, (n) => typeof n.text === 'string')
    expect(node?.text).toBe("@{((user.state == 'idle') ? 'Ready' : 'Busy')}")
  })

  it('keeps long numeric identifiers as text', () => {
    const safety = '904180047004428890268432910740436964069817656664439910064225'
    const projected = projectCardForWeb(card({ type: 'text', text: `@{user.number}` }), new Map([['user.number', safety]]))
    const node = find(projected, (n) => typeof n.text === 'string')
    expect(node?.text).toBe(`@{user.number}`)
  })
})

describe('cardVariables', () => {
  it('collects the card seed values', () => {
    const variables = cardVariables(
      card({ type: 'text', text: 'x' }, [
        { name: 'user.a', value: 1 },
        { name: 'user.b', value: 'two' },
      ]),
    )
    expect(variables.get('user.a')).toBe(1)
    expect(variables.get('user.b')).toBe('two')
  })
})

function collectTexts(node: unknown, out: string[] = []): string[] {
  if (!node || typeof node !== 'object') return out
  if (Array.isArray(node)) {
    for (const child of node) collectTexts(child, out)
    return out
  }
  const self = node as AnyNode
  if (self.type === 'text' && typeof self.text === 'string') out.push(self.text)
  for (const value of Object.values(self)) collectTexts(value, out)
  return out
}
