import type { DivExtensionClass } from '@divkitframework/divkit'

/**
 * DivKit extension registry. Empty by default: the terminal's behaviour is expressed
 * through `tools://` actions and custom blocks, not DivKit extensions.
 */
export const extensions = new Map<string, DivExtensionClass>()
