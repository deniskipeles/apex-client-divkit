import { mkdirSync, writeFileSync } from 'node:fs'
import {
  buildCustomerHistoryCard,
  buildCustomerScanCard,
  buildCustomerShopCard,
  buildOwnerCheckoutSheet,
  buildOwnerInventoryCard,
  buildOwnerPosCard,
  buildOwnerQrSequenceCard,
  buildProductModalCard,
  buildScanSheetCard,
} from './cards.ts'

mkdirSync('dist/cards', { recursive: true })
const cards = {
  owner_pos: buildOwnerPosCard(),
  owner_checkout_sheet: buildOwnerCheckoutSheet(),
  owner_qr_sequence: buildOwnerQrSequenceCard(),
  owner_inventory: buildOwnerInventoryCard(),
  product_modal: buildProductModalCard(),
  owner_scan_sheet: buildScanSheetCard(),
  customer_shop: buildCustomerShopCard(),
  customer_history: buildCustomerHistoryCard(),
  customer_scan: buildCustomerScanCard(),
}

for (const [name, card] of Object.entries(cards)) {
  writeFileSync(`dist/cards/${name}.json`, JSON.stringify(card, null, 2), 'utf8')
}
