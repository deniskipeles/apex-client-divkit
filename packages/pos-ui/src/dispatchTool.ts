import { canonicalCapabilityId } from '@apex/contract'
import type { ToolBridge } from './ToolBridge'
import type { ToolResult } from './ToolResult'

export async function dispatchTool(
  bridge: ToolBridge,
  alias: string,
  args: Record<string, unknown>,
): Promise<ToolResult> {
  const id = canonicalCapabilityId(alias)
  if (!id) return { kind: 'unsupported', tool: alias, reason: 'unknown capability' }
  return bridge.invoke(id, args)
}
