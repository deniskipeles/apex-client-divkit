# Universal E2EE Cryptographic Tool (`tools://libsignal` / `crypto.signal@1`)

The **Universal Signal Protocol Tool** provides generic end-to-end encryption (E2EE) and forward-secret session ratcheting for native Server-Driven UIs. 

Like the W3C `WebCrypto` API in web browsers, it operates as an unopinionated native primitive: it manages cryptographic identities, prekey bundles, Diffie-Hellman ratchets, and AES-256-GCM ciphertexts without knowing anything about the underlying business domain.

---

## 1. Architectural Model

```
┌────────────────────────────────────────────────────────────────────────┐
│                        SDUI DOCUMENT / APP                             │
│  (Healthcare Patient Records, Courier Dispatches, Financial Transfers) │
│                                                                        │
│  Calls: tools://libsignal/encrypt                                      │
│  Payload: { peer_id: "agent_42", data: "RAW_CONFIDENTIAL_PAYLOAD" }   │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│             NATIVE ENGINE BROWSER RUNTIME (`tools://libsignal`)        │
│                                                                        │
│  1. Curve25519 (X25519) Diffie-Hellman Key Exchange                    │
│  2. HKDF-SHA256 Derivation of Root and Chain Keys                      │
│  3. Double Ratchet Step (Forward Secrecy & Break-in Recovery)          │
│  4. AES-256-GCM Authenticated Encryption with 12-byte random IV        │
│  5. Safety Number / Fingerprint Generation (SHA-512)                   │
│  6. Origin Sandbox Isolation (`workspace_<tenantId>.db`)               │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Invocation URIs & Schemes

| Scheme URI | Operation | Description |
| :--- | :--- | :--- |
| `tools://libsignal/setup_identity` | `setup_identity` | Generates or loads terminal X25519 Identity Key and Signed PreKey. |
| `tools://libsignal/export_bundle` | `export_bundle` | Exports public PreKeyBundle for sharing with peer endpoints. |
| `tools://libsignal/create_session` | `create_session` | Starts a fresh X3DH + Double Ratchet handshake with a peer, replacing any stored ratchet. |
| `tools://libsignal/encrypt` | `encrypt` | Ratchets sending key forward, encrypts payload via AES-256-GCM. |
| `tools://libsignal/decrypt` | `decrypt` | Decrypts inbound ciphertext and ratchets receiving key. Detects PreKey vs. session messages. |
| `tools://libsignal/status` | `status` | Returns session status, message counters, and verification state. |
| `tools://libsignal/verify_identity` | `verify_identity` | Compares and verifies 6-block safety numbers. |
| `tools://libsignal/reset_session` | `reset_session` | Drops both ratchet states and the recorded verification. |
| `div-action://terminal.tool.invoke?tool=crypto.signal@1` | `invoke` | Versioned tool capability invoke interface. |

### Session lifecycle

`create_session` always begins a new handshake. It deletes the local ratchet and the
simulated peer ratchet before processing a fresh PreKeyBundle, so pressing the demo's
"Establish session" button after an app restart never reuses a stale session.

One-time prekeys are consumed by the receiving side, exactly as the Signal protocol
requires. The bundle advertises the lowest **unused** prekey and the store replenishes a
batch when fewer than 20 remain; assuming prekey `#1` always exists caused the second
handshake to fail with `PreKey #1 not found`.

`decrypt` accepts an optional `type` (`2` = `SignalMessage`, `3` = `PreKeySignalMessage`).
When it is omitted the envelope is inspected: a PreKey message carries the sender identity
and base key, a plain session message does not. The previous default of `3` rejected the
second half of a conversation.

---

## 3. Generic Action Contracts & Payloads

### 3.1 Initializing Identity (`setup_identity`)
```json
{
  "url": "tools://libsignal/setup_identity",
  "payload": {
    "output": {
      "registration_id": "device_reg_id",
      "identity_key": "public_identity_b64"
    }
  }
}
```

### 3.2 Establishing a Session with Any Peer (`create_session`)
```json
{
  "url": "tools://libsignal/create_session",
  "payload": {
    "peer_id": "remote_node_01",
    "output": {
      "session_id": "active_session_id",
      "fingerprint": "safety_number"
    }
  }
}
```

### 3.3 Encrypting Arbitrary Data (`encrypt`)
```json
{
  "url": "tools://libsignal/encrypt",
  "payload": {
    "peer_id": "remote_node_01",
    "data": "{\"action\":\"DISPATCH_COURIER\",\"pkg_id\":\"PKG-9021\"}",
    "output": {
      "ciphertext": "encrypted_envelope_b64",
      "counter": "sequence_number"
    }
  }
}
```

### 3.4 Decrypting Inbound Envelope (`decrypt`)
```json
{
  "url": "tools://libsignal/decrypt",
  "payload": {
    "peer_id": "remote_node_01",
    "ciphertext": "@{user.signal_ciphertext}",
    "output": {
      "status": "user.signal_status",
      "plaintext": "user.signal_plaintext",
      "safety_number": "user.signal_fingerprint"
    }
  }
}
```
`type` is optional (`2` or `3`). Leave it out and the engine detects whether the
envelope is a PreKey message or a message on an established session.

A failed decrypt returns `status: "FAILED"` and an `error` field. The host surfaces the
reason in the status banner and in `user.engine_error`; a card can bind `error` explicitly
if it wants to show it inline.

### 3.5 Verifying Safety Numbers (`verify_identity`)
```json
{
  "url": "tools://libsignal/verify_identity",
  "payload": {
    "peer_id": "remote_node_01",
    "provided_fingerprint": "@{scanned_qr_fingerprint}",
    "output": {
      "verified": "is_peer_trusted"
    }
  }
}
```
`provided_fingerprint` (or `safety_number`) is **required**. There is no default: a
missing value fails with `missing safety_number argument to verify` instead of recording
trust. A peer with no established session also fails, because there is no remote identity
to compare against.

`status` is read-only. It reports `NO_SESSION` for a peer that has never handshaked rather
than creating a session as a side effect, and `verify_identity` never mints a fingerprint
for a session that does not exist.

The `secure.*@1` aliases are honest too: `secure.status@1` returns `NO_SESSION`, and
`secure.verify_identity@1` requires `provided_fingerprint`.

Action payload expressions keep long digit strings verbatim. A 60-digit safety number is
an identifier, not a number: coercing `@{user.signal_fingerprint}` to a `Double` rounded it
and made every comparison report `MISMATCH`.

---

## 4. Generic DivKit Component Example

```json
{
  "type": "container",
  "orientation": "vertical",
  "background_color": "#0F172A",
  "corner_radius": 12,
  "paddings": { "left": 12, "right": 12, "top": 10, "bottom": 10 },
  "items": [
    {
      "type": "text",
      "text": "🔒 E2EE SECURE CHANNEL",
      "font_size": 11,
      "font_weight": "bold",
      "text_color": "#38BDF8"
    },
    {
      "type": "text",
      "text": "Session: @{active_session_id} • Safety Number: @{safety_number}",
      "font_size": 12,
      "font_family": "monospace",
      "text_color": "#F8FAFC"
    },
    {
      "type": "button",
      "text": "Establish Double Ratchet Session",
      "action": {
        "url": "tools://libsignal/create_session",
        "payload": {
          "peer_id": "central_hub",
          "output": {
            "session_id": "active_session_id",
            "fingerprint": "safety_number"
          }
        }
      }
    }
  ]
}
```