# Canonical JSON (v1)

Deterministic JSON canonicalisation shared across Kotlin (Android), Swift (iOS), and
TypeScript (Web). All three implementations MUST produce byte-identical output, because
the output is the signed message in the envelope contract (see `envelope.md`).

Source of truth: `apps/android/src/main/java/engine/crypto/CanonicalJson.kt`.

## Rules

- Object keys sorted lexicographically (by UTF-16 code unit; equivalent for ASCII keys).
- No insignificant whitespace anywhere.
- Integers emitted without a decimal point.
- Doubles emitted with minimal representation; a double whose value is exactly an integer
  and is within the `Long` range is emitted as an integer (`2.0` -> `2`).
- `null` -> `null`; booleans -> `true` / `false`.
- Strings JSON-escaped for `"`, `\`, `\n`, `\r`, `\t`.
- Arrays preserve order; only object keys are sorted.
- Non-finite doubles (NaN / Infinity) are an error.

This is intentionally hand-written rather than delegating to a serialization library, whose
whitespace and key ordering are not guaranteed stable across versions and must match the
edge signer exactly.

## Worked example

Input:

```json
{"z":10.0,"a":"hello","b":[true,false,null]}
```

Canonical bytes (42 bytes, no trailing newline):

```
{"a":"hello","b":[true,false,null],"z":10}
```

Note `10.0` collapses to `10` (whole double), keys are reordered `a`, `b`, `z`, and array
order is preserved.

## Fixtures

`packages/contract/fixtures/canonical/v1/*.json` are inputs; the sibling
`*.expected.bin` files are the exact expected bytes. Every platform's contract test walks
this directory. Fixtures are the cross-platform source of truth - never duplicate them.
