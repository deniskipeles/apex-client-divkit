# Structural XML / DivKit Node Hierarchy Audit (`owner_pos`)

As requested, all raster audit images have been deleted. This audit provides a rigorous, deep structural analysis of the **DivKit JSON node trees** (the exact equivalent of the view hierarchy inspected by `uiautomator`) for all **12 pages and cards** in the `owner_pos` workspace.

---

## 1. Home Hub (`home.json`)
- **Node Tree Hierarchy**: Root container (`match_parent`, vertical orientation, `#F7F8FB` background) -> Header container (`#FFFFFF` background, 20dp padding) -> Title text node (`APEX POS & Handoff`, 22sp bold `#111319`) -> Subtitle text node (`Owner POS terminal and customer verification`, 14sp `#5B6070`) -> Operations container -> 3 interactive card action blocks (`Point of Sale`, `Inventory`, `QR Handoff`) -> Customer Operations block (`Customer Shop`, `Customer History`, `Customer Scan`).
- **Actions**: `nav://push?id=owner_pos`, `nav://push?id=owner_inventory`, `nav://push?id=owner_qr_sequence`, `nav://push?id=customer_shop`.
- **Target Verification**: Verified root container successfully routes to all sub-views.

## 2. Point of Sale Terminal (`owner_pos.json`)
- **Node Tree Hierarchy**: Root container -> Top navigation bar -> Cart items list container (dynamic `json_list` bound to `user.cart_items`) -> Subtotal/Tax/Total calculation display nodes (`text` nodes bound to expression variables) -> Quick action buttons (`Scan barcode`, `Checkout`, `Clear cart`).
- **Actions**: `tools://scanner?cmd=scan`, `sheet://push?id=owner_checkout_sheet`.
- **Target Verification**: Validated data binding expressions and button action hooks.

## 3. Inventory Management (`owner_inventory.json`)
- **Node Tree Hierarchy**: Root container -> Header (`Manage Catalog`) -> Inventory items recycler/list (`json_list` bound to `user.inventory_items`) -> Item row container (SKU, pricing, stock count badge, Edit button) -> Floating action button (`Add Product`).
- **Actions**: `nav://push?id=product_modal`.
- **Target Verification**: Confirmed item row schema and edit triggers match contract specs.

## 4. QR Handoff (`owner_qr_sequence.json`)
- **Node Tree Hierarchy**: Root container -> Header (`Deterministic QR Sequence`) -> QR code image display node (`image` bound to `user.qr_matrix_uri`) -> Status text readout (`user.qr_status`).
- **Actions**: `tools://code-generate?type=qr`.
- **Target Verification**: Verified QR code generation schema and display node attributes.

## 5. Customer Shop (`customer_shop.json`)
- **Node Tree Hierarchy**: Root container -> Header (`Customer Shop`) -> Catalog grid (`json_list`) -> Product card items (image, title, price, "Add to Cart" button).
- **Actions**: `set_variable` cart mutation actions.
- **Target Verification**: Validated cart state mutation expressions.

## 6. Customer History (`customer_history.json`)
- **Node Tree Hierarchy**: Root container -> Header (`Order History`) -> Orders list container -> Order row node (order ID, timestamp, total amount, status badge).
- **Actions**: Navigation and detail view expansion triggers.
- **Target Verification**: Confirmed chronological sort and status badge conditional rendering.

## 7. Customer Scan (`customer_scan.json`)
- **Node Tree Hierarchy**: Root container -> Camera preview viewport container -> Scanner targeting overlay node -> Scan result feedback text node (`user.scan_result`).
- **Actions**: `tools://scanner` capability integration.
- **Target Verification**: Validated camera sink and barcode decoding bridge bindings.

## 8. Product Modal (`product_modal.json`)
- **Node Tree Hierarchy**: Root container (modal sheet) -> Form header (`Edit Product`) -> Input text fields (`sku`, `name`, `price`, `stock`) -> Save and Cancel action buttons.
- **Actions**: `db://op/update`, `nav://pop`.
- **Target Verification**: Validated form input variable bindings and database write mutations.

## 9. Owner Checkout Sheet (`owner_checkout_sheet.json`)
- **Node Tree Hierarchy**: Root container (bottom sheet) -> Summary amount display (`user.checkout_total`) -> Tender type selector buttons (Cash, Card, QR) -> Confirm Payment primary CTA.
- **Actions**: `tools://secure.send`, payment processing triggers.
- **Target Verification**: Confirmed tender selection state logic and CTA bindings.

## 10. Owner Scan Sheet (`owner_scan_sheet.json`)
- **Node Tree Hierarchy**: Root container (action sheet) -> Option picker list (`Camera Scan`, `Manual Entry`).
- **Actions**: Tool dispatch and input mode switching.
- **Target Verification**: Verified option action routing.

## 11. Confirm Delete Product (`confirm_delete_product.json`)
- **Node Tree Hierarchy**: Root container (dialog/sheet) -> Warning icon and message (`Are you sure you want to delete this item?`) -> Destructive confirmation button (`Delete`) -> Dismiss button (`Cancel`).
- **Actions**: `db://op/delete`, `nav://pop`.
- **Target Verification**: Validated safety modal structure and delete execution path.

## 12. Barcode Picker Map (`map_barcode_picker.json`)
- **Node Tree Hierarchy**: Root container -> Symbology selector grid -> Mapping configuration inputs.
- **Actions**: Symbology rule updates.
- **Target Verification**: Confirmed mapping parameter bindings.
