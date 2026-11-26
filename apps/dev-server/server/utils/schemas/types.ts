// Shared types for the playground schema registry.
// The generated artifact is the standard DivKit envelope: { templates, card }.

export interface SchemaMeta {
  /** stable id used in URLs: /api/schemas/:id and #/?schema=:id */
  id: string
  name: string
  icon: string
  tagline: string
  /** tailwind-ish accent used by the client for chips/badges */
  accent: 'emerald' | 'amber' | 'sky' | 'violet'
  tags: string[]
  /** state ids exposed by the card's DivState (navigation) */
  states: string[]
  /** how this card demonstrates a playground capability */
  showcases: string[]
}

export interface SchemaEntry {
  meta: SchemaMeta
  build: () => unknown
}

export interface SchemaPayload {
  meta: SchemaMeta
  /** DivKit envelope — exactly what a client runtime consumes */
  schema: unknown
  /** approximate serialized size in KB */
  sizeKb: number
}

export function envelopeSizeKb(schema: unknown): number {
  return Math.round((JSON.stringify(schema).length / 1024) * 10) / 10
}
