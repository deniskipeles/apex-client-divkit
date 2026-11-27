import { describe, expect, it } from 'vitest'
import {
  applyArrayAction,
  display,
  evalExpr,
  evaluateExpression,
  formatNumber,
  parseRows,
  resolveJsonExpressions,
  runActions,
  seedVariables,
  variableValueFromAction,
  type DivVars,
  type RenderAction,
} from '@apex/pos-ui/android-runtime'

const vars = (entries: Record<string, unknown> = {}): DivVars => entries as DivVars

describe('expression parser (DivKitExpression.kt port)', () => {
  it('evaluates arithmetic with number coercion', () => {
    expect(evaluateExpression('1 + 2 * 3', vars(), {})).toBe(7)
    expect(evaluateExpression("'4' + 1", vars(), {})).toBe('41')
    expect(evaluateExpression('7 / 2', vars(), {})).toBe(3.5)
    expect(evaluateExpression('7 % 4', vars(), {})).toBe(3)
  })

  it('evaluates ternaries and comparisons', () => {
    expect(evaluateExpression("user.state == 'idle' ? 'Ready' : 'Busy'", vars({ 'user.state': 'idle' }), {})).toBe('Ready')
    expect(evaluateExpression('user.qty >= 3', vars({ 'user.qty': 2 }), {})).toBe(false)
    expect(evaluateExpression("'10' > 9", vars(), {})).toBe(true)
  })

  it('treats "0" and "false" as falsy, and empty strings as falsy', () => {
    expect(evaluateExpression("user.flag ? 'y' : 'n'", vars({ 'user.flag': '0' }), {})).toBe('n')
    expect(evaluateExpression("user.flag ? 'y' : 'n'", vars({ 'user.flag': 'false' }), {})).toBe('n')
    expect(evaluateExpression("user.flag ? 'y' : 'n'", vars({ 'user.flag': '' }), {})).toBe('n')
    expect(evaluateExpression("user.flag ? 'y' : 'n'", vars({ 'user.flag': 'FALSE' }), {})).toBe('n')
  })

  it('implements the helper functions the cards use', () => {
    const v = vars({ 'user.qr_chunks': ['a', 'b'], 'user.qr_index': 1 })
    expect(evaluateExpression("getArrayOptString(user.qr_chunks, user.qr_index, '')", v, {})).toBe('b')
    expect(evaluateExpression("getArrayOptString(user.qr_chunks, 9, 'none')", v, {})).toBe('none')
    expect(evaluateExpression('getArrayString(user.qr_chunks, 0)', v, {})).toBe('a')
    expect(evaluateExpression('toInteger(user.qr_index) + 1', v, {})).toBe(2)
    expect(evaluateExpression('len(user.qr_chunks)', v, {})).toBe(2)
    expect(evaluateExpression("coalesce('', user.qr_index, 'x')", v, {})).toBe(1)
    expect(evaluateExpression('sum(1, 2, 3)', v, {})).toBe(6)
    expect(evaluateExpression('max(2, 9) + min(1, 5)', v, {})).toBe(10)
  })

  it('returns an empty string for an unknown function, never an error', () => {
    expect(evaluateExpression('notAFunction(1, 2)', vars(), {})).toBe('')
  })

  it('resolves item.* from the row context', () => {
    expect(evaluateExpression("item.name + ' x' + item.qty", vars(), { name: 'Water', qty: 2 })).toBe('Water x2')
    expect(evaluateExpression('user.item.sku', vars(), { sku: 'P1' })).toBe('P1')
  })
})

describe('display + formatting', () => {
  it('formats numbers like BigDecimal.stripTrailingZeros', () => {
    expect(formatNumber(3)).toBe('3')
    expect(formatNumber(3.5)).toBe('3.5')
    expect(formatNumber(1e21)).toBe('1000000000000000000000')
    expect(formatNumber(1.5e-7)).toBe('0.00000015')
    expect(formatNumber(Number.NaN)).toBe('')
  })

  it('joins arrays and renders booleans as text', () => {
    expect(display(['a', 1, true])).toBe('a,1,true')
    expect(display(null)).toBe('')
  })
})

describe('@{...} interpolation', () => {
  it('leaves strings without an expression untouched', () => {
    expect(evalExpr('Plain', vars(), {})).toBe('Plain')
  })

  it('substitutes every expression in a larger string', () => {
    expect(evalExpr('@{user.a} + @{user.b}', vars({ 'user.a': 1, 'user.b': 2 }), {})).toBe('1 + 2')
  })

  it('renders a trailing operator the way the device does', () => {
    // Kotlin's parser treats a missing operand as "" -> 0, so this is "1", not an error.
    expect(evalExpr('@{1 +}', vars(), {})).toBe('1')
  })

  it('contains a parser error instead of throwing into the renderer', () => {
    // A deliberate divergence: Android would rethrow here and crash the card. The
    // preview degrades to an empty label so an author can see the rest of the screen.
    expect(evalExpr('@{((1)}', vars(), {})).toBe('')
  })
})

describe('resolveJsonExpressions', () => {
  it('keeps an array-producing expression as an array', () => {
    expect(resolveJsonExpressions('@{user.list}', vars({ 'user.list': ['a', 'b'] }), {})).toEqual(['a', 'b'])
    expect(resolveJsonExpressions("@{getArrayOptString(user.list, 0, '')}", vars({ 'user.list': ['x'] }), {})).toBe('x')
  })

  it('coerces only short numeric literals and keeps long identifiers as text', () => {
    expect(resolveJsonExpressions('@{user.n}', vars({ 'user.n': '42' }), {})).toBe(42)
    const safety = '904180047004428890268432910740436964069817656664439910064225'
    expect(resolveJsonExpressions('@{user.n}', vars({ 'user.n': safety }), {})).toBe(safety)
    expect(resolveJsonExpressions('@{user.b}', vars({ 'user.b': 'true' }), {})).toBe(true)
  })

  it('resolves nested payload objects', () => {
    const payload = resolveJsonExpressions(
      { options: { code: '@{user.code}', count: '@{user.count}' } },
      vars({ 'user.code': 'APEX', 'user.count': 3 }),
      {},
    )
    expect(payload).toEqual({ options: { code: 'APEX', count: 3 } })
  })
})

describe('typed action values', () => {
  it('reads the {type, value} form', () => {
    expect(variableValueFromAction({ type: 'integer', value: '5' }, vars(), {})).toBe(5)
    expect(variableValueFromAction({ type: 'boolean', value: 'TRUE' }, vars(), {})).toBe(true)
    expect(variableValueFromAction({ type: 'array', value: ['a', 2] }, vars(), {})).toEqual(['a', 2])
    expect(variableValueFromAction({ type: 'string', value: 'hi @{user.x}' }, vars({ 'user.x': 'there' }), {})).toBe('hi there')
  })

  it('reads primitives and arrays', () => {
    expect(variableValueFromAction('@{user.x}', vars({ 'user.x': 'v' }), {})).toBe('v')
    expect(variableValueFromAction(7, vars(), {})).toBe(7)
    // Kotlin `divValueFromJson(JsonObject)` keeps an object cell as its JSON text.
    expect(variableValueFromAction(['a', { type: 'integer', value: '2' }], vars(), {})).toEqual([
      'a',
      '{"type":"integer","value":"2"}',
    ])
  })
})

describe('array actions', () => {
  it('inserts at the end and at the start', () => {
    expect(applyArrayAction('array_insert_value', { value: 'c' }, ['a', 'b'], vars(), {})).toEqual(['a', 'b', 'c'])
    expect(
      applyArrayAction('array_insert_value', { value: 'c', position: 'start' }, ['a', 'b'], vars(), {}),
    ).toEqual(['c', 'a', 'b'])
  })

  it('sets by evaluated index and pads short arrays', () => {
    const v = vars({ 'user.i': 2 })
    expect(applyArrayAction('array_set_value', { index: '@{user.i}', value: 'z' }, ['a'], v, {})).toEqual(['a', '', 'z'])
  })

  it('deletes by index and removes by value', () => {
    expect(applyArrayAction('array_delete_value', { index: 1 }, ['a', 'b', 'c'], vars(), {})).toEqual(['a', 'c'])
    expect(applyArrayAction('array_remove_value', { value: 'b' }, ['a', 'b', 'b'], vars(), {})).toEqual(['a'])
  })
})

describe('runActions', () => {
  it('folds variables in order so a later action sees an earlier write', async () => {
    const state: DivVars = { 'user.total': 1 }
    const seen: string[] = []
    const actions: RenderAction[] = [
      { node: { type: 'set_variable', variable_name: 'user.total', value: { type: 'integer', value: '5' } }, row: {} },
      { node: { type: 'array_insert_value', variable_name: 'user.ids', value: '@{user.total}' }, row: {} },
      { node: { url: 'tools://files/list', payload: { options: { prefix: '@{user.ids}' } } }, row: {} },
    ]
    await runActions(actions, {
      getVariables: () => state,
      setVariable: (name, value) => {
        state[name] = value
        seen.push(`${name}=${display(value)}`)
      },
      dispatchUrl: (url, payload) => {
        seen.push(`${url} ${JSON.stringify(payload)}`)
      },
    })
    expect(state['user.total']).toBe(5)
    expect(state['user.ids']).toEqual(['5'])
    expect(seen).toEqual([
      'user.total=5',
      'user.ids=5',
      // A whole-expression payload keeps the expression's type: `user.ids` is an array.
      'tools://files/list {"options":{"prefix":["5"]}}',
    ])
  })
})

describe('seedVariables', () => {
  it('coerces declared types and never overwrites a live value', () => {
    const card = {
      card: {
        variables: [
          { name: 'user.count', type: 'integer', value: '3' },
          { name: 'user.flag', type: 'boolean', value: true },
          { name: 'user.list', type: 'array', value: ['a'] },
          { name: 'user.title', type: 'string', value: 'Hi' },
        ],
      },
    }
    const seeded = seedVariables(card, { 'user.title': 'live' })
    expect(seeded).toEqual({
      'user.count': 3,
      'user.flag': true,
      'user.list': ['a'],
      'user.title': 'live',
    })
  })
})

describe('parseRows', () => {
  it('parses a JSON string array and keeps object cells as JSON text', () => {
    expect(parseRows('[{"name":"Water","qty":2,"meta":{"a":1}}]')).toEqual([
      { name: 'Water', qty: '2', meta: '{"a":1}' },
    ])
    expect(parseRows('not json')).toEqual([])
    expect(parseRows([{ name: 'Coffee' }])).toEqual([{ name: 'Coffee' }])
  })
})

describe('expression edge cases match the device', () => {
  it('is case-insensitive for function names', () => {
    expect(evaluateExpression('TOINTEGER(1.9)', vars(), {})).toBe(1)
  })

  it('handles escaped strings', () => {
    expect(evaluateExpression("'a\\nb'", vars(), {})).toBe('a\nb')
  })
})
