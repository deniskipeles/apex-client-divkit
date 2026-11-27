// ---------------------------------------------------------------------------
// Card factory for the tool conformance modules.
//
// The card and the cases come from the same module, so a trigger that ships in
// the `apex_tools_test` workspace always has a case that exercises it
// (`cardToolActions` vs the case steps) and always reads variables the card
// actually declares.
// ---------------------------------------------------------------------------

import {
  button, card, container, expression, inlineBanner, matchParent, metric,
  action, navPop, palette, space, stringVar, text, type as ramp,
} from '../workspaces/core/kit/index.ts'
import type { DivNode } from '../workspaces/core/kit/div.ts'
import type { DivVariable } from '../workspaces/core/kit/variables.ts'
import type { ToolCase, ToolTestModule } from './types.ts'
import { operationOf } from './types.ts'

const STANDARD_VARIABLES = ['user.tools_status', 'user.tools_result', 'user.tools_error']

export interface ToolCardSpec {
  title: string
  subtitle: string
  /** Variable read as the card's headline status. */
  statusExpr: string
  statusLabel: string
  /** Extra readouts rendered under the status card (image, banner, chart...). */
  extra?: DivNode[]
}

export type ToolDefinition = Omit<ToolTestModule, 'card' | 'variables'> & {
  card?: ToolCardSpec | unknown
}

const isCardSpec = (value: unknown): value is ToolCardSpec =>
  !!value && typeof value === 'object' && 'statusExpr' in (value as Record<string, unknown>)

/** Declares every `user.*` the card reads: the mapped outputs plus the readouts. */
function cardVariables(module: Omit<ToolTestModule, 'card' | 'variables'>, spec: ToolCardSpec): DivVariable[] {
  const names = new Set<string>(STANDARD_VARIABLES)
  for (const target of Object.values(module.outputMapping)) names.add(target)
  if (spec.statusExpr.startsWith('user.')) names.add(spec.statusExpr)
  return [...names].map((name) => stringVar(name, ''))
}

/** One button per case, wired to that case's first step. */
function caseTriggers(module: Omit<ToolTestModule, 'card' | 'variables'>): DivNode[] {
  return module.cases.map((testCase: ToolCase, index) => {
    const step = testCase.steps[0]
    const payload = (step.payload ?? {}) as Record<string, unknown>
    const output = {
      ...defaultOutput(module),
      ...((payload.output as Record<string, string> | undefined) ?? {}),
    }
    // A card tap runs the first step only, so a multi-step case advertises that in its
    // label instead of pretending the whole sequence happened.
    const total = testCase.steps.length
    const label = total > 1
      ? `${testCase.description ?? testCase.name} · 1/${total}`
      : (testCase.description ?? testCase.name)
    return button({
      text: label,
      subtitle: step.url,
      actions: [action(step.url, { payload: { ...payload, output } })],
      tone: index % 2 === 0 ? 'primary' : 'secondary',
      size: ramp.small,
      height: space(10),
      id: `tool_${module.slug}_${testCase.name}`,
      contentDescription: `${module.toolId} ${operationOf(step.url)}`,
    })
  })
}

/** Bind only fields the tool actually returns, so the card cannot read `undefined`. */
function defaultOutput(module: Omit<ToolTestModule, 'card' | 'variables'>): Record<string, string> {
  if (module.results.some((field) => field.name === 'status')) return { status: 'user.tools_status' }
  const first = Object.keys(module.outputMapping)[0]
  return first ? { [first]: 'user.tools_result' } : {}
}

export function defineTool(definition: ToolDefinition): ToolTestModule {
  const { card: cardSpec, ...module } = definition
  if (cardSpec && !isCardSpec(cardSpec)) {
    return { ...module, card: cardSpec as DivNode, variables: [] }
  }
  const spec = (cardSpec as ToolCardSpec | undefined) ?? {
    title: module.toolId,
    subtitle: module.usage,
    statusLabel: 'Status',
    statusExpr: 'user.tools_status',
  }
  const variables = cardVariables(module, spec)
  const body = container({
    spacing: space(2),
    padding: { bottom: space(4) },
    items: [
      container({
        orientation: 'horizontal',
        spacing: space(2),
        padding: { left: space(4), top: space(8), right: space(4), bottom: space(2) },
        items: [
          button({ text: 'Back', actions: [navPop()], tone: 'secondary', width: { type: 'fixed', value: 80 }, height: space(10), size: ramp.small, id: 'btn_back' }),
          container({
            weight: 1,
            items: [
              text({ text: spec.title, size: ramp.title, weight: 'bold' }),
              text({ text: spec.subtitle, size: ramp.small, color: palette.textMuted }),
            ],
          }),
        ],
      }),
      container({
        padding: { left: space(4), right: space(4) },
        spacing: space(3),
        items: [
          metric({ label: spec.statusLabel, value: expression(spec.statusExpr), tone: palette.accent, id: 'metric_status' }),
          inlineBanner({ text: expression("user.tools_error == '' ? 'No errors' : user.tools_error"), tone: 'warn' }),
          ...(spec.extra ?? []),
          card(caseTriggers(module)),
          button({ text: 'Back to tools', actions: [navPop()], tone: 'ghost', height: space(10), size: ramp.small, width: matchParent(), id: 'btn_back_footer' }),
        ],
      }),
    ],
  })
  return { ...module, card: body, variables }
}
