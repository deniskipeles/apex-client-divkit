import { describe, expect, it } from 'vitest'
import { base45Decode, base45Encode, dispatchWorkspaceUrl } from './toolRuntime'

describe('base45 (RFC 9285)', () => {
  it('round-trips text, including an odd byte count', () => {
    for (const text of ['', 'A', 'AB', 'hello world', '{"a":1,"b":[1,2,3]}']) {
      expect(base45Decode(base45Encode(text))).toBe(text)
    }
  })

  it('rejects a character outside the alphabet', () => {
    expect(base45Decode('!!!')).toBeNull()
  })
})

describe('tools://codec', () => {
  it('chunks a payload and maps the outputs the card asked for', async () => {
    const payload = {
      json: JSON.stringify({ order: 'A-1', lines: [1, 2, 3, 4, 5, 6, 7, 8] }),
      max_bytes: 120,
      encoding: 'base45',
      output: { chunks: 'user.qr_chunks', count: 'user.qr_count', checksum: 'user.qr_checksum' },
    }
    const report = await dispatchWorkspaceUrl('tools://codec/json/chunk', payload, {})
    expect(report.status).toBe('ok')
    const chunks = report.updates['user.qr_chunks']
    expect(Array.isArray(chunks)).toBe(true)
    expect((chunks as string[]).length).toBe(report.updates['user.qr_count'])
    for (const chunk of chunks as string[]) {
      expect(chunk.startsWith('C1|')).toBe(true)
      expect(chunk.length).toBeLessThanOrEqual(120)
    }
    expect(String(report.updates['user.qr_checksum'])).toHaveLength(12)
  })

  it('re-assembles the chunks back into the original json', async () => {
    const json = JSON.stringify({ order: 'A-1', note: 'pickup' })
    const chunked = await dispatchWorkspaceUrl('tools://codec/json/chunk', { json, max_bytes: 90 }, {})
    const chunks = chunked.data?.chunks as string[]
    const report = await dispatchWorkspaceUrl('tools://codec/json/assemble', { chunks, encoding: 'base45' }, {})
    expect(report.status).toBe('ok')
    expect(report.data?.valid).toBe(true)
    expect(report.data?.json).toBe(json)
  })

  it('reports an invalid document as INVALID, not as a failure', async () => {
    const report = await dispatchWorkspaceUrl('tools://codec/json/validate', { json: '{nope' }, {})
    expect(report.status).toBe('ok')
    expect(report.data?.status).toBe('INVALID')
    expect(report.data?.valid).toBe(false)
  })

  it('fails a missing argument instead of inventing one', async () => {
    const report = await dispatchWorkspaceUrl('tools://codec/json/chunk', {}, {})
    expect(report.status).toBe('failed')
    expect(report.message).toContain('missing json argument')
    expect(report.updates).toEqual({})
  })
})

describe('unimplemented capabilities are reported, never faked', () => {
  it('answers "unavailable" for a native tool', async () => {
    const report = await dispatchWorkspaceUrl('tools://code-generate/generate', { content: 'x' }, {})
    expect(report.status).toBe('unavailable')
    expect(report.updates).toEqual({})
    expect(report.message).toContain('no browser implementation')
  })

  it('answers "unavailable" for the SQLite plane', async () => {
    const report = await dispatchWorkspaceUrl('local-db://op/db.query', { collection: 'inventory' }, {})
    expect(report.status).toBe('unavailable')
    expect(report.updates).toEqual({})
  })

  it('answers "unavailable" for an unknown scheme', async () => {
    const report = await dispatchWorkspaceUrl('https://example.com', {}, {})
    expect(report.status).toBe('unavailable')
  })
})
