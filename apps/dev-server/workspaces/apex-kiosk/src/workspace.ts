import { nav } from '../../core/divkit/nav.ts'
import { DivContainer, DivText, matchParent } from '@divkitframework/jsonbuilder'
import type { WorkspaceDefinition, WorkspaceMeta } from '../../core/types.ts'
import {
  buildConfirmDeleteProductCard,
  buildCustomerHistoryCard,
  buildCustomerScanCard,
  buildCustomerShopCard,
  buildHomeCard,
  buildMapBarcodePickerCard,
  buildOwnerCheckoutSheet,
  buildOwnerInventoryCard,
  buildOwnerPosCard,
  buildOwnerQrSequenceCard,
  buildProductModalCard,
  buildScanSheetCard,
} from './cards.ts'
import { collections } from './collections.ts'
import { customerVariables, ownerVariables, productFormVariables } from './variables.ts'

const body = (card: any) => card.card.states[0].div

const pages = [
  { id: 'home', div: body(buildHomeCard()) },
  { id: 'owner_pos', div: body(buildOwnerPosCard()) },
  { id: 'owner_inventory', div: body(buildOwnerInventoryCard()) },
  { id: 'owner_checkout_sheet', div: body(buildOwnerCheckoutSheet()) },
  { id: 'owner_qr_sequence', div: body(buildOwnerQrSequenceCard()) },
  { id: 'product_modal', div: body(buildProductModalCard()) },
  { id: 'owner_scan_sheet', div: body(buildScanSheetCard()) },
  { id: 'customer_shop', div: body(buildCustomerShopCard()) },
  { id: 'customer_history', div: body(buildCustomerHistoryCard()) },
  { id: 'customer_scan', div: body(buildCustomerScanCard()) },
  { id: 'map_barcode_picker', div: body(buildMapBarcodePickerCard()) },
  { id: 'confirm_delete_product', div: body(buildConfirmDeleteProductCard()) },
]

const meta: WorkspaceMeta = {
  title: 'APEX Owner',
  subtitle: 'Owner POS, inventory and customer handoff · v1.5 live',
  terminal: 'POS-001',
  accent: '#6D4AFF',
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
    tunnel: 'https://apex-owner.local/',
    lan: 'http://192.168.1.21:8089',
    client_id: 'pk_live_bdbc75560c43b6605370bf4a',
    updates: { slug: 'owner-pos' },
  },
  schemaVersion: '1.5',
  unread: 0,
}

const hub = {
  card: {
    log_id: 'owner_hub',
    variables: [...ownerVariables(), ...productFormVariables(), ...customerVariables()] as any,
    states: [
      {
        state_id: 0,
        div: nav({
          initial: 'home',
          cards: pages as Array<{ id: string; div: any }>,
        }),
      },
    ],
  },
}

export const ownerPos: WorkspaceDefinition = {
  slug: 'owner-pos',
  manifestId: 'owner_pos',
  meta,
  hub: hub as any,
  cards: pages.map((page) => ({ name: page.id, card: page.div as any })),
  catalog: {
    title: 'APEX Owner',
    subtitle: 'Point of sale and customer verification',
    entries: pages.map((page) => ({
      file: page.id,
      label: page.id.replace(/_/g, ' '),
      icon: 'pos',
      accent_color: '#6D4AFF',
      subtitle: '',
    })),
  },
  collections: collections.map((collection) => ({
    file: `${collection.name}.json`,
    json: collection,
  })),
  targets: { android: true },
}
