# Contract Package

Cross-platform contract identity for the three terminal targets (Android, iOS, Web).
This package holds **no executable code**. It exists so all three platforms agree on the
same identifiers, byte formats, and wire shapes without sharing a code module.

## Why fixtures, not shared code

The former `shared/` KMP module was deleted. It had diverged from the Android app (the app
copies were newer and larger), so it was a stale duplicate rather than a single source of
truth. Maintaining one codebase that three toolchains compile is expensive and repeatedly
drifted. Instead each platform keeps its own idiomatic implementation, and this package
pins the *observable contract*:

- Every platform runs the same **fixtures** (`fixtures/`).
- Every platform asserts the same **capability set** (`tools.json`).
- The prose contracts below describe behaviour that fixtures cannot fully capture.

If a platform's implementation disagrees with a fixture or `tools.json`, that platform's
contract test fails. The contract, not the code, is shared.

## Contents

| Path | Purpose |
| :--- | :--- |
| `tools.json` | Source of truth for capability ids and aliases. |
| `fixtures/canonical/v1/` | Canonical JSON inputs and expected bytes. |
| `fixtures/navigation/v1/` | Navigation URL parse fixtures (`nav://`, `sheet://`). |
| `docs/canonical-json.md` | Deterministic JSON canonicalisation rules. |
| `docs/envelope.md` | Signed envelope shape and verification. |
| `docs/sync-batch.md` | Sync request/response and retry rules. |
| `docs/schema.md` | Workspace SQLite schema. |
| `docs/variables.md` | DivKit `user.*` variable namespace rule. |
| `docs/navigation.md` | Card navigation URL schemes and state-preservation rules. |
| `docs/tools.md` | Capability identity and handler contract. |
| `docs/api-surface.md` | Cross-platform engine API surface. |
| `../divkit-subset/schema.json` | Permitted DivKit component subset. |

## Invariants

1. Canonical JSON output is byte-identical across platforms for every fixture.
2. A signed envelope verifies on all platforms or on none; signature input excludes the
   `signature` field.
3. `user.*` is the only variable namespace the engine may write.
4. Every `id` in `tools.json` resolves to a registered handler on every platform.

## Adding a capability

1. Add the id and aliases to `tools.json`.
2. Implement the handler on each platform.
3. The contract tests on each platform fail until all platforms resolve the id.
