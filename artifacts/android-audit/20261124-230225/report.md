# Android audit / refactor — session report

Date: 2026-11-24 (sandbox clock drift: artifacts dir reads 20261124-230225)

## What changed in code

1. **Workspace vocabulary is no longer hardcoded** (`engine/state/WorkspaceVariableScope.kt`).
   Any `<scope>.<name>` in a non-reserved scope is writable; `engine|system|internal|runtime`
   stay unreachable. `EngineStateBridge`, the DivKit renderer, `input` nodes and
   `EngineRouter` output mapping now use this rule instead of a `user.*` prefix check.
2. **Per-domain ops deleted.** `engine/db/KioskOps.kt` (430 lines) is gone, as are the
   `kiosk.*` operations. Schema v5 semantics replaced them (`ApexKitSchema.kt`):
   `aggregates`, `canonical`, `lookup`, `references`, `append` (+`StockGuard`).
3. **Generic interpreter** (`engine/db/SchemaOps.kt`): validation (required/type/min/max/
   pattern/unique), aggregate roll-ups (`count()`, `sum(f)`, `sum(a*b)`, `round2`),
   child append with merge key + stock ceiling, quantity set, remove-with-undo,
   scanned-value lookup, delete guard, and canonical transfer packing
   (deterministic payload → SHA-256 → Base45 → numbered chunks; unpack verifies
   order-independence, missing/duplicate/corrupt chunks and checksum).
4. **New ops** (`EngineRegistry`): `db.upsert`, `db.commit`, `db.append_child`,
   `db.set_child_qty`, `db.remove_child`, `lookup.resolve`, `doc.pack`, `doc.append`,
   `doc.unpack`; `db.delete` now honours declared inbound references.
5. **Workspace schema data** (`demo/workspace/collections/*.json`): inventory gained
   `barcode` (indexed), unique SKU and inbound reference; `kiosk_orders` declares its
   roll-ups and canonical transfer view; `kiosk_order_lines` declares its merge key and
   stock guard; new `cust_history` collection.
6. **Manifest-driven startup**: `card.json` may declare `seed_ops`; cards may declare
   `seed_ops`. The ViewModel's hardcoded "query orders / count catalog" block is replaced by
   `runSeedOps` / `runCardSeedOps`.
7. **Neutral layout blocks** (`engine/ui/SduiLayoutBlocks.kt`, wired in
   `DivKitMultiplatform`): `sticky_footer`, `segmented`, `bottom_tabs`, `progress_ring`,
   `text_field`, `scan_chips`, `qr_controls`, `fab`, `toast`, `form_modal`,
   `bottom_sheet`, `camera_viewfinder`, `inline_banner`.
8. **Kiosk cards ported** from `kiosk.*` to the generic ops (customer, owner, transfer, LAN),
   so nothing calls a removed operation. The workflows workspace catalog is rendered as a
   calm single-column list (white surface, hairline border, 20 dp gutter, muted rows).

## Tests

`./gradlew testDebugUnitTest` — **312 tests, 0 failures** (was 310 on `main`).
New: `WorkspaceDataPlaneTest` (12 tests: merge, remove+undo, qty-to-zero, stock ceiling,
empty-commit refusal, commit lock, deterministic packing, chunk set errors, duplicate SKU,
lookup, delete guard, op registration), `WorkspaceVariableScopeTest` (4).
`./gradlew assembleDebug` — **BUILD SUCCESSFUL**.

## Emulator evidence

Installed the universal debug APK on a 1080x1920 android-28 x86_64 AVD (`-accel off`, no KVM).

* `screenshots/01-launch.png`, `02-launch.png` — cold launch after `pm clear`; hub renders,
  both workspaces listed, bottom nav intact.
* `screenshots/03-kiosk-entry.png` — kiosk workspace entry **before** the change:
  four saturated rainbow tiles (purple/teal/green/orange), repeated "Open card" subtitles.
* `screenshots/04-kiosk-entry-after.png` — captured after a clean uninstall/reinstall.
  **The entry card still shows the old tiles**: the kiosk hub body is built in
  `WorkspaceProvisioner.buildDefaultDivKitCardJson` (Kotlin), not from `tool_catalog.json`,
  so the catalog restyle only affected the tools workspace. The intended calm
  single-column list is implemented in `DemoCardRepository.catalogContainer` but the kiosk
  hub does not use it yet.
* `logcat/05-kiosk-customer.txt`, `ui-dumps/05-kiosk-customer.xml` — after tapping the
  first tile the UI tree still shows the hub, i.e. the tap did not open the customer card
  in this run.

## Not done / blocked (honest status)

* **Kiosk visual redesign: not delivered.** The kiosk entry card is still assembled in
  Kotlin with per-workflow colours. Moving it to `demo/<id>/workspace.json` + the neutral
  blocks is the next step.
* **Kiosk end-to-end flows (customer/owner/QR/LAN) not re-verified on device.** The ops they
  need exist and are unit-tested, but no clean-install screenshots of a completed order,
  receipt, QR set or LAN share exist yet.
* **Libsignal card** was not re-tested in this session; its contracts are unchanged and its
  tests pass.
* **Full per-card audit of the 24 tool cards** was not performed in this session.
* Emulator with `-accel off` is very slow (System UI ANR on first boot); a full
  tap-every-button pass needs a KVM-backed emulator or a physical device.
