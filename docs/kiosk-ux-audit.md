# Kiosk UX Audit (`owner_pos`)

This screenshot-driven audit evaluates the end-to-end user experience and rendering fidelity of the `owner_pos` kiosk workspace on Android (`emulator-5554`), driven by live adb interactions (`screencap` capture, touch input, and logcat analysis).

---

## Audit Summary

| Screen / Component | Severity | Evidence | Fix |
| :--- | :--- | :--- | :--- |
| **Workspace Selector / Home** | **Minor** | Workspace card list renders successfully with unread badges, but default padding around icon headers is slightly tight on mdpi viewports. | Increase card inner padding from `12dp` to `16dp` for improved touch affordance. |
| **Cart & Item List (`owner_pos`)** | **Major** | Initial schema migration previously rejected record updates (`1=0` policy), causing blank totals until policy fix. Now resolved. | Ensure policy rules (`read/create/update/delete: 'public'`) are explicitly declared on all operational collections. |
| **Barcode Picker Sheet (`map_barcode_picker`)** | **Critical** | Previously failed with missing sheet ID when invoked from card action triggers. Now resolved via stable sheet identifiers. | Always supply unique string IDs to bottom sheet builders in DivKit custom props. |
| **Delete Confirmation Modal (`confirm_delete_product`)** | **Major** | Foreign key restriction check (`restrict_delete: true`) needs clear inline toast feedback when refusing product deletion for active order lines. | Add toast notification handler in `EngineRouter` when delete is refused by reference guard. |

---

## Detailed Findings

### 1. Home Launcher & Workspace Switcher (`screen_home.png`)
- **Severity**: Minor
- **Evidence**: Captured via `screencap` immediately after launching `com.aistudio.sduiposengine.kmpdiv`. The `owner_pos` workspace card is visible and fully interactive.
- **Fix**: Refine typography hierarchy and card border radius for enhanced visual contrast.

### 2. Kiosk Point of Sale & Cart Execution (`screen_kiosk.png` / `screen_kiosk_scrolled.png`)
- **Severity**: Major (Resolved)
- **Evidence**: Previous policy evaluation returned `1=0` for `orders` updates. With public policies added and `price` typed as numeric, item addition, quantity updates, and cart roll-ups (`line_count`, `total`) calculate and render instantaneously without errors.
- **Fix**: Maintain strict schema typing and explicit policy rules on all tenant collection specs.

### 3. Action Sheet & Modals (`screen_action.png`)
- **Severity**: Critical (Resolved)
- **Evidence**: Bottom sheet invocation for barcode mapping and product deletion crashed due to missing sheet ID parameters. Supplying explicit IDs (`map_barcode_picker_sheet`, `confirm_delete_product_sheet`) successfully stabilized sheet rendering.
- **Fix**: Enforce non-empty identifier checks in `bottomSheet(...)` wrapper functions.
