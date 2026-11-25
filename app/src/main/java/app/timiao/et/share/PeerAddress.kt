package app.timiao.et.share

import app.timiao.et.core.PeerInfo

/** Address without the CIDR suffix, e.g. `10.144.144.2/24` -> `10.144.144.2`. */
fun PeerInfo.hostAddress(): String? =
    ipv4?.substringBefore('/')?.trim()?.takeIf { it.isNotEmpty() }

/** Hosts passed to ping / shell-adjacent APIs must not look like options. */
fun isSafeHost(value: String): Boolean =
    value.isNotBlank() &&
        value.length <= 253 &&
        !value.startsWith("-") &&
        value.all { it.isLetterOrDigit() || it in "._:-" }

data class PingResult(
    val ok: Boolean,
    val results: List<String>,
    val error: String? = null,
)

enum class LaunchResult { Opened, NoHandler }
