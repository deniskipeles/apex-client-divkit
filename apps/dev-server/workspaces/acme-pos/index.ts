import { hub } from './src/hub.ts'
import { cards } from './src/cards.ts'
import { collections } from './src/collections.ts'
import type { ToolCatalog, WorkspaceDefinition, WorkspaceMeta } from '../core/types.ts'

/**
 * APEX POS, Android's built-in showcase.
 *
 * Authored here so the dev-server and the Android demo share one source; `workspaces:build`
 * writes `apps/android/src/dev/resources/demo/acme_pos`.
 */
const meta: WorkspaceMeta = {
  "title": "Apex POS Terminal",
  "subtitle": "DivKit SDUI showcase",
  "terminal": "POS-TERM-01",
  "accent": "#0284C7",
  "mode": "HYBRID",
  "capabilities": [
    "scanner",
    "printer",
    "nfc",
    "cash_drawer",
    "haptics",
    "beeper"
  ],
  "tools": {
    "hapticVibration": true,
    "hardwareBeeperAudio": true,
    "cameraBarcodeScanner": true,
    "supervisorBiometricAuth": true,
    "clipboardAccess": true,
    "backgroundAlertsPush": true,
    "offlinePageCaching": true,
    "realtimeEngineWsSse": true
  },
  "urls": {
    "tunnel": "https://apex-terminal.local/",
    "lan": "http://192.168.1.10:8080",
    "client_id": "pk_live_bdbc75560c43b6605370bf4a"
  },
  "unread": 4
}

const catalog: ToolCatalog = {
  "title": "🧰 NATIVE TOOL CARDS",
  "subtitle": "Tap any entry to push its own DivKit card (nav://push)",
  "entries": [
    {
      "file": "local_db",
      "label": "SQLite Database",
      "icon": "stock",
      "accent_color": "#0284C7"
    },
    {
      "file": "scanner",
      "label": "Scanner + OCR",
      "icon": "scanner",
      "accent_color": "#10B981"
    },
    {
      "file": "code_generate",
      "label": "Barcode / QR",
      "icon": "qr_code",
      "accent_color": "#0EA5E9"
    },
    {
      "file": "codec",
      "label": "Generic Codec",
      "icon": "lock",
      "accent_color": "#0284C7"
    },
    {
      "file": "camera",
      "label": "Camera Capture",
      "icon": "camera",
      "accent_color": "#6366F1"
    },
    {
      "file": "mobilenet",
      "label": "MobileNetV4 AI",
      "icon": "brain",
      "accent_color": "#A855F7"
    },
    {
      "file": "u2net",
      "label": "U-2-Net Matting",
      "icon": "wand",
      "accent_color": "#EC4899"
    },
    {
      "file": "image_manipulation",
      "label": "Image Editing",
      "icon": "palette",
      "accent_color": "#F97316"
    },
    {
      "file": "embedding",
      "label": "Embeddings",
      "icon": "numbers",
      "accent_color": "#14B8A6"
    },
    {
      "file": "libsignal",
      "label": "Libsignal E2EE",
      "icon": "lock",
      "accent_color": "#22C55E"
    },
    {
      "file": "biometrics",
      "label": "Supervisor Auth",
      "icon": "shield",
      "accent_color": "#EF4444"
    },
    {
      "file": "haptics",
      "label": "Haptic Engine",
      "icon": "vibrate",
      "accent_color": "#8B5CF6"
    },
    {
      "file": "beeper",
      "label": "Hardware Beeper",
      "icon": "bell",
      "accent_color": "#F59E0B"
    },
    {
      "file": "clipboard_push",
      "label": "Clipboard + Push",
      "icon": "clipboard",
      "accent_color": "#06B6D4"
    },
    {
      "file": "offline_cache",
      "label": "Offline Cache",
      "icon": "database",
      "accent_color": "#64748B"
    },
    {
      "file": "realtime_ws",
      "label": "Realtime Channel",
      "icon": "signal",
      "accent_color": "#3B82F6"
    },
    {
      "file": "net",
      "label": "Network Transfer",
      "icon": "globe",
      "accent_color": "#0891B2"
    },
    {
      "file": "print",
      "label": "Thermal Printer",
      "icon": "printer",
      "accent_color": "#7C3AED"
    },
    {
      "file": "screen",
      "label": "Screen Capture",
      "icon": "sun",
      "accent_color": "#22D3EE"
    },
    {
      "file": "files",
      "label": "Workspace Files",
      "icon": "folder",
      "accent_color": "#0EA5E9"
    },
    {
      "file": "native_icons",
      "label": "Icon Scheme",
      "icon": "palette",
      "accent_color": "#D946EF"
    },
    {
      "file": "catalog_list",
      "label": "Catalog List",
      "icon": "product",
      "accent_color": "#10B981"
    },
    {
      "file": "hourly_chart",
      "label": "Hourly Throughput",
      "icon": "chart",
      "accent_color": "#0284C7"
    },
    {
      "file": "sdui_chart",
      "label": "Revenue by SKU",
      "icon": "chart",
      "accent_color": "#F43F5E"
    },
    {
      "file": "sheet_showcase",
      "label": "Sheet showcase",
      "icon": "dashboard",
      "accent_color": "#8B5CF6"
    }
  ]
}

const order = [
  "local_db",
  "scanner",
  "code_generate",
  "codec",
  "camera",
  "mobilenet",
  "u2net",
  "image_manipulation",
  "embedding",
  "libsignal",
  "biometrics",
  "haptics",
  "beeper",
  "clipboard_push",
  "offline_cache",
  "realtime_ws",
  "net",
  "print",
  "screen",
  "files",
  "native_icons",
  "catalog_list",
  "hourly_chart",
  "sdui_chart",
  "sheet_showcase"
]

export const acmePos: WorkspaceDefinition = {
  slug: 'acme-pos',
  manifestId: 'acme_pos',
  meta,
  hub,
  cards: order.map((name) => ({ name, card: cards[name] })),
  catalog,
  collections: [
  "inventory.json",
  "orders.json",
  "cust_history.json"
].map((file) => ({
    file,
    json: collections[file.replace(/\.json$/, '')],
  })),
  targets: { android: true },
}
