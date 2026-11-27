/**
 * Collection equivalents of the friend's SQL migrations.
 *
 * `inventory.barcode` is a normal field, not an `ALTER TABLE`; `cust_history` is a
 * collection with the same columns. The engine derives indexes from `unique`/`sql_indexed`
 * and roll-ups from `aggregates`, so no SQL is needed.
 */

export const inventory = {
  name: 'inventory',
  schema: {
    fields: {
      sku: { type: 'string', required: true, unique: true },
      name: { type: 'string', required: true, max_length: 60 },
      price: { type: 'number', min: 0 },
      photo_url: { type: 'string' },
      code: { type: 'string' },
      barcode: { type: 'string', sql_indexed: true },
      description: { type: 'string', max_length: 240 },
      stock: { type: 'number', min: 0 },
    },
    policies: {
      read: 'public',
      create: 'public',
      update: 'public',
      delete: 'public',
    },
  },
  seed: [
    { sku: 'LAT-01', name: 'Latte', price: 4.5, code: '0123456789012', barcode: '0123456789012', stock: 24, photo_url: '' },
    { sku: 'TEA-01', name: 'Green Tea', price: 3.2, code: '0123456789029', barcode: '0123456789029', stock: 18, photo_url: '' },
    { sku: 'MUF-01', name: 'Blueberry Muffin', price: 2.8, code: '0123456789036', barcode: '0123456789036', stock: 8, photo_url: '' },
    { sku: 'WAT-01', name: 'Mineral Water', price: 1.5, code: '0123456789043', barcode: '0123456789043', stock: 40, photo_url: '' },
    { sku: 'CRO-01', name: 'Croissant', price: 3.6, code: '0123456789050', barcode: '0123456789050', stock: 2, photo_url: '' },
  ],
}

export const orderLines = {
  name: 'order_lines',
  schema: {
    fields: {
      order_id: { type: 'string', required: true },
      sku: { type: 'string', required: true },
      item_name: { type: 'string' },
      unit_price: { type: 'number', min: 0 },
      quantity: { type: 'number', min: 0 },
    },
    policies: {
      read: 'public',
      create: 'public',
      update: 'public',
      delete: 'public',
    },
    append: {
      parent: 'orders',
      parent_field: 'order_id',
      merge_key: 'sku',
      quantity_field: 'quantity',
      stock: { collection: 'inventory', match_field: 'sku', stock_field: 'stock' },
    },
  },
  seed: [],
}

export const orders = {
  name: 'orders',
  schema: {
    fields: {
      status: { type: 'string', options: ['draft', 'submitted', 'paid', 'void'] },
      total: { type: 'number', min: 0 },
      item_count: { type: 'number', min: 0 },
      shop_name: { type: 'string' },
      shop_terminal: { type: 'string' },
      checksum: { type: 'string' },
      payload_json: { type: 'string' },
    },
    policies: {
      read: 'public',
      create: 'public',
      update: 'public',
      delete: 'public',
    },
    aggregates: [
      {
        target: 'order_lines',
        foreign_field: 'order_id',
        outputs: { item_count: 'count()', line_count: 'count()', total: 'round2(sum(unit_price * quantity))' },
        editable_status: ['draft'],
      },
    ],
    canonical: {
      child: 'order_lines',
      child_foreign_field: 'order_id',
      child_fields: ['sku', 'item_name', 'unit_price', 'quantity'],
      child_computed: {
        line_total_display: 'round2(unit_price * quantity)',
        unit_price_display: 'unit_price',
        name_and_qty: 'item_name',
        total_display: 'total',
        subtotal_display: 'total',
      },
      child_json_key: 'lines_json',
      child_count_key: 'item_count',
    },
  },
  seed: [],
}

export const custHistory = {
  name: 'cust_history',
  schema: {
    fields: {
      shop_terminal: { type: 'string', required: true },
      shop_name: { type: 'string', required: true },
      order_id: { type: 'string', required: true },
      payload_json: { type: 'string', required: true },
      checksum: { type: 'string', required: true },
      item_count: { type: 'number', min: 0 },
      total: { type: 'string' },
      received_at: { type: 'string' },
    },
  },
  seed: [],
}

export const collections = [inventory, orders, orderLines, custHistory]
