import type { WorkspaceMemoryDb } from '@apex/pos-ui/android-runtime'
import { createOperationRegistry } from '../../../workspaces/apex-kiosk/src/operations.ts'
import { workspaceExistingOps, workspaceStorage } from '../../../workspaces/apex-kiosk/src/storage.ts'

const registries = new WeakMap<WorkspaceMemoryDb, ReturnType<typeof createOperationRegistry>>()

function registryFor(db: WorkspaceMemoryDb) {
  let registry = registries.get(db)
  if (!registry) {
    registry = createOperationRegistry(workspaceStorage(db), workspaceExistingOps(db))
    registries.set(db, registry)
  }
  return registry
}

const money = (cents: number): string => `$${(cents / 100).toFixed(2)}`
const asText = (value: unknown): string => (value === null || value === undefined ? '' : String(value))
const asNumber = (value: unknown): number => {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : 0
}

function cartLines(db: WorkspaceMemoryDb, orderId: string) {
  return db.query('order_lines', { where: { order_id: orderId }, limit: 1000 }).map((row) => {
    const unit = asNumber(row.unit_price)
    const qty = asNumber(row.quantity)
    return {
      line_id: asText(row.id),
      sku: asText(row.sku),
      name: asText(row.item_name),
      qty,
      unit_price_display: money(Math.round(unit * 100)),
      line_total_display: money(Math.round(unit * qty * 100)),
      name_and_qty: `${asText(row.item_name)} × ${qty}`,
    }
  })
}

function cartUpdates(db: WorkspaceMemoryDb, orderId: string) {
  const lines = cartLines(db, orderId)
  const count = lines.reduce((sum, line) => sum + line.qty, 0)
  const total = lines.reduce((sum, line) => sum + Math.round(asNumber(line.line_total_display.replace('$', '')) * 100), 0)
  return {
    'owner.cart_id': orderId,
    'owner.cart_lines': lines,
    'owner.cart_count': count,
    'owner.cart_total': money(total),
    'owner.cart_footer_summary': `${count} item${count === 1 ? '' : 's'} · ${money(total)}`,
  }
}

function ensureDraftOrder(db: WorkspaceMemoryDb): string {
  const existing = db.query('orders', { where: { status: 'draft' }, limit: 1 })[0]
  if (existing) return asText(existing.id)
  return db.insertRecord('orders', {
    status: 'draft',
    shop_name: 'APEX Market',
    shop_terminal: 'Register',
    total: 0,
    item_count: 0,
  })
}

function inventoryUpdates(db: WorkspaceMemoryDb) {
  const rows = db.query('inventory', { limit: 1000 })
  return {
    'owner.inv_items': rows.map((row) => ({
      ...row,
      price_display: money(Math.round(asNumber(row.price) * 100)),
      code_display: asText(row.barcode) || asText(row.code),
      has_code: Boolean(row.barcode || row.code),
    })),
    'owner.inv_count': rows.length,
  }
}

function qrUpdates(result: {
  chunks: string[]
  checksum: string
  short_id: string
}) {
  return {
    'owner.qr_chunks': result.chunks.map((chunk) => ({ chunk })),
    'owner.qr_total': result.chunks.length,
    'owner.qr_index': 0,
    'owner.qr_progress_label': `Chunk 1 of ${result.chunks.length}`,
    'owner.qr_order_label': result.short_id,
    'owner.qr_confirmed': false,
  }
}

/**
 * Adapter between the generic `local-db://op/*` dispatcher and the owner/customer
 * operation registry. The registry returns domain results; this maps them onto the
 * `owner.*` / `cust.*` / `product.*` variables the cards read.
 */
export async function dispatchOwnerPosOperation(
  operation: string,
  args: Record<string, unknown>,
  db: WorkspaceMemoryDb,
  variables: Record<string, unknown> = {},
): Promise<{ updates: Record<string, unknown>; data?: unknown } | null> {
  if (!/^(pos|inventory|cust)\./.test(operation)) return null
  const registry = registryFor(db)
  const handler = (registry as unknown as Record<string, (payload: Record<string, unknown>) => Promise<unknown>>)[operation]

  switch (operation) {
    case 'pos.scan_resolve': {
      const result = (await handler(args)) as { ok: boolean; sku?: string; name?: string; price?: string; reason?: string }
      return {
        data: result,
        updates: {
          'owner.last_scan': asText(args.raw),
          'owner.last_scan_visible': true,
          'owner.last_scan_can_undo': false,
          'owner.scan_error_visible': !result.ok,
          'owner.scan_error_msg': result.reason ?? '',
          ...(result.ok ? { 'owner.highlighted_product_id': result.sku ?? '' } : {}),
        },
      }
    }
    case 'pos.add_by_sku': {
      const orderId = asText(args.order_id) || ensureDraftOrder(db)
      await handler({ ...args, order_id: orderId })
      return { updates: cartUpdates(db, orderId) }
    }
    case 'pos.set_qty':
    case 'pos.remove_line': {
      const result = await handler(args)
      const lineId = asText(args.line_id)
      const line = db.query('order_lines', { where: { id: lineId }, limit: 1 })[0]
      const orderId = asText(line?.order_id) || ensureDraftOrder(db)
      return { data: result, updates: cartUpdates(db, orderId) }
    }
    case 'pos.checkout':
    case 'pos.regenerate_qr': {
      const orderId = asText(args.order_id) || ensureDraftOrder(db)
      const result = (await handler({ ...args, order_id: orderId })) as {
        chunks: string[]
        checksum: string
        short_id: string
      }
      return { data: result, updates: { ...cartUpdates(db, orderId), ...qrUpdates(result) } }
    }
    case 'pos.qr_step': {
      const index = Math.max(0, asNumber(variables['owner.qr_index']) + asNumber(args.delta))
      return { updates: { 'owner.qr_index': index } }
    }
    case 'inventory.list':
      return { data: await handler(args), updates: inventoryUpdates(db) }
    case 'inventory.upsert': {
      const result = (await handler(args)) as { ok: boolean; product?: Record<string, unknown> }
      return { data: result, updates: { ...inventoryUpdates(db), 'product.dirty': false } }
    }
    case 'inventory.check_code': {
      const result = (await handler(args)) as { ok: boolean; conflict?: { sku: string; name: string } }
      return {
        data: result,
        updates: {
          'product.code_dup_warning': result.ok
            ? ''
            : `${result.conflict?.name ?? 'Product'} (${result.conflict?.sku ?? ''}) already uses this code.`,
          'product.code_dup_sku': result.conflict?.sku ?? '',
        },
      }
    }
    case 'inventory.open_form': {
      const id = asText(args.id)
      const row = id ? db.query('inventory', { where: { id }, limit: 1 })[0] : undefined
      return {
        updates: {
          'product.id': id,
          'product.name': asText(row?.name),
          'product.price': asText(row?.price),
          'product.photo_url': asText(row?.photo_url),
          'product.code': asText(row?.barcode) || asText(row?.code),
          'product.sku': asText(row?.sku),
          'product.description': asText(row?.description),
          'product.is_edit': Boolean(row),
          'product.dirty': false,
        },
      }
    }
    case 'inventory.suggest_sku': {
      const result = (await handler(args)) as { sku: string }
      return { data: result, updates: { 'product.sku_suggestion': result.sku } }
    }
    case 'cust.scan_append': {
      const result = (await handler(args)) as {
        accepted: boolean
        collected?: number[]
        total?: number
        reason?: string
      }
      const collected = result.collected?.length ?? 0
      const total = result.total ?? 0
      return {
        data: result,
        updates: {
          'cust.scan_current': collected,
          'cust.scan_total': total,
          'cust.scan_ring_label': `${collected} / ${total}`,
          'cust.scan_state': result.accepted ? 'scanning' : 'error',
          'cust.scan_error_msg': result.reason ?? '',
          'cust.scan_chunks': Array.from({ length: total }, (_, i) => ({
            chunk: String(i + 1),
            collected: result.collected?.includes(i + 1) ?? false,
          })),
        },
      }
    }
    case 'cust.assemble': {
      const result = (await handler(args)) as {
        ok: boolean
        order?: Record<string, unknown>
        reason?: string
      }
      return {
        data: result,
        updates: {
          'cust.scan_state': result.ok ? 'verified' : 'error',
          'cust.scan_error_msg': result.reason ?? '',
          'cust.received_order': result.order ?? {},
        },
      }
    }
    case 'cust.save_order': {
      const result = (await handler(args)) as { ok: boolean; id: string }
      const history = db.query('cust_history', { limit: 100 })
      return {
        data: result,
        updates: {
          'cust.history': history.map((row) => ({
            id: asText(row.id),
            short_id: asText(row.order_id).slice(0, 8),
            meta_line: `${asText(row.shop_name)} · ${asText(row.received_at).slice(0, 10)}`,
            total: asText(row.total),
          })),
          'cust.history_count': history.length,
        },
      }
    }
    case 'cust.scan_reset':
      return {
        updates: {
          'cust.scan_chunks': [],
          'cust.scan_collected': [],
          'cust.scan_corrupt': [],
          'cust.scan_state': 'idle',
          'cust.scan_current': 0,
          'cust.scan_total': 0,
          'cust.scan_ring_label': '0 / 0',
          'cust.scan_error_msg': '',
        },
      }
    case 'cust.open_history_detail': {
      const row = db.query('cust_history', { where: { id: asText(args.id) }, limit: 1 })[0]
      return { updates: { 'cust.detail_order': row ?? {} } }
    }
    default:
      if (!handler) return null
      return { data: await handler(args), updates: {} }
  }
}
