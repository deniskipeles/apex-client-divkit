// ---------------------------------------------------------------------------
// API client with graceful degradation:
//   1. /api/schemas/*  — live Nitro route (node, cloudflare_pages) or the
//                        prerendered file of the same path (static preset)
//   2. /schemas/*      — plain JSON snapshots written by `bun run schemas:build`
//
// Works on Node, workers and even a dumb static file server (relative paths).
// ---------------------------------------------------------------------------

import type { ServerToolInvocation, ServerToolResult } from '../workspace/toolRuntime'

export interface SchemaMeta {
  id: string
  name: string
  icon: string
  tagline: string
  accent: 'emerald' | 'amber' | 'sky' | 'violet'
  tags: string[]
  states: string[]
  showcases: string[]
}

export interface SchemaPayload {
  meta: SchemaMeta
  schema: unknown
  sizeKb: number
}

export interface SchemaCatalog {
  count: number
  schemas: SchemaMeta[]
}

/** Embedded fallback so the picker still renders with zero network. */
export const FALLBACK_CATALOG: SchemaCatalog = {
  count: 3,
  schemas: [
    {
      id: 'boda-driver',
      name: 'Boda Boda Driver',
      icon: '🛵',
      tagline: 'Work & payments manager for motorcycle-taxi drivers',
      accent: 'emerald',
      tags: ['DivState nav', 'variables', 'guarded writes'],
      states: ['home', 'trip', 'saved', 'history', 'expenses'],
      showcases: [
        'earnings hero + daily target progress',
        'fare quick-picks with ±10 stepper',
        'cash / M-Pesa split and trip log',
      ],
    },
    {
      id: 'waiter-terminal',
      name: 'Waiter Terminal',
      icon: '🍽️',
      tagline: 'Table-side ordering terminal for restaurant staff',
      accent: 'amber',
      tags: ['DivState nav', 'cart state', 'submit actions'],
      states: ['tables', 'menu', 'cart', 'sent'],
      showcases: [
        'table grid + menu picker',
        'cart lines with integer-cents totals',
        'order submit flow with sent state',
      ],
    },
    {
      id: 'custom-components-demo',
      name: 'Custom Components Demo',
      icon: '🧩',
      tagline: 'DivKit `custom` divs rendered by playground-registered web components',
      accent: 'violet',
      tags: ['custom_type', 'web components', 'slot children'],
      states: ['demo'],
      showcases: [
        'x-stat-ring — SVG ring rendered from card variables',
        'x-note-frame — custom chrome wrapping live DivKit items via <slot>',
        'variables stay reactive inside custom children',
      ],
    },
  ],
}

async function getJson<T>(urls: string[]): Promise<T> {
  let lastError: unknown = new Error('No source URLs provided')
  for (const url of urls) {
    try {
      const res = await fetch(url, { cache: 'no-store' })
      if (!res.ok) throw new Error(`HTTP ${res.status} for ${url}`)
      return (await res.json()) as T
    } catch (err) {
      lastError = err
    }
  }
  throw lastError
}

export function fetchCatalog(): Promise<SchemaCatalog> {
  return getJson<SchemaCatalog>(['/api/schemas', './schemas/index.json'])
}

export function fetchSchema(id: string): Promise<SchemaPayload> {
  return getJson<SchemaPayload>([`/api/schemas/${id}`, `./schemas/${id}.json`])
}

// ---------------------------------------------------------------------------
// Workspaces — generated `apex.workspace/1` packages.
//   live   : /api/workspaces, /api/workspaces/<slug>
//   static : ./workspaces/index.json, ./workspaces/<slug>/index.json
// ---------------------------------------------------------------------------

export interface WorkspaceMeta {
  slug: string
  manifestId: string
  title: string
  subtitle: string
  accent: string
  terminal: string
  android: boolean
  /** Present on the picker index (derived from the package), absent on a single workspace. */
  cardCount?: number
  collectionCount?: number
}

export interface WorkspaceIndex {
  count: number
  workspaces: WorkspaceMeta[]
}

export interface WorkspacePayload {
  meta: WorkspaceMeta
  manifest: unknown
  hub: unknown
  catalog: unknown
  collections: Record<string, unknown>
  cards: Record<string, unknown>
  order: string[]
}

export function fetchWorkspaces(): Promise<WorkspaceIndex> {
  return getJson<WorkspaceIndex>(['/api/workspaces', './workspaces/index.json'])
}

export function fetchWorkspace(slug: string): Promise<WorkspacePayload> {
  return getJson<WorkspacePayload>([`/api/workspaces/${slug}`, `./workspaces/${slug}/index.json`])
}

// ---------------------------------------------------------------------------
// Server tools — the same-origin execution API used by the workspace's
// "Server" mode. A static build has no route, so both helpers degrade to an
// `unavailable` result and the canvas falls back to its local implementation.
// ---------------------------------------------------------------------------

export interface ServerToolCatalogEntry {
  id: string
  aliases: string[]
  operations: string[]
  supported: boolean
}

export interface ServerToolCatalog {
  count: number
  supported: number
  tools: ServerToolCatalogEntry[]
}

export async function fetchServerTools(): Promise<ServerToolCatalog> {
  const res = await fetch('/api/tools', { cache: 'no-store' })
  if (!res.ok) throw new Error(`HTTP ${res.status} for /api/tools`)
  return (await res.json()) as ServerToolCatalog
}

export async function invokeServerTool(invocation: ServerToolInvocation): Promise<ServerToolResult> {
  try {
    const res = await fetch('/api/tools/invoke', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(invocation),
      cache: 'no-store',
    })
    if (res.status === 404 || res.status === 405 || res.status === 501) {
      return { status: 'unavailable', reason: 'server-unavailable', message: `HTTP ${res.status}` }
    }
    if (!res.ok) {
      return { status: 'failed', reason: 'server-error', message: `HTTP ${res.status}` }
    }
    return (await res.json()) as ServerToolResult
  } catch (error) {
    return {
      status: 'unavailable',
      reason: 'server-unavailable',
      message: error instanceof Error ? error.message : String(error),
    }
  }
}
