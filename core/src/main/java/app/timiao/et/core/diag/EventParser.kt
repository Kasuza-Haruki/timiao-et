package app.timiao.et.core.diag

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Locale

/** One translated kernel event. */
internal data class DiagEvent(
    val type: String,
    val time: String?,
    val text: String,
    val fatal: Boolean = false,
    /** Original error text, used to pick advice. Not shown verbatim. */
    val errorText: String? = null,
)

internal data class InfoSnapshot(
    val events: List<DiagEvent>,
    val myNatType: String?,
)

/**
 * Parses `collectNetworkInfos` pbjson. Event entries are serde JSON strings like
 * `{"time":"...","event":{"ConnectError":["tcp://a:11010","V4","Connection refused"]}}`.
 */
internal object EventParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val clockPattern = Regex("""\d{2}:\d{2}:\d{2}""")

    fun parse(
        raw: String?,
        instanceName: String?,
        strings: DiagStrings = ChineseDiagStrings,
    ): InfoSnapshot? {
        if (raw.isNullOrBlank()) return null
        val top = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null
        val root = (top["map"] as? JsonObject) ?: top
        val entry = (
            instanceName?.takeIf { it.isNotBlank() }?.let { root[it] }
                ?: root.values.firstOrNull()
            ) as? JsonObject ?: return null

        val events = (entry["events"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.mapNotNull { parseEvent(it, strings) }
            .orEmpty()
        val nat = ((entry["my_node_info"] as? JsonObject)?.get("stun_info") as? JsonObject)
            ?.get("udp_nat_type")
            ?.let { display(it) }
        return InfoSnapshot(events, normalizeNat(nat))
    }

    private fun parseEvent(text: String, strings: DiagStrings): DiagEvent? {
        val element = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return null
        var payload = element
        var time: String? = null
        if (element is JsonObject && element.containsKey("event")) {
            time = (element["time"] as? JsonPrimitive)?.contentOrNull
            payload = element["event"] ?: return null
        }

        val name: String
        val args: List<JsonElement>
        val argObject: JsonObject?
        when (payload) {
            is JsonPrimitive -> {
                name = payload.contentOrNull ?: return null
                args = emptyList()
                argObject = null
            }
            is JsonObject -> {
                val entry = payload.entries.firstOrNull() ?: return null
                name = entry.key
                when (val value = entry.value) {
                    is JsonArray -> {
                        args = value
                        argObject = null
                    }
                    is JsonObject -> {
                        args = listOf(value)
                        argObject = value
                    }
                    else -> {
                        args = listOf(value)
                        argObject = null
                    }
                }
            }
            else -> return null
        }

        val translated = translate(name, args, argObject, strings)
        return DiagEvent(
            type = name,
            time = time?.let { clockPattern.find(it)?.value ?: it },
            text = translated.first,
            fatal = translated.second,
            errorText = translated.third,
        )
    }

    /** (translated text, fatal, raw error text). */
    private fun translate(
        name: String,
        args: List<JsonElement>,
        obj: JsonObject?,
        strings: DiagStrings,
    ): Triple<String, Boolean, String?> {
        fun arg(index: Int): String = display(args.getOrNull(index))
        fun rawError(): String? = when (val element = args.lastOrNull()) {
            null, JsonNull -> null
            is JsonPrimitive -> element.contentOrNull
            else -> null
        }

        return when (name) {
            "Connecting" -> Triple(strings.get("ev_connecting", arg(0)), false, null)
            "ConnectError" -> {
                val dst = arg(0)
                val version = ipVersion(args.getOrNull(1))
                val error = rawError()
                val suffix = version?.let { strings.get("ev_connect_error_version", it) }.orEmpty()
                Triple(
                    strings.get("ev_connect_error", dst, suffix, translateError(error.orEmpty(), strings)),
                    false,
                    error,
                )
            }
            "ConnectionError" -> {
                val remote = arg(1)
                val error = rawError()
                Triple(
                    strings.get("ev_connection_error", remote, translateError(error.orEmpty(), strings)),
                    false,
                    error,
                )
            }
            "ConnectionAccepted" -> Triple(strings.get("ev_connection_accepted", arg(1)), false, null)
            "PeerConnAdded" -> Triple(strings.get("ev_peer_conn_added"), false, null)
            "PeerConnRemoved" -> Triple(strings.get("ev_peer_conn_removed"), false, null)
            "PeerAdded" -> Triple(strings.get("ev_peer_added", arg(0)), false, null)
            "PeerRemoved" -> Triple(strings.get("ev_peer_removed", arg(0)), false, null)
            "ListenerAdded" -> Triple(strings.get("ev_listener_added", arg(0)), false, null)
            "ListenerAddFailed" -> {
                val error = rawError()
                Triple(
                    strings.get("ev_listener_add_failed", arg(0), translateError(error.orEmpty(), strings)),
                    false,
                    error,
                )
            }
            "ListenerAcceptFailed" -> {
                val error = rawError()
                Triple(
                    strings.get("ev_listener_accept_failed", arg(0), translateError(error.orEmpty(), strings)),
                    false,
                    error,
                )
            }
            "ListenerPortMappingEstablished" -> {
                val local = obj?.get("local_listener")?.let(::display).orEmpty()
                val mapped = obj?.get("mapped_listener")?.let(::display).orEmpty()
                val backend = obj?.get("backend")?.let(::display).orEmpty()
                Triple(strings.get("ev_port_mapping", local, mapped, backend), false, null)
            }
            "TunDeviceReady" -> Triple(strings.get("ev_tun_ready", arg(0)), false, null)
            "TunDeviceError" -> Triple(strings.get("ev_tun_error", arg(0)), true, arg(0))
            "DhcpIpv4Changed" -> Triple(strings.get("ev_dhcp_ipv4_changed", arg(0), arg(1)), false, null)
            "DhcpIpv6Changed" -> Triple(strings.get("ev_dhcp_ipv6_changed", arg(0), arg(1)), false, null)
            "DhcpIpv4Conflicted" -> Triple(strings.get("ev_dhcp_ipv4_conflict", arg(0)), true, null)
            "DhcpIpv6Conflicted" -> Triple(strings.get("ev_dhcp_ipv6_conflict", arg(0)), true, null)
            "PublicIpv6Changed" -> Triple(strings.get("ev_public_ipv6_changed", arg(0), arg(1)), false, null)
            "PublicIpv6RoutesUpdated" -> Triple(strings.get("ev_public_ipv6_routes"), false, null)
            "VpnPortalStarted" -> Triple(strings.get("ev_vpn_portal_started", arg(0)), false, null)
            "VpnPortalClientConnected" -> Triple(strings.get("ev_vpn_portal_connected", arg(1)), false, null)
            "VpnPortalClientDisconnected" -> Triple(strings.get("ev_vpn_portal_disconnected", arg(1)), false, null)
            "PortForwardAdded" -> Triple(strings.get("ev_port_forward_added"), false, null)
            "ProxyCidrsUpdated" -> Triple(strings.get("ev_proxy_cidrs_updated"), false, null)
            "ConfigPatched" -> Triple(strings.get("ev_config_patched"), false, null)
            "CredentialChanged" -> Triple(strings.get("ev_credential_changed"), false, null)
            "UdpBroadcastRelayStartResult" -> {
                val error = obj?.get("error")?.let(::display)
                if (error.isNullOrBlank() || error == "—") {
                    Triple(strings.get("ev_udp_relay_started"), false, null)
                } else {
                    Triple(
                        strings.get("ev_udp_relay_failed", translateError(error, strings)),
                        false,
                        error,
                    )
                }
            }
            else -> Triple(name, false, null)
        }
    }

    private fun ipVersion(element: JsonElement?): String? {
        val raw = element?.let(::display)?.lowercase(Locale.US) ?: return null
        return when {
            raw.contains("4") -> "IPv4"
            raw.contains("6") -> "IPv6"
            raw == "both" -> "IPv4/IPv6"
            else -> null
        }
    }

    /** Common tunnel error strings to actionable Chinese. */
    internal fun translateError(raw: String, strings: DiagStrings = ChineseDiagStrings): String {
        val lower = raw.lowercase(Locale.US)
        return when {
            raw.isBlank() -> strings.get("ev_err_unknown")
            "refused" in lower -> strings.get("ev_err_refused")
            "timed out" in lower || "timeout" in lower -> strings.get("ev_err_timeout")
            "no route" in lower -> strings.get("ev_err_no_route")
            "unreachable" in lower || "network is down" in lower -> strings.get("ev_err_unreachable")
            "resolve" in lower || "dns" in lower || "lookup" in lower ||
                "name or service not known" in lower -> strings.get("ev_err_dns")
            "handshake" in lower || "decrypt" in lower || "auth" in lower ->
                strings.get("ev_err_handshake")
            "connection reset" in lower -> strings.get("ev_err_reset")
            "permission" in lower -> strings.get("ev_err_permission")
            else -> raw
        }
    }

    private fun display(element: JsonElement?): String = when (element) {
        null, JsonNull -> "—"
        is JsonPrimitive -> element.contentOrNull ?: "—"
        is JsonArray -> element.joinToString(", ") { display(it) }
        is JsonObject -> element.inetText()
            ?: element.entries.joinToString(", ") { (key, value) -> "$key=${display(value)}" }.take(160)
    }

    private fun JsonObject.inetText(): String? {
        val table = this["address"] as? JsonObject ?: return null
        val raw = (table["addr"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: return null
        val prefix = (this["network_length"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
        val ip = listOf(
            (raw shr 24) and 0xFF,
            (raw shr 16) and 0xFF,
            (raw shr 8) and 0xFF,
            raw and 0xFF,
        ).joinToString(".")
        return if (prefix != null) "$ip/$prefix" else ip
    }

    private fun normalizeNat(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() && it != "—" } ?: return null
        val numeric = value.toIntOrNull() ?: return value
        return when (numeric) {
            1 -> "OpenInternet"
            2 -> "NoPAT"
            3 -> "FullCone"
            4 -> "Restricted"
            5 -> "PortRestricted"
            6 -> "Symmetric"
            7 -> "SymUdpFirewall"
            8 -> "SymmetricEasyInc"
            9 -> "SymmetricEasyDec"
            else -> "Unknown"
        }
    }
}
