import { ServerTool, text } from '../ServerTool.ts'
import type { ToolOutcome } from '../types.ts'

// `tools://scan` — the preview has no camera and no image decoder, so only the
// injected (`simulated_code`) path can produce a real result. That is the path
// the demo cards and the conformance cases use. With no injected code the tool
// refuses with `NO_INPUT` instead of inventing a scan.

export class ScanTool extends ServerTool {
  readonly id = 'scan'
  readonly aliases = ['vision.scan_code', 'vision.scan_code@1', 'barcode', 'scanner']
  readonly operations = ['scan', 'continuous_scan', 'simulate', 'parse', 'ocr']

  invoke(operation: string, payload: Record<string, unknown>): ToolOutcome {
    const op = operation || 'scan'
    if (op === 'ocr') return this.refuse('NO_INPUT', 'No image source host for OCR')

    const injected =
      text(payload.simulated_code) ?? text(payload.code) ?? text(payload.raw_value) ?? text(payload.text)
    if (injected === null || injected.length === 0) {
      return this.refuse(
        'NO_INPUT',
        op === 'continuous_scan'
          ? 'Continuous scan needs a camera host'
          : 'Provide a camera frame, an image or a simulated_code',
      )
    }

    const format = inferFormat(injected)
    const target = (text(payload.target_format) ?? 'both').toLowerCase()
    if (!targetMatches(target, format)) {
      return this.refuse('FORMAT_MISMATCH', `Decoded ${format} does not match the requested ${target}`, {
        raw_value: injected,
        format,
      })
    }

    return this.ok({
      status: 'SUCCESS',
      raw_value: injected,
      format,
      type: isTwoDimensional(format) ? '2D' : '1D',
      timestamp: new Date().toISOString(),
    })
  }
}

function inferFormat(code: string): string {
  if (/^\d{13}$/.test(code)) return 'EAN_13'
  if (/^\d{12}$/.test(code)) return 'UPC_A'
  if (/^\d{8}$/.test(code)) return 'EAN_8'
  if (/^https?:\/\//i.test(code) || /^apexpos:\/\//i.test(code)) return 'QR_CODE'
  return 'CODE_128'
}

function isTwoDimensional(format: string): boolean {
  return format === 'QR_CODE' || format === 'PDF_417' || format === 'AZTEC' || format === 'DATA_MATRIX'
}

function targetMatches(target: string, format: string): boolean {
  if (target === '' || target === 'both' || target === 'any') return true
  const normalized = target.replace(/-/g, '_').toUpperCase()
  if (normalized === 'QR') return format === 'QR_CODE'
  if (normalized === 'BARCODE' || normalized === '1D') return !isTwoDimensional(format)
  if (normalized === '2D') return isTwoDimensional(format)
  return normalized === format
}
