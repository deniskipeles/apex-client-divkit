# Architectural Model

```
┌────────────────────────────────────────────────────────────────────────┐
│                        SDUI DOCUMENT / APP                             │
│      (Live Kitchen Stream, Order Status, Presence, Chat)               │
│                                                                        │
│  Calls: tools://realtime-ws/connect                                    │
│  Options: { channel: "kitchen_stream" }                                │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                 REALTIME WEBSOCKET / SSE RUNTIME                       │
│                                                                        │
│  1. Operations: connect, disconnect, send, subscribe                   │
│  2. Hybrid transport: WSS with SSE fallback                            │
│  3. Channel-scoped subscriptions                                       │
│  4. Connection telemetry (state, latency)                              │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼ Generic JSON Output
┌────────────────────────────────────────────────────────────────────────┐
│  {                                                                     │
│    "op": "connect",                                                    │
│    "channel": "kitchen_stream",                                        │
│    "connection_state": "CONNECTED",                                    │
│    "protocol": "WSS_SSE_HYBRID",                                       │
│    "latency_ms": 4                                                     │
│  }                                                                     │
└────────────────────────────────────────────────────────────────────────┘
```

# Realtime WebSocket / SSE Engine (`tools://realtime-ws` / `network.realtime@1`)

The **Realtime Channel Tool** opens and manages realtime WSS/SSE channels for live
workspace updates. It is unopinionated: it manages a named channel's connection lifecycle
and message passing without interpreting the traffic.

---

## 1. Parameters & Options

Invoked via:
- `tools://realtime-ws/connect`
- `tools://realtime-ws/disconnect`
- `tools://realtime-ws/send`
- `tools://realtime-ws/subscribe`
- `div-action://terminal.tool.invoke?tool=network.realtime@1`

### Options (`payload.options`)

| Option | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `channel` | `string` | `"kitchen_stream"` | Named realtime channel. |
| `message` | `string` | `"ping"` | Message payload for `send`. |

---

## 2. Tool Output Schema

| Field | Type | Description |
| :--- | :--- | :--- |
| `op` | `string` | Operation performed. |
| `channel` | `string` | Channel name. |
| `connection_state` | `string` | e.g. `"CONNECTED"`. |
| `protocol` | `string` | `"WSS_SSE_HYBRID"`. |
| `latency_ms` | `integer` | Last observed round-trip latency. |
| `last_message` | `string` | Most recent message. |
| `state` | `string` | `"ok"`. |

Default output mapping: `connection_state → user.realtime_connection_state`,
`channel → user.realtime_channel`.

---

## 3. DivKit Usage Examples

### Subscribe to the Kitchen Stream
```json
{
  "type": "button",
  "text": "Go Live",
  "action": {
    "url": "tools://realtime-ws/connect",
    "payload": {
      "options": { "channel": "kitchen_stream" },
      "output": { "connection_state": "realtime_state", "channel": "realtime_channel" }
    }
  }
}
```
