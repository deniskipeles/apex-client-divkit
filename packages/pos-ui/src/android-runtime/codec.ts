/**
 * `tools://codec` — the pure codec family (Base64 / Base45 / JSON).
 *
 * Kept framework-free so the browser runtime (`apps/dev-server`) and the Nitro server
 * tool registry execute byte-for-byte the same implementation. Uses only Web Crypto and
 * the TextEncoder/TextDecoder globals, which exist in both Node and the browser.
 */

export type CodecStatus = 'ok' | 'failed'

export interface CodecOutcome {
  status: CodecStatus
  message?: string
  data?: Record<string, unknown>
}

const BASE45_ALPHABET = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:'
const DEFAULT_MAX_BYTES = 500
const DEFAULT_MAX_CHUNKS = 32
const ENVELOPE_PREFIX = 'C1'

/** Mirrors `NativeToolsSuite.FAILURE_STATUSES`: these domain statuses are failed runs. */
const FAILURE_STATUSES = new Set([
  'FAILED',
  'UNSUPPORTED',
  'NO_INPUT',
  'NO_CODE_FOUND',
  'FORMAT_MISMATCH',
  'DENIED',
  'BLUETOOTH_OFF',
  'NO_DEVICE',
  'ERROR',
  'MISMATCH',
  'KEY_CHANGED',
  'INVALID',
])

export async function codec(operation: string, payload: Record<string, unknown>): Promise<CodecOutcome> {
  const op = (operation || str(payload.op) || str(payload.codec) || 'base64').toLowerCase()
  const encoding = (str(payload.encoding) ?? 'base45').toLowerCase()

  switch (op) {
    case 'base64': {
      const text = str(payload.text) ?? str(payload.value)
      if (text === null) return failed('missing text argument')
      const urlSafe = flag(payload.url_safe)
      if (action(payload) === 'decode') {
        const decoded = urlSafe ? base64UrlDecode(text) : base64Decode(text)
        return decoded === null
          ? failed('invalid base64')
          : ok({ status: 'SUCCESS', encoding: 'base64', action: 'decode', text: decoded, length: decoded.length })
      }
      const encoded = urlSafe ? base64UrlEncode(text) : base64Encode(text)
      return ok({ status: 'SUCCESS', encoding: 'base64', action: 'encode', text: encoded, length: encoded.length })
    }
    case 'base45': {
      const text = str(payload.text) ?? str(payload.value)
      if (text === null) return failed('missing text argument')
      if (action(payload) === 'decode') {
        const decoded = base45DecodeText(text)
        return decoded === null
          ? failed('invalid base45')
          : ok({ status: 'SUCCESS', encoding: 'base45', action: 'decode', text: decoded, length: decoded.length })
      }
      const encoded = base45EncodeText(text)
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
        return ok({
          status: 'INVALID',
          valid: false,
          error: error instanceof Error ? error.message : 'Invalid JSON',
        })
      }
    }
    case 'json/extract':
    case 'extract':
      return jsonExtract(payload)
    case 'json/stringify':
    case 'stringify':
      return jsonStringify(payload)
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

function jsonExtract(payload: Record<string, unknown>): CodecOutcome {
  const raw = str(payload.json) ?? str(payload.text)
  if (raw === null) return failed('missing json argument')
  let root: unknown
  try {
    root = JSON.parse(raw)
  } catch (error) {
    return failed(error instanceof Error ? error.message : 'Invalid JSON')
  }

  const paths = stringArray(payload.paths ?? payload.keys)
  const values: string[] = []
  const missing: string[] = []
  for (const path of paths) {
    const found = traverse(root, path)
    if (found === undefined || found === null) {
      values.push('')
      missing.push(path)
    } else {
      values.push(asText(found))
    }
  }

  const subtreePath = str(payload.subtree_path) ?? str(payload.subtree)
  const data: Record<string, unknown> = {
    status: 'SUCCESS',
    values,
    count: values.length,
    missing,
  }
  if (subtreePath !== null) {
    const subtree = traverse(root, subtreePath)
    data.subtree_json = subtree === undefined || subtree === null ? '' : JSON.stringify(subtree)
    data.subtree_path = subtreePath
  }
  return ok(data)
}

function jsonStringify(payload: Record<string, unknown>): CodecOutcome {
  const keys = stringArray(payload.keys)
  const values = arrayValue(payload.values)
  let result: string

  if (keys.length > 0) {
    const object: Record<string, unknown> = {}
    keys.forEach((key, index) => {
      object[key] = index < values.length && values[index] !== undefined ? values[index] : null
    })
    result = JSON.stringify(object)
  } else {
    const raw = str(payload.json) ?? str(payload.text)
    if (raw === null) return failed('missing json or keys/values')
    try {
      result = JSON.stringify(JSON.parse(raw))
    } catch (error) {
      return failed(error instanceof Error ? error.message : 'Invalid JSON')
    }
  }

  return ok({ status: 'SUCCESS', json: result, length: result.length })
}

async function jsonChunk(payload: Record<string, unknown>, encoding: string): Promise<CodecOutcome> {
  const raw = str(payload.json) ?? str(payload.text)
  if (raw === null) return failed('missing json argument')
  if (encoding !== 'base45' && encoding !== 'base64') return failed(`Unsupported chunk encoding '${encoding}'`)

  const maxBytes = clamp(int(payload.max_bytes) ?? DEFAULT_MAX_BYTES, 64, DEFAULT_MAX_BYTES)
  const maxChunks = clamp(int(payload.max_chunks) ?? DEFAULT_MAX_CHUNKS, 1, DEFAULT_MAX_CHUNKS)
  const bytes = new TextEncoder().encode(raw)
  const transferId = randomId()
  const checksum = (await sha256Hex(bytes)).slice(0, 12)
  const prefix = `${ENVELOPE_PREFIX}|${transferId}|00|00|${checksum}|`

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
    chunk.replace(`${ENVELOPE_PREFIX}|${transferId}|00|00|`, `${ENVELOPE_PREFIX}|${transferId}|${pad(index)}|${pad(total)}|`),
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

async function jsonAssemble(payload: Record<string, unknown>): Promise<CodecOutcome> {
  const chunks = stringArray(payload.chunks ?? payload.blocks)
  if (chunks.length === 0) return failed('missing chunks array')
  const encoding = (str(payload.encoding) ?? 'base45').toLowerCase()
  if (encoding !== 'base45' && encoding !== 'base64') return failed(`Unsupported chunk encoding '${encoding}'`)

  let transferId = ''
  let checksum = ''
  let total = -1
  const byIndex = new Map<number, string>()

  for (const chunk of chunks) {
    const parts = chunk.split('|', 6)
    if (parts.length !== 6 || parts[0] !== ENVELOPE_PREFIX) return failed('Malformed chunk envelope')
    const index = toInt(parts[2])
    if (index === null) return failed('Malformed chunk index')
    const declaredTotal = toInt(parts[3])
    if (declaredTotal === null) return failed('Malformed chunk total')
    if (total >= 0 && total !== declaredTotal) return failed('Chunk totals do not match')
    if (transferId !== '' && transferId !== parts[1]) return failed('Chunk transfer ids do not match')
    transferId = parts[1]
    checksum = parts[4]
    total = declaredTotal
    byIndex.set(index, parts[5])
  }

  const missing: string[] = []
  for (let index = 0; index < total; index += 1) {
    if (!byIndex.has(index)) missing.push(pad(index))
  }
  if (missing.length > 0) {
    return ok({
      status: 'PARTIAL',
      transfer_id: transferId,
      checksum,
      missing,
      count: chunks.length,
      expected: total,
    })
  }

  const partsBytes: Uint8Array[] = []
  for (let index = 0; index < total; index += 1) {
    partsBytes.push(decodeText(byIndex.get(index) ?? '', encoding))
  }
  const bytes = concatBytes(partsBytes)
  const digest = (await sha256Hex(bytes)).slice(0, 12)
  if (digest !== checksum) {
    return ok({
      status: 'FAILED',
      transfer_id: transferId,
      checksum,
      checksum_ok: false,
      error: 'Checksum mismatch',
      count: chunks.length,
    })
  }

  return ok({
    status: 'COMPLETE',
    encoding,
    transfer_id: transferId,
    checksum,
    checksum_ok: true,
    json: new TextDecoder().decode(bytes),
    count: total,
    raw_bytes: bytes.length,
    missing: [],
  })
}

// --- encoding primitives ---------------------------------------------------

export function base45EncodeText(text: string): string {
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

export function base45DecodeText(text: string): string | null {
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

function base64UrlEncode(text: string): string {
  return base64Encode(text).replace(/\+/g, '-').replace(/\//g, '_')
}

function base64UrlDecode(text: string): string | null {
  const normalized = text.replace(/-/g, '+').replace(/_/g, '/')
  const padded = normalized.padEnd(Math.ceil(normalized.length / 4) * 4, '=')
  return base64Decode(padded)
}

function encodeBytes(bytes: Uint8Array, encoding: string): string {
  const text = new TextDecoder().decode(bytes)
  return encoding === 'base64' ? base64Encode(text) : base45EncodeText(text)
}

function decodeText(text: string, encoding: string): Uint8Array {
  if (encoding === 'base64') {
    const decoded = base64Decode(text) ?? ''
    return new TextEncoder().encode(decoded)
  }
  return new TextEncoder().encode(base45DecodeText(text) ?? '')
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
  return value !== null && typeof value === 'object' ? 'object' : 'scalar'
}

function action(payload: Record<string, unknown>): string {
  const raw = (str(payload.action) ?? str(payload.mode) ?? 'encode').toLowerCase()
  if (raw === 'd' || raw === 'dec') return 'decode'
  if (raw === 'e' || raw === 'enc') return 'encode'
  return raw
}

function traverse(root: unknown, path: string): unknown {
  let current = root
  for (const segment of path.split('.')) {
    if (Array.isArray(current)) {
      if (!/^\d+$/.test(segment)) return undefined
      const index = Number(segment)
      if (index >= current.length) return undefined
      current = current[index]
    } else if (current !== null && typeof current === 'object') {
      current = (current as Record<string, unknown>)[segment]
      if (current === undefined) return undefined
    } else {
      return undefined
    }
  }
  return current
}

function asText(value: unknown): string {
  if (value === null || value === undefined) return ''
  return typeof value === 'object' ? JSON.stringify(value) : String(value)
}

function stringArray(value: unknown): string[] {
  return arrayValue(value).map((item) =>
    typeof item === 'string' ? item : item === null || item === undefined ? '' : String(item),
  )
}

function arrayValue(value: unknown): unknown[] {
  if (Array.isArray(value)) return value
  if (typeof value === 'string') {
    try {
      const parsed = JSON.parse(value)
      return Array.isArray(parsed) ? parsed : []
    } catch {
      return []
    }
  }
  return []
}

function toInt(value: string): number | null {
  return /^-?\d+$/.test(value) ? Number(value) : null
}

function outcome(data: Record<string, unknown>): CodecOutcome {
  const status = typeof data.status === 'string' ? data.status : ''
  if (FAILURE_STATUSES.has(status)) {
    return { status: 'failed', message: typeof data.error === 'string' ? data.error : status, data }
  }
  return { status: 'ok', data }
}

function ok(data: Record<string, unknown>): CodecOutcome {
  return outcome(data)
}

function failed(message: string, data?: Record<string, unknown>): CodecOutcome {
  return { status: 'failed', message, data }
}

function flag(value: unknown): boolean {
  if (typeof value === 'boolean') return value
  const raw = str(value)?.trim().toLowerCase()
  return raw === 'true' || raw === '1'
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
