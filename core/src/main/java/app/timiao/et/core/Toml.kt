package app.timiao.et.core

/**
 * Ordered TOML tree. Used to keep config fields this app does not model, so a
 * user's hand-written TOML survives an import/export cycle.
 */
public class TomlTable internal constructor(
    internal val entries: LinkedHashMap<String, TomlValue>,
) {
    public constructor() : this(LinkedHashMap())

    public val keys: Set<String>
        get() = entries.keys

    public fun isEmpty(): Boolean = entries.isEmpty()

    override fun equals(other: Any?): Boolean = other is TomlTable && entries == other.entries

    override fun hashCode(): Int = entries.hashCode()

    override fun toString(): String = "TomlTable(${entries.keys.joinToString()})"
}

internal sealed interface TomlValue {
    data class Str(val value: String) : TomlValue
    data class IntVal(val value: Long, val raw: String = value.toString()) : TomlValue
    data class FloatVal(val value: Double, val raw: String = value.toString()) : TomlValue
    data class BoolVal(val value: Boolean) : TomlValue
    data class RawVal(val raw: String) : TomlValue
    data class Arr(val items: MutableList<TomlValue> = mutableListOf()) : TomlValue
    data class Tbl(val entries: LinkedHashMap<String, TomlValue> = LinkedHashMap()) : TomlValue
}

internal fun TomlTable.copyDeep(): TomlTable = TomlTable(deepCopyMap(entries))

internal fun deepCopyMap(source: Map<String, TomlValue>): LinkedHashMap<String, TomlValue> {
    val out = LinkedHashMap<String, TomlValue>(source.size)
    for ((key, value) in source) out[key] = deepCopy(value)
    return out
}

internal fun deepCopy(value: TomlValue): TomlValue = when (value) {
    is TomlValue.Tbl -> TomlValue.Tbl(deepCopyMap(value.entries))
    is TomlValue.Arr -> TomlValue.Arr(value.items.mapTo(ArrayList(value.items.size)) { deepCopy(it) })
    else -> value
}

/** A syntax error at a byte offset in the decoded document. */
internal class TomlSyntaxError(val position: Int, message: String) : Exception(message)

/** User-facing TOML error. [line] and [column] are 1-based. */
public class TomlParseException(
    public val line: Int,
    public val column: Int,
    public val reason: String,
) : IllegalArgumentException("第 $line 行第 $column 列：$reason")

internal fun tomParseException(text: String, error: TomlSyntaxError): TomlParseException {
    val offset = error.position.coerceIn(0, text.length)
    var line = 1
    var lineStart = 0
    for (index in 0 until offset) {
        if (text[index] == '\n') {
            line++
            lineStart = index + 1
        }
    }
    val column = offset - lineStart + 1
    val reason = error.message.orEmpty().replace(Regex("""\s+at \d+$"""), "")
    return TomlParseException(line, column, reason)
}

internal class TomlParser(private val src: String) {
    private var pos = 0
    private val root = LinkedHashMap<String, TomlValue>()
    private var current: MutableMap<String, TomlValue> = root

    private fun fail(message: String): Nothing = throw TomlSyntaxError(pos, message)

    fun parse(): TomlTable {
        if (src.startsWith("﻿")) pos = 1
        while (true) {
            skipBlank()
            if (eof()) break
            if (peek() == '[') parseHeader() else parseKeyValue()
            skipLineTail()
        }
        return TomlTable(root)
    }

    private fun eof(): Boolean = pos >= src.length

    private fun peek(): Char = src[pos]

    private fun skipSpace() {
        while (!eof() && (src[pos] == ' ' || src[pos] == '\t' || src[pos] == '\r')) pos++
    }

    private fun skipBlank() {
        while (!eof()) {
            when (src[pos]) {
                ' ', '\t', '\r', '\n' -> pos++
                '#' -> while (!eof() && src[pos] != '\n') pos++
                else -> return
            }
        }
    }

    private fun skipLineTail() {
        skipSpace()
        if (!eof() && src[pos] == '#') while (!eof() && src[pos] != '\n') pos++
        if (!eof() && src[pos] == '\n') pos++
    }

    private fun parseHeader() {
        pos++ // [
        val isArray = !eof() && src[pos] == '['
        if (isArray) pos++
        val path = parseKeyPath()
        skipSpace()
        expect(']')
        if (isArray) expect(']')
        current = if (isArray) resolveArrayTable(path) else resolveTable(path)
    }

    private fun resolveTable(path: List<String>): MutableMap<String, TomlValue> {
        var table = root
        for (segment in path) {
            table = when (val existing = table[segment]) {
                null -> {
                    val child = LinkedHashMap<String, TomlValue>()
                    table[segment] = TomlValue.Tbl(child)
                    child
                }
                is TomlValue.Tbl -> existing.entries
                is TomlValue.Arr -> {
                    val last = existing.items.lastOrNull() as? TomlValue.Tbl
                        ?: fail("cannot enter non-table array '$segment' at $pos")
                    last.entries
                }
                else -> fail("key '$segment' is not a table at $pos")
            }
        }
        return table
    }

    private fun resolveArrayTable(path: List<String>): MutableMap<String, TomlValue> {
        require(path.isNotEmpty())
        var table = root
        for (segment in path.dropLast(1)) {
            table = when (val existing = table[segment]) {
                null -> {
                    val child = LinkedHashMap<String, TomlValue>()
                    table[segment] = TomlValue.Tbl(child)
                    child
                }
                is TomlValue.Tbl -> existing.entries
                is TomlValue.Arr -> (existing.items.lastOrNull() as? TomlValue.Tbl)?.entries
                    ?: fail("cannot enter non-table array '$segment' at $pos")
                else -> fail("key '$segment' is not a table at $pos")
            }
        }
        val name = path.last()
        val array = when (val existing = table[name]) {
            null -> TomlValue.Arr().also { table[name] = it }
            is TomlValue.Arr -> existing
            else -> fail("key '$name' is not an array of tables at $pos")
        }
        val child = LinkedHashMap<String, TomlValue>()
        array.items.add(TomlValue.Tbl(child))
        return child
    }

    private fun parseKeyPath(): List<String> {
        val parts = ArrayList<String>()
        while (true) {
            skipSpace()
            parts.add(parseKeyToken())
            skipSpace()
            if (!eof() && src[pos] == '.') {
                pos++
                continue
            }
            return parts
        }
    }

    private fun parseKeyToken(): String {
        if (eof()) fail("expected key at $pos")
        val c = src[pos]
        if (c == '"' || c == '\'') return parseString()
        val start = pos
        while (!eof()) {
            val ch = src[pos]
            if (ch.isLetterOrDigit() || ch == '_' || ch == '-') pos++ else break
        }
        if (start == pos) fail("expected key at $pos")
        return src.substring(start, pos)
    }

    private fun parseKeyValue() {
        val path = parseKeyPath()
        skipSpace()
        expect('=')
        skipSpace()
        assign(current, path, parseValue())
    }

    private fun assign(
        table: MutableMap<String, TomlValue>,
        path: List<String>,
        value: TomlValue,
    ) {
        var target = table
        for (segment in path.dropLast(1)) {
            val next = target[segment]
            target = when (next) {
                null -> {
                    val child = LinkedHashMap<String, TomlValue>()
                    target[segment] = TomlValue.Tbl(child)
                    child
                }
                is TomlValue.Tbl -> next.entries
                else -> fail("key '$segment' is not a table at $pos")
            }
        }
        target[path.last()] = value
    }

    private fun parseValue(): TomlValue {
        if (eof()) fail("expected value at $pos")
        return when (peek()) {
            '"', '\'' -> TomlValue.Str(parseString())
            '[' -> parseArray()
            '{' -> parseInlineTable()
            else -> parseScalar()
        }
    }

    private fun parseString(): String {
        if (src.startsWith("\"\"\"", pos)) return parseMultiline('"', true)
        if (src.startsWith("'''", pos)) return parseMultiline('\'', false)
        val quote = src[pos++]
        val out = StringBuilder()
        while (!eof()) {
            val c = src[pos++]
            when {
                c == quote -> return out.toString()
                c == '\\' && quote == '"' -> out.append(parseEscape())
                else -> out.append(c)
            }
        }
        fail("unterminated string at $pos")
    }

    private fun parseMultiline(quote: Char, escapes: Boolean): String {
        pos += 3
        if (!eof() && src[pos] == '\r') pos++
        if (!eof() && src[pos] == '\n') pos++
        val out = StringBuilder()
        while (!eof()) {
            if (src.startsWith("$quote$quote$quote", pos)) {
                pos += 3
                return out.toString()
            }
            val c = src[pos++]
            if (escapes && c == '\\') out.append(parseEscape()) else out.append(c)
        }
        fail("unterminated multiline string at $pos")
    }

    private fun parseEscape(): String {
        if (eof()) fail("unterminated escape at $pos")
        return when (val c = src[pos++]) {
            'n' -> "\n"
            't' -> "\t"
            'r' -> "\r"
            'b' -> "\b"
            'f' -> "\u000C"
            '"' -> "\""
            '\\' -> "\\"
            '/' -> "/"
            'u' -> {
                val hex = src.substring(pos, pos + 4)
                pos += 4
                hex.toInt(16).toChar().toString()
            }
            'U' -> {
                val hex = src.substring(pos, pos + 8)
                pos += 8
                String(Character.toChars(hex.toInt(16)))
            }
            else -> fail("unknown escape \\$c at $pos")
        }
    }

    private fun parseArray(): TomlValue.Arr {
        pos++ // [
        val array = TomlValue.Arr()
        while (true) {
            skipBlank()
            if (eof()) fail("unterminated array at $pos")
            if (src[pos] == ']') {
                pos++
                return array
            }
            array.items.add(parseValue())
            skipBlank()
            if (eof()) fail("unterminated array at $pos")
            when (src[pos]) {
                ',' -> pos++
                ']' -> {
                    pos++
                    return array
                }
                else -> fail("expected ',' or ']' at $pos")
            }
        }
    }

    private fun parseInlineTable(): TomlValue.Tbl {
        pos++ // {
        val entries = LinkedHashMap<String, TomlValue>()
        while (true) {
            skipSpace()
            if (eof()) fail("unterminated inline table at $pos")
            if (src[pos] == '}') {
                pos++
                return TomlValue.Tbl(entries)
            }
            val path = parseKeyPath()
            skipSpace()
            expect('=')
            skipSpace()
            assign(entries, path, parseValue())
            skipSpace()
            if (!eof() && src[pos] == ',') pos++ else if (!eof() && src[pos] == '}') {
                pos++
                return TomlValue.Tbl(entries)
            } else {
                fail("expected ',' or '}' at $pos")
            }
        }
    }

    private fun parseScalar(): TomlValue {
        val start = pos
        while (!eof()) {
            when (src[pos]) {
                ' ', '\t', '\r', '\n', ',', ']', '}', '#' -> break
                else -> pos++
            }
        }
        val raw = src.substring(start, pos)
        if (raw.isEmpty()) fail("expected value at $start")
        return when {
            raw == "true" -> TomlValue.BoolVal(true)
            raw == "false" -> TomlValue.BoolVal(false)
            INT_PATTERN.matches(raw) -> TomlValue.IntVal(parseInteger(raw), raw)
            FLOAT_PATTERN.matches(raw) -> TomlValue.FloatVal(
                raw.replace("_", "").toDoubleOrNull() ?: 0.0,
                raw,
            )
            else -> TomlValue.RawVal(raw)
        }
    }

    private fun expect(c: Char) {
        if (eof() || src[pos] != c) fail("expected '$c' at $pos")
        pos++
    }

    private companion object {
        val INT_PATTERN = Regex("[+-]?(0x[0-9A-Fa-f_]+|0o[0-7_]+|0b[01_]+|[0-9][0-9_]*)")
        val FLOAT_PATTERN = Regex(
            "[+-]?(inf|nan|[0-9][0-9_]*(\\.[0-9_]+)?([eE][+-]?[0-9_]+)?" +
                "|\\.[0-9_]+([eE][+-]?[0-9_]+)?)",
        )

        fun parseInteger(raw: String): Long {
            val text = raw.replace("_", "")
            val sign = if (text.startsWith("-")) -1L else 1L
            val body = text.removePrefix("+").removePrefix("-")
            return sign * when {
                body.startsWith("0x") -> body.substring(2).toLongOrNull(16) ?: 0L
                body.startsWith("0o") -> body.substring(2).toLongOrNull(8) ?: 0L
                body.startsWith("0b") -> body.substring(2).toLongOrNull(2) ?: 0L
                else -> body.toLongOrNull() ?: 0L
            }
        }
    }
}

internal object TomlWriter {
    fun write(root: TomlTable): String {
        val out = StringBuilder()
        writeTable(out, root.entries, emptyList(), header = false, array = false)
        return out.toString()
    }

    private fun writeTable(
        out: StringBuilder,
        entries: Map<String, TomlValue>,
        path: List<String>,
        header: Boolean,
        array: Boolean,
    ) {
        val sections = entries.filterValues(::isSection)
        val scalars = entries.filterValues { !isSection(it) }

        if (header) {
            if (out.isNotEmpty()) out.append('\n')
            if (array) {
                out.append("[[")
                out.append(path.joinToString(".") { encodeKey(it) })
                out.append("]]\n")
            } else {
                out.append('[')
                out.append(path.joinToString(".") { encodeKey(it) })
                out.append("]\n")
            }
        }
        for ((key, value) in scalars) {
            out.append(encodeKey(key)).append(" = ").append(inline(value)).append('\n')
        }
        for ((key, value) in sections) {
            when (value) {
                is TomlValue.Tbl -> writeTable(out, value.entries, path + key, true, false)
                is TomlValue.Arr -> for (item in value.items) {
                    if (item is TomlValue.Tbl) writeTable(out, item.entries, path + key, true, true)
                }
                else -> Unit
            }
        }
    }

    private fun isSection(value: TomlValue): Boolean = when (value) {
        is TomlValue.Tbl -> true
        is TomlValue.Arr -> value.items.isNotEmpty() && value.items.all { it is TomlValue.Tbl }
        else -> false
    }

    private fun inline(value: TomlValue): String = when (value) {
        is TomlValue.Str -> encodeString(value.value)
        is TomlValue.IntVal -> value.raw
        is TomlValue.FloatVal -> value.raw
        is TomlValue.BoolVal -> value.value.toString()
        is TomlValue.RawVal -> value.raw
        is TomlValue.Arr -> value.items.joinToString(", ", "[", "]") { inline(it) }
        is TomlValue.Tbl -> value.entries.entries.joinToString(", ", "{ ", " }") {
            "${encodeKey(it.key)} = ${inline(it.value)}"
        }
    }

    private fun encodeKey(key: String): String {
        if (BARE_KEY.matches(key)) return key
        return encodeString(key)
    }

    private fun encodeString(value: String): String {
        val out = StringBuilder("\"")
        for (c in value) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        return out.append('"').toString()
    }

    private val BARE_KEY = Regex("[A-Za-z0-9_-]+")
}
