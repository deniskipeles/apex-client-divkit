import tools from './tools.json'

export interface Capability {
  id: string
  aliases: string[]
}

export const capabilities = tools.capabilities as Capability[]

/** Resolves a canonical capability id or any of its aliases to the canonical id. */
export function canonicalCapabilityId(alias: string): string | undefined {
  return capabilities.find((c) => c.id === alias || c.aliases.includes(alias))?.id
}
