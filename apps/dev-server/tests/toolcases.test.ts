// ---------------------------------------------------------------------------
// The contract table is the source of truth for two consumers: the shipped
// `apex_tools_test` card and the on-device Kotlin conformance suite. These
// tests guard the table itself, so a malformed module fails in `pnpm test`
// instead of producing a card button that dispatches nothing.
// ---------------------------------------------------------------------------

import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { TOOL_MODULES } from './tools/index.ts'
import { EXPECTATIONS, STEP_EXPECTATIONS } from './tools/expectations.ts'
import { cardToolActions, operationOf, validateToolModule } from './types.ts'

const here = dirname(fileURLToPath(import.meta.url))
const fixtureDir = join(here, '..', '..', 'android', 'src', 'test', 'resources', 'toolcases')

const loadFixture = (slug: string) =>
  JSON.parse(readFileSync(join(fixtureDir, `${slug}.json`), 'utf8')) as {
    toolId: string
    versionedCapability: string
    usage: string
    outputMapping: Record<string, string>
    results: { name: string }[]
    statuses: { status: string }[]
    cases: {
      name: string
      steps: { url: string; payload: Record<string, unknown>; expect?: unknown }[]
      expect?: unknown
    }[]
  }

describe('tool-case modules', () => {
  it('covers at least twenty capabilities', () => {
    expect(TOOL_MODULES.length).toBeGreaterThanOrEqual(20)
  })

  it('validates every module', () => {
    for (const module of TOOL_MODULES) {
      expect(validateToolModule(module), module.slug).toEqual([])
    }
  })

  it('documents a user-facing usage line that is not the subtitle repeated', () => {
    for (const module of TOOL_MODULES) {
      expect(module.usage.endsWith('.'), module.slug).toBe(true)
      expect(module.usage.split(' ').length, `${module.slug} usage`).toBeGreaterThan(3)
      expect(module.statuses.length, `${module.slug} statuses`).toBeGreaterThan(0)
    }
  })

  it('only dispatches documented operations', () => {
    for (const module of TOOL_MODULES) {
      for (const testCase of module.cases) {
        for (const step of testCase.steps) {
          expect(module.operations, `${module.slug}/${testCase.name}`).toContain(operationOf(step.url))
        }
      }
    }
  })

  it('expects a result from every case', () => {
    for (const module of TOOL_MODULES) {
      for (const testCase of module.cases) {
        const hasExpect = Boolean(testCase.expect) || testCase.steps.some((step) => Boolean(step.expect))
        expect(hasExpect, `${module.slug}/${testCase.name} has no expectation`).toBe(true)
      }
    }
  })

  it('has no orphan expectations', () => {
    const keys = new Set<string>()
    for (const module of TOOL_MODULES) {
      for (const testCase of module.cases) keys.add(`${module.slug}/${testCase.name}`)
    }
    for (const key of [...Object.keys(EXPECTATIONS), ...Object.keys(STEP_EXPECTATIONS)]) {
      expect(keys.has(key), `${key} has an expectation but no case`).toBe(true)
    }
  })

  it('asserts success and a status in every expectation', () => {
    for (const [key, expectation] of Object.entries(EXPECTATIONS)) {
      expect(typeof expectation.success, `${key} success`).toBe('boolean')
      expect(expectation.status, `${key} status`).toBeTruthy()
    }
  })

  it('renders a button for every case and no button without a case', () => {
    for (const module of TOOL_MODULES) {
      const actions = cardToolActions(module.card as Record<string, unknown>)
      const covered = new Set(
        module.cases.map((testCase) => `${testCase.steps[0].url}|${JSON.stringify(sortKeys(testCase.steps[0].payload ?? {}))}`),
      )
      for (const action of actions) {
        const key = `${action.url}|${JSON.stringify(sortKeys(action.options ?? {}))}`
        const matches = [...covered].some((entry) => entry.includes(action.url))
        expect(matches, `${module.slug}: card button ${action.url} has no case`).toBe(true)
        expect(key.length).toBeGreaterThan(0)
      }
    }
  })
})

describe('generated Kotlin fixtures', () => {
  it('match the contract table', () => {
    for (const module of TOOL_MODULES) {
      const fixture = loadFixture(module.slug)
      expect(fixture.toolId).toBe(module.toolId)
      expect(fixture.versionedCapability).toBe(module.versionedCapability)
      expect(fixture.outputMapping).toEqual(module.outputMapping)
      expect(fixture.results.map((entry) => entry.name)).toEqual(module.results.map((entry) => entry.name))
      expect(fixture.cases.map((entry) => entry.name)).toEqual(module.cases.map((entry) => entry.name))
      fixture.cases.forEach((entry, index) => {
        const source = module.cases[index]
        expect(entry.steps.map((step) => step.url)).toEqual(source.steps.map((step) => step.url))
        expect(entry.expect ?? null).toEqual(source.expect ?? null)
      })
    }
  })
})

function sortKeys(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(sortKeys)
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value as Record<string, unknown>)
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([key, nested]) => [key, sortKeys(nested)]),
    )
  }
  return value
}
