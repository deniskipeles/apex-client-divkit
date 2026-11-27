#!/usr/bin/env node
// ---------------------------------------------------------------------------
// Emits the native icon catalog as JSON from the Android source of truth.
//
// `NativeIconCatalog.kt` is a pure-Kotlin table (no Compose import), so parsing
// it keeps one list: the device renders it, the playground resolves the same
// names, and `CardRegistry`-style alias resolution cannot drift.
//
//   node scripts/build-icon-catalog.mjs [--check]
// ---------------------------------------------------------------------------

import { readFileSync, writeFileSync, existsSync, mkdirSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const REPO = resolve(HERE, '..', '..', '..')
const SOURCE = join(REPO, 'apps/android/src/main/java/engine/icons/NativeIconCatalog.kt')
const TARGET = join(REPO, 'packages/contract/icons/native-icons.json')
const GENERATED = 'apps/dev-server/scripts/build-icon-catalog.mjs'
const check = process.argv.includes('--check')

const kotlin = readFileSync(SOURCE, 'utf8')

const categories = new Map()
for (const match of kotlin.matchAll(new RegExp('([A-Z_]+)\\("([^"]+)"\\)', 'g'))) {
  categories.set(match[1], match[2])
}

const icons = []
const entry = /NativeIcon\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*IconCategory\.([A-Z_]+)\s*(?:,\s*listOf\(([^)]*)\))?\s*\)/g
for (const match of kotlin.matchAll(entry)) {
  const [, name, material, categoryKey, aliasList = ''] = match
  const aliases = [...aliasList.matchAll(/"([^"]+)"/g)].map((alias) => alias[1])
  icons.push({
    name,
    material,
    category: categoryKey,
    categoryLabel: categories.get(categoryKey) ?? categoryKey,
    aliases,
  })
}

if (icons.length === 0) {
  console.error(`build-icon-catalog: no NativeIcon entries found in ${SOURCE}`)
  process.exit(1)
}

// Android keeps names/aliases in one map and Material ids in another, so a key
// may be claimed twice only when both claims are the same icon ("stock" has the
// Material id "Inventory" and the alias "inventory").
const nameKeys = new Map()
const materialKeys = new Map()
for (const icon of icons) {
  for (const key of [icon.name, ...icon.aliases]) {
    const owner = nameKeys.get(key)
    if (owner && owner !== icon.name) {
      console.error(`build-icon-catalog: duplicate icon key "${key}" (${owner} / ${icon.name})`)
      process.exit(1)
    }
    nameKeys.set(key, icon.name)
  }
  const material = icon.material.toLowerCase()
  const owner = materialKeys.get(material)
  if (owner && owner !== icon.name) {
    console.error(`build-icon-catalog: duplicate material "${icon.material}" (${owner} / ${icon.name})`)
    process.exit(1)
  }
  materialKeys.set(material, icon.name)
}
for (const [material, owner] of materialKeys) {
  const shadow = nameKeys.get(material)
  if (shadow && shadow !== owner) {
    console.error(`build-icon-catalog: material "${material}" shadowed by "${shadow}"`)
    process.exit(1)
  }
}

const payload = {
  _generated: GENERATED,
  _doNotEdit: 'Edit apps/android/src/main/java/engine/icons/NativeIconCatalog.kt and rerun `pnpm icons:build`.',
  count: icons.length,
  icons,
}
const serialized = `${JSON.stringify(payload, null, 2)}\n`

if (check) {
  const current = existsSync(TARGET) ? readFileSync(TARGET, 'utf8') : ''
  if (current !== serialized) {
    console.error('build-icon-catalog: catalog is stale — run `pnpm icons:build`')
    process.exit(1)
  }
  console.log(`build-icon-catalog: ${icons.length} icons in sync`)
} else {
  mkdirSync(dirname(TARGET), { recursive: true })
  writeFileSync(TARGET, serialized)
  console.log(`build-icon-catalog: wrote ${icons.length} icons to ${TARGET}`)
}
