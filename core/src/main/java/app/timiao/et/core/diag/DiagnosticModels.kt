package app.timiao.et.core.diag

import app.timiao.et.core.NetworkConfig
import app.timiao.et.core.NetworkStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Result of one diagnostic check. */
public enum class DiagSeverity(val label: String) {
    Pass("通过"),
    Warn("警告"),
    Fail("失败");

    public companion object {
        public fun worst(values: Iterable<DiagSeverity>): DiagSeverity = when {
            values.any { it == Fail } -> Fail
            values.any { it == Warn } -> Warn
            else -> Pass
        }
    }
}

/** One check: short conclusion plus concrete advice. */
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

    public val headline: String
        get() = when (overall) {
            DiagSeverity.Pass -> "检查通过，没有发现问题"
            DiagSeverity.Warn -> "可以使用，但有几项值得注意"
            DiagSeverity.Fail -> "发现问题，请按建议逐项处理"
        }

    /** Plain-text summary suitable for the clipboard. Never contains the network secret. */
    public fun plainText(): String {
        val text = StringBuilder()
        text.appendLine("缇喵 · 网络诊断")
        text.appendLine("结论：$headline")
        text.appendLine("时间：${formatTime(generatedAtEpochMs)}")
        text.appendLine("检查：${items.size} 项 · 通过 $passCount · 警告 $warnCount · 失败 $failCount")
        text.appendLine("本报告不包含网络密钥。")
        text.appendLine()
        items.forEach { item ->
            text.appendLine("[${item.severity.label}] ${item.title}")
            text.appendLine("  ${item.summary}")
            item.advice?.takeIf { it.isNotBlank() }?.let { text.appendLine("  建议：$it") }
            item.details.forEach { text.appendLine("  · $it") }
            text.appendLine()
        }
        return text.toString().trimEnd() + "\n"
    }
}

private fun formatTime(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(epochMs))

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
