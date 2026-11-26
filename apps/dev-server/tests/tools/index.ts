import { defineTool } from '../cardkit.ts'
import type { ToolTestModule } from '../types.ts'
import { TOOL_DEFINITIONS } from './definitions.ts'
import { EXPECTATIONS, STEP_EXPECTATIONS } from './expectations.ts'

const key = (slug: string, name: string) => `${slug}/${name}`

/**
 * One module per `tools://` capability, in tools-test hub order. The card is
 * built here so a module only declares metadata and cases, and the expected
 * outcomes are attached from `expectations.ts`.
 */
export const TOOL_MODULES: ToolTestModule[] = TOOL_DEFINITIONS.map((definition) => {
  const cases = definition.cases.map((testCase) => {
    const caseExpect = EXPECTATIONS[key(definition.slug, testCase.name)] ?? testCase.expect
    const stepExpect = STEP_EXPECTATIONS[key(definition.slug, testCase.name)] ?? {}
    const steps = testCase.steps.map((step, index) => ({
      ...step,
      ...(stepExpect[index] ? { expect: stepExpect[index] } : {}),
    }))
    if (!caseExpect && steps.every((step) => !step.expect)) {
      throw new Error(`toolcases: ${key(definition.slug, testCase.name)} has no expectation`)
    }
    return { ...testCase, steps, ...(caseExpect ? { expect: caseExpect } : {}) }
  })
  return defineTool({ ...definition, cases })
})

/** Lookup by manifest id, used by the generator and the card-coverage check. */
export const TOOL_MODULE_BY_ID: Record<string, ToolTestModule> = Object.fromEntries(
  TOOL_MODULES.map((module) => [module.toolId, module]),
)

export function expectationKey(toolId: string, caseName: string): string {
  const module = TOOL_MODULE_BY_ID[toolId]
  return key(module?.slug ?? toolId, caseName)
}
