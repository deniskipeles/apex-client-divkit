import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  FALLBACK_CATALOG,
  fetchCatalog,
  fetchSchema,
  type SchemaCatalog,
  type SchemaPayload,
} from '../lib/api'
import { useHashQueryParam } from '../lib/router'
import { DEVICES, getDevice } from '../simulator/devices'
import { DeviceSimulator } from '../simulator/DeviceSimulator'
import { DivKitScreen, type PlaygroundEvent, type VarSnapshot } from '../divkit/DivKitScreen'
import { CUSTOM_COMPONENT_IDS } from '../divkit/customComponents'
import { CodeViewer } from '../components/CodeViewer'
import { VariableInspector } from '../components/VariableInspector'
import { EventLog } from '../components/EventLog'
import { Chip, IconButton, Panel, Spinner } from '../components/ui'

// ---------------------------------------------------------------------------
// PlaygroundView — the heart of the SPA:
//   left  : device simulator running the live DivKit card
//   right : inspector (About / Variables / JSON / Events)
// Fully responsive: stacks on < lg, side-by-side above.
// ---------------------------------------------------------------------------

type Tab = 'about' | 'variables' | 'json' | 'events'

const TABS: Array<{ id: Tab; label: string }> = [
  { id: 'about', label: 'About' },
  { id: 'variables', label: 'Variables' },
  { id: 'json', label: 'JSON' },
  { id: 'events', label: 'Events' },
]

export function PlaygroundView() {
  const [schemaId, setSchemaId] = useHashQueryParam('schema')
  const [deviceId, setDeviceId] = useHashQueryParam('device')
  const [rotatedParam, setRotatedParam] = useHashQueryParam('rotate')

  const [catalog, setCatalog] = useState<SchemaCatalog>(FALLBACK_CATALOG)
  const [payload, setPayload] = useState<SchemaPayload | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  const [vars, setVars] = useState<VarSnapshot[]>([])
  const [events, setEvents] = useState<PlaygroundEvent[]>([])
  const [restartKey, setRestartKey] = useState(0)
  const [tab, setTab] = useState<Tab>('about')
  const [zoom, setZoom] = useState(1)

  const device = getDevice(deviceId || 'iphone-14-pro')
  const rotated = rotatedParam === '1'

  // Catalog (embedded fallback + refreshed from API when reachable).
  useEffect(() => {
    fetchCatalog()
      .then(setCatalog)
      .catch(() => {
        /* keep FALLBACK_CATALOG */
      })
  }, [])

  // Load the selected schema.
  useEffect(() => {
    const id = schemaId || 'boda-driver'
    let alive = true
    setLoading(true)
    setLoadError(null)
    setVars([])
    setEvents([])
    fetchSchema(id)
      .then((p) => {
        if (alive) setPayload(p)
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
  }, [schemaId])

  const onVariables = useCallback((next: VarSnapshot[]) => setVars(next), [])
  const onEvent = useCallback((e: PlaygroundEvent) => {
    setEvents((prev) => [...prev.slice(-79), e])
  }, [])

  const meta = payload?.meta ?? catalog.schemas.find((s) => s.id === (schemaId || 'boda-driver')) ?? catalog.schemas[0]

  const accentText = useMemo(() => {
    switch (meta?.accent) {
      case 'amber':
        return 'text-amber-300'
      case 'sky':
        return 'text-sky-300'
      case 'violet':
        return 'text-violet-300'
      default:
        return 'text-emerald-300'
    }
  }, [meta?.accent])

  return (
    <div className="mx-auto flex w-full max-w-7xl flex-1 flex-col gap-5 px-4 pb-10 pt-5 sm:px-6">
      {/* schema picker */}
      <section aria-label="Schema picker" className="flex flex-wrap items-center gap-2">
        {catalog.schemas.map((s) => {
          const active = s.id === meta?.id
          return (
            <button
              key={s.id}
              type="button"
              onClick={() => setSchemaId(s.id)}
              className={`group flex items-center gap-2.5 rounded-2xl border px-3.5 py-2.5 text-left transition ${
                active
                  ? 'border-emerald-400/40 bg-emerald-500/10 shadow-lg shadow-emerald-950/40'
                  : 'border-panel-line bg-panel hover:bg-white/[0.07]'
              }`}
            >
              <span className="text-xl leading-none">{s.icon}</span>
              <span className="leading-tight">
                <span className={`block text-[12.5px] font-bold ${active ? 'text-white' : 'text-ink'}`}>
                  {s.name}
                </span>
                <span className="block max-w-[240px] truncate text-[10.5px] text-ink-faint">{s.tagline}</span>
              </span>
            </button>
          )
        })}
        <span className="ml-auto hidden items-center gap-1.5 xl:flex">
          {CUSTOM_COMPONENT_IDS.map((id) => (
            <Chip key={id} tone="violet">
              🧩 {id}
            </Chip>
          ))}
        </span>
      </section>

      <main className="grid min-h-0 flex-1 items-start gap-5 lg:grid-cols-[minmax(0,1fr)_430px] xl:grid-cols-[minmax(0,1fr)_470px]">
        {/* simulator column */}
        <section aria-label="Live DivKit preview" className="flex min-w-0 flex-col gap-3">
          {/* toolbar */}
          <div className="flex flex-wrap items-center gap-2">
            <div className="flex min-w-0 flex-1 items-center gap-1.5 overflow-x-auto rounded-2xl border border-panel-line bg-panel p-1.5 no-scrollbar" role="group" aria-label="Device picker">
              {DEVICES.map((d) => {
                const active = d.id === device.id
                return (
                  <button
                    key={d.id}
                    type="button"
                    onClick={() => setDeviceId(d.id)}
                    title={d.badge ? `${d.name} — ${d.badge}` : d.name}
                    className={`whitespace-nowrap rounded-xl px-3 py-1.5 text-[11.5px] font-semibold transition ${
                      active
                        ? 'bg-emerald-500/15 text-emerald-300 ring-1 ring-emerald-400/40'
                        : 'text-ink-dim hover:bg-white/5 hover:text-ink'
                    }`}
                  >
                    {d.name}
                  </button>
                )
              })}
            </div>
            <div className="flex items-center gap-1.5">
              <IconButton title={rotated ? 'Rotate to portrait' : 'Rotate to landscape'} active={rotated} onClick={() => setRotatedParam(rotated ? null : '1')}>
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
                title="Restart card — resets all DivKit variables"
                onClick={() => {
                  setRestartKey((k) => k + 1)
                  setEvents((prev) => [...prev.slice(-79), { t: Date.now(), kind: 'action', text: 'playground: card restarted' }])
                }}
              >
                ⏻ Restart
              </IconButton>
            </div>
          </div>

          <DeviceSimulator device={device} rotated={rotated} zoom={zoom}>
            <DivKitScreen payload={payload} restartKey={restartKey} onVariables={onVariables} onEvent={onEvent} />
          </DeviceSimulator>

          <p className="text-center text-[10.5px] text-ink-faint">
            Live card · rendered by @divkitframework/divkit (touch) · frames by devices.css · restart resets
            card state
          </p>
        </section>

        {/* inspector column */}
        <section aria-label="Schema inspector" className="flex min-w-0 flex-col gap-4">
          {/* tabs */}
          <div className="flex items-center gap-1 overflow-x-auto rounded-2xl border border-panel-line bg-panel p-1.5 no-scrollbar" role="tablist" aria-label="Inspector tabs">
            {TABS.map((t) => {
              const count =
                t.id === 'variables' ? vars.length : t.id === 'events' ? events.length : undefined
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
              <Panel title={meta ? `${meta.icon} ${meta.name}` : 'Schema'}>
                <p className="text-[12.5px] leading-relaxed text-ink-dim">
                  {meta?.tagline} — the phone on the left renders the real DivKit envelope, the same JSON an
                  Android / iOS / web SDUI client consumes. Nitro generates it server-side from{' '}
                  <code className="rounded bg-black/40 px-1 py-0.5 font-mono text-[10.5px] text-emerald-300">
                    server/utils/schemas/
                  </code>{' '}
                  and serves it at{' '}
                  <code className="rounded bg-black/40 px-1 py-0.5 font-mono text-[10.5px] text-emerald-300">
                    /api/schemas/{meta?.id}
                  </code>
                  .
                </p>
                <div className="mt-3 flex flex-wrap gap-1.5">
                  {meta?.tags.map((t) => (
                    <Chip key={t} tone={meta.accent}>
                      {t}
                    </Chip>
                  ))}
                </div>
              </Panel>

              <Panel title="What this card showcases">
                <ul className="flex flex-col gap-2">
                  {meta?.showcases.map((f) => (
                    <li key={f} className="flex items-start gap-2.5 text-[12.5px] text-ink-dim">
                      <span className={`mt-0.5 flex h-4 w-4 shrink-0 items-center justify-center rounded-full bg-white/[0.06] text-[9px] ${accentText}`}>
                        ✓
                      </span>
                      <span>{f}</span>
                    </li>
                  ))}
                </ul>
                <div className="mt-3.5 flex flex-wrap items-center gap-1.5">
                  <span className="text-[10px] uppercase tracking-wide text-ink-faint">states</span>
                  {meta?.states.map((s) => (
                    <Chip key={s}>{s}</Chip>
                  ))}
                </div>
              </Panel>

              <Panel title="Try it">
                <ol className="flex flex-col gap-2.5">
                  {[
                    'Tap the bottom-nav inside the phone to switch card states.',
                    'Record a trip / order — watch the Variables tab update live.',
                    'Switch devices and rotate — the card re-lays out, not just scales.',
                    'Restart to reset all card variables to their initial values.',
                  ].map((s, i) => (
                    <li key={s} className="flex items-start gap-3 text-[12.5px] text-ink-dim">
                      <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-emerald-600/20 text-[10.5px] font-bold text-emerald-300">
                        {i + 1}
                      </span>
                      <span>{s}</span>
                    </li>
                  ))}
                </ol>
              </Panel>
            </div>
          )}

          {tab === 'variables' && (
            <Panel
              title={`Variables (${vars.length})`}
              action={<span className="text-[10px] uppercase tracking-wide text-ink-faint">live · 500ms poll</span>}
              className="fade-up"
            >
              <VariableInspector vars={vars} />
            </Panel>
          )}

          {tab === 'json' && (
            <Panel title="Generated schema" className="fade-up">
              <CodeViewer payload={payload} />
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
              <Spinner label={`Loading ${schemaId || 'boda-driver'}…`} />
            </div>
          )}
          {loadError && (
            <div className="rounded-2xl border border-red-500/30 bg-red-500/10 px-4 py-3">
              <p className="text-[12px] text-red-300">Failed to load schema: {loadError}</p>
            </div>
          )}
        </section>
      </main>
    </div>
  )
}
