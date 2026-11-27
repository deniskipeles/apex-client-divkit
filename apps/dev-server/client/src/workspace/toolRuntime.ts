import {
  display,
  resolveJsonExpressions,
  type DivValue,
  type DivVars,
  type WorkspaceMemoryDb,
} from '@apex/pos-ui/android-runtime'
import { dispatchLocalDb } from './localDb'

// ---------------------------------------------------------------------------
// Playground tool dispatch.
//
// The preview only executes what it can execute for real. A capability without a
// browser implementation is reported as `unavailable` — it never invents a result,
// because a fabricated success is exactly the failure mode a preview must not have.
//
// Implemented today: `data.codec@1` (the whole codec family), which the kiosk
// transfer flow uses. Everything else (SQLite, camera, printing, LAN sockets) is
// the next milestone and is surfaced in the UI as "not run".
// ---------------------------------------------------------------------------

export type ToolRuntimeStatus = 'ok' | 'failed' | 'unavailable'

export interface ToolRuntimeReport {
  url: string
  status: ToolRuntimeStatus
  message?: string
  tool?: string
  operation?: string
  data?: Record<string, unknown>
  updates: Record<string, DivValue>
}

export interface ToolRuntimeContext {
  collections?: Record<string, unknown>
  workspace?: { slug: string }
  /** The workspace's in-memory SQLite mirror (`local-db://`). */
  db?: WorkspaceMemoryDb | null
}

export async function dispatchWorkspaceUrl(
  url: string,
  payload: unknown,
  variables: DivVars,
  context: ToolRuntimeContext = {},
): Promise<ToolRuntimeReport> {
  const body = (payload && typeof payload === 'object' ? payload : {}) as Record<string, unknown>
  const resolved = resolveJsonExpressions(body, variables, {}) as Record<string, unknown>

  if (url.startsWith('tools://')) {
    const rest = url.slice('tools://'.length)
    const slash = rest.indexOf('/')
    const tool = slash === -1 ? rest : rest.slice(0, slash)
    const operation = slash === -1 ? '' : rest.slice(slash + 1)
    return runTool(tool, operation, resolved, url, context)
  }

  if (url.startsWith('div-action://terminal.tool.invoke')) {
    const query = new URLSearchParams(url.slice(url.indexOf('?') + 1))
    const tool = query.get('tool') ?? ''
    const operation = query.get('op') ?? ''
    return runTool(tool, operation, resolved, url, context)
  }

  if (url.startsWith('local-db://')) {
    const report = await dispatchLocalDb(url, resolved, variables, context.db ?? null)
    return {
      url,
      tool: 'local-db',
      operation: url.slice('local-db://op/'.length),
      status: report.status,
      message: report.message,
      data: report.data,
      updates: report.updates,
    }
  }

  return { url, status: 'unavailable', message: 'no handler for this scheme', updates: {} }
}

const CODEC_ALIASES = new Set(['codec', 'data.codec', 'data.codec@1', 'json'])

async function runTool(
  rawTool: string,
  rawOperation: string,
  payload: Record<string, unknown>,
  url: string,
  _context: ToolRuntimeContext,
): Promise<ToolRuntimeReport> {
  const tool = rawTool.trim().toLowerCase()
  const operation = rawOperation.trim().toLowerCase()

  const mapping = outputMappingOf(payload)
  if (CODEC_ALIASES.has(tool)) {
    return finish(url, tool, operation, await codec(operation, payload), mapping)
  }

  return {
    url,
    tool,
    operation,
    status: 'unavailable',
    message: `\`${rawTool}\` is a native capability with no browser implementation`,
    updates: {},
  }
}

type ToolOutcome = { status: ToolRuntimeStatus; message?: string; data?: Record<string, unknown> }

function finish(
  url: string,
  tool: string,
  operation: string,
  outcome: ToolOutcome,
  mapping: Record<string, string>,
): ToolRuntimeReport {
  return {
    url,
    tool,
    operation,
    status: outcome.status,
    message: outcome.message,
    data: outcome.data,
    updates: applyOutputMapping(outcome.data ?? {}, mapping),
  }
}

/** `payload.output` maps a result field to a variable name. */
function outputMappingOf(payload: Record<string, unknown>): Record<string, string> {
  const raw = payload.output
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return {}
  const out: Record<string, string> = {}
  for (const [field, variable] of Object.entries(raw as Record<string, unknown>)) {
    if (typeof variable === 'string') out[field] = variable
  }
  return out
}

/** `NativeToolsSuite` maps result fields into `user.*` variables via `output`. */
export function applyOutputMapping(
  data: Record<string, unknown>,
  mapping: Record<string, string>,
): Record<string, DivValue> {
  const updates: Record<string, DivValue> = {}
  for (const [field, variable] of Object.entries(mapping)) {
    if (!(field in data)) continue
    const value = data[field]
    // Array results stay arrays (a card reads them back with getArrayOptString);
    // a structured object is published as JSON text, like the device does.
    updates[variable] = Array.isArray(value)
      ? value.map((item) => display(item))
      : value !== null && typeof value === 'object'
        ? JSON.stringify(value)
        : typeof value === 'number' || typeof value === 'boolean'
          ? value
          : display(value)
  }
  return updates
}

async function codec(operation: string, payload: Record<string, unknown>): Promise<ToolOutcome> {
  const op = (operation || str(payload.op) || str(payload.codec) || 'base64').toLowerCase()
  const encoding = (str(payload.encoding) ?? 'base45').toLowerCase()

  switch (op) {
    case 'base64': {
      const text = str(payload.text) ?? str(payload.value)
      if (text === null) return failed('missing text argument')
      if (action(payload) === 'decode') {
        const decoded = base64Decode(text)
        return decoded === null ? failed('invalid base64') : ok({ status: 'SUCCESS', encoding: 'base64', action: 'decode', text: decoded, length: decoded.length })
      }
      const encoded = base64Encode(text)
      return ok({ status: 'SUCCESS', encoding: 'base64', action: 'encode', text: encoded, length: encoded.length })
    }
    case 'base45': {
      const text = str(payload.text) ?? str(payload.value)
      if (text === null) return failed('missing text argument')
      if (action(payload) === 'decode') {
        const decoded = base45Decode(text)
        return decoded === null ? failed('invalid base45') : ok({ status: 'SUCCESS', encoding: 'base45', action: 'decode', text: decoded, length: decoded.length })
      }
      const encoded = base45Encode(text)
      return ok({ status: 'SUCCESS', encoding: 'base45', action: 'encode', text: encoded, length: encoded.length })
    }
    case 'json':
    case 'json/validate':
    case 'validate': {
      const raw = str(payload.json) ?? str(payload.text)
      if (raw === null) return failed('missing json argument')
      try {
        const parsed = JSON.parse(raw)
        return ok({ status: 'SUCCESS', valid: true, kind: kindOf(parsed) })
      } catch (error) {
        return ok({ status: 'INVALID', valid: false, error: error instanceof Error ? error.message : 'Invalid JSON' })
      }
    }
    case 'json/extract':
    case 'extract': {
      const raw = str(payload.json) ?? str(payload.text)
      const path = str(payload.path) ?? ''
      if (raw === null) return failed('missing json argument')
      try {
        let current: unknown = JSON.parse(raw)
        for (const segment of path.split('.').filter((part) => part.length > 0)) {
          if (current === null || typeof current !== 'object') throw new Error(`path "${path}" not found`)
          current = (current as Record<string, unknown>)[segment]
        }
        if (current === undefined) return failed(`path "${path}" not found`)
        return ok({ status: 'SUCCESS', value: current, exists: true })
      } catch (error) {
        return failed(error instanceof Error ? error.message : 'extract error')
      }
    }
    case 'json/stringify':
    case 'stringify': {
      const value = payload.value ?? payload.json
      if (value === undefined) return failed('missing value argument')
      const text = typeof value === 'string' ? JSON.stringify(value) : JSON.stringify(value)
      return ok({ status: 'SUCCESS', text, length: text.length })
    }
    case 'json/chunk':
    case 'chunk':
      return jsonChunk(payload, encoding)
    case 'json/assemble':
    case 'assemble':
      return jsonAssemble(payload)
    default:
      return failed(`Unsupported codec '${op}'`)
  }
}

const DEFAULT_MAX_BYTES = 500
const DEFAULT_MAX_CHUNKS = 32

async function jsonChunk(payload: Record<string, unknown>, encoding: string): Promise<ToolOutcome> {
  const raw = str(payload.json) ?? str(payload.text)
  if (raw === null) return failed('missing json argument')
  if (encoding !== 'base45' && encoding !== 'base64') return failed(`Unsupported chunk encoding '${encoding}'`)

  const maxBytes = clamp(int(payload.max_bytes) ?? DEFAULT_MAX_BYTES, 64, DEFAULT_MAX_BYTES)
  const maxChunks = clamp(int(payload.max_chunks) ?? DEFAULT_MAX_CHUNKS, 1, DEFAULT_MAX_CHUNKS)
  const bytes = new TextEncoder().encode(raw)
  const transferId = randomId()
  const checksum = (await sha256Hex(bytes)).slice(0, 12)
  const prefix = `C1|${transferId}|00|00|${checksum}|`

  const chunks: string[] = []
  let offset = 0
  while (offset < bytes.length) {
    let take = Math.min(maxBytes, bytes.length - offset)
    let candidate = ''
    while (take > 0) {
      const payloadText = encodeBytes(bytes.subarray(offset, offset + take), encoding)
      candidate = prefix + payloadText
      if (candidate.length <= maxBytes) break
      take -= 1
    }
    if (take <= 0) return failed(`max_bytes=${maxBytes} is too small for the envelope`)
    chunks.push(candidate)
    offset += take
  }
  if (chunks.length > maxChunks) {
    return failed(`Transfer needs ${chunks.length} QR chunks, maximum is ${maxChunks}`)
  }

  const total = chunks.length
  const finalized = chunks.map((chunk, index) =>
    chunk.replace(`C1|${transferId}|00|00|`, `C1|${transferId}|${pad(index)}|${pad(total)}|`),
  )

  return ok({
    status: 'SUCCESS',
    encoding,
    transfer_id: transferId,
    checksum,
    chunks: finalized,
    count: total,
    raw_bytes: bytes.length,
    max_bytes: maxBytes,
    max_chunks: maxChunks,
  })
}

async function jsonAssemble(payload: Record<string, unknown>): Promise<ToolOutcome> {
  const chunks = payload.chunks ?? payload.items
  if (!Array.isArray(chunks) || chunks.length === 0) return failed('missing chunks argument')
  const encoding = (str(payload.encoding) ?? 'base45').toLowerCase()
  const parts: string[] = []
  let expected = 0
  let transferId: string | null = null
  let checksum: string | null = null

  for (const [index, raw] of chunks.entries()) {
    const text = str(raw)
    if (text === null) return failed(`chunk ${index} is not text`)
    const fields = text.split('|')
    if (fields.length < 6 || fields[0] !== 'C1') return failed(`chunk ${index} has no C1 envelope`)
    if (transferId === null) transferId = fields[1]
    else if (transferId !== fields[1]) return failed('chunks belong to different transfers')
    if (checksum === null) checksum = fields[4]
    else if (checksum !== fields[4]) return failed('chunk checksums differ')
    expected = Number(fields[3]) || expected
    parts.push(fields.slice(5).join('|'))
  }

  const bytes = concatBytes(parts.map((part) => decodeText(part, encoding)))
  const text = new TextDecoder().decode(bytes)
  const digest = (await sha256Hex(bytes)).slice(0, 12)
  if (checksum && digest !== checksum) {
    return ok({ status: 'CHECKSUM_MISMATCH', valid: false, transfer_id: transferId, checksum, computed: digest })
  }
  return ok({
    status: 'SUCCESS',
    valid: true,
    transfer_id: transferId,
    checksum,
    count: chunks.length,
    expected,
    json: text,
  })
}

// --- encoding primitives ---------------------------------------------------

const BASE45_ALPHABET = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:'

export function base45Encode(text: string): string {
  const bytes = new TextEncoder().encode(text)
  let out = ''
  for (let index = 0; index < bytes.length; index += 2) {
    if (index + 1 < bytes.length) {
      const value = bytes[index] * 256 + bytes[index + 1]
      out += BASE45_ALPHABET[value % 45]
      out += BASE45_ALPHABET[Math.floor(value / 45) % 45]
      out += BASE45_ALPHABET[Math.floor(value / 2025)]
    } else {
      const value = bytes[index]
      out += BASE45_ALPHABET[value % 45]
      out += BASE45_ALPHABET[Math.floor(value / 45)]
    }
  }
  return out
}

export function base45Decode(text: string): string | null {
  const values: number[] = []
  for (const char of text) {
    const value = BASE45_ALPHABET.indexOf(char)
    if (value === -1) return null
    values.push(value)
  }
  const bytes: number[] = []
  let index = 0
  while (index < values.length) {
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
  return new TextDecoder().decode(new Uint8Array(bytes))
}

export function base64Encode(text: string): string {
  const bytes = new TextEncoder().encode(text)
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary)
}

export function base64Decode(text: string): string | null {
  try {
    const binary = atob(text)
    const bytes = new Uint8Array(binary.length)
    for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index)
    return new TextDecoder().decode(bytes)
  } catch {
    return null
  }
}

function encodeBytes(bytes: Uint8Array, encoding: string): string {
  const text = new TextDecoder().decode(bytes)
  return encoding === 'base64' ? base64Encode(text) : base45Encode(text)
}

function decodeText(text: string, encoding: string): Uint8Array {
  if (encoding === 'base64') {
    const decoded = base64Decode(text) ?? ''
    return new TextEncoder().encode(decoded)
  }
  return new TextEncoder().encode(base45Decode(text) ?? '')
}

function concatBytes(parts: Uint8Array[]): Uint8Array {
  const total = parts.reduce((sum, part) => sum + part.length, 0)
  const out = new Uint8Array(total)
  let offset = 0
  for (const part of parts) {
    out.set(part, offset)
    offset += part.length
  }
  return out
}

async function sha256Hex(bytes: Uint8Array): Promise<string> {
  const digest = await globalThis.crypto.subtle.digest('SHA-256', bytes as unknown as BufferSource)
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, '0')).join('')
}

function randomId(): string {
  const bytes = new Uint8Array(4)
  globalThis.crypto.getRandomValues(bytes)
  return [...bytes].map((byte) => byte.toString(16).padStart(2, '0')).join('')
}

function kindOf(value: unknown): string {
  if (Array.isArray(value)) return 'array'
  if (value === null) return 'null'
  return typeof value === 'object' ? 'object' : typeof value
}

function action(payload: Record<string, unknown>): string {
  const raw = str(payload.action) ?? str(payload.mode) ?? 'encode'
  return raw.toLowerCase()
}

function ok(data: Record<string, unknown>): ToolOutcome {
  return { status: 'ok', data }
}

function failed(message: string): ToolOutcome {
  return { status: 'failed', message }
}

function str(value: unknown): string | null {
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  return null
}

function int(value: unknown): number | null {
  if (typeof value === 'number' && Number.isFinite(value)) return Math.trunc(value)
  if (typeof value === 'string' && /^-?\d+$/.test(value.trim())) return Number(value)
  return null
}

function pad(value: number): string {
  return String(value).padStart(2, '0')
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}
