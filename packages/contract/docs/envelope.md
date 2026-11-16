# Signed Envelope (v1)

Remote DivKit payloads are wrapped in a signed envelope and verified before any JSON
reaches the renderer.

Source of truth: `apps/android/src/main/java/engine/crypto/PayloadVerifier.kt`.

## Envelope JSON shape

```json
{
  "tenant": "acme",
  "schema_version": "1.0",
  "iat": 1712345678901,
  "jti": "uuid-v4",
  "payload": { },
  "signature": "base64-ed25519"
}
```

- `tenant` - tenant the payload is bound to.
- `schema_version` - DivKit schema version string.
- `iat` - issued-at, epoch milliseconds.
- `jti` - unique envelope id (UUID v4) used for replay detection.
- `payload` - the DivKit document.
- `signature` - base64 Ed25519 signature.

## Signature input (canonical bytes)

The signature is computed over the canonical bytes of a map containing **only** these keys:

```
tenant, schema_version, iat, jti, payload
```

`payload` is the decoded JSON value (objects become maps, arrays become lists, numbers
become `Long`/`Double`). The map is canonicalised per `canonical-json.md` and encoded
UTF-8. The `signature` field itself is never part of the signed message.

Verification steps, in order:

1. Decode the envelope (unknown keys rejected).
2. Reject if `tenant != expectedTenant` (`Tenant mismatch`).
3. Rebuild the canonical map and recompute the bytes.
4. Ed25519-verify the bytes against the configured public key.
5. Reject if `|now - iat| > maxSkewMs` (`Envelope expired`).
6. Reject if `jti` was already seen (`Replay detected`).

Any failure throws `SecurityException`.

## Constants

- `maxSkewMs = 300_000` (5 minutes) - maximum permitted `|now - iat|`.
- `maxSeenJti = 10_000` - size of the seen-`jti` LRU; the oldest entry is evicted when the
  set is full.

## Tenant binding

The verifier is constructed with an `expectedTenant` and rejects any envelope whose
`tenant` differs. A payload signed for one tenant can never be replayed against another,
even with a valid signature.
