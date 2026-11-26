import type { ReactNode } from 'react'

// Shared UI primitives — lean, hand-rolled on Tailwind (the SPA is
// intentionally framework-light so it stays portable).

export function Panel({
  title,
  action,
  children,
  className = '',
  bodyClassName = '',
}: {
  title?: ReactNode
  action?: ReactNode
  children: ReactNode
  className?: string
  bodyClassName?: string
}) {
  return (
    <section
      className={`rounded-2xl border border-panel-line bg-panel shadow-lg shadow-black/20 ${className}`}
    >
      {title !== undefined && (
        <header className="flex items-center justify-between gap-3 border-b border-panel-line/60 px-4 py-3">
          <h2 className="text-[13px] font-semibold tracking-wide text-ink">{title}</h2>
          {action}
        </header>
      )}
      <div className={`p-4 ${bodyClassName}`}>{children}</div>
    </section>
  )
}

const TONES: Record<string, string> = {
  emerald: 'border-emerald-500/25 bg-emerald-500/10 text-emerald-300',
  amber: 'border-amber-500/25 bg-amber-500/10 text-amber-300',
  sky: 'border-sky-500/25 bg-sky-500/10 text-sky-300',
  violet: 'border-violet-500/25 bg-violet-500/10 text-violet-300',
  neutral: 'border-white/10 bg-white/5 text-ink-dim',
}

export function Chip({
  tone = 'neutral',
  children,
  className = '',
}: {
  tone?: keyof typeof TONES | string
  children: ReactNode
  className?: string
}) {
  return (
    <span
      className={`inline-flex items-center gap-1 rounded-lg border px-2 py-1 text-[10px] font-medium leading-none ${TONES[tone] ?? TONES.neutral} ${className}`}
    >
      {children}
    </span>
  )
}

export function IconButton({
  onClick,
  active = false,
  title,
  children,
  className = '',
}: {
  onClick?: () => void
  active?: boolean
  title?: string
  children: ReactNode
  className?: string
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      title={title}
      aria-label={title}
      className={`inline-flex h-9 min-w-9 items-center justify-center gap-1.5 rounded-xl border px-2.5 text-[11px] font-semibold transition ${
        active
          ? 'border-emerald-400/40 bg-emerald-500/15 text-emerald-300'
          : 'border-panel-line bg-white/[0.04] text-ink-dim hover:bg-white/10 hover:text-ink'
      } ${className}`}
    >
      {children}
    </button>
  )
}

export function Spinner({ label }: { label?: string }) {
  return (
    <div className="flex flex-col items-center justify-center gap-3 py-8">
      <div className="h-7 w-7 animate-spin rounded-full border-2 border-emerald-500 border-t-transparent" />
      {label && <p className="text-xs text-ink-faint">{label}</p>}
    </div>
  )
}
