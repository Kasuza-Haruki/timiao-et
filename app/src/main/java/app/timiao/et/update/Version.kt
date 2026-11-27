package app.timiao.et.update

/** Stable compare for app and kernel tags. Accepts `v` prefixes and build suffixes. */
public fun normalizeVersion(value: String): String {
    val trimmed = value.trim()
    return if (trimmed.startsWith("v", ignoreCase = true)) trimmed.substring(1) else trimmed
}

/** > 0 when [left] is newer than [right]; 0 when equal; < 0 when older. */
public fun compareVersions(left: String, right: String): Int {
    val a = parseVersion(left)
    val b = parseVersion(right)
    val coreSize = maxOf(a.core.size, b.core.size)
    for (index in 0 until coreSize) {
        val result = a.core.getOrElse(index) { 0 }.compareTo(b.core.getOrElse(index) { 0 })
        if (result != 0) return result
    }
    // Absence of a pre-release suffix means a stable release, which is newer.
    if (a.pre.isEmpty() && b.pre.isEmpty()) return 0
    if (a.pre.isEmpty()) return 1
    if (b.pre.isEmpty()) return -1

    val preSize = maxOf(a.pre.size, b.pre.size)
    for (index in 0 until preSize) {
        val x = a.pre.getOrNull(index) ?: return -1
        val y = b.pre.getOrNull(index) ?: return 1
        val result = comparePreSegment(x, y)
        if (result != 0) return result
    }
    return 0
}

/**
 * Compares one pre-release segment. Digit runs inside a segment compare as
 * numbers, so `rc10` is newer than `rc9` instead of losing to a string compare.
 */
private fun comparePreSegment(left: String, right: String): Int {
    val a = splitAlphaNumeric(left)
    val b = splitAlphaNumeric(right)
    val size = maxOf(a.size, b.size)
    for (index in 0 until size) {
        val x = a.getOrNull(index) ?: return -1
        val y = b.getOrNull(index) ?: return 1
        val xNumber = x.toIntOrNull()
        val yNumber = y.toIntOrNull()
        val result = when {
            xNumber != null && yNumber != null -> xNumber.compareTo(yNumber)
            xNumber != null -> -1
            yNumber != null -> 1
            else -> x.compareTo(y)
        }
        if (result != 0) return result
    }
    return 0
}

/** Splits `rc10` into `rc`, `10`; keeps separators out of the comparison. */
private fun splitAlphaNumeric(value: String): List<String> {
    val parts = ArrayList<String>()
    val current = StringBuilder()
    var digits: Boolean? = null
    for (char in value) {
        val isDigit = char.isDigit()
        if (digits != null && digits != isDigit) {
            parts.add(current.toString())
            current.clear()
        }
        digits = isDigit
        current.append(char)
    }
    if (current.isNotEmpty()) parts.add(current.toString())
    return parts
}

public fun isNewerVersion(candidate: String, current: String): Boolean =
    compareVersions(candidate, current) > 0

private data class ParsedVersion(val core: List<Int>, val pre: List<String>)

private fun parseVersion(value: String): ParsedVersion {
    val cleaned = normalizeVersion(value).substringBefore('+')
    val coreText = cleaned.substringBefore('-')
    val preText = if ('-' in cleaned) cleaned.substringAfter('-') else ""
    val core = coreText.split('.').map { part ->
        part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
    }
    val pre = preText.split('.', '-', '~').filter { it.isNotEmpty() }
    return ParsedVersion(core, pre)
}
