import { createServer, type Server } from 'node:http'
import { ServerTool, numberOf, objectOf, requiredText, text } from '../ServerTool.ts'
import { workspaceKey } from '../types.ts'
import type { ToolContext, ToolOutcome } from '../types.ts'

// `tools://net` — a real HTTP client plus a real (loopback) LAN transfer hub.
//
// The hub binds an ephemeral port on 127.0.0.1 and serves `/files/<id>`; `share`
// without a running hub stages the entry so the card can share before serving.
// Nothing leaves the machine unless a card asks for an external URL.

interface SharedEntry {
  id: string
  name: string
  content: string
  content_type: string
  created_at: number
  bytes: number
}

interface Hub {
  server: Server
  port: number
  host: string
}

const HUBS = new Map<string, Hub>()
const SHARED = new Map<string, Map<string, SharedEntry>>()
const STAGED = new Map<string, Map<string, SharedEntry>>()

export class NetTool extends ServerTool {
  readonly id = 'net'
  readonly aliases = ['network.transfer', 'network.transfer@1', 'http', 'transfer']
  readonly operations = [
    'request',
    'get',
    'post',
    'download',
    'upload',
    'serve',
    'stop',
    'status',
    'share',
    'list',
    'unshare',
  ]

  async invoke(
    operation: string,
    payload: Record<string, unknown>,
    context: ToolContext,
  ): Promise<ToolOutcome> {
    const key = workspaceKey(context)
    switch (operation || 'status') {
      case 'serve':
        return this.serve(key, payload)
      case 'stop':
        return this.stop(key)
      case 'status':
        return this.status(key)
      case 'share':
        return this.share(key, payload)
      case 'list':
        return this.list(key)
      case 'unshare':
        return this.unshare(key, payload)
      case 'get':
      case 'post':
      case 'download':
      case 'upload':
      case 'request':
        return this.transfer(operation, payload)
      default:
        return this.refuse('UNSUPPORTED', `net/${operation} is not supported`)
    }
  }

  private async serve(key: string, payload: Record<string, unknown>): Promise<ToolOutcome> {
    const existing = HUBS.get(key)
    if (existing) {
      return this.ok({
        status: 'SERVING',
        running: true,
        port: existing.port,
        host: existing.host,
        url: `http://${existing.host}:${existing.port}/`,
        count: sharedFor(key).size,
      })
    }

    const host = text(payload.host) ?? '127.0.0.1'
    const requestedPort = numberOf(payload.port)
    const shared = sharedFor(key)
    const server = createServer((request, response) => {
      const path = new URL(request.url ?? '/', `http://${host}`).pathname
      if (path === '/' || path === '/files') {
        const listing = [...shared.values()].map(({ content: _content, ...rest }) => rest)
        response.writeHead(200, { 'content-type': 'application/json' })
        response.end(JSON.stringify(listing))
        return
      }
      const match = /^\/files\/([^/]+)$/.exec(path)
      if (match) {
        const entry = shared.get(match[1])
        if (!entry) {
          response.writeHead(404, { 'content-type': 'text/plain' })
          response.end('not found')
          return
        }
        response.writeHead(200, { 'content-type': entry.content_type })
        response.end(entry.content)
        return
      }
      response.writeHead(404, { 'content-type': 'text/plain' })
      response.end('not found')
    })

    await new Promise<void>((resolve, reject) => {
      server.once('error', reject)
      server.listen(requestedPort ?? 0, host, () => resolve())
    })

    const address = server.address()
    const port = typeof address === 'object' && address !== null ? address.port : (requestedPort ?? 0)
    HUBS.set(key, { server, port, host })

    // Entries shared before the hub existed become live now.
    const staged = STAGED.get(key)
    if (staged) {
      for (const [id, entry] of staged) shared.set(id, entry)
      STAGED.delete(key)
    }

    return this.ok({
      status: 'SERVING',
      running: true,
      port,
      host,
      url: `http://${host}:${port}/`,
      count: shared.size,
    })
  }

  private stop(key: string): ToolOutcome {
    const hub = HUBS.get(key)
    if (!hub) return this.ok({ status: 'NOT_RUNNING', running: false, port: 0 })
    hub.server.close()
    HUBS.delete(key)
    return this.ok({ status: 'STOPPED', running: false, port: 0 })
  }

  private status(key: string): ToolOutcome {
    const hub = HUBS.get(key)
    if (!hub) {
      return this.ok({
        status: 'IDLE',
        running: false,
        port: 0,
        count: (STAGED.get(key)?.size ?? 0),
      })
    }
    return this.ok({
      status: 'SERVING',
      running: true,
      port: hub.port,
      host: hub.host,
      url: `http://${hub.host}:${hub.port}/`,
      count: sharedFor(key).size,
    })
  }

  private share(key: string, payload: Record<string, unknown>): ToolOutcome {
    const content = text(payload.content) ?? text(payload.body) ?? text(payload.text)
    if (content === null || content.length === 0) throw new Error('missing content argument')
    const entry: SharedEntry = {
      id: `share_${randomId()}`,
      name: text(payload.name) ?? 'payload.bin',
      content,
      content_type: text(payload.content_type) ?? 'application/octet-stream',
      created_at: Date.now(),
      bytes: Buffer.byteLength(content, 'utf8'),
    }
    const hub = HUBS.get(key)
    if (hub) {
      sharedFor(key).set(entry.id, entry)
      return this.ok({
        status: 'SHARED',
        ...entry,
        hub_running: true,
        url: `http://${hub.host}:${hub.port}/files/${entry.id}`,
      })
    }
    stagedFor(key).set(entry.id, entry)
    return this.ok({ status: 'STAGED', ...entry, hub_running: false })
  }

  private list(key: string): ToolOutcome {
    const hub = HUBS.get(key)
    const entries = [...(hub ? sharedFor(key) : stagedFor(key)).values()]
    return this.ok({
      status: 'OK',
      count: entries.length,
      items: entries,
      items_json: JSON.stringify(entries),
    })
  }

  private unshare(key: string, payload: Record<string, unknown>): ToolOutcome {
    const id = text(payload.id)
    if (id === null || id.length === 0) throw new Error('missing id argument')
    const removed = sharedFor(key).delete(id) || stagedFor(key).delete(id)
    return this.ok({ status: 'REMOVED', id, removed })
  }

  private async transfer(operation: string, payload: Record<string, unknown>): Promise<ToolOutcome> {
    const url = requiredText(payload, 'url')
    const timeoutMs = numberOf(payload.timeout_ms) ?? 10_000
    const method = (text(payload.method) ?? defaultMethod(operation)).toUpperCase()
    const headers = headerObject(payload.headers)
    const body =
      operation === 'get' || operation === 'download'
        ? undefined
        : text(payload.body) ?? text(payload.content) ?? text(payload.text) ?? undefined

    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), Math.max(1, timeoutMs))
    try {
      const response = await fetch(url, { method, headers, body, signal: controller.signal })
      const buffer = Buffer.from(await response.arrayBuffer())
      const contentType = response.headers.get('content-type') ?? ''
      if (!response.ok) {
        return this.failed(`HTTP ${response.status} ${response.statusText}`.trim(), {
          status: 'FAILED',
          http_status: response.status,
          bytes: buffer.length,
          content_type: contentType,
        })
      }
      return this.ok({
        status: 'SUCCESS',
        http_status: response.status,
        bytes: buffer.length,
        content_type: contentType,
        text: buffer.toString('utf8'),
        body: buffer.toString('utf8'),
        headers: Object.fromEntries(response.headers.entries()),
      })
    } catch (error) {
      return this.failed(describeFetchError(error), { status: 'FAILED' })
    } finally {
      clearTimeout(timer)
    }
  }
}

function defaultMethod(operation: string): string {
  if (operation === 'post' || operation === 'upload') return 'POST'
  return 'GET'
}

function headerObject(value: unknown): Record<string, string> {
  const object = objectOf(value)
  if (!object) return {}
  const headers: Record<string, string> = {}
  for (const [key, entry] of Object.entries(object)) {
    const value = text(entry)
    if (value !== null) headers[key] = value
  }
  return headers
}

function describeFetchError(error: unknown): string {
  if (error instanceof Error) {
    if (error.name === 'AbortError') return 'Request timed out'
    const cause = (error as Error & { cause?: { code?: string; message?: string } }).cause
    if (cause?.code === 'ECONNREFUSED') return 'Connection refused'
    if (cause?.code) return `${error.message} (${cause.code})`
    if (cause?.message) return `${error.message} (${cause.message})`
    return error.message
  }
  return String(error)
}

function sharedFor(key: string): Map<string, SharedEntry> {
  let map = SHARED.get(key)
  if (!map) {
    map = new Map()
    SHARED.set(key, map)
  }
  return map
}

function stagedFor(key: string): Map<string, SharedEntry> {
  let map = STAGED.get(key)
  if (!map) {
    map = new Map()
    STAGED.set(key, map)
  }
  return map
}

function randomId(): string {
  const bytes = new Uint8Array(4)
  globalThis.crypto.getRandomValues(bytes)
  return [...bytes].map((byte) => byte.toString(16).padStart(2, '0')).join('')
}

/** Test/process teardown: close every hub socket this module opened. */
export function stopAllHubs(): void {
  for (const hub of HUBS.values()) hub.server.close()
  HUBS.clear()
}
