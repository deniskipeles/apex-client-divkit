// ---------------------------------------------------------------------------
// Dev-boot guard: make sure the SPA client and the static schema snapshots
// exist before `nitro dev` starts serving. Everything is built once and kept
// in-repo, so this is a no-op after the first build.
// ---------------------------------------------------------------------------
import { existsSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')

function run(cmd, args) {
  const r = spawnSync(cmd, args, { stdio: 'inherit', cwd: root, shell: process.platform === 'win32' })
  if (r.status !== 0) process.exit(r.status ?? 1)
}

const needClient = !existsSync(join(root, 'public', 'index.html'))
const needSchemas = !existsSync(join(root, 'public', 'schemas', 'index.json'))
const needWorkspaces = !existsSync(join(root, 'public', 'workspaces', 'index.json'))

if (needClient) {
  console.log('[ensure-client] public/index.html missing — building SPA client…')
  run('vite', ['build'])
}
if (needSchemas) {
  console.log('[ensure-client] static schema snapshots missing — building…')
  run(process.execPath, ['scripts/build-schemas.mjs'])
}
if (needWorkspaces) {
  console.log('[ensure-client] workspace snapshots missing — generating…')
  run(process.execPath, ['scripts/build-workspaces.mjs'])
}
console.log('[ensure-client] ok')
