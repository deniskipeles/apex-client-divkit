# Architectural Model

```
┌────────────────────────────────────────────────────────────────────────┐
│                        SDUI DOCUMENT / APP                             │
│      (Kitchen Ready Alerts, Order Dispatch, Staff Notifications)       │
│                                                                        │
│  Calls: tools://push/send_alert                                        │
│  Payload: { title: "Order Ready", message: "Table 7" }                 │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│               BACKGROUND ALERTS & PUSH RUNTIME                         │
│                                                                        │
│  1. Resolve channel + priority                                         │
│  2. Emit to the notification ledger (user.last_push_alert)             │
│  3. Return a deterministic notification id                             │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼ Generic JSON Output
┌────────────────────────────────────────────────────────────────────────┐
│  {                                                                     │
│    "notification_id": "push_9f2a1c40",                                 │
│    "title": "Order Ready",                                             │
│    "channel": "orders_dispatch",                                       │
│    "priority": "high",                                                 │
│    "status": "LOGGED_TO_LEDGER",                                       │
│    "state": "ok"                                                       │
│  }                                                                     │
└────────────────────────────────────────────────────────────────────────┘
```

# Background Alerts & Push Notifications (`tools://push` / `network.push@1`)

The **Push Alert Tool** dispatches background alerts to a named notification channel.
It is unopinionated: the caller supplies the title, message, channel, and priority, and
the tool records the alert and returns an identifier — no assumption about the payload.

---

## 1. Parameters & Options

Invoked via:
- `tools://push/send_alert`
- `tools://push/notify`
- `tools://push/send`
- `div-action://terminal.tool.invoke?tool=network.push@1`

### Options (`payload.options`)

| Option | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `channel` | `string` | `"orders_dispatch"` | Named notification channel. |
| `priority` | `string` | `"high"` | Priority: `high`, `normal`, `low`. |
| `title` | `string` | `"Kitchen / POS Alert"` | Alert title. |
| `message` | `string` | `"New order ready for pickup"` | Alert body. |

---

## 2. Tool Output Schema

| Field | Type | Description |
| :--- | :--- | :--- |
| `notification_id` | `string` | Deterministic id for the dispatched alert. |
| `title` | `string` | Alert title. |
| `message` | `string` | Alert body. |
| `channel` | `string` | Notification channel. |
| `priority` | `string` | Priority. |
| `status` | `string` | `"LOGGED_TO_LEDGER"`. |
| `state` | `string` | `"ok"`. |
| `timestamp` | `integer` | Unix epoch millisecond timestamp. |

Default output mapping: `notification_id → user.push_notification_id`,
`status → user.push_status`. A reactive variable `user.last_push_alert` is also updated
with `"<title>: <message>"`.

---

## 3. DivKit Usage Examples

### Dispatch a Kitchen Alert
```json
{
  "type": "button",
  "text": "Notify Kitchen",
  "action": {
    "url": "tools://push/send_alert",
    "payload": {
      "title": "Order Ready",
      "message": "Table 7 — 3 items",
      "options": { "channel": "orders_dispatch", "priority": "high" },
      "output": { "notification_id": "push_id", "status": "push_status" }
    }
  }
}
```
