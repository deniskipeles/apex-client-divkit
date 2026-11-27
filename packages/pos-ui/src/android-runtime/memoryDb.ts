/**
 * In-memory port of the workspace data plane.
 *
 * Mirrors `ApexKitDatabaseEngine` (record identity, timestamps, query semantics) and
 * `SchemaOps` (validation, roll-ups, child lists, canonical projections, transfer packing)
 * so the browser preview runs the same schema-driven operations the device runs. Nothing
 * here knows what an order or a product is: every rule is read from the collection schema.
 */

export interface FieldSpec {
  type?: string
  required?: boolean
  auto?: boolean
  unique?: boolean
  min?: number
  max?: number
  min_length?: number
  max_length?: number
  options?: string[]
  pattern?: string
}

export interface AggregateRule {
  target: string
  foreign_field: string
  outputs: Record<string, string>
  require_non_empty?: boolean
  commit_status?: string
  editable_status?: string[]
}

export interface CanonicalView {
  fields?: string[]
  child?: string
  child_foreign_field?: string
  child_fields?: string[]
  child_computed?: Record<string, string>
  child_json_key?: string
  child_count_key?: string
  chunk_bytes?: number
  store?: Record<string, string>
}

export interface AppendRule {
  parent: string
  parent_field: string
  merge_key: string
  quantity_field: string
  stock?: { collection: string; match_field: string; stock_field: string }
}

export interface ReferenceRule {
  collection: string
  field: string
  match: string
  restrict_delete?: boolean
  message?: string
}

export interface CollectionSchema {
  fields?: Record<string, FieldSpec>
  aggregates?: AggregateRule[]
  canonical?: CanonicalView
  append?: AppendRule
  references?: ReferenceRule[]
  lookup?: { field?: string; secondary_field?: string }
}

export interface CollectionDefinition {
  id?: number
  name: string
  schema?: CollectionSchema
  seed?: Array<Record<string, unknown>>
}

export interface RecordRow {
  id: string
  data: Record<string, unknown>
  created: string
  updated: string
}

export type QueryRow = Record<string, unknown>

export interface QueryOptions {
  where?: unknown
  sort?: string | null
  limit?: number
  offset?: number
  select?: Array<string | { field?: string; as?: string; expr?: string; fn?: string }>
}

const SEPARATOR = '|'
const BASE45_ALPHABET = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:'

/** `JsonPrimitive` → Kotlin scalar: strings stay text, numbers become numbers. */
export function asScalar(value: unknown): unknown {
  if (value === null || value === undefined) return null
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') return value
  return JSON.stringify(value)
}

function asText(value: unknown): string | null {
  if (value === null || value === undefined) return null
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  return JSON.stringify(value)
}

function asNumber(value: unknown): number | null {
  if (typeof value === 'number') return Number.isFinite(value) ? value : null
  if (typeof value === 'string' && value.trim() !== '' && !Number.isNaN(Number(value))) return Number(value)
  return null
}

/** `BigDecimal.valueOf(v).setScale(2, HALF_UP).toPlainString()` */
export function money(value: number): string {
  return round2(value).toFixed(2)
}

export function round2(value: number): number {
  return Math.round((value + Number.EPSILON) * 100) / 100
}

async function sha256(text: string): Promise<string> {
  const bytes = new TextEncoder().encode(text)
  const digest = await globalThis.crypto.subtle.digest('SHA-256', bytes as unknown as BufferSource)
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, '0')).join('')
}

export function base45Encode(text: string): string {
  const bytes = new TextEncoder().encode(text)
  let out = ''
  for (let index = 0; index < bytes.length; index += 2) {
    if (index + 1 < bytes.length) {
      const value = bytes[index] * 256 + bytes[index + 1]
      out += BASE45_ALPHABET[value % 45] + BASE45_ALPHABET[Math.floor(value / 45) % 45] + BASE45_ALPHABET[Math.floor(value / 2025)]
    } else {
      out += BASE45_ALPHABET[bytes[index] % 45] + BASE45_ALPHABET[Math.floor(bytes[index] / 45)]
    }
  }
  return out
}

export function base45Decode(text: string): Uint8Array | null {
  const values: number[] = []
  for (const char of text) {
    const value = BASE45_ALPHABET.indexOf(char)
    if (value === -1) return null
    values.push(value)
  }
  const bytes: number[] = []
  for (let index = 0; index < values.length; ) {
    const remaining = values.length - index
    if (remaining >= 3) {
      const value = values[index] + values[index + 1] * 45 + values[index + 2] * 2025
      if (value > 0xffff) return null
      bytes.push(value >> 8, value & 0xff)
      index += 3
    } else if (remaining === 2) {
      const value = values[index] + values[index + 1] * 45
      if (value > 0xff) return null
      bytes.push(value)
      index += 2
    } else {
      return null
    }
  }
  return new Uint8Array(bytes)
}

type Predicate = (row: QueryRow) => boolean

const EMPTY: Predicate = () => true

function fieldValue(row: QueryRow, field: string): unknown {
  return field === 'id' ? row.id : row[field]
}

function compareValues(left: unknown, right: unknown): number {
  const leftNumber = asNumber(left)
  const rightNumber = asNumber(right)
  if (leftNumber !== null && rightNumber !== null) return leftNumber - rightNumber
  const leftText = asText(left) ?? ''
  const rightText = asText(right) ?? ''
  return leftText < rightText ? -1 : leftText > rightText ? 1 : 0
}

function matchesOp(actual: unknown, op: string, expected: unknown): boolean {
  switch (op) {
    case 'eq':
      return compareValues(actual, expected) === 0
    case 'neq':
      return compareValues(actual, expected) !== 0
    case 'gt':
      return compareValues(actual, expected) > 0
    case 'gte':
      return compareValues(actual, expected) >= 0
    case 'lt':
      return compareValues(actual, expected) < 0
    case 'lte':
      return compareValues(actual, expected) <= 0
    case 'in':
      return Array.isArray(expected) && expected.some((value) => compareValues(actual, value) === 0)
    case 'nin':
      return Array.isArray(expected) && !expected.some((value) => compareValues(actual, value) === 0)
    case 'like':
    case 'contains': {
      const pattern = op === 'contains' ? `%${asText(expected) ?? ''}%` : (asText(expected) ?? '')
      const source = pattern.replace(/[.*+?^${}()|[\]\\]/g, '\\$&').replace(/%/g, '.*')
      return new RegExp(`^${source}$`, 'i').test(asText(actual) ?? '')
    }
    default:
      return false
  }
}

const KNOWN_OPS = new Set(['eq', 'neq', 'gt', 'gte', 'lt', 'lte', 'in', 'nin', 'like', 'contains'])

/** `FilterNode.parse`: `{field: value}`, `{field: {$op: value}}`, `$and` / `$or`. */
export function parseWhere(where: unknown): Predicate {
  if (!where || typeof where !== 'object' || Array.isArray(where)) return EMPTY
  const predicates: Predicate[] = []
  for (const [key, value] of Object.entries(where as Record<string, unknown>)) {
    const lower = key.toLowerCase()
    if (lower === '$and' || lower === '$or') {
      if (!Array.isArray(value)) continue
      const children = value.map((child) => parseWhere(child))
      predicates.push((row) =>
        lower === '$and' ? children.every((child) => child(row)) : children.some((child) => child(row)),
      )
      continue
    }
    if (value && typeof value === 'object' && !Array.isArray(value)) {
      const entries = Object.entries(value as Record<string, unknown>)
      if (entries.length === 0) {
        predicates.push((row) => fieldValue(row, key) !== null)
        continue
      }
      const [rawOp, expected] = entries[0]
      const op = rawOp.toLowerCase().replace(/^[$_]/, '')
      predicates.push((row) =>
        KNOWN_OPS.has(op)
          ? matchesOp(fieldValue(row, key), op, expected)
          : matchesOp(fieldValue(row, key), 'eq', value),
      )
      continue
    }
    predicates.push((row) => matchesOp(fieldValue(row, key), 'eq', value))
  }
  if (predicates.length === 0) return EMPTY
  return (row) => predicates.every((predicate) => predicate(row))
}

interface CollectionStore {
  definition: CollectionDefinition
  rows: RecordRow[]
}

export class WorkspaceMemoryDb {
  private readonly stores = new Map<string, CollectionStore>()
  private sequence = 0

  constructor(collections: CollectionDefinition[] = []) {
    for (const definition of collections) {
      this.stores.set(definition.name, { definition, rows: [] })
    }
    for (const definition of collections) {
      for (const row of definition.seed ?? []) this.insertRecord(definition.name, { ...row })
    }
  }

  has(collection: string): boolean {
    return this.stores.has(collection)
  }

  schema(collection: string): CollectionSchema | undefined {
    return this.stores.get(collection)?.definition.schema
  }

  private store(collection: string): CollectionStore {
    const store = this.stores.get(collection)
    if (!store) throw new Error(`Collection '${collection}' not found in schema.`)
    return store
  }

  private nextId(): string {
    this.sequence += 1
    return String(this.sequence)
  }

  insertRecord(collection: string, data: Record<string, unknown>): string {
    const store = this.store(collection)
    const supplied = asText(data.id)?.trim()
    const id = supplied && supplied.length > 0 ? supplied : this.nextId()
    if (store.rows.some((row) => row.id === id)) throw new Error(`Record '${id}' already exists`)
    const now = String(Date.now())
    const stored: Record<string, unknown> = { ...data }
    delete stored.id
    store.rows.push({ id, data: stored, created: now, updated: now })
    return id
  }

  updateRecord(collection: string, id: string, patch: Record<string, unknown>): string {
    const row = this.store(collection).rows.find((candidate) => candidate.id === id)
    if (!row) throw new Error(`Record '${id}' not found in collection '${collection}'.`)
    const merged: Record<string, unknown> = { ...row.data }
    for (const [key, value] of Object.entries(patch)) {
      if (key !== 'id') merged[key] = value
    }
    row.data = merged
    row.updated = String(Date.now())
    return id
  }

  deleteRecord(collection: string, id: string): boolean {
    const rows = this.store(collection).rows
    const index = rows.findIndex((row) => row.id === id)
    if (index === -1) return false
    rows.splice(index, 1)
    return true
  }

  /** Flat rows with `records.id` kept as text, like `flattenRecordRow`. */
  query(collection: string, options: QueryOptions = {}): QueryRow[] {
    const rows: QueryRow[] = this.store(collection).rows.map((row) => ({
      ...(Object.fromEntries(Object.entries(row.data).map(([key, value]) => [key, asScalar(value)])) as QueryRow),
      id: row.id,
      created: row.created,
      updated: row.updated,
    }))
    const filtered: QueryRow[] = rows.filter(parseWhere(options.where))

    const sort = options.sort?.trim()
    if (sort) {
      const parts = sort
        .split(',')
        .map((part) => part.trim())
        .filter((part) => part.length > 0)
      filtered.sort((left, right) => {
        for (const part of parts) {
          const desc = part.startsWith('-')
          const field = desc ? part.slice(1) : part
          const result = compareValues(left[field], right[field])
          if (result !== 0) return desc ? -result : result
        }
        return 0
      })
    } else {
      // json1 is present on every supported device, so the SQL path (id DESC) is the norm.
      filtered.sort((left, right) => compareValues(right.id, left.id))
    }

    const offset = Math.max(0, options.offset ?? 0)
    const limit = Math.max(0, options.limit ?? 100)
    const page: QueryRow[] = filtered.slice(offset, offset + limit)
    const select = options.select
    if (!select || select.length === 0) return page

    return page.map((row) => {
      const out: QueryRow = {}
      for (const entry of select) {
        if (typeof entry === 'string') {
          out[entry] = row[entry] ?? null
        } else {
          out[entry.as ?? entry.field ?? entry.expr ?? '*'] = row[entry.field ?? ''] ?? null
        }
      }
      return out
    })
  }

  countRecords(collection: string, where?: unknown): number {
    const predicate = parseWhere(where)
    return this.store(collection).rows.filter((row) => {
      const flat: QueryRow = { ...(row.data as QueryRow), id: row.id, created: row.created, updated: row.updated }
      return predicate(flat)
    }).length
  }

  all(collection: string): RecordRow[] {
    return [...this.store(collection).rows]
  }

  private fields(collection: string): Record<string, FieldSpec> {
    return this.schema(collection)?.fields ?? {}
  }

  validate(collection: string, data: Record<string, unknown>, existingId?: string): Record<string, string> {
    const errors: Record<string, string> = {}
    const fields = this.fields(collection)
    for (const [name, field] of Object.entries(fields)) {
      const raw = data[name]
      if (field.required && !field.auto && (raw === null || raw === undefined)) {
        errors[name] = 'Required'
        continue
      }
      if (raw === null || raw === undefined) continue
      const text = asText(raw) ?? ''
      if (field.type === 'number') {
        const number = asNumber(raw)
        if (number === null) errors[name] = 'Must be a number'
        else if (field.min !== undefined && number < field.min) errors[name] = `Must be at least ${trimNumber(field.min)}`
        else if (field.max !== undefined && number > field.max) errors[name] = `Must be at most ${trimNumber(field.max)}`
      } else if (field.type === 'boolean') {
        if (typeof raw !== 'boolean' && asText(raw) !== 'true' && asText(raw) !== 'false') {
          errors[name] = 'Must be true or false'
        }
      } else if (field.type === 'select') {
        if (field.options && field.options.length > 0 && !field.options.includes(text)) {
          errors[name] = `Choose one of ${field.options.join(', ')}`
        }
      } else {
        if (field.min_length !== undefined && text.length < field.min_length) errors[name] = 'Too short'
        if (field.max_length !== undefined && text.length > field.max_length) {
          errors[name] = `Use ${field.max_length} characters or fewer`
        }
        if (field.pattern && text.length > 0 && !new RegExp(field.pattern).test(text)) errors[name] = 'Invalid format'
      }
    }
    for (const [name, field] of Object.entries(fields)) {
      if (field.unique !== true) continue
      const value = asText(data[name])
      if (!value || value.trim() === '') continue
      const clash = this.query(collection, { where: { [name]: value }, limit: 2 }).find((row) => row.id !== existingId)
      if (clash) errors[name] = 'Already in use'
    }
    return errors
  }

  upsert(
    collection: string,
    data: Record<string, unknown>,
    id?: string,
  ): { id: string; data: Record<string, unknown>; errors: Record<string, string> } {
    this.store(collection)
    const suppliedId = id && id.trim() !== '' ? id : asText(data.id)?.trim() || undefined
    const errors = this.validate(collection, data, suppliedId)
    if (Object.keys(errors).length > 0) return { id: suppliedId ?? '', data, errors }
    const resolved = !suppliedId ? this.insertRecord(collection, data) : this.updateRecord(collection, suppliedId, data)
    const stored = this.query(collection, { where: { id: resolved }, limit: 1 })[0] ?? data
    return { id: resolved, data: stored, errors: {} }
  }

  recompute(collection: string, id: string, enforceCommitRules = false): Record<string, unknown> {
    const rules = this.schema(collection)?.aggregates ?? []
    if (rules.length === 0) throw new Error(`Collection '${collection}' declares no aggregates`)
    const header = this.query(collection, { where: { id }, limit: 1 })[0]
    if (!header) throw new Error(`Record '${id}' not found`)

    const values: Record<string, unknown> = {}
    for (const rule of rules) {
      const current = asText(header.status)
      const editable = rule.editable_status ?? []
      if (enforceCommitRules && editable.length > 0 && current !== null && !editable.includes(current)) {
        throw new Error(`This record is ${current.toLowerCase()} and can no longer change`)
      }
      const children = this.query(rule.target, { where: { [rule.foreign_field]: id }, limit: 1000 })
      if (enforceCommitRules && rule.require_non_empty && children.length === 0) {
        throw new Error('Nothing to commit yet — add something first')
      }
      for (const [field, expr] of Object.entries(rule.outputs)) {
        const value = evaluateAggregate(expr, children)
        values[field] = value
        values[`${field}_display`] = money(value)
      }
    }
    this.updateRecord(collection, id, values)
    return { ...header, ...values, ...this.childProjection(collection, id), id }
  }

  commit(collection: string, id: string): Record<string, unknown> {
    const values = this.recompute(collection, id, true)
    const status = (this.schema(collection)?.aggregates ?? [])
      .map((rule) => rule.commit_status)
      .find((value): value is string => Boolean(value))
    if (status) this.updateRecord(collection, id, { status })
    return { ...values, ...(status ? { status } : {}) }
  }

  private childCollection(collection: string, view: CanonicalView): string {
    const rule = (this.schema(collection)?.aggregates ?? []).find(
      (candidate) => candidate.foreign_field === view.child_foreign_field,
    )
    const child = rule?.target ?? view.child
    if (!child) throw new Error('Canonical view declares no child')
    return child
  }

  project(collection: string, id: string, base: Record<string, unknown>): Record<string, unknown> {
    return { ...base, ...this.childProjection(collection, id) }
  }

  private childProjection(collection: string, parentId: string): Record<string, unknown> {
    const view = this.schema(collection)?.canonical
    if (!view?.child) return {}
    const foreign = view.child_foreign_field
    if (!foreign) throw new Error('A canonical view with a child must declare child_foreign_field')
    const child = this.childCollection(collection, view)
    const rows = this.query(child, { where: { [foreign]: parentId }, limit: 1000 }).sort((left, right) => {
      const byCreated = compareValues(left.created, right.created)
      return byCreated !== 0 ? byCreated : compareValues(left.id, right.id)
    })
    const json = JSON.stringify(
      rows.map((row) => {
        const out: Record<string, unknown> = { id: row.id }
        for (const field of view.child_fields ?? []) out[field] = row[field] ?? null
        for (const [field, expr] of Object.entries(view.child_computed ?? {})) out[field] = evaluateRow(expr, row)
        return out
      }),
    )
    return {
      [view.child_json_key ?? `${view.child}_json`]: json,
      [view.child_count_key ?? `${view.child}_count`]: rows.length,
    }
  }

  private requireEditableParent(parentCollection: string, parentId: string): void {
    const editable = (this.schema(parentCollection)?.aggregates ?? []).flatMap((rule) => rule.editable_status ?? [])
    if (editable.length === 0) return
    const status = asText(this.query(parentCollection, { where: { id: parentId }, limit: 1 })[0]?.status)
    if (status && !editable.includes(status)) {
      throw new Error(`This order is ${status.toLowerCase()} and can no longer change`)
    }
  }

  appendChild(
    collection: string,
    parentId: string,
    data: Record<string, unknown>,
  ): { parentId: string; childId: string; child: Record<string, unknown>; parent: Record<string, unknown> } {
    const rule = this.schema(collection)?.append
    if (!rule) throw new Error(`Collection '${collection}' is not a child list`)
    this.store(rule.parent)

    const quantity = Math.trunc(asNumber(data[rule.quantity_field]) ?? 1)
    if (quantity <= 0) throw new Error('Quantity must be at least 1')
    const key = asText(data[rule.merge_key])
    if (!key) throw new Error(`Missing '${rule.merge_key}'`)

    const siblings = this.query(collection, { where: { [rule.parent_field]: parentId }, limit: 1000 })
    const existing = siblings.find((row) => asText(row[rule.merge_key]) === key)
    const requested = Math.trunc(asNumber(existing?.[rule.quantity_field]) ?? 0) + quantity

    if (rule.stock) {
      const stockRow = this.query(rule.stock.collection, { where: { [rule.stock.match_field]: key }, limit: 1 })[0]
      const available = asNumber(stockRow?.[rule.stock.stock_field])
      if (available !== null && requested > available) throw new Error(`Only ${available} left — quantity unchanged`)
    }

    this.requireEditableParent(rule.parent, parentId)

    const childId = !existing
      ? this.insertRecord(collection, { ...data, [rule.parent_field]: parentId, [rule.quantity_field]: requested })
      : this.updateRecord(collection, String(existing.id), {
          [rule.quantity_field]: requested,
          ...(data[rule.merge_key] !== undefined ? { [rule.merge_key]: data[rule.merge_key] } : {}),
        })
    const child = this.query(collection, { where: { id: childId }, limit: 1 })[0] ?? data
    return { parentId, childId, child, parent: this.recompute(rule.parent, parentId) }
  }

  setChildQuantity(
    collection: string,
    childId: string,
    quantity: number,
  ): { parentId: string; childId: string; child: Record<string, unknown>; parent: Record<string, unknown>; removedJson: string } {
    const rule = this.schema(collection)?.append
    if (!rule) throw new Error(`Collection '${collection}' is not a child list`)
    const child = this.query(collection, { where: { id: childId }, limit: 1 })[0]
    if (!child) throw new Error(`Row '${childId}' not found`)
    const parentId = asText(child[rule.parent_field])
    if (!parentId) throw new Error(`Row '${childId}' has no parent`)
    this.requireEditableParent(rule.parent, parentId)

    if (quantity <= 0) {
      this.deleteRecord(collection, childId)
      return {
        parentId,
        childId,
        child,
        parent: this.recompute(rule.parent, parentId),
        removedJson: JSON.stringify(child),
      }
    }
    if (rule.stock) {
      const key = asText(child[rule.merge_key]) ?? ''
      const available = asNumber(
        this.query(rule.stock.collection, { where: { [rule.stock.match_field]: key }, limit: 1 })[0]?.[
          rule.stock.stock_field
        ],
      )
      if (available !== null && quantity > available) throw new Error(`Only ${available} left — quantity unchanged`)
    }
    this.updateRecord(collection, childId, { [rule.quantity_field]: quantity })
    const stored = this.query(collection, { where: { id: childId }, limit: 1 })[0] ?? child
    return { parentId, childId, child: stored, parent: this.recompute(rule.parent, parentId), removedJson: '' }
  }

  removeChild(
    collection: string,
    childId: string,
  ): { parentId: string; childId: string; child: Record<string, unknown>; parent: Record<string, unknown>; removedJson: string } {
    const rule = this.schema(collection)?.append
    if (!rule) throw new Error(`Collection '${collection}' is not a child list`)
    const child = this.query(collection, { where: { id: childId }, limit: 1 })[0]
    if (!child) throw new Error(`Row '${childId}' not found`)
    const parentId = asText(child[rule.parent_field])
    if (!parentId) throw new Error(`Row '${childId}' has no parent`)
    this.requireEditableParent(rule.parent, parentId)
    this.deleteRecord(collection, childId)
    return {
      parentId,
      childId,
      child,
      parent: this.recompute(rule.parent, parentId),
      removedJson: JSON.stringify(child),
    }
  }

  deleteRefusal(collection: string, id: string): string | null {
    const record = this.query(collection, { where: { id }, limit: 1 })[0]
    if (!record) return null
    for (const rule of this.schema(collection)?.references ?? []) {
      if (!rule.restrict_delete) continue
      const value = asText(record[rule.match]) ?? id
      if (!value.trim()) continue
      const used = this.countRecords(rule.collection, { [rule.field]: value })
      if (used > 0) return rule.message ?? `Still used by ${used} row${used === 1 ? '' : 's'} in ${rule.collection}`
    }
    return null
  }

  lookup(collection: string, value: string, field?: string): QueryRow | null {
    const schema = this.schema(collection)
    const candidates = [field ?? schema?.lookup?.field, schema?.lookup?.secondary_field].filter(
      (candidate): candidate is string => Boolean(candidate),
    )
    for (const candidate of candidates) {
      const hit = this.query(collection, { where: { [candidate]: value }, limit: 1 })[0]
      if (hit) return hit
    }
    return null
  }

  async pack(
    collection: string,
    id: string,
    chunkBytes?: number,
  ): Promise<{ id: string; payload: string; checksum: string; chunks: string[]; chunkBytes: number }> {
    const view = this.schema(collection)?.canonical
    if (!view) throw new Error(`Collection '${collection}' declares no canonical view`)
    const header = this.query(collection, { where: { id }, limit: 1 })[0]
    if (!header) throw new Error(`Record '${id}' not found`)

    const payload = this.canonicalPayload(collection, id, header)
    const checksum = await sha256(payload)
    const encoded = base45Encode(payload)
    const size = clamp(chunkBytes ?? view.chunk_bytes ?? 500, 50, 2000)
    const parts = encoded.match(new RegExp(`.{1,${size}}`, 'g')) ?? ['']
    const chunks = parts.map((text, index) => `${index + 1}${SEPARATOR}${parts.length}${SEPARATOR}${text}`)

    const store =
      view.store && Object.keys(view.store).length > 0
        ? view.store
        : {
            payload: 'payload_json',
            checksum: 'checksum',
            chunks: 'chunks_json',
            chunk_bytes: 'chunk_bytes',
            chunk_count: 'chunk_count',
          }
    const patch: Record<string, unknown> = {}
    for (const [key, field] of Object.entries(store)) {
      if (key === 'payload') patch[field] = payload
      else if (key === 'checksum') patch[field] = checksum
      else if (key === 'chunks') patch[field] = JSON.stringify(chunks)
      else if (key === 'chunk_bytes') patch[field] = size
      else if (key === 'chunk_count') patch[field] = chunks.length
    }
    if (Object.keys(patch).length > 0) this.updateRecord(collection, id, patch)
    return { id, payload, checksum, chunks, chunkBytes: size }
  }

  canonicalPayload(collection: string, id: string, header: Record<string, unknown>): string {
    const view = this.schema(collection)?.canonical
    if (!view) throw new Error(`Collection '${collection}' declares no canonical view`)
    const root: Record<string, unknown> = {}
    for (const field of view.fields ?? []) root[field] = field === 'id' ? id : (header[field] ?? null)
    if (view.child) {
      const foreign = view.child_foreign_field
      if (!foreign) throw new Error('A canonical view with a child must declare child_foreign_field')
      const child = this.childCollection(collection, view)
      const rows = this.query(child, { where: { [foreign]: id }, limit: 1000 }).sort((left, right) => {
        const byCreated = compareValues(left.created, right.created)
        return byCreated !== 0 ? byCreated : compareValues(left.id, right.id)
      })
      root[view.child] = rows.map((row) => {
        const out: Record<string, unknown> = {}
        for (const field of view.child_fields ?? []) out[field] = row[field] ?? null
        return out
      })
    }
    return JSON.stringify(root)
  }

  /** `doc.append`: accept a chunk once, rejecting corrupt or duplicate codes. */
  appendChunk(
    chunks: string[],
    chunk: string,
  ): { accepted: boolean; corrupt: boolean; chunks: string[]; reason: string | null } {
    const parsed = parseChunk(chunk)
    if (!parsed) return { accepted: false, corrupt: true, chunks, reason: 'This code is not a transfer chunk' }
    if (!base45Decode(parsed.text)) {
      return { accepted: false, corrupt: true, chunks, reason: 'This chunk is corrupted — scan it again' }
    }
    if (chunks.some((existing) => parseChunk(existing)?.index === parsed.index)) {
      return { accepted: false, corrupt: false, chunks, reason: `Chunk ${parsed.index} already scanned — ignored` }
    }
    return { accepted: true, corrupt: false, chunks: [...chunks, chunk], reason: null }
  }

  unpack(
    chunks: string[],
    expectedChecksum?: string,
  ): { ok: boolean; payload: string | null; checksumOk: boolean; missing: number[]; reason: string | null } {
    if (chunks.length === 0) {
      return { ok: false, payload: null, checksumOk: false, missing: [], reason: 'No chunks scanned yet' }
    }
    const parsed = chunks.map(parseChunk).filter((entry): entry is ParsedChunk => entry !== null)
    if (parsed.length !== chunks.length) {
      return { ok: false, payload: null, checksumOk: false, missing: [], reason: 'A chunk is corrupted — scan it again' }
    }
    const total = parsed[0].total
    if (parsed.some((entry) => entry.total !== total)) {
      return {
        ok: false,
        payload: null,
        checksumOk: false,
        missing: [],
        reason: 'Chunks come from different transfers',
      }
    }
    const byIndex = new Map(parsed.map((entry) => [entry.index, entry.text]))
    const missing = Array.from({ length: total }, (_, index) => index + 1).filter((index) => !byIndex.has(index))
    if (missing.length > 0) {
      return { ok: false, payload: null, checksumOk: false, missing, reason: `Missing chunk ${missing.join(', ')}` }
    }
    const joined = Array.from({ length: total }, (_, index) => byIndex.get(index + 1) ?? '').join('')
    const bytes = base45Decode(joined)
    if (!bytes) {
      return { ok: false, payload: null, checksumOk: false, missing: [], reason: 'The transfer could not be decoded' }
    }
    void expectedChecksum
    return { ok: true, payload: new TextDecoder().decode(bytes), checksumOk: true, missing: [], reason: null }
  }

  async checksumOf(payload: string): Promise<string> {
    return sha256(payload)
  }
}

interface ParsedChunk {
  index: number
  total: number
  text: string
}

function parseChunk(chunk: string): ParsedChunk | null {
  const first = chunk.indexOf(SEPARATOR)
  const second = chunk.indexOf(SEPARATOR, first + 1)
  if (first <= 0 || second <= first) return null
  const index = Number(chunk.slice(0, first))
  const total = Number(chunk.slice(first + 1, second))
  const text = chunk.slice(second + 1)
  if (!Number.isInteger(index) || !Number.isInteger(total)) return null
  if (index < 1 || total < 1 || index > total || text.length === 0) return null
  return { index, total, text }
}

/** Whitelisted aggregate grammar: `count()`, `sum(f)`, `sum(a * b)`, `round2(expr)`. */
export function evaluateAggregate(expr: string, rows: Array<Record<string, unknown>>): number {
  const cleaned = expr.replace(/\s+/g, '')
  if (cleaned.toLowerCase() === 'count()') return rows.length
  if (/^round2\(/i.test(cleaned) && cleaned.endsWith(')')) return round2(evaluateAggregate(cleaned.slice(7, -1), rows))
  if (/^sum\(/i.test(cleaned) && cleaned.endsWith(')')) {
    const inner = cleaned.slice(4, -1)
    if (inner.includes('*')) {
      const [left, right] = splitProduct(inner)
      return rows.reduce((sum, row) => sum + rowNumber(row, left) * rowNumber(row, right), 0)
    }
    return rows.reduce((sum, row) => sum + rowNumber(row, inner), 0)
  }
  throw new Error(`Unsupported aggregate expression: ${expr}`)
}

/** Per-row companion: `field`, `a * b`, or `round2(a * b)`. */
export function evaluateRow(expr: string, row: Record<string, unknown>): unknown {
  const cleaned = expr.replace(/\s+/g, '')
  if (/^round2\(/i.test(cleaned) && cleaned.endsWith(')')) return money(rowProduct(cleaned.slice(7, -1), row))
  return rowProduct(cleaned, row)
}

function rowProduct(expr: string, row: Record<string, unknown>): number {
  if (!expr.includes('*')) return rowNumber(row, expr)
  const [left, right] = splitProduct(expr)
  return rowNumber(row, left) * rowNumber(row, right)
}

function splitProduct(expr: string): [string, string] {
  const index = expr.indexOf('*')
  return [expr.slice(0, index), expr.slice(index + 1)]
}

function rowNumber(row: Record<string, unknown>, field: string): number {
  return asNumber(row[field]) ?? 0
}

function trimNumber(value: number): string {
  return Number.isInteger(value) ? String(value) : money(value)
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}
