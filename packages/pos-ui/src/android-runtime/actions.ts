import { evalExpr, resolveJsonExpressions } from './expression.ts'
import { display, toInteger, toNumber, truthy, type DivValue, type DivVars, type RowContext } from './values.ts'

/**
 * Action semantics of `DivKitMultiplatform.dispatchAllActions`.
 *
 * Actions run in order and each one sees the variables the previous one wrote,
 * so an action may reference a value it just produced. The render model keeps
 * the raw action plus its row context; evaluation happens at dispatch time,
 * exactly like the device.
 */

export interface RenderAction {
  node: Record<string, unknown>
  row: RowContext
}

export interface ActionHost {
  /** Current variables; re-read after every URL dispatch, like `EngineStateBridge.state`. */
  getVariables(): DivVars
  setVariable(name: string, value: DivValue): void
  /** Resolve a URL against the engine. Awaited so later actions see its writes. */
  dispatchUrl(url: string, payload: unknown): Promise<void> | void
}

const ARRAY_ACTION_TYPES = new Set([
  'array_insert_value',
  'array_remove_value',
  'array_set_value',
  'array_delete_value',
])

/** Kotlin `extractActions`: `action` first, then `actions`. */
export function extractActions(node: Record<string, unknown>): Record<string, unknown>[] {
  const out: Record<string, unknown>[] = []
  const single = node.action
  if (single && typeof single === 'object' && !Array.isArray(single)) out.push(single as Record<string, unknown>)
  const list = node.actions
  if (Array.isArray(list)) {
    for (const item of list) {
      if (item && typeof item === 'object' && !Array.isArray(item)) out.push(item as Record<string, unknown>)
    }
  }
  return out
}

export function toRenderActions(node: Record<string, unknown>, row: RowContext): RenderAction[] {
  return extractActions(node).map((action) => ({ node: action, row }))
}

/** Kotlin `variableValueFromAction`. */
export function variableValueFromAction(
  raw: unknown,
  vars: DivVars,
  row: RowContext,
): DivValue {
  if (Array.isArray(raw)) return raw.map((item) => actionArrayValue(item, vars, row))

  if (raw && typeof raw === 'object') {
    const obj = raw as Record<string, unknown>
    const type = typeof obj.type === 'string' ? obj.type : 'string'
    const value = obj.value
    // Kotlin reads the value through `jsonPrimitive.contentOrNull`, so an object
    // or array here is "no text", not its JSON form.
    const rawText =
      typeof value === 'string'
        ? value
        : typeof value === 'number' || typeof value === 'boolean'
          ? String(value)
          : ''
    const text = () => evalExpr(rawText, vars, row)
    switch (type) {
      case 'integer':
        return toInteger(text())
      case 'number':
        return toNumber(text())
      case 'boolean':
        return text().toLowerCase() === 'true'
      case 'array':
        return Array.isArray(value) ? value.map((item) => actionArrayValue(item, vars, row)) : []
      default:
        return text()
    }
  }

  if (typeof raw === 'string') return evalExpr(raw, vars, row)
  if (typeof raw === 'boolean') return raw
  if (typeof raw === 'number') return raw
  if (raw === null || raw === undefined) return ''
  return display(raw)
}

function actionArrayValue(element: unknown, vars: DivVars, row: RowContext): DivValue {
  if (typeof element === 'string') return evalExpr(element, vars, row)
  if (typeof element === 'number' || typeof element === 'boolean') return element
  if (Array.isArray(element)) return element.map((item) => actionArrayValue(item, vars, row))
  // Kotlin `divValueFromJson` turns a plain object into its JSON text.
  if (element && typeof element === 'object') return JSON.stringify(element)
  return ''
}

/** Kotlin `applyArrayAction`. */
export function applyArrayAction(
  type: string,
  action: Record<string, unknown>,
  currentValue: DivValue,
  vars: DivVars,
  row: RowContext,
): DivValue[] {
  const values: DivValue[] = Array.isArray(currentValue) ? [...currentValue] : []
  const index = () => {
    const raw = action.index
    if (raw === undefined || raw === null) return undefined
    const resolved = typeof raw === 'string' ? evalExpr(raw, vars, row) : display(raw)
    const parsed = Number(resolved)
    return Number.isFinite(parsed) ? Math.trunc(parsed) : undefined
  }

  switch (type) {
    case 'array_insert_value': {
      const item = variableValueFromAction(action.value, vars, row)
      if (action.position === 'start') values.unshift(item)
      else values.push(item)
      return values
    }
    case 'array_remove_value': {
      const target = display(variableValueFromAction(action.value, vars, row))
      return values.filter((value) => display(value) !== target)
    }
    case 'array_set_value': {
      const position = index()
      if (position === undefined || position < 0) return values
      const item = variableValueFromAction(action.value, vars, row)
      while (values.length <= position) values.push('')
      values[position] = item
      return values
    }
    case 'array_delete_value': {
      const position = index()
      if (position !== undefined && position >= 0 && position < values.length) values.splice(position, 1)
      return values
    }
    default:
      return values
  }
}

/**
 * Runs a list of actions the way the device does: `set_variable` and the array
 * ops fold locally, every other action with a `url` goes to the host.
 */
export async function runActions(actions: RenderAction[], host: ActionHost): Promise<void> {
  let current = { ...host.getVariables() }

  for (const { node, row } of actions) {
    const type = typeof node.type === 'string' ? node.type : null
    const name = typeof node.variable_name === 'string' ? node.variable_name : null

    if (type === 'set_variable' && name) {
      const value = variableValueFromAction(node.value, current, row)
      current = { ...current, [name]: value }
      host.setVariable(name, value)
      continue
    }

    if (type && ARRAY_ACTION_TYPES.has(type) && name) {
      const updated = applyArrayAction(type, node, current[name] ?? null, current, row)
      current = { ...current, [name]: updated }
      host.setVariable(name, updated)
      continue
    }

    const rawUrl = typeof node.url === 'string' ? node.url : null
    if (!rawUrl) continue
    const url = evalExpr(rawUrl, current, row)
    const payload = node.payload === undefined ? undefined : resolveJsonExpressions(node.payload, current, row)
    await host.dispatchUrl(url, payload)
    current = { ...host.getVariables() }
  }
}

/** Kotlin `variableValueFromAction` treats `"TRUE "` differently: it compares the text. */
export function actionTruthy(value: unknown): boolean {
  return truthy(value)
}
