import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    globals: true,
    environment: 'node',
    environmentMatchGlobs: [['tests/**/pos-ui.test.ts', 'jsdom']],
    include: ['tests/**/*.test.ts'],
  },
})
