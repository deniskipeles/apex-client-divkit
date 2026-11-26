export type VariableType = 'string' | 'integer' | 'number' | 'boolean' | 'array'

export interface DivVariable {
  name: string
  type: VariableType
  value: unknown
}

export const stringVar = (name: string, value: string): DivVariable => ({ name, type: 'string', value })

export const intVar = (name: string, value: number): DivVariable => ({ name, type: 'integer', value })

export const numberVar = (name: string, value: number): DivVariable => ({ name, type: 'number', value })

export const boolVar = (name: string, value: boolean): DivVariable => ({ name, type: 'boolean', value })

/** JSON-array variables are carried as a string (Android parses them with `parseJsonListVariable`). */
export const arrayVar = (name: string, value: unknown[]): DivVariable => ({
  name,
  type: 'string',
  value: JSON.stringify(value),
})
