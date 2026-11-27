export const collections: Record<string, unknown> = {
  "inventory": {
  "id": 2,
  "name": "inventory",
  "schema": {
    "fields": {
      "item_name": {
        "type": "string",
        "required": false,
        "uid": "inventory_item_name",
        "min_length": 1,
        "max_length": 60
      },
      "sku": {
        "type": "string",
        "required": false,
        "uid": "inventory_sku",
        "unique": true,
        "min_length": 2
      },
      "price": {
        "type": "number",
        "required": false,
        "uid": "inventory_price",
        "min": 0
      },
      "stock": {
        "type": "number",
        "required": false,
        "uid": "inventory_stock"
      },
      "category": {
        "type": "string",
        "required": false,
        "uid": "inventory_category"
      },
      "barcode": {
        "type": "string",
        "required": false,
        "sql_indexed": true,
        "uid": "inventory_barcode"
      },
      "description": {
        "type": "string",
        "required": false,
        "uid": "inventory_description"
      },
      "photo_url": {
        "type": "string",
        "required": false,
        "uid": "inventory_photo_url"
      }
    },
    "policies": {
      "read": "public",
      "create": "public",
      "update": "public",
      "delete": "public"
    },
    "lookup": {
      "field": "barcode",
      "secondary_field": "sku"
    },
    "references": [
      {
        "collection": "kiosk_order_lines",
        "field": "sku",
        "match": "sku",
        "restrict_delete": true,
        "message": "This product is on past orders and can't be deleted. Edit it instead to set stock to 0."
      }
    ]
  },
  "seed": [
    {
      "item_name": "Wagyu Smash Burger",
      "sku": "ACM-101",
      "price": 16.5,
      "price_display": "16.50",
      "stock": 42,
      "category": "Entrees"
    },
    {
      "item_name": "Truffle Parmesan Fries",
      "sku": "ACM-102",
      "price": 8.0,
      "price_display": "8.00",
      "stock": 65,
      "category": "Sides"
    },
    {
      "item_name": "Artisan Cold Brew",
      "sku": "ACM-204",
      "price": 5.5,
      "price_display": "5.50",
      "stock": 88,
      "category": "Beverages"
    },
    {
      "item_name": "Wood-Fired Margherita",
      "sku": "ACM-109",
      "price": 18.0,
      "price_display": "18.00",
      "stock": 29,
      "category": "Pizza"
    }
  ]
},

  "orders": {
  "id": 1,
  "name": "orders",
  "schema": {
    "fields": {
      "item_name": {
        "type": "string",
        "required": false,
        "uid": "orders_item_name"
      },
      "sku": {
        "type": "string",
        "required": false,
        "uid": "orders_sku"
      },
      "price": {
        "type": "number",
        "required": false,
        "uid": "orders_price"
      },
      "qty": {
        "type": "number",
        "required": false,
        "uid": "orders_qty"
      },
      "terminal_id": {
        "type": "string",
        "required": false,
        "uid": "orders_terminal_id"
      },
      "status": {
        "type": "string",
        "required": false,
        "uid": "orders_status"
      }
    },
    "policies": {
      "read": "public",
      "create": "public",
      "update": "public",
      "delete": "public"
    }
  },
  "seed": [
    {
      "item_name": "Wagyu Smash Burger",
      "sku": "ACM-101",
      "price": 16.5,
      "qty": 1,
      "terminal_id": "POS-TERM-01",
      "status": "PAID"
    },
    {
      "item_name": "Truffle Parmesan Fries",
      "sku": "ACM-102",
      "price": 8.0,
      "qty": 2,
      "terminal_id": "POS-TERM-01",
      "status": "OPEN"
    },
    {
      "item_name": "Artisan Cold Brew",
      "sku": "ACM-204",
      "price": 5.5,
      "qty": 1,
      "terminal_id": "POS-TERM-01",
      "status": "OPEN"
    }
  ]
},

  "cust_history": {
  "id": 5,
  "name": "cust_history",
  "schema": {
    "fields": {
      "shop_terminal": {
        "type": "string",
        "required": false,
        "uid": "cust_history_shop_terminal"
      },
      "shop_name": {
        "type": "string",
        "required": false,
        "uid": "cust_history_shop_name"
      },
      "order_id": {
        "type": "string",
        "required": false,
        "uid": "cust_history_order_id"
      },
      "payload_json": {
        "type": "string",
        "required": false,
        "uid": "cust_history_payload_json"
      },
      "checksum": {
        "type": "string",
        "required": false,
        "uid": "cust_history_checksum"
      },
      "item_count": {
        "type": "number",
        "required": false,
        "uid": "cust_history_item_count"
      },
      "total": {
        "type": "number",
        "required": false,
        "uid": "cust_history_total"
      },
      "received_at": {
        "type": "string",
        "required": false,
        "uid": "cust_history_received_at"
      }
    },
    "policies": {
      "read": "public",
      "create": "public",
      "update": "public",
      "delete": "public"
    }
  }
},
}
