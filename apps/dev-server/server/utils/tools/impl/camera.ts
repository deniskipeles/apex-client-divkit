import { ServerTool } from '../ServerTool.ts'
import type { ToolOutcome } from '../types.ts'

// `tools://camera` needs a CameraX host the dev-server does not own. `get_status`
// is answerable (READY, available:false); capture and torch refuse.

export class CameraTool extends ServerTool {
  readonly id = 'camera'
  readonly aliases = ['capture_photo', 'vision.capture_photo', 'vision.capture_photo@1', 'torch']
  readonly operations = ['capture', 'torch_toggle', 'get_status']
  readonly supported = false

  invoke(operation: string): ToolOutcome {
    if (operation === 'get_status' || operation === 'status') {
      return this.ok({ status: 'READY', available: false, reason: 'No camera host' })
    }
    return this.refuse('UNSUPPORTED', 'No camera host on the dev-server', { available: false })
  }
}
