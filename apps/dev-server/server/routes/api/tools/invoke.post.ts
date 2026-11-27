import { createError, defineEventHandler, readBody, setHeader } from 'h3'
import { dispatchServerTool } from '../../../utils/tools/index.ts'
import type { ToolContext } from '../../../utils/tools/types.ts'

interface InvokeBody {
  tool?: unknown
  operation?: unknown
  payload?: unknown
  context?: unknown
}

// POST /api/tools/invoke
//
// The same-origin "magic API" the preview's server execution mode calls. It is
// deliberately permissive about unknown tools: the answer is
// `{ status: 'unavailable', reason: 'unknown-tool' }` so the client can fall back
// to its local implementation instead of surfacing a transport error.
export default defineEventHandler(async (event) => {
  const body = (await readBody(event)) as InvokeBody | null | undefined
  if (!body || typeof body !== 'object' || typeof body.tool !== 'string' || body.tool.trim() === '') {
    throw createError({ statusCode: 400, statusMessage: 'Missing tool id' })
  }

  setHeader(event, 'Cache-Control', 'no-store')
  return dispatchServerTool({
    tool: body.tool,
    operation: typeof body.operation === 'string' ? body.operation : '',
    payload: asRecord(body.payload),
    context: asContext(body.context),
  })
})

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {}
}

function asContext(value: unknown): ToolContext | undefined {
  const record = asRecord(value)
  const context: ToolContext = {}
  if (record.collections && typeof record.collections === 'object') {
    context.collections = record.collections as Record<string, unknown>
  }
  if (record.workspace && typeof record.workspace === 'object') {
    context.workspace = record.workspace as { slug?: string }
  }
  return context
}
