import { describe, expect, it } from 'vitest'
import { WorkspaceMemoryDb } from '@apex/pos-ui/android-runtime'
import { dispatchWorkspaceUrl } from './toolRuntime'
import { inventory, orderLines, orders } from '../../../workspaces/apex-kiosk/src/collections.ts'

const ownerDb = () => new WorkspaceMemoryDb([inventory, orderLines, orders] as never)

async function run(db: WorkspaceMemoryDb, url: string, payload: Record<string, unknown>) {
  return dispatchWorkspaceUrl(url, payload, {}, { db })
}

describe('local-db:// owner-pos flow', () => {
  it('creates an order, adds lines and keeps the roll-ups in step', async () => {
    const db = ownerDb()

    const created = await run(db, 'local-db://op/db.upsert', {
      collection: 'orders',
      data: { status: 'draft', shop_terminal: 'POS-001' },
      output: { id: 'user.order_id', status: 'user.order_status', item_count: 'user.order_item_count', total_display: 'user.order_total' },
    })
    expect(created.status).toBe('ok')
    const orderId = String(created.updates['user.order_id'])
    expect(orderId).not.toBe('')
    expect(created.updates['user.order_status']).toBe('draft')
    expect(created.updates['user.order_item_count']).toBe(0)
    expect(created.updates['user.order_total']).toBe('0.00')

    const added = await run(db, 'local-db://op/db.append_child', {
      collection: 'order_lines',
      parent_id: orderId,
      data: { sku: 'LAT-01', item_name: 'Latte', unit_price: 4.5, quantity: 2 },
      output: {
        child_id: 'user.order_line_id',
        item_count: 'user.order_item_count',
        total: 'user.order_total_value',
        total_display: 'user.order_total',
        lines_json: 'user.order_lines',
      },
    })
    expect(added.status).toBe('ok')
    expect(added.updates['user.order_item_count']).toBe(1)
    expect(added.updates['user.order_total_value']).toBe(9)
    expect(added.updates['user.order_total']).toBe('9.00')
    const lines = JSON.parse(String(added.updates['user.order_lines'])) as Array<Record<string, unknown>>
    expect(lines).toHaveLength(1)
    expect(lines[0]).toMatchObject({ sku: 'LAT-01', quantity: 2, line_total_display: '9.00' })

    const merged = await run(db, 'local-db://op/db.append_child', {
      collection: 'order_lines',
      parent_id: orderId,
      data: { sku: 'LAT-01', item_name: 'Latte', unit_price: 4.5, quantity: 1 },
      output: { item_count: 'user.order_item_count', total: 'user.order_total_value' },
    })
    expect(merged.updates['user.order_item_count']).toBe(1)
    expect(merged.updates['user.order_total_value']).toBe(13.5)
  })

  it('refuses more than the stock on hand', async () => {
    const db = ownerDb()
    const created = await run(db, 'local-db://op/db.upsert', {
      collection: 'orders',
      data: { status: 'draft' },
      output: { id: 'user.order_id' },
    })
    const report = await run(db, 'local-db://op/db.append_child', {
      collection: 'order_lines',
      parent_id: String(created.updates['user.order_id']),
      data: { sku: 'LAT-01', item_name: 'Latte', unit_price: 4.5, quantity: 999 },
    })
    expect(report.status).toBe('failed')
    expect(report.message).toBe('Only 24 left — quantity unchanged')
    expect(report.updates['user.engine_error']).toBe('Only 24 left — quantity unchanged')
  })

  it('queries orders as JSON for a json_list', async () => {
    const db = ownerDb()
    const created = await run(db, 'local-db://op/db.upsert', {
      collection: 'orders',
      data: { status: 'draft', shop_terminal: 'POS-001' },
      output: { id: 'user.order_id' },
    })
    const orderId = String(created.updates['user.order_id'])
    await run(db, 'local-db://op/db.append_child', {
      collection: 'order_lines',
      parent_id: orderId,
      data: { sku: 'LAT-01', item_name: 'Latte', unit_price: 4.5, quantity: 2 },
    })

    const listed = await run(db, 'local-db://op/db.query', {
      collection: 'orders',
      where: { status: 'draft' },
      sort: '-created',
      limit: 20,
      output: { _json: 'user.orders_json', _count: 'user.orders_count', id: 'user.order_ids' },
    })
    expect(listed.status).toBe('ok')
    expect(listed.updates['user.orders_count']).toBe(1)
    expect(listed.updates['user.order_ids']).toEqual([orderId])
    const rows = JSON.parse(String(listed.updates['user.orders_json'])) as Array<Record<string, unknown>>
    expect(rows[0]).toMatchObject({ id: orderId, status: 'draft', item_count: 1, total: 9 })
    expect(JSON.parse(String(rows[0].lines_json))).toHaveLength(1)
  })
})
