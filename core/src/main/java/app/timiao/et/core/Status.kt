package app.timiao.et.core

public enum class LinkState { Idle, Connecting, Connected, Error }

/** One live connection (tunnel) to a peer. */
public data class PeerConn(
    val connId: String? = null,
    /** Raw tunnel type (`udp`, `tcp`, `wg`, ...). IPv6 local addresses are marked with a `6` suffix. */
    val tunnel: String? = null,
    val localAddr: String? = null,
    val remoteAddr: String? = null,
    val latencyMs: Double? = null,
    val lossRate: Double? = null,
    val txBytes: Long = 0L,
    val rxBytes: Long = 0L,
    /** Official GUI prefers this connection for latency and loss. */
    val isDefault: Boolean = false,
)

public data class PeerInfo(
    val hostname: String,
    val ipv4: String?,
    /** `p2p`, `relay(N)` or `local`. Same rule as the official GUI. */
    val cost: String,
    val tunnel: String?,
    val latencyMs: Double?,
    val lossRate: Double?,
    val natType: String?,
    val txBytes: Long,
    val rxBytes: Long,
    val version: String?,
    val ipv6: String? = null,
    val peerId: String? = null,
    /** Route `next_hop_peer_id`; used by the topology view to place relays after their parent. */
    val nextHopPeerId: String? = null,
    val tcpNatType: String? = null,
    val isPublicServer: Boolean = false,
    val avoidRelayData: Boolean = false,
    val conns: List<PeerConn> = emptyList(),
)

/** Local node information shown on the node-info screen. */
public data class NodeInfo(
    val peerId: String? = null,
    val hostname: String? = null,
    val version: String? = null,
    val virtualIpv4: String? = null,
    val virtualIpv6: String? = null,
    val localIpv4s: List<String> = emptyList(),
    val localIpv6s: List<String> = emptyList(),
    val publicIpv4: String? = null,
    val publicIpv6: String? = null,
    val listeners: List<String> = emptyList(),
    val udpNatType: String? = null,
    val tcpNatType: String? = null,
    val stunMinPort: Int? = null,
    val stunMaxPort: Int? = null,
)

public enum class NetworkEventKind { Connect, Error, Other }

/** One entry from the kernel event journal (`events` in `collectNetworkInfos`). */
public data class NetworkEvent(
    /** Epoch milliseconds; null if the payload had no parseable time. */
    val timeMs: Long? = null,
    val type: String = "Unknown",
    /** Human-readable payload, already formatted (peer ids, URLs, address changes). */
    val detail: String = "",
    val kind: NetworkEventKind = NetworkEventKind.Other,
    /** Original JSON line, kept for clipboard export. */
    val raw: String = "",
)

public data class NetworkStatus(
    val state: LinkState,
    val myIpv4: String?,
    val peers: List<PeerInfo>,
    val txBps: Long,
    val rxBps: Long,
    val uptimeSec: Long,
    val error: String?,
    val node: NodeInfo? = null,
    val events: List<NetworkEvent> = emptyList(),
)
