import type { ToolResult } from './ToolResult'

export interface ToolBridge {
  invoke(capability: string, args: Record<string, unknown>): Promise<ToolResult>
}
