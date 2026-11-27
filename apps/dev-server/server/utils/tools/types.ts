// ---------------------------------------------------------------------------
// The same-origin tool API contract.
//
// `POST /api/tools/invoke` accepts a `ServerToolRequest` and answers a
// `ServerToolReport`. The report's `status` is the *runtime* outcome:
//
//   ok          — the tool ran (its domain status lives in `data.status`)
//   failed      — the tool ran and refused / hit an error
//   unavailable — no implementation ran (unknown tool, or a capability that
//                 needs hardware the server does not have)
//
// `updates` is already mapped through the card's `output` map, exactly as
// `NativeToolsSuite` does on the device.
// ---------------------------------------------------------------------------

export type ToolRuntimeStatus = 'ok' | 'failed' | 'unavailable'

export interface ToolContext {
  /** Workspace collections (schema + seed) the request was made from. */
  collections?: Record<string, unknown>
  workspace?: { slug?: string }
}

/** What a `ServerTool` returns. Domain statuses (`SUCCESS`, `DENIED`, …) go in `data`. */
export interface ToolOutcome {
  status: ToolRuntimeStatus
  message?: string
  data?: Record<string, unknown>
}

export interface ServerToolRequest {
  tool: string
  operation: string
  payload: Record<string, unknown>
  context?: ToolContext
}

export interface ServerToolReport {
  tool: string
  operation: string
  status: ToolRuntimeStatus
  message?: string
  data?: Record<string, unknown>
  updates: Record<string, unknown>
  /** `unknown-tool` when nothing matched the requested id/alias. */
  reason?: string
}

export interface ServerToolInfo {
  id: string
  aliases: string[]
  operations: string[]
  /** `true` when the server can execute it (false = honest hardware/host gap). */
  supported: boolean
}

/** The workspace key stateful tools scope their state to. */
export function workspaceKey(context: ToolContext | undefined): string {
  const slug = context?.workspace?.slug
  return typeof slug === 'string' && slug.length > 0 ? slug : 'default'
}
