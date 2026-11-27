import catalog from '../../../contract/icons/native-icons.json' with { type: 'json' }

/**
 * Port of `engine/icons/NativeIconCatalog.kt`.
 *
 * The catalog JSON is generated from the Kotlin table (`pnpm icons:build`), so
 * `icons://native/<name>` resolves to the same icon, category and Material id
 * on both sides. Glyph drawing is the only preview-only concern.
 */

export interface NativeIcon {
  name: string
  material: string
  category: string
  categoryLabel: string
  aliases: string[]
}

const icons = (catalog as { icons: NativeIcon[] }).icons

const byName = new Map<string, NativeIcon>()
const byMaterial = new Map<string, NativeIcon>()
for (const icon of icons) {
  byName.set(icon.name.toLowerCase(), icon)
  for (const alias of icon.aliases) byName.set(alias.toLowerCase(), icon)
  byMaterial.set(icon.material.toLowerCase(), icon)
}

export const ICON_COUNT = icons.length
export const ALL_ICONS: readonly NativeIcon[] = icons

/**
 * `normalize`: `icons://native/<name>`, `icons://<name>` and a bare name all
 * collapse to one key. A non-`icons` scheme is not an icon reference.
 */
export function normalizeIconName(reference: string): string | null {
  let ref = reference.trim()
  if (!ref) return null
  if (/^icons:\/\//i.test(ref)) {
    ref = ref.slice('icons://'.length)
    ref = ref.split('?')[0].split('#')[0]
    const segments = ref.split('/').filter((segment) => segment.length > 0)
    if (segments.length === 0) return null
    ref = segments[0].toLowerCase() === 'native' ? segments.slice(1).join('/') : segments.join('/')
  } else if (ref.includes('://')) {
    return null
  }
  const trimmed = ref.trim()
  if (!trimmed) return null
  const key = trimmed.toLowerCase()
  return byName.get(key)?.name ?? byMaterial.get(key)?.name ?? key
}

export function findIcon(reference: string): NativeIcon | null {
  const name = normalizeIconName(reference)
  return name ? (byName.get(name) ?? null) : null
}

export function iconUriFor(name: string): string {
  return `icons://native/${name}`
}
