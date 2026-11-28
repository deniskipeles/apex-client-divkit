# Structural DivKit / XML Node Hierarchy Audit (`owner_pos`)

This document presents the exact structural node definitions, container hierarchies, custom properties, data bindings, and action routing urls extracted directly from the 12 templates under `apps/android/src/dev/resources/demo/owner_pos/tools/`.

---

## 1. Home Hub (`home.json`)
- **Root Container**: `type: "container"`, background `#F7F8FB`, height `match_parent`.
- **Header Block**: 
  - Background: `#FFFFFF`
  - Padding: left 20, right 20, top 24, bottom 20
  - Text Node 1: `"APEX POS & Handoff"`, font_size `22`, font_weight `"bold"`, text_color `"#111319"`
  - Text Node 2: `"Owner POS terminal and customer verification"`, font_size `14`, text_color `"#5B6070"`
- **Operations Section**:
  - Heading: `"Owner Operations"`
  - Cards: `Point of Sale`, `Inventory`, `QR Handoff`
- **Navigation URLs**: `nav://push?id=owner_pos`, `nav://push?id=owner_inventory`, `nav://push?id=owner_qr_sequence`, `nav://push?id=customer_shop`.

## 2. Point of Sale Terminal (`owner_pos.json`)
- **Root Container**: `type: "container"`, background `#F7F8FB`.
- **Toast Component**: 
  - `id: "global_toast"`, `type: "toast"`
  - `visibility: "@{owner.toast_visible ? 'visible' : 'gone'}"`
  - `custom_props`: `text: "@{owner.toast_msg}"`, `severity_var: "owner.toast_severity"`, `dwell_ms: 4000`
- **Gallery / Container Items**:
  - Pending QR Banner: background `#FEF3C7`, corner_radius `12`, text bound to `@{owner.pending_qr_msg}`
  - Cart list items (`json_list`) and calculation summary nodes.
- **Action URLs**: `tools://scanner?cmd=scan`, `sheet://push?id=owner_checkout_sheet`, `local-db://op/pos.toast_action`.

## 3. Inventory Management (`owner_inventory.json`)
- **Root Container**: `type: "container"`, background `#F7F8FB`.
- **Header**: `"Manage Catalog"`
- **Inventory List**: `json_list` bound to `user.inventory_items`.
- **Item Row Components**: SKU text, price display, stock count badge, Edit button.
- **Action URLs**: `nav://push?id=product_modal`, `db://op/inventory`.

## 4. QR Handoff (`owner_qr_sequence.json`)
- **Root Container**: `type: "container"`, background `#F7F8FB`.
- **Header**: `"Deterministic QR Sequence"`
- **Display Node**: `type: "image"` bound to `user.qr_matrix_uri`.
- **Status Readout**: text node bound to `user.qr_status`.
- **Action URLs**: `tools://code-generate?type=qr`.

## 5. Customer Shop (`customer_shop.json`)
- **Root Container**: `type: "container"`, background `#F7F8FB`.
- **Header**: `"Customer Shop"`
- **Catalog Grid**: `json_list` displaying product cards, title, pricing, and "Add to Cart" buttons.
- **Action URLs**: `set_variable` cart mutation actions, `local-db://op/cart`.

## 6. Customer History (`customer_history.json`)
- **Root Container**: `type: "container"`, background `#F7F8FB`.
- **Header**: `"Order History"`
- **Orders List**: Iterative order rows displaying timestamp, total amount, and fulfillment status badge.
- **Action URLs**: Navigation and detail view expansion triggers.

## 7. Customer Scan (`customer_scan.json`)
- **Root Container**: `type: "container"`, background `#F7F8FB`.
- **Viewport**: Camera preview container with targeting overlay.
- **Feedback Node**: Text node bound to `user.scan_result`.
- **Action URLs**: `tools://scanner` capability dispatch.

## 8. Product Modal (`product_modal.json`)
- **Root Container**: Modal sheet container.
- **Form Fields**: Input text fields for `sku`, `name`, `price`, `stock`.
- **Action Buttons**: Save (`db://op/update`) and Cancel (`nav://pop`).

## 9. Owner Checkout Sheet (`owner_checkout_sheet.json`)
- **Root Container**: Bottom sheet container.
- **Summary**: Amount display bound to `user.checkout_total`.
- **Tender Selectors**: Cash, Card, QR buttons.
- **Primary CTA**: Confirm Payment (`tools://secure.send`).

## 10. Owner Scan Sheet (`owner_scan_sheet.json`)
- **Root Container**: Action sheet container.
- **Options List**: Camera Scan and Manual Entry options.
- **Action URLs**: Tool dispatch and input mode switching.

## 11. Confirm Delete Product (`confirm_delete_product.json`)
- **Root Container**: Dialog container.
- **Warning**: Message `"Are you sure you want to delete this item?"`.
- **CTAs**: Destructive Delete (`db://op/delete`) and Dismiss (`nav://pop`).

## 12. Barcode Picker Map (`map_barcode_picker.json`)
- **Root Container**: Container.
- **Grid**: Symbology selector grid and mapping configuration inputs.
- **Action URLs**: Symbology rule updates.
