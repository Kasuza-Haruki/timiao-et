package app.timiao.et.ui.util

import app.timiao.et.core.PeerInfo
import java.util.Locale
import kotlin.math.abs

fun formatRate(bytesPerSec: Long): String {
    val v = bytesPerSec.toDouble()
    return when {
        v < 1_000 -> "${bytesPerSec} B/s"
        v < 1_000_000 -> String.format(Locale.US, "%.1f KB/s", v / 1_000)
        v < 1_000_000_000 -> String.format(Locale.US, "%.1f MB/s", v / 1_000_000)
        else -> String.format(Locale.US, "%.2f GB/s", v / 1_000_000_000)
    }
}

fun formatBytes(bytes: Long): String {
    val v = abs(bytes).toDouble()
    val sign = if (bytes < 0) "-" else ""
    return when {
        v < 1_000 -> "${bytes} B"
        v < 1_000_000 -> String.format(Locale.US, "%s%.1f KB", sign, v / 1_000)
        v < 1_000_000_000 -> String.format(Locale.US, "%s%.1f MB", sign, v / 1_000_000)
        else -> String.format(Locale.US, "%s%.2f GB", sign, v / 1_000_000_000)
    }
}

fun formatUptime(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return "%d:%02d:%02d".format(h, m, s)
}

fun PeerInfo.isRelay(): Boolean = cost.startsWith("relay", ignoreCase = true)

fun PeerInfo.costLabel(): String = when {
    cost.equals("local", ignoreCase = true) -> "本机"
    isRelay() -> "中继 ${cost.substringAfter('(', "").substringBefore(')')}".trim().ifBlank { "中继" }
    else -> "直连"
}

fun PeerInfo.tunnelLabel(): String = tunnel?.uppercase(Locale.US) ?: "—"

fun natLabel(nat: String?): String = when (nat?.lowercase(Locale.US)) {
    "open" -> "开放"
    "cone" -> "锥形 NAT"
    "symmetric" -> "对称 NAT"
    null, "" -> "—"
    else -> nat ?: "—"
}

fun initialOf(name: String): String {
    val c = name.trim().firstOrNull() ?: '?'
    return c.uppercaseChar().toString()
}

private val AvatarPalette = listOf(
    0xFF7CC6A4, 0xFF8FB6FF, 0xFFC39BFF, 0xFFF0A96B, 0xFFE58FA8, 0xFF74B9C9,
)

fun avatarColorIndex(name: String): Int = (abs(name.hashCode()) % AvatarPalette.size)

fun parseLines(text: String): List<String> =
    text.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }

fun joinLines(items: List<String>): String = items.joinToString("\n")
