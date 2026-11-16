/**
 * Parses `nav://` and `sheet://` action URLs into the cross-platform navigation action
 * shape pinned by packages/contract/fixtures/navigation/v1. Returns `null` for any URL the
 * navigation layer does not own (unknown scheme/host, or a missing/blank card id), so
 * callers can fall through to other handlers without throwing.
 */
export type NavigationOp =
  | { op: 'push'; cardId: string }
  | { op: 'pop' }
  | { op: 'replace'; cardId: string }
  | { op: 'reset'; cardId: string }
  | { op: 'sheet-present'; cardId: string }
  | { op: 'sheet-dismiss' }

function queryParams(url: string): URLSearchParams {
  const q = url.indexOf('?')
  return new URLSearchParams(q >= 0 ? url.slice(q + 1) : '')
}

function param(url: string, key: string): string | undefined {
  const value = queryParams(url).get(key)
  return value && value.length > 0 ? value : undefined
}

export function parseNavigationUrl(url: string): NavigationOp | null {
  const match = /^([a-z][a-z0-9+.-]*):\/\/([^/?#]*)/i.exec(url)
  if (!match) return null
  const scheme = match[1].toLowerCase()
  const host = match[2].toLowerCase()

  if (scheme === 'nav') {
    switch (host) {
      case 'push': {
        const cardId = param(url, 'id')
        return cardId ? { op: 'push', cardId } : null
      }
      case 'pop':
        return { op: 'pop' }
      case 'replace': {
        const cardId = param(url, 'id')
        return cardId ? { op: 'replace', cardId } : null
      }
      case 'reset': {
        const cardId = param(url, 'id')
        return cardId ? { op: 'reset', cardId } : null
      }
      default:
        return null
    }
  }

  if (scheme === 'sheet') {
    switch (host) {
      case 'present': {
        const cardId = param(url, 'card_id')
        return cardId ? { op: 'sheet-present', cardId } : null
      }
      case 'dismiss':
        return { op: 'sheet-dismiss' }
      default:
        return null
    }
  }

  return null
}
