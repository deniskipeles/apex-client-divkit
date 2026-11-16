# Architectural Model

```
┌────────────────────────────────────────────────────────────────────────┐
│                        SDUI DOCUMENT / APP                             │
│   (Manager Override, Voids, Refunds, Restricted Configuration)         │
│                                                                        │
│  Calls: tools://biometrics/authenticate                                │
│  Payload: { required_role: "SUPERVISOR" }                              │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                 BIOMETRIC / PIN AUTHORIZATION GATE                     │
│                                                                        │
│  1. Prompt for biometric or PIN confirmation                           │
│  2. Resolve requested role (SUPERVISOR, MANAGER, ...)                   │
│  3. Issue a short-lived auth token on success                          │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼ Generic JSON Output
┌────────────────────────────────────────────────────────────────────────┐
│  {                                                                     │
│    "authenticated": true,                                              │
│    "role": "SUPERVISOR",                                               │
│    "auth_token": "bio_tok_9f2a...",                                    │
│    "method": "BIOMETRIC_OR_PIN_VERIFIED",                              │
│    "state": "ok"                                                       │
│  }                                                                     │
└────────────────────────────────────────────────────────────────────────┘
```

# OS Biometric Authentication (`tools://biometrics` / `auth.biometrics@1`)

The **Biometric Authentication Tool** gates sensitive actions behind a biometric or
PIN challenge. It is domain-agnostic: it authorizes a *role*, returning a token, and
makes no assumptions about what the caller does next.

---

## 1. Parameters & Options

Invoked via:
- `tools://biometrics/authenticate`
- `div-action://terminal.tool.invoke?tool=auth.biometrics@1`

### Options (`payload.options`)

| Option | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `prompt` | `string` | `"Supervisor Authorization Required"` | Message shown in the challenge. |
| `required_role` | `string` | `"SUPERVISOR"` | Role to authorize (e.g. `SUPERVISOR`, `MANAGER`). |
| `pin` | `string` | `null` | Optional PIN fallback used when biometrics are unavailable. |

---

## 2. Tool Output Schema

| Field | Type | Description |
| :--- | :--- | :--- |
| `authenticated` | `boolean` | `true` when the challenge succeeded. |
| `role` | `string` | The authorized role. |
| `auth_token` | `string` | Short-lived token for the authorized action. |
| `prompt` | `string` | Prompt that was shown. |
| `method` | `string` | `"BIOMETRIC_OR_PIN_VERIFIED"`. |
| `state` | `string` | `"ok"`. |
| `timestamp` | `integer` | Unix epoch millisecond timestamp. |

Default output mapping: `authenticated → user.biometric_authenticated`.

On failure the tool returns a `Failed` result (`"Supervisor authentication failed"`).

---

## 3. DivKit Usage Examples

### Authorize a Supervisor Override
```json
{
  "type": "button",
  "text": "Void Order",
  "action": {
    "url": "tools://biometrics/authenticate",
    "payload": {
      "options": { "required_role": "SUPERVISOR" },
      "output": { "authenticated": "supervisor_ok", "auth_token": "supervisor_token" }
    }
  }
}
```
