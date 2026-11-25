package app.timiao.et.update

/** Minimal fields of a GitHub release; no JSON library needed. */
public data class GitHubRelease(
    val tagName: String,
    val htmlUrl: String,
    val body: String?,
    val prerelease: Boolean,
    val draft: Boolean,
)

public object GitHubReleaseParser {
    public fun parse(json: String): GitHubRelease? {
        val tag = stringField(json, "tag_name")?.takeIf { it.isNotBlank() } ?: return null
        return GitHubRelease(
            tagName = tag,
            htmlUrl = stringField(json, "html_url").orEmpty(),
            body = stringField(json, "body"),
            prerelease = boolField(json, "prerelease") ?: false,
            draft = boolField(json, "draft") ?: false,
        )
    }

    /** Finds `"field": "..."`. Escaped quotes inside strings are handled. */
    internal fun stringField(json: String, field: String): String? {
        var index = valueStart(json, field) ?: return null
        if (index >= json.length || json[index] != '"') return null
        index++
        val text = StringBuilder()
        while (index < json.length) {
            when (val char = json[index]) {
                '\\' -> {
                    index++
                    if (index >= json.length) return null
                    when (val escaped = json[index]) {
                        '"', '\\', '/' -> text.append(escaped)
                        'b' -> text.append('\b')
                        'f' -> text.append('\u000C')
                        'n' -> text.append('\n')
                        'r' -> text.append('\r')
                        't' -> text.append('\t')
                        'u' -> {
                            if (index + 4 >= json.length) return null
                            val code = json.substring(index + 1, index + 5).toIntOrNull(16) ?: return null
                            text.append(code.toChar())
                            index += 4
                        }
                        else -> text.append(escaped)
                    }
                }
                '"' -> return text.toString()
                else -> text.append(char)
            }
            index++
        }
        return null
    }

    internal fun boolField(json: String, field: String): Boolean? {
        val index = valueStart(json, field) ?: return null
        return when {
            json.startsWith("true", index) -> true
            json.startsWith("false", index) -> false
            else -> null
        }
    }

    private fun valueStart(json: String, field: String): Int? {
        val keyIndex = json.indexOf("\"$field\"")
        if (keyIndex < 0) return null
        var index = keyIndex + field.length + 2
        while (index < json.length && json[index].isWhitespace()) index++
        if (index >= json.length || json[index] != ':') return null
        index++
        while (index < json.length && json[index].isWhitespace()) index++
        return index
    }
}
