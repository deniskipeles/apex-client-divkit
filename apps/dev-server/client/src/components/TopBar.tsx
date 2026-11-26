import { navigate, useRoute } from '../lib/router'

// ---------------------------------------------------------------------------
// TopBar — brand + view switch + external links. Collapses gracefully on
// small screens (nav chips scroll horizontally).
// ---------------------------------------------------------------------------

const NAV = [
  { path: '/', label: 'Playground' },
  { path: '/workspace', label: 'Workspaces' },
  { path: '/docs', label: 'Docs' },
]

export function TopBar() {
  const route = useRoute()

  return (
    <header className="sticky top-0 z-30 border-b border-panel-line bg-canvas-soft/85 backdrop-blur-md">
      <div className="mx-auto flex w-full max-w-7xl flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3 sm:px-6">
        <a href="#/" className="flex items-center gap-3">
          <span className="flex h-10 w-10 items-center justify-center rounded-2xl bg-gradient-to-br from-emerald-600 to-emerald-400 text-lg shadow-lg shadow-emerald-900/40">
            🛵
          </span>
          <span className="leading-tight">
            <span className="block text-[15px] font-bold text-white">Boda Playground</span>
            <span className="block text-[10.5px] text-ink-faint">
              DivKit SDUI · Nitro-powered · deploy anywhere
            </span>
          </span>
        </a>

        <nav className="order-3 flex min-w-0 items-center gap-1 overflow-x-auto rounded-xl border border-panel-line bg-white/[0.03] p-1 no-scrollbar sm:order-none" aria-label="Views">
          {NAV.map((n) => {
            const active = route.path === n.path
            return (
              <a
                key={n.path}
                href={`#${n.path}`}
                onClick={(e) => {
                  e.preventDefault()
                  navigate(n.path)
                }}
                className={`whitespace-nowrap rounded-lg px-3 py-1.5 text-[12px] font-semibold transition ${
                  active ? 'bg-emerald-500/15 text-emerald-300' : 'text-ink-dim hover:bg-white/5 hover:text-ink'
                }`}
              >
                {n.label}
              </a>
            )
          })}
        </nav>

        <div className="ml-auto hidden items-center gap-2 sm:flex">
          <a
            href="/api/schemas"
            className="rounded-xl border border-panel-line bg-white/[0.04] px-3 py-1.5 text-[11px] font-semibold text-ink-dim transition hover:bg-white/10 hover:text-ink"
            title="Nitro API — schema catalog"
          >
            API
          </a>
          <a
            href="https://playground.divkit.ru"
            target="_blank"
            rel="noreferrer"
            className="rounded-xl border border-panel-line bg-white/[0.04] px-3 py-1.5 text-[11px] font-semibold text-ink-dim transition hover:bg-white/10 hover:text-ink"
          >
            DivKit Playground ↗
          </a>
        </div>
      </div>
    </header>
  )
}
