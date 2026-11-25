package app.timiao.et.ui.data

import app.timiao.et.core.NetworkConfig
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

fun NetworkConfig.toUi(): UiNetwork = UiNetwork(
    id = id,
    name = name,
    secret = secret,
    servers = servers,
    dhcp = dhcp,
    ipv4 = ipv4.orEmpty(),
    flags = flags,
    proxyNetworks = proxyNetworks,
    portForwards = portForwards,
    vpnPortal = vpnPortal.orEmpty(),
    relayNetworkWhitelist = relayNetworkWhitelist,
    listeners = listeners,
    aclText = aclText.orEmpty(),
    tomlText = tomlText,
)

fun UiNetwork.toConfig(): NetworkConfig = NetworkConfig(
    id = id,
    name = name,
    secret = secret,
    servers = servers,
    dhcp = dhcp,
    ipv4 = ipv4.ifBlank { null },
    flags = flags,
    proxyNetworks = proxyNetworks,
    portForwards = portForwards,
    vpnPortal = vpnPortal.ifBlank { null },
    relayNetworkWhitelist = relayNetworkWhitelist,
    listeners = listeners,
    aclText = aclText.ifBlank { null },
    tomlText = tomlText,
)

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
