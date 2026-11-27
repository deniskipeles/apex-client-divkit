import { applyOutputMapping, outputMappingOf } from '@apex/pos-ui/android-runtime'
import { EchoTool } from './custom.ts'
import { TOOLS } from './impl/index.ts'
import type { ServerTool } from './ServerTool.ts'
import type { ServerToolInfo, ServerToolReport, ServerToolRequest } from './types.ts'

/**
 * The server-side tool registry.
 *
 * Add a capability by appending one `ServerTool` instance here (or to
 * `impl/index.ts`). `findServerTool` matches the canonical id and every alias
 * case-insensitively, so `tools://data.codec/base64` and
 * `div-action://terminal.tool.invoke?tool=data.codec@1` both reach `CodecTool`.
 */
const REGISTRY: ServerTool[] = [...TOOLS, new EchoTool()]

export function findServerTool(name: string): ServerTool | undefined {
  const needle = (name ?? '').trim()
  if (!needle) return undefined
  return REGISTRY.find((tool) => tool.matches(needle))
}

export function listServerTools(): ServerToolInfo[] {
  return REGISTRY.map((tool) => ({
    id: tool.id,
    aliases: [...tool.aliases],
    operations: [...tool.operations],
    supported: tool.supported,
  }))
}

export async function dispatchServerTool(request: ServerToolRequest): Promise<ServerToolReport> {
  const tool = findServerTool(request.tool ?? '')
  const operation = (request.operation ?? '').trim().toLowerCase()
  const payload = flattenOptions(request.payload ?? {})
  const mapping = outputMappingOf(payload)

  if (!tool) {
    return {
      tool: request.tool ?? '',
      operation,
      status: 'unavailable',
      reason: 'unknown-tool',
      message: `No server implementation for '${request.tool ?? ''}'`,
      updates: {},
    }
  }

  try {
    const outcome = await tool.invoke(operation, payload, request.context ?? {})
    return {
      tool: tool.id,
      operation,
      status: outcome.status,
      message: outcome.message,
      data: outcome.data,
      updates: applyOutputMapping(outcome.data ?? {}, mapping),
    }
  } catch (error) {
    const data = { status: 'FAILED' }
    return {
      tool: tool.id,
      operation,
      status: 'failed',
      message: error instanceof Error ? error.message : String(error),
      data,
      updates: applyOutputMapping(data, mapping),
    }
  }
}

/**
 * `EngineRouter` flattens `payload.options` into flat string args. Mirror it so a
 * card payload (`{ options: { text: '…' } }`) reaches the tool as `{ text: '…' }`.
 * Top-level keys win; `options` is removed.
 */
export function flattenOptions(payload: Record<string, unknown>): Record<string, unknown> {
  const options = payload.options
  if (!options || typeof options !== 'object' || Array.isArray(options)) return payload
  const merged: Record<string, unknown> = { ...(options as Record<string, unknown>), ...payload }
  delete merged.options
  return merged
}
