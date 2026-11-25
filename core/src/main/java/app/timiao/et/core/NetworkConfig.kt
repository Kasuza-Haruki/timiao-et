package app.timiao.et.core

/** One EasyTier network configuration. Mirrors the official GUI config fields. */
public data class NetworkConfig(
    val instanceId: String? = null,
    val instanceName: String = "default",
    val hostname: String? = null,
    val networkName: String = "default",
    val networkSecret: String = "",
    val dhcp: Boolean = true,
    val ipv4: String? = null,
    val ipv6: String? = null,
    val dhcpv6: Boolean? = null,
    val dhcpIpv4Cidr: String? = null,
    val dhcpIpv6Cidr: String? = null,
    val ipv6PublicAddrProvider: Boolean? = null,
    val ipv6PublicAddrAuto: Boolean? = null,
    val ipv6PublicAddrPrefix: String? = null,
    val netns: String? = null,
    val listenerUrls: List<String> = emptyList(),
    val mappedListeners: List<String> = emptyList(),
    val peers: List<PeerConfig> = emptyList(),
    val proxyNetworks: List<ProxyNetwork> = emptyList(),
    val vpnPortal: VpnPortal? = null,
    val portForwards: List<PortForward> = emptyList(),
    val routes: List<String> = emptyList(),
    val exitNodes: List<String> = emptyList(),
    val socks5Proxy: String? = null,
    val tcpWhitelist: List<String> = emptyList(),
    val udpWhitelist: List<String> = emptyList(),
    val stunServers: List<String>? = null,
    val tcpStunServers: List<String>? = null,
    val stunServersV6: List<String>? = null,
    val secureMode: SecureMode? = null,
    val managedCredentials: List<ManagedCredential> = emptyList(),
    val credentialFile: String? = null,
    val acl: String? = null,
    val flags: Flags = Flags(),
    val extras: TomlTable = TomlTable(),
) {
    /** Stable id used by Engine, ConfigRepository and UI. */
    public val id: String
        get() = instanceId?.takeIf { it.isNotBlank() } ?: instanceName
}

public data class PeerConfig(
    val uri: String,
    val publicKey: String? = null,
)

public data class ProxyNetwork(
    val cidr: String,
    val mappedCidr: String? = null,
    val allow: List<String> = emptyList(),
)

public data class VpnPortal(
    val wireguardListen: String,
    val enabled: Boolean? = null,
    val wireguardPrivateKey: String? = null,
    val clients: List<VpnPortalClient> = emptyList(),
)

public data class VpnPortalClient(
    val name: String,
    val virtualIp: String,
    val groups: List<String> = emptyList(),
)

public data class PortForward(
    val bindAddr: String,
    val dstAddr: String,
    val proto: String = "tcp",
)

public data class SecureMode(
    val enabled: Boolean,
    val localPrivateKey: String? = null,
    val localPublicKey: String? = null,
)

public data class ManagedCredential(
    val credentialId: String,
    val credentialSecret: String,
    val groups: List<String> = emptyList(),
    val allowRelay: Boolean = false,
    val allowedProxyCidrs: List<String> = emptyList(),
    val expiryUnix: Long = 0,
    val reusable: Boolean = true,
)

/** All EasyTier `[flags]` fields with their upstream defaults. */
public data class Flags(
    val defaultProtocol: String = "tcp",
    val devName: String = "",
    val enableEncryption: Boolean = true,
    val enableIpv6: Boolean = true,
    val mtu: Int = 1380,
    val latencyFirst: Boolean = false,
    val enableExitNode: Boolean = false,
    val noTun: Boolean = false,
    val useSmoltcp: Boolean = false,
    val relayNetworkWhitelist: String = "*",
    val disableP2p: Boolean = false,
    val relayAllPeerRpc: Boolean = false,
    val disableUdpHolePunching: Boolean = false,
    val multiThread: Boolean = true,
    val dataCompressAlgo: String = "None",
    val bindDevice: Boolean = true,
    val enableKcpProxy: Boolean = false,
    val disableKcpInput: Boolean = false,
    val disableRelayKcp: Boolean = false,
    val proxyForwardBySystem: Boolean = false,
    val acceptDns: Boolean = false,
    val privateMode: Boolean = false,
    val enableQuicProxy: Boolean = false,
    val disableQuicInput: Boolean = false,
    val disableRelayQuic: Boolean = false,
    val foreignRelayBpsLimit: Long? = null,
    val multiThreadCount: Int = 2,
    val enableRelayForeignNetworkKcp: Boolean = false,
    val enableRelayForeignNetworkQuic: Boolean = false,
    val encryptionAlgorithm: String = "aes-gcm",
    val disableSymHolePunching: Boolean = false,
    val p2pOnly: Boolean = false,
    val disableTcpHolePunching: Boolean = false,
    val lazyP2p: Boolean = false,
    val needP2p: Boolean = false,
    val instanceRecvBpsLimit: Long? = null,
    val disableUpnp: Boolean = false,
    val disableRelayData: Boolean = false,
    val enableUdpBroadcastRelay: Boolean = false,
    val socketMark: Int? = null,
    val preferPeerRelay: Boolean = false,
    val enableUdpFec: Boolean = false,
    val enableUdpFecKcp: Boolean = false,
    val enableUdpFecQuic: Boolean = false,
)
