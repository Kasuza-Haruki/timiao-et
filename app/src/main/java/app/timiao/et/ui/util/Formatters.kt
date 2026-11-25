package app.timiao.et.ui.util

import app.timiao.et.core.PeerInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Binary units (KiB = 1024 B), same base as the official GUI. Rates use a 1 s poll window. */
fun formatRate(bytesPerSec: Long): String {
    val v = bytesPerSec.toDouble()
    return when {
        v < 1_024 -> "${bytesPerSec} B/s"
        v < 1_048_576 -> String.format(Locale.US, "%.1f KiB/s", v / 1_024)
        v < 1_073_741_824 -> String.format(Locale.US, "%.1f MiB/s", v / 1_048_576)
        else -> String.format(Locale.US, "%.2f GiB/s", v / 1_073_741_824)
    }
}

fun formatBytes(bytes: Long): String {
    val v = abs(bytes).toDouble()
    val sign = if (bytes < 0) "-" else ""
    return when {
        v < 1_024 -> "${bytes} B"
        v < 1_048_576 -> String.format(Locale.US, "%s%.1f KiB", sign, v / 1_024)
        v < 1_073_741_824 -> String.format(Locale.US, "%s%.1f MiB", sign, v / 1_048_576)
        else -> String.format(Locale.US, "%s%.2f GiB", sign, v / 1_073_741_824)
    }
}

fun formatUptime(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return "%d:%02d:%02d".format(h, m, s)
}

fun PeerInfo.isRelay(): Boolean = cost.startsWith("relay", ignoreCase = true)

/** Cost text uses the official tokens verbatim: `local`, `p2p`, `relay(N)`. */
fun PeerInfo.costLabel(): String = when {
    cost.equals("local", ignoreCase = true) -> "local"
    isRelay() -> "relay(${cost.substringAfter('(', "").substringBefore(')')})"
    else -> "p2p"
}

/** Raw tunnel type; the `6` suffix is added by the parser for IPv6 local addresses. */
fun PeerInfo.tunnelLabel(): String = tunnel?.takeIf { it.isNotBlank() } ?: "—"

fun PeerInfo.versionLabel(): String = version?.takeIf { it.isNotBlank() } ?: "unknown"

/**
 * One Chinese name for every NAT enum value. Input is the canonical English name emitted by
 * [app.timiao.et.core.StatusParser]; numeric strings are accepted as a fallback.
 */
fun natLabel(nat: String?): String = when (nat?.trim()?.lowercase(Locale.US)) {
    null, "" -> "—"
    "unknown", "0" -> "未知"
    "openinternet", "open", "1" -> "开放互联网"
    "nopat", "2" -> "无 PAT"
    "fullcone", "cone", "3" -> "全锥形"
    "restricted", "4" -> "受限锥形"
    "portrestricted", "5" -> "端口受限锥形"
    "symmetric", "6" -> "对称 NAT"
    "symudpfirewall", "7" -> "对称 UDP 防火墙"
    "symmetriceasyinc", "8" -> "对称 NAT · 易增"
    "symmetriceasydec", "9" -> "对称 NAT · 易减"
    else -> nat ?: "—"
}

/** Kernel event type -> Chinese label, following the official `cn.yaml` event names. */
fun eventTypeLabel(type: String): String = when (type) {
    "TunDeviceReady" -> "TUN 设备就绪"
    "TunDeviceError" -> "TUN 设备错误"
    "PeerAdded" -> "对端添加"
    "PeerRemoved" -> "对端移除"
    "PeerConnAdded" -> "对端连接添加"
    "PeerConnRemoved" -> "对端连接移除"
    "ListenerAdded" -> "监听器添加"
    "ListenerAddFailed" -> "监听器添加失败"
    "ListenerAcceptFailed" -> "监听器接受连接失败"
    "ConnectionAccepted" -> "连接已接受"
    "ConnectionError" -> "连接错误"
    "Connecting" -> "正在连接"
    "ConnectError" -> "连接错误"
    "VpnPortalStarted" -> "VPN 门户已启动"
    "VpnPortalClientConnected" -> "VPN 门户客户端已连接"
    "VpnPortalClientDisconnected" -> "VPN 门户客户端已断开"
    "DhcpIpv4Changed" -> "DHCP IPv4 地址更改"
    "DhcpIpv4Conflicted" -> "DHCP IPv4 地址冲突"
    "DhcpIpv6Changed" -> "DHCP IPv6 地址更改"
    "DhcpIpv6Conflicted" -> "DHCP IPv6 地址冲突"
    "PortForwardAdded" -> "端口转发添加"
    "ProxyCidrsUpdated" -> "子网代理 CIDR 更新"
    "UdpBroadcastRelayStartResult" -> "UDP 广播中继结果"
    else -> type
}

fun formatEventTime(timeMs: Long?): String {
    if (timeMs == null) return "—"
    val pattern = if (isToday(timeMs)) "HH:mm:ss" else "MM-dd HH:mm"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timeMs))
}

private fun isToday(timeMs: Long): Boolean {
    val now = System.currentTimeMillis()
    val oneDay = 24L * 60 * 60 * 1000
    return timeMs in (now - oneDay)..(now + 60_000L)
}

/** Stable list identity: peer id first, then address, then hostname. */
fun PeerInfo.stableKey(): String =
    peerId?.let { "id:$it" } ?: "${hostname}|${ipv4.orEmpty()}"

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
