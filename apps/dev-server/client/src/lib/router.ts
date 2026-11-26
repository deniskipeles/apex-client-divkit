import { useEffect, useState, useCallback } from 'react'

// ---------------------------------------------------------------------------
// Tiny hash router — `#/` (playground), `#/docs`, optional query params.
// Hash routing keeps the SPA portable: it works on plain static hosting and
// inside Tauri (tauri:// protocol) with zero server-side fallbacks.
// ---------------------------------------------------------------------------

export interface RouteInfo {
  path: string
  query: URLSearchParams
}

function parseHash(): RouteInfo {
  const raw = window.location.hash.replace(/^#/, '') || '/'
  const [path, qs] = raw.split('?')
  return { path: path || '/', query: new URLSearchParams(qs ?? '') }
}

export function useRoute(): RouteInfo {
  const [route, setRoute] = useState<RouteInfo>(() => parseHash())
  useEffect(() => {
    const onChange = () => setRoute(parseHash())
    window.addEventListener('hashchange', onChange)
    return () => window.removeEventListener('hashchange', onChange)
  }, [])
  return route
}

export function navigate(to: string): void {
  if (window.location.hash === `#${to}`) return
  window.location.hash = to
}

/** Updates a query param in the current hash without adding history noise. */
export function useHashQueryParam(key: string): [string, (v: string | null) => void] {
  const route = useRoute()
  const current = route.query.get(key) ?? ''

  const set = useCallback(
    (v: string | null) => {
      const { path, query } = parseHash()
      if (v === null || v === '') query.delete(key)
      else query.set(key, v)
      const qs = query.toString()
      const next = qs ? `${path}?${qs}` : path
      if (`#${next}` !== window.location.hash) window.location.hash = next
    },
    [key],
  )

  return [current, set]
}
