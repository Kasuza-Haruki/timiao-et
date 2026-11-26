package app.timiao.et.debug

import app.timiao.et.core.Flags
import app.timiao.et.core.LinkState
import app.timiao.et.core.NetworkConfig
import app.timiao.et.core.NetworkEvent
import app.timiao.et.core.NetworkEventKind
import app.timiao.et.core.NetworkStatus
import app.timiao.et.core.NodeInfo
import app.timiao.et.core.PeerConfig
import app.timiao.et.core.PeerInfo
import app.timiao.et.core.ProxyNetwork

/**
 * Fictional data for README screenshots. Every address is from the documentation ranges
 * (203.0.113.0/24) or a made-up private network. No real node, host or key is present.
 */
object DemoData {
    const val ID = "demo-home-lab"
    const val NETWORK = "Home-Lab"
    const val SERVER = "tcp://public.example.net:11010"
    const val HOSTNAME = "demo-phone"
    const val MY_IPV4 = "10.144.144.2/24"
    const val PUBLIC_IPV4 = "203.0.113.7"
    const val PROXY_CIDR = "192.168.50.0/24"

    fun config(): NetworkConfig = NetworkConfig(
        instanceId = ID,
        instanceName = "home-lab",
        hostname = HOSTNAME,
        networkName = NETWORK,
        networkSecret = "",
        dhcp = true,
        listenerUrls = listOf(
            "tcp://0.0.0.0:11010",
            "udp://0.0.0.0:11010",
            "wg://0.0.0.0:11011",
        ),
        peers = listOf(PeerConfig(SERVER)),
        proxyNetworks = listOf(ProxyNetwork(PROXY_CIDR)),
        flags = Flags(latencyFirst = false),
    )

    fun peers(): List<PeerInfo> = listOf(
        PeerInfo(
            hostname = "nas-cat",
            ipv4 = "10.144.144.10/24",
            cost = "p2p",
            tunnel = "udp",
            latencyMs = 4.0,
            lossRate = 0.0,
            natType = "FullCone",
            txBytes = 1_284_000,
            rxBytes = 3_912_000,
            version = "2.7.1",
            peerId = "demo-peer-nas",
        ),
        PeerInfo(
            hostname = "office-pc",
            ipv4 = "10.144.144.20/24",
            cost = "p2p",
            tunnel = "udp",
            latencyMs = 18.0,
            lossRate = 0.0,
            natType = "PortRestricted",
            txBytes = 486_000,
            rxBytes = 724_000,
            version = "2.7.1",
            peerId = "demo-peer-office",
        ),
        PeerInfo(
            hostname = "travel-phone",
            ipv4 = "10.144.144.31/24",
            cost = "relay(1)",
            tunnel = "tcp",
            latencyMs = 62.0,
            lossRate = 0.004,
            natType = "Symmetric",
            txBytes = 96_000,
            rxBytes = 148_000,
            version = "2.7.1",
            peerId = "demo-peer-travel",
            nextHopPeerId = "demo-peer-office",
        ),
        PeerInfo(
            hostname = "pi-gateway",
            ipv4 = "10.144.144.40/24",
            cost = "p2p",
            tunnel = "udp",
            latencyMs = 9.0,
            lossRate = 0.0,
            natType = "FullCone",
            txBytes = 212_000,
            rxBytes = 390_000,
            version = "2.7.1",
            peerId = "demo-peer-pi",
        ),
    )

    fun status(now: Long = System.currentTimeMillis(), uptimeSec: Long = 12_340): NetworkStatus = NetworkStatus(
        state = LinkState.Connected,
        myIpv4 = MY_IPV4,
        peers = peers(),
        txBps = 18_400,
        rxBps = 42_100,
        uptimeSec = uptimeSec,
        error = null,
        node = NodeInfo(
            peerId = "demo-root",
            hostname = HOSTNAME,
            version = "2.7.1",
            virtualIpv4 = MY_IPV4,
            localIpv4s = listOf("192.168.1.20"),
            publicIpv4 = PUBLIC_IPV4,
            listeners = listOf("tcp://0.0.0.0:11010", "udp://0.0.0.0:11010"),
            udpNatType = "FullCone",
            tcpNatType = "PortRestricted",
            stunMinPort = 11010,
            stunMaxPort = 11020,
        ),
        events = events(now),
    )

    /** Newest first, matching the kernel journal order. */
    private fun events(now: Long): List<NetworkEvent> = listOf(
        event(now - 12_000, "DhcpIpv4Changed", "10.144.144.2/24", NetworkEventKind.Connect),
        event(now - 28_000, "ProxyCidrsUpdated", PROXY_CIDR, NetworkEventKind.Other),
        event(now - 45_000, "PeerConnAdded", "pi-gateway · udp · 10.144.144.40:11010", NetworkEventKind.Connect),
        event(now - 61_000, "PeerAdded", "travel-phone (10.144.144.31)", NetworkEventKind.Connect),
        event(now - 74_000, "PeerAdded", "office-pc (10.144.144.20)", NetworkEventKind.Connect),
        event(now - 88_000, "PeerAdded", "nas-cat (10.144.144.10)", NetworkEventKind.Connect),
        event(now - 102_000, "ListenerAdded", "tcp://0.0.0.0:11010", NetworkEventKind.Other),
        event(now - 118_000, "TunDeviceReady", "mtu 1380", NetworkEventKind.Other),
    )

    private fun event(
        timeMs: Long,
        type: String,
        detail: String,
        kind: NetworkEventKind,
    ) = NetworkEvent(timeMs = timeMs, type = type, detail = detail, kind = kind)
}
