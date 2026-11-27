import { describe, expect, it } from 'vitest'
import { WorkspaceMemoryDb } from '@apex/pos-ui/android-runtime'
import { collections } from '../../../workspaces/apex-kiosk/src/collections.ts'
import { dispatchOwnerPosOperation } from './ownerPosOps.ts'

const db = () => new WorkspaceMemoryDb(collections as never)

describe('owner-pos operation adapter', () => {
  it('lists the seeded inventory into owner.inv_items', async () => {
    const result = await dispatchOwnerPosOperation('inventory.list', {}, db())
    expect(result).not.toBeNull()
    expect((result!.updates['owner.inv_items'] as unknown[]).length).toBeGreaterThan(0)
    expect(result!.updates['owner.inv_count']).toBeGreaterThan(0)
  })

  it('creates a draft order, adds a line and produces QR chunks', async () => {
    const database = db()
    const added = await dispatchOwnerPosOperation(
      'pos.add_by_sku',
      { sku: 'LAT-01', qty: 2 },
      database,
    )
    expect(added).not.toBeNull()
    expect((added!.updates['owner.cart_lines'] as unknown[]).length).toBe(1)
    const orderId = String(added!.updates['owner.cart_id'])
    expect(orderId).not.toBe('')

    const checkout = await dispatchOwnerPosOperation('pos.checkout', { order_id: orderId }, database)
    expect(checkout).not.toBeNull()
    expect((checkout!.updates['owner.qr_chunks'] as unknown[]).length).toBeGreaterThan(0)
    expect(checkout!.updates['owner.qr_total']).toBeGreaterThan(0)
  })

  it('collects and assembles the QR chunks back into an order', async () => {
    const database = db()
    const added = await dispatchOwnerPosOperation(
      'pos.add_by_sku',
      { sku: 'TEA-01', qty: 1 },
      database,
    )
    const orderId = String(added!.updates['owner.cart_id'])
    const checkout = await dispatchOwnerPosOperation('pos.checkout', { order_id: orderId }, database)
    const chunks = checkout!.updates['owner.qr_chunks'] as Array<{ chunk: string }>
    for (const chunk of chunks) {
      const scanned = await dispatchOwnerPosOperation(
        'cust.scan_append',
        { chunk_string: chunk.chunk },
        database,
      )
      expect(scanned).not.toBeNull()
    }
    const assembled = await dispatchOwnerPosOperation('cust.assemble', {}, database)
    expect(assembled).not.toBeNull()
    expect(assembled!.updates['cust.scan_state']).toBe('verified')
  })
})
