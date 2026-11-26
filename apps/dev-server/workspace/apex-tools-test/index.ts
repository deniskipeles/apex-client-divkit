import {
  button, card, chart, container, divKitCard, expression, inlineBanner, intVar, jsonList,
  localDb, matchParent, metric, navPop, navPush, palette, space, stringVar, text,
  type as ramp,
} from '../kit/index.ts'
import type { DivCard } from '../kit/card.ts'
import type { ToolCatalogEntry, WorkspaceDefinition } from '../types.ts'
import { TOOL_MODULES } from '../../tests/tools/index.ts'

const ACCENT = '#0EA5E9'

const variables = [
  stringVar('user.workspace_title', 'APEX TOOLS'),
  stringVar('user.tools_status', 'Idle'),
  stringVar('user.tools_result', ''),
  stringVar('user.tools_error', ''),
  stringVar('user.samples_json', '[]'),
  intVar('user.samples_count', 0),
  stringVar('user.db_result', ''),
  stringVar('user.chart_json', '[]'),
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

const dbQuery = () => localDb('db.query', { collection: 'samples', order_by: 'id', limit: 50, output: { _json: 'user.samples_json' } })

/** The SQLite data plane is `local-db://op/*`, not a `tools://` capability. */
const localDbCard = divKitCard({
  logId: 'apex_tools_test_local_db',
  variables,
  states: [{
    div: container({
      spacing: space(2),
      padding: { bottom: space(4) },
      items: [
        header('Local DB', 'SQLite data plane: insert, query, count, delete'),
        container({
          padding: { left: space(4), right: space(4) },
          spacing: space(3),
          items: [
            card([
              text({ text: 'Last result', size: ramp.label, weight: 'bold', color: palette.textMuted }),
              text({ text: expression('user.db_result'), size: ramp.body, weight: 'bold', color: palette.accent }),
            ]),
            card([
              button({ text: 'Insert sample', actions: [localDb('db.insert', {
                collection: 'samples',
                data: { name: 'Probe item', note: 'inserted from card' },
                output: { id: 'user.db_result' },
              })], height: space(11), size: ramp.small, id: 'db_insert' }),
              button({ text: 'Query samples', actions: [dbQuery()], tone: 'secondary', height: space(11), size: ramp.small, id: 'db_query' }),
              button({ text: 'Count samples', actions: [localDb('db.count', { collection: 'samples', output: { count: 'user.samples_count' } })], tone: 'secondary', height: space(11), size: ramp.small, id: 'db_count' }),
              button({ text: 'Delete inserted', actions: [localDb('db.delete', {
                collection: 'samples',
                where: { note: 'inserted from card' },
                output: { deleted: 'user.db_result' },
              })], tone: 'danger', height: space(11), size: ramp.small, id: 'db_delete' }),
            ]),
          ],
        }),
      ],
    }),
  }],
})

const dataViewsCard = divKitCard({
  logId: 'apex_tools_test_data_views',
  variables,
  states: [{
    div: container({
      spacing: space(2),
      padding: { bottom: space(4) },
      items: [
        header('Data views', 'Chart and list blocks fed by the live collection'),
        container({
          padding: { left: space(4), right: space(4) },
          spacing: space(3),
          items: [
            card([
              text({ text: 'Sample count', size: ramp.label, weight: 'bold', color: palette.textMuted }),
              text({ text: expression('user.samples_count'), size: ramp.display, weight: 'bold', color: palette.accent }),
              inlineBanner({ text: expression("user.tools_error == '' ? 'No errors' : user.tools_error"), tone: 'warn' }),
            ]),
            chart({ data: expression('user.chart_json'), kind: 'bar', height: space(40), id: 'samples_chart' }),
            jsonList({ data: expression('user.samples_json'), titleKey: 'name', subtitleKey: 'note', id: 'samples_list' }),
            card([
              button({ text: 'Refresh samples', actions: [dbQuery()], height: space(11), size: ramp.small, id: 'data_refresh' }),
            ]),
          ],
        }),
      ],
    }),
  }],
})

/** Icons resolve through the bundled Android catalog; unknown names fail its test. */
const ICONS: Record<string, string> = {
  scan: 'qr_code_scanner',
  haptics: 'vibration',
  beeper: 'volume_up',
  camera: 'photo_camera',
  files: 'folder',
  'image-manipulation': 'image',
  embedding: 'hub',
  clipboard: 'content_copy',
  biometrics: 'fingerprint',
  libsignal: 'lock',
  mobilenetv4: 'category',
  u2net: 'auto_fix_high',
  push: 'notifications',
  'offline-cache': 'cloud_off',
  'realtime-ws': 'bolt',
  net: 'public',
  print: 'print',
  codec: 'data_object',
  'code-generate': 'qr_code',
  screen: 'screenshot_monitor',
}

const LABELS: Record<string, string> = {
  scan: 'Scanner',
  haptics: 'Haptics',
  beeper: 'Beeper',
  camera: 'Camera',
  files: 'Files',
  'image-manipulation': 'Image editing',
  embedding: 'Embedding',
  clipboard: 'Clipboard',
  biometrics: 'Biometrics',
  libsignal: 'Libsignal',
  mobilenetv4: 'Image classifier',
  u2net: 'Background removal',
  push: 'Push alerts',
  'offline-cache': 'Offline cache',
  'realtime-ws': 'Realtime channel',
  net: 'Network',
  print: 'Printer',
  codec: 'Codec',
  'code-generate': 'Code generator',
  screen: 'Screen',
}

const cards: { name: string; card: DivCard }[] = [
  { name: 'local_db', card: localDbCard },
  ...TOOL_MODULES.map((module) => ({ name: module.slug, card: module.card as DivCard })),
  { name: 'data_views', card: dataViewsCard },
]

const catalogEntries: ToolCatalogEntry[] = [
  { file: 'local_db', label: 'Local DB', icon: 'database', accent_color: ACCENT, subtitle: 'SQLite insert, query, count, delete' },
  ...TOOL_MODULES.map((module): ToolCatalogEntry => ({
    file: module.slug,
    label: LABELS[module.slug] ?? module.slug,
    icon: ICONS[module.slug] ?? 'build',
    accent_color: ACCENT,
    subtitle: module.usage.split('.')[0],
  })),
  { file: 'data_views', label: 'Data views', icon: 'chart', accent_color: ACCENT, subtitle: 'Chart and list blocks' },
]

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
            text({ text: 'Every capability, one card each', size: ramp.small, color: palette.textMuted }),
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
          items: cards.map((entry) =>
            button({
              text: LABELS[entry.name] ?? (entry.name === 'local_db' ? 'Local DB' : 'Data views'),
              actions: [navPush(entry.name)],
              tone: entry.name === 'local_db' ? 'primary' : 'secondary',
              height: space(11),
              size: ramp.small,
              id: `nav_${entry.name.replace(/[^a-z0-9]+/gi, '_')}`,
            }),
          ),
        }),
      ],
    }),
  }],
})

export const apexToolsTest: WorkspaceDefinition = {
  slug: 'apex-tools-test',
  manifestId: 'apex_tools_test',
  meta: {
    title: 'Apex Tools',
    subtitle: 'Capability conformance harness',
    terminal: 'TERM-TOOLS-01',
    accent: ACCENT,
    mode: 'NATIVE',
    capabilities: ['tools', 'sqlite', 'camera', 'bluetooth'],
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
  cards,
  catalog: {
    title: 'Harness',
    subtitle: 'One card per tool module, generated from the conformance contract.',
    entries: catalogEntries,
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
