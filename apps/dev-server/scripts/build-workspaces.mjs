#!/usr/bin/env node
/**
 * Generates every `apex.workspace/1` package from the TypeScript definitions in `workspace/`.
 *
 * Outputs, in one pass:
 *   - `client/public/workspaces/<slug>/`               playground snapshots (served statically)
 *   - `apps/android/src/dev/resources/demo/<id>/`      Android demo tenants that opt in
 *   - `apps/android/src/dev/resources/demo/workspaces.json`  index, appended without reordering
 *
 * JSON is emitted with a 2-space indent, the builders' insertion order (stable by
 * construction) and a trailing newline, so a second run is byte-identical.
 *
 * Flags: --check (exit 1 on drift), --only <slug>, --no-android, --force
 */
import { existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { buildWorkspace, indexEntry, workspacePayload, workspaceReports } from '../workspace/build.ts'
import { ANDROID_OWNED, WORKSPACES } from '../workspace/registry.ts'

const here = dirname(fileURLToPath(import.meta.url))
const appRoot = resolve(here, '..')
const repoRoot = resolve(appRoot, '../..')
const publicRoot = join(appRoot, 'client/public/workspaces')
const androidDemoRoot = join(repoRoot, 'apps/android/src/dev/resources/demo')

const args = process.argv.slice(2)
const check = args.includes('--check')
const noAndroid = args.includes('--no-android')
const force = args.includes('--force')
const onlyIndex = args.indexOf('--only')
const only = onlyIndex >= 0 ? args[onlyIndex + 1] : null

/** Reuses the indentation a file already has so regenerating never reformats it. */
function indentOf(path) {
  if (!existsSync(path)) return 2
  const second = readFileSync(path, 'utf8').split('\n')[1] ?? ''
  const match = /^( +)/.exec(second)
  return match ? match[1].length : 2
}

const serialize = (value, path) => `${JSON.stringify(value, null, indentOf(path))}\n`

const written = new Map()
let failures = 0

function emit(path, contents) {
  const previous = existsSync(path) ? readFileSync(path, 'utf8') : null
  if (previous === contents) return false
  written.set(path, contents)
  if (!check) {
    mkdirSync(dirname(path), { recursive: true })
    writeFileSync(path, contents)
  }
  return true
}

function pruneDirectory(dir, keep) {
  if (!existsSync(dir)) return
  for (const entry of readdirSync(dir)) {
    if (keep.has(entry)) continue
    const path = join(dir, entry)
    written.set(path, null)
    if (!check) rmSync(path, { recursive: true, force: true })
  }
}

const selected = only ? WORKSPACES.filter((workspace) => workspace.slug === only) : WORKSPACES
if (only && selected.length === 0) {
  console.error(`build-workspaces: no workspace with slug "${only}"`)
  process.exit(1)
}

const index = []

for (const definition of selected) {
  let bundle
  try {
    bundle = buildWorkspace(definition)
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error))
    failures += 1
    continue
  }
  index.push(indexEntry(bundle))

  const warnings = workspaceReports(definition).flatMap((report) =>
    report.warnings.map((warning) => `${definition.slug}: ${warning}`),
  )
  if (warnings.length > 0) {
    for (const warning of warnings) console.warn(`warning: ${warning}`)
  }

  for (const [path, value] of Object.entries(bundle.files)) {
    emit(join(publicRoot, bundle.slug, path), serialize(value, join(publicRoot, bundle.slug, path)))
  }
  // One-file bundle the SPA falls back to when the Nitro API is not reachable.
  emit(join(publicRoot, bundle.slug, 'index.json'), serialize(workspacePayload(bundle), join(publicRoot, bundle.slug, 'index.json')))
  pruneDirectory(join(publicRoot, bundle.slug, 'tools'), new Set(Object.keys(bundle.files).filter((p) => p.startsWith('tools/')).map((p) => p.slice('tools/'.length))))
  pruneDirectory(join(publicRoot, bundle.slug, 'collections'), new Set(Object.keys(bundle.files).filter((p) => p.startsWith('collections/')).map((p) => p.slice('collections/'.length))))

  if (noAndroid || !bundle.android) continue

  const target = join(androidDemoRoot, bundle.manifestId)
  const owned = ANDROID_OWNED.has(bundle.manifestId)
  if (existsSync(target) && !owned && !force) {
    console.error(`build-workspaces: refusing to overwrite "${bundle.manifestId}" (not in the Android-owned set; pass --force)`)
    failures += 1
    continue
  }
  for (const [path, value] of Object.entries(bundle.files)) {
    emit(join(target, path), serialize(value, join(target, path)))
  }
  pruneDirectory(join(target, 'tools'), new Set(Object.keys(bundle.files).filter((p) => p.startsWith('tools/')).map((p) => p.slice('tools/'.length))))
  pruneDirectory(join(target, 'collections'), new Set(Object.keys(bundle.files).filter((p) => p.startsWith('collections/')).map((p) => p.slice('collections/'.length))))
}

if (!only) {
  emit(join(publicRoot, 'index.json'), serialize({ workspaces: index }, join(publicRoot, 'index.json')))
  const androidIndex = join(androidDemoRoot, 'workspaces.json')
  const existing = existsSync(androidIndex)
    ? JSON.parse(readFileSync(androidIndex, 'utf8')).workspaces ?? []
    : []
  const merged = [...existing]
  for (const entry of index) {
    if (!merged.includes(entry.manifestId) && WORKSPACES.find((w) => w.slug === entry.slug)?.targets?.android) {
      merged.push(entry.manifestId)
    }
  }
  if (merged.length > 0) emit(androidIndex, serialize({ workspaces: merged }, androidIndex))
}

if (failures > 0) process.exit(1)

if (check && written.size > 0) {
  console.error('build-workspaces: generated files are out of date:')
  for (const [path] of written) console.error(`  ${relative(repoRoot, path)}`)
  console.error('run `pnpm workspaces:build` to regenerate them')
  process.exit(1)
}

if (!check) {
  const files = written.size
  const paths = [...written.keys()]
  console.log(`build-workspaces: ${selected.length} workspace(s), ${files} file(s) ${files === 0 ? 'already up to date' : 'written'}`)
  for (const path of paths) console.log(`  ${relative(repoRoot, path)}`)
}
