/**
 * Deterministic JSON canonicalisation. Byte-for-byte compatible with the Kotlin and Swift
 * implementations. See packages/contract/docs/canonical-json.md.
 */
export function canonicalize(value: unknown): string {
  if (value === null || value === undefined) return 'null';
  if (typeof value === 'boolean') return value ? 'true' : 'false';
  if (typeof value === 'bigint') return value.toString();
  if (typeof value === 'number') return canonicalizeNumber(value);
  if (typeof value === 'string') return quote(value);
  if (Array.isArray(value)) return '[' + value.map(canonicalize).join(',') + ']';
  if (typeof value === 'object') {
    const obj = value as Record<string, unknown>;
    const keys = Object.keys(obj).sort();
    return '{' + keys.map((k) => quote(k) + ':' + canonicalize(obj[k])).join(',') + '}';
  }
  throw new Error(`Unsupported type for canonicalisation: ${typeof value}`);
}

function canonicalizeNumber(n: number): string {
  if (!Number.isFinite(n)) throw new Error('Non-finite double');
  // Whole numbers are emitted without a decimal point, matching Kotlin's canonicalizeDouble.
  if (Number.isInteger(n) && Math.abs(n) < Number.MAX_SAFE_INTEGER) {
    return BigInt(n).toString();
  }
  return n.toString();
}

function quote(s: string): string {
  let out = '"';
  for (const c of s) {
    switch (c) {
      case '"': out += '\\"'; break;
      case '\\': out += '\\\\'; break;
      case '\n': out += '\\n'; break;
      case '\r': out += '\\r'; break;
      case '\t': out += '\\t'; break;
      default: out += c;
    }
  }
  return out + '"';
}
