import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  fetchWorkspace,
  fetchWorkspaces,
  type WorkspaceIndex,
  type WorkspaceMeta,
  type WorkspacePayload,
  type SchemaPayload,
} from '../lib/api'
import { navigate, useHashQueryParam } from '../lib/router'
import { getDevice } from '../simulator/devices'
import { DeviceSimulator } from '../simulator/DeviceSimulator'
import type { PlaygroundEvent, VarSnapshot } from '../divkit/DivKitScreen'
import { WorkspaceCanvas } from '../workspace/WorkspaceCanvas'
import { CodeViewer } from '../components/CodeViewer'
import { VariableInspector } from '../components/VariableInspector'
import { EventLog } from '../components/EventLog'
import { Chip, IconButton, Panel, Spinner } from '../components/ui'

// ---------------------------------------------------------------------------
// WorkspaceView — `#/workspace?ws=<slug>`: a generated `apex.workspace/1`
// package rendered as a navigable stack of live cards inside a device frame.
// ---------------------------------------------------------------------------

type Tab = 'about' | 'variables' | 'json' | 'events'

const TABS: Array<{ id: Tab; label: string }> = [
  { id: 'about', label: 'About' },
  { id: 'variables', label: 'Variables' },
  { id: 'json', label: 'JSON' },
  { id: 'events', label: 'Events' },
]

export function WorkspaceView() {
  const [slug, setSlug] = useHashQueryParam('ws')
  const [deviceId, setDeviceId] = useHashQueryParam('device')
  const [rotatedParam, setRotatedParam] = useHashQueryParam('rotate')

  const [index, setIndex] = useState<WorkspaceIndex | null>(null)
  const [workspace, setWorkspace] = useState<WorkspacePayload | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  const [vars, setVars] = useState<VarSnapshot[]>([])
  const [events, setEvents] = useState<PlaygroundEvent[]>([])
  const [restartKey, setRestartKey] = useState(0)
  const [tab, setTab] = useState<Tab>('about')
  const [zoom, setZoom] = useState(1)
  const [route, setRoute] = useState<{ cardId: string; depth: number }>({ cardId: 'hub', depth: 1 })
  const [openRequest, setOpenRequest] = useState<{ cardId: string; nonce: number } | null>(null)

  const device = getDevice(deviceId || 'iphone-14-pro')
  const rotated = rotatedParam === '1'

  useEffect(() => {
    let alive = true
    fetchWorkspaces()
      .then((next) => {
        if (!alive) return
        setIndex(next)
        if (!slug && next.workspaces[0]) setSlug(next.workspaces[0].slug)
      })
      .catch(() => {
        /* the picker falls back to whatever is already selected */
      })
    return () => {
      alive = false
    }
  }, [slug, setSlug])

  useEffect(() => {
    if (!slug) return
    let alive = true
    setLoading(true)
    setLoadError(null)
    setVars([])
    setEvents([])
    fetchWorkspace(slug)
      .then((payload) => {
        if (alive) setWorkspace(payload)
      })
      .catch((err: unknown) => {
        if (alive) setLoadError(err instanceof Error ? err.message : String(err))
      })
      .finally(() => {
        if (alive) setLoading(false)
      })
    return () => {
      alive = false
    }
  }, [slug])

  const onEvent = useCallback((event: PlaygroundEvent) => {
    setEvents((prev) => [...prev.slice(-119), event])
  }, [])

  const onVariables = useCallback((cardId: string, next: VarSnapshot[]) => {
    setVars(next.map((v) => ({ ...v, name: `${cardId} · ${v.name}` })))
  }, [])

  const onRoute = useCallback((cardId: string, depth: number) => {
    setRoute({ cardId, depth })
  }, [])

  const meta: WorkspaceMeta | null = workspace?.meta ?? null
  const catalog = (workspace?.catalog ?? null) as { title?: string; subtitle?: string } | null
  const cardCount = workspace ? Object.keys(workspace.cards).length : 0

  // CodeViewer takes the schema payload shape; the current card is the schema.
  const cardPayload: SchemaPayload | null = useMemo(() => {
    if (!workspace) return null
    const json = route.cardId === 'hub' ? workspace.hub : workspace.cards[route.cardId]
    if (!json) return null
    const text = JSON.stringify(json, null, 2)
    return {
      meta: {
        id: `${workspace.meta.slug}/${route.cardId}`,
        name: `${workspace.meta.title} · ${route.cardId}`,
        icon: '📦',
        tagline: workspace.meta.subtitle,
        accent: 'emerald',
        tags: ['apex.workspace/1', workspace.meta.android ? 'android target' : 'dev-server only'],
        states: [],
        showcases: [],
      },
      schema: json,
      sizeKb: Math.round((text.length / 1024) * 10) / 10,
    }
  }, [workspace, route.cardId])

  return (
    <main className="relative mx-auto flex w-full max-w-[1500px] flex-col gap-4 px-4 pb-10 pt-5">
      <section aria-label="Workspace picker" className="flex flex-wrap items-center gap-1.5 rounded-2xl border border-panel-line bg-panel p-1.5">
          {(index?.workspaces ?? []).map((item) => (
            <button
              key={item.slug}
              type="button"
              onClick={() => setSlug(item.slug)}
              className={`whitespace-nowrap rounded-xl px-3 py-1.5 text-[11.5px] font-semibold transition ${
                item.slug === slug
                  ? 'bg-emerald-500/15 text-emerald-300 ring-1 ring-emerald-400/40'
                  : 'text-ink-dim hover:bg-white/5 hover:text-ink'
              }`}
            >
              {item.title}
              <span className="ml-1.5 font-mono text-[9.5px] opacity-60">{item.cardCount}</span>
            </button>
          ))}
      </section>

      <main className="grid min-h-0 flex-1 items-start gap-5 lg:grid-cols-[minmax(0,1fr)_430px] xl:grid-cols-[minmax(0,1fr)_470px]">
      <section aria-label="Workspace preview" className="flex min-w-0 flex-col gap-3">
        <div className="flex flex-wrap items-center gap-2">
          <div className="flex min-w-0 flex-1 items-center gap-1.5 rounded-2xl border border-panel-line bg-panel px-3 py-1.5">
          <span className="ml-auto flex items-center gap-1.5">
            <IconButton title="Rotate" active={rotated} onClick={() => setRotatedParam(rotated ? null : '1')}>
              ⟲ Rotate
            </IconButton>
            <IconButton title="Zoom out" onClick={() => setZoom((z) => Math.max(0.5, +(z - 0.1).toFixed(2)))}>
              −
            </IconButton>
            <IconButton title="Reset zoom" onClick={() => setZoom(1)}>
              <span className="font-mono">{(zoom * 100).toFixed(0)}%</span>
            </IconButton>
            <IconButton title="Zoom in" onClick={() => setZoom((z) => Math.min(1.6, +(z + 0.1).toFixed(2)))}>
              +
            </IconButton>
            <IconButton
              title="Restart — resets the navigation stack and card variables"
              onClick={() => setRestartKey((k) => k + 1)}
            >
              ⏻ Restart
            </IconButton>
          </span>
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-1.5 rounded-2xl border border-panel-line bg-panel px-3 py-2">
          <span className="font-mono text-[10px] uppercase tracking-wide text-ink-faint">stack</span>
          <Chip tone="emerald">{`${route.depth} · ${route.cardId}`}</Chip>
          <span className="font-mono text-[10px] text-ink-faint">
            {meta ? `${meta.manifestId} · ${cardCount} cards · ${meta.android ? 'android' : 'preview only'}` : ''}
          </span>
          <button
            type="button"
            onClick={() => navigate('/')}
            className="ml-auto rounded-lg border border-panel-line bg-white/[0.04] px-2.5 py-1 text-[10.5px] font-semibold text-ink-dim hover:bg-white/10 hover:text-ink"
          >
            ← Playground
          </button>
        </div>

        <DeviceSimulator device={device} rotated={rotated} zoom={zoom}>
          {workspace && (!slug || workspace.meta.slug === slug) ? (
            <WorkspaceCanvas
              workspace={workspace}
              restartKey={restartKey}
              openRequest={openRequest}
              onEvent={onEvent}
              onVariables={onVariables}
              onRoute={onRoute}
            />
          ) : (
            <div className="flex h-full items-center justify-center">
              <Spinner label="Loading workspace…" />
            </div>
          )}
        </DeviceSimulator>

        <p className="text-center text-[10.5px] text-ink-faint">
          Generated from apps/dev-server/workspace · nav:// handled by @apex/pos-ui/navigation-core ·
          Android-only blocks are projected for preview
        </p>
      </section>

      <section aria-label="Workspace inspector" className="flex min-w-0 flex-col gap-4">
        <div className="flex items-center gap-1 overflow-x-auto rounded-2xl border border-panel-line bg-panel p-1.5 no-scrollbar" role="tablist">
          {TABS.map((t) => {
            const count = t.id === 'variables' ? vars.length : t.id === 'events' ? events.length : undefined
            const active = tab === t.id
            return (
              <button
                key={t.id}
                type="button"
                role="tab"
                aria-selected={active}
                onClick={() => setTab(t.id)}
                className={`flex items-center gap-1.5 whitespace-nowrap rounded-xl px-3 py-1.5 text-[11.5px] font-semibold transition ${
                  active ? 'bg-emerald-500/15 text-emerald-300 ring-1 ring-emerald-400/40' : 'text-ink-dim hover:bg-white/5 hover:text-ink'
                }`}
              >
                {t.label}
                {count !== undefined && count > 0 && (
                  <span className="rounded-md bg-white/10 px-1.5 py-0.5 font-mono text-[9.5px] leading-none">{count}</span>
                )}
              </button>
            )
          })}
        </div>

        {tab === 'about' && (
          <div className="fade-up flex flex-col gap-4">
            <Panel title={meta ? meta.title : 'Workspace'}>
              <p className="text-[12.5px] leading-relaxed text-ink-dim">
                {meta?.subtitle ?? catalog?.subtitle} — every card below is the JSON shipped to the device. The
                manifest, tool catalog and `cards.order` are derived from the TypeScript definition, so the files
                on disk cannot drift from the manifest.
              </p>
              <div className="mt-3 flex flex-wrap gap-1.5">
                <Chip tone="emerald">{meta?.terminal ?? '—'}</Chip>
                <Chip>{`${cardCount} cards`}</Chip>
                <Chip>{workspace ? `${Object.keys(workspace.collections).length} collections` : '—'}</Chip>
                <Chip tone="amber">{meta?.android ? 'ships to apps/android' : 'dev-server only'}</Chip>
              </div>
            </Panel>

            <Panel title="Cards">
              <ul className="flex flex-col gap-1.5">
                {(workspace?.order ?? []).map((name) => (
                  <li key={name} className="flex items-center justify-between gap-2 rounded-lg border border-panel-line bg-black/25 px-2.5 py-1.5">
                    <span className="font-mono text-[11px] text-ink-dim">tools/{name}.json</span>
                    <button
                      type="button"
                      onClick={() => setOpenRequest({ cardId: name, nonce: Date.now() })}
                      className="rounded-md border border-emerald-400/30 bg-emerald-500/10 px-2 py-0.5 text-[10px] font-semibold text-emerald-300"
                    >
                      open
                    </button>
                  </li>
                ))}
              </ul>
            </Panel>

            <Panel title="Try it">
              <ol className="flex flex-col gap-2.5">
                {[
                  'Tap a hub button — nav://push opens the card inside the same frame.',
                  'Tap Back (nav://pop) to return; the card you left keeps its variables.',
                  'Charts, lists and layout blocks render from the Android projection.',
                  'Restart resets the navigation stack and every card variable.',
                ].map((step, i) => (
                  <li key={step} className="flex items-start gap-3 text-[12.5px] text-ink-dim">
                    <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-emerald-600/20 text-[10.5px] font-bold text-emerald-300">
                      {i + 1}
                    </span>
                    <span>{step}</span>
                  </li>
                ))}
              </ol>
            </Panel>
          </div>
        )}

        {tab === 'variables' && (
          <Panel title={`Variables (${vars.length})`} className="fade-up">
            <VariableInspector vars={vars} />
          </Panel>
        )}

        {tab === 'json' && (
          <Panel title={`Card JSON · ${route.cardId}`} className="fade-up">
            <CodeViewer payload={cardPayload} />
          </Panel>
        )}

        {tab === 'events' && (
          <Panel
            title="Event log"
            action={
              <button
                type="button"
                onClick={() => setEvents([])}
                className="rounded-lg border border-panel-line bg-white/[0.04] px-2.5 py-1 text-[10.5px] font-semibold text-ink-dim hover:bg-white/10 hover:text-ink"
              >
                Clear
              </button>
            }
            className="fade-up"
          >
            <EventLog events={events} />
          </Panel>
        )}

        {loading && (
          <div className="rounded-2xl border border-panel-line bg-panel px-4">
            <Spinner label={`Loading ${slug || 'workspace'}…`} />
          </div>
        )}
        {loadError && (
          <div className="rounded-2xl border border-red-500/30 bg-red-500/10 px-4 py-3">
            <p className="text-[12px] text-red-300">Failed to load workspace: {loadError}</p>
          </div>
        )}
      </section>
      </main>
    </main>
  )
}
