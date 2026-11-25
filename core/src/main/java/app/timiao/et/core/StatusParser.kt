package app.timiao.et.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Parses `EasyTierJNI.collectNetworkInfos` output into [NetworkStatus].
 *
 * The payload is pbjson (proto JSON names): uint64 fields are strings and enum
 * fields are names. Missing scalar fields are the proto defaults.
 */
internal object StatusParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String, instanceName: String, uptimeSec: Long): NetworkStatus? {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val entry = (root[instanceName] ?: root.values.firstOrNull()) as? JsonObject ?: return null

        val running = entry.bool("running") ?: false
        val myIpv4 = (entry["my_node_info"] as? JsonObject)?.get("virtual_ipv4").inetToString()
        val error = entry.str("error_msg")

        if (!running) {
            return NetworkStatus(LinkState.Error, myIpv4, emptyList(), 0, 0, uptimeSec, error)
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

        return NetworkStatus(LinkState.Connected, myIpv4, peers, 0, 0, uptimeSec, null)
    }

    fun primaryIpv4(text: String, instanceName: String): String? {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val entry = (root[instanceName] ?: root.values.firstOrNull()) as? JsonObject ?: return null
        return (entry["my_node_info"] as? JsonObject)?.get("virtual_ipv4").inetToString()
    }

    fun applyRates(status: NetworkStatus, previous: NetworkStatus?, elapsedMs: Long): NetworkStatus {
        val currentTx = status.peers.sumOf { it.txBytes }
        val currentRx = status.peers.sumOf { it.rxBytes }
        if (previous == null || elapsedMs <= 0) {
            return status.copy(txBps = 0, rxBps = 0)
        }
        val previousTx = previous.peers.sumOf { it.txBytes }
        val previousRx = previous.peers.sumOf { it.rxBytes }
        val tx = ((currentTx - previousTx).coerceAtLeast(0) * 1000) / elapsedMs
        val rx = ((currentRx - previousRx).coerceAtLeast(0) * 1000) / elapsedMs
        return status.copy(txBps = tx, rxBps = rx)
    }

    private fun toPeer(route: JsonObject, peer: JsonObject?): PeerInfo {
        val cost = route.long("cost") ?: 0L
        val conns = peer?.objects("conns") ?: emptyList()
        val txBytes = conns.sumOf { conn -> (conn["stats"] as? JsonObject)?.long("tx_bytes") ?: 0L }
        val rxBytes = conns.sumOf { conn -> (conn["stats"] as? JsonObject)?.long("rx_bytes") ?: 0L }
        val latencies = conns.mapNotNull { conn ->
            (conn["stats"] as? JsonObject)?.long("latency_us")
        }
        val tunnels = conns.mapNotNull { conn ->
            (conn["tunnel"] as? JsonObject)?.str("tunnel_type")?.takeIf { it.isNotBlank() }
        }.distinct()
        val lossRate = conns.firstNotNullOfOrNull { conn ->
            (conn["loss_rate"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
        }
        val natType = (route["stun_info"] as? JsonObject)?.get("udp_nat_type")?.let { element ->
            val primitive = element as? JsonPrimitive
            primitive?.contentOrNull?.let { value ->
                primitive.intOrNullName()?.let { natTypeName(it) } ?: value
            }
        }

        return PeerInfo(
            hostname = route.str("hostname").orEmpty(),
            ipv4 = route["ipv4_addr"].inetToString(),
            cost = when {
                cost <= 0L -> "local"
                cost == 1L -> "p2p"
                else -> "relay($cost)"
            },
            tunnel = tunnels.takeIf { it.isNotEmpty() }?.joinToString(","),
            latencyMs = latencies.minOrNull()?.let { Math.ceil(it / 1000.0) },
            lossRate = lossRate,
            natType = natType,
            txBytes = txBytes,
            rxBytes = rxBytes,
            version = route.str("version"),
        )
    }

    private fun JsonPrimitive.intOrNullName(): Int? =
        if (isString) contentOrNull?.toIntOrNull() else contentOrNull?.toIntOrNull()

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

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    private fun JsonObject.objects(key: String): List<JsonObject> =
        (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

    private fun JsonElement?.inetToString(): String? {
        val table = this as? JsonObject ?: return null
        val address = table["address"] as? JsonObject ?: return null
        val raw = (address["addr"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: return null
        val prefix = table.long("network_length") ?: 0L
        val ip = listOf(
            (raw shr 24) and 0xFF,
            (raw shr 16) and 0xFF,
            (raw shr 8) and 0xFF,
            raw and 0xFF,
        ).joinToString(".")
        return "$ip/$prefix"
    }
}
