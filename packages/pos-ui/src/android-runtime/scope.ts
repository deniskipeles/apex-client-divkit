/**
 * Port of `engine/state/WorkspaceVariableScope.kt`: a variable is
 * `<scope>.<name>` in a non-reserved scope. Cards may read runtime scopes but
 * never write them.
 */

export const RESERVED_SCOPES = new Set(['engine', 'system', 'internal', 'runtime'])

const VALID_NAME = /^[A-Za-z][A-Za-z0-9_]*\.[A-Za-z0-9_.]+$/

export function isValidVariableName(name: string): boolean {
  if (name.length > 128 || !VALID_NAME.test(name)) return false
  return !RESERVED_SCOPES.has(name.slice(0, name.indexOf('.')))
}
