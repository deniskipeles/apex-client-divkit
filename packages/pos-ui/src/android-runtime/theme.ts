/**
 * Port of the renderer's light-theme colour adaptation
 * (`adaptSurfaceColorForTheme` and friends in `DivKitMultiplatform.kt`).
 *
 * Cards are authored against the dark slate palette. The device keeps the
 * layout and re-tints fills, borders, captions and buttons for a light system
 * theme, so a preview without this step shows contrasts the device never has.
 */

export interface Rgba {
  r: number
  g: number
  b: number
  a: number
}

const FALLBACK = '#38BDF8'

export function parseHexColor(hexStr: string): Rgba {
  const clean = hexStr.trim().replace('#', '')
  if (clean.length !== 6 && clean.length !== 8) return parseHexColor(FALLBACK)
  if (!/^[0-9a-fA-F]+$/.test(clean)) return parseHexColor(FALLBACK)
  const value = clean.length === 6 ? `ff${clean}` : clean
  const int = Number.parseInt(value, 16)
  return {
    r: (int >>> 24) & 0xff,
    g: (int >>> 16) & 0xff,
    b: (int >>> 8) & 0xff,
    a: int & 0xff,
  }
}

export function toCss(color: Rgba): string {
  const r = Math.round(color.r)
  const g = Math.round(color.g)
  const b = Math.round(color.b)
  if (color.a >= 255) return `#${[r, g, b].map((part) => part.toString(16).padStart(2, '0')).join('')}`
  return `rgba(${r}, ${g}, ${b}, ${(color.a / 255).toFixed(3)})`
}

/** Compose `Color.luminance()`. */
export function luminance(color: Rgba): number {
  const channel = (value: number) => {
    const srgb = value / 255
    return srgb <= 0.03928 ? srgb / 12.92 : ((srgb + 0.055) / 1.055) ** 2.4
  }
  return 0.2126 * channel(color.r) + 0.7152 * channel(color.g) + 0.0722 * channel(color.b)
}

function scaled(color: Rgba, factor: number): Rgba {
  return { r: color.r * factor, g: color.g * factor, b: color.b * factor, a: color.a }
}

export function adaptSurfaceColorForTheme(hexStr: string, isDark: boolean): string {
  if (isDark) return toCss(parseHexColor(hexStr))
  switch (hexStr.trim().toUpperCase()) {
    case '#1E293B':
    case '#152033':
      return toCss(parseHexColor('#FFFFFF'))
    case '#0F172A':
    case '#0B1120':
      return toCss(parseHexColor('#F8FAFC'))
    case '#334155':
      return toCss(parseHexColor('#F1F5F9'))
    default:
      return toCss(parseHexColor(hexStr))
  }
}

export function adaptBorderColorForTheme(hexStr: string, isDark: boolean): string {
  if (isDark) return toCss(parseHexColor(hexStr))
  switch (hexStr.trim().toUpperCase()) {
    case '#334155':
    case '#1E293B':
      return toCss(parseHexColor('#CBD5E1'))
    case '#38BDF8':
      return toCss(parseHexColor('#93C5FD'))
    default:
      return toCss(parseHexColor(hexStr))
  }
}

export function adaptTextColorForTheme(hexStr: string, isDark: boolean, surfaceHex?: string): string {
  const declared = parseHexColor(hexStr)
  if (!isDark && surfaceHex) {
    const surface = parseHexColor(adaptSurfaceColorForTheme(surfaceHex, false))
    if (luminance(surface) < 0.5) {
      switch (hexStr.trim().toUpperCase()) {
        case '#64748B':
        case '#94A3B8':
        case '#475569':
          return toCss(parseHexColor('#CBD5E1'))
        default:
          return luminance(declared) < 0.35 ? toCss(parseHexColor('#F8FAFC')) : toCss(declared)
      }
    }
  }
  if (isDark) {
    switch (hexStr.trim().toUpperCase()) {
      case '#64748B':
      case '#94A3B8':
        return toCss(parseHexColor('#CBD5E1'))
      default:
        return toCss(declared)
    }
  }

  const mapped = new Map<string, string>([
    ['#F8FAFC', '#0F172A'],
    ['#E2E8F0', '#0F172A'],
    ['#FFFFFF', '#0F172A'],
    ['#94A3B8', '#475569'],
    ['#CBD5E1', '#475569'],
    ['#64748B', '#475569'],
    ['#38BDF8', '#1D4ED8'],
    ['#34D399', '#047857'],
    ['#FBBF24', '#B45309'],
    ['#F87171', '#B91C1C'],
    ['#FCA5A5', '#B91C1C'],
  ]).get(hexStr.trim().toUpperCase())
  if (mapped) return toCss(parseHexColor(mapped))
  return luminance(declared) > 0.5 ? toCss(scaled(declared, 0.25)) : toCss(declared)
}

export function adaptButtonBgForTheme(hexStr: string, isDark: boolean): string {
  if (isDark) return toCss(parseHexColor(hexStr))
  const mapped = new Map<string, string>([
    ['#334155', '#F1F5F9'],
    ['#1E293B', '#F1F5F9'],
    ['#3F1D24', '#FEE2E2'],
    ['#0EA5E9', '#1D4ED8'],
    ['#0284C7', '#0369A1'],
    ['#0891B2', '#0E7490'],
    ['#0D9488', '#0F766E'],
    ['#059669', '#047857'],
    ['#D97706', '#B45309'],
    ['#8B5CF6', '#6D28D9'],
    ['#DB2777', '#BE123C'],
  ]).get(hexStr.trim().toUpperCase())
  return toCss(parseHexColor(mapped ?? hexStr))
}

export function adaptButtonTextForTheme(textHex: string, bgHex: string, isDark: boolean): string {
  if (isDark) return toCss(parseHexColor(textHex))
  const background = bgHex.trim().toUpperCase()
  if (background === '#334155' || background === '#1E293B') {
    switch (textHex.trim().toUpperCase()) {
      case '#38BDF8':
        return toCss(parseHexColor('#1D4ED8'))
      case '#A78BFA':
        return toCss(parseHexColor('#6D28D9'))
      case '#34D399':
        return toCss(parseHexColor('#047857'))
      default:
        return toCss(parseHexColor('#0F172A'))
    }
  }
  if (background === '#3F1D24') return toCss(parseHexColor('#B91C1C'))
  if (background === '#0EA5E9') return toCss(parseHexColor('#FFFFFF'))
  return toCss(parseHexColor(textHex))
}
