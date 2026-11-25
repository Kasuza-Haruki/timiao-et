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
