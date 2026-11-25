package app.timiao.et.ui.data

import app.timiao.et.core.Flags
import app.timiao.et.core.NetworkConfig
import app.timiao.et.core.PeerConfig
import app.timiao.et.core.PortForward
import app.timiao.et.core.ProxyNetwork
import app.timiao.et.core.VpnPortal
import java.util.UUID

/**
 * App-side editable model. All screens and the editor use this type.
 * Core model changes only require edits in [toConfig] / [toUi].
 */
data class UiNetwork(
    val id: String,
    val name: String,
    val secret: String = "",
    val servers: List<String> = emptyList(),
    val dhcp: Boolean = true,
    val ipv4: String = "",
    val flags: Map<String, Boolean> = emptyMap(),
    val proxyNetworks: List<String> = emptyList(),
    val portForwards: List<String> = emptyList(),
    val vpnPortal: String = "",
    val relayNetworkWhitelist: List<String> = emptyList(),
    val listeners: List<String> = emptyList(),
    val aclText: String = "",
    val tomlText: String? = null,
) {
    fun isRelayWhitelisted(name: String): Boolean =
        relayNetworkWhitelist.isEmpty() || relayNetworkWhitelist.contains(name) || relayNetworkWhitelist.contains("*")
}

private val boolFlags: Map<String, (Flags) -> Boolean> = mapOf(
    "latency_first" to { f -> f.latencyFirst }, "multi_thread" to { f -> f.multiThread },
    "disable_p2p" to { f -> f.disableP2p }, "disable_udp_hole_punching" to { f -> f.disableUdpHolePunching },
    "enable_encryption" to { f -> f.enableEncryption }, "enable_ipv6" to { f -> f.enableIpv6 },
    "accept_dns" to { f -> f.acceptDns }, "use_smoltcp" to { f -> f.useSmoltcp },
    "private_mode" to { f -> f.privateMode }, "relay_all_peer_rpc" to { f -> f.relayAllPeerRpc },
    "enable_kcp_proxy" to { f -> f.enableKcpProxy }, "enable_quic_proxy" to { f -> f.enableQuicProxy },
    "no_tun" to { f -> f.noTun }, "enable_exit_node" to { f -> f.enableExitNode },
    "proxy_forward_by_system" to { f -> f.proxyForwardBySystem },
)

private fun Flags.withFlag(key: String, v: Boolean): Flags = when (key) {
    "latency_first" -> copy(latencyFirst = v)
    "multi_thread" -> copy(multiThread = v)
    "disable_p2p" -> copy(disableP2p = v)
    "disable_udp_hole_punching" -> copy(disableUdpHolePunching = v)
    "enable_encryption" -> copy(enableEncryption = v)
    "enable_ipv6" -> copy(enableIpv6 = v)
    "accept_dns" -> copy(acceptDns = v)
    "use_smoltcp" -> copy(useSmoltcp = v)
    "private_mode" -> copy(privateMode = v)
    "relay_all_peer_rpc" -> copy(relayAllPeerRpc = v)
    "enable_kcp_proxy" -> copy(enableKcpProxy = v)
    "enable_quic_proxy" -> copy(enableQuicProxy = v)
    "no_tun" -> copy(noTun = v)
    "enable_exit_node" -> copy(enableExitNode = v)
    "proxy_forward_by_system" -> copy(proxyForwardBySystem = v)
    else -> this
}

fun NetworkConfig.toUi(): UiNetwork = UiNetwork(
    id = id,
    name = networkName,
    secret = networkSecret,
    servers = peers.map { it.uri },
    dhcp = dhcp,
    ipv4 = ipv4.orEmpty(),
    flags = boolFlags.mapValues { (_, get) -> get(flags) },
    proxyNetworks = proxyNetworks.map { it.cidr },
    portForwards = portForwards.map { "${it.proto}://${it.bindAddr} -> ${it.dstAddr}" },
    vpnPortal = vpnPortal?.wireguardListen.orEmpty(),
    relayNetworkWhitelist = flags.relayNetworkWhitelist.split(' ').filter { it.isNotBlank() && it != "*" },
    listeners = listenerUrls,
    aclText = acl.orEmpty(),
    tomlText = null,
)

/** Applies UI edits onto [base] so fields the UI does not show (hostname, secure mode, extras...) survive. */
fun UiNetwork.toConfig(base: NetworkConfig? = null): NetworkConfig {
    val b = base ?: NetworkConfig(instanceName = name.ifBlank { id })
    var f = b.flags
    flags.forEach { (k, v) -> f = f.withFlag(k, v) }
    f = f.copy(relayNetworkWhitelist = relayNetworkWhitelist.joinToString(" ").ifBlank { "*" })
    return b.copy(
        networkName = name,
        networkSecret = secret,
        peers = servers.map { u -> b.peers.firstOrNull { it.uri == u } ?: PeerConfig(u) },
        dhcp = dhcp,
        ipv4 = ipv4.ifBlank { null },
        proxyNetworks = proxyNetworks.map { c -> b.proxyNetworks.firstOrNull { it.cidr == c } ?: ProxyNetwork(c) },
        portForwards = portForwards.mapNotNull(::parseForward),
        vpnPortal = if (vpnPortal.isBlank()) null else (b.vpnPortal?.copy(wireguardListen = vpnPortal) ?: VpnPortal(vpnPortal)),
        listenerUrls = listeners,
        acl = aclText.ifBlank { null },
        flags = f,
    )
}

private fun parseForward(line: String): PortForward? {
    val m = Regex("""^(tcp|udp)://(\S+)\s*->\s*(\S+)$""").find(line.trim()) ?: return null
    return PortForward(bindAddr = m.groupValues[2], dstAddr = m.groupValues[3], proto = m.groupValues[1])
}

val DefaultFlags: Map<String, Boolean> = mapOf(
    "enable_encryption" to true,
    "enable_ipv6" to true,
    "multi_thread" to true,
)

fun newNetwork(): UiNetwork = UiNetwork(
    id = UUID.randomUUID().toString(),
    name = "",
    flags = DefaultFlags,
)

/** Official EasyTier boolean flags, shown as grouped switches in the editor. */
val OfficialFlagGroups: List<Pair<String, List<FlagSpec>>> = listOf(
    "连接" to listOf(
        FlagSpec("latency_first", "延迟优先", "优先选择延迟最低的路径"),
        FlagSpec("multi_thread", "多线程", "使用多线程运行时"),
        FlagSpec("disable_p2p", "禁用 P2P", "只通过服务器中转"),
        FlagSpec("disable_udp_hole_punching", "禁用 UDP 打洞", "不尝试 UDP 直连"),
        FlagSpec("enable_encryption", "启用加密", "对等节点之间加密传输"),
    ),
    "网络" to listOf(
        FlagSpec("enable_ipv6", "IPv6", "允许 IPv6 地址与监听"),
        FlagSpec("accept_dns", "接受 DNS", "使用网络下发的 DNS"),
        FlagSpec("use_smoltcp", "smoltcp 协议栈", "使用用户态 TCP/IP 栈"),
        FlagSpec("private_mode", "私有模式", "拒绝未知节点"),
        FlagSpec("relay_all_peer_rpc", "中继全部 RPC", "允许转发所有节点 RPC"),
    ),
    "代理与路由" to listOf(
        FlagSpec("enable_kcp_proxy", "KCP 代理", "启用 KCP 代理"),
        FlagSpec("enable_quic_proxy", "QUIC 代理", "启用 QUIC 代理"),
        FlagSpec("no_tun", "无 TUN", "不创建虚拟网卡"),
        FlagSpec("enable_exit_node", "出口节点", "允许作为出口节点"),
        FlagSpec("proxy_forward_by_system", "系统转发", "由系统转发代理流量"),
    ),
)

data class FlagSpec(val key: String, val label: String, val hint: String)

val AllFlags: List<FlagSpec> = OfficialFlagGroups.flatMap { it.second }
