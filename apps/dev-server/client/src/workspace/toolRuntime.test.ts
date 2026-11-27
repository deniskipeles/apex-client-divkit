import { describe, expect, it } from 'vitest'
import { base45Decode, base45Encode, dispatchWorkspaceUrl, type ServerToolInvocation } from './toolRuntime'

function corruptPayload(chunk: string): string {
  const parts = chunk.split('|')
  const payload = parts[5] ?? ''
  const last = payload.length > 0 ? payload[payload.length - 1] : '0'
  parts[5] = payload.slice(0, -1) + (last === '0' ? '1' : '0')
  return parts.join('|')
}

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
    expect(report.data?.status).toBe('COMPLETE')
    expect(report.data?.checksum_ok).toBe(true)
    expect(report.data?.json).toBe(json)
  })

  it('reports an invalid document as INVALID and a failed run', async () => {
    const report = await dispatchWorkspaceUrl('tools://codec/json/validate', { json: '{nope' }, {})
    expect(report.status).toBe('failed')
    expect(report.data?.status).toBe('INVALID')
    expect(report.data?.valid).toBe(false)
  })

  it('extracts paths and reports the missing ones', async () => {
    const report = await dispatchWorkspaceUrl(
      'tools://codec/json/extract',
      { paths: ['order.id', 'order.missing'], options: { json: '{"order":{"id":1042}}' } },
      {},
    )
    expect(report.status).toBe('ok')
    expect(report.data?.values).toEqual(['1042', ''])
    expect(report.data?.count).toBe(2)
    expect(report.data?.missing).toEqual(['order.missing'])
  })

  it('stringifies keys and values into a JSON object', async () => {
    const report = await dispatchWorkspaceUrl(
      'tools://codec/json/stringify',
      { keys: ['order', 'total'], values: [1042, 6.5] },
      {},
    )
    expect(report.status).toBe('ok')
    expect(report.data?.json).toBe('{"order":1042,"total":6.5}')
  })

  it('assembles out-of-order chunks and verifies the checksum', async () => {
    const json = JSON.stringify({ order: 'A-1', lines: [1, 2, 3, 4, 5, 6, 7, 8] })
    const chunked = await dispatchWorkspaceUrl('tools://codec/json/chunk', { json, max_bytes: 80 }, {})
    const chunks = chunked.data?.chunks as string[]
    expect(chunks.length).toBeGreaterThan(1)
    const report = await dispatchWorkspaceUrl('tools://codec/json/assemble', { chunks: [...chunks].reverse() }, {})
    expect(report.status).toBe('ok')
    expect(report.data?.status).toBe('COMPLETE')
    expect(report.data?.json).toBe(json)
    expect(report.data?.checksum_ok).toBe(true)
  })

  it('reports a missing chunk as PARTIAL', async () => {
    const json = JSON.stringify({ order: 'A-1', lines: [1, 2, 3, 4, 5, 6, 7, 8] })
    const chunked = await dispatchWorkspaceUrl('tools://codec/json/chunk', { json, max_bytes: 80 }, {})
    const chunks = chunked.data?.chunks as string[]
    const report = await dispatchWorkspaceUrl('tools://codec/json/assemble', { chunks: chunks.slice(0, 1) }, {})
    expect(report.status).toBe('ok')
    expect(report.data?.status).toBe('PARTIAL')
    expect((report.data?.missing as string[]).length).toBeGreaterThan(0)
  })

  it('reports a duplicate chunk as PARTIAL instead of completing', async () => {
    const json = JSON.stringify({ order: 'A-1', lines: [1, 2, 3, 4, 5, 6, 7, 8] })
    const chunked = await dispatchWorkspaceUrl('tools://codec/json/chunk', { json, max_bytes: 80 }, {})
    const chunks = chunked.data?.chunks as string[]
    const report = await dispatchWorkspaceUrl(
      'tools://codec/json/assemble',
      { chunks: [chunks[0], chunks[0]] },
      {},
    )
    expect(report.status).toBe('ok')
    expect(report.data?.status).toBe('PARTIAL')
    expect((report.data?.missing as string[]).length).toBeGreaterThan(0)
  })

  it('fails a corrupted chunk with a checksum mismatch', async () => {
    const json = JSON.stringify({ order: 'A-1', lines: [1, 2, 3, 4, 5, 6, 7, 8] })
    const chunked = await dispatchWorkspaceUrl('tools://codec/json/chunk', { json, max_bytes: 80 }, {})
    const chunks = chunked.data?.chunks as string[]
    const corrupted = chunks.map((chunk, index) => (index === chunks.length - 1 ? corruptPayload(chunk) : chunk))
    const report = await dispatchWorkspaceUrl('tools://codec/json/assemble', { chunks: corrupted }, {})
    expect(report.status).toBe('failed')
    expect(report.data?.status).toBe('FAILED')
    expect(report.data?.checksum_ok).toBe(false)
    expect(report.message).toContain('Checksum')
  })

  it('assembles the demo card out-of-order base64 chunks', async () => {
    const report = await dispatchWorkspaceUrl(
      'tools://codec/json/assemble',
      {
        encoding: 'base64',
        chunks: ['C1|t1|01|02|bef57ec7f53a|ZGVm', 'C1|t1|00|02|bef57ec7f53a|YWJj'],
      },
      {},
    )
    expect(report.status).toBe('ok')
    expect(report.data?.status).toBe('COMPLETE')
    expect(report.data?.json).toBe('abcdef')
    expect(report.data?.checksum_ok).toBe(true)
  })

  it('fails the demo card corrupted base64 chunk', async () => {
    const report = await dispatchWorkspaceUrl(
      'tools://codec/json/assemble',
      { encoding: 'base64', chunks: ['C1|t1|00|01|bef57ec7f53a|YWJj'] },
      {},
    )
    expect(report.status).toBe('failed')
    expect(report.data?.status).toBe('FAILED')
    expect(report.data?.checksum_ok).toBe(false)
  })

  it('fails a missing argument instead of inventing one', async () => {
    const report = await dispatchWorkspaceUrl('tools://codec/json/chunk', {}, {})
    expect(report.status).toBe('failed')
    expect(report.message).toContain('missing json argument')
    expect(report.updates).toEqual({})
  })

  it('flattens card options before running the local tool', async () => {
    const report = await dispatchWorkspaceUrl('tools://codec/base64', { options: { text: 'apex' } }, {})
    expect(report.status).toBe('ok')
    expect(report.data?.text).toBe('YXBleA==')
  })
})

describe('server execution mode', () => {
  it('sends flattened args and maps the server result', async () => {
    const invocations: ServerToolInvocation[] = []
    const report = await dispatchWorkspaceUrl(
      'tools://scan/scan',
      { options: { simulated_code: 'APEX-42' }, output: { raw_value: 'user.scan_raw' } },
      {},
      {
        mode: 'server',
        invokeServer: async (invocation) => {
          invocations.push(invocation)
          return { status: 'ok', data: { raw_value: 'APEX-42', status: 'SUCCESS' } }
        },
      },
    )
    expect(invocations).toHaveLength(1)
    expect(invocations[0].tool).toBe('scan')
    expect(invocations[0].payload).toMatchObject({ simulated_code: 'APEX-42' })
    expect(report.status).toBe('ok')
    expect(report.updates['user.scan_raw']).toBe('APEX-42')
  })

  it('falls back to the local implementation when the server has none', async () => {
    const report = await dispatchWorkspaceUrl(
      'tools://codec/base64',
      { text: 'apex', mode: 'encode' },
      {},
      { mode: 'server', invokeServer: async () => ({ status: 'unavailable', reason: 'unknown-tool' }) },
    )
    expect(report.status).toBe('ok')
    expect(report.data?.text).toBe('YXBleA==')
  })

  it('never calls the server in local mode', async () => {
    let called = false
    const report = await dispatchWorkspaceUrl(
      'tools://codec/base64',
      { text: 'x' },
      {},
      {
        mode: 'local',
        invokeServer: async () => {
          called = true
          return { status: 'ok' }
        },
      },
    )
    expect(called).toBe(false)
    expect(report.status).toBe('ok')
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
