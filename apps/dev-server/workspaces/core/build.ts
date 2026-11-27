import { validateCardDocument } from '../../../../packages/pos-ui/src/android-runtime/validate.ts'
import type {
  WorkspaceBundle,
  WorkspaceDefinition,
  WorkspaceIndexEntry,
  WorkspaceManifest,
} from './types.ts'

export const MANIFEST_ID_PATTERN = /^[a-z0-9_]{2,40}$/

export class WorkspaceValidationError extends Error {
  readonly problems: string[]

  constructor(slug: string, problems: string[]) {
    super(`workspace "${slug}" is invalid:\n  - ${problems.join('\n  - ')}`)
    this.name = 'WorkspaceValidationError'
    this.problems = problems
  }
}

const NAV_TARGET = /^(?:nav:\/\/push|nav:\/\/replace|nav:\/\/reset)\?id=([^&#]+)/
const SHEET_TARGET = /^sheet:\/\/present\?card_id=([^&#]+)/

/** Every card id a document navigates to (push/replace/reset/sheet-present). */
export function navigationTargets(value: unknown, out: Set<string> = new Set()): Set<string> {
  if (typeof value === 'string') {
    const nav = NAV_TARGET.exec(value)
    if (nav) out.add(decodeURIComponent(nav[1]))
    const sheet = SHEET_TARGET.exec(value)
    if (sheet) out.add(decodeURIComponent(sheet[1]))
    return out
  }
  if (Array.isArray(value)) {
    for (const item of value) navigationTargets(item, out)
    return out
  }
  if (value && typeof value === 'object') {
    for (const item of Object.values(value as Record<string, unknown>)) navigationTargets(item, out)
  }
  return out
}

/** Throws `WorkspaceValidationError` listing every problem; returns the definition otherwise. */
/** Class instances carry undefined fields for every declared prop; validate what serializes. */
const plain = <T>(value: T): T => JSON.parse(JSON.stringify(value))

export function validateWorkspace(def: WorkspaceDefinition): WorkspaceDefinition {
  const problems: string[] = []

  if (!MANIFEST_ID_PATTERN.test(def.manifestId)) {
    problems.push(`manifest id "${def.manifestId}" must match ${MANIFEST_ID_PATTERN}`)
  }

  const cardNames = def.cards.map((c) => c.name)
  const duplicateCards = cardNames.filter((name, i) => cardNames.indexOf(name) !== i)
  if (duplicateCards.length > 0) problems.push(`duplicate card ids: ${[...new Set(duplicateCards)].join(', ')}`)

  const collectionFiles = def.collections.map((c) => c.file)
  const duplicateCollections = collectionFiles.filter((file, i) => collectionFiles.indexOf(file) !== i)
  if (duplicateCollections.length > 0) {
    problems.push(`duplicate collection files: ${[...new Set(duplicateCollections)].join(', ')}`)
  }
  for (const file of collectionFiles) {
    if (!file.endsWith('.json')) problems.push(`collection "${file}" must be a .json file`)
  }

  for (const entry of def.catalog.entries) {
    if (!cardNames.includes(entry.file)) {
      problems.push(`catalog entry "${entry.file}" is not in cards.order`)
    }
  }
  for (const name of cardNames) {
    if (!def.catalog.entries.some((entry) => entry.file === name)) {
      problems.push(`card "${name}" has no tool_catalog entry`)
    }
  }

  const known = new Set(cardNames)
  const documents: Array<[string, unknown]> = [
    ['card.json', plain(def.hub)],
    ...def.cards.map((card): [string, unknown] => [`tools/${card.name}.json`, plain(card.card)]),
    ...def.collections.map((collection): [string, unknown] => [`collections/${collection.file}`, plain(collection.json)]),
  ]
  for (const [file, doc] of documents) {
    for (const target of navigationTargets(doc)) {
      // Android registers cards as `tool_<file>` while a workspace may link either id
      // (`CardRegistry` resolves both); validate against the file name.
      const name = target.startsWith('tool_') ? target.slice('tool_'.length) : target
      if (!known.has(name)) problems.push(`${file} navigates to unknown card "${target}"`)
    }
  }

  for (const report of workspaceReports(def)) problems.push(...report.errors)

  if (problems.length > 0) throw new WorkspaceValidationError(def.slug, problems)
  return def
}

/**
 * Vocabulary check per DivKit document. The device renders an unknown node as
 * nothing and ignores an unknown action silently, so the same JSON can build,
 * render in the browser and be blank on a handset. These reports are the only
 * authoring-time signal before the APK.
 */
export function workspaceReports(def: WorkspaceDefinition): Array<{
  file: string
  errors: string[]
  warnings: string[]
}> {
  const documents: Array<[string, unknown]> = [
    ['card.json', plain(def.hub)],
    ...def.cards.map((card): [string, unknown] => [`tools/${card.name}.json`, plain(card.card)]),
  ]
  const strict = def.targets?.android === true
  return documents.map(([file, doc]) => {
    const report = validateCardDocument(doc, `${def.manifestId}/${file}`, { strict })
    return { file, errors: report.errors, warnings: report.warnings }
  })
}

/** Android's schema model requires a stable `uid` on every field. Older bundles omitted it. */
function withCollectionId(collection: unknown, id: number): unknown {
  const json = plain(collection) as Record<string, any>
  if (json?.id === undefined) json.id = id
  return json
}

function withFieldUids(collection: unknown): unknown {
  const json = plain(collection) as Record<string, any>
  const name = typeof json?.name === 'string' ? json.name : 'collection'
  const fields = json?.schema?.fields
  if (!fields || typeof fields !== 'object') return json
  for (const [field, spec] of Object.entries(fields as Record<string, any>)) {
    if (spec && typeof spec === 'object' && !('uid' in spec)) spec.uid = `${name}_${field}`
  }
  const canonical = json.schema.canonical
  if (canonical && typeof canonical === 'object' && !Array.isArray(canonical.fields)) {
    canonical.fields = Object.keys(fields)
  }
  return json
}

function manifestFor(def: WorkspaceDefinition): WorkspaceManifest {
  return {
    format: 'apex.workspace/1',
    id: def.manifestId,
    ...def.meta,
    hub: 'card.json',
    catalog: 'tool_catalog.json',
    collections: { dir: 'collections', files: def.collections.map((c) => c.file) },
    cards: { dir: 'tools', order: def.cards.map((c) => c.name) },
  }
}

/**
 * Builds the complete `apex.workspace/1` file set. `cards.order` and `collections.files` are
 * derived from the definition, so the manifest can never disagree with the files on disk
 * (`EncryptWorkspacesTask` packs exactly those).
 */
export function buildWorkspace(def: WorkspaceDefinition): WorkspaceBundle {
  validateWorkspace(def)

  const collections = def.collections.map((collection, index) => ({
    ...collection,
    json: withCollectionId(withFieldUids(collection.json), index + 1),
  }))
  const files: Record<string, unknown> = {}
  files['workspace.json'] = manifestFor(def)
  files['card.json'] = def.hub
  files['tool_catalog.json'] = def.catalog
  for (const card of def.cards) files[`tools/${card.name}.json`] = card.card
  for (const collection of collections) files[`collections/${collection.file}`] = collection.json
  files['collections/index.json'] = { version: 1, collections: collections.map((c) => c.file) }

  return {
    slug: def.slug,
    manifestId: def.manifestId,
    android: def.targets?.android === true,
    meta: def.meta,
    files,
  }
}

/**
 * The whole package as one document — what `/api/workspaces/<id>` returns and what the static
 * snapshot `workspaces/<slug>/index.json` holds, so the SPA can fall back to a file.
 */
export function workspacePayload(bundle: WorkspaceBundle) {
  const cards: Record<string, unknown> = {}
  const order: string[] = []
  for (const [path, value] of Object.entries(bundle.files)) {
    if (!path.startsWith('tools/')) continue
    const name = path.slice('tools/'.length).replace(/\.json$/, '')
    cards[name] = value
    order.push(name)
  }
  return {
    meta: {
      slug: bundle.slug,
      manifestId: bundle.manifestId,
      title: bundle.meta.title,
      subtitle: bundle.meta.subtitle,
      accent: bundle.meta.accent,
      terminal: bundle.meta.terminal,
      android: bundle.android,
    },
    manifest: bundle.files['workspace.json'],
    hub: bundle.files['card.json'],
    catalog: bundle.files['tool_catalog.json'],
    collections: Object.fromEntries(
      Object.entries(bundle.files)
        .filter(([path]) => path.startsWith('collections/') && path !== 'collections/index.json')
        .map(([path, value]) => [path.slice('collections/'.length), value]),
    ),
    cards,
    order,
  }
}

export function indexEntry(bundle: WorkspaceBundle): WorkspaceIndexEntry {
  return {
    slug: bundle.slug,
    manifestId: bundle.manifestId,
    title: bundle.meta.title,
    subtitle: bundle.meta.subtitle,
    accent: bundle.meta.accent,
    terminal: bundle.meta.terminal,
    cardCount: Object.keys(bundle.files).filter((path) => path.startsWith('tools/')).length,
    collectionCount: Object.keys(bundle.files).filter((path) => path.startsWith('collections/') && path !== 'collections/index.json').length,
    android: bundle.android,
  }
}
