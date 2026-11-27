import { describe, expect, it } from 'vitest'
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
import { buildDeterministicQrChunks, createOperationRegistry } from './operations.ts'

const BANNED_VISIBLE_PATTERNS = [
  /\bkiosk\.[a-z0-9_]+/i,
  /\bpos\.[a-z0-9_]+/i,
  /\bcust\.[a-z0-9_]+/i,
  /\binventory\.[a-z0-9_]+/i,
  /\bproduct\.[a-z0-9_]+/i,
  /\bowner\.[a-z0-9_]+/i,
  /\bdb\.[a-z0-9_]+/i,
  /\buser\.[a-z0-9_]+/i,
  /:\d{4,5}\b/,
  /\b[0-9a-f]{32,64}\b/i,
  /^\s*\{.*\}\s*$/,
]

function collectVisibleStrings(node: unknown, out: string[] = []): string[] {
  if (!node || typeof node !== 'object') return out
  if (Array.isArray(node)) {
    for (const item of node) collectVisibleStrings(item, out)
    return out
  }
  const rec = node as Record<string, unknown>
  for (const key of ['text', 'label', 'hint_text', 'empty_label', 'unavailable_copy', 'collapsed_label']) {
    const val = rec[key]
    if (typeof val === 'string' && !val.startsWith('@{') && !val.startsWith('$item.')) {
      out.push(val)
    }
  }
  for (const [k, v] of Object.entries(rec)) {
    if (k !== 'actions' && k !== 'variables') collectVisibleStrings(v, out)
  }
  return out
}

describe('owner / customer UI copy', () => {
  it('never leaks internal identifiers into visible card copy', () => {
    const cards = [
      buildOwnerPosCard(),
      buildOwnerCheckoutSheet(),
      buildOwnerQrSequenceCard(),
      buildOwnerInventoryCard(),
      buildProductModalCard(),
      buildScanSheetCard(),
      buildCustomerShopCard(),
      buildCustomerHistoryCard(),
      buildCustomerScanCard(),
    ]
    for (const card of cards) {
      for (const visible of collectVisibleStrings(card)) {
        for (const re of BANNED_VISIBLE_PATTERNS) {
          expect(re.test(visible), `Banned identifier "${visible}" matched ${re}`).toBe(false)
        }
      }
    }
  })
})

describe('deterministic QR chunks', () => {
  it('is byte-identical for the same order and chunk size', () => {
    const order = {
      id: 'a1b2c3d4-0000-1111-2222-333344445555',
      items: [{ sku: 'LAT-01', qty: 2, unitCents: 450, lineCents: 900 }],
      total_cents: 900,
    }
    const first = buildDeterministicQrChunks(order, 500)
    const second = buildDeterministicQrChunks(order, 500)
    expect(first).toEqual(second)
    for (const chunk of first.chunks) {
      expect(new TextEncoder().encode(chunk).length).toBeLessThanOrEqual(500)
    }
  })
})

describe('scan assembly', () => {
  it('refuses incomplete or corrupted chunks and never saves', async () => {
    const ops = createOperationRegistry(
      {
        findProductByBarcodeOrSku: async () => null,
        findProductByCodeExcludingSku: async () => null,
        setProductBarcode: async () => {},
        listProducts: async () => [],
        upsertProduct: async (p) => p,
        isProductReferencedInOrders: async () => false,
        deleteProduct: async () => {},
        insertCustomerHistory: async () => {
          throw new Error('Must not persist')
        },
      },
      {
        addLine: async () => ({ lineId: '1', totalCents: 450, count: 1 }),
        setLineQty: async () => ({ totalCents: 450 }),
        removeLine: async () => ({ undoPayload: {} }),
        recomputeOrderHeader: async () => ({
          id: 'a1b2c3d4',
          items: [{ sku: 'LAT-01', name: 'Latte', qty: 1, unitCents: 450, lineCents: 450 }],
          totalCents: 450,
          shopName: 'APEX',
          shopTerminal: 'Register',
          createdAt: '2026-11-27T10:00:00Z',
        }),
        markSubmittedCustomerOrder: async () => {},
      },
    )
    const corrupt = await ops['cust.scan_append']({ chunk_string: 'AX1|1|2|A1B2|~~~BAD~~~' })
    expect(corrupt.accepted).toBe(false)
    expect(corrupt.corrupt).toBe(true)
    const assembled = await ops['cust.assemble']()
    expect(assembled.ok).toBe(false)
  })
})

describe('inventory rules', () => {
  it('rejects duplicate SKUs and non-decimal prices; delete refuses referenced products', async () => {
    const ops = createOperationRegistry(
      {
        findProductByBarcodeOrSku: async (raw) =>
          raw === 'LAT-01'
            ? { id: 'p1', name: 'Latte', price: '4.50', sku: 'LAT-01', stock: 5 }
            : null,
        findProductByCodeExcludingSku: async () => null,
        setProductBarcode: async () => {},
        listProducts: async () => [],
        upsertProduct: async (p) => p,
        isProductReferencedInOrders: async (id) => id === 'p1',
        deleteProduct: async () => {},
        insertCustomerHistory: async () => 'h1',
      },
      {} as never,
    )

    const dupSku = await ops['inventory.upsert']({
      product: { name: 'Latte', price: '4.50', sku: 'LAT-01', stock: 5 },
    })
    expect(dupSku.ok).toBe(false)
    expect((dupSku as any).errors.sku).toContain('LAT-02')

    const badPrice = await ops['inventory.upsert']({
      product: { name: 'Tea', price: 'abc', sku: 'TEA-01', stock: 5 },
    })
    expect(badPrice.ok).toBe(false)
    expect((badPrice as any).errors.price).toBeTruthy()

    const del = await ops['inventory.delete']({ id: 'p1' })
    expect(del.ok).toBe(false)
    if (!del.ok) expect(del.reason).toContain('past orders')
  })
})
