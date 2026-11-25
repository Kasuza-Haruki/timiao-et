package app.timiao.et.core.diag

import java.net.URI

/**
 * Parsed `[[peer]]` URI. EasyTier accepts `tcp/udp/ws/wss/wg/quic/faketcp`
 * and the special `ring/txt/srv` forms; `ws/wss` are TCP based.
 */
internal sealed interface PeerUri {
    data class Endpoint(
        val scheme: String,
        val host: String?,
        val port: Int?,
        val tcp: Boolean,
        val noteKey: String? = null,
        val noteArgs: List<String> = emptyList(),
    ) : PeerUri

    data class Invalid(
        val reasonKey: String,
        val args: List<String> = emptyList(),
    ) : PeerUri
}

private val SupportedSchemes = setOf(
    "tcp", "udp", "wg", "quic", "ws", "wss", "http", "https", "faketcp",
    "ring", "txt", "srv",
)

internal fun parsePeerUri(raw: String): PeerUri {
    val text = raw.trim()
    if (text.isEmpty()) return PeerUri.Invalid("diag_probe_empty")
    if (!text.contains("://")) {
        return PeerUri.Invalid("diag_probe_no_scheme")
    }
    val uri = try {
        URI(text)
    } catch (_: Exception) {
        return PeerUri.Invalid("diag_probe_bad_url")
    }
    val scheme = uri.scheme?.lowercase() ?: return PeerUri.Invalid("diag_probe_no_protocol")
    if (scheme !in SupportedSchemes) {
        return PeerUri.Invalid("diag_probe_unsupported", listOf(scheme))
    }
    if (scheme == "ring") {
        return PeerUri.Endpoint(
            scheme, null, null, tcp = false, noteKey = "diag_probe_ring_note",
        )
    }
    if (scheme == "txt" || scheme == "srv") {
        val host = uri.host?.takeIf { it.isNotBlank() }
            ?: return PeerUri.Invalid("diag_probe_no_host")
        return PeerUri.Endpoint(
            scheme, host, null, tcp = false,
            noteKey = "diag_probe_txt_note", noteArgs = listOf(scheme),
        )
    }
    val host = uri.host?.takeIf { it.isNotBlank() }
        ?: return PeerUri.Invalid("diag_probe_no_host")
    val port = if (uri.port != -1) uri.port else defaultPortFor(scheme)
    if (port == null || port !in 1..65535) return PeerUri.Invalid("diag_probe_bad_port")

    val tcp = scheme == "tcp" || scheme == "ws" || scheme == "wss" ||
        scheme == "http" || scheme == "https"
    return PeerUri.Endpoint(
        scheme, host, port, tcp,
        noteKey = if (!tcp) "diag_probe_udp_note" else null,
    )
}

private fun defaultPortFor(scheme: String): Int? = when (scheme) {
    "tcp", "udp" -> 11010
    "ws", "http" -> 80
    "wss", "https" -> 443
    "wg" -> 11011
    "quic" -> 11012
    "faketcp" -> 11013
    else -> null
}

/** Short, stable text for a parsed URI in details. */
internal fun PeerUri.describe(): String = when (this) {
    is PeerUri.Endpoint -> "${scheme}://${host ?: "?"}${port?.let { ":$it" } ?: ""}"
    is PeerUri.Invalid -> reasonKey
}
