package app.timiao.et.ui.util

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.timiao.et.R
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

/** Resource for one NAT enum value. Unknown values fall back to the raw text. */
@StringRes
fun natLabelRes(nat: String?): Int? = when (nat?.trim()?.lowercase(Locale.US)) {
    null, "" -> null
    "unknown", "0" -> R.string.nat_unknown
    "openinternet", "open", "1" -> R.string.nat_open
    "nopat", "2" -> R.string.nat_nopat
    "fullcone", "cone", "3" -> R.string.nat_fullcone
    "restricted", "4" -> R.string.nat_restricted
    "portrestricted", "5" -> R.string.nat_portrestricted
    "symmetric", "6" -> R.string.nat_symmetric
    "symudpfirewall", "7" -> R.string.nat_symudpfirewall
    "symmetriceasyinc", "8" -> R.string.nat_syminc
    "symmetriceasydec", "9" -> R.string.nat_symdec
    else -> null
}

@Composable
fun natLabel(nat: String?): String =
    natLabelRes(nat)?.let { stringResource(it) } ?: nat ?: "—"

/** Resource for a kernel event type; null means show the raw type. */
@StringRes
fun eventTypeLabelRes(type: String): Int? = when (type) {
    "TunDeviceReady" -> R.string.event_tun_ready
    "TunDeviceError" -> R.string.event_tun_error
    "PeerAdded" -> R.string.event_peer_added
    "PeerRemoved" -> R.string.event_peer_removed
    "PeerConnAdded" -> R.string.event_peer_conn_added
    "PeerConnRemoved" -> R.string.event_peer_conn_removed
    "ListenerAdded" -> R.string.event_listener_added
    "ListenerAddFailed" -> R.string.event_listener_add_failed
    "ListenerAcceptFailed" -> R.string.event_listener_accept_failed
    "ConnectionAccepted" -> R.string.event_connection_accepted
    "ConnectionError" -> R.string.event_connection_error
    "Connecting" -> R.string.event_connecting
    "ConnectError" -> R.string.event_connect_error
    "VpnPortalStarted" -> R.string.event_vpn_portal_started
    "VpnPortalClientConnected" -> R.string.event_vpn_portal_client_connected
    "VpnPortalClientDisconnected" -> R.string.event_vpn_portal_client_disconnected
    "DhcpIpv4Changed" -> R.string.event_dhcp_ipv4_changed
    "DhcpIpv4Conflicted" -> R.string.event_dhcp_ipv4_conflicted
    "DhcpIpv6Changed" -> R.string.event_dhcp_ipv6_changed
    "DhcpIpv6Conflicted" -> R.string.event_dhcp_ipv6_conflicted
    "PortForwardAdded" -> R.string.event_port_forward_added
    "ProxyCidrsUpdated" -> R.string.event_proxy_cidrs_updated
    "UdpBroadcastRelayStartResult" -> R.string.event_udp_relay_result
    else -> null
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
