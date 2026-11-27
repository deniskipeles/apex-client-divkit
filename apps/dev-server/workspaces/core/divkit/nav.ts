import { DivCustom, DivState } from '@divkitframework/jsonbuilder'
import type { DivNode } from '../kit/div.ts'

/**
 * Two navigation primitives, on purpose:
 *
 * - `nav` is the custom `sdui_nav` div. It owns a real push/pop history, so a
 *   single card document can hold several pages and `nav://push|pop|replace|reset`
 *   switches between them. Native `DivState` cannot express a stack.
 * - `state` is the native `DivState` node. It switches one active child through
 *   `state_id_variable` (or `default_state_id`), which is enough for simple tabs.
 */

export interface NavPage {
  /** Page id the `nav://…?id=` actions target. */
  id: string
  /** Raw page body. */
  div: DivNode
}

/**
 * jsonbuilder classes keep optional props as own keys set to `undefined`. The
 * authoring validator walks `Object.keys`, so normalise to the exact JSON shape
 * the generator writes.
 */
const plain = (node: unknown): DivNode => JSON.parse(JSON.stringify(node)) as DivNode

export interface NavOptions {
  /** Page shown first. Must be one of `cards`. */
  initial: string
  cards: NavPage[]
  id?: string
}

export const nav = (opts: NavOptions): DivNode => {
  if (opts.cards.length === 0) throw new Error('nav: at least one page is required')
  const ids = opts.cards.map((page) => page.id)
  if (new Set(ids).size !== ids.length) throw new Error('nav: duplicate page ids')
  if (!ids.includes(opts.initial)) throw new Error(`nav: initial page "${opts.initial}" is not in cards`)

  const node = new DivCustom({
    custom_type: 'sdui_nav',
    custom_props: { initial: opts.initial, cards: opts.cards },
  })
  if (opts.id) (node as unknown as { id: string }).id = opts.id
  return plain(node)
}

export interface StatePage {
  /** State id written into `state_id_variable`. */
  id: string
  div: DivNode
}

export interface StateOptions {
  id?: string
  /** Variable whose string value selects the active state. */
  stateIdVariable?: string
  /** State shown when the variable is empty/unknown. Defaults to the first state. */
  defaultStateId?: string
  states: StatePage[]
}

export const state = (opts: StateOptions): DivNode => {
  if (opts.states.length === 0) throw new Error('state: at least one state is required')
  const ids = opts.states.map((entry) => entry.id)
  if (new Set(ids).size !== ids.length) throw new Error('state: duplicate state ids')
  const defaultStateId = opts.defaultStateId ?? ids[0]
  if (!ids.includes(defaultStateId)) throw new Error(`state: default "${defaultStateId}" is not a state id`)

  const node = new DivState({
    ...(opts.stateIdVariable ? { state_id_variable: opts.stateIdVariable } : {}),
    default_state_id: defaultStateId,
    states: opts.states.map((entry) => ({ state_id: entry.id, div: entry.div as never })),
  })
  if (opts.id) (node as unknown as { id: string }).id = opts.id
  return plain(node)
}
