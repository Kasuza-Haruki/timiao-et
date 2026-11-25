package app.timiao.et.core.diag

import app.timiao.et.core.NetworkConfig
import app.timiao.et.core.NetworkStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Result of one diagnostic check. [key] is language-neutral for app-side labels. */
public enum class DiagSeverity(public val key: String) {
    Pass("pass"),
    Warn("warn"),
    Fail("fail");

    public companion object {
        public fun worst(values: Iterable<DiagSeverity>): DiagSeverity = when {
            values.any { it == Fail } -> Fail
            values.any { it == Warn } -> Warn
            else -> Pass
        }
    }
}

/** One check: short conclusion plus concrete advice. Texts are already localized. */
public data class DiagItem(
    val id: String,
    val title: String,
    val severity: DiagSeverity,
    val summary: String,
    val advice: String? = null,
    val details: List<String> = emptyList(),
)

/** Everything the diagnostics engine needs. [rawInfosJson] is the optional JNI payload. */
public data class DiagnosticInput(
    val config: NetworkConfig?,
    val status: NetworkStatus?,
    val rawInfosJson: String? = null,
)

/** Copyable, secret-free report. Construct only through [DiagnosticsEngine]. */
public data class DiagnosticReport(
    val items: List<DiagItem>,
    val generatedAtEpochMs: Long,
) {
    public val overall: DiagSeverity get() = DiagSeverity.worst(items.map { it.severity })

    public val passCount: Int get() = items.count { it.severity == DiagSeverity.Pass }
    public val warnCount: Int get() = items.count { it.severity == DiagSeverity.Warn }
    public val failCount: Int get() = items.count { it.severity == DiagSeverity.Fail }

    public fun headline(strings: DiagStrings = ChineseDiagStrings): String =
        strings.get("diag_headline_${overall.key}")

    /** Plain-text summary suitable for the clipboard. Never contains the network secret. */
    public fun plainText(strings: DiagStrings = ChineseDiagStrings): String {
        val text = StringBuilder()
        text.appendLine(strings.get("diag_report_title"))
        text.appendLine(strings.get("diag_report_conclusion", headline(strings)))
        text.appendLine(strings.get("diag_report_time", formatTime(generatedAtEpochMs)))
        text.appendLine(
            strings.get("diag_report_checks", items.size, passCount, warnCount, failCount),
        )
        text.appendLine(strings.get("diag_report_no_secret"))
        text.appendLine()
        items.forEach { item ->
            text.appendLine("[${strings.get("diag_sev_${item.severity.key}")}] ${item.title}")
            text.appendLine("  ${item.summary}")
            item.advice?.takeIf { it.isNotBlank() }?.let {
                text.appendLine("  ${strings.get("diag_report_advice", it)}")
            }
            item.details.forEach { text.appendLine("  · $it") }
            text.appendLine()
        }
        return text.toString().trimEnd() + "\n"
    }
}

private fun formatTime(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))

/** Replaces the secret in every free-text field. Defence in depth: checkers never print it. */
internal fun DiagItem.redacted(secret: String): DiagItem {
    if (secret.isBlank()) return this
    fun scrub(value: String): String = value.replace(secret, "***")
    return copy(
        title = scrub(title),
        summary = scrub(summary),
        advice = advice?.let(::scrub),
        details = details.map(::scrub),
    )
}
