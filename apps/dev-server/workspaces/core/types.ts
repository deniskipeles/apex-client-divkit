import type { DivCard } from './kit/card.ts'
import type { DivNode } from './kit/div.ts'

/** `apex.workspace/1` manifest, minus the derived `cards.order` / `collections.files`. */
export interface WorkspaceMeta {
  title: string
  subtitle: string
  terminal: string
  accent: string
  mode: 'NATIVE' | 'WEB' | 'HYBRID'
  capabilities: string[]
  tools: Record<string, boolean>
  urls?: { tunnel?: string; lan?: string; client_id?: string }
  unread?: number
}

export interface WorkspaceManifest extends WorkspaceMeta {
  format: 'apex.workspace/1'
  id: string
  hub: string
  catalog: string
  collections: { dir: string; files: string[] }
  cards: { dir: string; order: string[] }
}

export interface ToolCatalogEntry {
  file: string
  label: string
  icon: string
  accent_color?: string
  subtitle?: string
}

export interface ToolCatalog {
  title: string
  subtitle: string
  entries: ToolCatalogEntry[]
}

export interface WorkspaceCard {
  /** Card id == file name under `tools/` (and the `nav://push?id=` target). */
  name: string
  /**
   * Raw div body. The Android host embeds it under a Back button and supplies the
   * workspace's `user.*` declarations, so a card file must never be a full
   * `{card:{...}}` document.
   */
  card: DivNode
}

export interface WorkspaceCollection {
  /** File name under `collections/`, e.g. `inventory.json`. */
  file: string
  json: unknown
}

export interface WorkspaceTargets {
  /** Emit into `apps/android/src/dev/resources/demo/<manifestId>/`. */
  android?: boolean
}

export interface WorkspaceDefinition {
  /** Dev-server slug (`apex-kiosk`); the URL segment, never the Android id. */
  slug: string
  /** Android/demo folder name; must match `^[a-z0-9_]{2,40}$`. */
  manifestId: string
  meta: WorkspaceMeta
  hub: DivCard
  cards: WorkspaceCard[]
  catalog: ToolCatalog
  collections: WorkspaceCollection[]
  targets?: WorkspaceTargets
}

export interface WorkspaceBundle {
  slug: string
  manifestId: string
  android: boolean
  meta: WorkspaceMeta
  /** Relative path → JSON document, in write order. */
  files: Record<string, unknown>
}

/** Index entry the playground lists (`workspaces/index.json`). */
export interface WorkspaceIndexEntry {
  slug: string
  manifestId: string
  title: string
  subtitle: string
  accent: string
  terminal: string
  cardCount: number
  collectionCount: number
  android: boolean
}
