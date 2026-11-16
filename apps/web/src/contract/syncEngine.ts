/**
 * Web mirror of the sync contract (packages/contract/docs/sync-batch.md).
 * The server contract is idempotent by `client_uuid`.
 */
export type SyncOutcome = 'success' | 'retry';

export interface SyncRow {
  client_uuid: string;
  op: string;
  payload: string;
}

export class SyncEngine {
  static readonly BATCH_SIZE = 50;
  static readonly MAX_ATTEMPTS = 10;

  static isRetryable(reason: string): boolean {
    return reason.startsWith('5') || reason === 'timeout' || reason === 'rate_limited';
  }

  constructor(private readonly endpoint: string) {}

  async sync(rows: SyncRow[]): Promise<SyncOutcome> {
    if (rows.length === 0) return 'success';

    let response: Response;
    try {
      response = await fetch(new URL('/sync', this.endpoint).toString(), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json; charset=utf-8' },
        body: JSON.stringify({ batch: rows }),
      });
    } catch {
      return 'retry';
    }

    if (!response.ok) return 'retry';

    let json: { rejected?: Array<{ uuid: string; reason?: string }> };
    try {
      json = (await response.json()) as { rejected?: Array<{ uuid: string; reason?: string }> };
    } catch {
      return 'success';
    }
    const rejected = json.rejected ?? [];
    return rejected.some((r) => r.reason !== undefined && SyncEngine.isRetryable(r.reason))
      ? 'retry'
      : 'success';
  }
}
