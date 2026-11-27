import { ServerTool, requiredText, text } from '../ServerTool.ts'
import type { ToolOutcome } from '../types.ts'

// `tools://code-generate` needs a symbology encoder (ZXing on the device). The
// dev-server bundles none, so it refuses rather than returning a broken image.

export class CodeGenerateTool extends ServerTool {
  readonly id = 'code-generate'
  readonly aliases = [
    'vision.generate_code',
    'vision.generate_code@1',
    'code_generate',
    'qr-code',
    'barcode-generator',
  ]
  readonly operations = ['generate', 'qr', 'barcode', 'render']
  readonly supported = false

  invoke(operation: string, payload: Record<string, unknown>): ToolOutcome {
    const content = requiredText(payload, 'content', 'text', 'value')
    const format = (text(payload.format) ?? (operation === 'qr' ? 'qr' : 'CODE_128')).toUpperCase()
    return this.refuse('UNSUPPORTED', 'No barcode encoder bundled on the dev-server (use the device)', {
      content,
      format: format === 'QR' ? 'QR_CODE' : format,
      operation,
    })
  }
}
