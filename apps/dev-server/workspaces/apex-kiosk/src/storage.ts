import type { WorkspaceMemoryDb } from '@apex/pos-ui/android-runtime'
import type { ExistingOps, ProductRecord, Storage } from './operations.ts'

const asText = (value: unknown): string => (value === null || value === undefined ? '' : String(value))
const asNumber = (value: unknown): number => {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : 0
}

export function workspaceStorage(db: WorkspaceMemoryDb): Storage {
  return {
    async findProductByBarcodeOrSku(raw) {
      const value = raw.trim()
      const rows = db.query('inventory', {
        where: { $or: [{ barcode: value }, { sku: value }, { code: value }, { sku: value.toUpperCase() }] },
        limit: 1,
      })
      return rows[0] ? (rows[0] as unknown as ProductRecord) : null
    },

    async findProductByCodeExcludingSku(code, excludeSku) {
      const rows = db.query('inventory', {
        where: { $or: [{ barcode: code }, { code }] },
        limit: 10,
      })
      const hit = rows.find((row) => asText(row.sku) !== asText(excludeSku))
      return hit ? (hit as unknown as ProductRecord) : null
    },

    async setProductBarcode(sku, raw) {
      const row = db.query('inventory', { where: { sku }, limit: 1 })[0]
      if (!row) throw new Error(`No product with SKU ${sku}`)
      db.updateRecord('inventory', asText(row.id), { barcode: raw, code: raw })
    },

    async listProducts(filter) {
      const needle = (filter ?? '').trim().toLowerCase()
      return db
        .query('inventory', { limit: 1000 })
        .filter((row) =>
          needle
            ? ['name', 'sku', 'code', 'barcode'].some((field) =>
                asText(row[field]).toLowerCase().includes(needle),
              )
            : true,
        ) as unknown as ProductRecord[]
    },

    async upsertProduct(product) {
      const data = {
        sku: product.sku,
        name: product.name,
        price: product.price,
        photo_url: product.photo_url ?? '',
        code: product.code ?? '',
        barcode: product.code ?? '',
        description: product.description ?? '',
        stock: product.stock,
      }
      const result = db.upsert('inventory', data, product.id)
      if (Object.keys(result.errors).length > 0) {
        throw new Error(Object.values(result.errors).join('; '))
      }
      const row = db.query('inventory', { where: { id: result.id }, limit: 1 })[0]
      return (row ?? { ...data, id: result.id }) as unknown as ProductRecord
    },

    async isProductReferencedInOrders(id) {
      const product = db.query('inventory', { where: { id }, limit: 1 })[0]
      const sku = asText(product?.sku)
      if (!sku) return false
      const lines = db.query('order_lines', { where: { sku }, limit: 1 })
      return lines.length > 0
    },

    async deleteProduct(id) {
      db.deleteRecord('inventory', id)
    },

    async insertCustomerHistory(row) {
      return db.insertRecord('cust_history', row)
    },
  }
}

export function workspaceExistingOps(db: WorkspaceMemoryDb): ExistingOps {
  return {
    async addLine(orderId, sku, qty) {
      const product = db.query('inventory', { where: { sku }, limit: 1 })[0]
      if (!product) throw new Error(`Unknown SKU ${sku}`)
      const added = db.appendChild('order_lines', orderId, {
        sku,
        item_name: asText(product.name),
        unit_price: asNumber(product.price),
        quantity: qty,
      })
      return {
        lineId: added.childId,
        totalCents: Math.round(asNumber(added.parent.total) * 100),
        count: asNumber(added.parent.item_count),
      }
    },

    async setLineQty(lineId, qty) {
      const result = db.setChildQuantity('order_lines', lineId, qty)
      return {
        removed: qty <= 0,
        totalCents: Math.round(asNumber(result.parent.total) * 100),
      }
    },

    async removeLine(lineId) {
      const result = db.removeChild('order_lines', lineId)
      return { undoPayload: result.removedJson ? JSON.parse(result.removedJson) : {} }
    },

    async recomputeOrderHeader(orderId) {
      const parent = db.recompute('orders', orderId)
      const order = db.query('orders', { where: { id: orderId }, limit: 1 })[0] ?? {}
      const lines = db.query('order_lines', { where: { order_id: orderId }, limit: 1000 })
      const items = lines.map((line) => {
        const unitCents = Math.round(asNumber(line.unit_price) * 100)
        const qty = asNumber(line.quantity)
        return {
          sku: asText(line.sku),
          name: asText(line.item_name),
          qty,
          unitCents,
          lineCents: unitCents * qty,
        }
      })
      return {
        id: orderId,
        items,
        totalCents: Math.round(asNumber(parent.total ?? order.total) * 100),
        shopName: asText(order.shop_name) || 'APEX Market',
        shopTerminal: asText(order.shop_terminal) || 'Register',
        createdAt: asText(order.created),
      }
    },

    async markSubmittedCustomerOrder(orderId, checksum) {
      db.updateRecord('orders', orderId, { status: 'submitted', checksum })
    },
  }
}
