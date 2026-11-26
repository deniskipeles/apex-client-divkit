import { useEffect, useRef } from 'react'
import type { DivJson } from '@divkitframework/divkit/client'
import type { SchemaPayload } from '../lib/api'
import { getCustomComponents } from './customComponents'

// ---------------------------------------------------------------------------
// DivKitScreen — mounts one live DivKit instance into its host div.
//
// Uses @divkitframework/divkit/client-devtool so the playground gets
// getDebugAllVariables() for the live inspector. Every custom component in
// client/src/divkit/customComponents.ts is registered here at render time.
// ---------------------------------------------------------------------------

export interface VarSnapshot {
  name: string
  type: string
  value: unknown
}

export interface PlaygroundEvent {
  t: number
  kind: 'stat' | 'action' | 'error'
  text: string
}

interface DivkitDebugInstance {
  $destroy: () => void
  getDebugAllVariables: () => Map<string, { getName(): string; getValue(): unknown; getType(): string }>
}

interface Props {
  payload: SchemaPayload | null
  restartKey: number
  onVariables: (vars: VarSnapshot[]) => void
  onEvent: (event: PlaygroundEvent) => void
  onReady?: () => void
}

export function DivKitScreen({ payload, restartKey, onVariables, onEvent, onReady }: Props) {
  const hostRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    const host = hostRef.current
    if (!host || !payload?.schema) return

    let instance: DivkitDebugInstance | null = null
    let alive = true
    let poll: ReturnType<typeof setInterval> | undefined

    import('@divkitframework/divkit/client-devtool')
      .then(({ render }) => {
        if (!alive || !hostRef.current) return
        const el = hostRef.current
        el.innerHTML = ''
        instance = render({
          target: el,
          id: `playground-${payload.meta.id}`,
          json: payload.schema as DivJson,
          platform: 'touch',
          customComponents: getCustomComponents(),
          onStat: (stat: unknown) => {
            const s = stat as { type?: string; action?: { log_id?: string; type?: string; url?: string } }
            const label =
              s?.action?.log_id ??
              s?.action?.type ??
              s?.type ??
              'event'
            onEvent({ t: Date.now(), kind: 'stat', text: String(label) })
          },
          onCustomAction: (action: unknown, value: unknown) => {
            const name =
              typeof action === 'string'
                ? action
                : ((action as { url?: string; log_id?: string })?.log_id ??
                  (action as { url?: string })?.url ??
                  String(action))
            onEvent({ t: Date.now(), kind: 'action', text: `custom:${name} ${JSON.stringify(value ?? '') ?? ''}`.trim() })
          },
          onError: (err: unknown) => {
            const msg = err instanceof Error ? err.message : String(err)
            onEvent({ t: Date.now(), kind: 'error', text: msg })
          },
        }) as unknown as DivkitDebugInstance

        // DivKit root is a CSS grid sized by its parent — pin it to the
        // device screen so DivState + bottom nav lay out app-like.
        const rootEl = el.firstElementChild as HTMLElement | null
        if (rootEl) rootEl.style.height = '100%'

        const snapshot = () => {
          if (!instance || !alive) return
          try {
            const map = instance.getDebugAllVariables()
            const vars: VarSnapshot[] = [...map.values()].map((v) => ({
              name: v.getName(),
              type: v.getType(),
              value: v.getValue(),
            }))
            onVariables(vars)
          } catch {
            /* instance mid-destroy */
          }
        }
        snapshot()
        poll = setInterval(snapshot, 500)
        onReady?.()
      })
      .catch((err: unknown) => {
        if (alive) onEvent({ t: Date.now(), kind: 'error', text: `runtime load failed: ${String(err)}` })
      })

    return () => {
      alive = false
      if (poll) clearInterval(poll)
      try {
        instance?.$destroy()
      } catch {
        /* noop */
      }
      if (hostRef.current) hostRef.current.innerHTML = ''
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [payload, restartKey])

  return <div ref={hostRef} className="divkit-host" />
}
