import {
  button, card, chart, container, divKitCard, expression, inlineBanner, intVar, jsonList,
  localDb, matchParent, metric, navPop, navPush, palette, radius, space, stringVar, text,
  tool, type as ramp,
} from '../kit/index.ts'
import type { DivCard } from '../kit/card.ts'
import type { DivNode } from '../kit/div.ts'
import type { WorkspaceDefinition } from '../types.ts'

const ACCENT = '#0EA5E9'

const variables = [
  stringVar('user.workspace_title', 'APEX TOOLS'),
  stringVar('user.tools_status', 'Idle'),
  stringVar('user.tools_result', ''),
  stringVar('user.tools_error', ''),
  stringVar('user.haptic_state', 'idle'),
  stringVar('user.beep_state', 'idle'),
  stringVar('user.clipboard_content', ''),
  stringVar('user.code_image_uri', ''),
  stringVar('user.code_format', ''),
  stringVar('user.file_preview', ''),
  stringVar('user.files_json', '[]'),
  stringVar('user.net_status', 'stopped'),
  stringVar('user.net_url', ''),
  stringVar('user.net_shares_json', '[]'),
  stringVar('user.print_state', 'idle'),
  stringVar('user.screen_state', 'idle'),
  stringVar('user.samples_json', '[]'),
  intVar('user.samples_count', 0),
]

const header = (title: string, subtitle: string) =>
  container({
    orientation: 'horizontal',
    spacing: space(2),
    padding: { left: space(4), top: space(8), right: space(4), bottom: space(2) },
    items: [
      button({ text: 'Back', actions: [navPop()], tone: 'secondary', width: { type: 'fixed', value: 80 }, height: space(10), size: ramp.small, id: 'btn_back' }),
      container({
        weight: 1,
        items: [
          text({ text: title, size: ramp.title, weight: 'bold' }),
          text({ text: subtitle, size: ramp.small, color: palette.textMuted }),
        ],
      }),
    ],
  })

const statusCard = (valueExpr: string, label: string) =>
  card([
    text({ text: label, size: ramp.label, weight: 'bold', color: palette.textMuted }),
    text({ text: valueExpr, size: ramp.body, weight: 'bold', color: palette.accent }),
    inlineBanner({ text: expression("user.tools_error == '' ? 'No errors' : user.tools_error"), tone: 'warn' }),
  ])

/** One harness card: status readout plus a column of tool triggers. */
const probeCard = (
  name: string,
  title: string,
  subtitle: string,
  statusLabel: string,
  statusExpr: string,
  actions: DivNode[],
  extra: DivNode[] = [],
): DivCard =>
  divKitCard({
    logId: `apex_tools_test_${name}`,
    variables,
    states: [{
      div: container({
        spacing: space(2),
        padding: { bottom: space(4) },
        items: [
          header(title, subtitle),
          container({
            padding: { left: space(4), right: space(4) },
            spacing: space(3),
            items: [statusCard(statusExpr, statusLabel), ...extra, card(actions)],
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
      spacing: space(2),
      padding: { bottom: space(4) },
      items: [
        container({
          padding: { left: space(4), top: space(8), right: space(4) },
          items: [
            text({ text: 'APEX TOOLS', size: ramp.display, weight: 'bold' }),
            text({ text: 'Every capability, one harness workspace', size: ramp.small, color: palette.textMuted }),
          ],
        }),
        container({
          orientation: 'horizontal',
          spacing: space(2),
          padding: { left: space(4), right: space(4) },
          items: [
            metric({ label: 'SAMPLES', value: expression('user.samples_count'), tone: palette.accent, width: matchParent(), id: 'metric_samples' }),
            metric({ label: 'STATUS', value: expression('user.tools_status'), width: matchParent(), id: 'metric_status' }),
          ],
        }),
        container({
          padding: { left: space(4), right: space(4), top: space(2) },
          spacing: space(2),
          items: [
            button({ text: 'Local DB', actions: [navPush('local_db')], id: 'nav_local_db' }),
            button({ text: 'Scanner', actions: [navPush('scanner')], tone: 'secondary', id: 'nav_scanner' }),
            button({ text: 'Code generator', actions: [navPush('code_generate')], tone: 'secondary', id: 'nav_code_generate' }),
            button({ text: 'Haptics and beeper', actions: [navPush('haptics')], tone: 'secondary', id: 'nav_haptics' }),
            button({ text: 'Clipboard', actions: [navPush('clipboard')], tone: 'secondary', id: 'nav_clipboard' }),
            button({ text: 'Network', actions: [navPush('net')], tone: 'secondary', id: 'nav_net' }),
            button({ text: 'Printer', actions: [navPush('print')], tone: 'secondary', id: 'nav_print' }),
            button({ text: 'Files and screen', actions: [navPush('files')], tone: 'secondary', id: 'nav_files' }),
            button({ text: 'Data views', actions: [navPush('data_views')], tone: 'secondary', id: 'nav_data_views' }),
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
  'Last result',
  expression('user.tools_result'),
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
)

const scannerCard = probeCard(
  'scanner',
  'Scanner',
  'Camera scan, OCR and format checks',
  'Scan status',
  expression('user.tools_status'),
  [
    trigger('scan_any', 'Scan barcode', tool('scanner', 'scan', { output: { status: 'user.tools_status', value: 'user.tools_result' } })),
    trigger('scan_ocr', 'Read text (OCR)', tool('scanner', 'ocr', { output: { status: 'user.tools_status', text: 'user.tools_result' } })),
    trigger('scan_ean', 'Scan EAN-13 only', tool('scanner', 'scan', {
      options: { format: 'EAN_13' },
      output: { status: 'user.tools_status', value: 'user.tools_result' },
    })),
  ],
)

const codeGenerateCard = probeCard(
  'code_generate',
  'Code generator',
  'QR, EAN-13 and Code 128 rendered on device',
  'Generated symbol',
  expression("user.code_format == '' ? 'none yet' : user.code_format"),
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
      text({ text: 'Rendered symbol', size: ramp.label, weight: 'bold', color: palette.textMuted }),
      { type: 'image', image_url: expression('user.code_image_uri'), height: { type: 'fixed', value: 180 }, scale: 'fit', id: 'code_image' },
    ]),
  ],
)

const hapticsCard = probeCard(
  'haptics',
  'Haptics and beeper',
  'Vibration patterns and tone sequences',
  'Haptic / beeper state',
  expression("user.haptic_state + ' / ' + user.beep_state"),
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
  'Clipboard content',
  expression("user.clipboard_content == '' ? 'empty' : user.clipboard_content"),
  [
    trigger('clip_copy', 'Copy receipt text', tool('clipboard', 'copy', {
      options: { text: 'APEX TOOLS - receipt #0001' },
      output: { text: 'user.clipboard_content', length: 'user.clipboard_length' },
    })),
    trigger('clip_paste', 'Paste', tool('clipboard', 'paste', { output: { text: 'user.clipboard_content' } })),
    trigger('clip_clear', 'Clear', tool('clipboard', 'clear', { output: { status: 'user.tools_status' } })),
  ],
)

const netCard = probeCard(
  'net',
  'Network',
  'HTTP fetch plus the LAN share hub',
  'Hub state',
  expression("user.net_status + (user.net_url == '' ? '' : ' - ' + user.net_url)"),
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
          text({ text: '@{item.id}', size: ramp.label, color: palette.textMuted }),
        ],
      }),
    }),
  ],
)

const printCard = probeCard(
  'print',
  'Printer',
  'Bluetooth ESC/POS pairing and test print',
  'Printer state',
  expression('user.print_state'),
  [
    trigger('print_scan', 'Scan printers', tool('print', 'scan', { output: { status: 'user.print_state', _json: 'user.print_devices_json' } })),
    trigger('print_connect', 'Connect', tool('print', 'connect', {
      options: { address: '00:11:22:33:44:55' },
      output: { status: 'user.print_state' },
    })),
    trigger('print_test', 'Test print', tool('print', 'print', {
      options: { address: '00:11:22:33:44:55', text: 'APEX TOOLS TEST PRINT' },
      output: { status: 'user.print_state' },
    })),
    trigger('print_disconnect', 'Disconnect', tool('print', 'disconnect', { output: { status: 'user.print_state' } })),
  ],
)

const filesCard = probeCard(
  'files',
  'Files and screen',
  'Workspace sandbox files and screen capture',
  'File preview',
  expression("user.file_preview == '' ? 'no file yet' : user.file_preview"),
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
    trigger('screen_awake', 'Keep awake', tool('screen', 'keep_awake', { options: { enabled: 'true' }, output: { status: 'user.screen_state' } })),
    trigger('screen_shot', 'Screenshot', tool('screen', 'screenshot', { output: { uri: 'user.screen_uri', status: 'user.screen_state' } })),
    trigger('screen_record', 'Start recording', tool('screen', 'record_start', { output: { status: 'user.screen_state' } })),
    trigger('screen_stop', 'Stop recording', tool('screen', 'record_stop', { output: { uri: 'user.screen_video_uri', status: 'user.screen_state' } })),
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
          text({ text: '@{item.bytes} B', size: ramp.label, color: palette.textMuted }),
        ],
      }),
    }),
  ],
)

const dataViewsCard = divKitCard({
  logId: 'apex_tools_test_data_views',
  variables,
  states: [{
    div: container({
      spacing: space(2),
      padding: { bottom: space(4) },
      items: [
        header('Data views', 'Chart and list blocks over the samples collection'),
        container({
          padding: { left: space(4), right: space(4) },
          spacing: space(3),
          items: [
            chart({
              id: 'samples_chart',
              chartType: 'donut',
              title: 'SAMPLES BY ID',
              subtitle: 'Live from user.samples_json',
              dataVar: 'user.samples_json',
              labelField: 'name',
              valueField: 'id',
              height: 220,
            }),
            card([
              text({ text: 'SAMPLES', size: ramp.label, weight: 'bold', color: palette.textMuted }),
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
                    text({ text: '#@{item.id}', size: ramp.label, color: palette.accent }),
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
    { name: 'scanner', card: scannerCard },
    { name: 'code_generate', card: codeGenerateCard },
    { name: 'haptics', card: hapticsCard },
    { name: 'clipboard', card: clipboardCard },
    { name: 'net', card: netCard },
    { name: 'print', card: printCard },
    { name: 'files', card: filesCard },
    { name: 'data_views', card: dataViewsCard },
  ],
  catalog: {
    title: 'Harness',
    subtitle: 'Every capability, one card each.',
    entries: [
      { file: 'local_db', label: 'Local DB', icon: 'database', accent_color: ACCENT, subtitle: 'SQLite insert/query/count/delete' },
      { file: 'scanner', label: 'Scanner', icon: 'scan', accent_color: ACCENT, subtitle: 'Scan and OCR' },
      { file: 'code_generate', label: 'Code generator', icon: 'qr_code', accent_color: ACCENT, subtitle: 'QR, EAN-13, Code 128' },
      { file: 'haptics', label: 'Haptics and beeper', icon: 'haptics', accent_color: ACCENT, subtitle: 'Patterns and tones' },
      { file: 'clipboard', label: 'Clipboard', icon: 'clipboard', accent_color: ACCENT, subtitle: 'Copy, paste, clear' },
      { file: 'net', label: 'Network', icon: 'globe', accent_color: ACCENT, subtitle: 'HTTP and LAN hub' },
      { file: 'print', label: 'Printer', icon: 'printer', accent_color: ACCENT, subtitle: 'ESC/POS over Bluetooth' },
      { file: 'files', label: 'Files and screen', icon: 'folder', accent_color: ACCENT, subtitle: 'Sandbox files, capture' },
      { file: 'data_views', label: 'Data views', icon: 'chart', accent_color: ACCENT, subtitle: 'Chart and list blocks' },
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
