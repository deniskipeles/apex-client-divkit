import Ajv2020 from 'ajv/dist/2020.js'
import subsetSchema from '../../../../packages/divkit-subset/schema.json'

// The subset schema declares `$schema: draft/2020-12`, which the default Ajv (draft-07) cannot compile.
const ajv = new Ajv2020({ strict: false, allErrors: true })
const validateComponent = ajv.compile(subsetSchema)

export interface CardValidation {
  ok: boolean
  errors: string[]
}

interface DivKitCard {
  log_id?: unknown
  states?: Array<{ div?: unknown }>
}

/**
 * Validates a DivKit card against the permitted component subset
 * (packages/divkit-subset/schema.json). Each `card.states[].div` is a component tree;
 * a component type outside the subset is rejected before it can reach the renderer.
 */
export function validateCard(divkit: unknown): CardValidation {
  const errors: string[] = []
  if (typeof divkit !== 'object' || divkit === null) {
    return { ok: false, errors: ['divkit must be an object'] }
  }

  const card = (divkit as { card?: DivKitCard }).card
  if (typeof card !== 'object' || card === null) {
    return { ok: false, errors: ['card is required'] }
  }
  if (typeof card.log_id !== 'string' || card.log_id.length === 0) {
    errors.push('card.log_id is required')
  }
  if (!Array.isArray(card.states) || card.states.length === 0) {
    errors.push('card.states must be a non-empty array')
    return { ok: errors.length === 0, errors }
  }

  card.states.forEach((state, index) => {
    if (typeof state?.div !== 'object' || state.div === null) {
      errors.push(`card.states[${index}].div is required`)
      return
    }
    if (!validateComponent(state.div)) {
      errors.push(`card.states[${index}].div: ${ajv.errorsText(validateComponent.errors)}`)
    }
  })

  return { ok: errors.length === 0, errors }
}
