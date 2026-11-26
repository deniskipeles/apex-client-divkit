import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// The SPA client lives in client/ and builds into public/ — the directory
// Nitro serves statically for every preset (node, cloudflare_pages, static).
export default defineConfig({
  root: 'client',
  plugins: [react(), tailwindcss()],
  // Ignore the legacy root postcss.config.mjs (Next.js scaffold) — Tailwind
  // is applied through the @tailwindcss/vite plugin instead.
  css: { postcss: { plugins: [] } },
  build: {
    outDir: '../public',
    emptyOutDir: true,
    target: 'es2022',
    chunkSizeWarningLimit: 3000,
  },
  // Convenience when running `vite` alone in dev: API calls go to Nitro.
  server: {
    proxy: {
      '/api': 'http://localhost:3000',
      '/schemas': 'http://localhost:3000',
    },
  },
})
