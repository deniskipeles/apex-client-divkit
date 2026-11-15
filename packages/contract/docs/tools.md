# Capability Contract (v1)

A capability is the versioned, stable identifier a DivKit action uses to invoke native
functionality. `tools.json` is the single source of truth for the capability set and is
consumed by every platform's contract test.

Source of truth: `engine.tools.ToolRegistry` / `engine.tools.ToolHandler`.

## Identity rules

- A capability id is `<domain>.<name>@<version>`, e.g. `vision.scan_code@1`.
- `ToolHandler.capability` returns the canonical id.
- The registry also resolves aliases (unversioned ids and legacy names) to the canonical
  handler. Aliases are declared in `tools.json`; the `registry.manifest()` keys are the
  canonical ids plus their aliases.

## Handler contract

```kotlin
interface ToolHandler {
    val capability: String
    val requiredPermissions: Set<String> get() = emptySet()
    suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult
}
```

`ToolResult` is a sealed type; handlers MUST return a value and MUST NOT throw across the
capability boundary:

| Variant | Meaning |
| :--- | :--- |
| `Success(data)` | Completed; `data` is the result map. |
| `Unsupported(tool, reason)` | Capability exists but the operation/argument is not supported. |
| `PermissionDenied(tool, permission)` | A required OS permission is not granted. |
| `Failed(tool, message)` | Runtime failure. |

## Invariant

Every `id` listed in `tools.json` MUST resolve to a registered handler on every platform.
This is enforced by each platform's contract test; a missing handler fails the build.
