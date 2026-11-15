import type { ToolBridge, ToolResult } from '@apex/pos-ui'

export const NoopToolBridge: ToolBridge = {
  async invoke(capability): Promise<ToolResult> {
    return {
      kind: 'unsupported',
      tool: capability,
      reason: 'Web dashboard is read-only; run the desktop POS for hardware access.',
    }
  },
}
