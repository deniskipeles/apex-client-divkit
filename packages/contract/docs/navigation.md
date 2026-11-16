# Card Navigation Contract


## URL schemes


### nav://push?id=<card_id>
Push a card onto the navigation stack. `<card_id>` must resolve via CardRegistry.
If it does not resolve, the action is a silent no-op (no exception, no push).


### nav://pop
Pop the top card. No-op if the stack has only one entry.


### nav://replace?id=<card_id>
Replace the current stack top. Use for tab switches that should not grow history.


### nav://reset?id=<card_id>
Reset the stack to a single card. Use for "return to launcher".


### sheet://present?card_id=<card_id>
Present a bottom sheet hosting the referenced card. Reuses the same
CardRegistry pointer; no data duplication.


### sheet://dismiss
Dismiss the active sheet.


## CardRegistry (all four platforms expose this)


    register(id: String, data: DivData): Unit
    card(id: String): DivData?
    has(id: String): Boolean


## Variable scoping


Card-local variables (declared in the card's `variables` array) are scoped to
that card instance and MUST NOT leak across push/pop. EngineStateBridge only
accepts `user.*` names; card-local state never enters EngineStateBridge.


## State preservation requirement


- The stack holds DivData pointers (immutable), never copies.
- One view instance per card_id in a bounded LRU cache (default 8).
- Push/pop attaches/detaches views; it never recreates them.
- The visible card is never evicted from the cache.
- Evicted cards re-render from their pointer on the next push (no JSON re-parse).


## Parsed action shape


Every platform parses the URLs above into the same discriminated shape. The
fixtures in `fixtures/navigation/v1/` pin the mapping from URL to shape; a
per-platform `NavigationContractTest` reads that directory, parses each `input`
and asserts equality with `expected`. Drift turns CI red.

    { "op": "push",          "cardId": "<card_id>" }
    { "op": "pop" }
    { "op": "replace",       "cardId": "<card_id>" }
    { "op": "reset",         "cardId": "<card_id>" }
    { "op": "sheet-present", "cardId": "<card_id>" }
    { "op": "sheet-dismiss" }

Notes:

- `nav://push` and `nav://replace` / `nav://reset` read the `id` query parameter.
- `sheet://present` reads the `card_id` query parameter.
- `nav://pop` and `sheet://dismiss` take no parameter and carry no `cardId` key.
- An unrecognised scheme/host, or a missing/blank id, parses to `null`
  (a silent no-op at the handler, never an exception).
