import { DivContainer, DivCustom, DivGrid, DivImage, DivInput, DivText } from '@divkitframework/jsonbuilder'
import type { DivNode } from '../../core/kit/div.ts'

export const cards: Record<string, DivNode> = {
  "local_db": new DivContainer({
    "orientation": "vertical",
    "background_color": "#071221",
    "corner_radius": 14,
    "paddings": {
      "left": 14,
      "right": 14,
      "top": 14,
      "bottom": 14,
    },
    "border": {
      "corner_radius": 14,
      "stroke": {
        "color": "#0284C7",
        "width": 2,
      },
    },
    "items": [
      new DivContainer({
        "orientation": "vertical",
        "background_color": "#0F2744",
        "corner_radius": 12,
        "paddings": {
          "left": 14,
          "right": 14,
          "top": 12,
          "bottom": 12,
        },
        "border": {
          "corner_radius": 12,
          "stroke": {
            "color": "#1E40AF",
            "width": 1,
          },
        },
        "items": [
          new DivContainer({
            "orientation": "horizontal",
            "content_alignment_vertical": "center",
            "items": [
              new DivText({
                "text": "🗄️  SQLITE DATA PLANE  —  local-db://op/*",
                "font_size": 15,
                "font_weight": "bold",
                "text_color": "#38BDF8",
                "weight": 1,
              } as any),
            ],
          } as any),
          new DivText({
            "text": "Workspace: acme_pos  •  Schema v4  •  PRAGMA foreign_keys = ON",
            "font_size": 11,
            "text_color": "#64748B",
            "font_family": "monospace",
            "margins": {
              "top": 4,
            },
          } as any),
          new DivText({
            "text": "Orders: @{user.order_count}  •  Catalog: @{user.catalog_count}  •  Last Row: #@{user.last_order_id}",
            "font_size": 12,
            "text_color": "#94A3B8",
            "font_family": "monospace",
            "margins": {
              "top": 4,
            },
          } as any),
        ],
      } as any),
      new DivContainer({
        "orientation": "horizontal",
        "item_spacing": 8,
        "margins": {
          "top": 10,
        },
        "items": [
          new DivContainer({
            "orientation": "vertical",
            "weight": 1,
            "background_color": "#0C1F3A",
            "corner_radius": 10,
            "paddings": {
              "left": 10,
              "right": 10,
              "top": 10,
              "bottom": 10,
            },
            "border": {
              "corner_radius": 10,
              "stroke": {
                "color": "#1E3A5F",
                "width": 1,
              },
            },
            "content_alignment_horizontal": "center",
            "items": [
              new DivText({
                "text": "@{user.order_count}",
                "font_size": 24,
                "font_weight": "bold",
                "text_color": "#38BDF8",
                "text_alignment_horizontal": "center",
              } as any),
              new DivText({
                "text": "ORDERS",
                "font_size": 10,
                "text_color": "#64748B",
                "font_weight": "bold",
                "text_alignment_horizontal": "center",
                "margins": {
                  "top": 2,
                },
              } as any),
            ],
          } as any),
          new DivContainer({
            "orientation": "vertical",
            "weight": 1,
            "background_color": "#0C1F3A",
            "corner_radius": 10,
            "paddings": {
              "left": 10,
              "right": 10,
              "top": 10,
              "bottom": 10,
            },
            "border": {
              "corner_radius": 10,
              "stroke": {
                "color": "#064E3B",
                "width": 1,
              },
            },
            "content_alignment_horizontal": "center",
            "items": [
              new DivText({
                "text": "@{user.catalog_count}",
                "font_size": 24,
                "font_weight": "bold",
                "text_color": "#34D399",
                "text_alignment_horizontal": "center",
              } as any),
              new DivText({
                "text": "SKUs IN DB",
                "font_size": 10,
                "text_color": "#64748B",
                "font_weight": "bold",
                "text_alignment_horizontal": "center",
                "margins": {
                  "top": 2,
                },
              } as any),
            ],
          } as any),
          new DivContainer({
            "orientation": "vertical",
            "weight": 1,
            "background_color": "#0C1F3A",
            "corner_radius": 10,
            "paddings": {
              "left": 10,
              "right": 10,
              "top": 10,
              "bottom": 10,
            },
            "border": {
              "corner_radius": 10,
              "stroke": {
                "color": "#78350F",
                "width": 1,
              },
            },
            "content_alignment_horizontal": "center",
            "items": [
              new DivText({
                "text": "#@{user.last_order_id}",
                "font_size": 24,
                "font_weight": "bold",
                "text_color": "#FBBF24",
                "text_alignment_horizontal": "center",
              } as any),
              new DivText({
                "text": "LAST ROW #",
                "font_size": 10,
                "text_color": "#64748B",
                "font_weight": "bold",
                "text_alignment_horizontal": "center",
                "margins": {
                  "top": 2,
                },
              } as any),
            ],
          } as any),
        ],
      } as any),
      new DivText({
        "text": "🛒  QUICK-SALE CATALOG  (db.insert → orders)",
        "font_size": 11,
        "font_weight": "bold",
        "text_color": "#94A3B8",
        "margins": {
          "top": 14,
          "bottom": 6,
        },
      } as any),
      new DivGrid({
        "column_count": 2,
        "item_spacing": 8,
        "line_spacing": 8,
        "items": [
          {
            "type": "button",
            "text": "Wagyu Smash Burger\n$16.50  •  ACM-101",
            "font_size": 13,
            "font_weight": "bold",
            "text_color": "#F0F9FF",
            "background_color": "#0C4A6E",
            "corner_radius": 10,
            "paddings": {
              "left": 12,
              "right": 12,
              "top": 12,
              "bottom": 12,
            },
            "actions": [
              {
                "type": "action",
                "url": "local-db://op/db.insert",
                "payload": {
                  "table": "orders",
                  "values": {
                    "item_name": "Wagyu Smash Burger",
                    "sku": "ACM-101",
                    "price": "16.50",
                    "qty": "1",
                    "terminal_id": "POS-TERM-01",
                    "status": "OPEN",
                  },
                  "output": {
                    "id": "user.last_order_id",
                  },
                },
              },
              {
                "type": "action",
                "url": "local-db://op/db.query",
                "payload": {
                  "table": "orders",
                  "order_by": "id",
                  "limit": 50,
                  "output": {
                    "_json": "user.orders_json",
                  },
                  "json_list_var": "user.orders_json",
                },
              },
              {
                "type": "action",
                "url": "local-db://op/db.count",
                "payload": {
                  "table": "orders",
                  "output": {
                    "count": "user.order_count",
                  },
                },
              },
            ],
          },
          {
            "type": "button",
            "text": "Truffle Parmesan Fries\n$8.00  •  ACM-102",
            "font_size": 13,
            "font_weight": "bold",
            "text_color": "#F0FFF4",
            "background_color": "#064E3B",
            "corner_radius": 10,
            "paddings": {
              "left": 12,
              "right": 12,
              "top": 12,
              "bottom": 12,
            },
            "actions": [
              {
                "type": "action",
                "url": "local-db://op/db.insert",
                "payload": {
                  "table": "orders",
                  "values": {
                    "item_name": "Truffle Parmesan Fries",
                    "sku": "ACM-102",
                    "price": "8.00",
                    "qty": "1",
                    "terminal_id": "POS-TERM-01",
                    "status": "OPEN",
                  },
                  "output": {
                    "id": "user.last_order_id",
                  },
                },
              },
              {
                "type": "action",
                "url": "local-db://op/db.query",
                "payload": {
                  "table": "orders",
                  "order_by": "id",
                  "limit": 50,
                  "output": {
                    "_json": "user.orders_json",
                  },
                  "json_list_var": "user.orders_json",
                },
              },
              {
                "type": "action",
                "url": "local-db://op/db.count",
                "payload": {
                  "table": "orders",
                  "output": {
                    "count": "user.order_count",
                  },
                },
              },
            ],
          },
          {
            "type": "button",
            "text": "Artisan Cold Brew\n$5.50  •  ACM-204",
            "font_size": 13,
            "font_weight": "bold",
            "text_color": "#EEF2FF",
            "background_color": "#312E81",
            "corner_radius": 10,
            "paddings": {
              "left": 12,
              "right": 12,
              "top": 12,
              "bottom": 12,
            },
            "actions": [
              {
                "type": "action",
                "url": "local-db://op/db.insert",
                "payload": {
                  "table": "orders",
                  "values": {
                    "item_name": "Artisan Cold Brew",
                    "sku": "ACM-204",
                    "price": "5.50",
                    "qty": "1",
                    "terminal_id": "POS-TERM-01",
                    "status": "OPEN",
                  },
                  "output": {
                    "id": "user.last_order_id",
                  },
                },
              },
              {
                "type": "action",
                "url": "local-db://op/db.query",
                "payload": {
                  "table": "orders",
                  "order_by": "id",
                  "limit": 50,
                  "output": {
                    "_json": "user.orders_json",
                  },
                  "json_list_var": "user.orders_json",
                },
              },
              {
                "type": "action",
                "url": "local-db://op/db.count",
                "payload": {
                  "table": "orders",
                  "output": {
                    "count": "user.order_count",
                  },
                },
              },
            ],
          },
          {
            "type": "button",
            "text": "Wood-Fired Margherita\n$18.00  •  ACM-109",
            "font_size": 13,
            "font_weight": "bold",
            "text_color": "#FFFBEB",
            "background_color": "#78350F",
            "corner_radius": 10,
            "paddings": {
              "left": 12,
              "right": 12,
              "top": 12,
              "bottom": 12,
            },
            "actions": [
              {
                "type": "action",
                "url": "local-db://op/db.insert",
                "payload": {
                  "table": "orders",
                  "values": {
                    "item_name": "Wood-Fired Margherita",
                    "sku": "ACM-109",
                    "price": "18.00",
                    "qty": "1",
                    "terminal_id": "POS-TERM-01",
                    "status": "OPEN",
                  },
                  "output": {
                    "id": "user.last_order_id",
                  },
                },
              },
              {
                "type": "action",
                "url": "local-db://op/db.query",
                "payload": {
                  "table": "orders",
                  "order_by": "id",
                  "limit": 50,
                  "output": {
                    "_json": "user.orders_json",
                  },
                  "json_list_var": "user.orders_json",
                },
              },
              {
                "type": "action",
                "url": "local-db://op/db.count",
                "payload": {
                  "table": "orders",
                  "output": {
                    "count": "user.order_count",
                  },
                },
              },
            ],
          },
        ],
      } as any),
      new DivText({
        "text": "CUSTOM LINE ITEM",
        "font_size": 11,
        "font_weight": "bold",
        "text_color": "#94A3B8",
        "margins": {
          "top": 14,
          "bottom": 6,
        },
      } as any),
      new DivContainer({
        "orientation": "vertical",
        "background_color": "#0F172A",
        "corner_radius": 10,
        "paddings": {
          "left": 12,
          "right": 12,
          "top": 12,
          "bottom": 12,
        },
        "border": {
          "corner_radius": 10,
          "stroke": {
            "color": "#1E293B",
            "width": 1,
          },
        },
        "item_spacing": 8,
        "items": [
          new DivContainer({
            "orientation": "horizontal",
            "item_spacing": 8,
            "items": [
              new DivInput({
                "weight": 1,
                "text_variable": "user.custom_item_name",
                "hint_text": "Item name…",
                "hint_text_color": "#475569",
                "text_color": "#F8FAFC",
                "background_color": "#1E293B",
                "corner_radius": 8,
                "paddings": {
                  "left": 10,
                  "right": 10,
                  "top": 10,
                  "bottom": 10,
                },
                "font_size": 13,
              } as any),
              new DivInput({
                "weight": 0.5,
                "text_variable": "user.custom_item_price",
                "hint_text": "Price ($)",
                "hint_text_color": "#475569",
                "text_color": "#F8FAFC",
                "background_color": "#1E293B",
                "corner_radius": 8,
                "paddings": {
                  "left": 10,
                  "right": 10,
                  "top": 10,
                  "bottom": 10,
                },
                "font_size": 13,
                "keyboard_type": "number",
              } as any),
            ],
          } as any),
          {
            "type": "button",
            "text": "+ Insert @{user.custom_item_name} at $@{user.custom_item_price}",
            "icon": "add",
            "font_size": 13,
            "font_weight": "bold",
            "text_color": "#F0F9FF",
            "background_color": "#0369A1",
            "corner_radius": 8,
            "paddings": {
              "left": 12,
              "right": 12,
              "top": 10,
              "bottom": 10,
            },
            "actions": [
              {
                "type": "action",
                "url": "local-db://op/db.insert",
                "payload": {
                  "table": "orders",
                  "values": {
                    "item_name": "@{user.custom_item_name}",
                    "sku": "CUSTOM-SKU",
                    "price": "@{user.custom_item_price}",
                    "qty": "1",
                    "terminal_id": "POS-TERM-01",
                    "status": "OPEN",
                  },
                  "output": {
                    "id": "user.last_order_id",
                  },
                },
              },
              {
                "type": "action",
                "url": "local-db://op/db.query",
                "payload": {
                  "table": "orders",
                  "order_by": "id",
                  "limit": 50,
                  "output": {
                    "_json": "user.orders_json",
                  },
                  "json_list_var": "user.orders_json",
                },
              },
              {
                "type": "action",
                "url": "local-db://op/db.count",
                "payload": {
                  "table": "orders",
                  "output": {
                    "count": "user.order_count",
                  },
                },
              },
            ],
          },
        ],
      } as any),
      new DivText({
        "text": "LIVE ORDER LEDGER",
        "font_size": 11,
        "font_weight": "bold",
        "text_color": "#94A3B8",
        "margins": {
          "top": 14,
          "bottom": 6,
        },
      } as any),
      new DivContainer({
        "orientation": "horizontal",
        "item_spacing": 8,
        "margins": {
          "bottom": 8,
        },
        "items": [
          {
            "type": "button",
            "weight": 1,
            "text": "Refresh",
            "icon": "refresh",
            "font_size": 13,
            "font_weight": "bold",
            "text_color": "#BAE6FD",
            "background_color": "#1E3A5F",
            "corner_radius": 8,
            "paddings": {
              "left": 12,
              "right": 12,
              "top": 10,
              "bottom": 10,
            },
            "actions": [
              {
                "type": "action",
                "url": "local-db://op/db.query",
                "payload": {
                  "table": "orders",
                  "order_by": "id",
                  "limit": 50,
                  "output": {
                    "_json": "user.orders_json",
                  },
                  "json_list_var": "user.orders_json",
                },
              },
              {
                "type": "action",
                "url": "local-db://op/db.count",
                "payload": {
                  "table": "orders",
                  "output": {
                    "count": "user.order_count",
                  },
                },
              },
            ],
          },
          {
            "type": "button",
            "weight": 1,
            "text": "Clear All",
            "icon": "delete",
            "font_size": 13,
            "font_weight": "bold",
            "text_color": "#FCA5A5",
            "background_color": "#7F1D1D",
            "corner_radius": 8,
            "paddings": {
              "left": 12,
              "right": 12,
              "top": 10,
              "bottom": 10,
            },
            "actions": [
              {
                "type": "action",
                "url": "local-db://op/db.delete",
                "payload": {
                  "table": "orders",
                  "where": [],
                  "output": {
                    "count": "user.order_count",
                  },
                },
              },
              {
                "type": "action",
                "url": "local-db://op/db.query",
                "payload": {
                  "table": "orders",
                  "order_by": "id",
                  "limit": 50,
                  "output": {
                    "_json": "user.orders_json",
                  },
                  "json_list_var": "user.orders_json",
                },
              },
              {
                "type": "action",
                "url": "local-db://op/db.count",
                "payload": {
                  "table": "orders",
                  "output": {
                    "count": "user.order_count",
                  },
                },
              },
            ],
          },
        ],
      } as any),
      {
        "type": "json_list",
        "data_var": "user.orders_json",
        "empty_text": "No active orders — tap a catalog item above to insert one",
        "item_spacing": 6,
        "row_card": new DivContainer({
          "orientation": "horizontal",
          "background_color": "#0F172A",
          "corner_radius": 10,
          "paddings": {
            "left": 12,
            "right": 8,
            "top": 10,
            "bottom": 10,
          },
          "border": {
            "corner_radius": 10,
            "stroke": {
              "color": "#1E3A5F",
              "width": 1,
            },
          },
          "content_alignment_vertical": "center",
          "item_spacing": 8,
          "items": [
            new DivContainer({
              "orientation": "vertical",
              "weight": 1,
              "items": [
                new DivText({
                  "text": "#@{item.id}  @{item.item_name}",
                  "font_size": 14,
                  "font_weight": "bold",
                  "text_color": "#F8FAFC",
                } as any),
                new DivText({
                  "text": "SKU: @{item.sku}  •  $@{item.price} × @{item.qty}  •  @{item.status}",
                  "font_size": 12,
                  "text_color": "#94A3B8",
                  "font_family": "monospace",
                  "margins": {
                    "top": 2,
                  },
                } as any),
              ],
            } as any),
            {
              "type": "button",
              "weight": 0.38,
              "text": "Void",
              "icon": "delete",
              "font_size": 12,
              "font_weight": "bold",
              "text_color": "#FCA5A5",
              "background_color": "#450A0A",
              "corner_radius": 8,
              "paddings": {
                "left": 10,
                "right": 10,
                "top": 8,
                "bottom": 8,
              },
              "actions": [
                {
                  "type": "action",
                  "url": "local-db://op/db.delete",
                  "payload": {
                    "table": "orders",
                    "where": [
                      {
                        "field": "id",
                        "op": "eq",
                        "value": "@{item.id}",
                      },
                    ],
                    "output": {
                      "count": "user.last_deleted_count",
                    },
                  },
                },
                {
                  "type": "action",
                  "url": "local-db://op/db.query",
                  "payload": {
                    "table": "orders",
                    "order_by": "id",
                    "limit": 50,
                    "output": {
                      "_json": "user.orders_json",
                    },
                    "json_list_var": "user.orders_json",
                  },
                },
                {
                  "type": "action",
                  "url": "local-db://op/db.count",
                  "payload": {
                    "table": "orders",
                    "output": {
                      "count": "user.order_count",
                    },
                  },
                },
              ],
            },
          ],
        } as any),
      },
      new DivText({
        "text": "🔍  SCHEMA INTROSPECTION",
        "font_size": 11,
        "font_weight": "bold",
        "text_color": "#94A3B8",
        "margins": {
          "top": 14,
          "bottom": 6,
        },
      } as any),
      new DivContainer({
        "orientation": "horizontal",
        "item_spacing": 8,
        "items": [
          {
            "type": "button",
            "weight": 1,
            "text": "List Collections",
            "icon": "stock",
            "font_size": 13,
            "font_weight": "bold",
            "text_color": "#BAE6FD",
            "background_color": "#1C3A5E",
            "corner_radius": 8,
            "paddings": {
              "left": 12,
              "right": 12,
              "top": 10,
              "bottom": 10,
            },
            "action": {
              "type": "action",
              "url": "local-db://op/db.list_collections",
              "payload": {
                "output": {
                  "_json": "user.orders_json",
                },
              },
            },
          },
          {
            "type": "button",
            "weight": 1,
            "text": "Count Orders",
            "icon": "numbers",
            "font_size": 13,
            "font_weight": "bold",
            "text_color": "#C4B5FD",
            "background_color": "#1C2E5E",
            "corner_radius": 8,
            "paddings": {
              "left": 12,
              "right": 12,
              "top": 10,
              "bottom": 10,
            },
            "action": {
              "type": "action",
              "url": "local-db://op/db.count",
              "payload": {
                "table": "orders",
                "output": {
                  "count": "user.order_count",
                },
              },
            },
          },
        ],
      } as any),
    ],
  } as any),
  "scanner": new DivContainer({
    "orientation": "vertical",
    "background_color": "#0C1F14",
    "corner_radius": 12,
    "item_spacing": 10,
    "paddings": {
      "left": 12,
      "right": 12,
      "top": 10,
      "bottom": 10,
    },
    "border": {
      "corner_radius": 12,
      "stroke": {
        "color": "#047857",
        "width": 2,
      },
    },
    "items": [
      new DivContainer({
        "background_color": "#0C1F14",
        "corner_radius": 10,
        "paddings": {
          "left": 12,
          "right": 12,
          "top": 10,
          "bottom": 10,
        },
        "border": {
          "corner_radius": 10,
          "stroke": {
            "color": "#047857",
            "width": 1,
          },
        },
        "items": [
          new DivText({
            "text": "📷 SCANNER — ML Kit + ZXing Live Camera (tools://scan)",
            "font_size": 13,
            "font_weight": "bold",
            "text_color": "#047857",
          } as any),
          new DivText({
            "text": "Point the camera at a barcode or QR code, or decode an image file. Results appear below.",
            "font_size": 11,
            "text_color": "#6EE7B7",
            "margins": {
              "top": 4,
            },
          } as any),
        ],
      } as any),
      new DivContainer({
        "background_color": "#0A1A0F",
        "corner_radius": 10,
        "paddings": {
          "left": 12,
          "right": 12,
          "top": 10,
          "bottom": 10,
        },
        "border": {
          "corner_radius": 10,
          "stroke": {
            "color": "#064E3B",
            "width": 1,
          },
        },
        "item_spacing": 4,
        "items": [
          new DivText({
            "text": "CODE  @{user.scanned_code}",
            "font_size": 14,
            "font_weight": "bold",
            "text_color": "#34D399",
            "font_family": "monospace",
          } as any),
          new DivText({
            "text": "FORMAT  @{user.scanned_format}  •  TYPE  @{user.scanned_type}",
            "font_size": 12,
            "text_color": "#6EE7B7",
            "font_family": "monospace",
          } as any),
          new DivText({
            "text": "STATUS  @{user.scan_status}",
            "font_size": 11,
            "text_color": "#A7F3D0",
            "font_family": "monospace",
          } as any),
          new DivText({
            "text": "OCR  @{user.ocr_text}",
            "font_size": 11,
            "text_color": "#A7F3D0",
            "font_family": "monospace",
          } as any),
          new DivText({
            "text": "CONF  @{user.ocr_confidence}  •  BLOCKS  @{user.ocr_block_count}",
            "font_size": 11,
            "text_color": "#6EE7B7",
            "font_family": "monospace",
          } as any),
        ],
      } as any),
      new DivImage({
        "image_url": "@{user.scan_image_uri}",
        "preview_height": 180,
        "corner_radius": 10,
        "content_description": "Last scanned image",
      } as any),
      new DivText({
        "text": "LIVE — opens viewfinder and decodes what you point at",
        "font_size": 11,
        "font_weight": "bold",
        "text_color": "#A7F3D0",
      } as any),
      new DivContainer({
        "orientation": "horizontal",
        "item_spacing": 8,
        "items": [
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_scan_live_barcode",
                "text": "Barcode",
                "font_size": 12,
                "subtitle": "Camera",
                "icon": "scanner",
                "background_color": "#047857",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_scan_live_barcode",
                  "url": "tools://scan/scan",
                  "payload": {
                    "operation": "scan",
                    "options": {
                      "target_format": "barcode",
                      "camera": "true",
                    },
                    "output": {
                      "raw_value": "user.scanned_code",
                      "format": "user.scanned_format",
                      "type": "user.scanned_type",
                      "status": "user.scan_status",
                    },
                  },
                },
              },
            ],
          } as any),
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_scan_live_qr",
                "text": "QR code",
                "font_size": 12,
                "subtitle": "Camera",
                "icon": "qr_code",
                "background_color": "#0D9488",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_scan_live_qr",
                  "url": "tools://scan/scan",
                  "payload": {
                    "operation": "scan",
                    "options": {
                      "target_format": "qr",
                      "camera": "true",
                    },
                    "output": {
                      "raw_value": "user.scanned_code",
                      "format": "user.scanned_format",
                      "type": "user.scanned_type",
                      "status": "user.scan_status",
                    },
                  },
                },
              },
            ],
          } as any),
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_scan_live_both",
                "text": "Both",
                "font_size": 12,
                "subtitle": "Auto",
                "icon": "scanner",
                "background_color": "#065F46",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_scan_live_both",
                  "url": "tools://scan/scan",
                  "payload": {
                    "operation": "scan",
                    "options": {
                      "target_format": "any",
                      "camera": "true",
                    },
                    "output": {
                      "raw_value": "user.scanned_code",
                      "format": "user.scanned_format",
                      "type": "user.scanned_type",
                      "status": "user.scan_status",
                    },
                  },
                },
              },
            ],
          } as any),
        ],
      } as any),
      new DivText({
        "text": "OCR — ML Kit text recognition",
        "font_size": 11,
        "font_weight": "bold",
        "text_color": "#A7F3D0",
      } as any),
      new DivContainer({
        "orientation": "horizontal",
        "item_spacing": 8,
        "items": [
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_scan_ocr_camera",
                "text": "Camera OCR",
                "subtitle": "Live ML Kit text scan",
                "icon": "search",
                "background_color": "#0369A1",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_scan_ocr_camera",
                  "url": "tools://scan/ocr",
                  "payload": {
                    "operation": "ocr",
                    "options": {
                      "camera": "true",
                    },
                    "output": {
                      "text": "user.ocr_text",
                      "confidence": "user.ocr_confidence",
                      "block_count": "user.ocr_block_count",
                      "source_image": "user.scan_image_uri",
                    },
                  },
                },
              },
            ],
          } as any),
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_scan_ocr_photo",
                "text": "Photo OCR",
                "subtitle": "Pick from gallery",
                "icon": "search",
                "background_color": "#1D4ED8",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_scan_ocr_photo",
                  "url": "tools://scan/ocr",
                  "payload": {
                    "operation": "ocr",
                    "options": {
                      "camera": "false",
                    },
                    "output": {
                      "text": "user.ocr_text",
                      "confidence": "user.ocr_confidence",
                      "block_count": "user.ocr_block_count",
                      "source_image": "user.scan_image_uri",
                    },
                  },
                },
              },
            ],
          } as any),
        ],
      } as any),
      new DivContainer({
        "background_color": "#071B10",
        "corner_radius": 10,
        "paddings": {
          "left": 12,
          "right": 12,
          "top": 10,
          "bottom": 10,
        },
        "item_spacing": 8,
        "border": {
          "corner_radius": 10,
          "stroke": {
            "color": "#064E3B",
            "width": 1,
          },
        },
        "items": [
          new DivText({
            "text": "DECODE FROM FILE (no camera needed)",
            "font_size": 11,
            "font_weight": "bold",
            "text_color": "#6EE7B7",
          } as any),
          new DivContainer({
            "orientation": "horizontal",
            "item_spacing": 8,
            "items": [
              new DivContainer({
                "weight": 1,
                "items": [
                  {
                    "type": "button",
                    "id": "tool_scan_scan",
                    "text": "Sample barcode",
                    "font_size": 12,
                    "subtitle": "EAN-13 file",
                    "icon": "barcode",
                    "background_color": "#064E3B",
                    "text_color": "#FFFFFF",
                    "corner_radius": 10,
                    "action": {
                      "log_id": "exec_tool_scan_scan",
                      "url": "tools://scan/scan",
                      "payload": {
                        "operation": "scan",
                        "options": {
                          "target_format": "barcode",
                          "uri": "${demoBarcodeUri}",
                        },
                        "output": {
                          "raw_value": "user.scanned_code",
                          "format": "user.scanned_format",
                          "type": "user.scanned_type",
                          "status": "user.scan_status",
                        },
                      },
                    },
                  },
                ],
              } as any),
              new DivContainer({
                "weight": 1,
                "items": [
                  {
                    "type": "button",
                    "id": "tool_scan_scan_qr",
                    "text": "Sample QR",
                    "font_size": 12,
                    "subtitle": "QR file",
                    "icon": "qr_code",
                    "background_color": "#065F46",
                    "text_color": "#FFFFFF",
                    "corner_radius": 10,
                    "action": {
                      "log_id": "exec_tool_scan_scan_qr",
                      "url": "tools://scan/scan",
                      "payload": {
                        "operation": "scan",
                        "options": {
                          "target_format": "qr",
                          "uri": "${demoQrUri}",
                        },
                        "output": {
                          "raw_value": "user.scanned_code",
                          "format": "user.scanned_format",
                          "type": "user.scanned_type",
                          "status": "user.scan_status",
                        },
                      },
                    },
                  },
                ],
              } as any),
            ],
          } as any),
        ],
      } as any),
    ],
  } as any),
  "code_generate": new DivContainer({
    "orientation": "vertical",
    "background_color": "#1A1200",
    "corner_radius": 14,
    "paddings": {
      "left": 14,
      "right": 14,
      "top": 12,
      "bottom": 12,
    },
    "border": {
      "corner_radius": 14,
      "stroke": {
        "color": "#F59E0B",
        "width": 2,
      },
    },
    "items": [
      new DivText({
        "text": "🔳 CODE GENERATOR — ZXing QR & 1D Barcodes (tools://code-generate)",
        "font_size": 12,
        "font_weight": "bold",
        "text_color": "#FCD34D",
        "margins": {
          "bottom": 4,
        },
      } as any),
      new DivText({
        "text": "Generates scannable PNGs saved to the tenant media sandbox. Generated codes can be scanned immediately with the Scanner card.",
        "font_size": 11,
        "text_color": "#FEF3C7",
        "margins": {
          "bottom": 10,
        },
      } as any),
      new DivContainer({
        "orientation": "vertical",
        "background_color": "#0D0900",
        "corner_radius": 8,
        "paddings": {
          "left": 10,
          "right": 10,
          "top": 8,
          "bottom": 8,
        },
        "margins": {
          "bottom": 10,
        },
        "items": [
          new DivText({
            "text": "Format: @{user.code_format}",
            "font_size": 11,
            "text_color": "#FDE68A",
            "font_family": "monospace",
          } as any),
          new DivText({
            "text": "Kind:   @{user.code_kind}",
            "font_size": 11,
            "text_color": "#FDE68A",
            "font_family": "monospace",
          } as any),
          new DivText({
            "text": "Content: @{user.code_content}",
            "font_size": 11,
            "text_color": "#FDE68A",
            "font_family": "monospace",
          } as any),
          new DivText({
            "text": "Code id: @{user.code_id}",
            "font_size": 11,
            "text_color": "#FDE68A",
            "font_family": "monospace",
          } as any),
          new DivText({
            "text": "Image: @{if(user.code_image_uri == '', 'not generated yet — tap Generate', user.code_image_uri)}",
            "font_size": 10,
            "text_color": "#FDE68A",
            "font_family": "monospace",
          } as any),
        ],
      } as any),
      new DivContainer({
        "orientation": "vertical",
        "background_color": "#FAFAFA",
        "corner_radius": 10,
        "paddings": {
          "left": 6,
          "right": 6,
          "top": 6,
          "bottom": 6,
        },
        "margins": {
          "bottom": 10,
        },
        "items": [
          new DivImage({
            "image_url": "@{user.code_image_uri}",
            "preview_height": 240,
            "corner_radius": 10,
            "content_description": "Generated code image",
          } as any),
        ],
      } as any),
      new DivContainer({
        "orientation": "horizontal",
        "item_spacing": 8,
        "margins": {
          "bottom": 8,
        },
        "items": [
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_code-generate_generate",
                "text": "Generate Order QR",
                "subtitle": "tools://code-generate/generate (QR_CODE)",
                "icon": "qr_code",
                "background_color": "#D97706",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_code-generate_generate",
                  "url": "tools://code-generate/generate",
                  "payload": {
                    "operation": "generate",
                    "options": {
                      "content": "apexpos://order/pickup?terminal=POS-TERM-01",
                      "format": "qr",
                      "width": "512",
                      "ec_level": "M",
                      "encoding": "uri",
                    },
                    "output": {
                      "uri": "user.code_image_uri",
                      "format": "user.code_format",
                      "kind": "user.code_kind",
                      "content": "user.code_content",
                      "code_id": "user.code_id",
                    },
                  },
                },
              },
            ],
          } as any),
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_code-generate_barcode",
                "text": "Generate SKU Barcode",
                "subtitle": "tools://code-generate/barcode (EAN_13)",
                "icon": "barcode",
                "background_color": "#B45309",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_code-generate_barcode",
                  "url": "tools://code-generate/barcode",
                  "payload": {
                    "operation": "barcode",
                    "options": {
                      "content": "9780201633610",
                      "format": "EAN_13",
                      "width": "400",
                      "height": "160",
                    },
                    "output": {
                      "uri": "user.code_image_uri",
                      "format": "user.code_format",
                      "kind": "user.code_kind",
                      "content": "user.code_content",
                      "code_id": "user.code_id",
                    },
                  },
                },
              },
            ],
          } as any),
        ],
      } as any),
      new DivContainer({
        "orientation": "horizontal",
        "item_spacing": 8,
        "margins": {
          "bottom": 12,
        },
        "items": [
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_code-generate_table_qr",
                "text": "Generate Table QR",
                "subtitle": "APEX://table/T12 • ec_level H",
                "icon": "qr_code",
                "background_color": "#92400E",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_code-generate_table_qr",
                  "url": "tools://code-generate/generate",
                  "payload": {
                    "operation": "generate",
                    "options": {
                      "content": "APEX://table/T12?guests=4&server=Maria",
                      "format": "qr",
                      "width": "512",
                      "ec_level": "H",
                    },
                    "output": {
                      "uri": "user.code_image_uri",
                      "format": "user.code_format",
                      "kind": "user.code_kind",
                      "content": "user.code_content",
                      "code_id": "user.code_id",
                    },
                  },
                },
              },
            ],
          } as any),
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_code-generate_receipt_barcode",
                "text": "Receipt Barcode",
                "subtitle": "CODE_128 • 600×180",
                "icon": "barcode",
                "background_color": "#78350F",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_code-generate_receipt_barcode",
                  "url": "tools://code-generate/barcode",
                  "payload": {
                    "operation": "barcode",
                    "options": {
                      "content": "REC-20261122-POS-TERM-01",
                      "format": "CODE_128",
                      "width": "600",
                      "height": "180",
                    },
                    "output": {
                      "uri": "user.code_image_uri",
                      "format": "user.code_format",
                      "kind": "user.code_kind",
                      "content": "user.code_content",
                      "code_id": "user.code_id",
                    },
                  },
                },
              },
            ],
          } as any),
        ],
      } as any),
      new DivContainer({
        "orientation": "vertical",
        "background_color": "#002020",
        "corner_radius": 8,
        "paddings": {
          "left": 10,
          "right": 10,
          "top": 8,
          "bottom": 8,
        },
        "items": [
          new DivText({
            "text": "▶  SCAN YOUR GENERATED CODE",
            "font_size": 11,
            "font_weight": "bold",
            "text_color": "#22D3EE",
          } as any),
          new DivText({
            "text": "Switch to Scanner card and point the camera at the code above",
            "font_size": 11,
            "text_color": "#67E8F9",
          } as any),
        ],
      } as any),
    ],
  } as any),
  "codec": new DivContainer({
    "orientation": "vertical",
    "background_color": "#071221",
    "corner_radius": 14,
    "paddings": {
      "left": 14,
      "right": 14,
      "top": 14,
      "bottom": 14,
    },
    "item_spacing": 10,
    "items": [
      new DivText({
        "text": "🔐 GENERIC CODEC  —  tools://codec",
        "font_size": 15,
        "font_weight": "bold",
        "text_color": "#38BDF8",
      } as any),
      new DivText({
        "text": "Base64, Base45, JSON validate/extract/stringify/chunk/assemble. Chunk envelopes are caller-sized and capped by max_chunks.",
        "font_size": 11,
        "text_color": "#94A3B8",
      } as any),
      new DivContainer({
        "orientation": "horizontal",
        "item_spacing": 8,
        "items": [
          {
            "type": "button",
            "text": "Validate JSON",
            "background_color": "#0284C7",
            "text_color": "#F0F9FF",
            "corner_radius": 8,
            "actions": [
              {
                "log_id": "codec_validate",
                "url": "tools://codec/json/validate",
                "payload": {
                  "json": "{\"order_id\":\"42\",\"lines\":[]}",
                },
              },
            ],
          },
          {
            "type": "button",
            "text": "Chunk JSON",
            "background_color": "#059669",
            "text_color": "#ECFDF5",
            "corner_radius": 8,
            "actions": [
              {
                "log_id": "codec_chunk",
                "url": "tools://codec/json/chunk",
                "payload": {
                  "json": "{\"order_id\":\"42\",\"note\":\"hello\"}",
                  "encoding": "base45",
                  "max_bytes": 500,
                  "max_chunks": 32,
                },
              },
            ],
          },
        ],
      } as any),
    ],
  } as any),
  "camera": new DivContainer({
    "orientation": "vertical",
    "background_color": "#0C2B3A",
    "corner_radius": 12,
    "paddings": {
      "left": 12,
      "right": 12,
      "top": 10,
      "bottom": 10,
    },
    "border": {
      "corner_radius": 12,
      "stroke": {
        "color": "#22D3EE",
        "width": 1,
      },
    },
    "items": [
      new DivText({
        "text": "📸 CAMERA — LIVE PREVIEW & CAPTURE (tools://camera)",
        "font_size": 11,
        "text_color": "#67E8F9",
        "font_weight": "bold",
      } as any),
      new DivText({
        "text": "Last capture: @{if(user.camera_capture_uri == '', 'none yet — tap Capture', user.camera_capture_uri)}",
        "font_size": 12,
        "text_color": "#CFFAFE",
        "font_family": "monospace",
      } as any),
      new DivImage({
        "image_url": "@{user.camera_capture_uri}",
        "preview_height": 200,
        "corner_radius": 10,
        "content_description": "Last capture",
      } as any),
      new DivText({
        "text": "Torch: @{user.camera_torch_state}",
        "font_size": 12,
        "text_color": "#CFFAFE",
        "font_family": "monospace",
      } as any),
      new DivText({
        "text": "Capture opens the viewfinder; press the shutter to take the photo",
        "font_size": 11,
        "text_color": "#A5F3FC",
        "font_weight": "bold",
      } as any),
      new DivContainer({
        "orientation": "horizontal",
        "item_spacing": 8,
        "items": [
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_camera_capture",
                "text": "Capture Photo (Back)",
                "subtitle": "Rear camera, opens preview",
                "icon": "camera",
                "background_color": "#0891B2",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_camera_capture",
                  "url": "tools://camera/capture",
                  "payload": {
                    "operation": "capture",
                    "options": {
                      "facing": "back",
                      "max_dimension": "1920",
                      "encoding": "uri",
                    },
                    "output": {
                      "uri": "user.camera_capture_uri",
                    },
                  },
                },
              },
            ],
          } as any),
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_camera_capture_front",
                "text": "Capture Photo (Front)",
                "subtitle": "Front camera, opens preview",
                "icon": "camera",
                "background_color": "#0E7490",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_camera_capture_front",
                  "url": "tools://camera/capture",
                  "payload": {
                    "operation": "capture",
                    "options": {
                      "facing": "front",
                      "max_dimension": "1920",
                      "encoding": "uri",
                    },
                    "output": {
                      "uri": "user.camera_capture_uri",
                    },
                  },
                },
              },
            ],
          } as any),
        ],
      } as any),
      new DivContainer({
        "orientation": "horizontal",
        "item_spacing": 8,
        "items": [
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_camera_torch_on",
                "text": "Torch On",
                "subtitle": "Rear flash LED",
                "icon": "camera",
                "background_color": "#B45309",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_camera_torch_on",
                  "url": "tools://camera/torch_toggle",
                  "payload": {
                    "operation": "torch_toggle",
                    "options": {
                      "state": "on",
                    },
                    "output": {
                      "torch_state": "user.camera_torch_state",
                    },
                  },
                },
              },
            ],
          } as any),
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_camera_torch_off",
                "text": "Torch Off",
                "subtitle": "Rear flash LED",
                "icon": "camera",
                "background_color": "#78350F",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_camera_torch_off",
                  "url": "tools://camera/torch_toggle",
                  "payload": {
                    "operation": "torch_toggle",
                    "options": {
                      "state": "off",
                    },
                    "output": {
                      "torch_state": "user.camera_torch_state",
                    },
                  },
                },
              },
            ],
          } as any),
        ],
      } as any),
    ],
  } as any),
  "mobilenet": new DivContainer({
    "orientation": "vertical",
    "background_color": "#1E1B4B",
    "corner_radius": 12,
    "paddings": {
      "left": 12,
      "right": 12,
      "top": 10,
      "bottom": 10,
    },
    "border": {
      "corner_radius": 12,
      "stroke": {
        "color": "#6366F1",
        "width": 1,
      },
    },
    "items": [
      new DivText({
        "text": "🧠 MobileNetV4-Small INT8 IMAGE CLASSIFIER (tools://MobileNetV4-small)",
        "font_size": 11,
        "font_weight": "bold",
        "text_color": "#5B21B6",
      } as any),
      new DivText({
        "text": "Prediction: @{user.ai_prediction_label} • Conf: @{user.ai_confidence_score} • Latency: @{user.tool_last_latency}ms",
        "font_size": 12,
        "text_color": "#E0E7FF",
        "font_family": "monospace",
      } as any),
      new DivImage({
        "image_url": "@{user.ai_source_image_uri}",
        "preview_height": 200,
        "corner_radius": 10,
        "content_description": "Classified image",
      } as any),
      new DivContainer({
        "orientation": "horizontal",
        "item_spacing": 8,
        "items": [
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_MobileNetV4-small_classify",
                "text": "📷 Classify What The Camera Sees",
                "subtitle": "Captures a frame, runs real INT8 inference",
                "icon": "camera",
                "background_color": "#4F46E5",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_MobileNetV4-small_classify",
                  "url": "tools://MobileNetV4-small/classify",
                  "payload": {
                    "operation": "classify",
                    "options": {
                      "top_k": "3",
                      "min_confidence": "0.40",
                      "camera": "true",
                    },
                    "output": {
                      "prediction_label": "user.ai_prediction_label",
                      "confidence": "user.ai_confidence_score",
                      "source_image": "user.ai_source_image_uri",
                    },
                  },
                },
              },
            ],
          } as any),
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_MobileNetV4-small_classify_file",
                "text": "🖼 Classify A Photo",
                "subtitle": "Pick an existing image",
                "icon": "image",
                "background_color": "#3730A3",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_MobileNetV4-small_classify_file",
                  "url": "tools://MobileNetV4-small/classify",
                  "payload": {
                    "operation": "classify",
                    "options": {
                      "top_k": "3",
                      "min_confidence": "0.40",
                      "camera": "false",
                    },
                    "output": {
                      "prediction_label": "user.ai_prediction_label",
                      "confidence": "user.ai_confidence_score",
                      "source_image": "user.ai_source_image_uri",
                    },
                  },
                },
              },
            ],
          } as any),
        ],
      } as any),
    ],
  } as any),
  "u2net": new DivContainer({
    "orientation": "vertical",
    "background_color": "#142834",
    "corner_radius": 12,
    "paddings": {
      "left": 12,
      "right": 12,
      "top": 10,
      "bottom": 10,
    },
    "border": {
      "corner_radius": 12,
      "stroke": {
        "color": "#06B6D4",
        "width": 1,
      },
    },
    "items": [
      new DivText({
        "text": "🪄 BACKGROUND REMOVAL — U-2-NETP (tools://u-2-Net.tflite · model not bundled)",
        "font_size": 11,
        "font_weight": "bold",
        "text_color": "#67E8F9",
      } as any),
      new DivText({
        "text": "Status: @{user.bg_remove_status}",
        "font_size": 12,
        "text_color": "#E0F2FE",
        "font_family": "monospace",
      } as any),
      new DivImage({
        "image_url": "@{user.u2net_mask_uri}",
        "preview_height": 200,
        "corner_radius": 10,
        "content_description": "Background-removed image",
      } as any),
      new DivContainer({
        "orientation": "horizontal",
        "item_spacing": 8,
        "items": [
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_u-2-Net_tflite_segment",
                "text": "🪄 Remove Background (Camera)",
                "subtitle": "Returns UNSUPPORTED unless the U-2-Netp weights are bundled",
                "icon": "crop",
                "background_color": "#0891B2",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_u-2-Net.tflite_segment",
                  "url": "tools://u-2-Net.tflite/segment",
                  "payload": {
                    "operation": "segment",
                    "options": {
                      "threshold": "0.45",
                      "camera": "true",
                    },
                    "output": {
                      "status": "user.bg_remove_status",
                      "uri": "user.u2net_mask_uri",
                    },
                  },
                },
              },
            ],
          } as any),
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_u-2-Net_tflite_segment_file",
                "text": "🖼 Remove Background (Photo)",
                "subtitle": "Pick an existing image",
                "icon": "image",
                "background_color": "#155E75",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_u-2-Net.tflite_segment_file",
                  "url": "tools://u-2-Net.tflite/segment",
                  "payload": {
                    "operation": "segment",
                    "options": {
                      "threshold": "0.45",
                      "camera": "false",
                    },
                    "output": {
                      "status": "user.bg_remove_status",
                      "uri": "user.u2net_mask_uri",
                    },
                  },
                },
              },
            ],
          } as any),
        ],
      } as any),
    ],
  } as any),
  "image_manipulation": new DivContainer({
    "orientation": "vertical",
    "background_color": "#1E1B2E",
    "corner_radius": 12,
    "paddings": {
      "left": 12,
      "right": 12,
      "top": 10,
      "bottom": 10,
    },
    "border": {
      "corner_radius": 12,
      "stroke": {
        "color": "#A78BFA",
        "width": 1,
      },
    },
    "items": [
      new DivText({
        "text": "🎛️ IMAGE MANIPULATION — REAL PIXELS IN, REAL PIXELS OUT (tools://image-manipulation)",
        "font_size": 11,
        "text_color": "#C4B5FD",
        "font_weight": "bold",
      } as any),
      new DivText({
        "text": "Every button below runs a real transform on the source shown underneath and writes a new PNG into the tenant media sandbox.",
        "font_size": 11,
        "text_color": "#5B21B6",
      } as any),
      new DivText({
        "text": "Source image",
        "font_size": 11,
        "text_color": "#DDD6FE",
        "font_weight": "bold",
      } as any),
      new DivImage({
        "image_url": "@{user.processed_source_uri}",
        "preview_height": 160,
        "corner_radius": 10,
        "content_description": "Source image being manipulated",
      } as any),
      new DivText({
        "text": "Output",
        "font_size": 11,
        "text_color": "#DDD6FE",
        "font_weight": "bold",
      } as any),
      new DivImage({
        "image_url": "@{user.processed_image_uri}",
        "preview_height": 200,
        "corner_radius": 10,
        "content_description": "Manipulated output image",
      } as any),
      new DivText({
        "text": "Status: @{user.image_processing_status} • @{user.processed_width}×@{user.processed_height}",
        "font_size": 11,
        "text_color": "#EDE9FE",
        "font_family": "monospace",
      } as any),
      new DivText({
        "text": "Background removal",
        "font_size": 11,
        "text_color": "#DDD6FE",
        "font_weight": "bold",
      } as any),
      new DivContainer({
        "orientation": "horizontal",
        "item_spacing": 8,
        "items": [
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_image-manipulation_remove_background",
                "text": "Remove Background",
                "subtitle": "tools://image-manipulation/remove_background",
                "icon": "crop",
                "background_color": "#7C3AED",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_image-manipulation_remove_background",
                  "url": "tools://image-manipulation/remove_background",
                  "payload": {
                    "operation": "remove_background",
                    "options": {
                      "op": "remove_background",
                      "threshold": "0.35",
                      "uri": "${demoImageUri}",
                    },
                    "output": {
                      "uri": "user.processed_image_uri",
                      "status": "user.image_processing_status",
                      "width": "user.processed_width",
                      "height": "user.processed_height",
                      "source_image": "user.processed_source_uri",
                    },
                  },
                },
              },
            ],
          } as any),
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_image-manipulation_remove_background_wide",
                "text": "Remove Wide Matte",
                "subtitle": "Wider colour tolerance",
                "icon": "wand",
                "background_color": "#6D28D9",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_image-manipulation_remove_background_wide",
                  "url": "tools://image-manipulation/remove_background",
                  "payload": {
                    "operation": "remove_background",
                    "options": {
                      "op": "remove_background",
                      "threshold": "0.6",
                      "uri": "${demoImageUri}",
                    },
                    "output": {
                      "uri": "user.processed_image_uri",
                      "status": "user.image_processing_status",
                      "width": "user.processed_width",
                      "height": "user.processed_height",
                      "source_image": "user.processed_source_uri",
                    },
                  },
                },
              },
            ],
          } as any),
        ],
      } as any),
      new DivText({
        "text": "Smoothing",
        "font_size": 11,
        "text_color": "#DDD6FE",
        "font_weight": "bold",
      } as any),
      new DivContainer({
        "orientation": "horizontal",
        "item_spacing": 8,
        "items": [
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_image-manipulation_smooth",
                "text": "Edge-Preserving Smooth",
                "subtitle": "Bilateral, strength 0.6",
                "icon": "refresh",
                "background_color": "#4F46E5",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_image-manipulation_smooth",
                  "url": "tools://image-manipulation/smooth",
                  "payload": {
                    "operation": "smooth",
                    "options": {
                      "op": "smooth",
                      "type": "bilateral",
                      "strength": "0.6",
                      "uri": "${demoImageUri}",
                    },
                    "output": {
                      "uri": "user.processed_image_uri",
                      "status": "user.image_processing_status",
                      "width": "user.processed_width",
                      "height": "user.processed_height",
                      "source_image": "user.processed_source_uri",
                    },
                  },
                },
              },
            ],
          } as any),
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_image-manipulation_blur",
                "text": "Box Blur",
                "subtitle": "Uniform blur, radius 12",
                "icon": "blur",
                "background_color": "#4338CA",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_image-manipulation_blur",
                  "url": "tools://image-manipulation/smooth",
                  "payload": {
                    "operation": "smooth",
                    "options": {
                      "op": "smooth",
                      "type": "box",
                      "radius": "12",
                      "uri": "${demoImageUri}",
                    },
                    "output": {
                      "uri": "user.processed_image_uri",
                      "status": "user.image_processing_status",
                      "width": "user.processed_width",
                      "height": "user.processed_height",
                      "source_image": "user.processed_source_uri",
                    },
                  },
                },
              },
            ],
          } as any),
        ],
      } as any),
      new DivText({
        "text": "Tone & colour",
        "font_size": 11,
        "text_color": "#DDD6FE",
        "font_weight": "bold",
      } as any),
      new DivContainer({
        "orientation": "horizontal",
        "item_spacing": 8,
        "items": [
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_image-manipulation_grayscale",
                "text": "Grayscale",
                "subtitle": "Saturation → 0",
                "icon": "palette",
                "background_color": "#0F766E",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_image-manipulation_grayscale",
                  "url": "tools://image-manipulation/adjust_color",
                  "payload": {
                    "operation": "adjust_color",
                    "options": {
                      "op": "adjust_color",
                      "grayscale": "true",
                      "uri": "${demoImageUri}",
                    },
                    "output": {
                      "uri": "user.processed_image_uri",
                      "status": "user.image_processing_status",
                      "width": "user.processed_width",
                      "height": "user.processed_height",
                      "source_image": "user.processed_source_uri",
                    },
                  },
                },
              },
            ],
          } as any),
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_image-manipulation_brighten",
                "text": "Brighten + Contrast",
                "subtitle": "brightness 0.18, contrast 1.25",
                "icon": "sun",
                "background_color": "#0D9488",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_image-manipulation_brighten",
                  "url": "tools://image-manipulation/adjust_color",
                  "payload": {
                    "operation": "adjust_color",
                    "options": {
                      "op": "adjust_color",
                      "brightness": "0.18",
                      "contrast": "1.25",
                      "uri": "${demoImageUri}",
                    },
                    "output": {
                      "uri": "user.processed_image_uri",
                      "status": "user.image_processing_status",
                      "width": "user.processed_width",
                      "height": "user.processed_height",
                      "source_image": "user.processed_source_uri",
                    },
                  },
                },
              },
            ],
          } as any),
        ],
      } as any),
      new DivContainer({
        "orientation": "horizontal",
        "item_spacing": 8,
        "items": [
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_image-manipulation_threshold",
                "text": "Binarize",
                "subtitle": "Threshold at 128",
                "icon": "barcode",
                "background_color": "#B45309",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_image-manipulation_threshold",
                  "url": "tools://image-manipulation/threshold",
                  "payload": {
                    "operation": "threshold",
                    "options": {
                      "op": "threshold",
                      "threshold": "128",
                      "uri": "${demoImageUri}",
                    },
                    "output": {
                      "uri": "user.processed_image_uri",
                      "status": "user.image_processing_status",
                      "width": "user.processed_width",
                      "height": "user.processed_height",
                      "source_image": "user.processed_source_uri",
                    },
                  },
                },
              },
            ],
          } as any),
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_image-manipulation_flip",
                "text": "Mirror",
                "subtitle": "Flip horizontally",
                "icon": "refresh",
                "background_color": "#92400E",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_image-manipulation_flip",
                  "url": "tools://image-manipulation/flip",
                  "payload": {
                    "operation": "flip",
                    "options": {
                      "op": "flip",
                      "direction": "horizontal",
                      "uri": "${demoImageUri}",
                    },
                    "output": {
                      "uri": "user.processed_image_uri",
                      "status": "user.image_processing_status",
                      "width": "user.processed_width",
                      "height": "user.processed_height",
                      "source_image": "user.processed_source_uri",
                    },
                  },
                },
              },
            ],
          } as any),
        ],
      } as any),
      new DivText({
        "text": "Geometry",
        "font_size": 11,
        "text_color": "#DDD6FE",
        "font_weight": "bold",
      } as any),
      new DivContainer({
        "orientation": "horizontal",
        "item_spacing": 8,
        "items": [
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_image-manipulation_crop",
                "text": "Crop Centre",
                "subtitle": "Middle 60% of the frame",
                "icon": "crop",
                "background_color": "#BE123C",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_image-manipulation_crop",
                  "url": "tools://image-manipulation/crop",
                  "payload": {
                    "operation": "crop",
                    "options": {
                      "op": "crop",
                      "x": "20",
                      "y": "20",
                      "width": "60",
                      "height": "60",
                      "relative": "true",
                      "uri": "${demoImageUri}",
                    },
                    "output": {
                      "uri": "user.processed_image_uri",
                      "status": "user.image_processing_status",
                      "width": "user.processed_width",
                      "height": "user.processed_height",
                      "source_image": "user.processed_source_uri",
                    },
                  },
                },
              },
            ],
          } as any),
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_image-manipulation_resize",
                "text": "Resize to 320px",
                "subtitle": "Longest edge → 320",
                "icon": "image",
                "background_color": "#9F1239",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_image-manipulation_resize",
                  "url": "tools://image-manipulation/resize",
                  "payload": {
                    "operation": "resize",
                    "options": {
                      "op": "resize",
                      "max_dimension": "320",
                      "uri": "${demoImageUri}",
                    },
                    "output": {
                      "uri": "user.processed_image_uri",
                      "status": "user.image_processing_status",
                      "width": "user.processed_width",
                      "height": "user.processed_height",
                      "source_image": "user.processed_source_uri",
                    },
                  },
                },
              },
            ],
          } as any),
        ],
      } as any),
      new DivContainer({
        "orientation": "horizontal",
        "item_spacing": 8,
        "items": [
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_image-manipulation_rotate",
                "text": "Rotate 90°",
                "subtitle": "Clockwise quarter turn",
                "icon": "refresh",
                "background_color": "#7E22CE",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_image-manipulation_rotate",
                  "url": "tools://image-manipulation/rotate",
                  "payload": {
                    "operation": "rotate",
                    "options": {
                      "op": "rotate",
                      "degrees": "90",
                      "uri": "${demoImageUri}",
                    },
                    "output": {
                      "uri": "user.processed_image_uri",
                      "status": "user.image_processing_status",
                      "width": "user.processed_width",
                      "height": "user.processed_height",
                      "source_image": "user.processed_source_uri",
                    },
                  },
                },
              },
            ],
          } as any),
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_image-manipulation_pick",
                "text": "Use My Photo",
                "subtitle": "Capture or pick a source, then transform it",
                "icon": "camera",
                "background_color": "#5B21B6",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_image-manipulation_pick",
                  "url": "tools://image-manipulation/smooth",
                  "payload": {
                    "operation": "smooth",
                    "options": {
                      "op": "smooth",
                      "type": "bilateral",
                      "strength": "0.4",
                      "camera": "true",
                    },
                    "output": {
                      "uri": "user.processed_image_uri",
                      "status": "user.image_processing_status",
                      "width": "user.processed_width",
                      "height": "user.processed_height",
                      "source_image": "user.processed_source_uri",
                    },
                  },
                },
              },
            ],
          } as any),
        ],
      } as any),
      new DivText({
        "text": "Pipeline",
        "font_size": 11,
        "text_color": "#DDD6FE",
        "font_weight": "bold",
      } as any),
      new DivContainer({
        "weight": 1,
        "items": [
          {
            "type": "button",
            "id": "tool_image-manipulation_pipeline",
            "text": "Run Pipeline: crop → smooth → brighten",
            "subtitle": "One call, three steps",
            "icon": "wand",
            "background_color": "#6B21A8",
            "text_color": "#FFFFFF",
            "corner_radius": 10,
            "action": {
              "log_id": "exec_tool_image-manipulation_pipeline",
              "url": "tools://image-manipulation/process",
              "payload": {
                "operation": "process",
                "options": {
                  "op": "process",
                  "uri": "${demoImageUri}",
                  "pipeline": "[{\"op\": \"crop\", \"x\": 10, \"y\": 10, \"width\": 80, \"height\": 80, \"relative\": true}, {\"op\": \"smooth\", \"type\": \"bilateral\", \"strength\": 0.4}, {\"op\": \"adjust_color\", \"brightness\": 0.1, \"contrast\": 1.1}]",
                },
                "output": {
                  "uri": "user.processed_image_uri",
                  "status": "user.image_processing_status",
                  "width": "user.processed_width",
                  "height": "user.processed_height",
                  "source_image": "user.processed_source_uri",
                },
              },
            },
          },
        ],
      } as any),
    ],
  } as any),
  "embedding": new DivContainer({
    "orientation": "vertical",
    "background_color": "#111827",
    "corner_radius": 12,
    "paddings": {
      "left": 12,
      "right": 12,
      "top": 10,
      "bottom": 10,
    },
    "border": {
      "corner_radius": 12,
      "stroke": {
        "color": "#38BDF8",
        "width": 1,
      },
    },
    "items": [
      new DivText({
        "text": "🧮 TEXT & IMAGE EMBEDDINGS (tools://embedding)",
        "font_size": 11,
        "font_weight": "bold",
        "text_color": "#7DD3FC",
      } as any),
      new DivText({
        "text": "Source: @{user.vector_source} • Dim: @{user.vector_dim} • Similarity: @{user.vector_similarity}",
        "font_size": 12,
        "text_color": "#E0F2FE",
        "font_family": "monospace",
      } as any),
      new DivText({
        "text": "Vector: @{user.vector_b64}",
        "font_size": 10,
        "text_color": "#BAE6FD",
        "font_family": "monospace",
        "max_lines": 2,
      } as any),
      new DivText({
        "text": "OCR: @{user.ocr_text}",
        "font_size": 10,
        "text_color": "#A7F3D0",
        "font_family": "monospace",
        "max_lines": 2,
      } as any),
      {
        "type": "button",
        "id": "tool_embedding_text",
        "text": "Embed Item Name",
        "subtitle": "tools://embedding/text • USE-Lite / lexical fallback",
        "icon": "search",
        "background_color": "#0284C7",
        "text_color": "#FFFFFF",
        "corner_radius": 10,
        "action": {
          "log_id": "exec_tool_embedding_text",
          "url": "tools://embedding/text",
          "payload": {
            "operation": "text",
            "options": {
              "normalize": "true",
              "text": "Wagyu Smash Burger • ACM-101 • $16.50",
            },
            "output": {
              "vector_b64": "user.vector_b64",
              "dim": "user.vector_dim",
              "source_type": "user.vector_source",
            },
          },
        },
      },
      {
        "type": "button",
        "id": "tool_embedding_image",
        "text": "Embed Image",
        "subtitle": "tools://embedding/image • MobileNetV4 1000-d",
        "icon": "camera",
        "background_color": "#0E7490",
        "text_color": "#FFFFFF",
        "corner_radius": 10,
        "action": {
          "log_id": "exec_tool_embedding_image",
          "url": "tools://embedding/image",
          "payload": {
            "operation": "image",
            "options": {
              "normalize": "true",
              "uri": "${demoImageUri}",
            },
            "output": {
              "vector_b64": "user.vector_b64",
              "dim": "user.vector_dim",
              "source_type": "user.vector_source",
            },
          },
        },
      },
      {
        "type": "button",
        "id": "tool_embedding_image_text",
        "text": "Embed Image + OCR",
        "subtitle": "tools://embedding/image_text • ML Kit + USE-Lite / lexical fallback",
        "icon": "ocr",
        "background_color": "#0369A1",
        "text_color": "#FFFFFF",
        "corner_radius": 10,
        "action": {
          "log_id": "exec_tool_embedding_image_text",
          "url": "tools://embedding/image_text",
          "payload": {
            "operation": "image_text",
            "options": {
              "normalize": "true",
              "include_ocr_text": "true",
              "uri": "${demoImageUri}",
            },
            "output": {
              "vector_b64": "user.vector_b64",
              "dim": "user.vector_dim",
              "source_type": "user.vector_source",
              "ocr_text": "user.ocr_text",
            },
          },
        },
      },
      {
        "type": "button",
        "id": "tool_embedding_text_similarity",
        "text": "Compare Two Texts",
        "subtitle": "tools://embedding/text_similarity • cosine",
        "icon": "query_stats",
        "background_color": "#6D28D9",
        "text_color": "#FFFFFF",
        "corner_radius": 10,
        "action": {
          "log_id": "exec_tool_embedding_text_similarity",
          "url": "tools://embedding/text_similarity",
          "payload": {
            "operation": "text_similarity",
            "options": {
              "a": "Wagyu Smash Burger",
              "b": "Smash burger with wagyu beef",
            },
            "output": {
              "score": "user.vector_similarity",
              "dim": "user.vector_dim",
              "source_type": "user.vector_source",
            },
          },
        },
      },
      {
        "type": "button",
        "id": "tool_embedding_similarity",
        "text": "Compare Current Vector",
        "subtitle": "tools://embedding/similarity • a = b",
        "icon": "query_stats",
        "background_color": "#4C1D95",
        "text_color": "#FFFFFF",
        "corner_radius": 10,
        "action": {
          "log_id": "exec_tool_embedding_similarity",
          "url": "tools://embedding/similarity",
          "payload": {
            "operation": "similarity",
            "options": {
              "a": "@{user.vector_b64}",
              "b": "@{user.vector_b64}",
            },
            "output": {
              "score": "user.vector_similarity",
              "dim": "user.vector_dim",
              "source_type": "user.vector_source",
            },
          },
        },
      },
    ],
  } as any),
  "libsignal": new DivContainer({
    "variables": [
      {
        "name": "user.signal_peer_id",
        "type": "string",
        "value": "acme_peer_01",
      },
      {
        "name": "user.signal_session_id",
        "type": "string",
        "value": "",
      },
      {
        "name": "user.signal_fingerprint",
        "type": "string",
        "value": "",
      },
      {
        "name": "user.signal_fingerprint_display",
        "type": "string",
        "value": "",
      },
      {
        "name": "user.signal_status",
        "type": "string",
        "value": "NO_SESSION",
      },
      {
        "name": "user.signal_verification",
        "type": "string",
        "value": "UNVERIFIED",
      },
      {
        "name": "user.signal_ciphertext",
        "type": "string",
        "value": "",
      },
      {
        "name": "user.signal_plaintext",
        "type": "string",
        "value": "",
      },
    ],
    "orientation": "vertical",
    "background_color": "#1E293B",
    "corner_radius": 14,
    "paddings": {
      "left": 16,
      "right": 16,
      "top": 16,
      "bottom": 16,
    },
    "border": {
      "corner_radius": 14,
      "stroke": {
        "color": "#334155",
        "width": 1,
      },
    },
    "items": [
      new DivContainer({
        "orientation": "horizontal",
        "item_spacing": 10,
        "items": [
          new DivContainer({
            "orientation": "vertical",
            "item_spacing": 2,
            "items": [
              new DivText({
                "text": "Libsignal E2EE",
                "font_size": 18,
                "text_color": "#F8FAFC",
                "font_weight": "bold",
              } as any),
              new DivText({
                "text": "Signal Protocol · Double Ratchet · X3DH",
                "font_size": 11,
                "text_color": "#CBD5E1",
              } as any),
            ],
            "weight": 1,
          } as any),
        ],
      } as any),
      new DivText({
        "text": "@{user.signal_status}",
        "font_size": 11,
        "text_color": "#F8FAFC",
        "font_weight": "bold",
        "background_color": "#334155",
        "corner_radius": 20,
        "paddings": {
          "left": 10,
          "right": 10,
          "top": 5,
          "bottom": 5,
        },
        "margins": {
          "top": 6,
        },
      } as any),
      new DivText({
        "text": "Peer @{user.signal_peer_id} · @{if(user.signal_session_id == '', 'no session', user.signal_session_id)}",
        "font_size": 10,
        "text_color": "#CBD5E1",
        "font_family": "monospace",
      } as any),
      new DivContainer({
        "orientation": "vertical",
        "item_spacing": 2,
        "items": [
          new DivText({
            "text": "Safety number",
            "font_size": 10,
            "text_color": "#CBD5E1",
            "font_weight": "bold",
          } as any),
          new DivText({
            "text": "@{if(user.signal_fingerprint_display == '', '—', user.signal_fingerprint_display)}",
            "font_size": 12,
            "text_color": "#F8FAFC",
            "font_weight": "bold",
            "font_family": "monospace",
            "max_lines": 3,
          } as any),
        ],
      } as any),
      new DivContainer({
        "orientation": "vertical",
        "item_spacing": 2,
        "items": [
          new DivText({
            "text": "Verification",
            "font_size": 10,
            "text_color": "#CBD5E1",
            "font_weight": "bold",
          } as any),
          new DivText({
            "text": "@{user.signal_verification}",
            "font_size": 12,
            "text_color": "@{if(user.signal_verification == 'VERIFIED', '#34D399', '#F87171')}",
            "font_weight": "bold",
          } as any),
        ],
      } as any),
      new DivContainer({
        "orientation": "horizontal",
        "item_spacing": 8,
        "items": [
          {
            "type": "button",
            "text": "Establish",
            "icon": "lock",
            "background_color": "#7C3AED",
            "text_color": "#FFFFFF",
            "corner_radius": 12,
            "font_size": 14,
            "font_weight": "bold",
            "weight": 1,
            "action": {
              "log_id": "exec_tool_libsignal_create_session",
              "url": "tools://libsignal/create_session",
              "payload": {
                "peer_id": "@{user.signal_peer_id}",
                "output": {
                  "status": "user.signal_status",
                  "session_id": "user.signal_session_id",
                  "fingerprint": "user.signal_fingerprint",
                  "safety_number_display": "user.signal_fingerprint_display",
                  "verification_status": "user.signal_verification",
                  "ciphertext": "user.signal_ciphertext",
                  "plaintext": "user.signal_plaintext",
                },
              },
            },
            "id": "tool_libsignal_create_session",
          },
          {
            "type": "button",
            "text": "Encrypt",
            "icon": "e2ee",
            "background_color": "#6D28D9",
            "text_color": "#FFFFFF",
            "corner_radius": 12,
            "font_size": 14,
            "font_weight": "bold",
            "weight": 1,
            "action": {
              "log_id": "exec_tool_libsignal_encrypt",
              "url": "tools://libsignal/encrypt",
              "payload": {
                "peer_id": "@{user.signal_peer_id}",
                "data": "{\"order\":\"#1042\",\"total\":\"18.50\",\"terminal\":\"till-07\"}",
                "output": {
                  "status": "user.signal_status",
                  "ciphertext": "user.signal_ciphertext",
                  "safety_number": "user.signal_fingerprint",
                  "safety_number_display": "user.signal_fingerprint_display",
                },
              },
            },
            "id": "tool_libsignal_encrypt",
          },
        ],
      } as any),
      new DivContainer({
        "orientation": "horizontal",
        "item_spacing": 8,
        "items": [
          {
            "type": "button",
            "text": "Decrypt",
            "icon": "shield",
            "background_color": "#5B21B6",
            "text_color": "#FFFFFF",
            "corner_radius": 12,
            "font_size": 14,
            "font_weight": "bold",
            "weight": 1,
            "action": {
              "log_id": "exec_tool_libsignal_decrypt",
              "url": "tools://libsignal/decrypt",
              "payload": {
                "peer_id": "@{user.signal_peer_id}",
                "ciphertext": "@{user.signal_ciphertext}",
                "output": {
                  "status": "user.signal_status",
                  "plaintext": "user.signal_plaintext",
                  "safety_number_display": "user.signal_fingerprint_display",
                },
              },
            },
            "id": "tool_libsignal_decrypt",
          },
          {
            "type": "button",
            "text": "Verify identity",
            "icon": "verified",
            "background_color": "#4C1D95",
            "text_color": "#FFFFFF",
            "corner_radius": 12,
            "font_size": 13,
            "font_weight": "bold",
            "weight": 1,
            "action": {
              "log_id": "exec_tool_libsignal_verify_identity",
              "url": "tools://libsignal/verify_identity",
              "payload": {
                "peer_id": "@{user.signal_peer_id}",
                "safety_number": "@{user.signal_fingerprint}",
                "output": {
                  "status": "user.signal_verification",
                  "safety_number": "user.signal_fingerprint",
                  "safety_number_display": "user.signal_fingerprint_display",
                },
              },
            },
            "id": "tool_libsignal_verify_identity",
          },
        ],
      } as any),
      new DivContainer({
        "orientation": "vertical",
        "item_spacing": 4,
        "items": [
          new DivText({
            "text": "@{if(user.signal_ciphertext == '', 'Ciphertext', 'Ciphertext · ' + len(user.signal_ciphertext) + ' chars (3-line preview)')}",
            "font_size": 11,
            "text_color": "#CBD5E1",
            "font_weight": "bold",
          } as any),
          new DivText({
            "text": "@{if(user.signal_ciphertext == '', 'Nothing encrypted yet.', user.signal_ciphertext)}",
            "font_size": 11,
            "text_color": "#CBD5E1",
            "font_family": "monospace",
            "max_lines": 3,
          } as any),
        ],
        "background_color": "#334155",
        "corner_radius": 10,
        "paddings": {
          "left": 12,
          "right": 12,
          "top": 10,
          "bottom": 10,
        },
      } as any),
      new DivContainer({
        "orientation": "vertical",
        "item_spacing": 4,
        "items": [
          new DivText({
            "text": "Decrypted plaintext",
            "font_size": 11,
            "text_color": "#CBD5E1",
            "font_weight": "bold",
          } as any),
          new DivText({
            "text": "@{if(user.signal_plaintext == '', 'Nothing decrypted yet.', user.signal_plaintext)}",
            "font_size": 11,
            "text_color": "#F8FAFC",
            "font_family": "monospace",
            "max_lines": 3,
          } as any),
        ],
        "background_color": "#334155",
        "corner_radius": 10,
        "paddings": {
          "left": 12,
          "right": 12,
          "top": 10,
          "bottom": 10,
        },
      } as any),
      new DivText({
        "text": "@{if(user.engine_error != '', 'Error: ' + user.engine_error, if(user.signal_status == 'NO_SESSION', 'Ready — establish a session to begin.', if(user.signal_status == 'VERIFIED', 'Round trip verified · identity trusted for this peer.', if(user.signal_status == 'DECRYPTED', 'Round trip confirmed · plaintext matches the payload.', 'Session live · encrypt then decrypt to confirm the round trip.'))))}",
        "font_size": 11,
        "text_color": "@{if(user.engine_error == '', '#64748B', '#F87171')}",
        "font_family": "monospace",
        "max_lines": 3,
      } as any),
      {
        "type": "button",
        "text": "Tamper test",
        "icon": "warning",
        "background_color": "#7F1D1D",
        "text_color": "#FFFFFF",
        "corner_radius": 12,
        "font_size": 14,
        "font_weight": "bold",
        "weight": 1,
        "action": {
          "log_id": "exec_tool_libsignal_tamper_decrypt",
          "url": "tools://libsignal/decrypt",
          "payload": {
            "peer_id": "@{user.signal_peer_id}",
            "ciphertext": "@{user.signal_ciphertext}",
            "output": {
              "status": "user.signal_status",
              "plaintext": "user.signal_plaintext",
              "safety_number_display": "user.signal_fingerprint_display",
            },
            "tamper": "1",
          },
        },
        "id": "tool_libsignal_tamper",
      },
      {
        "type": "button",
        "text": "Reset session",
        "icon": "refresh",
        "background_color": "#334155",
        "text_color": "#F8FAFC",
        "corner_radius": 12,
        "font_size": 12,
        "font_weight": "bold",
        "weight": 1,
        "action": {
          "log_id": "exec_tool_libsignal_reset_session",
          "url": "tools://libsignal/reset_session",
          "payload": {
            "peer_id": "@{user.signal_peer_id}",
            "output": {
              "status": "user.signal_status",
              "session_id": "user.signal_session_id",
              "fingerprint": "user.signal_fingerprint",
              "safety_number_display": "user.signal_fingerprint_display",
              "verification_status": "user.signal_verification",
              "ciphertext": "user.signal_ciphertext",
              "plaintext": "user.signal_plaintext",
            },
          },
        },
        "id": "tool_libsignal_reset_session",
      },
    ],
  } as any),
  "biometrics": new DivContainer({
    "orientation": "vertical",
    "background_color": "#0D0D0D",
    "corner_radius": 14,
    "item_spacing": 12,
    "paddings": {
      "left": 14,
      "right": 14,
      "top": 12,
      "bottom": 12,
    },
    "border": {
      "corner_radius": 14,
      "stroke": {
        "color": "#B91C1C",
        "width": 2,
      },
    },
    "items": [
      new DivContainer({
        "background_color": "#1A0A0A",
        "corner_radius": 10,
        "paddings": {
          "left": 12,
          "right": 12,
          "top": 10,
          "bottom": 10,
        },
        "border": {
          "corner_radius": 10,
          "stroke": {
            "color": "#B91C1C",
            "width": 1,
          },
        },
        "item_spacing": 6,
        "items": [
          new DivText({
            "text": "🛡️ SUPERVISOR CREDENTIAL GATE",
            "font_size": 14,
            "font_weight": "bold",
            "text_color": "#B91C1C",
          } as any),
          new DivText({
            "text": "tools://biometrics • auth.biometrics@1",
            "font_size": 10,
            "text_color": "#F87171",
            "font_family": "monospace",
          } as any),
          new DivText({
            "text": "Void, refund, and discount operations require supervisor fingerprint or PIN. This terminal is locked until authorized.",
            "font_size": 11,
            "text_color": "#FCA5A5",
            "margins": {
              "top": 4,
            },
          } as any),
        ],
      } as any),
      new DivContainer({
        "background_color": "#1A0A0A",
        "corner_radius": 10,
        "paddings": {
          "left": 12,
          "right": 12,
          "top": 12,
          "bottom": 12,
        },
        "border": {
          "corner_radius": 10,
          "stroke": {
            "color": "#7F1D1D",
            "width": 1,
          },
        },
        "item_spacing": 6,
        "items": [
          new DivText({
            "text": "CURRENT AUTH STATE",
            "font_size": 10,
            "font_weight": "bold",
            "text_color": "#4B5563",
            "font_family": "monospace",
          } as any),
          new DivText({
            "text": "@{user.supervisor_authenticated}",
            "font_size": 22,
            "font_weight": "bold",
            "text_color": "#34D399",
            "font_family": "monospace",
            "alignment_horizontal": "center",
          } as any),
          new DivText({
            "text": "Role: @{user.supervisor_role}  •  Method: @{user.supervisor_auth_method}",
            "font_size": 11,
            "text_color": "#6EE7B7",
            "font_family": "monospace",
            "alignment_horizontal": "center",
          } as any),
        ],
      } as any),
      new DivText({
        "text": "SUPERVISOR ACTIONS",
        "font_size": 10,
        "font_weight": "bold",
        "text_color": "#4B5563",
        "font_family": "monospace",
      } as any),
      {
        "type": "button",
        "id": "tool_biometrics_authenticate",
        "text": "Authenticate Supervisor (Biometric / PIN)",
        "subtitle": "Triggers real OS device-credential prompt — denies when unverified",
        "icon": "fingerprint",
        "background_color": "#991B1B",
        "text_color": "#FFFFFF",
        "corner_radius": 10,
        "action": {
          "log_id": "exec_tool_biometrics_authenticate",
          "url": "tools://biometrics/authenticate",
          "payload": {
            "operation": "authenticate",
            "options": {
              "required_role": "SUPERVISOR",
            },
            "output": {
              "authenticated": "user.supervisor_authenticated",
              "role": "user.supervisor_role",
              "method": "user.supervisor_auth_method",
            },
          },
        },
      },
      {
        "type": "button",
        "id": "tool_biometrics_authorize_void",
        "text": "Authorize Void / Refund",
        "subtitle": "Requires supervisor credential for void or refund operations",
        "icon": "lock",
        "background_color": "#7C3AED",
        "text_color": "#FFFFFF",
        "corner_radius": 10,
        "action": {
          "log_id": "exec_tool_biometrics_authorize_void",
          "url": "tools://biometrics/authenticate",
          "payload": {
            "operation": "authenticate",
            "options": {
              "required_role": "SUPERVISOR",
              "reason": "Authorize void/refund",
            },
            "output": {
              "authenticated": "user.supervisor_authenticated",
              "role": "user.supervisor_role",
              "method": "user.supervisor_auth_method",
            },
          },
        },
      },
      {
        "type": "button",
        "id": "tool_biometrics_lock",
        "text": "Lock Terminal",
        "subtitle": "Clears supervisor session — requires re-auth for protected operations",
        "icon": "lock",
        "background_color": "#374151",
        "text_color": "#D1D5DB",
        "corner_radius": 10,
        "action": {
          "log_id": "exec_tool_biometrics_lock",
          "url": "tools://biometrics/lock",
          "payload": {
            "operation": "lock",
            "options": {
              "op": "lock",
            },
            "output": {
              "authenticated": "user.supervisor_authenticated",
              "role": "user.supervisor_role",
            },
          },
        },
      },
    ],
  } as any),
  "haptics": new DivContainer({
    "orientation": "vertical",
    "background_color": "#1F2937",
    "corner_radius": 12,
    "paddings": {
      "left": 12,
      "right": 12,
      "top": 10,
      "bottom": 10,
    },
    "border": {
      "corner_radius": 12,
      "stroke": {
        "color": "#F59E0B",
        "width": 1,
      },
    },
    "items": [
      new DivText({
        "text": "📳 HAPTIC TACTILE ENGINE (device.haptics@1 • tools://haptics)",
        "font_size": 11,
        "font_weight": "bold",
        "text_color": "#FCD34D",
      } as any),
      new DivText({
        "text": "Each button drives the device vibrator with the pattern named on it and reports the pattern and duration that were actually applied.",
        "font_size": 11,
        "text_color": "#FDE68A",
      } as any),
      new DivText({
        "text": "State: @{user.haptic_state} • Pattern: @{user.haptic_pattern} • Duration: @{user.haptic_duration} ms",
        "font_size": 12,
        "text_color": "#FEF3C7",
        "font_family": "monospace",
      } as any),
      new DivContainer({
        "orientation": "horizontal",
        "item_spacing": 8,
        "items": [
          {
            "type": "button",
            "weight": 1,
            "id": "haptic_click",
            "text": "Click",
            "icon": "haptics",
            "background_color": "#374151",
            "text_color": "#F9FAFB",
            "corner_radius": 8,
            "action": {
              "log_id": "haptic_click",
              "url": "tools://haptics/click",
              "payload": {
                "output": {
                  "status": "user.haptic_state",
                  "pattern": "user.haptic_pattern",
                  "duration_ms": "user.haptic_duration",
                },
              },
            },
          },
          {
            "type": "button",
            "weight": 1,
            "id": "haptic_heavy",
            "text": "Heavy",
            "icon": "haptics",
            "background_color": "#D97706",
            "text_color": "#FFFFFF",
            "corner_radius": 8,
            "action": {
              "log_id": "haptic_heavy",
              "url": "tools://haptics/heavy",
              "payload": {
                "output": {
                  "status": "user.haptic_state",
                  "pattern": "user.haptic_pattern",
                  "duration_ms": "user.haptic_duration",
                },
              },
            },
          },
          {
            "type": "button",
            "weight": 1,
            "id": "haptic_success",
            "text": "Success",
            "icon": "check",
            "background_color": "#059669",
            "text_color": "#FFFFFF",
            "corner_radius": 8,
            "action": {
              "log_id": "haptic_success",
              "url": "tools://haptics/success",
              "payload": {
                "output": {
                  "status": "user.haptic_state",
                  "pattern": "user.haptic_pattern",
                  "duration_ms": "user.haptic_duration",
                },
              },
            },
          },
        ],
      } as any),
      new DivContainer({
        "orientation": "horizontal",
        "item_spacing": 8,
        "items": [
          {
            "type": "button",
            "weight": 1,
            "id": "haptic_warning",
            "text": "Warning",
            "icon": "notification",
            "background_color": "#B45309",
            "text_color": "#FFFFFF",
            "corner_radius": 8,
            "action": {
              "log_id": "haptic_warning",
              "url": "tools://haptics/warning",
              "payload": {
                "output": {
                  "status": "user.haptic_state",
                  "pattern": "user.haptic_pattern",
                  "duration_ms": "user.haptic_duration",
                },
              },
            },
          },
          {
            "type": "button",
            "weight": 1,
            "id": "haptic_error",
            "text": "Error Buzz",
            "icon": "clear",
            "background_color": "#DC2626",
            "text_color": "#FFFFFF",
            "corner_radius": 8,
            "action": {
              "log_id": "haptic_error",
              "url": "tools://haptics/error",
              "payload": {
                "output": {
                  "status": "user.haptic_state",
                  "pattern": "user.haptic_pattern",
                  "duration_ms": "user.haptic_duration",
                },
              },
            },
          },
          {
            "type": "button",
            "weight": 1,
            "id": "haptic_tick",
            "text": "Tick",
            "icon": "haptics",
            "background_color": "#4B5563",
            "text_color": "#F9FAFB",
            "corner_radius": 8,
            "action": {
              "log_id": "haptic_tick",
              "url": "tools://haptics/tick",
              "payload": {
                "output": {
                  "status": "user.haptic_state",
                  "pattern": "user.haptic_pattern",
                  "duration_ms": "user.haptic_duration",
                },
              },
            },
          },
        ],
      } as any),
      {
        "type": "button",
        "id": "haptic_stop",
        "text": "Stop vibration",
        "subtitle": "tools://haptics/stop",
        "icon": "clear",
        "background_color": "#334155",
        "text_color": "#FFFFFF",
        "corner_radius": 8,
        "action": {
          "log_id": "haptic_stop",
          "url": "tools://haptics/stop",
          "payload": {
            "output": {
              "status": "user.haptic_state",
            },
          },
        },
      },
    ],
  } as any),
  "beeper": new DivContainer({
    "orientation": "vertical",
    "background_color": "#1E1B4B",
    "corner_radius": 12,
    "paddings": {
      "left": 12,
      "right": 12,
      "top": 10,
      "bottom": 10,
    },
    "border": {
      "corner_radius": 12,
      "stroke": {
        "color": "#818CF8",
        "width": 1,
      },
    },
    "items": [
      new DivText({
        "text": "🔔 HARDWARE BEEPER AUDIO (device.beeper@1 • tools://beeper)",
        "font_size": 11,
        "font_weight": "bold",
        "text_color": "#C7D2FE",
      } as any),
      new DivText({
        "text": "Each button synthesises a real waveform through AudioTrack and reports the frequency it actually played.",
        "font_size": 11,
        "text_color": "#5B21B6",
      } as any),
      new DivText({
        "text": "Tone: @{user.beeper_tone} • Frequency: @{user.beeper_frequency} Hz • Status: @{user.beeper_status}",
        "font_size": 12,
        "text_color": "#EEF2FF",
        "font_family": "monospace",
      } as any),
      new DivContainer({
        "orientation": "horizontal",
        "item_spacing": 8,
        "items": [
          {
            "type": "button",
            "weight": 1,
            "id": "beeper_ring",
            "text": "Ring (1200Hz)",
            "icon": "volume",
            "background_color": "#4338CA",
            "text_color": "#FFFFFF",
            "corner_radius": 8,
            "action": {
              "log_id": "beeper_ring",
              "url": "tools://beeper/tone",
              "payload": {
                "frequency_hz": 1200,
                "duration_ms": 250,
                "volume": 0.85,
                "waveform": "sine",
                "output": {
                  "status": "user.beeper_status",
                  "frequency_hz": "user.beeper_frequency",
                },
              },
            },
          },
          {
            "type": "button",
            "weight": 1,
            "id": "beeper_alert",
            "text": "Alert (2400Hz)",
            "icon": "beeper",
            "background_color": "#D97706",
            "text_color": "#FFFFFF",
            "corner_radius": 8,
            "action": {
              "log_id": "beeper_alert",
              "url": "tools://beeper/alert",
              "payload": {
                "frequency_hz": 2400,
                "duration_ms": 160,
                "volume": 0.9,
                "waveform": "square",
                "output": {
                  "status": "user.beeper_status",
                  "frequency_hz": "user.beeper_frequency",
                },
              },
            },
          },
          {
            "type": "button",
            "weight": 1,
            "id": "beeper_error",
            "text": "Error (600Hz)",
            "icon": "notification",
            "background_color": "#DC2626",
            "text_color": "#FFFFFF",
            "corner_radius": 8,
            "action": {
              "log_id": "beeper_error",
              "url": "tools://beeper/tone",
              "payload": {
                "frequency_hz": 600,
                "duration_ms": 320,
                "volume": 0.95,
                "waveform": "sawtooth",
                "output": {
                  "status": "user.beeper_status",
                  "frequency_hz": "user.beeper_frequency",
                },
              },
            },
          },
        ],
      } as any),
      new DivContainer({
        "orientation": "horizontal",
        "item_spacing": 8,
        "items": [
          {
            "type": "button",
            "weight": 1,
            "id": "beeper_chime",
            "text": "Chime (rising)",
            "icon": "check",
            "background_color": "#0F766E",
            "text_color": "#FFFFFF",
            "corner_radius": 8,
            "action": {
              "log_id": "beeper_chime",
              "url": "tools://beeper/chime",
              "payload": {
                "volume": 0.8,
                "waveform": "triangle",
                "output": {
                  "status": "user.beeper_status",
                  "profile": "user.beeper_tone",
                },
              },
            },
          },
          {
            "type": "button",
            "weight": 1,
            "id": "beeper_stop",
            "text": "Stop",
            "icon": "clear",
            "background_color": "#334155",
            "text_color": "#FFFFFF",
            "corner_radius": 8,
            "action": {
              "log_id": "beeper_stop",
              "url": "tools://beeper/stop",
              "payload": {
                "output": {
                  "status": "user.beeper_status",
                },
              },
            },
          },
        ],
      } as any),
    ],
  } as any),
  "clipboard_push": new DivContainer({
    "orientation": "vertical",
    "background_color": "#0F291E",
    "corner_radius": 12,
    "paddings": {
      "left": 12,
      "right": 12,
      "top": 10,
      "bottom": 10,
    },
    "border": {
      "corner_radius": 12,
      "stroke": {
        "color": "#047857",
        "width": 1,
      },
    },
    "items": [
      new DivText({
        "text": "📋 CLIPBOARD & REALTIME NOTIFICATIONS (tools://clipboard & tools://push)",
        "font_size": 11,
        "font_weight": "bold",
        "text_color": "#6EE7B7",
      } as any),
      new DivText({
        "text": "Clipboard: @{user.clipboard_content} • Alert: @{user.last_push_alert}",
        "font_size": 12,
        "text_color": "#D1FAE5",
        "font_family": "monospace",
      } as any),
      new DivContainer({
        "orientation": "horizontal",
        "item_spacing": 8,
        "items": [
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_clipboard_copy",
                "text": "Copy Receipt",
                "subtitle": "tools://clipboard/copy",
                "icon": "copy",
                "background_color": "#047857",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_clipboard_copy",
                  "url": "tools://clipboard/copy",
                  "payload": {
                    "operation": "copy",
                    "options": {
                      "text": "RECEIPT #8891 - Total $24.50 @ Apex POS Terminal",
                    },
                    "output": {
                      "text": "user.clipboard_content",
                      "length": "user.clipboard_length",
                    },
                  },
                },
              },
            ],
          } as any),
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_push_send_alert",
                "text": "Kitchen Push Alert",
                "subtitle": "tools://push/send_alert",
                "icon": "bell",
                "background_color": "#0D9488",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_push_send_alert",
                  "url": "tools://push/send_alert",
                  "payload": {
                    "operation": "send_alert",
                    "options": {
                      "channel": "kitchen_dispatch",
                      "message": "Order #1042 Ready for Pickup",
                    },
                    "output": {
                      "message": "user.last_push_alert",
                    },
                  },
                },
              },
            ],
          } as any),
        ],
      } as any),
    ],
  } as any),
  "offline_cache": new DivContainer({
    "orientation": "vertical",
    "background_color": "#1C1917",
    "corner_radius": 12,
    "paddings": {
      "left": 12,
      "right": 12,
      "top": 10,
      "bottom": 10,
    },
    "border": {
      "corner_radius": 12,
      "stroke": {
        "color": "#A8A29E",
        "width": 1,
      },
    },
    "items": [
      new DivText({
        "text": "💾 OFFLINE PAGE & SCHEMA CACHE (tools://offline-cache)",
        "font_size": 11,
        "font_weight": "bold",
        "text_color": "#D6D3D1",
      } as any),
      new DivText({
        "text": "Status: @{user.offline_cache_status} • Cached Pages: @{user.offline_cached_pages}",
        "font_size": 12,
        "text_color": "#FAFAF9",
        "font_family": "monospace",
      } as any),
      new DivContainer({
        "orientation": "horizontal",
        "item_spacing": 8,
        "items": [
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_offline-cache_cache",
                "text": "Cache Card",
                "subtitle": "tools://offline-cache/cache",
                "icon": "cloud",
                "background_color": "#57534E",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_offline-cache_cache",
                  "url": "tools://offline-cache/cache",
                  "payload": {
                    "operation": "cache",
                    "options": {
                      "op": "cache",
                      "key": "catalog-page",
                      "content": "{\"items\":[\"Coffee\",\"Bagel\"]}",
                    },
                    "output": {
                      "status": "user.offline_cache_status",
                      "cached_pages": "user.offline_cached_pages",
                    },
                  },
                },
              },
            ],
          } as any),
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_offline-cache_get_stats",
                "text": "Cache Stats",
                "subtitle": "tools://offline-cache/get_stats",
                "icon": "stock",
                "background_color": "#44403C",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_offline-cache_get_stats",
                  "url": "tools://offline-cache/get_stats",
                  "payload": {
                    "operation": "get_stats",
                    "options": {
                      "op": "get_stats",
                    },
                    "output": {
                      "status": "user.offline_cache_status",
                      "cached_pages": "user.offline_cached_pages",
                    },
                  },
                },
              },
            ],
          } as any),
        ],
      } as any),
    ],
  } as any),
  "realtime_ws": new DivContainer({
    "orientation": "vertical",
    "background_color": "#0B1F2A",
    "corner_radius": 12,
    "paddings": {
      "left": 12,
      "right": 12,
      "top": 10,
      "bottom": 10,
    },
    "border": {
      "corner_radius": 12,
      "stroke": {
        "color": "#2DD4BF",
        "width": 1,
      },
    },
    "items": [
      new DivText({
        "text": "📡 REALTIME WEBSOCKET / SSE CHANNEL (tools://realtime-ws)",
        "font_size": 11,
        "font_weight": "bold",
        "text_color": "#5EEAD4",
      } as any),
      new DivText({
        "text": "State: @{user.realtime_connection_state} • Channel: @{user.realtime_channel}",
        "font_size": 12,
        "text_color": "#CCFBF1",
        "font_family": "monospace",
      } as any),
      new DivContainer({
        "orientation": "horizontal",
        "item_spacing": 8,
        "items": [
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_realtime-ws_connect",
                "text": "Connect Stream",
                "subtitle": "tools://realtime-ws/connect • wss:// kitchen_stream",
                "icon": "sync",
                "background_color": "#0F766E",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_realtime-ws_connect",
                  "url": "tools://realtime-ws/connect",
                  "payload": {
                    "operation": "connect",
                    "options": {
                      "channel": "kitchen_stream",
                    },
                    "output": {
                      "connection_state": "user.realtime_connection_state",
                      "channel": "user.realtime_channel",
                    },
                  },
                },
              },
            ],
          } as any),
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_realtime-ws_disconnect",
                "text": "Disconnect",
                "subtitle": "tools://realtime-ws/disconnect",
                "icon": "delete",
                "background_color": "#115E59",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_realtime-ws_disconnect",
                  "url": "tools://realtime-ws/disconnect",
                  "payload": {
                    "operation": "disconnect",
                    "options": {
                      "channel": "kitchen_stream",
                    },
                    "output": {
                      "connection_state": "user.realtime_connection_state",
                      "channel": "user.realtime_channel",
                    },
                  },
                },
              },
            ],
          } as any),
        ],
      } as any),
    ],
  } as any),
  "net": new DivContainer({
    "orientation": "vertical",
    "background_color": "#111C2E",
    "corner_radius": 12,
    "paddings": {
      "left": 12,
      "right": 12,
      "top": 10,
      "bottom": 10,
    },
    "border": {
      "corner_radius": 12,
      "stroke": {
        "color": "#60A5FA",
        "width": 1,
      },
    },
    "items": [
      new DivText({
        "text": "🌐 NETWORK TRANSFER (Ktor) & LAN HUB (tools://net)",
        "font_size": 11,
        "font_weight": "bold",
        "text_color": "#93C5FD",
      } as any),
      new DivText({
        "text": "Start the hub, share a file into it, then fetch the hub's own /health endpoint over loopback. Nothing here is simulated: a real Ktor CIO server is listening while you press these buttons.",
        "font_size": 11,
        "text_color": "#BFDBFE",
      } as any),
      new DivText({
        "text": "Status: @{user.net_status} • Port: @{user.net_hub_port} • Shares: @{user.net_share_count}",
        "font_size": 12,
        "text_color": "#DBEAFE",
        "font_family": "monospace",
      } as any),
      new DivText({
        "text": "HTTP: @{user.net_http_status} • Bytes: @{user.net_bytes}",
        "font_size": 12,
        "text_color": "#DBEAFE",
        "font_family": "monospace",
      } as any),
      new DivContainer({
        "orientation": "horizontal",
        "item_spacing": 8,
        "items": [
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_net_serve",
                "text": "1. Start LAN hub",
                "subtitle": "tools://net/serve • port 8899",
                "icon": "cloud",
                "background_color": "#1D4ED8",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_net_serve",
                  "url": "tools://net/serve",
                  "payload": {
                    "port": "8899",
                    "label": "ApexClient Transfer Hub",
                    "output": {
                      "status": "user.net_status",
                      "port": "user.net_hub_port",
                    },
                  },
                },
              },
            ],
          } as any),
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_net_share",
                "text": "2. Share a file",
                "subtitle": "tools://net/share",
                "icon": "receipt",
                "background_color": "#1E40AF",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_net_share",
                  "url": "tools://net/share",
                  "payload": {
                    "name": "receipt-1042.txt",
                    "content_type": "text/plain",
                    "content_base64": "T3JkZXIgIzEwNDIgLSB0b3RhbCAxOC41MA==",
                    "output": {
                      "status": "user.net_status",
                      "bytes": "user.net_bytes",
                      "count": "user.net_share_count",
                    },
                  },
                },
              },
            ],
          } as any),
        ],
      } as any),
      new DivContainer({
        "orientation": "horizontal",
        "item_spacing": 8,
        "items": [
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_net_get",
                "text": "3. HTTP GET /health",
                "subtitle": "tools://net/get • loopback",
                "icon": "sync",
                "background_color": "#2563EB",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_net_get",
                  "url": "tools://net/get",
                  "payload": {
                    "url": "http://127.0.0.1:8899/health",
                    "timeout_ms": "10000",
                    "output": {
                      "status": "user.net_status",
                      "http_status": "user.net_http_status",
                      "bytes": "user.net_bytes",
                    },
                  },
                },
              },
            ],
          } as any),
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_net_list",
                "text": "4. List shares",
                "subtitle": "tools://net/list",
                "icon": "search",
                "background_color": "#1E3A8A",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_net_list",
                  "url": "tools://net/list",
                  "payload": {
                    "output": {
                      "status": "user.net_status",
                      "count": "user.net_share_count",
                    },
                  },
                },
              },
            ],
          } as any),
        ],
      } as any),
      {
        "type": "button",
        "id": "tool_net_stop",
        "text": "5. Stop LAN hub",
        "subtitle": "tools://net/stop",
        "icon": "clear",
        "background_color": "#334155",
        "text_color": "#FFFFFF",
        "corner_radius": 10,
        "action": {
          "log_id": "exec_tool_net_stop",
          "url": "tools://net/stop",
          "payload": {
            "output": {
              "status": "user.net_status",
              "port": "user.net_hub_port",
              "share_count": "user.net_share_count",
            },
          },
        },
      },
    ],
  } as any),
  "print": new DivContainer({
    "orientation": "vertical",
    "background_color": "#2A1A2E",
    "corner_radius": 12,
    "paddings": {
      "left": 12,
      "right": 12,
      "top": 10,
      "bottom": 10,
    },
    "border": {
      "corner_radius": 12,
      "stroke": {
        "color": "#E879F9",
        "width": 1,
      },
    },
    "items": [
      new DivText({
        "text": "🖨️ BLUETOOTH THERMAL PRINTER BRIDGE (tools://print)",
        "font_size": 11,
        "font_weight": "bold",
        "text_color": "#F0ABFC",
      } as any),
      new DivText({
        "text": "Status: @{user.print_status} • Devices: @{user.print_device_count} • Bytes: @{user.print_bytes}",
        "font_size": 12,
        "text_color": "#FAE8FF",
        "font_family": "monospace",
      } as any),
      new DivContainer({
        "orientation": "horizontal",
        "item_spacing": 8,
        "items": [
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_print_scan",
                "text": "Scan Printers",
                "subtitle": "tools://print/scan • RFCOMM SPP",
                "icon": "printer",
                "background_color": "#A21CAF",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_print_scan",
                  "url": "tools://print/scan",
                  "payload": {
                    "operation": "scan",
                    "options": {
                      "op": "scan",
                    },
                    "output": {
                      "status": "user.print_status",
                      "count": "user.print_device_count",
                    },
                  },
                },
              },
            ],
          } as any),
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_print_print",
                "text": "Print ESC/POS Receipt",
                "subtitle": "tools://print/print • 58mm thermal",
                "icon": "receipt",
                "background_color": "#86198F",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_print_print",
                  "url": "tools://print/print",
                  "payload": {
                    "operation": "print",
                    "options": {
                      "text": "APEX POS • Apex POS Terminal\nOrder #1042  TOTAL $24.50",
                      "align": "left",
                      "feed_lines": "3",
                    },
                    "output": {
                      "status": "user.print_status",
                      "bytes": "user.print_bytes",
                    },
                  },
                },
              },
            ],
          } as any),
        ],
      } as any),
    ],
  } as any),
  "screen": new DivContainer({
    "orientation": "vertical",
    "background_color": "#0F172A",
    "corner_radius": 12,
    "paddings": {
      "left": 12,
      "right": 12,
      "top": 10,
      "bottom": 10,
    },
    "border": {
      "corner_radius": 12,
      "stroke": {
        "color": "#22D3EE",
        "width": 1,
      },
    },
    "items": [
      new DivText({
        "text": "🖥 SCREEN / KEEP-AWAKE • SCREENSHOT • RECORDING (tools://screen)",
        "font_size": 11,
        "font_weight": "bold",
        "text_color": "#67E8F9",
      } as any),
      new DivText({
        "text": "Status: @{user.screen_status} • Awake: @{user.screen_keep_awake} • Recording: @{user.screen_recording} • @{user.screen_error}",
        "font_size": 12,
        "text_color": "#E0F2FE",
        "font_family": "monospace",
      } as any),
      new DivContainer({
        "orientation": "horizontal",
        "item_spacing": 8,
        "items": [
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_screen_keep_awake",
                "text": "Hold Screen Awake",
                "subtitle": "tools://screen/keep-awake • 5 min",
                "icon": "bell",
                "background_color": "#0284C7",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_screen_keep_awake",
                  "url": "tools://screen/keep-awake",
                  "payload": {
                    "options": {
                      "action": "enable",
                      "level": "screen",
                      "timeout_ms": "300000",
                    },
                  },
                },
              },
            ],
          } as any),
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_screen_release_awake",
                "text": "Release Wake-Lock",
                "subtitle": "tools://screen/keep-awake • disable",
                "icon": "volume_off",
                "background_color": "#075985",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_screen_release_awake",
                  "url": "tools://screen/keep-awake",
                  "payload": {
                    "options": {
                      "action": "disable",
                    },
                  },
                },
              },
            ],
          } as any),
        ],
      } as any),
      new DivContainer({
        "orientation": "horizontal",
        "item_spacing": 8,
        "items": [
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_screen_screenshot",
                "text": "Capture Screenshot",
                "subtitle": "tools://screen/screenshot • PNG",
                "icon": "camera",
                "background_color": "#0E7490",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_screen_screenshot",
                  "url": "tools://screen/screenshot",
                  "payload": {
                    "options": {
                      "format": "png",
                      "quality": "95",
                    },
                  },
                },
              },
            ],
          } as any),
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_screen_record_start",
                "text": "Start Recording",
                "subtitle": "tools://screen/recording • start",
                "icon": "scan",
                "background_color": "#BE123C",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_screen_record_start",
                  "url": "tools://screen/recording",
                  "payload": {
                    "options": {
                      "action": "start",
                    },
                  },
                },
              },
            ],
          } as any),
        ],
      } as any),
      new DivContainer({
        "orientation": "horizontal",
        "item_spacing": 8,
        "items": [
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_screen_record_stop",
                "text": "Stop Recording",
                "subtitle": "tools://screen/recording • stop",
                "icon": "close",
                "background_color": "#7F1D1D",
                "text_color": "#FFFFFF",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_screen_record_stop",
                  "url": "tools://screen/recording",
                  "payload": {
                    "options": {
                      "action": "stop",
                    },
                  },
                },
              },
            ],
          } as any),
          new DivContainer({
            "weight": 1,
            "items": [
              new DivText({
                "text": "Last file: @{user.screen_media_uri}",
                "font_size": 10,
                "text_color": "#A5F3FC",
                "font_family": "monospace",
                "max_lines": 2,
              } as any),
            ],
          } as any),
        ],
      } as any),
    ],
  } as any),
  "files": new DivContainer({
    "orientation": "vertical",
    "background_color": "#0B1220",
    "corner_radius": 12,
    "paddings": {
      "left": 12,
      "right": 12,
      "top": 10,
      "bottom": 10,
    },
    "border": {
      "corner_radius": 12,
      "stroke": {
        "color": "#38BDF8",
        "width": 1,
      },
    },
    "items": [
      new DivText({
        "text": "🗂️ WORKSPACE FILES (tools://files) — storage.files@1",
        "font_size": 11,
        "text_color": "#7DD3FC",
        "font_weight": "bold",
      } as any),
      new DivText({
        "text": "Bytes in, bytes out, scoped to this tenant's sandbox. Paths are relative to the workspace root and cannot escape it.",
        "font_size": 11,
        "text_color": "#94A3B8",
      } as any),
      new DivText({
        "text": "Status: @{user.files_status}",
        "font_size": 11,
        "text_color": "#E0F2FE",
        "font_family": "monospace",
      } as any),
      new DivText({
        "text": "Path: @{user.files_path}",
        "font_size": 11,
        "text_color": "#E0F2FE",
        "font_family": "monospace",
      } as any),
      new DivText({
        "text": "Workspace root",
        "font_size": 11,
        "text_color": "#BAE6FD",
        "font_weight": "bold",
      } as any),
      new DivContainer({
        "orientation": "horizontal",
        "item_spacing": 8,
        "items": [
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_files_list",
                "text": "List Root",
                "subtitle": "Enumerate entries",
                "icon": "folder",
                "background_color": "#0369A1",
                "text_color": "#F8FAFC",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_files_list",
                  "url": "tools://files/list",
                  "payload": {
                    "operation": "list",
                    "options": {
                      "op": "list",
                    },
                    "output": {
                      "status": "user.files_status",
                      "listing": "user.files_listing",
                      "path": "user.files_path",
                    },
                  },
                },
              },
            ],
          } as any),
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_files_mkdir",
                "text": "Create Folder",
                "subtitle": "mkdir notes",
                "icon": "folder",
                "background_color": "#075985",
                "text_color": "#F8FAFC",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_files_mkdir",
                  "url": "tools://files/mkdir",
                  "payload": {
                    "operation": "mkdir",
                    "options": {
                      "op": "mkdir",
                      "path": "notes",
                    },
                    "output": {
                      "status": "user.files_status",
                      "path": "user.files_path",
                    },
                  },
                },
              },
            ],
          } as any),
        ],
      } as any),
      new DivText({
        "text": "Write & read",
        "font_size": 11,
        "text_color": "#BAE6FD",
        "font_weight": "bold",
      } as any),
      new DivContainer({
        "orientation": "horizontal",
        "item_spacing": 8,
        "items": [
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_files_write",
                "text": "Write Text File",
                "subtitle": "Base64-encoded payload",
                "icon": "edit",
                "background_color": "#0F766E",
                "text_color": "#F8FAFC",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_files_write",
                  "url": "tools://files/write",
                  "payload": {
                    "operation": "write",
                    "options": {
                      "op": "write",
                      "path": "notes/hello.txt",
                      "content": "SGVsbG8gZnJvbSB0aGUgd29ya3NwYWNlIGZpbGUgc3RvcmUu",
                    },
                    "output": {
                      "status": "user.files_status",
                      "path": "user.files_path",
                    },
                  },
                },
              },
            ],
          } as any),
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_files_read",
                "text": "Read It Back",
                "subtitle": "Return decoded text",
                "icon": "scanner",
                "background_color": "#115E59",
                "text_color": "#F8FAFC",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_files_read",
                  "url": "tools://files/read",
                  "payload": {
                    "operation": "read",
                    "options": {
                      "op": "read",
                      "path": "notes/hello.txt",
                    },
                    "output": {
                      "status": "user.files_status",
                      "content": "user.files_content",
                      "path": "user.files_path",
                    },
                  },
                },
              },
            ],
          } as any),
        ],
      } as any),
      new DivText({
        "text": "Copy, move, delete",
        "font_size": 11,
        "text_color": "#BAE6FD",
        "font_weight": "bold",
      } as any),
      new DivContainer({
        "orientation": "horizontal",
        "item_spacing": 8,
        "items": [
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_files_copy",
                "text": "Copy",
                "subtitle": "notes/hello.txt → notes/copy.txt",
                "icon": "clipboard",
                "background_color": "#7C3AED",
                "text_color": "#F8FAFC",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_files_copy",
                  "url": "tools://files/copy",
                  "payload": {
                    "operation": "copy",
                    "options": {
                      "op": "copy",
                      "from": "notes/hello.txt",
                      "to": "notes/copy.txt",
                    },
                    "output": {
                      "status": "user.files_status",
                      "path": "user.files_path",
                    },
                  },
                },
              },
            ],
          } as any),
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_files_move",
                "text": "Move",
                "subtitle": "notes/copy.txt → notes/moved.txt",
                "icon": "refresh",
                "background_color": "#6D28D9",
                "text_color": "#F8FAFC",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_files_move",
                  "url": "tools://files/move",
                  "payload": {
                    "operation": "move",
                    "options": {
                      "op": "move",
                      "from": "notes/copy.txt",
                      "to": "notes/moved.txt",
                    },
                    "output": {
                      "status": "user.files_status",
                      "path": "user.files_path",
                    },
                  },
                },
              },
            ],
          } as any),
        ],
      } as any),
      new DivContainer({
        "orientation": "horizontal",
        "item_spacing": 8,
        "items": [
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_files_stat",
                "text": "Stat",
                "subtitle": "Size and mtime",
                "icon": "database",
                "background_color": "#B45309",
                "text_color": "#F8FAFC",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_files_stat",
                  "url": "tools://files/stat",
                  "payload": {
                    "operation": "stat",
                    "options": {
                      "op": "stat",
                      "path": "notes/moved.txt",
                    },
                    "output": {
                      "status": "user.files_status",
                      "path": "user.files_path",
                    },
                  },
                },
              },
            ],
          } as any),
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "tool_files_delete",
                "text": "Delete",
                "subtitle": "Remove notes/moved.txt",
                "icon": "close",
                "background_color": "#991B1B",
                "text_color": "#F8FAFC",
                "corner_radius": 10,
                "action": {
                  "log_id": "exec_tool_files_delete",
                  "url": "tools://files/delete",
                  "payload": {
                    "operation": "delete",
                    "options": {
                      "op": "delete",
                      "path": "notes/moved.txt",
                    },
                    "output": {
                      "status": "user.files_status",
                      "path": "user.files_path",
                    },
                  },
                },
              },
            ],
          } as any),
        ],
      } as any),
      new DivText({
        "text": "Listing",
        "font_size": 11,
        "text_color": "#BAE6FD",
        "font_weight": "bold",
      } as any),
      new DivText({
        "text": "@{user.files_listing}",
        "font_size": 11,
        "text_color": "#E0F2FE",
        "font_family": "monospace",
      } as any),
      new DivText({
        "text": "Content",
        "font_size": 11,
        "text_color": "#BAE6FD",
        "font_weight": "bold",
      } as any),
      new DivText({
        "text": "@{user.files_content}",
        "font_size": 11,
        "text_color": "#E0F2FE",
        "font_family": "monospace",
      } as any),
    ],
  } as any),
  "native_icons": new DivContainer({
    "orientation": "vertical",
    "background_color": "#1E1B4B",
    "corner_radius": 12,
    "paddings": {
      "left": 12,
      "right": 12,
      "top": 10,
      "bottom": 10,
    },
    "border": {
      "corner_radius": 12,
      "stroke": {
        "color": "#818CF8",
        "width": 1,
      },
    },
    "items": [
      new DivText({
        "text": "🎨 NATIVE ICON SCHEME (icons://native/<name> • 285 icons)",
        "font_size": 11,
        "font_weight": "bold",
        "text_color": "#5B21B6",
      } as any),
      new DivText({
        "text": "Resolved: @{user.icon_name} • Material: @{user.icon_material} • Category: @{user.icon_category}",
        "font_size": 12,
        "text_color": "#E0E7FF",
        "font_family": "monospace",
      } as any),
      new DivContainer({
        "orientation": "horizontal",
        "item_spacing": 10,
        "items": [
          new DivImage({
            "image_url": "icons://native/cart",
            "size": 26,
            "tint": "#C7D2FE",
          } as any),
          new DivImage({
            "image_url": "icons://native/bell",
            "size": 26,
            "tint": "#C7D2FE",
          } as any),
          new DivImage({
            "image_url": "icons://native/receipt",
            "size": 26,
            "tint": "#C7D2FE",
          } as any),
          new DivImage({
            "image_url": "icons://native/payment",
            "size": 26,
            "tint": "#C7D2FE",
          } as any),
          new DivImage({
            "image_url": "icons://native/stock",
            "size": 26,
            "tint": "#C7D2FE",
          } as any),
          new DivImage({
            "image_url": "icons://native/printer",
            "size": 26,
            "tint": "#C7D2FE",
          } as any),
          new DivImage({
            "image_url": "icons://native/scan",
            "size": 26,
            "tint": "#C7D2FE",
          } as any),
          new DivImage({
            "image_url": "icons://native/warehouse",
            "size": 26,
            "tint": "#C7D2FE",
          } as any),
        ],
      } as any),
      new DivContainer({
        "orientation": "horizontal",
        "item_spacing": 8,
        "items": [
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "icon_cart",
                "text": "Cart",
                "icon": "icons://native/cart",
                "background_color": "#4338CA",
                "text_color": "#F8FAFC",
                "corner_radius": 8,
                "action": {
                  "log_id": "icon_pick_cart",
                  "url": "icons://native/cart",
                },
              },
            ],
          } as any),
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "icon_bell",
                "text": "Bell",
                "icon": "icons://native/bell",
                "background_color": "#7C3AED",
                "text_color": "#F8FAFC",
                "corner_radius": 8,
                "action": {
                  "log_id": "icon_pick_bell",
                  "url": "icons://native/bell",
                },
              },
            ],
          } as any),
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "icon_receipt",
                "text": "Receipt",
                "icon": "icons://native/receipt",
                "background_color": "#2563EB",
                "text_color": "#F8FAFC",
                "corner_radius": 8,
                "action": {
                  "log_id": "icon_pick_receipt",
                  "url": "icons://native/receipt",
                },
              },
            ],
          } as any),
          new DivContainer({
            "weight": 1,
            "items": [
              {
                "type": "button",
                "id": "icon_discount",
                "text": "Discount",
                "icon": "icons://native/discount",
                "background_color": "#DB2777",
                "text_color": "#F8FAFC",
                "corner_radius": 8,
                "action": {
                  "log_id": "icon_pick_discount",
                  "url": "icons://native/discount",
                },
              },
            ],
          } as any),
        ],
      } as any),
    ],
  } as any),
  "catalog_list": new DivContainer({
    "orientation": "vertical",
    "item_spacing": 8,
    "items": [
      new DivText({
        "text": "CATALOG · LIVE FROM INVENTORY",
        "font_size": 11,
        "font_weight": "bold",
        "text_color": "#34D399",
      } as any),
      {
        "type": "json_list",
        "data_var": "user.catalog_json",
        "empty_text": "No SKUs in acme_pos_catalog",
        "row_card": new DivContainer({
          "orientation": "horizontal",
          "background_color": "#0F172A",
          "corner_radius": 10,
          "paddings": {
            "left": 12,
            "right": 12,
            "top": 8,
            "bottom": 8,
          },
          "border": {
            "corner_radius": 10,
            "stroke": {
              "color": "#1E293B",
              "width": 1,
            },
          },
          "items": [
            new DivContainer({
              "orientation": "vertical",
              "weight": 1,
              "items": [
                new DivText({
                  "text": "@{item.item_name} (@{item.category})",
                  "font_size": 13,
                  "font_weight": "bold",
                  "text_color": "#F8FAFC",
                } as any),
                new DivText({
                  "text": "SKU: @{item.sku} • $@{item.price_display} • Stock: @{item.stock}",
                  "font_size": 11,
                  "text_color": "#34D399",
                  "font_family": "monospace",
                } as any),
              ],
            } as any),
            {
              "type": "button",
              "weight": 0.4,
              "text": "+1 Order",
              "background_color": "#065F46",
              "text_color": "#A7F3D0",
              "corner_radius": 8,
              "actions": [
                {
                  "log_id": "insert_order",
                  "url": "local-db://op/db.insert",
                  "payload": {
                    "table": "orders",
                    "values": {
                      "item_name": "@{item.item_name}",
                      "sku": "@{item.sku}",
                      "price": "@{item.price}",
                      "qty": 1,
                      "terminal_id": "POS-TERM-01",
                      "status": "OPEN",
                    },
                    "output": {
                      "id": "user.last_order_id",
                    },
                  },
                },
                {
                  "log_id": "refresh_orders_list",
                  "url": "local-db://op/db.query",
                  "payload": {
                    "table": "orders",
                    "order_by": "id",
                    "limit": 50,
                    "json_list_var": "user.orders_json",
                    "output": {
                      "_json": "user.orders_json",
                    },
                  },
                },
                {
                  "log_id": "count_orders",
                  "url": "local-db://op/db.count",
                  "payload": {
                    "table": "orders",
                    "output": {
                      "count": "user.order_count",
                    },
                  },
                },
              ],
            },
          ],
        } as any),
      },
    ],
  } as any),
  "hourly_chart": new DivCustom({
    "custom_type": "sdui_chart",
    "id": "onfly_hourly_area_acme_pos",
    "custom_props": {
      "chart_type": "area",
      "title": "HOURLY POS THROUGHPUT (POS-TERM-01)",
      "subtitle": "Orders per two-hour window today",
      "value_prefix": "$",
      "accent_color": "#0284C7",
      "height": 200,
      "show_grid": true,
      "show_legend": true,
      "points": [
        {
          "label": "09:00",
          "value": 145,
          "color": "#38BDF8",
          "subtitle": "Window 09:00",
        },
        {
          "label": "11:00",
          "value": 310.5,
          "color": "#34D399",
          "subtitle": "Window 11:00",
        },
        {
          "label": "13:00",
          "value": 540,
          "color": "#818CF8",
          "subtitle": "Window 13:00",
        },
        {
          "label": "15:00",
          "value": 390,
          "color": "#F59E0B",
          "subtitle": "Window 15:00",
        },
        {
          "label": "17:00",
          "value": 620.75,
          "color": "#EC4899",
          "subtitle": "Window 17:00",
        },
        {
          "label": "19:00",
          "value": 485.25,
          "color": "#A78BFA",
          "subtitle": "Window 19:00",
        },
      ],
    },
  } as any),
  "sdui_chart": new DivCustom({
    "custom_type": "sdui_chart",
    "id": "onfly_chart_bar_orders",
    "custom_props": {
      "chart_type": "bar",
      "title": "LIVE REVENUE BY SKU (POS-TERM-01)",
      "subtitle": "Live revenue per SKU from today's orders",
      "data_var": "user.orders_json",
      "label_field": "sku",
      "value_field": "price",
      "value_prefix": "$",
      "value_suffix": "",
      "accent_color": "#0284C7",
      "height": 210,
      "show_grid": true,
      "show_legend": true,
      "interactive": true,
    },
  } as any),
  "sheet_showcase": {
    "card": {
      "log_id": "sheet_showcase",
      "variables": [
        {
          "name": "user.sheet_showcase_note",
          "type": "string",
          "value": "",
        },
      ],
      "states": [
        {
          "state_id": 0,
          "div": new DivContainer({
            "orientation": "vertical",
            "item_spacing": 12,
            "items": [
              new DivText({
                "text": "Sheet showcase",
                "font_size": 22,
                "font_weight": "bold",
                "text_color": "#F8FAFC",
              } as any),
              new DivText({
                "text": "This card is presented as a modal bottom sheet through the sheet:// navigation scheme. It is the same DivKit document the workspace host renders, so sheets can carry any card.",
                "font_size": 13,
                "text_color": "#94A3B8",
              } as any),
              new DivText({
                "text": "Present it again with sheet://present?card_id=sheet_showcase, or close it with sheet://dismiss.",
                "font_size": 12,
                "text_color": "#94A3B8",
                "font_family": "monospace",
              } as any),
              new DivText({
                "text": "Close sheet",
                "font_size": 15,
                "font_weight": "bold",
                "text_color": "#FFFFFF",
                "background_color": "#8B5CF6",
                "corner_radius": 12,
                "paddings": {
                  "left": 16,
                  "right": 16,
                  "top": 14,
                  "bottom": 14,
                },
                "actions": [
                  {
                    "type": "div-action",
                    "url": "sheet://dismiss",
                    "log_id": "sheet_showcase_close",
                  },
                ],
                "id": "sheet_showcase_close",
              } as any),
            ],
          } as any),
        },
      ],
    },
  },
} as unknown as Record<string, DivNode>
