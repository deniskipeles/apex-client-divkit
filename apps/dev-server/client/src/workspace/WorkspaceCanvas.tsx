import { useCallback, useEffect, useRef, useState } from 'react'
import type { DivJson } from '@divkitframework/divkit/client'
import { DivCardNavigator, parseNavigationUrl } from '@apex/pos-ui/navigation-core'
import type { PlaygroundEvent, VarSnapshot } from '../divkit/DivKitScreen'
import { getCustomComponents } from '../divkit/customComponents'
import { cardVariables, projectCardForWeb } from '../divkit/androidProjection'
import type { WorkspacePayload } from '../lib/api'

// ---------------------------------------------------------------------------
// WorkspaceCanvas — a live stack of DivKit cards for one generated workspace.
//
// Navigation is the shared state machine from `@apex/pos-ui/navigation-core`
// (the same parser/registry the React host uses), so a `nav://push` in the
// preview behaves like the device. Visited cards stay mounted with
// `display: none` so going back keeps their DivKit variables.
// ---------------------------------------------------------------------------

interface DivkitDebugInstance {
  $destroy: () => void
  getDebugAllVariables: () => Map<string, { getName(): string; getValue(): unknown; getType(): string }>
}

interface CardHostProps {
  cardId: string
  json: DivJson
  visible: boolean
  restartKey: number
  onEvent: (event: PlaygroundEvent) => void
  onVariables: (cardId: string, vars: VarSnapshot[]) => void
  onAction: (url: string) => void
}

function CardHost({ cardId, json, visible, restartKey, onEvent, onVariables, onAction }: CardHostProps) {
  const hostRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    const host = hostRef.current
    if (!host) return
    let instance: DivkitDebugInstance | null = null
    let alive = true
    let poll: ReturnType<typeof setInterval> | undefined

    const variables = cardVariables(json)
    const projected = projectCardForWeb(json, variables)

    import('@divkitframework/divkit/client-devtool')
      .then(({ render }) => {
        if (!alive || !hostRef.current) return
        const el = hostRef.current
        el.innerHTML = ''
        instance = render({
          target: el,
          id: `workspace-${cardId}`,
          json: projected as DivJson,
          platform: 'touch',
          customComponents: getCustomComponents(),
          onStat: (stat: unknown) => {
            const s = stat as { type?: string; action?: { log_id?: string; url?: string } }
            onEvent({ t: Date.now(), kind: 'stat', text: `${cardId}: ${s?.action?.log_id ?? s?.action?.url ?? s?.type ?? 'event'}` })
          },
          onCustomAction: (action: unknown) => {
            const url =
              typeof action === 'string'
                ? action
                : ((action as { url?: string })?.url ?? (action as { log_id?: string })?.log_id ?? String(action))
            onAction(String(url))
          },
          onError: (err: unknown) => {
            let detail = ''
            try {
              const obj = err as { message?: string; msg?: string }
              detail = err instanceof Error ? err.message : (obj?.message ?? obj?.msg ?? JSON.stringify(err).slice(0, 300))
            } catch {
              detail = String(err)
            }
            onEvent({ t: Date.now(), kind: 'error', text: `${cardId}: ${detail.slice(0, 160)}` })
          },
        }) as unknown as DivkitDebugInstance

        const rootEl = el.firstElementChild as HTMLElement | null
        if (rootEl) rootEl.style.height = '100%'

        const snapshot = () => {
          if (!instance || !alive) return
          try {
            const map = instance.getDebugAllVariables()
            onVariables(
              cardId,
              [...map.values()].map((v) => ({ name: v.getName(), type: v.getType(), value: v.getValue() })),
            )
          } catch {
            /* instance mid-destroy */
          }
        }
        snapshot()
        poll = setInterval(snapshot, 600)
      })
      .catch((err: unknown) => {
        if (alive) onEvent({ t: Date.now(), kind: 'error', text: `${cardId}: runtime load failed: ${String(err)}` })
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
  }, [cardId, restartKey])

  return <div ref={hostRef} className="divkit-host" style={{ display: visible ? 'block' : 'none', height: '100%' }} />
}

export interface WorkspaceCanvasProps {
  workspace: WorkspacePayload
  restartKey: number
  /** Imperative jump used by the inspector (the kiosk hub itself has no nav buttons). */
  openRequest?: { cardId: string; nonce: number } | null
  onEvent: (event: PlaygroundEvent) => void
  onVariables: (cardId: string, vars: VarSnapshot[]) => void
  onRoute: (cardId: string, depth: number) => void
}

export function WorkspaceCanvas({ workspace, restartKey, openRequest, onEvent, onVariables, onRoute }: WorkspaceCanvasProps) {
  const cards = useRef<Map<string, DivJson>>(new Map())
  const navigatorRef = useRef<DivCardNavigator | null>(null)
  const [liveIds, setLiveIds] = useState<string[]>([])
  const [currentId, setCurrentId] = useState('')
  const [sheetId, setSheetId] = useState<string | null>(null)

  // Rebuild the registry + navigator whenever the workspace changes.
  useEffect(() => {
    const registry = new Map<string, DivJson>()
    // The hub is a full card document; the Android tool cards are bare divs that the device
    // wraps in the tenant card, so the preview wraps them with the hub's seeded variables.
    const hubCard = (workspace.hub as { card?: { variables?: { name: string; value: unknown }[] } }).card
    // The browser has no SQLite plane, so the catalog variable starts from the collection seed
    // the device would have loaded, otherwise every `json_list` renders its empty state.
    const inventory = workspace.collections['inventory.json'] as { seed?: unknown[] } | undefined
    const seeded: Record<string, string> =
      Array.isArray(inventory?.seed) && inventory.seed.length > 0
        ? { 'user.catalog_json': JSON.stringify(inventory.seed) }
        : {}
    const seedVariables = (hubCard?.variables ?? []).map((variable) =>
      seeded[variable.name] === undefined ? variable : { ...variable, value: seeded[variable.name] },
    )
    const asCard = (raw: unknown, id: string): DivJson => {
      const node = raw as Record<string, unknown>
      if (node && typeof node === 'object' && 'card' in node) return node as unknown as DivJson
      return {
        card: {
          log_id: `workspace-${workspace.meta.slug}-${id}`,
          variables: seedVariables,
          states: [{ state_id: 0, div: node }],
        },
      } as unknown as DivJson
    }
    registry.set('hub', asCard(workspace.hub, 'hub'))
    for (const [id, card] of Object.entries(workspace.cards)) registry.set(id, asCard(card, id))
    cards.current = registry
    const navigator = new DivCardNavigator([{ id: 'hub', data: registry.get('hub')! }], 12)
    navigatorRef.current = navigator
    setLiveIds(['hub'])
    setCurrentId('hub')
    setSheetId(null)
    onRoute('hub', 1)
  }, [workspace, restartKey, onRoute])

  const open = useCallback(
    (id: string): boolean => {
      const card = cards.current.get(id)
      if (!card) {
        onEvent({ t: Date.now(), kind: 'error', text: `unknown card "${id}"` })
        return false
      }
      setLiveIds((prev) => (prev.includes(id) ? prev : [...prev, id]))
      return true
    },
    [onEvent],
  )

  const handleAction = useCallback(
    (url: string) => {
      const nav = parseNavigationUrl(url)
      if (!nav) {
        const kind = url.startsWith('tools://') || url.startsWith('local-db://') || url.startsWith('icons://') ? 'action' : 'stat'
        onEvent({ t: Date.now(), kind, text: `dispatch ${url}` })
        return
      }
      const navigator = navigatorRef.current
      if (!navigator) return

      if (nav.op === 'push' || nav.op === 'replace' || nav.op === 'reset') {
        if (!open(nav.cardId)) return
        const entry = { id: nav.cardId, data: cards.current.get(nav.cardId)! }
        if (nav.op === 'push') navigator.push(entry)
        else if (nav.op === 'replace') navigator.replace(entry)
        else navigator.reset(entry)
      } else if (nav.op === 'pop') {
        navigator.pop()
      } else if (nav.op === 'sheet-present') {
        if (!open(nav.cardId)) return
        setSheetId(nav.cardId)
        onEvent({ t: Date.now(), kind: 'action', text: `sheet ${nav.cardId}` })
        return
      } else if (nav.op === 'sheet-dismiss') {
        setSheetId(null)
        onEvent({ t: Date.now(), kind: 'action', text: 'sheet dismissed' })
        return
      }

      const current = navigator.current
      if (current) {
        setCurrentId(current.id)
        onRoute(current.id, navigator.currentIndex + 1)
      }
      onEvent({ t: Date.now(), kind: 'action', text: `${nav.op} → ${'cardId' in nav ? nav.cardId : 'back'}` })
    },
    [onEvent, onRoute, open],
  )

  useEffect(() => {
    if (!openRequest) return
    handleAction(`nav://push?id=${openRequest.cardId}`)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [openRequest?.nonce])

  return (
    <div className="relative h-full w-full overflow-hidden bg-[#0B1120]">
      {liveIds.map((id) => (
        <CardHost
          key={`${workspace.meta.slug}-${id}-${restartKey}`}
          cardId={id}
          json={cards.current.get(id)!}
          visible={id === currentId && sheetId === null}
          restartKey={restartKey}
          onEvent={onEvent}
          onVariables={onVariables}
          onAction={handleAction}
        />
      ))}

      {sheetId && cards.current.get(sheetId) ? (
        <div className="absolute inset-0 flex flex-col justify-end bg-black/60">
          <div className="max-h-[80%] overflow-y-auto rounded-t-2xl bg-[#0B1120] pb-3 shadow-2xl">
            <div className="flex items-center justify-between px-4 py-2">
              <span className="font-mono text-[10px] uppercase tracking-wider text-ink-faint">sheet · {sheetId}</span>
              <button
                type="button"
                onClick={() => {
                  setSheetId(null)
                  onEvent({ t: Date.now(), kind: 'action', text: 'sheet dismissed' })
                }}
                className="rounded-lg border border-panel-line px-2 py-1 text-[10px] font-semibold text-ink-dim"
              >
                Close
              </button>
            </div>
            <CardHost
              key={`${workspace.meta.slug}-sheet-${sheetId}-${restartKey}`}
              cardId={`sheet-${sheetId}`}
              json={cards.current.get(sheetId)!}
              visible
              restartKey={restartKey}
              onEvent={onEvent}
              onVariables={onVariables}
              onAction={handleAction}
            />
          </div>
        </div>
      ) : null}
    </div>
  )
}
