# Boda Playground — portable DivKit playground on Nitro

A server-driven-UI playground for the **Boda Boda Driver** DivKit card (plus two
more cards), built to run **anywhere**: Node, Cloudflare Pages, or as a fully
static dist you can point **Tauri** (or nginx, or any dumb file server) at.

```
┌────────────────────────────────────────────────────────────────────┐
│  Vite SPA (client/)  ──build──▶  public/  ◀── served by ──┐        │
│                                                           │        │
│  Nitro server (server/) ── /api/schemas/*  ───────────────┤        │
│    presets: node-server │ cloudflare_pages │ static       │        │
└────────────────────────────────────────────────────────────┘
```

## Why Nitro

One codebase, every runtime. The schema generators are pure JS modules — the
same module runs live on Node, on Cloudflare Workers (Pages Functions), and at
prerender time for static builds. The SPA is a hash-routed single page, so it
works from any static host and inside a Tauri shell with zero server
fallbacks.

## Commands

Runs as the `@apex/dev-server` package of the repository's pnpm workspace, so every
command is available from the repo root (`pnpm dev:dev-server`) or from this
directory (`pnpm dev`).

| Command                | What it does                                                     |
| ---------------------- | ---------------------------------------------------------------- |
| `pnpm dev`             | Nitro dev server on `:3000` (`PORT` overrides) serving the SPA + live API |
| `pnpm client:build`    | Rebuild the SPA into `public/` (needed after editing `client/`)  |
| `pnpm schemas:build`   | Refresh static schema snapshots into `client/public/schemas/`    |
| `pnpm build`           | SPA + API → `.output/` (node-server preset)                      |
| `pnpm build:static`    | SPA + prerendered API → `.output/public` — **Tauri-ready dist**  |
| `pnpm build:pages`     | SPA + `_worker.js` → `dist/` — **Cloudflare Pages**              |
| `pnpm start`           | Run the Node build: `PORT=3000 node .output/server/index.mjs`    |
| `pnpm typecheck`       | `tsc --noEmit` over `client/`, `server/` and `scripts/`          |

Unit tests for the render projection run on the workspace's vitest install:

```bash
pnpm test:dev-server        # from the repo root → vitest over client/src/**/*.test.ts
```

They cover the `json_list` row expansion, the Android `if()` → ternary rewrite and the
custom-block prop resolver, so a card that renders on the device keeps rendering here.

### Deploy targets

- **Node / Docker** — `pnpm build && node .output/server/index.mjs`
- **Cloudflare Pages** — `pnpm build:pages`, deploy `dist/`
  (`npx wrangler pages deploy dist`). `/api/schemas/*` become Pages Functions.
- **Tauri / static host** — `pnpm build:static`, point the app at
  `.output/public`. The API routes are prerendered to plain JSON files, so the
  dist is completely self-contained.

## Extending

### Add a card schema (server-side)

1. Create `server/utils/schemas/my-card.ts` — a pure JS module built with
   `@divkitframework/jsonbuilder` (no node builtins, so it prerenders on
   workers too).
2. Push `{ meta, build }` into `SCHEMAS` in `server/utils/schemas/registry.ts`.
3. Run `pnpm schemas:build` to refresh the static snapshots.

`/api/schemas/my-card`, the picker, and the static preset all pick it up
automatically.

### Add a custom div component (client-side)

DivKit's web runtime renders `{ type: 'custom', custom_type: '<key>' }` divs
through a registry passed to `render({ customComponents })`. This playground
keeps that registry in one file:

`client/src/divkit/customComponents.ts`

- implement the element (a web component host is defined for you via
  `defineCustomHost`), add a template function that receives
  `{ props, variables }`
- register it in `customComponentRegistry`
- reference it from any card: `{ type: 'custom', custom_type: '<key>',
  custom_props: { ... } }`

The **Custom Components Demo** card shows both patterns:

- `x-stat-ring` — template snapshot rendered from card variables
- `x-note-frame` — custom chrome wrapping live DivKit `items` through a
  `<slot>` (those children stay fully reactive)

Template snapshots are taken at render time; wrapped `items` are regular
DivKit children and update live with variables.

## Layout

```
client/                 SPA (React + Tailwind 4, hash-routed)
  src/simulator/        devices.css frames + scale-to-fit simulator
  src/divkit/           render wrapper + customComponents registry
  src/views/            Playground + Docs views
  public/schemas/       static schema snapshots (offline/Tauri fallback)
server/                 Nitro
  routes/api/schemas/   catalog + per-card JSON
  utils/schemas/        generators (boda, waiter, custom demo) + registry
scripts/                build-schemas.mjs, ensure-client.mjs
public/                 built SPA (generated — commit-friendly artifact)
```

## Notes

- DivKit runtime: `@divkitframework/divkit` 33.x (client-devtool build for the
  live variable inspector).
- Device frames: [devices.css](https://devicescss.xyz) (iPhone 14 Pro / 14 / X / 8,
  Pixel 6 Pro / Pixel, Galaxy S8, Z Fold4, iPad Pro) with auto scale-to-fit,
  rotation and a card-restart button.
- The `db:*` scripts and the legacy `src/` Next.js scaffold remain from the
  original environment but are unused by the playground.

## Android parity runtime (`@apex/pos-ui/android-runtime`)

`apps/android` does **not** use the Yandex DivKit runtime; it renders a hand-written
Compose interpreter of a DivKit subset. A workspace preview is only truthful if it runs
that interpreter, so the workspace canvas (`client/src/workspace/WorkspaceCanvas.tsx`)
renders through a browser port of it instead of `@divkitframework/divkit`.

```
packages/pos-ui/src/android-runtime/   pure TS, no React, no DivKit
  vocabulary.ts    node types / native blocks / chart aliases / action types / URL schemes
  expression.ts    the @{...} parser (functions, coercion, display formatting)
  values.ts        DivValue display/number/row semantics
  actions.ts       set_variable + array ops + URL dispatch, folded in order
  renderModel.ts   card JSON -> render tree (defaults, visibility, blocks, json_list)
  chart.ts         DivChartSpec: aggregation, price*qty, demo fallback
  theme.ts         light/dark colour adaptation
  icons.ts         icon catalog resolution (icons://native/<name>, aliases, Material ids)
  validate.ts      authoring check: unknown node types, action schemes, name/expr confusion
client/src/android/AndroidCard.tsx     the React renderer for that tree
client/src/workspace/toolRuntime.ts    tool dispatch: codec runs, everything else reports
```

`packages/pos-ui` stays React-free on purpose: `apps/dev-server` uses React 19 while the
package declares React 18, and a second copy would break hooks. The renderer lives in the
app.

### Generated artifacts (do not hand-edit)

| Artifact | Generated from | Command |
| --- | --- | --- |
| `packages/contract/icons/native-icons.json` | `apps/android/.../NativeIconCatalog.kt` | `pnpm icons:build` |
| `packages/divkit-subset/schema.json` | `packages/pos-ui/src/android-runtime/vocabulary.ts` | `pnpm divkit:schema` |
| `apps/android/src/dev/resources/demo/**` + `client/public/workspaces/**` | `workspace/**` | `pnpm workspaces:build` |

Each has a `--check` variant (`icons:check`, `divkit:schema:check`, `workspaces:check`) and
every workspace build runs the vocabulary validator, so a card that the device would render
blank fails here instead of on a handset.

### What the preview can and cannot do

- Navigation, variables (workspace-scoped like `EngineStateBridge`), `icons://`,
  `div-action://set_variable` and the whole `data.codec@1` family run for real.
- `local-db://op/*` runs against an in-memory mirror of the workspace collections
  (`WorkspaceMemoryDb`, seeded from the bundle). It implements the registry's operations —
  `db.insert`/`update`/`upsert`/`query`/`query_one`/`count`/`delete`,
  `db.append_child`/`set_child_qty`/`remove_child`/`commit`, `doc.pack`/`append`/`unpack`,
  `lookup.resolve` — including schema validation, roll-ups (`sum`, `round2`), the merge-by-key
  child list with its stock ceiling, `editable_status` locks, canonical `lines_json`
  projections, reference delete guards and the `C1|…` transfer envelopes. Records live for the
  life of the page: a restart reloads the seed, exactly like a fresh workspace import.
- Native-only capabilities (camera, print, LAN sockets, ML, `db.import_schema`, …) are
  reported as **unavailable** and show up in the event log. They never return a fabricated
  success — the point of the preview is to tell you when the device would do something it
  cannot show you.
- `sdui_numpad` / `sdui_signature` are web/desktop custom components that **Android renders
  as nothing**; the validator warns about them and they only appear in web-only workspaces.
