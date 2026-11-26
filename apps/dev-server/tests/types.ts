// ---------------------------------------------------------------------------
// Contract types for the per-tool conformance suite.
//
// One module per `tools://` capability is the single source for three artifacts:
// the demo card it ships in the `apex_tools_test` workspace, the documented
// contract in `docs/tools/<doc>.md`, and the JSON fixture `ToolConformanceTest`
// executes on the device. A module that drifts from `NativeToolsSuite` fails
// `toolConformance_mirrorsTheKotlinRegistry`.
// ---------------------------------------------------------------------------

/** A matcher is a single-key object, which keeps the JSON fixture unambiguous. */
export type Matcher =
  | { equals: unknown }
  | { type: 'string' | 'number' | 'boolean' | 'array' | 'object' }
  | { regex: string }
  | { nonEmpty: true }
  | { oneOf: unknown[] }
  | { range: { min?: number; max?: number } }
  | { contains: string }
  | { startsWith: string }

export const MATCHER_KEYS = [
  'equals',
  'type',
  'regex',
  'nonEmpty',
  'oneOf',
  'range',
  'contains',
  'startsWith',
] as const

export type MatcherKey = (typeof MATCHER_KEYS)[number]

export interface Expect {
  success?: boolean
  /** Expected `status` field, or several acceptable ones. */
  status?: string | string[]
  errorContains?: string
  /** Matchers over the tool's raw result object (`ToolExecutionOutcome.resultData`). */
  data?: Record<string, Matcher>
  /** Matchers over the mapped DivKit variables (`user.*`). */
  outputs?: Record<string, Matcher>
  /** Output keys that must NOT be mapped — proves a failure path stays quiet. */
  notOutputs?: string[]
}

export interface ToolStep {
  url: string
  /** The JSON object handed to `NativeToolsSuite.execute(url, payload)`. */
  payload?: Record<string, unknown>
  expect?: Expect
}

export interface Stress {
  repeat?: number
  /** Result fields that must differ between iterations (ratchet, ids, timestamps). */
  distinct?: string[]
  concurrent?: number
}

export type HostMode = 'fake' | 'none' | 'denied'

export interface ToolCase {
  name: string
  description?: string
  steps: ToolStep[]
  expect?: Expect
  host?: HostMode
  stress?: Stress
  /** Set only for an operation the harness cannot reach on this platform. */
  blocked?: string
}

export interface FieldMeta {
  name: string
  type: string
  description: string
}

export interface OptionMeta {
  name: string
  type: string
  default?: string
  description: string
}

export interface StatusMeta {
  status: string
  meaning: string
}

export interface ToolTestModule {
  /** Must equal `NativeToolsSuite.ToolSchema.toolId`. */
  toolId: string
  /** Fixture file name and the `--only` selector. */
  slug: string
  /** Reference doc, relative to the repo root. */
  doc: string
  /** Contract id callers may use in a card action. */
  versionedCapability: string
  usage: string
  operations: string[]
  /** Mirror of `defaultOutputMapping`; drift fails the Kotlin mirror assertion. */
  outputMapping: Record<string, string>
  options: OptionMeta[]
  results: FieldMeta[]
  statuses: StatusMeta[]
  cases: ToolCase[]
  /** DivKit card shipped by the `apex_tools_test` workspace. */
  card: unknown
}

const HOSTS: HostMode[] = ['fake', 'none', 'denied']

function isMatcher(value: unknown): value is Record<string, MatcherKey> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  const keys = Object.keys(value)
  return keys.length === 1 && (MATCHER_KEYS as readonly string[]).includes(keys[0])
}

function matcherProblems(where: string, matchers: Record<string, unknown> | undefined): string[] {
  const problems: string[] = []
  for (const [field, matcher] of Object.entries(matchers ?? {})) {
    if (!isMatcher(matcher)) {
      problems.push(`${where}.${field}: unknown matcher ${JSON.stringify(matcher)}`)
      continue
    }
    if ('regex' in matcher) {
      try {
        new RegExp(String(matcher.regex))
      } catch {
        problems.push(`${where}.${field}: invalid regex ${String(matcher.regex)}`)
      }
    }
    if ('range' in matcher) {
      const range = matcher.range as { min?: number; max?: number }
      if (range.min == null && range.max == null) problems.push(`${where}.${field}: range needs min or max`)
    }
  }
  return problems
}

/**
 * Structural validation shared by the generator (`--check`) and the vitest suite.
 * A module that fails here never reaches the device, so the messages name the
 * exact field that is wrong.
 */
export function validateToolModule(module: ToolTestModule): string[] {
  const problems: string[] = []
  if (!module.toolId) problems.push('toolId is required')
  if (!/^[a-z0-9][a-z0-9.-]*$/i.test(module.slug)) problems.push(`slug "${module.slug}" is not a file-safe name`)
  if (!module.operations.length) problems.push('operations must not be empty')
  if (!module.cases.length) problems.push('cases must not be empty')
  if (!Object.keys(module.outputMapping).length) problems.push('outputMapping must not be empty')

  const seen = new Set<string>()
  for (const testCase of module.cases) {
    const where = `cases[${testCase.name}]`
    if (seen.has(testCase.name)) problems.push(`${where}: duplicate case name`)
    seen.add(testCase.name)
    if (!testCase.steps.length) problems.push(`${where}: steps must not be empty`)
    if (testCase.host && !HOSTS.includes(testCase.host)) problems.push(`${where}: unknown host ${testCase.host}`)
    problems.push(...matcherProblems(`${where}.expect`, testCase.expect?.data))
    problems.push(...matcherProblems(`${where}.expect.outputs`, testCase.expect?.outputs))

    for (const [index, step] of testCase.steps.entries()) {
      const stepWhere = `${where}.steps[${index}]`
      if (!step.url.startsWith('tools://') && !step.url.startsWith('tool://')) {
        problems.push(`${stepWhere}: url must start with tools:// (got ${step.url})`)
      }
      const operation = operationOf(step.url)
      if (operation && !module.operations.includes(operation)) {
        problems.push(`${stepWhere}: operation "${operation}" is not in operations ${JSON.stringify(module.operations)}`)
      }
      problems.push(...matcherProblems(`${stepWhere}.expect`, step.expect?.data))
      problems.push(...matcherProblems(`${stepWhere}.expect.outputs`, step.expect?.outputs))
      for (const reference of referencesIn(step.payload)) {
        const match = reference.match(/^\$prev(?:\[(\d+)\])?\./)
        const target = match?.[1] ? Number(match[1]) : index - 1
        if (index === 0 || target < 0 || target >= index) {
          problems.push(`${stepWhere}: ${reference} has no earlier step to bind`)
        }
      }
    }

    if (testCase.stress?.repeat != null && testCase.stress.repeat < 2) {
      problems.push(`${where}: stress.repeat below 2 proves nothing`)
    }
    if (testCase.stress?.concurrent != null && testCase.stress.concurrent < 2) {
      problems.push(`${where}: stress.concurrent below 2 proves nothing`)
    }
    for (const field of testCase.stress?.distinct ?? []) {
      const namedInData = Object.keys(testCase.expect?.data ?? {}).includes(field)
      if (!namedInData && !testCase.steps.some((step) => Object.keys(step.expect?.data ?? {}).includes(field))) {
        problems.push(`${where}: distinct field "${field}" is not asserted anywhere`)
      }
    }
  }
  return problems
}

/** `$prev.field` / `$prev[1].field` references inside a payload. */
export function referencesIn(value: unknown, found: string[] = []): string[] {
  if (typeof value === 'string') {
    if (value.startsWith('$prev')) found.push(value)
  } else if (Array.isArray(value)) {
    for (const item of value) referencesIn(item, found)
  } else if (value && typeof value === 'object') {
    for (const item of Object.values(value)) referencesIn(item, found)
  }
  return found
}

/** Every `tools://` action in a card, in card order — the card/contract diff input. */
export function cardToolActions(card: unknown): { url: string; options: Record<string, string> }[] {
  const actions: { url: string; options: Record<string, string> }[] = []
  const walk = (node: unknown): void => {
    if (Array.isArray(node)) {
      node.forEach(walk)
      return
    }
    if (!node || typeof node !== 'object') return
    const record = node as Record<string, unknown>
    if (typeof record.url === 'string' && record.url.startsWith('tools://')) {
      const payload = (record.payload ?? {}) as Record<string, unknown>
      actions.push({ url: record.url, options: stringOptions(record.options ?? payload.options) })
    }
    Object.values(record).forEach(walk)
  }
  walk(card)
  return actions
}

export function stringOptions(value: unknown): Record<string, string> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {}
  const out: Record<string, string> = {}
  for (const [key, raw] of Object.entries(value as Record<string, unknown>)) {
    if (raw == null) continue
    out[key] = String(raw)
  }
  return out
}

/** Card actions whose `(url, options)` pair no case ever exercises. */
export function uncoveredCardActions(module: ToolTestModule): string[] {
  const covered = new Set<string>()
  for (const testCase of module.cases) {
    for (const step of testCase.steps) {
      covered.add(stepKey(step.url, stringOptions(step.payload?.options)))
    }
  }
  const missing: string[] = []
  for (const action of cardToolActions(module.card)) {
    const key = stepKey(action.url, action.options)
    if (!covered.has(key)) missing.push(key)
  }
  return missing
}

function stepKey(url: string, options: Record<string, string>): string {
  const optionPart = Object.keys(options)
    .sort()
    .map((key) => `${key}=${options[key]}`)
    .join('&')
  return `${normalizeOp(url)}?${optionPart}`
}

/** `tools://data.codec/json/validate` addresses operation `json/validate`. */
export function operationOf(url: string): string {
  const path = url.replace(/^tool(s)?:\/\//, '').split('?')[0]
  return path.split('/').slice(1).join('/') || 'default'
}

/** `tools://scanner/scan` and `tools://scan/scan` are the same contract step. */
export function normalizeOp(url: string): string {
  const path = url.replace(/^tool(s)?:\/\//, '')
  const tool = path.split('/')[0]
  const operation = operationOf(url)
  const alias: Record<string, string> = {
    scanner: 'scan',
    codegen: 'code-generate',
    vectorize: 'embedding',
    embed: 'embedding',
  }
  return `${alias[tool] ?? tool}/${operation}`
}

/** Operations with no case at all — reported, never silently skipped. */
export function uncoveredOperations(module: ToolTestModule): string[] {
  const covered = new Set<string>()
  for (const testCase of module.cases) {
    for (const step of testCase.steps) {
      covered.add(operationOf(step.url))
    }
  }
  return module.operations.filter((operation) => !covered.has(operation))
}
