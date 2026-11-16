# DivKit Variable Namespace (v1)

Source of truth: `EngineStateBridge.pushUpdate` in
`apps/android/src/main/java/engine/state/EngineStateBridge.kt`.

## Rule

Every DivKit variable written by the engine MUST be namespaced under `user.`.

`pushUpdate` and the batch path both enforce this:

```kotlin
fun pushUpdate(name: String, value: DivVariableValue) {
    require(name.startsWith("user.")) { "Variable must be user.*-namespaced: $name" }
    // ...
}
```

## Rationale

The `user.*` namespace is the only region of the DivKit variable store the engine is
permitted to mutate. A compromised or malformed remote payload cannot address engine-owned
or framework-owned variables, because a non-`user.`-prefixed name fails the `require`.

## Notes

- Tool output mappings declare their target variable in `defaultOutputMapping` (see
  `tools.md`); every target must be `user.`-prefixed.
- `ToolContext.variables` applies the same rule defensively, prepending `user.` when a
  caller passes a bare key.
