import { acmePos } from '../acme-pos/index.ts'
import { ownerPos } from '../apex-kiosk/index.ts'
import { apexToolsTest } from '../apex-tools-test/index.ts'
import { motorbike } from '../motorbike/index.ts'
import type { WorkspaceDefinition } from './types.ts'

/**
 * Every workspace the generator owns. Order is the order of `workspaces/index.json` and the
 * append order for `demo/workspaces.json`.
 */
export const WORKSPACES: WorkspaceDefinition[] = [motorbike, apexToolsTest, ownerPos, acmePos]

/**
 * Workspaces whose folder under `apps/android/src/dev/resources/demo/` is generated output.
 * A folder that is not listed here is hand-authored and must not be overwritten without
 * `--force`.
 */
export const ANDROID_OWNED: ReadonlySet<string> = new Set(['acme_pos', 'apex_tools_test', 'owner_pos'])

export function workspaceBySlug(slug: string): WorkspaceDefinition | undefined {
  return WORKSPACES.find((workspace) => workspace.slug === slug)
}
