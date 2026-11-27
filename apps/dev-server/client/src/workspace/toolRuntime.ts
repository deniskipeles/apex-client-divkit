import {
  applyOutputMapping,
  codec,
  outputMappingOf,
  resolveJsonExpressions,
  type DivValue,
  type DivVars,
  type WorkspaceMemoryDb,
} from '@apex/pos-ui/android-runtime'
import { dispatchLocalDb } from './localDb'

export {
  base45DecodeText as base45Decode,
  base45EncodeText as base45Encode,
  base64Decode,
  base64Encode,
} from '@apex/pos-ui/android-runtime'

export { applyOutputMapping } from '@apex/pos-ui/android-runtime'

// ---------------------------------------------------------------------------
// Playground tool dispatch.
//
// Two execution modes, selected by the workspace UI:
//
//   local  — every capability the browser can execute for real (the `data.codec@1`
//            family) runs in-page; the rest is reported as `unavailable`. Never fakes.
//   server — the same-origin Nitro tool API (`POST /api/tools/invoke`) executes the
//            capability, so the preview can run tools that need a process (files,
//            HTTP/LAN, printing, embeddings, …) and any custom `ServerTool` a user
//            registers. If the server has no implementation, dispatch falls back to
//            the local implementation; if neither has one, the call is `unavailable`.
//
// A fabricated success is exactly the failure mode a preview must not have.
// ---------------------------------------------------------------------------

export type ToolRuntimeStatus = 'ok' | 'failed' | 'unavailable'
export type ToolExecutionMode = 'local' | 'server'

export interface ToolRuntimeReport {
  url: string
  status: ToolRuntimeStatus
  message?: string
  tool?: string
  operation?: string
  data?: Record<string, unknown>
  updates: Record<string, DivValue>
}

/** Request the client sends to `POST /api/tools/invoke`. */
export interface ServerToolInvocation {
  tool: string
  operation: string
  payload: Record<string, unknown>
  context?: { collections?: Record<string, unknown>; workspace?: { slug: string } }
}

/** Response the server tool API returns (matches `ServerToolReport`). */
export interface ServerToolResult {
  status: ToolRuntimeStatus
  message?: string
  data?: Record<string, unknown>
  /** Why a server implementation was not used, e.g. `unknown-tool` / `server-unavailable`. */
  reason?: string
}

export interface ToolRuntimeContext {
  collections?: Record<string, unknown>
  workspace?: { slug: string }
  /** The workspace's in-memory SQLite mirror (`local-db://`). */
  db?: WorkspaceMemoryDb | null
  /** Where `tools://` runs. Defaults to `local`. */
  mode?: ToolExecutionMode
  /** Injected by the host so tests can drive server mode without a network. */
  invokeServer?: (invocation: ServerToolInvocation) => Promise<ServerToolResult>
}

export async function dispatchWorkspaceUrl(
  url: string,
  payload: unknown,
  variables: DivVars,
  context: ToolRuntimeContext = {},
): Promise<ToolRuntimeReport> {
  const body = (payload && typeof payload === 'object' ? payload : {}) as Record<string, unknown>
  const resolved = resolveJsonExpressions(body, variables, {}) as Record<string, unknown>

  if (url.startsWith('tools://')) {
    const rest = url.slice('tools://'.length)
    const slash = rest.indexOf('/')
    const tool = slash === -1 ? rest : rest.slice(0, slash)
    const operation = slash === -1 ? '' : rest.slice(slash + 1)
    return runTool(tool, operation, resolved, url, context)
  }

  if (url.startsWith('div-action://terminal.tool.invoke')) {
    const query = new URLSearchParams(url.slice(url.indexOf('?') + 1))
    const tool = query.get('tool') ?? ''
    const operation = query.get('op') ?? ''
    return runTool(tool, operation, resolved, url, context)
  }

  if (url.startsWith('local-db://')) {
    const report = await dispatchLocalDb(url, resolved, variables, context.db ?? null)
    return {
      url,
      tool: 'local-db',
      operation: url.slice('local-db://op/'.length),
      status: report.status,
      message: report.message,
      data: report.data,
      updates: report.updates,
    }
  }

  if (url.startsWith('hardware://')) {
    return {
      url,
      status: 'unavailable',
      message: 'hardware:// needs a device host; use tools:// on the device',
      updates: {},
    }
  }

  return { url, status: 'unavailable', message: 'no handler for this scheme', updates: {} }
}

const CODEC_ALIASES = new Set(['codec', 'data.codec', 'data.codec@1', 'json'])

async function runTool(
  rawTool: string,
  rawOperation: string,
  payload: Record<string, unknown>,
  url: string,
  context: ToolRuntimeContext,
): Promise<ToolRuntimeReport> {
  const tool = rawTool.trim().toLowerCase()
  const operation = rawOperation.trim().toLowerCase()
  const args = flattenOptions(payload)
  const mapping = outputMappingOf(args)

  if (context.mode === 'server' && context.invokeServer) {
    try {
      const remote = await context.invokeServer({
        tool,
        operation,
        payload: args,
        context: { collections: context.collections, workspace: context.workspace },
      })
      if (remote && remote.status !== 'unavailable') {
        return finish(
          url,
          tool,
          operation,
          { status: remote.status, message: remote.message, data: remote.data },
          mapping,
        )
      }
    } catch {
      // A missing/unreachable server is not an error the card should surface: the
      // preview degrades to the local implementation exactly as it does in static builds.
    }
  }

  if (CODEC_ALIASES.has(tool)) {
    return finish(url, tool, operation, await codec(operation, args), mapping)
  }

  return {
    url,
    tool,
    operation,
    status: 'unavailable',
    message: `\`${rawTool}\` is a native capability with no browser implementation`,
    updates: {},
  }
}

type ToolOutcome = { status: ToolRuntimeStatus; message?: string; data?: Record<string, unknown> }

function finish(
  url: string,
  tool: string,
  operation: string,
  outcome: ToolOutcome,
  mapping: Record<string, string>,
): ToolRuntimeReport {
  return {
    url,
    tool,
    operation,
    status: outcome.status,
    message: outcome.message,
    data: outcome.data,
    updates: applyOutputMapping(outcome.data ?? {}, mapping),
  }
}

/**
 * `EngineRouter` flattens `payload.options` into flat string args. The card
 * payload nests them, so every tool sees the same shape as it does on the device.
 */
function flattenOptions(payload: Record<string, unknown>): Record<string, unknown> {
  const options = payload.options
  if (!options || typeof options !== 'object' || Array.isArray(options)) return payload
  const merged: Record<string, unknown> = { ...(options as Record<string, unknown>), ...payload }
  delete merged.options
  return merged
}
