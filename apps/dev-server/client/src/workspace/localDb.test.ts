import { describe, expect, it } from 'vitest'
import { WorkspaceMemoryDb } from '@apex/pos-ui/android-runtime'
import { dispatchWorkspaceUrl } from './toolRuntime'
import inventory from '../../../workspace/apex-kiosk/collections/inventory.json'
import orderLines from '../../../workspace/apex-kiosk/collections/kiosk_order_lines.json'
import orders from '../../../workspace/apex-kiosk/collections/kiosk_orders.json'

// The kiosk workspace is the data-plane showcase: create an order, add lines,
// change a quantity, commit it, then read it back as JSON for a `json_list`.
// Every step below is the payload the shipped cards send.

function kioskDb() {
  return new WorkspaceMemoryDb([inventory, orderLines, orders] as never)
}

async function run(db: WorkspaceMemoryDb, url: string, payload: Record<string, unknown>) {
  return dispatchWorkspaceUrl(url, payload, {}, { db })
}

describe('local-db:// kiosk flow', () => {
  it('creates an order, adds lines and keeps the roll-ups in step', async () => {
    const db = kioskDb()

    const created = await run(db, 'local-db://op/db.upsert', {
      collection: 'kiosk_orders',
      data: { direction: 'CUSTOMER_REQUEST', status: 'draft', terminal_id: 'KSK-001' },
      output: { id: 'user.kiosk_order_id', status: 'user.kiosk_status', item_count: 'user.kiosk_item_count', total_display: 'user.kiosk_total' },
    })
    expect(created.status).toBe('ok')
    const orderId = String(created.updates['user.kiosk_order_id'])
    expect(orderId).not.toBe('')
    expect(created.updates['user.kiosk_status']).toBe('draft')
    expect(created.updates['user.kiosk_item_count']).toBe(0)
    expect(created.updates['user.kiosk_total']).toBe('0.00')

    const added = await run(db, 'local-db://op/db.append_child', {
      collection: 'kiosk_order_lines',
      parent_id: orderId,
      data: { sku: 'P1001', item_name: 'Mineral Water', unit_price: 1.5, quantity: 2 },
      output: {
        child_id: 'user.kiosk_line_id',
        item_count: 'user.kiosk_item_count',
        total: 'user.kiosk_total_value',
        total_display: 'user.kiosk_total',
        line_count: 'user.kiosk_line_count',
        lines_json: 'user.kiosk_lines',
      },
    })
    expect(added.status).toBe('ok')
    expect(added.updates['user.kiosk_item_count']).toBe(2)
    expect(added.updates['user.kiosk_total_value']).toBe(3)
    expect(added.updates['user.kiosk_total']).toBe('3.00')
    expect(added.updates['user.kiosk_line_count']).toBe(1)
    const lines = JSON.parse(String(added.updates['user.kiosk_lines'])) as Array<Record<string, unknown>>
    expect(lines).toHaveLength(1)
    expect(lines[0]).toMatchObject({ sku: 'P1001', quantity: 2, line_total: '3.00' })

    // Same sku merges into the existing line instead of adding a second row.
    const merged = await run(db, 'local-db://op/db.append_child', {
      collection: 'kiosk_order_lines',
      parent_id: orderId,
      data: { sku: 'P1001', item_name: 'Mineral Water', unit_price: 1.5, quantity: 1 },
      output: { item_count: 'user.kiosk_item_count', total: 'user.kiosk_total_value', line_count: 'user.kiosk_line_count' },
    })
    expect(merged.updates['user.kiosk_line_count']).toBe(1)
    expect(merged.updates['user.kiosk_item_count']).toBe(3)
    expect(merged.updates['user.kiosk_total_value']).toBe(4.5)
  })

  it('refuses more than the stock on hand', async () => {
    const db = kioskDb()
    const created = await run(db, 'local-db://op/db.upsert', {
      collection: 'kiosk_orders',
      data: { direction: 'CUSTOMER_REQUEST', status: 'draft' },
      output: { id: 'user.kiosk_order_id' },
    })
    const report = await run(db, 'local-db://op/db.append_child', {
      collection: 'kiosk_order_lines',
      parent_id: String(created.updates['user.kiosk_order_id']),
      data: { sku: 'P1001', item_name: 'Mineral Water', unit_price: 1.5, quantity: 999 },
    })
    expect(report.status).toBe('failed')
    expect(report.message).toBe('Only 120 left — quantity unchanged')
    expect(report.updates['user.engine_error']).toBe('Only 120 left — quantity unchanged')
  })

  it('commits the order and then refuses further edits', async () => {
    const db = kioskDb()
    const created = await run(db, 'local-db://op/db.upsert', {
      collection: 'kiosk_orders',
      data: { direction: 'CUSTOMER_REQUEST', status: 'draft' },
      output: { id: 'user.kiosk_order_id' },
    })
    const orderId = String(created.updates['user.kiosk_order_id'])
    await run(db, 'local-db://op/db.append_child', {
      collection: 'kiosk_order_lines',
      parent_id: orderId,
      data: { sku: 'P1008', item_name: 'Sandwich', unit_price: 4, quantity: 1 },
    })

    const committed = await run(db, 'local-db://op/db.commit', {
      collection: 'kiosk_orders',
      id: orderId,
      output: { status: 'user.kiosk_status', total_display: 'user.kiosk_total' },
    })
    expect(committed.status).toBe('ok')
    expect(committed.updates['user.kiosk_status']).toBe('submitted')
    expect(committed.updates['user.kiosk_total']).toBe('4.00')

    const refused = await run(db, 'local-db://op/db.append_child', {
      collection: 'kiosk_order_lines',
      parent_id: orderId,
      data: { sku: 'P1008', item_name: 'Sandwich', unit_price: 4, quantity: 1 },
    })
    expect(refused.status).toBe('failed')
    expect(refused.message).toBe('This order is submitted and can no longer change')
  })

  it('refuses to commit an empty order', async () => {
    const db = kioskDb()
    const created = await run(db, 'local-db://op/db.upsert', {
      collection: 'kiosk_orders',
      data: { direction: 'OWNER_RECEIPT', status: 'draft' },
      output: { id: 'user.kiosk_order_id' },
    })
    const report = await run(db, 'local-db://op/db.commit', {
      collection: 'kiosk_orders',
      id: String(created.updates['user.kiosk_order_id']),
    })
    expect(report.status).toBe('failed')
    expect(report.message).toBe('Nothing to commit yet — add something first')
  })

  it('queries submitted orders as JSON for a json_list', async () => {
    const db = kioskDb()
    const created = await run(db, 'local-db://op/db.upsert', {
      collection: 'kiosk_orders',
      data: { direction: 'CUSTOMER_REQUEST', status: 'draft' },
      output: { id: 'user.kiosk_order_id' },
    })
    const orderId = String(created.updates['user.kiosk_order_id'])
    await run(db, 'local-db://op/db.append_child', {
      collection: 'kiosk_order_lines',
      parent_id: orderId,
      data: { sku: 'P1012', item_name: 'Coffee', unit_price: 2.5, quantity: 2 },
    })
    await run(db, 'local-db://op/db.commit', { collection: 'kiosk_orders', id: orderId })

    const listed = await run(db, 'local-db://op/db.query', {
      collection: 'kiosk_orders',
      where: { status: 'submitted' },
      sort: '-created_at',
      limit: 20,
      output: { _json: 'user.kiosk_orders_json', _count: 'user.kiosk_orders_count', id: 'user.kiosk_order_ids' },
    })
    expect(listed.status).toBe('ok')
    expect(listed.updates['user.kiosk_orders_count']).toBe(1)
    expect(listed.updates['user.kiosk_order_ids']).toEqual([orderId])
    const rows = JSON.parse(String(listed.updates['user.kiosk_orders_json'])) as Array<Record<string, unknown>>
    expect(rows[0]).toMatchObject({ id: orderId, status: 'submitted', item_count: 2, total: 5 })
    expect(JSON.parse(String(rows[0].lines_json))).toHaveLength(1)
  })

  it('loads a single record into scalars', async () => {
    const db = kioskDb()
    const created = await run(db, 'local-db://op/db.upsert', {
      collection: 'kiosk_orders',
      data: { direction: 'OWNER_RECEIPT', status: 'draft', terminal_id: 'KSK-9' },
      output: { id: 'user.kiosk_order_id' },
    })
    const loaded = await run(db, 'local-db://op/db.query', {
      collection: 'kiosk_orders',
      where: { id: String(created.updates['user.kiosk_order_id']) },
      single: true,
      output: { id: 'user.kiosk_order_id', status: 'user.kiosk_status', terminal_id: 'user.kiosk_terminal' },
    })
    expect(loaded.status).toBe('ok')
    expect(loaded.updates['user.kiosk_status']).toBe('draft')
    expect(loaded.updates['user.kiosk_terminal']).toBe('KSK-9')
  })

  it('packs a record into transfer chunks and unpacks them again', async () => {
    const db = kioskDb()
    const created = await run(db, 'local-db://op/db.upsert', {
      collection: 'kiosk_orders',
      data: { direction: 'CUSTOMER_REQUEST', status: 'draft' },
      output: { id: 'user.kiosk_order_id' },
    })
    const orderId = String(created.updates['user.kiosk_order_id'])
    await run(db, 'local-db://op/db.append_child', {
      collection: 'kiosk_order_lines',
      parent_id: orderId,
      data: { sku: 'P1015', item_name: 'Chocolate Bar', unit_price: 1.75, quantity: 3 },
    })

    const packed = await run(db, 'local-db://op/doc.pack', {
      collection: 'kiosk_orders',
      id: orderId,
      output: { chunks_json: 'user.kiosk_chunks', chunk_count: 'user.kiosk_chunk_count', checksum: 'user.kiosk_checksum' },
    })
    expect(packed.status).toBe('ok')
    const chunks = JSON.parse(String(packed.updates['user.kiosk_chunks'])) as string[]
    expect(chunks.length).toBe(packed.updates['user.kiosk_chunk_count'])
    expect(chunks[0].startsWith('1|')).toBe(true)

    const unpacked = await run(db, 'local-db://op/doc.unpack', {
      chunks,
      checksum: packed.updates['user.kiosk_checksum'],
      output: { ok: 'user.kiosk_unpacked', payload_json: 'user.kiosk_payload' },
    })
    expect(unpacked.status).toBe('ok')
    expect(unpacked.updates['user.kiosk_unpacked']).toBe(true)
    const payload = JSON.parse(String(unpacked.updates['user.kiosk_payload'])) as Record<string, unknown>
    expect(payload).toMatchObject({ id: orderId, status: 'draft', item_count: 3, total: 5.25 })
    expect(payload.lines).toEqual([{ sku: 'P1015', item_name: 'Chocolate Bar', unit_price: 1.75, quantity: 3 }])
  })

  it('refuses to delete a product a line still references', async () => {
    const db = kioskDb()
    const created = await run(db, 'local-db://op/db.upsert', {
      collection: 'kiosk_orders',
      data: { direction: 'CUSTOMER_REQUEST', status: 'draft' },
      output: { id: 'user.kiosk_order_id' },
    })
    await run(db, 'local-db://op/db.append_child', {
      collection: 'kiosk_order_lines',
      parent_id: String(created.updates['user.kiosk_order_id']),
      data: { sku: 'P1020', item_name: 'Fresh Juice', unit_price: 3.25, quantity: 1 },
    })
    const product = db.query('inventory', { where: { sku: 'P1020' }, limit: 1 })[0]

    const refused = await run(db, 'local-db://op/db.delete', {
      collection: 'inventory',
      id: String(product.id),
    })
    expect(refused.status).toBe('failed')
    expect(refused.message).toContain("This product is on past orders and can't be deleted")
  })

  it('reports an unknown operation instead of doing nothing', async () => {
    const db = kioskDb()
    const report = await run(db, 'local-db://op/db.teleport', { collection: 'kiosk_orders' })
    expect(report.status).toBe('failed')
    expect(report.message).toBe('Unknown operation: db.teleport')
  })

  it('is unavailable when the workspace has no database', async () => {
    const report = await dispatchWorkspaceUrl('local-db://op/db.count', { collection: 'inventory' }, {}, {})
    expect(report.status).toBe('unavailable')
  })
})
