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
        val note: String? = null,
    ) : PeerUri

    data class Invalid(val reason: String) : PeerUri
}

private val SupportedSchemes = setOf(
    "tcp", "udp", "wg", "quic", "ws", "wss", "http", "https", "faketcp",
    "ring", "txt", "srv",
)

internal fun parsePeerUri(raw: String): PeerUri {
    val text = raw.trim()
    if (text.isEmpty()) return PeerUri.Invalid("地址为空")
    if (!text.contains("://")) {
        return PeerUri.Invalid("缺少协议前缀，例如 tcp://1.2.3.4:11010")
    }
    val uri = try {
        URI(text)
    } catch (_: Exception) {
        return PeerUri.Invalid("不是合法的 URL")
    }
    val scheme = uri.scheme?.lowercase() ?: return PeerUri.Invalid("缺少协议")
    if (scheme !in SupportedSchemes) {
        return PeerUri.Invalid("不支持的协议 $scheme")
    }
    if (scheme == "ring") {
        return PeerUri.Endpoint(scheme, null, null, tcp = false, note = "ring 地址由中继服务解析，无法直接探测")
    }
    if (scheme == "txt" || scheme == "srv") {
        val host = uri.host?.takeIf { it.isNotBlank() }
            ?: return PeerUri.Invalid("缺少主机名")
        return PeerUri.Endpoint(scheme, host, null, tcp = false, note = "$scheme 记录无法直接探测")
    }
    val host = uri.host?.takeIf { it.isNotBlank() } ?: return PeerUri.Invalid("缺少主机名")
    val port = if (uri.port != -1) uri.port else defaultPortFor(scheme)
    if (port == null || port !in 1..65535) return PeerUri.Invalid("缺少端口或端口无效")

    val tcp = scheme == "tcp" || scheme == "ws" || scheme == "wss" ||
        scheme == "http" || scheme == "https"
    val note = if (!tcp) "UDP 探测只能说明已发包，不能确认端口是否开放" else null
    return PeerUri.Endpoint(scheme, host, port, tcp, note)
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
    is PeerUri.Invalid -> reason
}
