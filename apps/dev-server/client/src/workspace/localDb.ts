import { dispatchOwnerPosOperation } from './ownerPosOps.ts'
import {
  display,
  isValidVariableName,
  type DivValue,
  type DivVars,
  type QueryRow,
  type WorkspaceMemoryDb,
} from '@apex/pos-ui/android-runtime'

// ---------------------------------------------------------------------------
// `local-db://op/<name>` — the workspace data plane in the preview.
//
// The registry below mirrors `EngineRegistry`'s operation whitelist and
// `EngineRouter.emitResult`'s publication rules, so a card that writes and reads
// its own records behaves the same here as on the device.
// ---------------------------------------------------------------------------

export interface LocalDbReport {
  status: 'ok' | 'failed' | 'unavailable'
  message?: string
  updates: Record<string, DivValue>
  data?: Record<string, unknown>
}

type DbResult =
  | { kind: 'rows'; rows: QueryRow[] }
  | { kind: 'row'; row: QueryRow | null }
  | { kind: 'rowId'; id: string }
  | { kind: 'count'; n: number }
  | { kind: 'schemaImported'; count: number }
  | { kind: 'scalar'; value: unknown }
  | { kind: 'empty' }

export async function dispatchLocalDb(
  url: string,
  payload: Record<string, unknown>,
  variables: DivVars,
  db: WorkspaceMemoryDb | null,
): Promise<LocalDbReport> {
  if (!db) {
    return { status: 'unavailable', message: 'no workspace database is loaded', updates: {} }
  }
  const operation = url.slice('local-db://op/'.length).trim()
  try {
    const custom = await dispatchOwnerPosOperation(operation, payload, db, variables as Record<string, unknown>)
    if (custom) {
      return {
        status: 'ok',
        updates: { ...(custom.updates as Record<string, DivValue>), 'user.engine_error': '' },
        data: custom.data as Record<string, unknown> | undefined,
      }
    }
    const result = await runOperation(operation, payload, db)
    const updates = emitResult(result, payload, variables)
    updates['user.engine_error'] = ''
    return { status: 'ok', updates, data: result.kind === 'row' ? (result.row ?? {}) : undefined }
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    const updates: Record<string, DivValue> = { 'user.engine_error': message }
    const errorVariable = asString(payload.error_variable)
    if (errorVariable && isValidVariableName(errorVariable)) updates[errorVariable] = message
    return { status: 'failed', message, updates }
  }
}

async function runOperation(operation: string, payload: Record<string, unknown>, db: WorkspaceMemoryDb): Promise<DbResult> {
  const collection =
    asString(payload.collection) ?? asString(payload.table) ?? asString(payload.from) ?? ''
  switch (operation) {
    case 'db.import_schema':
    case 'schema.import': {
      const raw = payload.schema && typeof payload.schema === 'object' ? payload.schema : payload
      const pkg = raw as { collections?: unknown }
      if (!Array.isArray(pkg.collections)) throw new Error("Missing 'collections' array in schema package")
      return { kind: 'schemaImported', count: db.importSchema(pkg as Parameters<typeof db.importSchema>[0]) }
    }
    case 'db.list_collections':
    case 'schema.collections': {
      return { kind: 'rows', rows: db.listCollections() as QueryRow[] }
    }
    case 'db.insert':
    case 'records.create': {
      requireName(collection, 'collection')
      const data = asObject(payload.data) ?? asObject(payload.values)
      if (!data) throw new Error("Missing 'data' or 'values' object")
      return { kind: 'rowId', id: db.insertRecord(collection, data) }
    }
    case 'db.update':
    case 'records.update': {
      requireName(collection, 'collection')
      const id = asString(payload.id) ?? asString(payload.record_id)
      if (!id) throw new Error("Missing 'id' parameter")
      const data = asObject(payload.data) ?? asObject(payload.values)
      if (!data) throw new Error("Missing 'data' or 'values' object")
      return { kind: 'rowId', id: db.updateRecord(collection, id, data) }
    }
    case 'db.upsert':
    case 'records.upsert': {
      requireName(collection, 'collection')
      const data = asObject(payload.data) ?? asObject(payload.values)
      if (!data) throw new Error("Missing 'data' parameter")
      const result = db.upsert(collection, data, asString(payload.id) ?? undefined)
      const rollUps = (db.schema(collection)?.aggregates ?? []).length > 0 && Object.keys(result.errors).length === 0
        ? db.recompute(collection, result.id)
        : {}
      return {
        kind: 'row',
        row: {
          ...(result.data as Record<string, unknown>),
          ...rollUps,
          id: result.id,
          ok: Object.keys(result.errors).length === 0,
          errors_json: JSON.stringify(result.errors),
          error_count: Object.keys(result.errors).length,
        },
      }
    }
    case 'db.query':
    case 'records.query': {
      requireName(collection, 'collection')
      const limit = asNumber(payload.limit) ?? 100
      const offset = asNumber(payload.offset) ?? 0
      const rows = db.query(collection, {
        where: payload.where ?? payload.filter,
        sort: asString(payload.sort) ?? asString(payload.order_by),
        limit,
        offset,
        select: Array.isArray(payload.select) ? (payload.select as Array<string | { field?: string; as?: string }>) : undefined,
      })
      const projected = rows.map((row) => {
        const id = asString(row.id)
        return id ? db.project(collection, id, row) : row
      })
      if (payload.single === true) {
        const first = projected[0]
        if (!first) throw new Error(`No saved ${collection} yet`)
        return { kind: 'row', row: first }
      }
      return { kind: 'rows', rows: projected }
    }
    case 'db.query_one':
    case 'records.get': {
      requireName(collection, 'collection')
      const rows = db.query(collection, { where: payload.where, limit: 1 })
      const first = rows[0]
      return { kind: 'row', row: first ? db.project(collection, asString(first.id) ?? '', first) : null }
    }
    case 'db.count':
    case 'records.count': {
      requireName(collection, 'collection')
      return { kind: 'count', n: db.countRecords(collection, payload.where) }
    }
    case 'db.delete':
    case 'records.delete': {
      requireName(collection, 'collection')
      const id = asString(payload.id) ?? asString(payload.record_id)
      if (!id) throw new Error("Missing 'id' parameter for delete operation")
      const refusal = db.deleteRefusal(collection, id)
      if (refusal) throw new Error(refusal)
      return { kind: 'count', n: db.deleteRecord(collection, id) ? 1 : 0 }
    }
    case 'db.append_child': {
      requireName(collection, 'collection')
      const parentId = asString(payload.parent_id)
      if (!parentId) throw new Error("Missing 'parent_id' parameter")
      const data = asObject(payload.data) ?? asObject(payload.values)
      if (!data) throw new Error("Missing 'data' parameter")
      const result = db.appendChild(collection, parentId, data)
      return {
        kind: 'row',
        row: { ...(result.child as Record<string, unknown>), ...result.parent, parent_id: result.parentId, child_id: result.childId },
      }
    }
    case 'db.set_child_qty': {
      requireName(collection, 'collection')
      const id = asString(payload.id)
      if (!id) throw new Error("Missing 'id' parameter")
      const quantity = asNumber(payload.quantity)
      if (quantity === null) throw new Error("Missing 'quantity' parameter")
      const result = db.setChildQuantity(collection, id, Math.trunc(quantity))
      return {
        kind: 'row',
        row: {
          ...(result.child as Record<string, unknown>),
          ...result.parent,
          parent_id: result.parentId,
          child_id: id,
          undo_json: result.removedJson,
        },
      }
    }
    case 'db.remove_child': {
      requireName(collection, 'collection')
      const id = asString(payload.id)
      if (!id) throw new Error("Missing 'id' parameter")
      const result = db.removeChild(collection, id)
      return {
        kind: 'row',
        row: { ...(result.child as Record<string, unknown>), ...result.parent, parent_id: result.parentId, undo_json: result.removedJson },
      }
    }
    case 'db.commit': {
      requireName(collection, 'collection')
      const id = asString(payload.id) ?? asString(payload.record_id)
      if (!id) throw new Error("Missing 'id' parameter")
      return { kind: 'row', row: db.commit(collection, id) }
    }
    case 'doc.pack':
    case 'doc.chunk': {
      requireName(collection, 'collection')
      const id = asString(payload.id) ?? asString(payload.record_id)
      if (!id) throw new Error("Missing 'id' parameter")
      const packed = await db.pack(collection, id, asNumber(payload.chunk_bytes) ?? undefined)
      return {
        kind: 'row',
        row: {
          id: packed.id,
          payload_json: packed.payload,
          checksum: packed.checksum,
          chunks_json: JSON.stringify(packed.chunks),
          chunk_count: packed.chunks.length,
          chunk_bytes: packed.chunkBytes,
        },
      }
    }
    case 'doc.append': {
      const chunks = asChunks(payload)
      const chunk = asString(payload.chunk)
      if (!chunk) throw new Error("Missing 'chunk' parameter")
      const result = db.appendChunk(chunks, chunk)
      return {
        kind: 'row',
        row: {
          accepted: result.accepted,
          corrupt: result.corrupt,
          reason: result.reason ?? '',
          chunks_json: JSON.stringify(result.chunks),
          chunk_count: result.chunks.length,
        },
      }
    }
    case 'doc.unpack':
    case 'doc.assemble': {
      const chunks = asChunks(payload)
      const unpacked = db.unpack(chunks, asString(payload.checksum) ?? undefined)
      const checksumOk = unpacked.payload
        ? await db.checksumOf(unpacked.payload).then((digest) => {
            const expected = asString(payload.checksum)
            return !expected || digest.toLowerCase() === expected.toLowerCase()
          })
        : false
      const ok = unpacked.ok && checksumOk
      return {
        kind: 'row',
        row: {
          ok,
          payload_json: ok ? unpacked.payload : '',
          checksum_ok: checksumOk,
          missing_json: JSON.stringify(unpacked.missing),
          reason: ok ? '' : (unpacked.reason ?? 'Checksum mismatch — ask for a fresh code'),
        },
      }
    }
    case 'lookup.resolve':
    case 'records.resolve': {
      requireName(collection, 'collection')
      const value = asString(payload.value) ?? asString(payload.raw)
      if (!value) throw new Error("Missing 'value' parameter")
      const hit = db.lookup(collection, value, asString(payload.field) ?? undefined)
      return {
        kind: 'row',
        row: hit
          ? { ...(hit as Record<string, unknown>), ok: true, value, record_json: JSON.stringify(hit) }
          : { ok: false, value, reason: `No record matches ${value}` },
      }
    }
    default:
      throw new Error(`Unknown operation: ${operation}`)
  }
}

/** `EngineRouter.emitResult`: rows → `_count` / `*` / per-column arrays. */
function emitResult(result: DbResult, payload: Record<string, unknown>, variables: DivVars): Record<string, DivValue> {
  const updates: Record<string, DivValue> = {}
  const output = asObject(payload.output)
  const set = (name: string, value: DivValue) => {
    if (isValidVariableName(name)) updates[name] = value
  }

  switch (result.kind) {
    case 'rows': {
      const json = JSON.stringify(result.rows)
      const listVar = asString(payload.json_list_var)
      if (listVar) set(listVar, json)
      for (const [column, target] of Object.entries(output ?? {})) {
        const name = asString(target)
        if (!name) continue
        if (column === '_count') {
          set(name, result.rows.length)
          continue
        }
        if (column === '*' || column === '_json') {
          set(name, json)
          continue
        }
        set(
          name,
          result.rows.map((row) => display(row[column] ?? '')),
        )
      }
      return updates
    }
    case 'row': {
      for (const [column, target] of Object.entries(output ?? {})) {
        const name = asString(target)
        if (!name || !result.row || !(column in result.row)) continue
        set(name, toDivValue(result.row[column]))
      }
      return updates
    }
    case 'rowId': {
      const target = asString(output?.id)
      if (target) set(target, result.id)
      return updates
    }
    case 'count': {
      const target = asString(output?.count)
      if (target) set(target, result.n)
      return updates
    }
    case 'schemaImported': {
      const target = asString(output?.collections)
      if (target) set(target, result.count)
      return updates
    }
    case 'scalar': {
      const target = asString(output?.value)
      if (target) set(target, toDivValue(result.value))
      return updates
    }
    default:
      void variables
      return updates
  }
}

function toDivValue(value: unknown): DivValue {
  if (value === null || value === undefined) return ''
  if (typeof value === 'boolean' || typeof value === 'number') return value
  if (Array.isArray(value)) return value.map((entry) => display(entry))
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

function asString(value: unknown): string | null {
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  return null
}

function asNumber(value: unknown): number | null {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value === 'string' && value.trim() !== '' && !Number.isNaN(Number(value))) return Number(value)
  return null
}

function asObject(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : null
}

function asChunks(payload: Record<string, unknown>): string[] {
  const raw = payload.chunks_json ?? payload.chunks
  if (Array.isArray(raw)) return raw.map((entry) => asString(entry) ?? '').filter((entry) => entry.length > 0)
  if (typeof raw === 'string') {
    try {
      const parsed = JSON.parse(raw)
      return Array.isArray(parsed) ? parsed.map((entry) => String(entry)) : []
    } catch {
      return []
    }
  }
  return []
}

function requireName(value: string, label: string): void {
  if (!value) throw new Error(`Missing '${label}' parameter`)
}
