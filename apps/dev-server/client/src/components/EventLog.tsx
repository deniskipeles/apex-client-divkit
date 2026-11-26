import { useEffect, useRef } from 'react'
import type { PlaygroundEvent } from '../divkit/DivKitScreen'

// ---------------------------------------------------------------------------
// EventLog — stat / custom action / error events coming from the runtime.
// ---------------------------------------------------------------------------

const KIND_STYLE: Record<PlaygroundEvent['kind'], string> = {
  stat: 'border-panel-line bg-black/30 text-ink-dim',
  action: 'border-emerald-500/25 bg-emerald-500/10 text-emerald-300',
  error: 'border-red-500/30 bg-red-500/10 text-red-300',
}

function clock(t: number): string {
  const d = new Date(t)
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:${String(
    d.getSeconds(),
  ).padStart(2, '0')}`
}

export function EventLog({ events }: { events: PlaygroundEvent[] }) {
  const boxRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    const el = boxRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [events])

  if (!events.length) {
    return <p className="text-xs text-ink-faint">Interact with the card — taps, swipes and actions land here.</p>
  }

  return (
    <div ref={boxRef} className="flex max-h-72 flex-col gap-1.5 overflow-y-auto pr-1">
      {events.map((e, i) => (
        <div
          key={`${e.t}-${i}`}
          className={`flex items-baseline gap-2 rounded-lg border px-2.5 py-1.5 font-mono text-[10.5px] leading-snug ${KIND_STYLE[e.kind]}`}
        >
          <span className="shrink-0 text-ink-faint">{clock(e.t)}</span>
          <span className="shrink-0 uppercase tracking-wide opacity-70">{e.kind}</span>
          <span className="truncate" title={e.text}>
            {e.text}
          </span>
        </div>
      ))}
    </div>
  )
}
