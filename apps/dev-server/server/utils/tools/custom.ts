import { ServerTool, text } from './ServerTool.ts'
import type { ToolContext, ToolOutcome } from './types.ts'

// ---------------------------------------------------------------------------
// A worked example of extending the server inventory.
//
// Copy this file, change `id` / `aliases` / `operations`, and add the new
// instance to `impl/index.ts`. The card keeps using `tools://echo/echo`; no
// client change is needed because dispatch is id-based.
// ---------------------------------------------------------------------------

export class EchoTool extends ServerTool {
  readonly id = 'echo'
  readonly aliases = ['custom.echo', 'demo.echo']
  readonly operations = ['ping', 'echo']

  invoke(operation: string, payload: Record<string, unknown>, context: ToolContext): ToolOutcome {
    if ((operation || 'ping') === 'ping') {
      return this.ok({ status: 'SUCCESS', pong: true, workspace: context.workspace?.slug ?? 'default' })
    }
    const message = text(payload.message) ?? text(payload.text) ?? ''
    return this.ok({ status: 'SUCCESS', message, echo: message, length: message.length })
  }
}
