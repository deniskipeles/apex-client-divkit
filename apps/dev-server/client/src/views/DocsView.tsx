import { Panel, Chip } from '../components/ui'
import { CUSTOM_COMPONENT_IDS } from '../divkit/customComponents'

// ---------------------------------------------------------------------------
// DocsView — the portability contract of this playground: what runs where,
// how to add schemas and how to extend the runtime with custom components.
// ---------------------------------------------------------------------------

const TARGETS = [
  {
    icon: '🟩',
    name: 'Node (dev / prod)',
    cmd: 'bun run dev  ·  bun run build && bun run start',
    detail: 'nitro dev on :3000 — API routes run live on Node. .output/server/index.mjs is a plain entry point.',
  },
  {
    icon: '⚡',
    name: 'Cloudflare Pages',
    cmd: 'NITRO_PRESET=cloudflare_pages bun run build',
    detail: 'dist/ gets static assets + _worker.js. /api/schemas/* become Pages Functions — schema generation runs on workers.',
  },
  {
    icon: '🖥️',
    name: 'Tauri / any static host',
    cmd: 'NITRO_PRESET=static bun run build',
    detail: '.output/public is a fully self-contained dist: the SPA plus prerendered /api/schemas/* JSON files. Point Tauri (or nginx) at it — no server needed.',
  },
  {
    icon: '📦',
    name: 'Docker / bare metal',
    cmd: 'bun run build && node .output/server/index.mjs',
    detail: 'Zero-runtime-dependency output. PORT + HOST env vars, no framework lock-in.',
  },
]

const SNIPPET_ADD_SCHEMA = `// server/utils/schemas/my-card.ts
import { divCard, DivText, NumberVariable } from '@divkitframework/jsonbuilder'

export const myCard = divCard({}, {
  log_id: 'my_card',
  variables: [new NumberVariable({ name: 'score', value: 0 })],
  states: [{ state_id: 0, div: new DivText({ text: 'Hi @{score}' }) }],
})

// server/utils/schemas/registry.ts
SCHEMAS.push({
  meta: { id: 'my-card', name: 'My Card', icon: '✨', /* … */ },
  build: () => myCard,
})

// done — /api/schemas/my-card + the picker update automatically.`

const SNIPPET_CUSTOM = `// client/src/divkit/customComponents.ts
customElements.define('x-sparkline', class extends HTMLElement { /* … */ })

customComponentRegistry.set('sparkline', {
  element: 'x-sparkline',
  shadowRootMode: 'open',
  template: ({ props, variables }) => \`<svg>\${draw(resolve(props.value, variables))}</svg>\`,
})

// any card can now use:
// { "type": "custom", "custom_type": "sparkline",
//   "custom_props": { "value": "@{earnings}" } }`

export function DocsView() {
  return (
    <div className="mx-auto flex w-full max-w-5xl flex-1 flex-col gap-5 px-4 pb-12 pt-5 sm:px-6">
      <div className="fade-up">
        <h1 className="text-xl font-bold text-white">One playground, every runtime</h1>
        <p className="mt-1.5 max-w-3xl text-[13px] leading-relaxed text-ink-dim">
          The playground is a <strong className="text-ink">Nitro</strong> server plus a{' '}
          <strong className="text-ink">Vite-built SPA</strong>. Nitro compiles the same code to Node,
          Cloudflare Workers or fully static output, so the identical playground runs in the cloud, on an
          edge network or inside a Tauri shell. Hash routing keeps the SPA portable — every view is a
          client-side route (<code className="rounded bg-black/40 px-1 py-0.5 font-mono text-[11px] text-emerald-300">#/</code>,{' '}
          <code className="rounded bg-black/40 px-1 py-0.5 font-mono text-[11px] text-emerald-300">#/docs</code>).
        </p>
      </div>

      <Panel title="Build targets">
        <div className="grid gap-3 sm:grid-cols-2">
          {TARGETS.map((t) => (
            <div key={t.name} className="rounded-xl border border-panel-line bg-black/25 p-3.5">
              <div className="flex items-center gap-2">
                <span>{t.icon}</span>
                <h3 className="text-[13px] font-bold text-ink">{t.name}</h3>
              </div>
              <code className="mt-2 block overflow-x-auto whitespace-nowrap rounded-lg bg-black/50 px-2.5 py-1.5 font-mono text-[10.5px] text-emerald-300">
                {t.cmd}
              </code>
              <p className="mt-2 text-[11.5px] leading-relaxed text-ink-faint">{t.detail}</p>
            </div>
          ))}
        </div>
      </Panel>

      <Panel
        title="Add a new card schema"
        action={<Chip tone="emerald">server-side</Chip>}
      >
        <p className="mb-3 text-[12.5px] leading-relaxed text-ink-dim">
          Schemas are plain modules built with <code className="rounded bg-black/40 px-1 py-0.5 font-mono text-[10.5px] text-emerald-300">@divkitframework/jsonbuilder</code> —
          pure JS, no node builtins, so the same module prerenders on workers. Register it once; the API,
          the static snapshots and the picker all pick it up.
        </p>
        <pre className="json-view overflow-x-auto whitespace-pre rounded-xl border border-panel-line bg-black/45 p-3.5">
          {SNIPPET_ADD_SCHEMA}
        </pre>
        <p className="mt-2.5 text-[11px] text-ink-faint">
          Then run <code className="font-mono">bun run schemas:build</code> to refresh the static snapshots
          in <code className="font-mono">client/public/schemas/</code> (used by the static/Tauri preset and
          as offline fallback).
        </p>
      </Panel>

      <Panel
        title="Scale with custom div components"
        action={<Chip tone="violet">client-side</Chip>}
      >
        <p className="mb-3 text-[12.5px] leading-relaxed text-ink-dim">
          DivKit's web runtime renders{' '}
          <code className="rounded bg-black/40 px-1 py-0.5 font-mono text-[10.5px] text-violet-300">{'{ type: "custom" }'}</code>{' '}
          divs through a registry passed to{' '}
          <code className="rounded bg-black/40 px-1 py-0.5 font-mono text-[10.5px] text-violet-300">render({'{ customComponents }'})</code>.
          This playground keeps that registry in one file — future custom components slot in without
          touching views or server code. Registered today:{' '}
          {CUSTOM_COMPONENT_IDS.map((id) => (
            <Chip key={id} tone="violet" className="mx-0.5">
              {id}
            </Chip>
          ))}
        </p>
        <pre className="json-view overflow-x-auto whitespace-pre rounded-xl border border-panel-line bg-black/45 p-3.5">
          {SNIPPET_CUSTOM}
        </pre>
        <p className="mt-2.5 text-[11px] leading-relaxed text-ink-faint">
          Template functions receive a snapshot of{' '}
          <code className="font-mono">{'{ props, variables }'}</code> at render time; the card's{' '}
          <code className="font-mono">items</code> are mounted inside the element as regular DivKit
          children and stay fully reactive. See the{' '}
          <a href="#/?schema=custom-components-demo" className="text-emerald-300 underline decoration-emerald-300/40">
            Custom Components Demo
          </a>{' '}
          for both patterns.
        </p>
      </Panel>

      <Panel title="Architecture map">
        <div className="grid gap-3 text-[12px] leading-relaxed text-ink-dim sm:grid-cols-2">
          <div className="rounded-xl border border-panel-line bg-black/25 p-3.5">
            <h3 className="mb-1.5 text-[12.5px] font-bold text-ink">server/ — Nitro</h3>
            <ul className="flex flex-col gap-1.5">
              <li><code className="font-mono text-[10.5px] text-emerald-300">routes/api/schemas/index.get.ts</code> — catalog</li>
              <li><code className="font-mono text-[10.5px] text-emerald-300">routes/api/schemas/[id].get.ts</code> — envelope</li>
              <li><code className="font-mono text-[10.5px] text-emerald-300">utils/schemas/</code> — generators + registry</li>
            </ul>
          </div>
          <div className="rounded-xl border border-panel-line bg-black/25 p-3.5">
            <h3 className="mb-1.5 text-[12.5px] font-bold text-ink">client/ — Vite SPA</h3>
            <ul className="flex flex-col gap-1.5">
              <li><code className="font-mono text-[10.5px] text-emerald-300">simulator/</code> — devices.css frames + fit scaling</li>
              <li><code className="font-mono text-[10.5px] text-emerald-300">divkit/</code> — renderer + custom registry</li>
              <li><code className="font-mono text-[10.5px] text-emerald-300">views/</code> — Playground, Docs (hash router)</li>
            </ul>
          </div>
        </div>
      </Panel>
    </div>
  )
}
