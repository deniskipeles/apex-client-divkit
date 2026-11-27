/**
 * Value semantics of the Android SDUI renderer (`DivKitExpression.kt`,
 * `DivKitMultiplatform.kt`).
 *
 * Android carries a typed variable (`IntVal` / `DoubleVal` / `Bool` / `Str` /
 * `ArrayVal`); JavaScript collapses numbers to one type, so the only visible
 * consequence is display formatting, which is reproduced here exactly.
 */

export type DivValue = string | number | boolean | DivValue[] | null
export type DivVars = Record<string, DivValue>
export type RowContext = Record<string, unknown>

/** Kotlin `divExpressionDisplay`. */
export function display(value: unknown): string {
  if (value === null || value === undefined) return ''
  if (typeof value === 'boolean') return value ? 'true' : 'false'
  if (typeof value === 'number') return formatNumber(value)
  if (Array.isArray(value)) return value.map((item) => display(item)).join(',')
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

/**
 * Kotlin `formatNumber`: integral doubles print as integers, everything else
 * goes through `BigDecimal.stripTrailingZeros().toPlainString()`, which never
 * emits an exponent. JS `String(1e21)` does, so expand it back.
 */
export function formatNumber(value: number): string {
  if (Number.isNaN(value)) return ''
  if (!Number.isFinite(value)) return value > 0 ? 'Infinity' : '-Infinity'
  if (Number.isInteger(value) && Math.abs(value) <= Number.MAX_SAFE_INTEGER) return String(value)
  return expandExponent(String(value))
}

function expandExponent(raw: string): string {
  const match = /^(-?)(\d+)(?:\.(\d+))?e([+-]?\d+)$/i.exec(raw)
  if (!match) return raw
  const [, sign, intPart, fracPart = '', expRaw] = match
  const exponent = Number(expRaw)
  const digits = intPart + fracPart
  const point = intPart.length + exponent
  if (point <= 0) return `${sign}0.${'0'.repeat(-point)}${digits}`
  if (point >= digits.length) return `${sign}${digits}${'0'.repeat(point - digits.length)}`
  return `${sign}${digits.slice(0, point)}.${digits.slice(point)}`
}

/** Kotlin `truthy`. */
export function truthy(value: unknown): boolean {
  if (value === null || value === undefined) return false
  if (typeof value === 'boolean') return value
  if (typeof value === 'number') return value !== 0
  if (Array.isArray(value)) return value.length > 0
  const text = String(value)
  return text.length > 0 && text.toLowerCase() !== 'false' && text !== '0'
}

/** Kotlin `number(...)`: a strict parse, so `"12abc"` is 0 rather than 12. */
export function toNumber(value: unknown): number {
  if (value === null || value === undefined) return 0
  if (typeof value === 'number') return value
  if (typeof value === 'boolean') return value ? 1 : 0
  const text = String(value).trim()
  if (!/^[+-]?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?$/.test(text)) return 0
  const parsed = Number(text)
  return Number.isFinite(parsed) ? parsed : 0
}

/** Kotlin `toLongOrNull()` / `toDoubleOrNull()` used by typed action values. */
export function toInteger(value: unknown): number {
  return Math.trunc(toNumber(value))
}

/**
 * `parseJsonListVariable`: a JSON array string or an already-parsed array.
 * Object cells keep their JSON form, primitives become their text content.
 */
export function parseRows(value: unknown): RowContext[] {
  const coerce = (raw: unknown): RowContext[] => {
    if (!Array.isArray(raw)) return []
    const out: RowContext[] = []
    for (const row of raw) {
      if (!row || typeof row !== 'object' || Array.isArray(row)) continue
      const mapped: RowContext = {}
      for (const [key, cell] of Object.entries(row as Record<string, unknown>)) {
        mapped[key] =
          cell === null || cell === undefined
            ? null
            : typeof cell === 'object'
              ? JSON.stringify(cell)
              : String(cell)
      }
      out.push(mapped)
    }
    return out
  }

  if (Array.isArray(value)) return coerce(value)
  if (typeof value === 'string') {
    const text = value.trim()
    if (!text.startsWith('[')) return []
    try {
      return coerce(JSON.parse(text))
    } catch {
      return []
    }
  }
  return []
}
