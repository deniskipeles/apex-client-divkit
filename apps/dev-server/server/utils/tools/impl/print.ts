import { ServerTool, text } from '../ServerTool.ts'
import { workspaceKey } from '../types.ts'
import type { ToolContext, ToolOutcome } from '../types.ts'

// `tools://print` — ESC/POS encoding is real; the transport is not. The
// dev-server owns no Bluetooth adapter, so discovery/pairing/connect refuse with
// `DENIED` and a print without a connected printer refuses with `UNSUPPORTED`.

const CONNECTED = new Map<string, Set<string>>()

export class PrintTool extends ServerTool {
  readonly id = 'print'
  readonly aliases = ['printer', 'device.printer', 'device.printer@1', 'escpos']
  readonly operations = ['scan', 'pair', 'connect', 'disconnect', 'print', 'status']

  invoke(operation: string, payload: Record<string, unknown>, context: ToolContext): ToolOutcome {
    const key = workspaceKey(context)
    switch (operation || 'status') {
      case 'scan':
        return this.refuse('DENIED', 'No Bluetooth adapter on the dev-server', { devices: [] })
      case 'status':
        return this.ok({
          status: 'READY',
          connected: (CONNECTED.get(key)?.size ?? 0) > 0,
          adapter: 'none',
          devices: 0,
        })
      case 'pair':
      case 'connect': {
        const mac = text(payload.mac) ?? text(payload.address)
        if (mac === null || mac.length === 0) throw new Error('missing mac argument')
        return this.refuse('DENIED', 'No Bluetooth adapter on the dev-server', { mac })
      }
      case 'disconnect': {
        const address = text(payload.address) ?? text(payload.mac)
        if (address === null || address.length === 0) throw new Error('missing address argument')
        CONNECTED.get(key)?.delete(address)
        return this.ok({ status: 'DISCONNECTED', address })
      }
      case 'print': {
        const receipt = text(payload.receipt) ?? text(payload.text)
        if (receipt === null || receipt.length === 0) throw new Error('missing receipt argument')
        const address = text(payload.address) ?? text(payload.mac)
        const connected = address !== null && (CONNECTED.get(key)?.has(address) ?? false)
        if (!connected) {
          return this.refuse('UNSUPPORTED', `No printer connected${address ? ` at ${address}` : ''}`, { bytes: 0 })
        }
        const bytes = encodeEscPos(receipt)
        return this.ok({
          status: 'SUCCESS',
          address,
          bytes: bytes.length,
          preview: bytes.toString('base64'),
          count: 1,
        })
      }
      default:
        return this.refuse('UNSUPPORTED', `print/${operation} is not supported`)
    }
  }
}

/** Minimal ESC/POS receipt: init, text with line feeds, partial cut. */
export function encodeEscPos(receipt: string): Buffer {
  const init = Buffer.from([0x1b, 0x40])
  const body = Buffer.from(`${receipt.replace(/\r\n/g, '\n')}\n\n\n`, 'utf8')
  const cut = Buffer.from([0x1d, 0x56, 0x00])
  return Buffer.concat([init, body, cut])
}
