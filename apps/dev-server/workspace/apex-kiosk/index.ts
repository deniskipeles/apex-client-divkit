import hubJson from './cards/card.json' with { type: 'json' }
import customerJson from './cards/kiosk_customer.json' with { type: 'json' }
import ownerJson from './cards/kiosk_owner.json' with { type: 'json' }
import transferJson from './cards/kiosk_transfer.json' with { type: 'json' }
import lanJson from './cards/kiosk_lan.json' with { type: 'json' }
import inventoryJson from './collections/inventory.json' with { type: 'json' }
import ordersJson from './collections/orders.json' with { type: 'json' }
import kioskOrdersJson from './collections/kiosk_orders.json' with { type: 'json' }
import kioskOrderLinesJson from './collections/kiosk_order_lines.json' with { type: 'json' }
import type { DivCard } from '../kit/card.ts'
import type { WorkspaceDefinition, WorkspaceMeta } from '../types.ts'

/**
 * APEX KIOSK port.
 *
 * The manifest and tool catalog are typed here; the four hand-authored card bodies and the
 * collection schemas are carried as data under `cards/` and `collections/` so the port changes
 * no layout, variable or action. `workspaces:build` owns the Android folder from here on.
 */
const meta: WorkspaceMeta = {
  title: 'APEX KIOSK',
  subtitle: 'Orders, receipts, QR & LAN',
  terminal: 'KSK-001',
  accent: '#7C3AED',
  mode: 'HYBRID',
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
  urls: {
    tunnel: 'https://apex-kiosk.local/',
    lan: 'http://192.168.1.20:8089',
    client_id: 'pk_live_bdbc75560c43b6605370bf4a',
  },
  unread: 2,
}

const card = (json: unknown): DivCard => json as DivCard

export const apexKiosk: WorkspaceDefinition = {
  slug: 'apex-kiosk',
  manifestId: 'apex_kiosk',
  meta,
  hub: card(hubJson),
  cards: [
    { name: 'kiosk_customer', card: card(customerJson) },
    { name: 'kiosk_owner', card: card(ownerJson) },
    { name: 'kiosk_transfer', card: card(transferJson) },
    { name: 'kiosk_lan', card: card(lanJson) },
  ],
  catalog: {
    title: 'Workflows',
    subtitle: 'Pick one to begin.',
    entries: [
      { file: 'kiosk_customer', label: 'Customer order', icon: 'product', accent_color: '#7C3AED', subtitle: 'Build and place an order' },
      { file: 'kiosk_owner', label: 'Owner receipt', icon: 'printer', accent_color: '#7C3AED', subtitle: 'Start from a receipt' },
      { file: 'kiosk_transfer', label: 'Send by QR', icon: 'qr_code', accent_color: '#7C3AED', subtitle: 'Move the order to another device' },
      { file: 'kiosk_lan', label: 'Share on the network', icon: 'globe', accent_color: '#7C3AED', subtitle: 'Same Wi-Fi, one code' },
    ],
  },
  collections: [
    { file: 'inventory.json', json: inventoryJson },
    { file: 'orders.json', json: ordersJson },
    { file: 'kiosk_orders.json', json: kioskOrdersJson },
    { file: 'kiosk_order_lines.json', json: kioskOrderLinesJson },
  ],
  targets: { android: true },
}
