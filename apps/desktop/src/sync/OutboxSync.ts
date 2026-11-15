import Database from '@tauri-apps/plugin-sql'

const BATCH_SIZE = 50
const MAX_ATTEMPTS = 10

interface PendingRow {
  client_uuid: string
  op: string
  payload: string
  attempts: number
}

interface SyncResponse {
  accepted?: string[]
  rejected?: Array<{ uuid: string; reason?: string }>
}

/** Retry classification from packages/contract/docs/sync-batch.md. */
export function isRetryable(reason: string): boolean {
  return reason.startsWith('5') || reason === 'timeout' || reason === 'rate_limited'
}

/**
 * Offline-first outbox. Reads `pending_sync` rows, POSTs a batch to the edge, and applies
 * `accepted`/`rejected` per the sync-batch contract. Transport errors leave the batch queued.
 */
export class OutboxSync {
  constructor(
    private readonly edgeEndpoint: string,
    private readonly db: Database,
  ) {}

  static open(path = 'sqlite:apex.db'): Promise<Database> {
    return Database.load(path)
  }

  async push(): Promise<{ accepted: number; deadLettered: number }> {
    const rows = await this.db.select<PendingRow[]>(
      'SELECT client_uuid, op, payload, attempts FROM pending_sync WHERE attempts < ? ORDER BY id LIMIT ?',
      [MAX_ATTEMPTS, BATCH_SIZE],
    )
    if (rows.length === 0) return { accepted: 0, deadLettered: 0 }

    let response: Response
    try {
      response = await fetch(new URL('/sync', this.edgeEndpoint).toString(), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json; charset=utf-8' },
        body: JSON.stringify({
          batch: rows.map((row) => ({
            client_uuid: row.client_uuid,
            op: row.op,
            payload: JSON.parse(row.payload),
          })),
        }),
      })
    } catch {
      return { accepted: 0, deadLettered: 0 }
    }

    if (!response.ok) return { accepted: 0, deadLettered: 0 }

    const { accepted = [], rejected = [] } = (await response.json()) as SyncResponse

    for (const uuid of accepted) {
      await this.db.execute('DELETE FROM pending_sync WHERE client_uuid = ?', [uuid])
    }

    let deadLettered = 0
    for (const { uuid, reason } of rejected) {
      if (isRetryable(reason ?? '')) {
        await this.db.execute('UPDATE pending_sync SET attempts = attempts + 1 WHERE client_uuid = ?', [uuid])
      } else {
        await this.db.execute('UPDATE pending_sync SET dead = 1 WHERE client_uuid = ?', [uuid])
        deadLettered += 1
      }
    }

    return { accepted: accepted.length, deadLettered }
  }
}
