import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { DivJson } from '@divkitframework/divkit/client'
import { DivCardNavigator, parseNavigationUrl } from '@apex/pos-ui/navigation-core'
import {
  WorkspaceMemoryDb,
  findIcon,
  isValidVariableName,
  seedVariables,
  type CollectionDefinition,
  type DivValue,
  type DivVars,
} from '@apex/pos-ui/android-runtime'
import type { PlaygroundEvent, VarSnapshot } from '../divkit/DivKitScreen'
import { AndroidCard } from '../android/AndroidCard'
import { dispatchWorkspaceUrl, type ToolExecutionMode, type ToolRuntimeReport } from './toolRuntime'
import { invokeServerTool } from '../lib/api'
import type { WorkspacePayload } from '../lib/api'

// ---------------------------------------------------------------------------
// WorkspaceCanvas — a live stack of DivKit cards rendered by the Android runtime.
//
// This surface is the Compose interpreter (`@apex/pos-ui/android-runtime`), not a
// DivKit client: variables are workspace-scoped like `EngineStateBridge`, cards
// keep their state while the stack is live, and `nav://` runs the same navigator
// as the device. Anything the runtime cannot execute is reported, never faked.
// ---------------------------------------------------------------------------

export interface WorkspaceCanvasProps {
  workspace: WorkspacePayload
  restartKey: number
  /** `local` runs only in-page implementations; `server` calls `/api/tools/invoke`. */
  mode?: ToolExecutionMode
  /** Imperative jump used by the inspector (the kiosk hub itself has no nav buttons). */
  openRequest?: { cardId: string; nonce: number } | null
  onEvent: (event: PlaygroundEvent) => void
  onVariables: (cardId: string, vars: VarSnapshot[]) => void
  onRoute: (cardId: string, depth: number) => void
  onToolReport?: (report: ToolRuntimeReport) => void
}

function snapshotVariables(vars: DivVars): VarSnapshot[] {
  return Object.entries(vars).map(([name, value]) => ({
    name,
    type: Array.isArray(value) ? 'array' : value === null ? 'null' : typeof value,
    value,
  }))
}

export function WorkspaceCanvas({
  workspace,
  restartKey,
  mode = 'local',
  openRequest,
  onEvent,
  onVariables,
  onRoute,
  onToolReport,
}: WorkspaceCanvasProps) {
  const varsRef = useRef<DivVars>({})
  const [vars, setVars] = useState<DivVars>({})
  const cards = useRef<Map<string, DivJson>>(new Map())
  const database = useRef<WorkspaceMemoryDb | null>(null)
  const navigatorRef = useRef<DivCardNavigator | null>(null)
  const [liveIds, setLiveIds] = useState<string[]>([])
  const [currentId, setCurrentId] = useState('')
  const [sheetId, setSheetId] = useState<string | null>(null)
  const [resetNonce, setResetNonce] = useState(0)

  const setVariable = useCallback((name: string, value: DivValue) => {
    if (!isValidVariableName(name)) return
    varsRef.current = { ...varsRef.current, [name]: value }
    setVars(varsRef.current)
  }, [])

  const getVariables = useCallback(() => varsRef.current, [])

  // Rebuild the registry + navigator whenever the workspace changes.
  useEffect(() => {
    const registry = new Map<string, DivJson>()
    const collectionSeeds: DivVars = {}
    const definitions: CollectionDefinition[] = []
    for (const [file, value] of Object.entries(workspace.collections ?? {})) {
      const definition = value as CollectionDefinition
      if (!definition || typeof definition !== 'object') continue
      definitions.push({ ...definition, name: definition.name ?? file.replace(/\.json$/, '') })
      const seed = definition.seed
      if (!Array.isArray(seed) || seed.length === 0) continue
      const name = file.replace(/\.json$/, '')
      // The kiosk catalog variable is the one the device fills from the seeded table.
      collectionSeeds[name === 'inventory' ? 'user.catalog_json' : `user.${name}_json`] = JSON.stringify(seed)
    }
    // One database per workspace load: the device reseeds a workspace when its bundle
    // changes, and a restart must not carry records across.
    database.current = new WorkspaceMemoryDb(definitions)

    let seeded = seedVariables(workspace.hub, { ...collectionSeeds })
    const asCard = (raw: unknown, id: string): DivJson => {
      const node = raw as Record<string, unknown>
      if (node && typeof node === 'object' && 'card' in node) return raw as DivJson
      return {
        card: { log_id: `workspace-${workspace.meta.slug}-${id}`, states: [{ state_id: 0, div: node }] },
      } as unknown as DivJson
    }

    registry.set('hub', asCard(workspace.hub, 'hub'))
    for (const [id, card] of Object.entries(workspace.cards ?? {})) registry.set(id, asCard(card, id))
    // The device seeds a card's declared variables on first render, never overwriting
    // a live value. Seeding the whole registry up front is the same result without a
    // mount-order race.
    for (const card of registry.values()) seeded = seedVariables(card, seeded)

    cards.current = registry
    varsRef.current = seeded
    setVars(seeded)

    const navigator = new DivCardNavigator([{ id: 'hub', data: registry.get('hub')! }], 12)
    navigatorRef.current = navigator
    setLiveIds(['hub'])
    setCurrentId('hub')
    setSheetId(null)
    onRoute('hub', 1)
  }, [workspace, restartKey, resetNonce, onRoute])

  // Publish the live variable table to the inspector.
  useEffect(() => {
    if (!currentId) return
    onVariables(currentId, snapshotVariables(vars))
  }, [vars, currentId, onVariables])

  const open = useCallback(
    (id: string): boolean => {
      if (!cards.current.has(id)) {
        onEvent({ t: Date.now(), kind: 'error', text: `unknown card "${id}"` })
        return false
      }
      setLiveIds((prev) => (prev.includes(id) ? prev : [...prev, id]))
      return true
    },
    [onEvent],
  )

  const handleAction = useCallback(
    async (url: string, payload: unknown) => {
      const nav = parseNavigationUrl(url)
      if (nav) {
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
        } else if (nav.op === 'sheet-dismiss') {
          setSheetId(null)
        }
        const current = navigator.current
        if (current) {
          setCurrentId(current.id)
          onRoute(current.id, navigator.currentIndex + 1)
        }
        onEvent({ t: Date.now(), kind: 'action', text: `${nav.op} → ${'cardId' in nav ? nav.cardId : 'back'}` })
        return
      }

      // `div-action://set_variable?name=&value=` writes a string, like the device.
      if (url.startsWith('div-action://set_variable')) {
        const query = new URLSearchParams(url.slice(url.indexOf('?') + 1))
        const name = query.get('name')
        if (name) setVariable(name, query.get('value') ?? '')
        onEvent({ t: Date.now(), kind: 'action', text: `set_variable ${name ?? '?'}` })
        return
      }

      // `icons://` publishes the resolved icon so a card can read it back.
      if (url.startsWith('icons://')) {
        const icon = findIcon(url)
        if (icon) {
          setVariable('user.icon_name', icon.name)
          setVariable('user.icon_material', icon.material)
          setVariable('user.icon_category', icon.categoryLabel)
          onEvent({ t: Date.now(), kind: 'action', text: `icon ${icon.name} · ${icon.categoryLabel}` })
        } else {
          onEvent({ t: Date.now(), kind: 'error', text: `unknown icon ${url}` })
        }
        return
      }

      // `workspace://clear_all` reseeds the workspace: a fresh in-memory database,
      // fresh variables and a navigator reset to the hub, like a device restart.
      if (url.startsWith('workspace://clear_all')) {
        setResetNonce((nonce) => nonce + 1)
        onEvent({ t: Date.now(), kind: 'action', text: 'workspace clear_all → reseeded' })
        return
      }

      const report = await dispatchWorkspaceUrl(url, payload, getVariables(), {
        collections: workspace.collections,
        workspace: workspace.meta,
        db: database.current,
        mode,
        invokeServer: invokeServerTool,
      })
      for (const [name, value] of Object.entries(report.updates)) setVariable(name, value)
      onToolReport?.(report)
      onEvent({
        t: Date.now(),
        kind: report.status === 'ok' ? 'action' : 'error',
        text: `${url} → ${report.status}${report.message ? ` (${report.message})` : ''}`,
      })
    },
    [getVariables, mode, onEvent, onRoute, onToolReport, open, setVariable],
  )

  useEffect(() => {
    if (!openRequest) return
    void handleAction(`nav://push?id=${openRequest.cardId}`, undefined)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [openRequest?.nonce])

  const cardsForRender = useMemo(() => liveIds.filter((id) => cards.current.has(id)), [liveIds])

  return (
    <div className="relative h-full w-full overflow-hidden bg-[#0B1120]">
      {cardsForRender.map((id) => (
        <div
          key={`${workspace.meta.slug}-${id}-${restartKey}`}
          style={{ display: id === currentId && sheetId === null ? 'block' : 'none', height: '100%' }}
        >
          <AndroidCard
            card={cards.current.get(id)}
            variables={vars}
            getVariables={getVariables}
            onAction={handleAction}
            onSetVariable={setVariable}
            onEvent={(text) => onEvent({ t: Date.now(), kind: 'action', text: `${id}: ${text}` })}
          />
        </div>
      ))}

      {sheetId && cards.current.has(sheetId) ? (
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
            <AndroidCard
              card={cards.current.get(sheetId)}
              variables={vars}
              getVariables={getVariables}
              onAction={handleAction}
              onSetVariable={setVariable}
              onEvent={(text) => onEvent({ t: Date.now(), kind: 'action', text: `sheet: ${text}` })}
            />
          </div>
        </div>
      ) : null}
    </div>
  )
}
