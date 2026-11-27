package com.yandex.divkit.multiplatform

import engine.state.DivVariableValue
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Evaluator for the expression subset used by the bundled SDUI cards.
 *
 * DivKit cards are plain JSON, so action payloads, labels and visibility conditions address
 * runtime state through `@{...}`. The Android renderer does not embed the full DivKit
 * expression engine; this parser covers the operators and helpers the demo cards rely on
 * (arithmetic, comparisons, ternaries and the common array/date/number helpers).
 */
internal fun evaluateDivExpression(
    source: String,
    variables: Map<String, DivVariableValue>,
    rowContext: Map<String, Any?>
): Any? = ExpressionParser(source, variables, rowContext).parse()

internal fun divExpressionDisplay(value: Any?): String = when (value) {
    null -> ""
    is Boolean -> value.toString()
    is Number -> formatNumber(value.toDouble())
    is List<*> -> value.joinToString(",") { divExpressionDisplay(it) }
    else -> value.toString()
}

private fun formatNumber(value: Double): String {
    if (value.isNaN()) return ""
    if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
    if (value == value.toLong().toDouble()) return value.toLong().toString()
    return BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
}

private class ExpressionParser(
    private val source: String,
    private val variables: Map<String, DivVariableValue>,
    private val rowContext: Map<String, Any?>
) {
    private var pos = 0

    fun parse(): Any? {
        val value = parseTernary()
        skipWhitespace()
        if (pos < source.length) error("Unexpected '${source.substring(pos)}'")
        return value
    }

    private fun parseTernary(): Any? {
        val condition = parseOr()
        skipWhitespace()
        if (match("?")) {
            val whenTrue = parseTernary()
            skipWhitespace()
            expect(":")
            val whenFalse = parseTernary()
            return if (truthy(condition)) whenTrue else whenFalse
        }
        return condition
    }

    private fun parseOr(): Any? {
        var left = parseAnd()
        while (true) {
            skipWhitespace()
            if (!match("||")) return left
            val right = parseAnd()
            left = truthy(left) || truthy(right)
        }
    }

    private fun parseAnd(): Any? {
        var left = parseEquality()
        while (true) {
            skipWhitespace()
            if (!match("&&")) return left
            val right = parseEquality()
            left = truthy(left) && truthy(right)
        }
    }

    private fun parseEquality(): Any? {
        var left = parseComparison()
        while (true) {
            skipWhitespace()
            left = when {
                match("==") -> equalsValue(left, parseComparison())
                match("!=") -> !equalsValue(left, parseComparison())
                else -> return left
            }
        }
    }

    private fun parseComparison(): Any? {
        var left = parseAdditive()
        while (true) {
            skipWhitespace()
            left = when {
                match(">=") -> compare(left, parseAdditive()) >= 0
                match("<=") -> compare(left, parseAdditive()) <= 0
                match(">") -> compare(left, parseAdditive()) > 0
                match("<") -> compare(left, parseAdditive()) < 0
                else -> return left
            }
        }
    }

    private fun parseAdditive(): Any? {
        var left = parseMultiplicative()
        while (true) {
            skipWhitespace()
            left = when {
                match("+") -> add(left, parseMultiplicative())
                match("-") -> number(left) - number(parseMultiplicative())
                else -> return left
            }
        }
    }

    private fun parseMultiplicative(): Any? {
        var left = parseUnary()
        while (true) {
            skipWhitespace()
            left = when {
                match("*") -> number(left) * number(parseUnary())
                match("/") -> number(left) / number(parseUnary())
                match("%") -> number(left) % number(parseUnary())
                else -> return left
            }
        }
    }

    private fun parseUnary(): Any? {
        skipWhitespace()
        return when {
            match("!") -> !truthy(parseUnary())
            match("-") -> -number(parseUnary())
            match("+") -> number(parseUnary())
            else -> parsePrimary()
        }
    }

    private fun parsePrimary(): Any? {
        skipWhitespace()
        if (pos >= source.length) return ""
        val c = source[pos]
        if (c == '(') {
            pos++
            val value = parseTernary()
            skipWhitespace()
            expect(")")
            return value
        }
        if (c == '\'' || c == '"') return parseString(c)
        if (c.isDigit() || (c == '.' && pos + 1 < source.length && source[pos + 1].isDigit())) {
            return parseNumber()
        }
        if (c.isLetter() || c == '_' || c == '$') return parseIdentifier()
        pos++
        return c.toString()
    }

    private fun parseString(quote: Char): String {
        pos++
        val out = StringBuilder()
        while (pos < source.length) {
            val c = source[pos++]
            when {
                c == quote -> return out.toString()
                c == '\\' && pos < source.length -> {
                    when (val escaped = source[pos++]) {
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        else -> out.append(escaped)
                    }
                }
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    private fun parseNumber(): Double {
        val start = pos
        while (pos < source.length && (source[pos].isDigit() || source[pos] == '.')) pos++
        return source.substring(start, pos).toDoubleOrNull() ?: 0.0
    }

    private fun parseIdentifier(): Any? {
        val start = pos
        while (pos < source.length && (source[pos].isLetterOrDigit() || source[pos] == '_' || source[pos] == '.' || source[pos] == '$')) pos++
        val name = source.substring(start, pos)
        skipWhitespace()
        if (pos < source.length && source[pos] == '(') {
            pos++
            val args = mutableListOf<Any?>()
            skipWhitespace()
            if (pos < source.length && source[pos] != ')') {
                while (true) {
                    args.add(parseTernary())
                    skipWhitespace()
                    if (match(",")) continue
                    break
                }
            }
            expect(")")
            return call(name, args)
        }
        return resolve(name)
    }

    private fun resolve(name: String): Any? {
        return when {
            name == "true" -> true
            name == "false" -> false
            name == "null" -> null
            name.startsWith("item.") -> rowContext[name.removePrefix("item.")]
            name.startsWith("user.item.") -> rowContext[name.removePrefix("user.item.")]
            else -> variableValue(name)
        }
    }

    /** Variables may hold a dict; `cust.order.total` resolves through its `cust.order` base. */
    private fun variableValue(name: String): Any? {
        variables[name]?.toValue()?.let { return it }
        var dot = name.lastIndexOf('.')
        while (dot > 0) {
            val base = variables[name.substring(0, dot)]
            if (base is DivVariableValue.DictVal) {
                var value: Any? = base.toValue()
                for (part in name.substring(dot + 1).split('.')) {
                    value = (value as? Map<*, *>)?.get(part)
                }
                return value
            }
            dot = name.lastIndexOf('.', dot - 1)
        }
        return null
    }

    private fun call(name: String, args: List<Any?>): Any? {
        val lower = name.lowercase()
        return when (lower) {
            "tonumber", "to_number" -> number(args.getOrNull(0))
            "tointeger", "to_integer", "toint" -> number(args.getOrNull(0)).toLong()
            "tostring", "to_string" -> divExpressionDisplay(args.getOrNull(0))
            "toboolean", "to_boolean" -> truthy(args.getOrNull(0))
            "len", "length", "size" -> when (val value = args.getOrNull(0)) {
                is List<*> -> value.size
                is String -> value.length
                else -> divExpressionDisplay(value).length
            }
            "getarraystring", "get_array_string" -> arrayElement(args, false)
            "getarrayoptstring", "get_array_opt_string" -> arrayElement(args, true)
            "getarrayinteger", "get_array_integer" -> arrayElement(args, false)?.let { number(it).toLong() }
            "getarrayoptinteger", "get_array_opt_integer" -> arrayElement(args, true)?.let { number(it).toLong() }
            "mod" -> number(args.getOrNull(0)) % number(args.getOrNull(1))
            "sum" -> args.fold(0.0) { acc, v -> acc + number(v) }
            "sub" -> number(args.getOrNull(0)) - number(args.getOrNull(1))
            "mul" -> number(args.getOrNull(0)) * number(args.getOrNull(1))
            "div" -> number(args.getOrNull(0)) / number(args.getOrNull(1))
            "min" -> min(number(args.getOrNull(0)), number(args.getOrNull(1)))
            "max" -> max(number(args.getOrNull(0)), number(args.getOrNull(1)))
            "abs" -> abs(number(args.getOrNull(0)))
            "floor" -> floor(number(args.getOrNull(0)))
            "ceil" -> ceil(number(args.getOrNull(0)))
            "round" -> round(number(args.getOrNull(0)))
            "nowlocal", "now_local", "now" -> System.currentTimeMillis()
            "formatdateaslocal", "format_date_as_local", "formatdate", "format_date" -> {
                val millis = number(args.getOrNull(0)).toLong()
                val pattern = divExpressionDisplay(args.getOrNull(1)).ifBlank { "yyyy-MM-dd HH:mm:ss" }
                SimpleDateFormat(pattern, Locale.US).format(Date(millis))
            }
            "if" -> if (truthy(args.getOrNull(0))) args.getOrNull(1) else args.getOrNull(2)
            "coalesce" -> args.firstOrNull { it != null && divExpressionDisplay(it).isNotEmpty() }
            else -> ""
        }
    }

    private fun arrayElement(args: List<Any?>, optional: Boolean): Any? {
        val list = args.getOrNull(0)
        val index = number(args.getOrNull(1)).toInt()
        val value = when (list) {
            is List<*> -> list.getOrNull(index)
            is String -> if (index in list.indices) list[index].toString() else null
            else -> null
        }
        if (value != null) return value
        return if (optional) args.getOrNull(2) ?: "" else ""
    }

    private fun add(left: Any?, right: Any?): Any? {
        if (left is Number && right is Number) return left.toDouble() + right.toDouble()
        if (left is String || right is String) return divExpressionDisplay(left) + divExpressionDisplay(right)
        return number(left) + number(right)
    }

    private fun compare(left: Any?, right: Any?): Int {
        if (left is Number || right is Number) return number(left).compareTo(number(right))
        return divExpressionDisplay(left).compareTo(divExpressionDisplay(right))
    }

    private fun equalsValue(left: Any?, right: Any?): Boolean {
        if (left is Number || right is Number) return number(left) == number(right)
        return divExpressionDisplay(left) == divExpressionDisplay(right)
    }

    private fun truthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is Number -> value.toDouble() != 0.0
        is List<*> -> value.isNotEmpty()
        else -> value.toString().let { it.isNotEmpty() && !it.equals("false", true) && it != "0" }
    }

    private fun number(value: Any?): Double = when (value) {
        null -> 0.0
        is Number -> value.toDouble()
        is Boolean -> if (value) 1.0 else 0.0
        else -> value.toString().trim().toDoubleOrNull() ?: 0.0
    }

    private fun skipWhitespace() {
        while (pos < source.length && source[pos].isWhitespace()) pos++
    }

    private fun match(token: String): Boolean {
        skipWhitespace()
        if (!source.startsWith(token, pos)) return false
        pos += token.length
        return true
    }

    private fun expect(token: String) {
        skipWhitespace()
        if (!source.startsWith(token, pos)) error("Expected '$token' at ${source.substring(pos)}")
        pos += token.length
    }
}

private fun DivVariableValue.toValue(): Any? = when (this) {
    is DivVariableValue.Str -> value
    is DivVariableValue.IntVal -> value
    is DivVariableValue.DoubleVal -> value
    is DivVariableValue.Bool -> value
    is DivVariableValue.ArrayVal -> values.map { it.toValue() }
    is DivVariableValue.DictVal -> values.mapValues { it.value.toValue() }
}
