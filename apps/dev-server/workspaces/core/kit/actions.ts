import type { DivNode } from './div.ts'

export interface DivAction extends DivNode {
  url: string
}

export interface ActionOptions {
  payload?: Record<string, unknown>
  logId?: string
  /** `div-action://set_variable?name=...&value=...` style target variables. */
  target?: string
}

export const action = (url: string, opts: ActionOptions = {}): DivAction => {
  const node: DivAction = { url }
  if (opts.payload) node.payload = opts.payload
  if (opts.logId) node.log_id = opts.logId
  if (opts.target) node.target = opts.target
  return node
}

export interface ToolOptions {
  /** Flat string arguments — Android reads `payload.options` as a string map. */
  options?: Record<string, string | number | boolean>
  /** DivKit variable names the result is mapped into (`user.foo`). */
  output?: Record<string, string>
  logId?: string
}

/** `tools://<capability>/<operation>` with the argument/output payload Android expects. */
export const tool = (capability: string, operation: string, opts: ToolOptions = {}): DivAction => {
  const payload: Record<string, unknown> = {}
  if (opts.options) payload.options = stringifyOptions(opts.options)
  if (opts.output) payload.output = opts.output
  return action(`tools://${capability}/${operation}`, {
    payload: Object.keys(payload).length > 0 ? payload : undefined,
    logId: opts.logId,
  })
}

/** `local-db://op/<name>` — the SQLite data plane. */
export const localDb = (operation: string, payload: Record<string, unknown> = {}): DivAction =>
  action(`local-db://op/${operation}`, { payload })

export const navPush = (cardId: string): DivAction => action(`nav://push?id=${cardId}`)

export const navPop = (): DivAction => action('nav://pop')

export const navReplace = (cardId: string): DivAction => action(`nav://replace?id=${cardId}`)

export const navReset = (cardId: string): DivAction => action(`nav://reset?id=${cardId}`)

export const sheetPresent = (cardId: string): DivAction => action(`sheet://present?card_id=${cardId}`)

export const sheetDismiss = (): DivAction => action('sheet://dismiss')

/** `div-action://set_variable?name=user.x&value=y` */
export const setVariable = (name: string, value: string): DivAction =>
  action(`div-action://set_variable?name=${encodeURIComponent(name)}&value=${encodeURIComponent(value)}`)

function stringifyOptions(options: Record<string, string | number | boolean>): Record<string, string> {
  const out: Record<string, string> = {}
  for (const [key, value] of Object.entries(options)) out[key] = String(value)
  return out
}
