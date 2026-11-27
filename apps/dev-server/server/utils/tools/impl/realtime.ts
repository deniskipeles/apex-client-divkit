import { ServerTool } from '../ServerTool.ts'
import type { ToolOutcome } from '../types.ts'

// `tools://realtime-ws` needs a WebSocket transport. Node's global WebSocket is a
// client only and the dev-server opens no channels, so connect refuses; the
// remaining operations report the same `not connected` failure a device would.

export class RealtimeTool extends ServerTool {
  readonly id = 'realtime-ws'
  readonly aliases = ['realtime_ws', 'network.realtime', 'network.realtime@1', 'realtime', 'wss']
  readonly operations = ['connect', 'disconnect', 'send', 'subscribe']
  readonly supported = false

  invoke(operation: string): ToolOutcome {
    switch (operation || 'connect') {
      case 'connect':
        return this.refuse('UNSUPPORTED', 'no websocket transport on the dev-server', {
          connection_state: 'DISCONNECTED',
        })
      case 'disconnect':
        return this.ok({ status: 'DISCONNECTED', connection_state: 'DISCONNECTED' })
      case 'send':
      case 'subscribe':
        return this.refuse('FAILED', 'not connected', { connection_state: 'DISCONNECTED' })
      default:
        return this.refuse('UNSUPPORTED', `realtime-ws/${operation} is not supported`)
    }
  }
}
