# Tool conformance cases

Every `tools://` capability ships a declarative **case list**. The same list has three
consumers, so a capability cannot be "tested" in one place and shipped from another:

| Consumer | What it does | Runs in |
| --- | --- | --- |
| Kotlin `ToolConformanceTest` | drives the real `NativeToolsSuite` for every case, asserts `success`, `status` and payload matchers | `./gradlew testDevDebugUnitTest` |
| `apex_tools_test` workspace | renders one card per module with one button per case | emulator / playground |
| `tools/android_ui_debug` | taps those buttons on a device (`scenarios/toolcases.json`) | device |

```
apps/dev-server/tests/tools/definitions.ts   contract table: operations, results, statuses, cases
apps/dev-server/tests/tools/expectations.ts  expected outcome per case
apps/dev-server/tests/cardkit.ts             case -> DivKit card (one button per case)
apps/dev-server/tests/types.ts               schema + validators (used by vitest and the generator)
apps/dev-server/scripts/build-tool-cases.mjs writes the Kotlin fixtures
apps/android/src/test/resources/toolcases/   generated fixtures (do not edit by hand)
apps/android/src/test/java/com/example/ToolConformanceTest.kt
```

## Rules

- A module lists **every operation** its handler accepts, including aliases, because the
  drift test fails when the Kotlin registry exposes an operation the table does not describe.
- A case has one or more `steps`. Each step is a `tools://` URL and a payload. `$prev.field`
  binds a value from the previous step's result, `$prev[2].field` from an earlier step.
  Array and object arguments go at the payload root; only flat strings belong in `options`
  (that is how `EngineRouter` builds card payloads).
- A case declares an expectation: `success`, `status`, and matchers on `data` and `outputs`.
  Matchers are `equals`, `nonEmpty`, `containing`, `startsWith`, `regex`, `min`, `max`,
  `oneOf`.
- Cases that need a host (`camera`, image source, biometric prompt, websocket transport)
  declare `host: 'fake' | 'none'`. `fake` installs a deterministic stand-in sink; `none`
  asserts the fail-closed path.
- Add a case before you add a button: `build-tool-cases.mjs` fails when a card action has no
  case, and so does `toolConformance_cardActionsAreCovered`.

## Adding a capability

1. Implement the handler and register it in `engine/tools/TerminalApp.kt`,
   `engine/tools/ToolRegistry.kt` and `engine/tools/NativeToolsSuite.kt` (schema, dispatch,
   aliases, output mapping).
2. Add a module to `definitions.ts` with `doc`, `usage`, `operations`, `outputMapping`,
   `options`, `results`, `statuses` and `cases`.
3. Add an expectation per case in `expectations.ts`.
4. Run `pnpm toolcases:build`, then `pnpm test:dev-server` and
   `./gradlew testDevDebugUnitTest`.
5. Commit the regenerated `apps/android/src/test/resources/toolcases/` and the
   `apex_tools_test` demo cards together with the source change.

## Statuses are a contract

`status` is what the card shows, and `NativeToolsSuite` derives `success` from
`FAILURE_STATUSES`. Adding a status means deciding which side of that line it belongs to:
`INVALID` (a rejected JSON document), `MISMATCH` (a safety-number comparison that did not
match) and `DENIED` are failures; `CANCELLED`, `EMPTY`, `PARTIAL`, `STAGED` and
`NOT_RUNNING` are successful outcomes.

## Fail-closed paths are part of the contract

The table asserts the negative paths as first-class cases, not as an afterthought:

- `camera`, `mobilenetv4`, `u2net`, `image-manipulation`, `scan` OCR with no image source
  report `NO_INPUT`/`UNSUPPORTED` and never synthesise a frame or a prediction.
- `print` with no receipt is `FAILED`; with a receipt but no open socket it is
  `UNSUPPORTED`. It used to report a successful 12-byte job.
- `realtime-ws` needs an installed transport; without one `connect` is `UNSUPPORTED`
  instead of reporting `CONNECTED` with a fabricated latency.
- `codec json/validate` on a document that does not parse is `INVALID`, not `SUCCESS`.
- `net` unreachable endpoints report `FAILED` with the transport error.
