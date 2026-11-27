import { ServerTool, text } from '../ServerTool.ts'
import { workspaceKey } from '../types.ts'
import type { ToolContext, ToolOutcome } from '../types.ts'

// `tools://offline-cache` — a per-workspace key/value cache. The preview has no
// service worker, so this models the cache the device keeps on disk: it is real
// state inside the dev-server process, not a fake success.

interface CacheEntry {
  content: string
  cachedAt: number
}

const CACHES = new Map<string, Map<string, CacheEntry>>()

export class OfflineCacheTool extends ServerTool {
  readonly id = 'offline-cache'
  readonly aliases = ['offline_cache', 'storage.offline_cache', 'storage.offline_cache@1', 'offlinecache']
  readonly operations = ['cache', 'get_stats', 'clear']

  invoke(operation: string, payload: Record<string, unknown>, context: ToolContext): ToolOutcome {
    const cache = cacheFor(workspaceKey(context))
    switch (operation || 'get_stats') {
      case 'cache': {
        const key = text(payload.key) ?? text(payload.url) ?? text(payload.name)
        const content = text(payload.content) ?? text(payload.body) ?? text(payload.value)
        if (key === null || key.length === 0) throw new Error('missing key argument')
        if (content === null) throw new Error('missing content argument')
        cache.set(key, { content, cachedAt: Date.now() })
        return this.ok({
          status: 'CACHED_OFFLINE_READY',
          key,
          cached_pages: cache.size,
          bytes: Buffer.byteLength(content, 'utf8'),
        })
      }
      case 'get_stats':
        return this.ok({
          status: 'CACHED_OFFLINE_READY',
          cached_pages: cache.size,
          keys: [...cache.keys()],
          bytes: [...cache.values()].reduce((sum, entry) => sum + Buffer.byteLength(entry.content, 'utf8'), 0),
        })
      case 'clear':
        cache.clear()
        return this.ok({ status: 'CACHED_OFFLINE_READY', cached_pages: 0 })
      default:
        return this.refuse('UNSUPPORTED', `offline-cache/${operation} is not supported`)
    }
  }
}

function cacheFor(key: string): Map<string, CacheEntry> {
  let cache = CACHES.get(key)
  if (!cache) {
    cache = new Map()
    CACHES.set(key, cache)
  }
  return cache
}
