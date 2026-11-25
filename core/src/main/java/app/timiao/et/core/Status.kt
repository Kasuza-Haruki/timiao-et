package app.timiao.et.core

public enum class LinkState { Idle, Connecting, Connected, Error }

public data class PeerInfo(
    val hostname: String,
    val ipv4: String?,
    /** `p2p`, `relay(N)` or `local`. */
    val cost: String,
    val tunnel: String?,
    val latencyMs: Double?,
    val lossRate: Double?,
    val natType: String?,
    val txBytes: Long,
    val rxBytes: Long,
    val version: String?,
)

public data class NetworkStatus(
    val state: LinkState,
    val myIpv4: String?,
    val peers: List<PeerInfo>,
    val txBps: Long,
    val rxBps: Long,
    val uptimeSec: Long,
    val error: String?,
)
