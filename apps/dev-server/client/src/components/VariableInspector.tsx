import { useEffect, useRef } from 'react'
import type { VarSnapshot } from '../divkit/DivKitScreen'

// ---------------------------------------------------------------------------
// VariableInspector — live card variables from getDebugAllVariables().
// Changed values flash emerald so you can see DivKit state mutate in place.
// ---------------------------------------------------------------------------

const fmt = (v: unknown): string => {
  if (typeof v === 'string') return v
  if (typeof v === 'number' || typeof v === 'boolean') return String(v)
  try {
    return JSON.stringify(v)
  } catch {
    return String(v)
  }
}

const TYPE_TONE: Record<string, string> = {
  string: 'text-sky-300',
  number: 'text-amber-300',
  integer: 'text-amber-300',
  boolean: 'text-fuchsia-300',
  color: 'text-emerald-300',
  url: 'text-violet-300',
}

export function VariableInspector({ vars }: { vars: VarSnapshot[] }) {
  const prev = useRef<Map<string, unknown>>(new Map())
  const changed = useRef<Set<string>>(new Set())

  useEffect(() => {
    const nextChanged = new Set<string>()
    for (const v of vars) {
      if (prev.current.has(v.name) && prev.current.get(v.name) !== v.value) nextChanged.add(v.name)
      prev.current.set(v.name, v.value)
    }
    changed.current = nextChanged
  }, [vars])

  if (!vars.length) return <p className="text-xs text-ink-faint">No variables reported yet.</p>

  return (
    <div className="flex max-h-72 flex-wrap gap-1.5 overflow-y-auto pr-1">
      {vars.map((v) => {
        const isChanged = changed.current.has(v.name)
        return (
          <span
            key={v.name}
            title={`${v.name}: ${v.type}`}
            className={`inline-flex max-w-full items-baseline gap-1.5 rounded-lg border px-2 py-1 font-mono text-[10.5px] leading-none transition-colors ${
              isChanged
                ? 'border-emerald-400/50 bg-emerald-500/15 text-emerald-200'
                : 'border-panel-line bg-black/30 text-ink-dim'
            }`}
          >
            <span className={TYPE_TONE[v.type] ?? 'text-ink-dim'}>{v.name}</span>
            <span className="text-ink-faint">=</span>
            <span className="truncate text-ink">{fmt(v.value)}</span>
          </span>
        )
      })}
    </div>
  )
}
