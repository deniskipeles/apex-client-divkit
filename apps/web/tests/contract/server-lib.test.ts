import { applyBatch, isRetryable } from '../../server/lib/sync'
import { validateCard } from '../../server/lib/validateCard'

const validCard = {
  card: {
    log_id: 'demo',
    states: [
      { state_id: 0, div: { type: 'container', items: [{ type: 'text', text: 'hi' }] } },
    ],
  },
}

describe('sync.applyBatch', () => {
  it('classifies retryable reasons like the contract', () => {
    expect(isRetryable('503')).toBe(true)
    expect(isRetryable('timeout')).toBe(true)
    expect(isRetryable('rate_limited')).toBe(true)
    expect(isRetryable('validation_error')).toBe(false)
  })

  it('accepts new ops and reports terminal failures', async () => {
    const applied = new Set<string>()
    const out = await applyBatch(
      [
        { client_uuid: 'a', op: 'insert', payload: {} },
        { client_uuid: 'b', op: '', payload: {} },
      ],
      (u) => applied.has(u),
      (u) => applied.add(u),
    )
    expect(out.accepted).toEqual(['a'])
    expect(out.rejected).toEqual([{ uuid: 'b', reason: 'validation_error' }])
  })

  it('is idempotent for an already-applied uuid', async () => {
    const applied = new Set<string>(['dup'])
    const out = await applyBatch(
      [{ client_uuid: 'dup', op: '', payload: {} }],
      (u) => applied.has(u),
      (u) => applied.add(u),
    )
    expect(out.accepted).toEqual(['dup'])
    expect(out.rejected).toEqual([])
  })
})

describe('validateCard', () => {
  it('accepts a card whose component tree is in the subset', () => {
    expect(validateCard(validCard)).toEqual({ ok: true, errors: [] })
  })

  it('rejects a component type outside the subset', () => {
    const result = validateCard({
      card: { log_id: 'x', states: [{ state_id: 0, div: { type: 'video', url: 'v' } }] },
    })
    expect(result.ok).toBe(false)
    expect(result.errors.join(' ')).toMatch(/states\[0\]\.div/)
  })

  it('requires log_id and states', () => {
    expect(validateCard({ card: { states: [] } }).ok).toBe(false)
    expect(validateCard({}).ok).toBe(false)
  })
})
