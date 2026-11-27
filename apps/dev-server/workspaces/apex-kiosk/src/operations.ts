const B45 = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:'

export function base45Encode(bytes: Uint8Array): string {
  let out = ''
  for (let i = 0; i < bytes.length; i += 2) {
    if (i + 1 < bytes.length) {
      const x = (bytes[i] << 8) + bytes[i + 1]
      const e = Math.floor(x / (45 * 45))
      const d = Math.floor((x % (45 * 45)) / 45)
      const c = x % 45
      out += B45[c] + B45[d] + B45[e]
    } else {
      const x = bytes[i]
      out += B45[x % 45] + B45[Math.floor(x / 45)]
    }
  }
  return out
}

export function base45Decode(str: string): Uint8Array {
  if (!str || str.length % 3 === 1) throw new Error('Invalid Base45 length')
  const out: number[] = []
  for (let i = 0; i < str.length; i += 3) {
    const c = B45.indexOf(str[i])
    const d = B45.indexOf(str[i + 1])
    if (c < 0 || d < 0) throw new Error('Invalid Base45 character')
    if (i + 2 < str.length) {
      const e = B45.indexOf(str[i + 2])
      if (e < 0) throw new Error('Invalid Base45 character')
      const v = c + d * 45 + e * 45 * 45
      if (v > 0xffff) throw new Error('Base45 overflow')
      out.push(v >> 8, v & 0xff)
    } else {
      const v = c + d * 45
      if (v > 0xff) throw new Error('Base45 overflow')
      out.push(v)
    }
  }
  return new Uint8Array(out)
}

export function shortOrderLabel(recordId: string): string {
  const hex = recordId.replace(/[^a-fA-F0-9]/g, '').toUpperCase()
  return `#${hex.slice(0, 4).padEnd(4, '0')}`
}

export function canonicalJson(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`
  if (value && typeof value === 'object') {
    const keys = Object.keys(value as Record<string, unknown>).sort()
    return `{${keys
      .map((k) => `${JSON.stringify(k)}:${canonicalJson((value as Record<string, unknown>)[k])}`)
      .join(',')}}`
  }
  return JSON.stringify(value)
}

/** Synchronous SHA-256 so the QR chunker stays deterministic in a browser and in Node. */
export function sha256Hex(input: string | Uint8Array): string {
  const bytes = typeof input === 'string' ? new TextEncoder().encode(input) : input
  const K = [
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
  ]
  const H = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19]
  const padded = new Uint8Array((((bytes.length + 9 + 63) >> 6) << 6))
  padded.set(bytes)
  padded[bytes.length] = 0x80
  const view = new DataView(padded.buffer)
  const bitLength = bytes.length * 8
  view.setUint32(padded.length - 8, Math.floor(bitLength / 0x100000000))
  view.setUint32(padded.length - 4, bitLength >>> 0)
  const w = new Uint32Array(64)
  for (let offset = 0; offset < padded.length; offset += 64) {
    for (let i = 0; i < 16; i++) w[i] = view.getUint32(offset + i * 4)
    for (let i = 16; i < 64; i++) {
      const s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3)
      const s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10)
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) >>> 0
    }
    let [a, b, c, d, e, f, g, h] = H
    for (let i = 0; i < 64; i++) {
      const S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)
      const ch = (e & f) ^ (~e & g)
      const temp1 = (h + S1 + ch + K[i] + w[i]) >>> 0
      const S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)
      const maj = (a & b) ^ (a & c) ^ (b & c)
      const temp2 = (S0 + maj) >>> 0
      h = g
      g = f
      f = e
      e = (d + temp1) >>> 0
      d = c
      c = b
      b = a
      a = (temp1 + temp2) >>> 0
    }
    H[0] = (H[0] + a) >>> 0
    H[1] = (H[1] + b) >>> 0
    H[2] = (H[2] + c) >>> 0
    H[3] = (H[3] + d) >>> 0
    H[4] = (H[4] + e) >>> 0
    H[5] = (H[5] + f) >>> 0
    H[6] = (H[6] + g) >>> 0
    H[7] = (H[7] + h) >>> 0
  }
  return H.map((value) => value.toString(16).padStart(8, '0')).join('')
}

function rotr(value: number, bits: number): number {
  return (value >>> bits) | (value << (32 - bits))
}

export function buildDeterministicQrChunks(
  orderPayload: Record<string, unknown>,
  chunkBytes = 500,
): { payloadJson: string; checksum: string; chunks: string[] } {
  const payloadJson = canonicalJson(orderPayload)
  const checksum = sha256Hex(payloadJson)
  const b45 = base45Encode(new TextEncoder().encode(payloadJson))
  const tag = shortOrderLabel(String(orderPayload.id)).slice(1)
  const maxBody = Math.max(3, Math.floor((chunkBytes - 20) / 3) * 3)
  const slices: string[] = []
  for (let i = 0; i < b45.length; i += maxBody) slices.push(b45.slice(i, i + maxBody))
  const total = slices.length
  const chunks = slices.map((body, idx) => `AX1|${idx + 1}|${total}|${tag}|${body}`)
  return { payloadJson, checksum, chunks }
}

export interface ExistingOps {
  addLine(
    orderId: string,
    sku: string,
    qty: number,
  ): Promise<{ lineId: string; totalCents: number; count: number }>
  setLineQty(lineId: string, qty: number): Promise<{ removed?: boolean; totalCents: number }>
  removeLine(lineId: string): Promise<{ undoPayload: Record<string, unknown> }>
  recomputeOrderHeader(orderId: string): Promise<{
    id: string
    items: Array<{ sku: string; name: string; qty: number; unitCents: number; lineCents: number }>
    totalCents: number
    shopName: string
    shopTerminal: string
    createdAt: string
  }>
  markSubmittedCustomerOrder(orderId: string, checksum: string): Promise<void>
}

export interface ProductRecord {
  id?: string
  name: string
  price: string
  photo_url?: string
  code?: string | null
  sku: string
  description?: string
  stock: number
}

export interface Storage {
  findProductByBarcodeOrSku(raw: string): Promise<ProductRecord | null>
  findProductByCodeExcludingSku(code: string, excludeSku?: string): Promise<ProductRecord | null>
  setProductBarcode(sku: string, raw: string): Promise<void>
  listProducts(filter?: string): Promise<ProductRecord[]>
  upsertProduct(p: ProductRecord & { priceCents: number }): Promise<ProductRecord>
  isProductReferencedInOrders(id: string): Promise<boolean>
  deleteProduct(id: string): Promise<void>
  insertCustomerHistory(row: {
    id: string
    shop_terminal: string
    shop_name: string
    order_id: string
    payload_json: string
    checksum: string
    item_count: number
    total: string
    received_at: string
  }): Promise<string>
}

export function createOperationRegistry(db: Storage, existing: ExistingOps) {
  const scanBuffer = {
    total: 0,
    chunks: new Map<number, string>(),
    corrupt: new Set<number>(),
    orderTag: '',
  }

  return {
    'pos.scan_resolve': async (args: { raw: string; mode: 'barcode' | 'qr' | 'ocr' }) => {
      const hit = await db.findProductByBarcodeOrSku(args.raw.trim())
      if (!hit) return { ok: false, reason: 'Unknown barcode — tap Map to product to assign it.' }
      if (hit.stock <= 0) return { ok: false, reason: 'Oversell blocked — only 0 left. Reduce quantity.' }
      return { ok: true, sku: hit.sku, name: hit.name, price: hit.price }
    },

    'pos.map_barcode': async (args: { raw: string; sku: string }) => {
      await db.setProductBarcode(args.sku, args.raw.trim())
      return { ok: true }
    },

    'pos.add_by_sku': (args: { order_id: string; sku: string; qty?: number }) =>
      existing.addLine(args.order_id, args.sku, args.qty ?? 1),

    'pos.set_qty': (args: { line_id: string; qty: number }) =>
      existing.setLineQty(args.line_id, args.qty),

    'pos.remove_line': (args: { line_id: string }) => existing.removeLine(args.line_id),

    'pos.checkout': async (args: { order_id: string; chunk_bytes?: number }) => {
      const header = await existing.recomputeOrderHeader(args.order_id)
      const { payloadJson, checksum, chunks } = buildDeterministicQrChunks(
        {
          id: header.id,
          short_id: shortOrderLabel(header.id),
          shop_name: header.shopName,
          shop_terminal: header.shopTerminal,
          items: header.items,
          total_cents: header.totalCents,
          created_at: header.createdAt,
        },
        args.chunk_bytes ?? 500,
      )
      await existing.markSubmittedCustomerOrder(header.id, checksum)
      return { ok: true, chunks, checksum, short_id: shortOrderLabel(header.id), payload_json: payloadJson }
    },

    'pos.regenerate_qr': async (args: { order_id: string; chunk_bytes?: number }) => {
      const header = await existing.recomputeOrderHeader(args.order_id)
      return buildDeterministicQrChunks(
        {
          id: header.id,
          short_id: shortOrderLabel(header.id),
          shop_name: header.shopName,
          shop_terminal: header.shopTerminal,
          items: header.items,
          total_cents: header.totalCents,
          created_at: header.createdAt,
        },
        args.chunk_bytes ?? 500,
      )
    },

    'cust.scan_append': async (args: { chunk_string: string }) => {
      const m = /^AX1\|(\d+)\|(\d+)\|([A-Z0-9]+)\|(.*)$/.exec(args.chunk_string)
      if (!m) return { accepted: false, reason: 'Unrecognized QR format — ask the owner to reopen the QR.' }
      const idx = Number(m[1])
      const total = Number(m[2])
      const tag = m[3]
      const body = m[4]
      if (scanBuffer.total === 0) {
        scanBuffer.total = total
        scanBuffer.orderTag = tag
      }
      if (scanBuffer.chunks.has(idx)) {
        return { accepted: false, duplicate: true, reason: `Chunk ${idx} already scanned — ignored` }
      }
      try {
        base45Decode(body)
        scanBuffer.corrupt.delete(idx)
        scanBuffer.chunks.set(idx, body)
        return { accepted: true, index: idx, total, collected: [...scanBuffer.chunks.keys()] }
      } catch {
        scanBuffer.corrupt.add(idx)
        return { accepted: false, corrupt: true, index: idx, reason: `Chunk ${idx} is corrupted — rescan it` }
      }
    },

    'cust.assemble': async () => {
      if (
        scanBuffer.total === 0 ||
        scanBuffer.chunks.size !== scanBuffer.total ||
        scanBuffer.corrupt.size > 0
      ) {
        return { ok: false, reason: 'Chunks incomplete or corrupted — rescan missing chunks.' }
      }
      try {
        const ordered = Array.from({ length: scanBuffer.total }, (_, i) => scanBuffer.chunks.get(i + 1)!).join('')
        const jsonStr = new TextDecoder().decode(base45Decode(ordered))
        const order = JSON.parse(jsonStr)
        const checksum = sha256Hex(canonicalJson(order))
        return { ok: true, order: { ...order, checksum, payload_json: jsonStr } }
      } catch {
        return {
          ok: false,
          reason: 'The order did not arrive intact. Ask the owner to regenerate the QR and rescan.',
        }
      }
    },

    'cust.save_order': async (args: {
      order: {
        id: string
        shop_terminal: string
        shop_name: string
        payload_json: string
        checksum: string
        items: unknown[]
        total_cents: number
      }
    }) => {
      if (!args.order?.checksum || !args.order?.payload_json) {
        throw new Error('Cannot save unverified order')
      }
      const id = await db.insertCustomerHistory({
        id: `hist_${args.order.id}`,
        shop_terminal: args.order.shop_terminal,
        shop_name: args.order.shop_name,
        order_id: args.order.id,
        payload_json: args.order.payload_json,
        checksum: args.order.checksum,
        item_count: args.order.items.length,
        total: `$${(args.order.total_cents / 100).toFixed(2)}`,
        received_at: new Date().toISOString(),
      })
      return { ok: true, id }
    },

    'inventory.list': (args: { filter?: string }) => db.listProducts(args.filter),

    'inventory.upsert': async (args: { product: ProductRecord }) => {
      const errors: { sku?: string; price?: string; code?: string; name?: string } = {}
      const sku = args.product.sku?.trim().toUpperCase()
      if (!args.product.name || args.product.name.trim().length > 60) {
        errors.name = 'Enter a name between 1 and 60 characters.'
      }
      if (!/^\d+(\.\d{1,2})?$/.test(args.product.price?.trim() ?? '')) {
        errors.price = 'Enter a valid decimal price (for example, 4.50).'
      }
      if (!sku) errors.sku = 'Enter a SKU.'
      const existingSku = sku ? await db.findProductByBarcodeOrSku(sku) : null
      if (existingSku && existingSku.id !== args.product.id) {
        const nextNum = `${sku.replace(/-\d+$/, '')}-02`
        errors.sku = `SKU already in use — try ${nextNum}`
      }
      if (Object.keys(errors).length > 0) return { ok: false, errors }
      const priceCents = Math.round(Number(args.product.price) * 100)
      const saved = await db.upsertProduct({ ...args.product, sku, priceCents })
      return { ok: true, product: saved, errors: {} }
    },

    'inventory.check_code': async (args: { code: string; exclude_sku?: string }) => {
      if (!args.code.trim()) return { ok: true }
      const conflict = await db.findProductByCodeExcludingSku(args.code.trim(), args.exclude_sku)
      return conflict ? { ok: false, conflict: { sku: conflict.sku, name: conflict.name } } : { ok: true }
    },

    'inventory.suggest_sku': async (args: { name: string }) => {
      const prefix = (args.name.replace(/[^A-Za-z]/g, '').toUpperCase().slice(0, 3) || 'ITM').padEnd(3, 'X')
      return { sku: `${prefix}-01` }
    },

    'inventory.delete': async (args: { id: string }) => {
      if (await db.isProductReferencedInOrders(args.id)) {
        return {
          ok: false,
          reason: "This product is on past orders and can't be deleted. Edit it instead to set stock to 0.",
        }
      }
      await db.deleteProduct(args.id)
      return { ok: true }
    },

    'inventory.scan_resolve': async (args: { raw: string }) => {
      const hit = await db.findProductByBarcodeOrSku(args.raw.trim())
      return hit
        ? { ok: true, sku: hit.sku, name: hit.name, price: hit.price }
        : { ok: false, reason: 'New code ready to assign.' }
    },
  }
}
