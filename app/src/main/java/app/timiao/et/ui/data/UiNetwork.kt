package app.timiao.et.ui.data

import app.timiao.et.core.Flags
import app.timiao.et.core.NetworkConfig
import app.timiao.et.core.PeerConfig
import app.timiao.et.core.PortForward
import app.timiao.et.core.ProxyNetwork
import app.timiao.et.core.SecureMode
import app.timiao.et.core.VpnPortal
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * App-side editable model. [base] is the complete configuration the values came
 * from, so fields the form does not show (managed credentials, dhcpv6, unknown
 * TOML) survive a save. A save always writes a full [NetworkConfig].
 */
data class UiNetwork(
    val id: String,
    val name: String,
    val secret: String = "",
    val hostname: String = "",
    val servers: List<String> = emptyList(),
    val dhcp: Boolean = true,
    val ipv4: String = "",
    val ipv6: String = "",
    val listeners: List<String> = emptyList(),
    val flags: Map<String, Boolean> = emptyMap(),
    val mtu: String = "",
    val instanceRecvBpsLimit: String = "",
    val foreignRelayBpsLimit: String = "",
    val multiThreadCount: String = "2",
    val socketMark: String = "",
    val defaultProtocol: String = "tcp",
    val compressionAlgo: String = "None",
    val encryptionAlgorithm: String = "aes-gcm",
    val devName: String = "",
    val ipv6PublicAddrAuto: Boolean = false,
    val relayNetworkWhitelistEnabled: Boolean = false,
    val relayNetworkWhitelist: List<String> = emptyList(),
    val manualRoutesEnabled: Boolean = false,
    val routes: List<String> = emptyList(),
    val exitNodes: List<String> = emptyList(),
    val mappedListeners: List<String> = emptyList(),
    val stunServers: List<String> = emptyList(),
    val socks5: String = "",
    val secureMode: SecureMode? = null,
    val proxyNetworks: List<ProxyNetwork> = emptyList(),
    val portForwards: List<PortForward> = emptyList(),
    val vpnPortal: VpnPortal? = null,
    val aclText: String = "",
    val base: NetworkConfig? = null,
) {
    val isRelayWhitelisted: (String) -> Boolean
        get() = { name ->
            !relayNetworkWhitelistEnabled || relayNetworkWhitelist.isEmpty() ||
                relayNetworkWhitelist.any { it == "*" || name.startsWith(it.removeSuffix("*")) }
        }
}

fun NetworkConfig.toUi(): UiNetwork {
    val whitelist = flags.relayNetworkWhitelist
    return UiNetwork(
        id = id,
        name = networkName,
        secret = networkSecret,
        hostname = hostname.orEmpty(),
        servers = peers.map { it.uri },
        dhcp = dhcp,
        ipv4 = ipv4.orEmpty(),
        ipv6 = ipv6.orEmpty(),
        listeners = listenerUrls,
        flags = BoolFlags.associate { it.key to it.get(flags) },
        mtu = flags.mtu.toString(),
        instanceRecvBpsLimit = flags.instanceRecvBpsLimit?.toString().orEmpty(),
        foreignRelayBpsLimit = flags.foreignRelayBpsLimit?.toString().orEmpty(),
        multiThreadCount = flags.multiThreadCount.toString(),
        socketMark = flags.socketMark?.toString().orEmpty(),
        defaultProtocol = flags.defaultProtocol,
        compressionAlgo = flags.dataCompressAlgo,
        encryptionAlgorithm = flags.encryptionAlgorithm,
        devName = flags.devName,
        ipv6PublicAddrAuto = ipv6PublicAddrAuto ?: false,
        relayNetworkWhitelistEnabled = whitelist != "*",
        relayNetworkWhitelist = if (whitelist == "*") emptyList() else whitelist.split(' ').filter { it.isNotBlank() },
        manualRoutesEnabled = routes.isNotEmpty(),
        routes = routes,
        exitNodes = exitNodes,
        mappedListeners = mappedListeners,
        stunServers = stunServers.orEmpty(),
        socks5 = socks5Proxy.orEmpty(),
        secureMode = secureMode,
        proxyNetworks = proxyNetworks,
        portForwards = portForwards,
        vpnPortal = vpnPortal?.let { it.copy(enabled = it.enabled ?: true) },
        aclText = acl.orEmpty(),
        base = this,
    )
}

/** Applies UI edits onto [base] (or this model's preserved [UiNetwork.base]). */
fun UiNetwork.toConfig(base: NetworkConfig? = null): NetworkConfig {
    val b = this.base ?: base ?: NetworkConfig()
    var flags = b.flags
    BoolFlags.forEach { spec -> flags = spec.set(flags, this.flags[spec.key] ?: spec.default) }
    flags = flags.copy(
        mtu = mtu.toIntOrNull() ?: 1380,
        instanceRecvBpsLimit = instanceRecvBpsLimit.toLongOrNull(),
        foreignRelayBpsLimit = foreignRelayBpsLimit.toLongOrNull(),
        multiThreadCount = multiThreadCount.toIntOrNull()?.coerceAtLeast(1) ?: 2,
        socketMark = socketMark.toIntOrNull(),
        defaultProtocol = defaultProtocol.ifBlank { "tcp" },
        dataCompressAlgo = compressionAlgo.ifBlank { "None" },
        encryptionAlgorithm = encryptionAlgorithm.ifBlank { "aes-gcm" },
        devName = devName,
        relayNetworkWhitelist = if (relayNetworkWhitelistEnabled) {
            relayNetworkWhitelist.joinToString(" ")
        } else {
            "*"
        },
    )
    val instanceId = id.takeIf { it != "new" } ?: b.instanceId
    return b.copy(
        instanceId = instanceId,
        instanceName = b.instanceName,
        hostname = hostname.ifBlank { null },
        networkName = name,
        networkSecret = secret,
        peers = servers.filter { it.isNotBlank() }.map { uri ->
            b.peers.firstOrNull { it.uri == uri } ?: PeerConfig(uri)
        },
        dhcp = dhcp,
        ipv4 = ipv4.ifBlank { null },
        ipv6 = ipv6.ifBlank { null },
        listenerUrls = listeners.filter { it.isNotBlank() },
        mappedListeners = mappedListeners.filter { it.isNotBlank() },
        routes = if (manualRoutesEnabled) routes.filter { it.isNotBlank() } else emptyList(),
        exitNodes = exitNodes.filter { it.isNotBlank() },
        stunServers = stunServers.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() },
        socks5Proxy = socks5.ifBlank { null },
        secureMode = secureMode,
        proxyNetworks = proxyNetworks,
        portForwards = portForwards,
        vpnPortal = vpnPortal,
        acl = aclText.ifBlank { null },
        ipv6PublicAddrAuto = ipv6PublicAddrAuto,
        flags = flags,
    )
}

// region flags

class BoolFlagSpec(
    val key: String,
    val label: String,
    val hint: String,
    val group: String,
    val default: Boolean = false,
    val get: (Flags) -> Boolean,
    val set: (Flags, Boolean) -> Flags,
)

/** The 30 boolean flags the official GUI offers, plus IPv6 auto handled separately. */
val BoolFlags: List<BoolFlagSpec> = listOf(
    // 连接
    BoolFlagSpec("multi_thread", "启用多线程", "使用多线程运行时", "连接", true, { it.multiThread }, { f, v -> f.copy(multiThread = v) }),
    BoolFlagSpec("bind_device", "仅使用物理网卡", "避免通过其他虚拟网建立连接", "连接", true, { it.bindDevice }, { f, v -> f.copy(bindDevice = v) }),
    BoolFlagSpec("no_tun", "无 TUN 模式", "不创建 TUN，适合无权限时使用", "连接", false, { it.noTun }, { f, v -> f.copy(noTun = v) }),
    BoolFlagSpec("latency_first", "延迟优先", "忽略中转跳数，选择总延迟最低的路径", "连接", false, { it.latencyFirst }, { f, v -> f.copy(latencyFirst = v) }),
    BoolFlagSpec("use_smoltcp", "用户态协议栈", "使用 smoltcp，避免系统防火墙影响代理", "连接", false, { it.useSmoltcp }, { f, v -> f.copy(useSmoltcp = v) }),
    BoolFlagSpec("enable_encryption", "启用加密", "对等节点之间加密传输，需与对端一致", "连接", true, { it.enableEncryption }, { f, v -> f.copy(enableEncryption = v) }),
    BoolFlagSpec("enable_ipv6", "启用 IPv6", "允许 IPv6 通信，关闭后仅使用 IPv4", "连接", true, { it.enableIpv6 }, { f, v -> f.copy(enableIpv6 = v) }),
    BoolFlagSpec("accept_dns", "启用魔法 DNS", "可通过 node.et.net 访问其他节点", "连接", false, { it.acceptDns }, { f, v -> f.copy(acceptDns = v) }),
    BoolFlagSpec("private_mode", "私有模式", "拒绝网络名或密钥不同的节点", "连接", false, { it.privateMode }, { f, v -> f.copy(privateMode = v) }),
    BoolFlagSpec("disable_upnp", "禁用 UPnP", "不自动映射监听端口", "连接", false, { it.disableUpnp }, { f, v -> f.copy(disableUpnp = v) }),
    // P2P 与打洞
    BoolFlagSpec("disable_p2p", "禁用 P2P", "只通过中转通信", "P2P 与打洞", false, { it.disableP2p }, { f, v -> f.copy(disableP2p = v) }),
    BoolFlagSpec("p2p_only", "仅 P2P", "只与已建立 P2P 的节点通信", "P2P 与打洞", false, { it.p2pOnly }, { f, v -> f.copy(p2pOnly = v) }),
    BoolFlagSpec("lazy_p2p", "延迟 P2P", "有实际流量时才建立 P2P", "P2P 与打洞", false, { it.lazyP2p }, { f, v -> f.copy(lazyP2p = v) }),
    BoolFlagSpec("need_p2p", "需要 P2P", "要求对端主动建立 P2P", "P2P 与打洞", false, { it.needP2p }, { f, v -> f.copy(needP2p = v) }),
    BoolFlagSpec("disable_udp_hole_punching", "禁用 UDP 打洞", "不尝试 UDP 直连", "P2P 与打洞", false, { it.disableUdpHolePunching }, { f, v -> f.copy(disableUdpHolePunching = v) }),
    BoolFlagSpec("disable_tcp_hole_punching", "禁用 TCP 打洞", "不尝试 TCP 直连", "P2P 与打洞", false, { it.disableTcpHolePunching }, { f, v -> f.copy(disableTcpHolePunching = v) }),
    BoolFlagSpec("disable_sym_hole_punching", "禁用对称 NAT 打洞", "将对称 NAT 当作锥形 NAT 处理", "P2P 与打洞", false, { it.disableSymHolePunching }, { f, v -> f.copy(disableSymHolePunching = v) }),
    // 代理与转发
    BoolFlagSpec("enable_kcp_proxy", "KCP 代理", "将 TCP 流量转为 KCP", "代理与转发", false, { it.enableKcpProxy }, { f, v -> f.copy(enableKcpProxy = v) }),
    BoolFlagSpec("disable_kcp_input", "禁用 KCP 输入", "不接受 KCP 入站流量", "代理与转发", false, { it.disableKcpInput }, { f, v -> f.copy(disableKcpInput = v) }),
    BoolFlagSpec("enable_quic_proxy", "QUIC 代理", "将 TCP 流量转为 QUIC", "代理与转发", false, { it.enableQuicProxy }, { f, v -> f.copy(enableQuicProxy = v) }),
    BoolFlagSpec("disable_quic_input", "禁用 QUIC 输入", "不接受 QUIC 入站流量", "代理与转发", false, { it.disableQuicInput }, { f, v -> f.copy(disableQuicInput = v) }),
    BoolFlagSpec("enable_exit_node", "启用出口节点", "允许本节点成为出口节点", "代理与转发", false, { it.enableExitNode }, { f, v -> f.copy(enableExitNode = v) }),
    BoolFlagSpec("proxy_forward_by_system", "系统转发", "用内核转发子网代理流量", "代理与转发", false, { it.proxyForwardBySystem }, { f, v -> f.copy(proxyForwardBySystem = v) }),
    BoolFlagSpec("relay_all_peer_rpc", "转发 RPC 包", "转发白名单外节点的 RPC", "代理与转发", false, { it.relayAllPeerRpc }, { f, v -> f.copy(relayAllPeerRpc = v) }),
    BoolFlagSpec("disable_relay_data", "不转发数据", "不承载其他节点的流量", "代理与转发", false, { it.disableRelayData }, { f, v -> f.copy(disableRelayData = v) }),
    BoolFlagSpec("prefer_peer_relay", "优先凭证节点转发", "优先使用已转发到目标的凭证节点", "代理与转发", false, { it.preferPeerRelay }, { f, v -> f.copy(preferPeerRelay = v) }),
    BoolFlagSpec("enable_udp_broadcast_relay", "UDP 广播中继", "仅 Windows，转发局域网广播", "代理与转发", false, { it.enableUdpBroadcastRelay }, { f, v -> f.copy(enableUdpBroadcastRelay = v) }),
    BoolFlagSpec("enable_udp_fec", "UDP FEC", "启用前向纠错", "代理与转发", false, { it.enableUdpFec }, { f, v -> f.copy(enableUdpFec = v) }),
    BoolFlagSpec("enable_udp_fec_kcp", "KCP FEC", "KCP 链路上启用前向纠错", "代理与转发", false, { it.enableUdpFecKcp }, { f, v -> f.copy(enableUdpFecKcp = v) }),
    BoolFlagSpec("enable_udp_fec_quic", "QUIC FEC", "QUIC 链路上启用前向纠错", "代理与转发", false, { it.enableUdpFecQuic }, { f, v -> f.copy(enableUdpFecQuic = v) }),
)

val FlagGroups: List<Pair<String, List<BoolFlagSpec>>> =
    BoolFlags.groupBy { it.group }.map { (group, flags) -> group to flags }

val DefaultProtocols = listOf("tcp", "udp", "wg", "ws", "wss", "quic", "faketcp")
val CompressionAlgos = listOf("None", "Zstd", "Lzo")
val EncryptionAlgorithms = listOf("aes-gcm", "aes-256-gcm", "chacha20-poly1305")

// endregion

// region validation and drafts

data class PortForwardDraft(
    val proto: String = "tcp",
    val bindIp: String = "",
    val bindPort: String = "",
    val dstIp: String = "",
    val dstPort: String = "",
)

fun PortForward.toDraft(): PortForwardDraft {
    val (bindIp, bindPort) = splitHostPort(bindAddr)
    val (dstIp, dstPort) = splitHostPort(dstAddr)
    return PortForwardDraft(proto, bindIp, bindPort, dstIp, dstPort)
}

fun PortForwardDraft.toPortForward(): PortForward =
    PortForward(
        bindAddr = joinHostPort(bindIp, bindPort),
        dstAddr = joinHostPort(dstIp, dstPort),
        proto = proto,
    )

/** Returns a user-facing error, or null when the row is complete and valid. */
fun PortForwardDraft.error(): String? = when {
    proto != "tcp" && proto != "udp" -> "协议只能是 tcp 或 udp"
    bindIp.isBlank() || dstIp.isBlank() -> "地址不能为空"
    !isValidHost(bindIp) || !isValidHost(dstIp) -> "地址格式不正确"
    !isValidPort(bindPort) || !isValidPort(dstPort) -> "端口应为 1-65535"
    else -> null
}

fun splitHostPort(value: String): Pair<String, String> {
    val text = value.trim()
    if (text.isEmpty()) return "" to ""
    if (text.startsWith("[")) {
        val close = text.indexOf(']')
        if (close > 0) {
            val host = text.substring(1, close)
            val port = text.substring(close + 1).removePrefix(":")
            return host to port
        }
    }
    val colon = text.lastIndexOf(':')
    if (colon <= 0 || text.indexOf(':') != colon) return text to ""
    return text.substring(0, colon) to text.substring(colon + 1)
}

fun joinHostPort(host: String, port: String): String {
    val h = host.trim()
    val p = port.trim()
    return if (h.contains(':') && !h.startsWith("[")) "[$h]:$p" else "$h:$p"
}

fun isValidPort(value: String): Boolean = value.trim().toIntOrNull()?.let { it in 1..65535 } == true

/** The static address input is editable only when DHCP is off. */
fun staticIpEditable(dhcp: Boolean): Boolean = !dhcp

fun isValidHost(value: String): Boolean {
    val host = value.trim()
    if (host.isEmpty() || host.any { it.isWhitespace() || it == '/' || it == '@' }) return false
    if (host.contains(':')) return IPv6_PATTERN.matches(host)
    if (host.all { it.isDigit() || it == '.' }) return IPv4_PATTERN.matches(host)
    return HOST_PATTERN.matches(host)
}

fun isValidHostPort(value: String): Boolean {
    val (host, port) = splitHostPort(value)
    return host.isNotBlank() && isValidHost(host) && isValidPort(port)
}

fun isValidCidr(value: String): Boolean {
    val text = value.trim()
    val slash = text.lastIndexOf('/')
    if (slash <= 0) return false
    val host = text.substring(0, slash)
    val prefix = text.substring(slash + 1).toIntOrNull() ?: return false
    return if (host.contains(':')) {
        IPv6_PATTERN.matches(host) && prefix in 0..128
    } else {
        IPv4_PATTERN.matches(host) && prefix in 0..32
    }
}

/** Parses official proxy syntax: `real_cidr` or `real_cidr->mapped_cidr`. */
fun parseProxyNetwork(raw: String): ProxyNetwork? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val parts = text.split("->")
    if (parts.size > 2) return null
    val real = parts[0].trim()
    val mapped = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
    if (!isValidCidr(real)) return null
    if (mapped != null && !isValidCidr(mapped)) return null
    return ProxyNetwork(real, mapped)
}

fun formatProxyNetwork(proxy: ProxyNetwork): String =
    if (proxy.mappedCidr.isNullOrBlank()) proxy.cidr else "${proxy.cidr}->${proxy.mappedCidr}"

fun parseLines(text: String): List<String> = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

fun joinLines(lines: List<String>): String = lines.joinToString("\n")

private val IPv4_PATTERN = Regex("""(\d{1,3}\.){3}\d{1,3}""")
private val HOST_PATTERN = Regex("""[A-Za-z0-9](?:[A-Za-z0-9.-]{0,253})""")
private val IPv6_PATTERN = Regex("""[0-9A-Fa-f:]+""")

val DefaultListeners: List<String> = listOf(
    "tcp://0.0.0.0:11010",
    "udp://0.0.0.0:11010",
    "wg://0.0.0.0:11011",
)

/** Official GUI defaults: network name easytier, DHCP on, three listeners. */
fun newNetwork(): UiNetwork = UiNetwork(
    id = UUID.randomUUID().toString(),
    name = "easytier",
    dhcp = true,
    listeners = DefaultListeners,
    flags = BoolFlags.associate { it.key to it.default },
    mtu = "",
    multiThreadCount = "2",
    defaultProtocol = "tcp",
    compressionAlgo = "None",
    encryptionAlgorithm = "aes-gcm",
)

// endregion

// region WireGuard portal keys and client config

/** WireGuard key helpers. X25519 is implemented in pure Kotlin, so API 26+ works. */
object WireGuardKeys {
    private const val SALT = "easytier/wireguard-portal/v1"

    /** Generates a clamped Curve25519 private key, Base64 encoded like `wg genkey`. */
    fun generatePrivateKey(random: SecureRandom = SecureRandom()): String {
        val key = ByteArray(32)
        random.nextBytes(key)
        key[0] = (key[0].toInt() and 248).toByte()
        key[31] = ((key[31].toInt() and 127) or 64).toByte()
        return Base64.getEncoder().encodeToString(key)
    }

    /** Pure-Kotlin X25519, so every supported Android version can derive keys. */
    fun canDerivePublicKey(): Boolean = true

    fun publicKey(privateKeyBase64: String): String? = runCatching {
        val privateBytes = Base64.getDecoder().decode(privateKeyBase64)
        require(privateBytes.size == 32)
        val basepoint = ByteArray(32).also { it[0] = 9 }
        Base64.getEncoder().encodeToString(x25519(privateBytes, basepoint))
    }.getOrNull()

    /** RFC 7748 X25519 scalar multiplication. Inputs and output are little-endian. */
    internal fun x25519(privateKey: ByteArray, uCoordinate: ByteArray): ByteArray {
        require(privateKey.size == 32 && uCoordinate.size == 32)
        val scalar = privateKey.copyOf()
        scalar[0] = (scalar[0].toInt() and 248).toByte()
        scalar[31] = ((scalar[31].toInt() and 127) or 64).toByte()
        val k = leToBigInteger(scalar)
        val p = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
        val x1 = leToBigInteger(uCoordinate).mod(p)
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0
        for (t in 254 downTo 0) {
            val bit = k.shiftRight(t).and(BigInteger.ONE).toInt()
            swap = swap xor bit
            if (swap == 1) {
                val tx = x2; x2 = x3; x3 = tx
                val tz = z2; z2 = z3; z3 = tz
            }
            swap = bit
            val a = x2.add(z2).mod(p)
            val aa = a.multiply(a).mod(p)
            val b = x2.subtract(z2).mod(p)
            val bb = b.multiply(b).mod(p)
            val e = aa.subtract(bb).mod(p)
            val c = x3.add(z3).mod(p)
            val d = x3.subtract(z3).mod(p)
            val da = d.multiply(a).mod(p)
            val cb = c.multiply(b).mod(p)
            x3 = da.add(cb).mod(p).let { it.multiply(it).mod(p) }
            z3 = x1.multiply(da.subtract(cb).mod(p).let { it.multiply(it).mod(p) }).mod(p)
            x2 = aa.multiply(bb).mod(p)
            z2 = e.multiply(aa.add(BigInteger.valueOf(121665).multiply(e).mod(p)).mod(p)).mod(p)
        }
        if (swap == 1) {
            val tx = x2; x2 = x3; x3 = tx
            val tz = z2; z2 = z3; z3 = tz
        }
        val result = x2.multiply(z2.modPow(p.subtract(BigInteger.valueOf(2)), p)).mod(p)
        return bigIntegerToLe32(result)
    }

    private fun leToBigInteger(bytes: ByteArray): BigInteger =
        BigInteger(1, bytes.reversedArray())

    private fun bigIntegerToLe32(value: BigInteger): ByteArray {
        val be = value.toByteArray().let { raw ->
            if (raw.size > 32) raw.copyOfRange(raw.size - 32, raw.size) else raw
        }
        val out = ByteArray(32)
        for (index in be.indices) out[index] = be[be.size - 1 - index]
        return out
    }

    /** HKDF-SHA256 derivation used by the official portal for client keys. */
    fun deriveClientPrivateKey(masterPrivateKeyBase64: String, clientName: String): String? = runCatching {
        val master = Base64.getDecoder().decode(masterPrivateKeyBase64)
        require(master.size == 32)
        val info = ArrayList<Byte>()
        appendLabel(info, "wireguard-client".toByteArray())
        appendLabel(info, clientName.toByteArray())
        val okm = hkdfSha256(master, SALT.toByteArray(), info.toByteArray(), 32)
        Base64.getEncoder().encodeToString(okm)
    }.getOrNull()

    /**
     * Builds the official client configuration template. Returns null when the
     * platform cannot derive X25519 keys or the input is incomplete.
     */
    fun clientConfig(
        masterPrivateKeyBase64: String,
        listen: String,
        clientName: String,
        virtualIpCidr: String,
        proxyCidrs: List<String>,
    ): String? {
        if (!isValidHostPort(listen)) return null
        val address = virtualIpCidr.substringBefore('/').trim()
        if (address.isBlank()) return null
        val clientPrivate = deriveClientPrivateKey(masterPrivateKeyBase64, clientName) ?: return null
        val serverPublic = publicKey(masterPrivateKeyBase64) ?: return null
        val allowed = LinkedHashSet<String>()
        proxyCidrs.forEach { cidr -> parseProxyNetwork(cidr)?.let { allowed.add(it.mappedCidr ?: it.cidr) } }
        networkCidr(virtualIpCidr)?.let { allowed.add(it) }
        val allowedText = allowed.joinToString(", ")
        return buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = $clientPrivate")
            appendLine("Address = $address/32")
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = $serverPublic")
            appendLine("AllowedIPs = $allowedText")
            appendLine("Endpoint = $listen # 占位：0.0.0.0 请替换为公网地址")
            appendLine("PersistentKeepalive = 25")
        }
    }

    private fun appendLabel(out: MutableList<Byte>, label: ByteArray) {
        val length = label.size
        out.add(((length ushr 24) and 0xFF).toByte())
        out.add(((length ushr 16) and 0xFF).toByte())
        out.add(((length ushr 8) and 0xFF).toByte())
        out.add((length and 0xFF).toByte())
        label.forEach { out.add(it) }
    }

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info)
        mac.update(1)
        return mac.doFinal().copyOf(length)
    }

    private fun networkCidr(cidr: String): String? {
        val text = cidr.trim()
        val slash = text.lastIndexOf('/')
        if (slash <= 0) return null
        val host = text.substring(0, slash)
        val prefix = text.substring(slash + 1).toIntOrNull() ?: return null
        val octets = host.split('.').mapNotNull { it.toIntOrNull() }
        if (octets.size != 4 || prefix !in 0..32) return null
        var value = 0L
        octets.forEach { value = (value shl 8) or it.toLong() }
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val network = value and mask
        val out = (3 downTo 0).map { shift -> ((network ushr (shift * 8)) and 0xFF).toString() }
        return out.joinToString(".") + "/$prefix"
    }
}

// endregion
