// ---------------------------------------------------------------------------
// Writes every registered schema to client/public/schemas/*.json.
//
// These files are:
//   * the offline fallback when /api/schemas/* is not reachable (static dist
//     opened from a plain file server), and
//   * the source of truth for `static` builds (Tauri) where the prerendered
//     API route and this file are byte-identical.
//
// Run: pnpm schemas:build
// ---------------------------------------------------------------------------
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const outDir = join(here, '..', 'client', 'public', 'schemas')

// The registry is a Nitro-side TS module and plain TS with type-only syntax, so
// Node's built-in type stripping (Node >= 22.18) imports it directly.
const { SCHEMAS } = await import('../server/utils/schemas/registry.ts')

mkdirSync(outDir, { recursive: true })

const index = []
for (const entry of SCHEMAS) {
  const schema = entry.build()
  const payload = { meta: entry.meta, schema, sizeKb: Math.round((JSON.stringify(schema).length / 1024) * 10) / 10 }
  const file = join(outDir, `${entry.meta.id}.json`)
  writeFileSync(file, JSON.stringify(payload, null, 2), 'utf8')
  index.push(entry.meta)
  console.log(`✓ ${entry.meta.id}.json  (${payload.sizeKb} KB)`)
}

writeFileSync(join(outDir, 'index.json'), JSON.stringify({ count: index.length, schemas: index }, null, 2), 'utf8')
console.log(`✓ index.json  (${index.length} schemas)`)
console.log(`Wrote ${SCHEMAS.length + 1} files to client/public/schemas/`)
