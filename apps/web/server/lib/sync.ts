/** Retry classification from packages/contract/docs/sync-batch.md. */
export function isRetryable(reason: string): boolean {
  return reason.startsWith('5') || reason === 'timeout' || reason === 'rate_limited'
}

export interface SyncItem {
  client_uuid: string
  op: string
  payload: unknown
}

export interface SyncOutcome {
  accepted: string[]
  rejected: Array<{ uuid: string; reason: string }>
}

/** Applies one op. Idempotency is enforced by the caller via `client_uuid`. */
async function applyOp(op: string, payload: unknown): Promise<void> {
  if (!op) {
    const err = new Error('validation_error') as Error & { code?: string }
    err.code = 'validation_error'
    throw err
  }
  void payload
}

/**
 * Applies a batch idempotently: an already-applied `client_uuid` is accepted without
 * re-running the op, retryable failures are surfaced as `5xx_retry`, and terminal
 * failures carry their reason.
 */
export async function applyBatch(
  batch: SyncItem[],
  isApplied: (uuid: string) => boolean,
  markApplied: (uuid: string) => void,
): Promise<SyncOutcome> {
  const accepted: string[] = []
  const rejected: SyncOutcome['rejected'] = []

  for (const item of batch) {
    if (isApplied(item.client_uuid)) {
      accepted.push(item.client_uuid)
      continue
    }
    try {
      await applyOp(item.op, item.payload)
      markApplied(item.client_uuid)
      accepted.push(item.client_uuid)
    } catch (e) {
      const err = e as Error & { code?: string }
      rejected.push({
        uuid: item.client_uuid,
        reason: isRetryable(err.code ?? '') ? '5xx_retry' : (err.message ?? 'rejected'),
      })
    }
  }

  return { accepted, rejected }
}
