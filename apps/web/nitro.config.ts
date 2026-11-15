import { defineNitroConfig } from 'nitropack/config'

export default defineNitroConfig({
  srcDir: 'server',
  compatibilityDate: '2026-11-01',
  preset: process.env.NITRO_PRESET,
  // The dashboard SPA is built by Vite into `dist/`; Nitro serves it as static assets.
  publicAssets: [{ dir: '../dist', baseURL: '/' }],
  routeRules: {
    '/api/**': { cors: true, headers: { 'cache-control': 'no-store' } },
  },
})
