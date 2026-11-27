import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { afterAll, describe, expect, it } from 'vitest'
import {
  dispatchServerTool,
  findServerTool,
  listServerTools,
  stopAllHubs,
} from '../../server/utils/tools/index.ts'

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../../../..')
const nativeSuite = readFileSync(
  resolve(repoRoot, 'apps/android/src/main/java/engine/tools/NativeToolsSuite.kt'),
  'utf8',
)
const androidToolIds = [...nativeSuite.matchAll(/toolId = "([^"]+)"/g)].map((match) => match[1])

afterAll(() => stopAllHubs())

async function invoke(
  tool: string,
  operation: string,
  payload: Record<string, unknown> = {},
  slug = `ws-${Math.random().toString(16).slice(2)}`,
) {
  return dispatchServerTool({ tool, operation, payload, context: { workspace: { slug } } })
}

describe('server tool registry', () => {
  it('advertises every capability family and the custom example', () => {
    const tools = listServerTools()
    const ids = tools.map((tool) => tool.id)
    expect(ids).toEqual(
      expect.arrayContaining([
        'codec',
        'scan',
        'files',
        'offline-cache',
        'clipboard',
        'push',
        'embedding',
        'net',
        'print',
        'screen',
        'haptics',
        'beeper',
        'camera',
        'biometrics',
        'image-manipulation',
        'libsignal',
        'realtime-ws',
        'code-generate',
        'MobileNetV4-small',
        'u-2-Net.tflite',
        'echo',
      ]),
    )
    expect(tools.find((tool) => tool.id === 'echo')?.supported).toBe(true)
    expect(tools.find((tool) => tool.id === 'camera')?.supported).toBe(false)
  })

  it('registers every tool id from the Android registry', () => {
    const ids = new Set(listServerTools().map((tool) => tool.id))
    expect(androidToolIds.length).toBeGreaterThan(0)
    expect(androidToolIds.filter((id) => !ids.has(id))).toEqual([])
  })

  it('matches canonical ids and aliases case-insensitively', () => {
    expect(findServerTool('data.codec@1')?.id).toBe('codec')
    expect(findServerTool('DEVICE.PRINTER')?.id).toBe('print')
    expect(findServerTool('mobilenetv4-small')?.id).toBe('MobileNetV4-small')
  })

  it('answers unknown-tool instead of throwing', async () => {
    const report = await invoke('does-not-exist', 'run')
    expect(report.status).toBe('unavailable')
    expect(report.reason).toBe('unknown-tool')
    expect(report.updates).toEqual({})
  })
})

describe('codec tool', () => {
  it('flattens options and maps outputs exactly like the device', async () => {
    const report = await invoke('data.codec', 'base64', {
      options: { op: 'base64', text: 'apex pos', mode: 'encode' },
      output: { text: 'user.codec_text', encoding: 'user.codec_encoding' },
    })
    expect(report.status).toBe('ok')
    expect(report.data?.text).toBe('YXBleCBwb3M=')
    expect(report.updates['user.codec_text']).toBe('YXBleCBwb3M=')
    expect(report.updates['user.codec_encoding']).toBe('base64')
  })

  it('reports an invalid document as INVALID and a failed run', async () => {
    const report = await invoke('codec', 'json/validate', { json: '{"order":' })
    expect(report.status).toBe('failed')
    expect(report.data?.status).toBe('INVALID')
  })

  it('extracts multiple paths and reports the missing ones', async () => {
    const report = await invoke('codec', 'json/extract', {
      paths: ['order.id', 'order.missing'],
      options: { json: '{"order":{"id":1042}}' },
    })
    expect(report.status).toBe('ok')
    expect(report.data?.values).toEqual(['1042', ''])
    expect(report.data?.count).toBe(2)
    expect(report.data?.missing).toEqual(['order.missing'])
  })

  it('stringifies keys and values into a JSON object', async () => {
    const report = await invoke('codec', 'json/stringify', { keys: ['order', 'total'], values: [1042, 6.5] })
    expect(report.status).toBe('ok')
    expect(report.data?.json).toBe('{"order":1042,"total":6.5}')
  })

  it('assembles out-of-order chunks and verifies the checksum', async () => {
    const json = JSON.stringify({ order: 'A-1', lines: [1, 2, 3, 4, 5, 6, 7, 8] })
    const chunked = await invoke('codec', 'json/chunk', { json, max_bytes: 80 })
    const chunks = chunked.data?.chunks as string[]
    expect(chunks.length).toBeGreaterThan(1)
    const report = await invoke('codec', 'json/assemble', { chunks: [...chunks].reverse() })
    expect(report.status).toBe('ok')
    expect(report.data?.status).toBe('COMPLETE')
    expect(report.data?.json).toBe(json)
    expect(report.data?.checksum_ok).toBe(true)
  })

  it('reports a missing chunk as PARTIAL', async () => {
    const json = JSON.stringify({ order: 'A-1', lines: [1, 2, 3, 4, 5, 6, 7, 8] })
    const chunked = await invoke('codec', 'json/chunk', { json, max_bytes: 80 })
    const chunks = chunked.data?.chunks as string[]
    const report = await invoke('codec', 'json/assemble', { chunks: chunks.slice(0, 1) })
    expect(report.status).toBe('ok')
    expect(report.data?.status).toBe('PARTIAL')
    expect((report.data?.missing as string[]).length).toBeGreaterThan(0)
  })

  it('fails a corrupted chunk with a checksum mismatch', async () => {
    const json = JSON.stringify({ order: 'A-1', lines: [1, 2, 3, 4, 5, 6, 7, 8] })
    const chunked = await invoke('codec', 'json/chunk', { json, max_bytes: 80 })
    const chunks = chunked.data?.chunks as string[]
    const corrupted = chunks.map((chunk, index) => {
      if (index !== chunks.length - 1) return chunk
      const parts = chunk.split('|')
      const payload = parts[5] ?? ''
      parts[5] = payload.slice(0, -1) + (payload.endsWith('0') ? '1' : '0')
      return parts.join('|')
    })
    const report = await invoke('codec', 'json/assemble', { chunks: corrupted })
    expect(report.status).toBe('failed')
    expect(report.data?.status).toBe('FAILED')
    expect(report.data?.checksum_ok).toBe(false)
    expect(report.message).toContain('Checksum')
  })
})

describe('scan tool', () => {
  it('decodes an injected code', async () => {
    const report = await invoke('scan', 'scan', { options: { simulated_code: 'APEX-4006381333931' } })
    expect(report.status).toBe('ok')
    expect(report.data?.raw_value).toBe('APEX-4006381333931')
    expect(report.data?.status).toBe('SUCCESS')
  })

  it('extracts the symbology on parse', async () => {
    const report = await invoke('scan', 'parse', { simulated_code: 'APEX-7788' })
    expect(report.data?.format).toBe('CODE_128')
  })

  it('reports a format mismatch', async () => {
    const report = await invoke('scan', 'scan', {
      simulated_code: 'APEX-QR-ONLY',
      target_format: 'EAN_13',
    })
    expect(report.status).toBe('failed')
    expect(report.data?.status).toBe('FORMAT_MISMATCH')
    expect(report.message).toContain('does not match')
  })

  it('refuses with NO_INPUT when there is nothing to decode', async () => {
    const report = await invoke('scan', 'scan', { camera: 'false' })
    expect(report.status).toBe('failed')
    expect(report.data?.status).toBe('NO_INPUT')
  })
})

describe('files tool', () => {
  it('round-trips, appends, copies and moves', async () => {
    const slug = 'files-round-trip'
    const written = await invoke('files', 'write', { path: 'notes/hello.txt', content: 'apex conformance' }, slug)
    expect(written.status).toBe('ok')
    expect(written.data?.content).toBe('apex conformance')

    const read = await invoke('files', 'read', { path: 'notes/hello.txt' }, slug)
    expect(read.data?.content).toBe('apex conformance')

    await invoke('files', 'write', { path: 'notes/append.txt', content: 'one' }, slug)
    const appended = await invoke('files', 'append', { path: 'notes/append.txt', content: '-two' }, slug)
    expect(appended.data?.content).toBe('one-two')

    await invoke('files', 'copy', { path: 'notes/hello.txt', destination: 'notes/copy.txt' }, slug)
    const moved = await invoke('files', 'move', { path: 'notes/copy.txt', destination: 'notes/moved.txt' }, slug)
    expect(moved.data?.path).toBe('notes/moved.txt')
    const exists = await invoke('files', 'exists', { path: 'notes/moved.txt' }, slug)
    expect(exists.data?.exists).toBe(true)
  })

  it('lists a directory and resolves a file uri', async () => {
    const slug = 'files-list'
    await invoke('files', 'write', { path: 'notes/one.txt', content: 'x' }, slug)
    const list = await invoke('files', 'list', { path: 'notes' }, slug)
    expect(list.data?.count).toBe(1)
    const uri = await invoke('files', 'uri', { path: 'notes/one.txt' }, slug)
    expect(String(uri.data?.uri)).toMatch(/^file:\/\//)
  })

  it('fails a read of a missing file', async () => {
    const report = await invoke('files', 'read', { path: 'notes/missing.txt' }, 'files-missing')
    expect(report.status).toBe('failed')
    expect(report.message).toContain('No such file')
  })
})

describe('stateful logical tools', () => {
  it('caches and clears offline content', async () => {
    const slug = 'cache-ws'
    const cached = await invoke('offline-cache', 'cache', { key: 'catalog', content: '{"items":3}' }, slug)
    expect(cached.data?.status).toBe('CACHED_OFFLINE_READY')
    expect(cached.data?.cached_pages).toBe(1)
    const cleared = await invoke('offline-cache', 'clear', {}, slug)
    expect(cleared.data?.cached_pages).toBe(0)
    const stats = await invoke('offline-cache', 'get_stats', {}, slug)
    expect(stats.data?.cached_pages).toBe(0)
  })

  it('copies, pastes and clears the clipboard', async () => {
    const slug = 'clip-ws'
    await invoke('clipboard', 'copy', { text: 'apex clipboard' }, slug)
    const pasted = await invoke('clipboard', 'paste', {}, slug)
    expect(pasted.data?.text).toBe('apex clipboard')
    const cleared = await invoke('clipboard', 'clear', {}, slug)
    expect(cleared.data?.status).toBe('EMPTY')
    expect(cleared.data?.text).toBe('')
  })

  it('logs a push to the ledger', async () => {
    const report = await invoke('push', 'send_alert', { title: 'Order ready', message: 'Order #1042' })
    expect(report.data?.status).toBe('LOGGED_TO_LEDGER')
    expect(String(report.data?.notification_id)).toMatch(/^push_/)
  })
})

describe('embedding tool', () => {
  it('embeds text into 512 dimensions', async () => {
    const report = await invoke('embedding', 'text', { text: 'apex pos kiosk' })
    expect(report.data?.status).toBe('SUCCESS')
    expect(report.data?.dim).toBe(512)
    expect(String(report.data?.vector_b64).length).toBeGreaterThan(0)
  })

  it('scores identical text at 1.0', async () => {
    const report = await invoke('embedding', 'text_similarity', { text_a: 'coffee order', text_b: 'coffee order' })
    expect(Number(report.data?.score)).toBeCloseTo(1, 5)
  })

  it('requires packed vectors for similarity', async () => {
    const report = await invoke('embedding', 'similarity', { text_a: 'a', text_b: 'b' })
    expect(report.status).toBe('failed')
    expect(report.message).toContain('packed vectors')
  })
})

describe('net tool', () => {
  it('reports idle, stages a share, then serves it', async () => {
    const slug = 'net-hub'
    const idle = await invoke('net', 'status', {}, slug)
    expect(idle.data?.status).toBe('IDLE')

    const staged = await invoke('net', 'share', { content: '{"order":7}', name: 'staged.json' }, slug)
    expect(staged.data?.status).toBe('STAGED')
    expect(staged.data?.hub_running).toBe(false)

    const serving = await invoke('net', 'serve', {}, slug)
    expect(serving.data?.status).toBe('SERVING')
    expect(Number(serving.data?.port)).toBeGreaterThan(0)

    const shared = await invoke('net', 'share', { content: '{"order":1042}', name: 'order.json' }, slug)
    expect(shared.data?.status).toBe('SHARED')

    const list = await invoke('net', 'list', {}, slug)
    expect(Number(list.data?.count)).toBeGreaterThanOrEqual(2)

    const unshared = await invoke('net', 'unshare', { id: shared.data?.id }, slug)
    expect(unshared.data?.status).toBe('REMOVED')

    const stopped = await invoke('net', 'stop', {}, slug)
    expect(stopped.data?.status).toBe('STOPPED')
    const again = await invoke('net', 'stop', {}, slug)
    expect(again.data?.status).toBe('NOT_RUNNING')
  })

  it('fails a share without content', async () => {
    const report = await invoke('net', 'share', { name: 'orphan.json' })
    expect(report.status).toBe('failed')
    expect(report.message).toContain('missing content')
  })

  it('reports a refused connection', async () => {
    const report = await invoke('net', 'get', { url: 'http://127.0.0.1:59999/none', timeout_ms: '1500' })
    expect(report.status).toBe('failed')
    expect(report.message).toContain('Connection refused')
  })
})

describe('print / screen / hardware tools', () => {
  it('refuses Bluetooth discovery and a print with no printer', async () => {
    const scan = await invoke('print', 'scan', {})
    expect(scan.data?.status).toBe('DENIED')
    const status = await invoke('print', 'status', {})
    expect(status.data?.status).toBe('READY')
    const missing = await invoke('print', 'print', {})
    expect(missing.message).toContain('missing receipt')
    const noPrinter = await invoke('print', 'print', { receipt: 'APEX ORDER', address: '00:11:22:33:44:55' })
    expect(noPrinter.data?.status).toBe('UNSUPPORTED')
    expect(noPrinter.message).toContain('printer')
  })

  it('tracks keep-awake and refuses screen capture', async () => {
    const slug = 'screen-ws'
    const on = await invoke('screen', 'keep-awake', { state: 'on' }, slug)
    expect(on.data?.status).toBe('ENABLED')
    expect(on.data?.enabled).toBe(true)
    const off = await invoke('screen', 'keep-awake', { state: 'off' }, slug)
    expect(off.data?.status).toBe('DISABLED')
    const shot = await invoke('screen', 'screenshot', {}, slug)
    expect(shot.data?.status).toBe('UNSUPPORTED')
  })

  it('refuses haptics with an honest UNSUPPORTED', async () => {
    const report = await invoke('haptics', 'click', {})
    expect(report.status).toBe('failed')
    expect(report.data?.status).toBe('UNSUPPORTED')
  })

  it('refuses a websocket connect and keeps disconnect safe', async () => {
    const connect = await invoke('realtime-ws', 'connect', { channel: 'kitchen', url: 'ws://127.0.0.1:1' })
    expect(connect.data?.status).toBe('UNSUPPORTED')
    expect(connect.message).toContain('no websocket transport')
    const send = await invoke('realtime-ws', 'send', { channel: 'kitchen', message: 'ping' })
    expect(send.message).toBe('not connected')
    const disconnect = await invoke('realtime-ws', 'disconnect', {})
    expect(disconnect.data?.status).toBe('DISCONNECTED')
  })

  it('refuses code generation without an encoder', async () => {
    const missing = await invoke('code-generate', 'generate', { format: 'qr' })
    expect(missing.message).toContain('content')
    const report = await invoke('code-generate', 'generate', { content: 'apexpos://order/1', format: 'qr' })
    expect(report.data?.status).toBe('UNSUPPORTED')
  })

  it('refuses ML without an image and with one', async () => {
    const noImage = await invoke('MobileNetV4-small', 'classify', {})
    expect(noImage.data?.status).toBe('NO_INPUT')
    const withImage = await invoke('u-2-Net.tflite', 'segment', { image: 'file:///tmp/a.png' })
    expect(withImage.data?.status).toBe('UNSUPPORTED')
  })
})

describe('custom tool extension', () => {
  it('runs the example echo tool through the registry', async () => {
    const ping = await invoke('custom.echo', 'ping', {}, 'echo-ws')
    expect(ping.data?.pong).toBe(true)
    expect(ping.data?.workspace).toBe('echo-ws')
    const echo = await invoke('echo', 'echo', { message: 'hello' })
    expect(echo.data?.echo).toBe('hello')
  })
})
