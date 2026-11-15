/**
 * In-memory edge store. A real deployment swaps this for durable storage; the contract
 * (idempotency by client_uuid, per-tenant schema) is what matters here.
 */
const applied = new Set<string>()
const schemas = new Map<string, unknown>()
let latest: unknown = null

export function isApplied(clientUuid: string): boolean {
  return applied.has(clientUuid)
}

export function markApplied(clientUuid: string): void {
  applied.add(clientUuid)
}

export function getSchema(tenantId: string): unknown {
  return schemas.get(tenantId)
}

export function getLatestSchema(): unknown {
  return latest
}

export function putSchema(tenantId: string, divkit: unknown): void {
  schemas.set(tenantId, divkit)
  latest = divkit
}
