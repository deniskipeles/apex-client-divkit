import { codec } from '@apex/pos-ui/android-runtime'
import { ServerTool } from '../ServerTool.ts'
import type { ToolOutcome } from '../types.ts'

// `tools://codec` is the one family that is byte-identical on both sides: the
// browser runtime and this tool call the same pure `codec()` implementation.

export class CodecTool extends ServerTool {
  readonly id = 'codec'
  readonly aliases = ['data.codec', 'data.codec@1', 'json']
  readonly operations = [
    'base64',
    'base45',
    'json/validate',
    'json/extract',
    'json/stringify',
    'json/chunk',
    'json/assemble',
  ]

  async invoke(operation: string, payload: Record<string, unknown>): Promise<ToolOutcome> {
    return codec(operation, payload)
  }
}
