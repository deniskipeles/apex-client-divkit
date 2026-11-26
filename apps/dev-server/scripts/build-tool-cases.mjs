#!/usr/bin/env node
// ---------------------------------------------------------------------------
// Emits the tool-conformance fixtures the Android test executes.
//
// Sources: `tests/tools/definitions.ts` (metadata + cases) and the generated
// cards. Targets: `apps/android/src/test/resources/toolcases/` for the device,
// `client/public/toolcases/` for the playground. `--check` fails on drift so a
// hand-edited fixture cannot masquerade as a passing contract.
//
//   node scripts/build-tool-cases.mjs [--check] [--only <slug>] [--no-android]
// ---------------------------------------------------------------------------

import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { TOOL_MODULES } from '../tests/tools/index.ts'
import { cardToolActions, uncoveredCardActions, uncoveredOperations, validateToolModule } from '../tests/types.ts'

const HERE = dirname(fileURLToPath(import.meta.url))
const APP = resolve(HERE, '..')
const REPO = resolve(APP, '..', '..')
const ANDROID_OUT = join(REPO, 'apps/android/src/test/resources/toolcases')
const CLIENT_OUT = join(APP, 'client/public/toolcases')

const args = process.argv.slice(2)
const check = args.includes('--check')
const noAndroid = args.includes('--no-android')
const onlyIndex = args.indexOf('--only')
const only = onlyIndex >= 0 ? args[onlyIndex + 1] : null

const GENERATED = 'apps/dev-server/scripts/build-tool-cases.mjs'

function serialize(value) {
  return `${JSON.stringify(value, null, 2)}\n`
}

/** One self-contained fixture per tool: contract, card and executable cases. */
function fixture(module) {
  return {
    _generated: GENERATED,
    _doNotEdit: 'Edit apps/dev-server/tests/tools/definitions.ts and rerun `pnpm tool-cases:build`.',
    toolId: module.toolId,
    slug: module.slug,
    doc: module.doc,
    versionedCapability: module.versionedCapability,
    usage: module.usage,
    operations: module.operations,
    outputMapping: module.outputMapping,
    options: module.options,
    results: module.results,
    statuses: module.statuses,
    cardActions: cardToolActions(module.card),
    notCovered: uncoveredOperations(module).map((operation) => `${operation}: no case yet`),
    cases: module.cases,
  }
}

/** Contract problems that must block the build, not just warn. */
function problemsFor(module) {
  const problems = validateToolModule(module).map((problem) => `${module.slug}: ${problem}`)
  const uncovered = uncoveredCardActions(module)
  for (const action of uncovered) problems.push(`${module.slug}: card action ${action} has no case`)
  return problems
}

async function write(path, contents, changed) {
  const existing = await readFile(path, 'utf8').catch(() => null)
  if (existing === contents) return
  changed.push(path)
  if (check) return
  await mkdir(dirname(path), { recursive: true })
  await writeFile(path, contents)
}

const selected = only ? TOOL_MODULES.filter((module) => module.slug === only || module.toolId === only) : TOOL_MODULES
if (only && selected.length === 0) {
  console.error(`build-tool-cases: no module matches --only ${only}`)
  process.exit(2)
}

const duplicates = new Set()
const seen = new Set()
for (const module of TOOL_MODULES) {
  const key = module.toolId.toLowerCase()
  if (seen.has(key)) duplicates.add(module.toolId)
  seen.add(key)
}
if (duplicates.size > 0) {
  console.error(`build-tool-cases: duplicate tool ids ${[...duplicates].join(', ')}`)
  process.exit(1)
}

const problems = selected.flatMap(problemsFor)
if (problems.length > 0) {
  console.error('build-tool-cases: contract problems\n  ' + problems.join('\n  '))
  process.exit(1)
}

const changed = []
const index = []
for (const module of selected) {
  const contents = serialize(fixture(module))
  index.push({ slug: module.slug, toolId: module.toolId, caseCount: module.cases.length })
  if (!noAndroid) await write(join(ANDROID_OUT, `${module.slug}.json`), contents, changed)
  await write(join(CLIENT_OUT, `${module.slug}.json`), contents, changed)
}

const indexContents = serialize({ _generated: GENERATED, tools: index })
if (!noAndroid) await write(join(ANDROID_OUT, 'index.json'), indexContents, changed)
await write(join(CLIENT_OUT, 'index.json'), indexContents, changed)

const totalCases = selected.reduce((sum, module) => sum + module.cases.length, 0)
if (check) {
  if (changed.length > 0) {
    console.error('build-tool-cases: fixtures are stale, rerun `pnpm tool-cases:build`:\n  ' + changed.join('\n  '))
    process.exit(1)
  }
  console.log(`toolcases: ${selected.length} tools, ${totalCases} cases, no drift`)
} else {
  console.log(
    `toolcases: ${selected.length} tools, ${totalCases} cases, ${changed.length} file(s) updated`,
  )
}
