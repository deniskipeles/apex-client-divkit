import { ServerTool, text } from '../ServerTool.ts'
import { workspaceKey } from '../types.ts'
import type { ToolContext, ToolOutcome } from '../types.ts'

// `tools://clipboard` — the dev-server's own clipboard. A browser preview cannot
// read the host clipboard without a user gesture, so the server keeps the value
// the card copied; `paste` returns exactly what was written.

const CLIPS = new Map<string, string>()

export class ClipboardTool extends ServerTool {
  readonly id = 'clipboard'
  readonly aliases = ['device.clipboard', 'device.clipboard@1', 'clip']
  readonly operations = ['copy', 'paste', 'clear']

  invoke(operation: string, payload: Record<string, unknown>, context: ToolContext): ToolOutcome {
    const key = workspaceKey(context)
    switch (operation || 'paste') {
      case 'copy': {
        const value = text(payload.text) ?? text(payload.value) ?? ''
        CLIPS.set(key, value)
        return this.ok({ status: 'SUCCESS', text: value, state: 'copied', op: 'copy', length: value.length })
      }
      case 'paste': {
        const value = CLIPS.get(key) ?? ''
        return this.ok({ status: 'SUCCESS', text: value, state: 'pasted', op: 'paste', length: value.length })
      }
      case 'clear':
        CLIPS.delete(key)
        return this.ok({ status: 'EMPTY', text: '', state: 'cleared', op: 'clear', length: 0 })
      default:
        return this.refuse('UNSUPPORTED', `clipboard/${operation} is not supported`)
    }
  }
}
