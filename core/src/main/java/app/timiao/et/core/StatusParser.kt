package app.timiao.et.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * Parses `EasyTierJNI.collectNetworkInfos` output into [NetworkStatus].
 *
 * The payload is pbjson (proto JSON names): uint64 fields are strings and enum
 * fields are names. Missing scalar fields are the proto defaults.
 *
 * Display rules mirror the official GUI (`statusDisplay.ts`):
 * - latency: default connection first, then the smallest connection, then `path_latency`;
 * - loss: the first connection in default-first order;
 * - tunnel: `tunnel_type` plus `6` when the local address is IPv6;
 * - cost: `local` / `p2p` / `relay(N)`.
 */
internal object StatusParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Rate smoothing factor. The poll window is one second. */
    private const val RATE_ALPHA = 0.4

    /** A freshly started instance stays in "connecting" until this many seconds have passed. */
    private const val STARTUP_GRACE_SEC = 8L

    private val errorEvents = setOf(
        "TunDeviceError",
        "ListenerAddFailed",
        "ListenerAcceptFailed",
        "ConnectionError",
        "ConnectError",
        "DhcpIpv4Conflicted",
        "DhcpIpv6Conflicted",
    )

    private val connectEvents = setOf(
        "TunDeviceReady",
        "PeerAdded",
        "PeerRemoved",
        "PeerConnAdded",
        "PeerConnRemoved",
        "ListenerAdded",
        "ConnectionAccepted",
        "Connecting",
        "VpnPortalStarted",
        "VpnPortalClientConnected",
        "VpnPortalClientDisconnected",
        "DhcpIpv4Changed",
        "DhcpIpv6Changed",
        "PortForwardAdded",
        "ProxyCidrsUpdated",
    )

    /** Real payload is `{"map": {"<instance>": {...}}}`. Older shapes without the wrapper still work. */
    private fun entryOf(text: String, instanceName: String): JsonObject? {
        val top = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val root = (top["map"] as? JsonObject) ?: top
        return (root[instanceName] ?: root.values.firstOrNull()) as? JsonObject
    }

    /**
     * Parses one instance entry.
     *
     * Connection state rules:
     * - not running -> [LinkState.Error];
     * - system default network unavailable -> reconnecting
     *   ([LinkState.Connecting] + [NetworkStatus.reconnecting]), even if the kernel still
     *   lists stale peers;
     * - at least one peer -> [LinkState.Connected];
     * - no peers, under [STARTUP_GRACE_SEC] -> [LinkState.Connecting];
     * - no peers, no server configured (isolated network) -> [LinkState.Connected],
     *   because there is nothing to reach;
     * - no peers, a peer was seen recently ([peerSeenRecently]) -> [LinkState.Connected],
     *   which keeps a short tunnel blip from flipping the UI;
     * - otherwise (server configured but no peer) -> reconnecting.
     *
     * Tradeoff: the kernel does not report a server "heartbeat" that outlives the route
     * table, so when the state is ambiguous we trust the system default network. With a
     * physical network up but a firewall dropping the server, the UI can stay "connected"
     * until the peer grace window ends. The e2e case (mobile data and Wi-Fi off) is always
     * detected because the default network disappears.
     */
    fun parse(
        text: String,
        instanceName: String,
        uptimeSec: Long,
        networkAvailable: Boolean = true,
        hasConfiguredPeers: Boolean = true,
        peerSeenRecently: Boolean = false,
    ): NetworkStatus? {
        val entry = entryOf(text, instanceName) ?: return null

        val running = entry.bool("running") ?: false
        val node = (entry["my_node_info"] as? JsonObject)?.toNodeInfo()
        val myIpv4 = (entry["my_node_info"] as? JsonObject)
            ?.get("virtual_ipv4").inetToString()
            ?: node?.virtualIpv4
        val error = entry.str("error_msg")
        val events = parseEvents(entry)

        if (!running) {
            return NetworkStatus(
                state = LinkState.Error,
                myIpv4 = myIpv4,
                peers = emptyList(),
                txBps = 0,
                rxBps = 0,
                uptimeSec = uptimeSec,
                error = error,
                node = node,
                events = events,
            )
        }

        val routes = entry.objects("routes").associateBy { it.long("peer_id") ?: -1L }
        val pairs = entry.objects("peer_route_pairs")
        val peers = if (pairs.isNotEmpty()) {
            pairs.mapNotNull { pair ->
                val route = pair["route"] as? JsonObject ?: return@mapNotNull null
                val peer = pair["peer"] as? JsonObject
                toPeer(route, peer)
            }
        } else {
            routes.values.mapNotNull { route -> toPeer(route, null) }
        }

        val hasPeers = peers.isNotEmpty()
        val reconnecting = !networkAvailable || (!hasPeers && when {
            uptimeSec < STARTUP_GRACE_SEC -> false
            peerSeenRecently -> false
            !hasConfiguredPeers -> false
            else -> true
        })
        val state = when {
            !networkAvailable -> LinkState.Connecting
            hasPeers -> LinkState.Connected
            reconnecting -> LinkState.Connecting
            uptimeSec < STARTUP_GRACE_SEC -> LinkState.Connecting
            else -> LinkState.Connected
        }

        return NetworkStatus(
            state = state,
            myIpv4 = myIpv4,
            peers = peers,
            txBps = 0,
            rxBps = 0,
            uptimeSec = uptimeSec,
            error = null,
            node = node,
            events = events,
            reconnecting = reconnecting,
        )
    }

    fun primaryIpv4(text: String, instanceName: String): String? {
        val entry = entryOf(text, instanceName) ?: return null
        return (entry["my_node_info"] as? JsonObject)?.get("virtual_ipv4").inetToString()
    }

    /**
     * Computes bytes/s from peer byte counters over the poll window and applies exponential
     * smoothing (alpha = 0.4). One-second updates stay readable instead of jumping on every poll.
     */
    fun applyRates(status: NetworkStatus, previous: NetworkStatus?, elapsedMs: Long): NetworkStatus {
        if (previous == null || elapsedMs <= 0) {
            return status.copy(txBps = 0, rxBps = 0)
        }
        val currentTx = status.peers.sumOf { it.txBytes }
        val currentRx = status.peers.sumOf { it.rxBytes }
        val previousTx = previous.peers.sumOf { it.txBytes }
        val previousRx = previous.peers.sumOf { it.rxBytes }
        val rawTx = (((currentTx - previousTx).coerceAtLeast(0) * 1000.0) / elapsedMs)
        val rawRx = (((currentRx - previousRx).coerceAtLeast(0) * 1000.0) / elapsedMs)
        val tx = (RATE_ALPHA * rawTx + (1 - RATE_ALPHA) * previous.txBps).roundToLong()
        val rx = (RATE_ALPHA * rawRx + (1 - RATE_ALPHA) * previous.rxBps).roundToLong()
        return status.copy(txBps = tx, rxBps = rx)
    }

    private fun toPeer(route: JsonObject, peer: JsonObject?): PeerInfo {
        val cost = route.long("cost") ?: 0L
        val defaultConnId = peer?.let { defaultConnId(it) }
        val conns = peer?.objects("conns").orEmpty().map { parseConn(it, defaultConnId) }
        // Official GUI: default connection first, then the rest in kernel order.
        val orderedConns = if (defaultConnId == null) conns else conns.sortedByDescending { it.isDefault }

        val latency = orderedConns.firstOrNull { it.isDefault }?.latencyMs
            ?: conns.mapNotNull { it.latencyMs }.minOrNull()
            ?: route.long("path_latency")?.takeIf { it >= 0 }?.toDouble()
        val lossRate = orderedConns.firstOrNull()?.let { it.lossRate ?: 0.0 }
        val tunnels = conns.mapNotNull { it.tunnel }.distinct()

        val stun = route["stun_info"] as? JsonObject
        val feature = route["feature_flag"] as? JsonObject

        return PeerInfo(
            hostname = route.str("hostname").orEmpty(),
            ipv4 = route["ipv4_addr"].inetToString(),
            cost = when {
                cost <= 0L -> "local"
                cost == 1L -> "p2p"
                else -> "relay($cost)"
            },
            tunnel = tunnels.takeIf { it.isNotEmpty() }?.joinToString(","),
            latencyMs = latency,
            lossRate = lossRate,
            natType = stun?.natType("udp_nat_type"),
            txBytes = conns.sumOf { it.txBytes },
            rxBytes = conns.sumOf { it.rxBytes },
            version = route.str("version"),
            ipv6 = route["ipv6_addr"].inet6ToString(),
            peerId = route.long("peer_id")?.toString(),
            nextHopPeerId = route.long("next_hop_peer_id")?.toString(),
            tcpNatType = stun?.natType("tcp_nat_type"),
            isPublicServer = feature?.bool("is_public_server") ?: false,
            avoidRelayData = feature?.bool("avoid_relay_data") ?: false,
            conns = orderedConns,
        )
    }

    private fun parseConn(obj: JsonObject, defaultConnId: String?): PeerConn {
        val tunnel = obj["tunnel"] as? JsonObject
        val stats = obj["stats"] as? JsonObject
        val connId = obj.str("conn_id")
        return PeerConn(
            connId = connId,
            tunnel = oneTunnelProto(tunnel),
            localAddr = ((tunnel?.get("local_addr") as? JsonObject)?.str("url")),
            remoteAddr = ((tunnel?.get("remote_addr") as? JsonObject)?.str("url")),
            latencyMs = stats?.long("latency_us")?.let { ceil(it / 1000.0) },
            lossRate = (obj["loss_rate"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull(),
            txBytes = stats?.long("tx_bytes") ?: 0L,
            rxBytes = stats?.long("rx_bytes") ?: 0L,
            isDefault = defaultConnId != null && connId == defaultConnId,
        )
    }

    /** Builds the kernel UUID string from the four uint32 parts, same as the official GUI. */
    private fun defaultConnId(peer: JsonObject): String? {
        val uuid = peer["default_conn_id"] as? JsonObject ?: return null
        val part1 = uuid.long("part1") ?: 0L
        val part2 = uuid.long("part2") ?: 0L
        val part3 = uuid.long("part3") ?: 0L
        val part4 = uuid.long("part4") ?: 0L
        if (part1 == 0L && part2 == 0L && part3 == 0L && part4 == 0L) return null
        fun hex(value: Long): String = java.lang.Long.toString(value, 16).padStart(8, '0')
        val p2 = hex(part2)
        val p3 = hex(part3)
        return "${hex(part1)}-${p2.take(4)}-${p2.substring(4)}-${p3.take(4)}-${p3.substring(4)}${hex(part4)}"
    }

    private fun oneTunnelProto(tunnel: JsonObject?): String? {
        val type = tunnel?.str("tunnel_type")?.takeIf { it.isNotBlank() } ?: return null
        val localUrl = ((tunnel["local_addr"] as? JsonObject)?.str("url")).orEmpty()
        // IPv6 URLs put the host in brackets, for example udp://[::]:11010.
        val isIpv6 = IPv6_URL.containsMatchIn(localUrl)
        return if (isIpv6) "${type}6" else type
    }

    private val IPv6_URL = Regex("://\\[[^]]*:[^]]*]")

    private fun JsonObject.toNodeInfo(): NodeInfo? {
        val ips = this["ips"] as? JsonObject
        val stun = this["stun_info"] as? JsonObject
        val hasAny = listOf(
            this["peer_id"], this["hostname"], this["version"], this["virtual_ipv4"], this["virtual_ipv6"],
            this["ips"], this["listeners"], this["stun_info"],
        ).any { it != null }
        if (!hasAny) return null

        val publicFromStun = (stun?.get("public_ip") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.firstOrNull()

        return NodeInfo(
            peerId = this.long("peer_id")?.toString(),
            hostname = this.str("hostname"),
            version = this.str("version"),
            virtualIpv4 = this["virtual_ipv4"].inetToString(),
            virtualIpv6 = this["virtual_ipv6"].inet6ToString(),
            localIpv4s = ips?.ipv4List("interface_ipv4s").orEmpty(),
            localIpv6s = ips?.ipv6List("interface_ipv6s").orEmpty(),
            publicIpv4 = ips?.get("public_ipv4").ipv4ToString() ?: publicFromStun,
            publicIpv6 = ips?.get("public_ipv6").ipv6ToString(),
            listeners = listeners(),
            udpNatType = stun?.natType("udp_nat_type"),
            tcpNatType = stun?.natType("tcp_nat_type"),
            stunMinPort = stun?.int("min_port"),
            stunMaxPort = stun?.int("max_port"),
        )
    }

    /** `listeners` is a list of `{"url": ...}` objects in current payloads, strings in older ones. */
    private fun JsonObject.listeners(): List<String> {
        val raw = this["listeners"] as? JsonArray ?: return emptyList()
        return raw.mapNotNull { element ->
            when (element) {
                is JsonObject -> element.str("url")
                is JsonPrimitive -> element.contentOrNull
                else -> null
            }?.takeIf { it.isNotBlank() }
        }
    }

    private fun JsonObject.ipv4List(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { it.ipv4ToString() }

    private fun JsonObject.ipv6List(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { it.ipv6ToString() }

    private fun JsonObject.natType(key: String): String? {
        val element = this[key] as? JsonPrimitive ?: return null
        val raw = element.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val number = raw.toIntOrNull()
        return if (number != null) natTypeName(number) else canonicalNatName(raw)
    }

    private fun canonicalNatName(value: String): String {
        val key = value.lowercase().replace(" ", "").replace("_", "").replace("-", "")
        return when (key) {
            "unknown" -> "Unknown"
            "openinternet" -> "OpenInternet"
            "nopat" -> "NoPAT"
            "fullcone" -> "FullCone"
            "restricted" -> "Restricted"
            "portrestricted" -> "PortRestricted"
            "symmetric" -> "Symmetric"
            "symudpfirewall" -> "SymUdpFirewall"
            "symmetriceasyinc" -> "SymmetricEasyInc"
            "symmetriceasydec" -> "SymmetricEasyDec"
            else -> "Unknown"
        }
    }

    private fun natTypeName(value: Int): String = when (value) {
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

    private fun parseEvents(entry: JsonObject): List<NetworkEvent> {
        val raw = entry["events"] as? JsonArray ?: return emptyList()
        return raw.mapNotNull { element ->
            val text = (element as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            parseEvent(text)
        }.sortedWith(compareByDescending { it.timeMs ?: Long.MIN_VALUE })
    }

    private fun parseEvent(text: String): NetworkEvent? {
        val obj = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val time = obj.str("time")
        val event = obj["event"] as? JsonObject ?: return null
        val (type, value) = event.entries.firstOrNull() ?: return null
        val isRelayFailure = type == "UdpBroadcastRelayStartResult" &&
            (value as? JsonObject)?.str("error") != null
        val kind = when {
            type in errorEvents || isRelayFailure -> NetworkEventKind.Error
            type in connectEvents -> NetworkEventKind.Connect
            else -> NetworkEventKind.Other
        }
        return NetworkEvent(
            timeMs = parseTime(time),
            type = type,
            detail = eventDetail(type, value),
            kind = kind,
            raw = text,
        )
    }

    private fun eventDetail(type: String, value: JsonElement): String = when (type) {
        "PeerAdded", "PeerRemoved" -> "对端 ${value.text()}"
        "PeerConnAdded", "PeerConnRemoved" -> {
            val obj = value as? JsonObject
            val tunnel = obj?.get("tunnel") as? JsonObject
            val remote = ((tunnel?.get("remote_addr") as? JsonObject)?.str("url")).orEmpty()
            val proto = tunnel?.str("tunnel_type").orEmpty()
            listOfNotNull(
                obj?.long("peer_id")?.let { "对端 $it" },
                proto.ifBlank { null },
                remote.ifBlank { null },
            ).joinToString(" · ")
        }
        "DhcpIpv4Changed", "DhcpIpv6Changed" -> {
            val arr = value as? JsonArray
            "${arr?.getOrNull(0).text()} → ${arr?.getOrNull(1).text()}"
        }
        "DhcpIpv4Conflicted", "DhcpIpv6Conflicted" -> "冲突地址 ${value.text()}"
        "ProxyCidrsUpdated" -> {
            val arr = value as? JsonArray
            "新增 ${arr?.getOrNull(0).text()} · 移除 ${arr?.getOrNull(1).text()}"
        }
        "UdpBroadcastRelayStartResult" -> {
            val obj = value as? JsonObject
            val backend = obj?.str("capture_backend")
            val error = obj?.str("error")
            when {
                error != null -> "失败：$error"
                backend != null -> "后端：$backend"
                else -> value.text()
            }
        }
        "PortForwardAdded" -> {
            val obj = value as? JsonObject
            val bind = obj?.str("bind_addr")
            val dst = obj?.str("dst_addr")
            val proto = obj?.str("proto")
            if (bind != null && dst != null) {
                "${proto.orEmpty()} $bind → $dst".trim()
            } else {
                value.text()
            }
        }
        else -> value.text()
    }

    private fun JsonElement?.text(): String = when (val element = this) {
        null, is JsonNull -> "无"
        is JsonPrimitive -> element.contentOrNull ?: element.content
        is JsonArray -> element.joinToString(" · ") { it.text() }
        is JsonObject -> element.toString()
    }

    private fun parseTime(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching { java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli() }
            .recoverCatching { java.time.Instant.parse(value).toEpochMilli() }
            .getOrNull()
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

    private fun JsonObject.objects(key: String): List<JsonObject> =
        (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

    /** `Ipv4Inet` -> `a.b.c.d/prefix`. Strings pass through. */
    private fun JsonElement?.inetToString(): String? {
        val primitive = this as? JsonPrimitive
        if (primitive != null && primitive.isString) return primitive.contentOrNull
        val table = this as? JsonObject ?: return null
        val address = table["address"] as? JsonObject ?: return null
        val raw = (address["addr"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: return null
        val prefix = table.long("network_length") ?: 0L
        return "${ipv4String(raw)}/$prefix"
    }

    /** `Ipv4Addr` -> `a.b.c.d`. Strings pass through. */
    private fun JsonElement?.ipv4ToString(): String? {
        val primitive = this as? JsonPrimitive
        if (primitive != null && primitive.isString) return primitive.contentOrNull
        val obj = this as? JsonObject ?: return null
        val raw = (obj["addr"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: return null
        return ipv4String(raw)
    }

    private fun ipv4String(raw: Long): String = listOf(
        (raw shr 24) and 0xFF,
        (raw shr 16) and 0xFF,
        (raw shr 8) and 0xFF,
        raw and 0xFF,
    ).joinToString(".")

    /** `Ipv6Inet` -> RFC 5952 compressed address with prefix. Strings pass through. */
    private fun JsonElement?.inet6ToString(): String? {
        val primitive = this as? JsonPrimitive
        if (primitive != null && primitive.isString) return primitive.contentOrNull
        val table = this as? JsonObject ?: return null
        val address = table["address"] as? JsonObject ?: return null
        val ip = ipv6String(address) ?: return null
        val prefix = table.long("network_length") ?: 0L
        return "$ip/$prefix"
    }

    /** `Ipv6Addr` -> compressed address. Strings pass through. */
    private fun JsonElement?.ipv6ToString(): String? {
        val primitive = this as? JsonPrimitive
        if (primitive != null && primitive.isString) return primitive.contentOrNull
        val obj = this as? JsonObject ?: return null
        return ipv6String(obj)
    }

    private fun ipv6String(obj: JsonObject): String? {
        val parts = (1..4).map { obj.long("part$it") ?: 0L }
        if (parts.all { it == 0L } && obj["part1"] == null) return null
        val groups = IntArray(8)
        parts.forEachIndexed { index, part ->
            groups[index * 2] = ((part ushr 16) and 0xFFFF).toInt()
            groups[index * 2 + 1] = (part and 0xFFFF).toInt()
        }
        // RFC 5952: compress the longest run of two or more zero groups, leftmost on ties.
        var bestStart = -1
        var bestLength = 0
        var start = -1
        var length = 0
        for (i in groups.indices) {
            if (groups[i] == 0) {
                if (start < 0) start = i
                length++
                if (length > bestLength) {
                    bestStart = start
                    bestLength = length
                }
            } else {
                start = -1
                length = 0
            }
        }
        val useCompression = bestLength >= 2
        val result = StringBuilder()
        var i = 0
        while (i < groups.size) {
            if (useCompression && i == bestStart) {
                result.append("::")
                i += bestLength
                continue
            }
            if (result.isNotEmpty() && !result.endsWith(":")) result.append(':')
            result.append(Integer.toHexString(groups[i]))
            i++
        }
        return result.toString().ifEmpty { "::" }
    }
}
