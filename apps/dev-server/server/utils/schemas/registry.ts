// ---------------------------------------------------------------------------
// Schema registry — the single extension point for the playground.
//
// To add a new card to the playground:
//   1. drop a generator module next to this file (pure JS, no node builtins)
//   2. import it below and push an entry into SCHEMAS
// The Nitro route (/api/schemas) picks it up automatically — and with the
// `static` preset the same route is prerendered at build time, so Tauri /
// Cloudflare Pages builds get the new card for free.
// ---------------------------------------------------------------------------
import { bodaCard } from './boda-driver.ts'
import { waiterCard } from './waiter-terminal.ts'
import { customDemoCard } from './custom-components-demo.ts'
import type { SchemaEntry, SchemaPayload } from './types.ts'
import { envelopeSizeKb } from './types.ts'

export const SCHEMAS: SchemaEntry[] = [
  {
    meta: {
      id: 'boda-driver',
      name: 'Boda Boda Driver',
      icon: '🛵',
      tagline: 'Work & payments manager for motorcycle-taxi drivers',
      accent: 'emerald',
      tags: ['DivState nav', 'variables', 'guarded writes'],
      states: ['home', 'trip', 'saved', 'history', 'expenses'],
      showcases: [
        'earnings hero + daily target progress',
        'fare quick-picks with ±10 stepper',
        'cash / M-Pesa split and trip log',
      ],
    },
    build: () => bodaCard,
  },
  {
    meta: {
      id: 'waiter-terminal',
      name: 'Waiter Terminal',
      icon: '🍽️',
      tagline: 'Table-side ordering terminal for restaurant staff',
      accent: 'amber',
      tags: ['DivState nav', 'cart state', 'submit actions'],
      states: ['tables', 'menu', 'cart', 'sent'],
      showcases: [
        'table grid + menu picker',
        'cart lines with integer-cents totals',
        'order submit flow with sent state',
      ],
    },
    build: () => waiterCard,
  },
  {
    meta: {
      id: 'custom-components-demo',
      name: 'Custom Components Demo',
      icon: '🧩',
      tagline: 'DivKit `custom` divs rendered by playground-registered web components',
      accent: 'violet',
      tags: ['custom_type', 'web components', 'slot children'],
      states: ['demo'],
      showcases: [
        'x-stat-ring — SVG ring rendered from card variables',
        'x-note-frame — custom chrome wrapping live DivKit items via <slot>',
        'variables stay reactive inside custom children',
      ],
    },
    build: () => customDemoCard,
  },
]

export function getSchemaEntry(id: string): SchemaEntry | undefined {
  return SCHEMAS.find((s) => s.meta.id === id)
}

export function buildSchemaPayload(id: string): SchemaPayload | undefined {
  const entry = getSchemaEntry(id)
  if (!entry) return undefined
  const schema = entry.build()
  return { meta: entry.meta, schema, sizeKb: envelopeSizeKb(schema) }
}

export function listSchemaPayloads(): SchemaPayload[] {
  return SCHEMAS.map((e) => {
    const schema = e.build()
    return { meta: e.meta, schema, sizeKb: envelopeSizeKb(schema) }
  })
}
