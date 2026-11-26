import type { DivNode } from './div.ts'
import type { DivVariable } from './variables.ts'

export interface DivCard {
  card: {
    log_id: string
    variables: DivVariable[]
    states: Array<{ state_id: number; div: DivNode }>
  }
}

export interface CardOptions {
  logId: string
  variables: DivVariable[]
  states: Array<{ stateId?: number; div: DivNode }>
}

/** `apex.workspace/1` cards are single-state DivKit documents. */
export const divKitCard = (opts: CardOptions): DivCard => ({
  card: {
    log_id: opts.logId,
    variables: opts.variables,
    states: opts.states.map((state, index) => ({ state_id: state.stateId ?? index, div: state.div })),
  },
})

export const singleState = (div: DivNode): DivCard =>
  divKitCard({ logId: 'card', variables: [], states: [{ div }] })
