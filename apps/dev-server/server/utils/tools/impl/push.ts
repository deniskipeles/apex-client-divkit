import { ServerTool, text } from '../ServerTool.ts'
import { workspaceKey } from '../types.ts'
import type { ToolContext, ToolOutcome } from '../types.ts'

// `tools://push` — the dev-server cannot reach a push provider, so alerts land in
// a per-workspace ledger. That is the same observable shape the device reports
// (`LOGGED_TO_LEDGER` + a `push_…` id) without pretending a device was notified.

interface LedgerEntry {
  id: string
  title: string
  message: string
  sentAt: number
}

const LEDGERS = new Map<string, LedgerEntry[]>()

export class PushTool extends ServerTool {
  readonly id = 'push'
  readonly aliases = ['network.push', 'network.push@1', 'alerts', 'notify']
  readonly operations = ['send_alert', 'notify', 'send']

  invoke(operation: string, payload: Record<string, unknown>, context: ToolContext): ToolOutcome {
    const key = workspaceKey(context)
    const title = text(payload.title) ?? 'Apex POS'
    const message = text(payload.message) ?? text(payload.body) ?? text(payload.text)
    if (message === null || message.length === 0) throw new Error('missing message argument')

    const entry: LedgerEntry = {
      id: `push_${randomId()}`,
      title,
      message,
      sentAt: Date.now(),
    }
    const ledger = LEDGERS.get(key) ?? []
    ledger.push(entry)
    LEDGERS.set(key, ledger)

    return this.ok({
      status: 'LOGGED_TO_LEDGER',
      notification_id: entry.id,
      title,
      message,
      op: operation || 'send_alert',
      count: ledger.length,
      ledger_json: JSON.stringify(ledger.slice(-20)),
    })
  }
}

function randomId(): string {
  const bytes = new Uint8Array(4)
  globalThis.crypto.getRandomValues(bytes)
  return [...bytes].map((byte) => byte.toString(16).padStart(2, '0')).join('')
}
