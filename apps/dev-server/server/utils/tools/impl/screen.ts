import { ServerTool, text } from '../ServerTool.ts'
import { workspaceKey } from '../types.ts'
import type { ToolContext, ToolOutcome } from '../types.ts'

// `tools://screen` — the keep-awake flag is real state; screenshots and screen
// recording need a capture host the dev-server does not have.

const KEEP_AWAKE = new Map<string, boolean>()

export class ScreenTool extends ServerTool {
  readonly id = 'screen'
  readonly aliases = ['device.screen', 'device.screen@1', 'wake-lock']
  readonly operations = ['keep-awake', 'screenshot', 'recording', 'status']

  invoke(operation: string, payload: Record<string, unknown>, context: ToolContext): ToolOutcome {
    const key = workspaceKey(context)
    switch (operation || 'status') {
      case 'keep-awake': {
        const state = (text(payload.state) ?? 'on').toLowerCase()
        const enabled = state === 'on' || state === 'true' || state === 'enable'
        KEEP_AWAKE.set(key, enabled)
        return this.ok({ status: enabled ? 'ENABLED' : 'DISABLED', enabled, level: text(payload.level) ?? 'screen' })
      }
      case 'status':
        return this.ok({ status: 'READY', enabled: KEEP_AWAKE.get(key) ?? false, recording: false })
      case 'screenshot':
      case 'recording':
        return this.refuse('UNSUPPORTED', 'No screen capture host on the dev-server')
      default:
        return this.refuse('UNSUPPORTED', `screen/${operation} is not supported`)
    }
  }
}
