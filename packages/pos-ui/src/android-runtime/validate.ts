import { cardRoot } from './renderModel.ts'
import { isValidVariableName } from './scope.ts'
import {
  ACTION_TYPES,
  COMMON_PROPS,
  NODE_PROPS,
  URL_SCHEMES,
  isChartCustomType,
  isNodeType,
} from './vocabulary.ts'

/**
 * Static check of an authored card against the Android vocabulary.
 *
 * The device renders an unknown node as nothing and ignores an unknown action
 * silently, so a card can build, render in a browser and still be blank on a
 * handset. These errors catch that at authoring time.
 */

export interface ValidationReport {
  errors: string[]
  warnings: string[]
}

export interface ValidateOptions {
  /**
   * `true` for an Android shipping target: a node the device renders as nothing
   * is an error. `false` for a web/desktop-only workspace, where it is a warning.
   */
  strict?: boolean
}

export function validateCardDocument(
  card: unknown,
  file: string,
  options: ValidateOptions = {},
): ValidationReport {
  const report: ValidationReport = { errors: [], warnings: [] }
  const strict = options.strict !== false
  const root = card as Record<string, unknown> | null
  if (!root || typeof root !== 'object') {
    report.errors.push(`${file}: document is not an object`)
    return report
  }

  const cardObj = (root.card as Record<string, unknown>) ?? root
  const declared = new Set<string>()
  if (Array.isArray(cardObj.variables)) {
    for (const entry of cardObj.variables) {
      const name = (entry as Record<string, unknown>)?.name
      if (typeof name === 'string') {
        declared.add(name)
        if (!isValidVariableName(name)) {
          report.errors.push(`${file}: variable "${name}" is not a valid <scope>.<name> in a non-reserved scope`)
        }
      }
    }
  }

  const div = cardRoot(root)
  if (!div) {
    report.errors.push(`${file}: card has no div to render`)
    return report
  }

  walk(div, `${file}:div`, report, declared, strict)
  return report
}

function walk(
  node: Record<string, unknown>,
  path: string,
  report: ValidationReport,
  declared: Set<string>,
  strict: boolean,
): void {
  const flag = (message: string) => (strict ? report.errors.push(message) : report.warnings.push(message))
  const type = typeof node.type === 'string' ? node.type : null
  if (!type) {
    report.errors.push(`${path}: missing "type"`)
    return
  }
  if (!isNodeType(type)) {
    flag(`${path}: unknown node type "${type}" — the device renders nothing`)
    return
  }

  if (type === 'custom') {
    const customType = typeof node.custom_type === 'string' ? node.custom_type : ''
    if (!customType) {
      report.errors.push(`${path}: "custom" needs a custom_type`)
    } else if (customType !== 'json_list' && !isChartCustomType(customType)) {
      flag(
        `${path}: custom_type "${customType}" is not implemented by the Android renderer — the device renders nothing`,
      )
    }
  }

  checkProps(node, type, path, report)
  checkVariableReferenceProps(node, type, path, report)
  checkActions(node, path, report, declared)

  const children: unknown[] = []
  // Only descend where the device renders child divs. `segmented.items`,
  // `qr_controls.prev/next` and `fab.actions` hold descriptors/actions, not divs,
  // and `items` on a chart is meaningless.
  const structuralItems = type === 'container' || type === 'gallery' || type === 'grid'
  if (structuralItems && Array.isArray(node.items)) children.push(...node.items)

  const customProps = node.custom_props as Record<string, unknown> | undefined
  const props = customProps ?? node
  if (type === 'json_list') pushObject(children, props.row_card)
  if (type === 'sticky_footer') {
    pushArray(children, props.body)
    pushArray(children, props.footer)
  }
  if (type === 'form_modal' || type === 'bottom_sheet') pushArray(children, props.body)

  children.forEach((child, index) => {
    if (child && typeof child === 'object' && !Array.isArray(child)) {
      walk(child as Record<string, unknown>, `${path}/${index}`, report, declared, strict)
    }
  })
}

function pushArray(out: unknown[], value: unknown): void {
  if (Array.isArray(value)) out.push(...value)
}

function pushObject(out: unknown[], value: unknown): void {
  if (value && typeof value === 'object' && !Array.isArray(value)) out.push(value)
}

const VARIABLE_REFERENCE_PROPS: Record<string, string[]> = {
  json_list: ['data_var'],
  text: [],
  input: ['text_variable'],
  text_field: ['variable'],
  scan_chips: ['chunks_var'],
  custom: ['selection_var'],
}

function checkVariableReferenceProps(
  node: Record<string, unknown>,
  type: string,
  path: string,
  report: ValidationReport,
): void {
  const props = (node.custom_props as Record<string, unknown>) ?? node
  for (const key of VARIABLE_REFERENCE_PROPS[type] ?? []) {
    const value = props[key]
    if (typeof value === 'string' && value.includes('@{')) {
      report.errors.push(
        `${path}: "${key}" is a variable name, not an expression — "${value}" reads a variable that does not exist`,
      )
    }
  }
}

function checkProps(
  node: Record<string, unknown>,
  type: string,
  path: string,
  report: ValidationReport,
): void {
  const known = new Set([...COMMON_PROPS, ...(NODE_PROPS[type] ?? [])])
  for (const key of Object.keys(node)) {
    if (!known.has(key)) report.warnings.push(`${path}: "${key}" is not read by the "${type}" renderer`)
  }
}

function checkActions(
  node: Record<string, unknown>,
  path: string,
  report: ValidationReport,
  declared: Set<string>,
): void {
  const actions: Record<string, unknown>[] = []
  if (node.action && typeof node.action === 'object') actions.push(node.action as Record<string, unknown>)
  if (Array.isArray(node.actions)) {
    for (const action of node.actions) {
      if (action && typeof action === 'object') actions.push(action as Record<string, unknown>)
    }
  }

  actions.forEach((action, index) => {
    const at = `${path}:action[${index}]`
    const actionType = typeof action.type === 'string' ? action.type : null
    const url = typeof action.url === 'string' ? action.url : null

    if (actionType && (ACTION_TYPES as readonly string[]).includes(actionType)) {
      const name = typeof action.variable_name === 'string' ? action.variable_name : null
      if (!name) report.errors.push(`${at}: ${actionType} needs "variable_name"`)
      else if (!isValidVariableName(name)) report.errors.push(`${at}: "${name}" is not a writable variable name`)
      else if (declared.size > 0 && !declared.has(name)) {
        report.warnings.push(`${at}: writes "${name}" which the card does not declare`)
      }
      return
    }

    if (!url) {
      report.errors.push(`${at}: action has neither a known type nor a url`)
      return
    }
    const scheme = /^([a-z][a-z0-9+.-]*):\/\//i.exec(url)?.[1]?.toLowerCase()
    if (!scheme || !(URL_SCHEMES as readonly string[]).includes(scheme)) {
      report.errors.push(`${at}: "${scheme ?? url}" is not a scheme the device dispatches`)
    }
  })
}
