import { defineNitroConfig } from 'nitropack'
import { resolve } from 'node:path'

// ---------------------------------------------------------------------------
// Nitro config — one codebase, many targets:
//
//   bun run dev            → nitro dev            (node-server, port 3000)
//   bun run build          → nitro build          (.output/server + public)
//   NITRO_PRESET=cloudflare_pages bun run build   (Cloudflare Pages)
//   NITRO_PRESET=static bun run build             (.output/public — drop into
//                                                  Tauri, nginx, any static host)
//
// The SPA client (built by Vite into ./public) is served statically by every
// preset; /api/schemas/* routes run live on node/workers and are prerendered
// to plain files for the static preset.
// ---------------------------------------------------------------------------
export default defineNitroConfig({
  srcDir: 'server',
  // Vite writes the SPA here; declared explicitly because the auto-scan only
  // looks for a `public/` folder *inside* srcDir (server/public).
  publicAssets: [{ dir: resolve('public'), baseURL: '/', maxAge: 0 }],
  preset: (process.env.NITRO_PRESET || undefined) as never,
  compatibilityDate: '2025-07-15',
  prerender: {
    crawlLinks: false,
    autoSubfolderIndex: false,
    failOnError: false,
    // NOTE: /api/schemas (the collection) is intentionally NOT prerendered —
    // a file named `api/schemas` would collide with the `api/schemas/`
    // directory of the per-card routes. The SPA falls back to the static
    // snapshot ./schemas/index.json for the catalog in static builds.
    routes: [
      '/api/health',
      '/api/schemas/boda-driver',
      '/api/schemas/waiter-terminal',
      '/api/schemas/custom-components-demo',
      // NOTE: /api/workspaces (the collection) is not prerendered for the same
      // reason as /api/schemas — a file would collide with the route directory.
      // The SPA falls back to ./workspaces/index.json in static builds.
      '/api/workspaces/motorbike',
      '/api/workspaces/apex-tools-test',
      '/api/workspaces/apex-kiosk',
    ],
  },
})
