import type { ToolContext, ToolOutcome } from './types.ts'

// ---------------------------------------------------------------------------
// Extending the server tool inventory
// ---------------------------------------------------------------------------
//
// A custom capability is one subclass registered in `registry.ts`:
//
//   export class LoyaltyTool extends ServerTool {
//     readonly id = 'loyalty'
//     readonly aliases = ['loyalty-points']
//     readonly operations = ['balance', 'redeem']
//     async invoke(operation, payload) {
//       if (operation === 'balance') return this.ok({ status: 'SUCCESS', points: 42 })
//       return this.refuse('UNSUPPORTED', `loyalty/${operation} is not wired yet`)
//     }
//   }
//
// `id` and `aliases` are matched case-insensitively against the `tools://<id>/<op>`
// host and the `div-action://terminal.tool.invoke?tool=<id>` query, so the card
// needs no change. `operations` is advertised by `GET /api/tools`.
// ---------------------------------------------------------------------------

export abstract class ServerTool {
  /** Canonical `tools://` host, e.g. `files`. */
  abstract readonly id: string
  /** Extra names `tools://` and `div-action://` accept. */
  readonly aliases: string[] = []
  /** Operations this tool advertises (used by `GET /api/tools`). */
  abstract readonly operations: string[]
  /** `false` documents a capability the server honestly cannot execute. */
  readonly supported: boolean = true

  abstract invoke(
    operation: string,
    payload: Record<string, unknown>,
    context: ToolContext,
  ): Promise<ToolOutcome> | ToolOutcome

  matches(name: string): boolean {
    const needle = name.trim().toLowerCase()
    if (needle === this.id.toLowerCase()) return true
    return this.aliases.some((alias) => alias.toLowerCase() === needle)
  }

  /** The tool ran and produced a domain result. */
  protected ok(data: Record<string, unknown>): ToolOutcome {
    return { status: 'ok', data }
  }

  /** The tool ran and refused, or hit a transport/argument error. */
  protected failed(message: string, data?: Record<string, unknown>): ToolOutcome {
    return { status: 'failed', message, data }
  }

  /** A domain refusal with an explicit status, e.g. `NO_INPUT` or `UNSUPPORTED`. */
  protected refuse(status: string, message: string, data: Record<string, unknown> = {}): ToolOutcome {
    return { status: 'failed', message, data: { ...data, status } }
  }

  /** No implementation can run (hardware the server does not own). */
  protected unavailable(message: string): ToolOutcome {
    return { status: 'unavailable', message }
  }
}

// --- payload coercion ------------------------------------------------------

export function text(value: unknown): string | null {
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  return null
}

export function requiredText(payload: Record<string, unknown>, ...keys: string[]): string {
  for (const key of keys) {
    const value = text(payload[key])
    if (value !== null && value.length > 0) return value
  }
  throw new Error(`missing ${keys[0]} argument`)
}

export function numberOf(value: unknown): number | null {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value === 'string' && value.trim() !== '' && !Number.isNaN(Number(value))) return Number(value)
  return null
}

export function booleanOf(value: unknown): boolean | null {
  if (typeof value === 'boolean') return value
  if (typeof value === 'string') {
    const normalized = value.trim().toLowerCase()
    if (normalized === 'true' || normalized === '1' || normalized === 'on' || normalized === 'yes') return true
    if (normalized === 'false' || normalized === '0' || normalized === 'off' || normalized === 'no') return false
  }
  return null
}

export function objectOf(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null
}
