/**
 * `output` mapping shared by the browser runtime and the server tool registry.
 *
 * `NativeToolsSuite` publishes result fields into `user.*` variables through the
 * card's `output` map. Keeping this in one place means the preview's local and
 * server execution modes publish identical values.
 */

import { display } from './values.ts'

export type DivOutputValue = string | number | boolean | null | DivOutputValue[]

/** `payload.output` maps a result field to a variable name. */
export function outputMappingOf(payload: Record<string, unknown>): Record<string, string> {
  const raw = payload.output
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return {}
  const out: Record<string, string> = {}
  for (const [field, variable] of Object.entries(raw as Record<string, unknown>)) {
    if (typeof variable === 'string') out[field] = variable
  }
  return out
}

/** `NativeToolsSuite` maps result fields into `user.*` variables via `output`. */
export function applyOutputMapping(
  data: Record<string, unknown>,
  mapping: Record<string, string>,
): Record<string, DivOutputValue> {
  const updates: Record<string, DivOutputValue> = {}
  for (const [field, variable] of Object.entries(mapping)) {
    if (!(field in data)) continue
    const value = data[field]
    // Array results stay arrays (a card reads them back with getArrayOptString);
    // a structured object is published as JSON text, like the device does.
    updates[variable] = Array.isArray(value)
      ? value.map((item) => display(item))
      : value !== null && typeof value === 'object'
        ? JSON.stringify(value)
        : typeof value === 'number' || typeof value === 'boolean'
          ? value
          : display(value)
  }
  return updates
}
