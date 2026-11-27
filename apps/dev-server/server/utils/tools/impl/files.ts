import { ServerTool, requiredText, text } from '../ServerTool.ts'
import { workspaceKey } from '../types.ts'
import type { ToolContext, ToolOutcome } from '../types.ts'

// `tools://files` — a tenant-scoped byte store. The dev-server keeps it in
// memory (per workspace slug), which is enough for the preview and for cards
// that write a document then read it back. Nothing is written outside the
// process.

interface FileEntry {
  kind: 'file' | 'dir'
  data: Buffer
  createdAt: number
  updatedAt: number
}

const STORES = new Map<string, Map<string, FileEntry>>()

export class FilesTool extends ServerTool {
  readonly id = 'files'
  readonly aliases = ['file', 'storage.files', 'storage.files@1', 'file-store', 'fs']
  readonly operations = ['list', 'info', 'read', 'write', 'append', 'copy', 'move', 'delete', 'mkdir', 'exists', 'uri']

  invoke(operation: string, payload: Record<string, unknown>, context: ToolContext): ToolOutcome {
    const store = storeFor(workspaceKey(context))
    const op = operation || 'list'
    const path = normalizePath(text(payload.path) ?? '')
    const encoding = (text(payload.encoding) ?? 'utf8').toLowerCase()

    switch (op) {
      case 'write': {
        requirePath(path)
        const data = decodeContent(payload, encoding)
        ensureParentDirs(store, path)
        const now = Date.now()
        const existing = store.get(path)
        store.set(path, { kind: 'file', data, createdAt: existing?.createdAt ?? now, updatedAt: now })
        return this.ok({ status: 'SUCCESS', path, bytes: data.length, content: encodeContent(data, encoding) })
      }
      case 'append': {
        requirePath(path)
        const addition = decodeContent(payload, encoding)
        ensureParentDirs(store, path)
        const existing = store.get(path)
        if (existing?.kind === 'dir') throw new Error(`Is a directory: ${path}`)
        const data = Buffer.concat([existing?.data ?? Buffer.alloc(0), addition])
        const now = Date.now()
        store.set(path, { kind: 'file', data, createdAt: existing?.createdAt ?? now, updatedAt: now })
        return this.ok({ status: 'SUCCESS', path, bytes: data.length, content: encodeContent(data, encoding) })
      }
      case 'read': {
        requirePath(path)
        const entry = store.get(path)
        if (!entry) throw new Error(`No such file: ${path}`)
        if (entry.kind === 'dir') throw new Error(`Is a directory: ${path}`)
        return this.ok({
          status: 'SUCCESS',
          path,
          bytes: entry.data.length,
          encoding,
          content: encodeContent(entry.data, encoding),
        })
      }
      case 'info': {
        requirePath(path)
        const entry = store.get(path)
        return this.ok({
          status: 'SUCCESS',
          path,
          exists: Boolean(entry),
          is_dir: entry?.kind === 'dir',
          bytes: entry?.data.length ?? 0,
          updated_at: entry?.updatedAt ?? 0,
        })
      }
      case 'exists': {
        requirePath(path)
        return this.ok({ status: 'SUCCESS', path, exists: store.has(path) })
      }
      case 'list': {
        const entries = listEntries(store, path)
        return this.ok({
          status: 'SUCCESS',
          path,
          count: entries.length,
          entries,
          entries_json: JSON.stringify(entries),
        })
      }
      case 'mkdir': {
        requirePath(path)
        ensureDir(store, path)
        return this.ok({ status: 'SUCCESS', path, is_dir: true })
      }
      case 'copy':
      case 'move': {
        const destination = normalizePath(requiredText(payload, 'destination', 'dest', 'to'))
        requirePath(destination)
        const entry = store.get(path)
        if (!entry) throw new Error(`No such file: ${path}`)
        ensureParentDirs(store, destination)
        store.set(destination, { ...entry, updatedAt: Date.now() })
        if (op === 'move') removeEntry(store, path)
        return this.ok({ status: 'SUCCESS', path: destination, source: path, bytes: entry.data.length })
      }
      case 'delete': {
        requirePath(path)
        const existed = store.has(path)
        removeEntry(store, path)
        return this.ok({ status: 'SUCCESS', path, deleted: existed })
      }
      case 'uri': {
        requirePath(path)
        if (!store.has(path)) throw new Error(`No such file: ${path}`)
        return this.ok({ status: 'SUCCESS', path, uri: `file:///${path.split('/').map(encodeURIComponent).join('/')}` })
      }
      default:
        return this.refuse('UNSUPPORTED', `files/${op} is not supported`)
    }
  }
}

function storeFor(key: string): Map<string, FileEntry> {
  let store = STORES.get(key)
  if (!store) {
    store = new Map()
    STORES.set(key, store)
  }
  return store
}

function normalizePath(raw: string): string {
  const parts: string[] = []
  for (const segment of raw.replace(/\\/g, '/').split('/')) {
    if (!segment || segment === '.') continue
    if (segment === '..') {
      parts.pop()
      continue
    }
    parts.push(segment)
  }
  return parts.join('/')
}

function requirePath(path: string): void {
  if (!path) throw new Error('missing path argument')
}

function decodeContent(payload: Record<string, unknown>, encoding: string): Buffer {
  const raw = text(payload.content) ?? text(payload.body) ?? ''
  if (encoding === 'base64') return Buffer.from(raw, 'base64')
  return Buffer.from(raw, 'utf8')
}

function encodeContent(data: Buffer, encoding: string): string {
  return encoding === 'base64' ? data.toString('base64') : data.toString('utf8')
}

function ensureParentDirs(store: Map<string, FileEntry>, path: string): void {
  const parts = path.split('/').slice(0, -1)
  let prefix = ''
  for (const part of parts) {
    prefix = prefix ? `${prefix}/${part}` : part
    ensureDir(store, prefix)
  }
}

function ensureDir(store: Map<string, FileEntry>, path: string): void {
  const now = Date.now()
  const existing = store.get(path)
  if (existing) {
    if (existing.kind !== 'dir') throw new Error(`A file exists at ${path}`)
    return
  }
  store.set(path, { kind: 'dir', data: Buffer.alloc(0), createdAt: now, updatedAt: now })
}

function removeEntry(store: Map<string, FileEntry>, path: string): void {
  store.delete(path)
  const prefix = `${path}/`
  for (const key of [...store.keys()]) {
    if (key.startsWith(prefix)) store.delete(key)
  }
}

function listEntries(store: Map<string, FileEntry>, path: string) {
  const prefix = path ? `${path}/` : ''
  const entries: Array<Record<string, unknown>> = []
  for (const [key, entry] of store) {
    if (path && key === path) continue
    if (!key.startsWith(prefix)) continue
    const rest = key.slice(prefix.length)
    if (!rest || rest.includes('/')) continue
    entries.push({
      name: rest,
      path: key,
      kind: entry.kind,
      is_dir: entry.kind === 'dir',
      bytes: entry.data.length,
      updated_at: entry.updatedAt,
    })
  }
  entries.sort((left, right) => String(left.name).localeCompare(String(right.name)))
  return entries
}
