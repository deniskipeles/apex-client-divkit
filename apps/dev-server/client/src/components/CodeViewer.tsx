import { useMemo, useState } from 'react'
import type { SchemaPayload } from '../lib/api'

// ---------------------------------------------------------------------------
// CodeViewer — pretty-printed schema JSON with lightweight token coloring,
// copy and download. No heavy editor dependency: the SPA stays lean.
// ---------------------------------------------------------------------------

function highlight(json: string): string {
  const esc = json
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')

  return esc.replace(
    /("(?:\\.|[^"\\])*")(\s*:)?|\b(true|false)\b|\bnull\b|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/g,
    (match, str, colon, bool) => {
      if (str) {
        if (colon) return `<span class="j-key">${str}</span>${colon}`
        // mark DivKit expression refs inside strings
        const withExpr = str.replace(/@\{[^}"]*\}/g, '<span class="j-expr">$&</span>')
        return `<span class="j-str">${withExpr}</span>`
      }
      if (bool) return `<span class="j-bool">${bool}</span>`
      if (match === 'null') return '<span class="j-null">null</span>'
      return `<span class="j-num">${match}</span>`
    },
  )
}

export function CodeViewer({ payload }: { payload: SchemaPayload | null }) {
  const text = useMemo(() => (payload ? JSON.stringify(payload.schema, null, 2) : ''), [payload])
  const html = useMemo(() => (text ? highlight(text) : ''), [text])
  const [copied, setCopied] = useState(false)

  const copy = async () => {
    if (!text) return
    try {
      await navigator.clipboard.writeText(text)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      /* clipboard unavailable */
    }
  }

  const download = () => {
    if (!payload) return
    const blob = new Blob([text], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${payload.meta.id}.json`
    a.click()
    URL.revokeObjectURL(url)
  }

  if (!payload) return <p className="text-xs text-ink-faint">Load a schema to inspect its JSON.</p>

  return (
    <div className="flex min-h-0 flex-col gap-2">
      <div className="flex flex-wrap items-center gap-2">
        <button
          type="button"
          onClick={copy}
          className="rounded-lg border border-panel-line bg-white/[0.04] px-2.5 py-1.5 text-[11px] font-semibold text-ink-dim transition hover:bg-white/10 hover:text-ink"
        >
          {copied ? 'Copied ✓' : 'Copy JSON'}
        </button>
        <button
          type="button"
          onClick={download}
          className="rounded-lg border border-panel-line bg-white/[0.04] px-2.5 py-1.5 text-[11px] font-semibold text-ink-dim transition hover:bg-white/10 hover:text-ink"
        >
          ⬇ Download
        </button>
        <span className="font-mono text-[10px] text-ink-faint">
          {payload.sizeKb} KB · envelope: templates + card
        </span>
      </div>
      <pre
        className="json-view max-h-[460px] overflow-auto whitespace-pre-wrap break-words rounded-xl border border-panel-line bg-black/45 p-3.5"
        dangerouslySetInnerHTML={{ __html: html }}
      />
    </div>
  )
}
