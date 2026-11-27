import { display, toNumber, truthy, type DivVars, type RowContext } from './values.ts'

/**
 * Port of the expression subset Android evaluates (`DivKitExpression.kt`).
 *
 * Cards are authored against this parser, not against DivKit's own expression
 * language: `if(...)` is a function, unknown functions evaluate to an empty
 * string, and a bare unknown identifier is not an error. Keeping the port
 * literal is the only way the preview and the device agree on a label.
 */

export function evaluateExpression(source: string, vars: DivVars, row: RowContext): unknown {
  return new Parser(source, vars, row).parse()
}

class Parser {
  private pos = 0
  private readonly source: string
  private readonly vars: DivVars
  private readonly row: RowContext

  constructor(source: string, vars: DivVars, row: RowContext) {
    this.source = source
    this.vars = vars
    this.row = row
  }

  parse(): unknown {
    const value = this.parseTernary()
    this.skipWhitespace()
    if (this.pos < this.source.length) {
      throw new Error(`Unexpected '${this.source.slice(this.pos)}'`)
    }
    return value
  }

  private parseTernary(): unknown {
    const condition = this.parseOr()
    this.skipWhitespace()
    if (this.match('?')) {
      const whenTrue = this.parseTernary()
      this.skipWhitespace()
      this.expect(':')
      const whenFalse = this.parseTernary()
      return truthy(condition) ? whenTrue : whenFalse
    }
    return condition
  }

  private parseOr(): unknown {
    let left = this.parseAnd()
    while (this.match('||')) {
      const right = this.parseAnd()
      left = truthy(left) || truthy(right)
    }
    return left
  }

  private parseAnd(): unknown {
    let left = this.parseEquality()
    while (this.match('&&')) {
      const right = this.parseEquality()
      left = truthy(left) && truthy(right)
    }
    return left
  }

  private parseEquality(): unknown {
    let left = this.parseComparison()
    for (;;) {
      if (this.match('==')) left = equalsValue(left, this.parseComparison())
      else if (this.match('!=')) left = !equalsValue(left, this.parseComparison())
      else return left
    }
  }

  private parseComparison(): unknown {
    let left = this.parseAdditive()
    for (;;) {
      if (this.match('>=')) left = compare(left, this.parseAdditive()) >= 0
      else if (this.match('<=')) left = compare(left, this.parseAdditive()) <= 0
      else if (this.match('>')) left = compare(left, this.parseAdditive()) > 0
      else if (this.match('<')) left = compare(left, this.parseAdditive()) < 0
      else return left
    }
  }

  private parseAdditive(): unknown {
    let left = this.parseMultiplicative()
    for (;;) {
      if (this.match('+')) left = add(left, this.parseMultiplicative())
      else if (this.match('-')) left = toNumber(left) - toNumber(this.parseMultiplicative())
      else return left
    }
  }

  private parseMultiplicative(): unknown {
    let left = this.parseUnary()
    for (;;) {
      if (this.match('*')) left = toNumber(left) * toNumber(this.parseUnary())
      else if (this.match('/')) left = toNumber(left) / toNumber(this.parseUnary())
      else if (this.match('%')) left = toNumber(left) % toNumber(this.parseUnary())
      else return left
    }
  }

  private parseUnary(): unknown {
    this.skipWhitespace()
    if (this.match('!')) return !truthy(this.parseUnary())
    if (this.match('-')) return -toNumber(this.parseUnary())
    if (this.match('+')) return toNumber(this.parseUnary())
    return this.parsePrimary()
  }

  private parsePrimary(): unknown {
    this.skipWhitespace()
    if (this.pos >= this.source.length) return ''
    const char = this.source[this.pos]
    if (char === '(') {
      this.pos += 1
      const value = this.parseTernary()
      this.skipWhitespace()
      this.expect(')')
      return value
    }
    if (char === "'" || char === '"') return this.parseString(char)
    if (isDigit(char) || (char === '.' && isDigit(this.source[this.pos + 1] ?? ''))) {
      return this.parseNumber()
    }
    if (isIdentifierStart(char)) return this.parseIdentifier()
    this.pos += 1
    return char
  }

  private parseString(quote: string): string {
    this.pos += 1
    let out = ''
    while (this.pos < this.source.length) {
      const char = this.source[this.pos]
      this.pos += 1
      if (char === quote) return out
      if (char === '\\' && this.pos < this.source.length) {
        const escaped = this.source[this.pos]
        this.pos += 1
        if (escaped === 'n') out += '\n'
        else if (escaped === 'r') out += '\r'
        else if (escaped === 't') out += '\t'
        else out += escaped
        continue
      }
      out += char
    }
    return out
  }

  private parseNumber(): number {
    const start = this.pos
    while (this.pos < this.source.length && (isDigit(this.source[this.pos]) || this.source[this.pos] === '.')) {
      this.pos += 1
    }
    return Number(this.source.slice(start, this.pos))
  }

  private parseIdentifier(): unknown {
    const start = this.pos
    while (this.pos < this.source.length && isIdentifierPart(this.source[this.pos])) this.pos += 1
    const name = this.source.slice(start, this.pos)
    this.skipWhitespace()
    if (this.pos < this.source.length && this.source[this.pos] === '(') {
      this.pos += 1
      const args: unknown[] = []
      this.skipWhitespace()
      if (this.pos < this.source.length && this.source[this.pos] !== ')') {
        for (;;) {
          args.push(this.parseTernary())
          this.skipWhitespace()
          if (this.match(',')) continue
          break
        }
      }
      this.expect(')')
      return callFunction(name, args)
    }
    return this.resolve(name)
  }

  private resolve(name: string): unknown {
    if (name === 'true') return true
    if (name === 'false') return false
    if (name === 'null') return null
    if (name.startsWith('item.')) return this.row[name.slice('item.'.length)]
    if (name.startsWith('user.item.')) return this.row[name.slice('user.item.'.length)]
    return this.vars[name] ?? null
  }

  private skipWhitespace(): void {
    while (this.pos < this.source.length && /\s/.test(this.source[this.pos])) this.pos += 1
  }

  private match(token: string): boolean {
    this.skipWhitespace()
    if (!this.source.startsWith(token, this.pos)) return false
    this.pos += token.length
    return true
  }

  private expect(token: string): void {
    this.skipWhitespace()
    if (!this.source.startsWith(token, this.pos)) {
      throw new Error(`Expected '${token}' at ${this.source.slice(this.pos)}`)
    }
    this.pos += token.length
  }
}

function isDigit(char: string): boolean {
  return char >= '0' && char <= '9'
}

function isIdentifierStart(char: string): boolean {
  return /[A-Za-z_$]/.test(char)
}

function isIdentifierPart(char: string): boolean {
  return /[A-Za-z0-9_$.]/.test(char)
}

/** Kotlin `call(...)`: unknown functions evaluate to `""`, never to an error. */
function callFunction(name: string, args: unknown[]): unknown {
  const arg = (index: number) => args[index] ?? null
  switch (name.toLowerCase()) {
    case 'tonumber':
    case 'to_number':
      return toNumber(arg(0))
    case 'tointeger':
    case 'to_integer':
    case 'toint':
      return Math.trunc(toNumber(arg(0)))
    case 'tostring':
    case 'to_string':
      return display(arg(0))
    case 'toboolean':
    case 'to_boolean':
      return truthy(arg(0))
    case 'len':
    case 'length':
    case 'size': {
      const value = arg(0)
      if (Array.isArray(value)) return value.length
      if (typeof value === 'string') return value.length
      return display(value).length
    }
    case 'getarraystring':
    case 'get_array_string':
      return arrayElement(args, false)
    case 'getarrayoptstring':
    case 'get_array_opt_string':
      return arrayElement(args, true)
    case 'getarrayinteger':
    case 'get_array_integer': {
      const value = arrayElement(args, false)
      return value === null || value === undefined ? null : Math.trunc(toNumber(value))
    }
    case 'getarrayoptinteger':
    case 'get_array_opt_integer': {
      const value = arrayElement(args, true)
      return value === null || value === undefined ? null : Math.trunc(toNumber(value))
    }
    case 'mod':
      return toNumber(arg(0)) % toNumber(arg(1))
    case 'sum':
      return args.reduce<number>((total, value) => total + toNumber(value), 0)
    case 'sub':
      return toNumber(arg(0)) - toNumber(arg(1))
    case 'mul':
      return toNumber(arg(0)) * toNumber(arg(1))
    case 'div':
      return toNumber(arg(0)) / toNumber(arg(1))
    case 'min':
      return Math.min(toNumber(arg(0)), toNumber(arg(1)))
    case 'max':
      return Math.max(toNumber(arg(0)), toNumber(arg(1)))
    case 'abs':
      return Math.abs(toNumber(arg(0)))
    case 'floor':
      return Math.floor(toNumber(arg(0)))
    case 'ceil':
      return Math.ceil(toNumber(arg(0)))
    case 'round':
      return Math.round(toNumber(arg(0)))
    case 'nowlocal':
    case 'now_local':
    case 'now':
      return Date.now()
    case 'formatdateaslocal':
    case 'format_date_as_local':
    case 'formatdate':
    case 'format_date':
      return formatDate(toNumber(arg(0)), display(arg(1)) || 'yyyy-MM-dd HH:mm:ss')
    case 'if':
      return truthy(arg(0)) ? arg(1) : arg(2)
    case 'coalesce':
      return args.find((value) => value !== null && value !== undefined && display(value).length > 0) ?? null
    default:
      return ''
  }
}

function arrayElement(args: unknown[], optional: boolean): unknown {
  const list = args[0]
  const index = Math.trunc(toNumber(args[1]))
  const value = Array.isArray(list) ? (list[index] ?? null) : typeof list === 'string' ? (list[index] ?? null) : null
  if (value !== null) return value
  return optional ? (args[2] ?? '') : ''
}

/** Java `SimpleDateFormat` subset used by cards (`yyyy`, `MM`, `dd`, `HH`, `mm`, `ss`). */
function formatDate(millis: number, pattern: string): string {
  const date = new Date(millis)
  if (Number.isNaN(date.getTime())) return ''
  const pad = (value: number, width = 2) => String(value).padStart(width, '0')
  const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
  const tokens: Record<string, string> = {
    yyyy: String(date.getFullYear()),
    MMMM: months[date.getMonth()],
    MMM: months[date.getMonth()],
    MM: pad(date.getMonth() + 1),
    dd: pad(date.getDate()),
    HH: pad(date.getHours()),
    mm: pad(date.getMinutes()),
    ss: pad(date.getSeconds()),
  }
  return pattern.replace(/yyyy|MMMM|MMM|MM|dd|HH|mm|ss/g, (token) => tokens[token] ?? token)
}

function add(left: unknown, right: unknown): unknown {
  if (typeof left === 'number' && typeof right === 'number') return left + right
  if (typeof left === 'string' || typeof right === 'string') return display(left) + display(right)
  return toNumber(left) + toNumber(right)
}

function compare(left: unknown, right: unknown): number {
  if (typeof left === 'number' || typeof right === 'number') return toNumber(left) - toNumber(right)
  const a = display(left)
  const b = display(right)
  return a < b ? -1 : a > b ? 1 : 0
}

function equalsValue(left: unknown, right: unknown): boolean {
  if (typeof left === 'number' || typeof right === 'number') return toNumber(left) === toNumber(right)
  return display(left) === display(right)
}

const EXPRESSION = /@\{([^}]+)\}/g

/** Kotlin `evalExpr`: `@{...}` interpolation inside a larger string. */
export function evalExpr(input: string, vars: DivVars, row: RowContext): string {
  if (!input.includes('@{')) return input
  return input.replace(EXPRESSION, (_match, body: string) => {
    try {
      return display(evaluateExpression(body, vars, row))
    } catch {
      return ''
    }
  })
}

/**
 * Kotlin `resolveJsonExpressions`: resolves a payload tree. A value that is one
 * whole `@{...}` expression keeps the expression's type (array, short number,
 * boolean); everything else becomes text.
 */
export function resolveJsonExpressions(value: unknown, vars: DivVars, row: RowContext): unknown {
  if (typeof value === 'string') {
    const whole = isWholeExpression(value)
    if (whole) {
      const evaluated = evaluateExpression(value.slice(2, -1), vars, row)
      if (Array.isArray(evaluated)) return evaluated.map(expressionValueToJson)
    }
    const text = evalExpr(value, vars, row)
    if (whole) {
      if (isSafeNumericLiteral(text)) return Number(text)
      if (text === 'true' || text === 'false') return text === 'true'
    }
    return text
  }
  if (Array.isArray(value)) return value.map((item) => resolveJsonExpressions(item, vars, row))
  if (value && typeof value === 'object') {
    const out: Record<string, unknown> = {}
    for (const [key, item] of Object.entries(value as Record<string, unknown>)) {
      out[key] = resolveJsonExpressions(item, vars, row)
    }
    return out
  }
  return value
}

function isWholeExpression(value: string): boolean {
  return value.startsWith('@{') && value.endsWith('}') && !value.slice(2, -1).includes('@{')
}

function expressionValueToJson(value: unknown): unknown {
  if (value === undefined) return null
  if (value === null || typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') {
    return value
  }
  if (Array.isArray(value)) return value.map(expressionValueToJson)
  if (typeof value === 'object') {
    const out: Record<string, unknown> = {}
    for (const [key, item] of Object.entries(value as Record<string, unknown>)) out[key] = expressionValueToJson(item)
    return out
  }
  return String(value)
}

/**
 * Numeric action arguments are convenience, not semantics. Coercing a long digit
 * string (a signal safety number, an account id, a barcode) to a number loses
 * digits, so only short canonical literals are converted.
 */
export function isSafeNumericLiteral(value: string): boolean {
  if (value.length === 0 || value.length > 15) return false
  if (value.length > 1 && value[0] === '0' && isDigit(value[1])) return false
  if (!/^[+-]?(\d+(\.\d*)?|\.\d+)$/.test(value)) return false
  return Number.isFinite(Number(value))
}
