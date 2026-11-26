import {
  button, card, chart, container, divKitCard, expression, grid, hero, inlineBanner, intVar,
  jsonList, localDb, matchParent, metric, navPop, navPush, palette, pill, quickTile, radius,
  resultLine, sectionTitle, space, stringVar, text, tool, type as ramp,
} from '../kit/index.ts'
import type { DivCard } from '../kit/card.ts'
import type { DivNode } from '../kit/div.ts'
import type { WorkspaceDefinition } from '../types.ts'

const ACCENT = '#41BDF0'

const variables = [
  stringVar('user.workspace_title', 'APEX TOOLS'),
  stringVar('user.tools_status', 'Idle'),
  stringVar('user.tools_result', ''),
  stringVar('user.tools_error', ''),
  stringVar('user.haptic_state', 'idle'),
  stringVar('user.beep_state', 'idle'),
  stringVar('user.clipboard_content', ''),
  stringVar('user.clipboard_length', ''),
  stringVar('user.code_image_uri', ''),
  stringVar('user.code_format', ''),
  stringVar('user.code_kind', ''),
  stringVar('user.file_preview', ''),
  stringVar('user.files_json', '[]'),
  stringVar('user.net_status', 'stopped'),
  stringVar('user.net_url', ''),
  stringVar('user.net_shares_json', '[]'),
  stringVar('user.print_state', 'idle'),
  stringVar('user.screen_state', 'idle'),
  stringVar('user.codec_status', 'idle'),
  stringVar('user.codec_text', ''),
  stringVar('user.codec_json', ''),
  stringVar('user.codec_valid', ''),
  stringVar('user.push_state', 'idle'),
  stringVar('user.realtime_state', 'IDLE'),
  stringVar('user.realtime_channel', 'kitchen_stream'),
  stringVar('user.biometric_state', 'idle'),
  stringVar('user.camera_state', 'idle'),
  stringVar('user.scan_state', 'idle'),
  stringVar('user.samples_json', '[]'),
  intVar('user.samples_count', 0),
]

const GUTTER = { left: space(5), right: space(5) }

const backHeader = () =>
  button({ text: 'Back', actions: [navPop()], tone: 'ghost', width: matchParent(), height: space(10), size: ramp.small, id: 'btn_back' })

const scopeBanner = (live: boolean) =>
  inlineBanner({
    text: live
      ? 'Runs live in this dev server — the same dispatch the device makes, executed by the web tool host.'
      : 'Needs the device client — this capability drives real hardware the browser does not have. The preview reports it instead of faking success.',
    tone: live ? 'info' : 'warn',
  })

/** One harness card: hero, scope banner, status readout, triggers, extras. */
const probeCard = (
  name: string,
  title: string,
  subtitle: string,
  live: boolean,
  statusLabel: string,
  statusExpr: string,
  statusTone: 'accent' | 'warn' | 'danger' | 'info' | 'neutral',
  triggers: DivNode[],
  extra: DivNode[] = [],
): DivCard =>
  divKitCard({
    logId: `apex_tools_test_${name}`,
    variables,
    states: [{
      div: container({
        spacing: space(3),
        padding: { bottom: space(6) },
        items: [
          hero({ title, subtitle, accent: ACCENT, back: true, id: `${name}_hero` }),
          container({
            ...GUTTER,
            spacing: space(3),
            items: [
              scopeBanner(live),
              resultLine({ label: statusLabel, value: statusExpr, tone: statusTone, id: `${name}_status` }),
              ...extra,
              sectionTitle('TRIGGERS'),
              card(triggers, { elevation: 'raised', spacing: space(2) }),
            ],
          }),
        ],
      }),
    }],
  })

const row = (buttons: DivNode[]) =>
  container({ orientation: 'horizontal', spacing: space(2), items: buttons })

const trigger = (id: string, label: string, action: DivNode) =>
  button({ text: label, actions: [action], tone: 'secondary', height: space(11), width: matchParent(), size: ramp.small, id })

const dbQuery = () => localDb('db.query', { collection: 'samples', order_by: 'id', limit: 50, output: { _json: 'user.samples_json' } })

const hub = divKitCard({
  logId: 'apex_tools_test_hub',
  variables,
  states: [{
    div: container({
      spacing: space(3),
      padding: { bottom: space(6) },
      items: [
        hero({
          title: 'APEX TOOLS',
          subtitle: 'Every capability, one harness workspace',
          accent: ACCENT,
          badge: 'TOOLS-001',
          id: 'hub_hero',
        }),
        container({
          ...GUTTER,
          spacing: space(3),
          items: [
            grid(
              [
                metric({ label: 'SAMPLES', value: expression('user.samples_count'), tone: palette.accent, id: 'metric_samples' }),
                metric({ label: 'STATUS', value: expression('user.tools_status'), id: 'metric_status' }),
              ],
              2,
              'metric_strip',
            ),
            sectionTitle('SOFTWARE · LIVE IN THE DEV SERVER'),
            grid(
              [
                quickTile({ label: 'Local DB', subtitle: 'SQLite insert/query/count/delete', icon: 'database', tone: 'primary', actions: [navPush('local_db')], id: 'nav_local_db' }),
                quickTile({ label: 'Clipboard', subtitle: 'Copy, paste, clear', icon: 'clipboard', tone: 'accent', actions: [navPush('clipboard')], id: 'nav_clipboard' }),
                quickTile({ label: 'Codec', subtitle: 'Base64, Base45, JSON chunking', icon: 'text', tone: 'primary', actions: [navPush('codec')], id: 'nav_codec' }),
                quickTile({ label: 'Code generator', subtitle: 'QR, EAN-13, Code 128', icon: 'qr_code', tone: 'accent', actions: [navPush('code_generate')], id: 'nav_code_generate' }),
                quickTile({ label: 'Files', subtitle: 'Workspace sandbox store', icon: 'folder', tone: 'primary', actions: [navPush('files')], id: 'nav_files' }),
                quickTile({ label: 'Network', subtitle: 'HTTP fetch and LAN hub', icon: 'globe', tone: 'accent', actions: [navPush('net')], id: 'nav_net' }),
                quickTile({ label: 'Push alerts', subtitle: 'In-app notification ledger', icon: 'push', tone: 'primary', actions: [navPush('push')], id: 'nav_push' }),
                quickTile({ label: 'Realtime', subtitle: 'WebSocket / SSE channel', icon: 'sync', tone: 'accent', actions: [navPush('realtime')], id: 'nav_realtime' }),
                quickTile({ label: 'Data views', subtitle: 'Chart and list blocks', icon: 'bar_chart', tone: 'primary', actions: [navPush('data_views')], id: 'nav_data_views' }),
              ],
              2,
              'software_tiles',
            ),
            sectionTitle('DEVICE · RUNS ON THE CLIENT'),
            grid(
              [
                quickTile({ label: 'Scanner', subtitle: 'Camera scan and OCR', icon: 'scan', tone: 'warn', actions: [navPush('scanner')], id: 'nav_scanner' }),
                quickTile({ label: 'Haptics and beeper', subtitle: 'Patterns and tones', icon: 'vibration', tone: 'warn', actions: [navPush('haptics')], id: 'nav_haptics' }),
                quickTile({ label: 'Printer', subtitle: 'Bluetooth ESC/POS', icon: 'print', tone: 'warn', actions: [navPush('print')], id: 'nav_print' }),
                quickTile({ label: 'Client hardware', subtitle: 'Camera and biometric gate', icon: 'security', tone: 'warn', actions: [navPush('client_hardware')], id: 'nav_client_hardware' }),
              ],
              2,
              'device_tiles',
            ),
          ],
        }),
      ],
    }),
  }],
})

const localDbCard = probeCard(
  'local_db',
  'Local DB',
  'SQLite data plane: insert, query, count, delete',
  true,
  'Last result',
  expression('user.tools_result'),
  'accent',
  [
    trigger('db_insert', 'Insert sample', localDb('db.insert', {
      collection: 'samples',
      data: { name: 'Probe item', note: 'inserted from card' },
      output: { id: 'user.tools_result' },
    })),
    trigger('db_query', 'Query samples', dbQuery()),
    trigger('db_count', 'Count samples', localDb('db.count', { collection: 'samples', output: { count: 'user.samples_count' } })),
    trigger('db_delete', 'Delete inserted', localDb('db.delete', {
      collection: 'samples',
      where: { note: 'inserted from card' },
      output: { deleted: 'user.tools_result' },
    })),
  ],
  [
    card([
      sectionTitle('SAMPLES'),
      jsonList({
        dataVar: 'user.samples_json',
        emptyText: 'Query the samples collection first',
        id: 'local_db_list',
        rowCard: container({
          orientation: 'horizontal',
          padding: space(2),
          background: palette.surfaceAlt,
          radius: radius.md,
          items: [
            text({ text: '@{item.name}', size: ramp.small, width: matchParent() }),
            text({ text: '#@{item.id}', size: ramp.label, color: palette.accent, align: 'right' }),
          ],
        }),
      }),
    ]),
  ],
)

const scannerCard = probeCard(
  'scanner',
  'Scanner',
  'Camera scan, OCR and format checks',
  false,
  'Scan status',
  expression('user.scan_state'),
  'warn',
  [
    trigger('scan_any', 'Scan barcode (device camera)', tool('scanner', 'scan', { output: { status: 'user.scan_state', value: 'user.tools_result' } })),
    trigger('scan_sim', 'Simulate a scan', tool('scanner', 'simulate', { options: { content: 'APEX-POS-000123', format: 'CODE_128' }, output: { status: 'user.scan_state', raw_value: 'user.tools_result', format: 'user.scanned_format' } })),
    trigger('scan_ocr', 'Read text (OCR, device)', tool('scanner', 'ocr', { output: { status: 'user.scan_state', text: 'user.tools_result' } })),
    trigger('scan_ean', 'Scan EAN-13 only (device)', tool('scanner', 'scan', {
      options: { format: 'EAN_13' },
      output: { status: 'user.scan_state', value: 'user.tools_result' },
    })),
  ],
)

const codeGenerateCard = probeCard(
  'code_generate',
  'Code generator',
  'QR, EAN-13 and Code 128 rendered from the module matrix',
  true,
  'Generated symbol',
  expression("user.code_format == '' ? 'none yet' : user.code_format"),
  'accent',
  [
    trigger('gen_qr', 'Generate QR', tool('code-generate', 'generate', {
      options: { content: 'apexpos://tools/probe?terminal=TEST-1', format: 'qr', width: '512', ec_level: 'M' },
      output: { uri: 'user.code_image_uri', format: 'user.code_format', kind: 'user.code_kind' },
    })),
    trigger('gen_ean', 'Generate EAN-13', tool('code-generate', 'barcode', {
      options: { content: '9780201633610', format: 'EAN_13', width: '400', height: '160' },
      output: { uri: 'user.code_image_uri', format: 'user.code_format', kind: 'user.code_kind' },
    })),
    trigger('gen_code128', 'Generate Code 128', tool('code-generate', 'barcode', {
      options: { content: 'APEX-TOOLS-0001', format: 'CODE_128', width: '600', height: '180' },
      output: { uri: 'user.code_image_uri', format: 'user.code_format', kind: 'user.code_kind' },
    })),
  ],
  [
    card([
      sectionTitle('RENDERED SYMBOL'),
      { type: 'image', image_url: expression('user.code_image_uri'), height: { type: 'fixed', value: 180 }, scale: 'fit', id: 'code_image' },
    ]),
  ],
)

const hapticsCard = probeCard(
  'haptics',
  'Haptics and beeper',
  'Vibration patterns and tone sequences',
  false,
  'Haptic / beeper state',
  expression("user.haptic_state + ' / ' + user.beep_state"),
  'warn',
  [
    row([
      trigger('haptic_click', 'Click', tool('haptics', 'click', { output: { status: 'user.haptic_state' } })),
      trigger('haptic_success', 'Success', tool('haptics', 'success', { output: { status: 'user.haptic_state' } })),
    ]),
    row([
      trigger('haptic_warning', 'Warning', tool('haptics', 'warning', { output: { status: 'user.haptic_state' } })),
      trigger('haptic_stop', 'Stop', tool('haptics', 'stop', { output: { status: 'user.haptic_state' } })),
    ]),
    row([
      trigger('beep_tone', 'Tone', tool('beeper', 'tone', { options: { frequency: '880', duration_ms: '150' }, output: { status: 'user.beep_state' } })),
      trigger('beep_stop', 'Silence', tool('beeper', 'stop', { output: { status: 'user.beep_state' } })),
    ]),
  ],
)

const clipboardCard = probeCard(
  'clipboard',
  'Clipboard',
  'Copy, paste and clear the system clipboard',
  true,
  'Clipboard content',
  expression("user.clipboard_content == '' ? 'empty' : user.clipboard_content"),
  'accent',
  [
    trigger('clip_copy', 'Copy receipt text', tool('clipboard', 'copy', {
      options: { text: 'APEX TOOLS - receipt #0001' },
      output: { text: 'user.clipboard_content', length: 'user.clipboard_length' },
    })),
    trigger('clip_paste', 'Paste', tool('clipboard', 'paste', { output: { text: 'user.clipboard_content', length: 'user.clipboard_length' } })),
    trigger('clip_clear', 'Clear', tool('clipboard', 'clear', { output: { status: 'user.tools_status', text: 'user.clipboard_content' } })),
  ],
)

const codecCard = probeCard(
  'codec',
  'Codec',
  'Base64, Base45 and JSON chunk envelopes',
  true,
  'Codec status',
  expression('user.codec_status'),
  'accent',
  [
    trigger('codec_b64enc', 'Base64 encode', tool('codec', 'base64', {
      options: { text: 'APEX TOOLS probe payload', op: 'encode' },
      output: { status: 'user.codec_status', text: 'user.codec_text' },
    })),
    trigger('codec_b45', 'Base45 round-trip', tool('codec', 'base45', {
      options: { text: 'KIOSK-ORDER-0042', op: 'encode' },
      output: { status: 'user.codec_status', text: 'user.codec_text', checksum: 'user.codec_checksum' },
    })),
    trigger('codec_json', 'Validate JSON', tool('codec', 'json/validate', {
      options: { text: '{"order":"A100","total":350}' },
      output: { status: 'user.codec_status', valid: 'user.codec_valid', json: 'user.codec_json' },
    })),
    trigger('codec_chunk', 'Chunk JSON for QR', tool('codec', 'json/chunk', {
      options: { json: '{"order":"A100","lines":[{"sku":"P1","qty":2},{"sku":"P2","qty":1}]}', max_bytes: '120' },
      output: { status: 'user.codec_status', chunks: 'user.codec_chunks', count: 'user.codec_count' },
    })),
  ],
  [
    card([
      sectionTitle('CODEC OUTPUT'),
      resultLine({ label: 'TEXT', value: expression("user.codec_text == '' ? '—' : user.codec_text"), tone: 'accent', id: 'codec_text_line' }),
      resultLine({ label: 'VALID', value: expression("user.codec_valid == '' ? '—' : user.codec_valid"), tone: 'info', id: 'codec_valid_line' }),
    ]),
  ],
)

const netCard = probeCard(
  'net',
  'Network',
  'HTTP fetch plus the LAN share hub',
  true,
  'Hub state',
  expression("user.net_status + (user.net_url == '' ? '' : ' - ' + user.net_url)"),
  'accent',
  [
    trigger('net_get', 'GET example.com', tool('net', 'get', {
      options: { url: 'https://example.com' },
      output: { status: 'user.tools_status', body: 'user.tools_result' },
    })),
    trigger('net_serve', 'Start hub', tool('net', 'serve', {
      options: { port: '8089' },
      output: { status: 'user.net_status', url: 'user.net_url', id: 'user.net_share_id' },
    })),
    trigger('net_status', 'Hub status', tool('net', 'status', { output: { status: 'user.net_status', url: 'user.net_url' } })),
    trigger('net_share', 'Share sample', tool('net', 'share', {
      options: { title: 'Probe item', payload: '{"sku":"P1001","price":1.5}' },
      output: { id: 'user.net_share_id', count: 'user.net_share_count' },
    })),
    trigger('net_list', 'List shares', tool('net', 'list', { output: { _json: 'user.net_shares_json' } })),
    trigger('net_stop', 'Stop hub', tool('net', 'stop', { output: { status: 'user.net_status' } })),
  ],
  [
    jsonList({
      dataVar: 'user.net_shares_json',
      emptyText: 'No shares on the hub',
      id: 'net_shares_list',
      rowCard: container({
        orientation: 'horizontal',
        padding: space(2),
        background: palette.surfaceAlt,
        radius: radius.md,
        items: [
          text({ text: '@{item.title}', size: ramp.small, width: matchParent() }),
          text({ text: '@{item.id}', size: ramp.label, color: palette.textMuted, align: 'right' }),
        ],
      }),
    }),
  ],
)

const pushCard = probeCard(
  'push',
  'Push alerts',
  'Background alerts through the notification ledger',
  true,
  'Last dispatch',
  expression("user.push_state == 'idle' ? 'nothing sent yet' : user.push_state"),
  'accent',
  [
    trigger('push_order', 'Order ready alert', tool('push', 'send_alert', {
      options: { title: 'Kitchen alert', message: 'Order #1042 ready for pickup', channel: 'orders_dispatch', priority: 'high' },
      output: { status: 'user.push_state', notification_id: 'user.push_notification_id' },
    })),
    trigger('push_low', 'Low priority note', tool('push', 'notify', {
      options: { title: 'Shift note', message: 'Float counted and locked', priority: 'default' },
      output: { status: 'user.push_state', notification_id: 'user.push_notification_id' },
    })),
  ],
)

const realtimeCard = probeCard(
  'realtime',
  'Realtime channel',
  'WebSocket / SSE engine state machine',
  true,
  'Connection',
  expression("user.realtime_state + ' · ' + user.realtime_channel"),
  'accent',
  [
    trigger('rt_connect', 'Connect', tool('realtime-ws', 'connect', { options: { channel: 'kitchen_stream' }, output: { status: 'user.realtime_state', channel: 'user.realtime_channel' } })),
    trigger('rt_send', 'Send ping', tool('realtime-ws', 'send', { options: { channel: 'kitchen_stream', message: 'ping' }, output: { status: 'user.realtime_state', last_message: 'user.tools_result' } })),
    trigger('rt_subscribe', 'Subscribe', tool('realtime-ws', 'subscribe', { options: { channel: 'kitchen_stream' }, output: { status: 'user.realtime_state' } })),
    trigger('rt_disconnect', 'Disconnect', tool('realtime-ws', 'disconnect', { output: { status: 'user.realtime_state' } })),
  ],
)

const printCard = probeCard(
  'print',
  'Printer',
  'Bluetooth ESC/POS pairing and test print',
  false,
  'Printer state',
  expression('user.print_state'),
  'warn',
  [
    trigger('print_scan', 'Scan printers', tool('print', 'scan', { output: { status: 'user.print_state', count: 'user.print_device_count', _json: 'user.print_devices_json' } })),
    trigger('print_connect', 'Connect', tool('print', 'connect', {
      options: { address: '00:11:22:33:44:55' },
      output: { status: 'user.print_state' },
    })),
    trigger('print_test', 'Test print', tool('print', 'print', {
      options: { address: '00:11:22:33:44:55', text: 'APEX TOOLS TEST PRINT' },
      output: { status: 'user.print_state', bytes: 'user.print_bytes' },
    })),
    trigger('print_disconnect', 'Disconnect', tool('print', 'disconnect', { output: { status: 'user.print_state' } })),
  ],
)

const filesCard = probeCard(
  'files',
  'Files and screen',
  'Workspace sandbox files and screen capture',
  true,
  'File preview',
  expression("user.file_preview == '' ? 'no file yet' : user.file_preview"),
  'accent',
  [
    trigger('files_write', 'Write file', tool('files', 'write', {
      options: { path: 'notes/probe.txt', content: 'APEX TOOLS PROBE' },
      output: { path: 'user.file_path', bytes: 'user.file_bytes' },
    })),
    trigger('files_read', 'Read file', tool('files', 'read', {
      options: { path: 'notes/probe.txt' },
      output: { text: 'user.file_preview', path: 'user.file_path' },
    })),
    trigger('files_list', 'List files', tool('files', 'list', { options: { path: 'notes' }, output: { _json: 'user.files_json' } })),
    trigger('files_delete', 'Delete file', tool('files', 'delete', { options: { path: 'notes/probe.txt' }, output: { status: 'user.tools_status' } })),
    trigger('screen_awake', 'Keep awake', tool('screen', 'keep-awake', { options: { action: 'enable' }, output: { status: 'user.screen_state', enabled: 'user.screen_keep_awake' } })),
    trigger('screen_shot', 'Screenshot (device)', tool('screen', 'screenshot', { output: { uri: 'user.screen_uri', status: 'user.screen_state' } })),
    trigger('screen_record', 'Start recording (device)', tool('screen', 'recording', { options: { action: 'start' }, output: { status: 'user.screen_state' } })),
    trigger('screen_stop', 'Stop recording (device)', tool('screen', 'recording', { options: { action: 'stop' }, output: { status: 'user.screen_state', uri: 'user.screen_video_uri' } })),
  ],
  [
    jsonList({
      dataVar: 'user.files_json',
      emptyText: 'No files in the sandbox',
      id: 'files_list',
      rowCard: container({
        orientation: 'horizontal',
        padding: space(2),
        background: palette.surfaceAlt,
        radius: radius.md,
        items: [
          text({ text: '@{item.name}', size: ramp.small, width: matchParent() }),
          text({ text: '@{item.size_bytes} B', size: ramp.label, color: palette.textMuted, align: 'right' }),
        ],
      }),
    }),
  ],
)

const clientHardwareCard = probeCard(
  'client_hardware',
  'Client hardware',
  'Camera capture and the supervisor biometric gate',
  false,
  'Hardware state',
  expression("'biometric: ' + user.biometric_state + ' · camera: ' + user.camera_state"),
  'warn',
  [
    trigger('bio_auth', 'Supervisor auth (device)', tool('biometrics', 'authenticate', {
      options: { prompt: 'Supervisor Authorization Required' },
      output: { status: 'user.biometric_state', authenticated: 'user.biometric_authenticated' },
    })),
    trigger('cam_capture', 'Capture photo (device)', tool('camera', 'capture', {
      options: { facing: 'back', resolution: '1080p' },
      output: { status: 'user.camera_state', uri: 'user.camera_capture_uri' },
    })),
    trigger('cam_torch', 'Toggle torch (device)', tool('camera', 'torch_toggle', { output: { status: 'user.camera_state' } })),
    trigger('cam_status', 'Camera status (device)', tool('camera', 'get_status', { output: { status: 'user.camera_state' } })),
  ],
)

const dataViewsCard = divKitCard({
  logId: 'apex_tools_test_data_views',
  variables,
  states: [{
    div: container({
      spacing: space(3),
      padding: { bottom: space(6) },
      items: [
        hero({
          title: 'Data views',
          subtitle: 'Chart and list blocks over the samples collection',
          accent: ACCENT,
          back: true,
          id: 'data_views_hero',
        }),
        container({
          ...GUTTER,
          spacing: space(3),
          items: [
            card([
              sectionTitle('SAMPLES BY ID'),
              chart({
                id: 'samples_chart',
                chartType: 'donut',
                title: 'Live from user.samples_json',
                subtitle: 'Donut',
                dataVar: 'user.samples_json',
                labelField: 'name',
                valueField: 'id',
                accentColor: ACCENT,
                height: 220,
              }),
            ]),
            card([
              sectionTitle('SAMPLES'),
              jsonList({
                dataVar: 'user.samples_json',
                emptyText: 'Query the samples collection first',
                id: 'samples_list',
                rowCard: container({
                  orientation: 'horizontal',
                  padding: space(2),
                  background: palette.surfaceAlt,
                  radius: radius.md,
                  items: [
                    text({ text: '@{item.name}', size: ramp.small, width: matchParent() }),
                    text({ text: '#@{item.id}', size: ramp.label, color: palette.accent, align: 'right' }),
                  ],
                }),
              }),
              button({ text: 'Refresh samples', tone: 'secondary', actions: [dbQuery()], id: 'refresh_samples' }),
            ]),
          ],
        }),
      ],
    }),
  }],
})

export const apexToolsTest: WorkspaceDefinition = {
  slug: 'apex-tools-test',
  manifestId: 'apex_tools_test',
  meta: {
    title: 'APEX TOOLS',
    subtitle: 'Capability harness for every native tool',
    terminal: 'TOOLS-001',
    accent: ACCENT,
    mode: 'NATIVE',
    capabilities: ['scanner', 'printer', 'haptics', 'beeper', 'clipboard'],
    tools: {
      hapticVibration: true,
      hardwareBeeperAudio: true,
      cameraBarcodeScanner: true,
      clipboardAccess: true,
      backgroundAlertsPush: true,
      offlinePageCaching: true,
      realtimeEngineWsSse: true,
    },
    urls: { tunnel: 'https://apex-tools.local/', lan: 'http://192.168.1.30:8089', client_id: 'pk_test_apex_tools' },
    unread: 0,
  },
  hub,
  cards: [
    { name: 'local_db', card: localDbCard },
    { name: 'clipboard', card: clipboardCard },
    { name: 'codec', card: codecCard },
    { name: 'code_generate', card: codeGenerateCard },
    { name: 'files', card: filesCard },
    { name: 'net', card: netCard },
    { name: 'push', card: pushCard },
    { name: 'realtime', card: realtimeCard },
    { name: 'data_views', card: dataViewsCard },
    { name: 'scanner', card: scannerCard },
    { name: 'haptics', card: hapticsCard },
    { name: 'print', card: printCard },
    { name: 'client_hardware', card: clientHardwareCard },
  ],
  catalog: {
    title: 'Harness',
    subtitle: 'Every capability, one card each.',
    entries: [
      { file: 'local_db', label: 'Local DB', icon: 'database', accent_color: ACCENT, subtitle: 'SQLite insert/query/count/delete' },
      { file: 'clipboard', label: 'Clipboard', icon: 'clipboard', accent_color: ACCENT, subtitle: 'Copy, paste, clear' },
      { file: 'codec', label: 'Codec', icon: 'text', accent_color: ACCENT, subtitle: 'Base64, Base45, JSON chunking' },
      { file: 'code_generate', label: 'Code generator', icon: 'qr_code', accent_color: ACCENT, subtitle: 'QR, EAN-13, Code 128' },
      { file: 'files', label: 'Files', icon: 'folder', accent_color: ACCENT, subtitle: 'Sandbox store and screen' },
      { file: 'net', label: 'Network', icon: 'globe', accent_color: ACCENT, subtitle: 'HTTP and LAN hub' },
      { file: 'push', label: 'Push alerts', icon: 'push', accent_color: ACCENT, subtitle: 'Notification ledger' },
      { file: 'realtime', label: 'Realtime', icon: 'sync', accent_color: ACCENT, subtitle: 'WebSocket / SSE channel' },
      { file: 'data_views', label: 'Data views', icon: 'bar_chart', accent_color: ACCENT, subtitle: 'Chart and list blocks' },
      { file: 'scanner', label: 'Scanner', icon: 'scan', accent_color: ACCENT, subtitle: 'Camera scan and OCR' },
      { file: 'haptics', label: 'Haptics and beeper', icon: 'vibration', accent_color: ACCENT, subtitle: 'Patterns and tones' },
      { file: 'print', label: 'Printer', icon: 'print', accent_color: ACCENT, subtitle: 'ESC/POS over Bluetooth' },
      { file: 'client_hardware', label: 'Client hardware', icon: 'security', accent_color: ACCENT, subtitle: 'Camera and biometric gate' },
    ],
  },
  collections: [
    {
      file: 'samples.json',
      json: {
        id: 1,
        name: 'samples',
        schema: {
          fields: {
            name: { type: 'string', required: false, uid: 'samples_name' },
            note: { type: 'string', required: false, uid: 'samples_note' },
          },
        },
        seed: [
          { name: 'Probe item', note: 'seed' },
          { name: 'Second probe', note: 'seed' },
        ],
      },
    },
  ],
  targets: { android: true },
}
