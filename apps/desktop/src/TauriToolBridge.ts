import { invoke } from '@tauri-apps/api/core'
import type { ToolBridge, ToolResult } from '@apex/pos-ui'

export const TauriToolBridge: ToolBridge = {
  async invoke(capability, args): Promise<ToolResult> {
    try {
      const data = await invoke<Record<string, unknown>>('tool_invoke', { capability, args })
      return { kind: 'success', data }
    } catch (e) {
      const err = e as { kind?: string; permission?: string; message?: string }
      if (err?.kind === 'permissionDenied') {
        return { kind: 'permissionDenied', tool: capability, permission: err.permission ?? 'unknown' }
      }
      return { kind: 'failed', tool: capability, message: String(err?.message ?? e) }
    }
  },
}
