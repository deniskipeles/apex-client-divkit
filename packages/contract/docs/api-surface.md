# Engine API Surface (v1)

The public engine API each platform reimplements. Names and semantics are contract; the
implementation language differs per platform.

## Engine

- `execute(url: String, payload: Json, tenantId: String) -> ToolExecutionOutcome`
  - Parses `tools://<tool>/<operation>` (and `tool://`), resolves the tool schema, applies
    default options and output mapping, dispatches, and returns the outcome.
  - `ToolExecutionOutcome`: `success`, `toolId`, `operation`, `summary`, `latencyMs`,
    `resultData` (JSON), `outputVariables` (map of `user.*` variable -> value).

## Tool registry

- `manifest() -> Map<String, Int>` - canonical capability ids and aliases, each mapped to
  its version.
- `resolve(capability) -> ToolHandler?` - canonical id or alias to handler.
- `register(capability, handler)` - binds a handler to a canonical id.

## Canonicalisation and crypto

- `canonicalize(value) -> ByteArray` (see `canonical-json.md`).
- `PayloadVerifier(publicKey, expectedTenant).verify(envelopeJson) -> verifiedPayload`
  (see `envelope.md`).

## Sync

- `SyncEngine(workspaceManager, http).sync(workspaceId, endpoint) -> SyncOutcome`
  where `SyncOutcome = Success | Retry` (see `sync-batch.md`).

## Variables

- `EngineStateBridge.pushUpdate(name, value)` - requires `name.startsWith("user.")`
  (see `variables.md`).
