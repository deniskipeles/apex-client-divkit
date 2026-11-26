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
