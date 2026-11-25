package app.timiao.et.core

/** EasyTier TOML import/export with round-trip preservation of unknown fields. */
public object TomlCodec {

    public fun importToml(text: String): NetworkConfig {
        val (aclRaw, body) = TomlAcl.split(text)
        val root = TomlParser(body).parse()

        fun str(key: String): String? = (root.entries[key] as? TomlValue.Str)?.value
        fun bool(key: String): Boolean? = (root.entries[key] as? TomlValue.BoolVal)?.value
        fun strings(key: String): List<String> = (root.entries[key] as? TomlValue.Arr)
            ?.items?.mapNotNull { (it as? TomlValue.Str)?.value } ?: emptyList()
        fun tables(key: String): List<TomlTable> = ((root.entries[key] as? TomlValue.Arr)?.items ?: emptyList())
            .filterIsInstance<TomlValue.Tbl>().map { TomlTable(it.entries) }
        fun table(key: String): TomlTable? = (root.entries[key] as? TomlValue.Tbl)?.let { TomlTable(it.entries) }

        val identity = table("network_identity")
        val vpnTable = table("vpn_portal_config")
        val secureTable = table("secure_mode")

        val config = NetworkConfig(
            instanceId = str("instance_id"),
            instanceName = str("instance_name") ?: "default",
            hostname = str("hostname"),
            networkName = identity?.entries?.get("network_name")?.let { (it as? TomlValue.Str)?.value }
                ?: "default",
            networkSecret = identity?.entries?.get("network_secret")?.let { (it as? TomlValue.Str)?.value }
                ?: "",
            dhcp = bool("dhcp") ?: true,
            ipv4 = str("ipv4"),
            ipv6 = str("ipv6"),
            dhcpv6 = bool("dhcpv6"),
            dhcpIpv4Cidr = str("dhcp_ipv4_cidr"),
            dhcpIpv6Cidr = str("dhcp_ipv6_cidr"),
            ipv6PublicAddrProvider = bool("ipv6_public_addr_provider"),
            ipv6PublicAddrAuto = bool("ipv6_public_addr_auto"),
            ipv6PublicAddrPrefix = str("ipv6_public_addr_prefix"),
            netns = str("netns"),
            listenerUrls = strings("listeners"),
            mappedListeners = strings("mapped_listeners"),
            peers = tables("peer").mapNotNull { peer ->
                val uri = peer.string("uri") ?: return@mapNotNull null
                PeerConfig(uri, peer.string("peer_public_key"))
            },
            proxyNetworks = tables("proxy_network").mapNotNull { proxy ->
                val cidr = proxy.string("cidr") ?: return@mapNotNull null
                ProxyNetwork(
                    cidr = cidr,
                    mappedCidr = proxy.string("mapped_cidr"),
                    allow = proxy.strings("allow"),
                )
            },
            vpnPortal = vpnTable?.let { portal ->
                VpnPortal(
                    wireguardListen = portal.string("wireguard_listen") ?: "",
                    enabled = portal.bool("enabled"),
                    wireguardPrivateKey = portal.string("wireguard_private_key"),
                    clients = portal.tables("clients").mapNotNull { client ->
                        val name = client.string("name") ?: return@mapNotNull null
                        VpnPortalClient(
                            name = name,
                            virtualIp = client.string("virtual_ip") ?: "",
                            groups = client.strings("groups"),
                        )
                    },
                )
            },
            portForwards = tables("port_forward").mapNotNull { forward ->
                val bind = forward.string("bind_addr") ?: return@mapNotNull null
                val dst = forward.string("dst_addr") ?: return@mapNotNull null
                PortForward(bind, dst, forward.string("proto") ?: "tcp")
            },
            routes = strings("routes"),
            exitNodes = strings("exit_nodes"),
            socks5Proxy = str("socks5_proxy"),
            tcpWhitelist = strings("tcp_whitelist"),
            udpWhitelist = strings("udp_whitelist"),
            stunServers = optionalStrings(root, "stun_servers"),
            tcpStunServers = optionalStrings(root, "tcp_stun_servers"),
            stunServersV6 = optionalStrings(root, "stun_servers_v6"),
            secureMode = secureTable?.let { secure ->
                SecureMode(
                    enabled = secure.bool("enabled") ?: true,
                    localPrivateKey = secure.string("local_private_key"),
                    localPublicKey = secure.string("local_public_key"),
                )
            },
            managedCredentials = tables("managed_credentials").mapNotNull { credential ->
                val id = credential.string("credential_id") ?: return@mapNotNull null
                ManagedCredential(
                    credentialId = id,
                    credentialSecret = credential.string("credential_secret") ?: "",
                    groups = credential.strings("groups"),
                    allowRelay = credential.bool("allow_relay") ?: false,
                    allowedProxyCidrs = credential.strings("allowed_proxy_cidrs"),
                    expiryUnix = credential.long("expiry_unix") ?: 0,
                    reusable = credential.bool("reusable") ?: true,
                )
            },
            credentialFile = str("credential_file"),
            acl = aclRaw?.trim()?.takeIf { it.isNotEmpty() },
            flags = readFlags(table("flags")),
            extras = cleanExtras(root),
        )
        return config
    }

    public fun exportToml(config: NetworkConfig): String {
        val root = config.extras.copyDeep()
        val entries = root.entries
        entries.remove("acl")

        putString(entries, "instance_name", config.instanceName)
        putString(entries, "instance_id", config.instanceId)
        putString(entries, "hostname", config.hostname)
        putString(entries, "netns", config.netns)
        putString(entries, "ipv4", config.ipv4)
        putString(entries, "ipv6", config.ipv6)
        putBoolean(entries, "dhcpv6", config.dhcpv6)
        putString(entries, "dhcp_ipv4_cidr", config.dhcpIpv4Cidr)
        putString(entries, "dhcp_ipv6_cidr", config.dhcpIpv6Cidr)
        putBoolean(entries, "ipv6_public_addr_provider", config.ipv6PublicAddrProvider)
        putBoolean(entries, "ipv6_public_addr_auto", config.ipv6PublicAddrAuto)
        putString(entries, "ipv6_public_addr_prefix", config.ipv6PublicAddrPrefix)
        entries["dhcp"] = TomlValue.BoolVal(config.dhcp)

        putStrings(entries, "listeners", config.listenerUrls)
        putStrings(entries, "mapped_listeners", config.mappedListeners)
        putStrings(entries, "routes", config.routes)
        putStrings(entries, "exit_nodes", config.exitNodes)
        putStrings(entries, "tcp_whitelist", config.tcpWhitelist)
        putStrings(entries, "udp_whitelist", config.udpWhitelist)
        putOptionalStrings(entries, "stun_servers", config.stunServers)
        putOptionalStrings(entries, "tcp_stun_servers", config.tcpStunServers)
        putOptionalStrings(entries, "stun_servers_v6", config.stunServersV6)
        putString(entries, "socks5_proxy", config.socks5Proxy)
        putString(entries, "credential_file", config.credentialFile)

        val identity = ensureTable(entries, "network_identity")
        identity["network_name"] = TomlValue.Str(config.networkName)
        if (config.networkSecret.isBlank()) {
            identity.remove("network_secret")
        } else {
            identity["network_secret"] = TomlValue.Str(config.networkSecret)
        }
        dropIfEmpty(entries, "network_identity", identity)

        if (config.peers.isEmpty()) {
            entries.remove("peer")
        } else {
            entries["peer"] = TomlValue.Arr(
                config.peers.mapTo(ArrayList<TomlValue>()) { peer ->
                    val item = LinkedHashMap<String, TomlValue>()
                    item["uri"] = TomlValue.Str(peer.uri)
                    peer.publicKey?.takeIf { it.isNotBlank() }?.let {
                        item["peer_public_key"] = TomlValue.Str(it)
                    }
                    TomlValue.Tbl(item)
                },
            )
        }

        if (config.proxyNetworks.isEmpty()) {
            entries.remove("proxy_network")
        } else {
            entries["proxy_network"] = TomlValue.Arr(
                config.proxyNetworks.mapTo(ArrayList<TomlValue>()) { network ->
                    val item = LinkedHashMap<String, TomlValue>()
                    item["cidr"] = TomlValue.Str(network.cidr)
                    network.mappedCidr?.takeIf { it.isNotBlank() }?.let {
                        item["mapped_cidr"] = TomlValue.Str(it)
                    }
                    if (network.allow.isNotEmpty()) {
                        item["allow"] = TomlValue.Arr(
                            network.allow.mapTo(ArrayList<TomlValue>()) { TomlValue.Str(it) },
                        )
                    }
                    TomlValue.Tbl(item)
                },
            )
        }

        if (config.portForwards.isEmpty()) {
            entries.remove("port_forward")
        } else {
            entries["port_forward"] = TomlValue.Arr(
                config.portForwards.mapTo(ArrayList<TomlValue>()) { forward ->
                    val item = LinkedHashMap<String, TomlValue>()
                    item["bind_addr"] = TomlValue.Str(forward.bindAddr)
                    item["dst_addr"] = TomlValue.Str(forward.dstAddr)
                    item["proto"] = TomlValue.Str(forward.proto)
                    TomlValue.Tbl(item)
                },
            )
        }

        applyVpnPortal(entries, config.vpnPortal)

        if (config.managedCredentials.isEmpty()) {
            entries.remove("managed_credentials")
        } else {
            entries["managed_credentials"] = TomlValue.Arr(
                config.managedCredentials.mapTo(ArrayList<TomlValue>()) { credential ->
                    val item = LinkedHashMap<String, TomlValue>()
                    item["credential_id"] = TomlValue.Str(credential.credentialId)
                    item["credential_secret"] = TomlValue.Str(credential.credentialSecret)
                    if (credential.groups.isNotEmpty()) {
                        item["groups"] = TomlValue.Arr(
                            credential.groups.mapTo(ArrayList<TomlValue>()) { TomlValue.Str(it) },
                        )
                    }
                    item["allow_relay"] = TomlValue.BoolVal(credential.allowRelay)
                    if (credential.allowedProxyCidrs.isNotEmpty()) {
                        item["allowed_proxy_cidrs"] = TomlValue.Arr(
                            credential.allowedProxyCidrs.mapTo(ArrayList<TomlValue>()) { TomlValue.Str(it) },
                        )
                    }
                    item["expiry_unix"] = TomlValue.IntVal(credential.expiryUnix)
                    item["reusable"] = TomlValue.BoolVal(credential.reusable)
                    TomlValue.Tbl(item)
                },
            )
        }

        applySecureMode(entries, config.secureMode)

        val flags = ensureTable(entries, "flags")
        writeFlags(flags, config.flags)
        dropIfEmpty(entries, "flags", flags)

        val body = TomlWriter.write(root).trimEnd()
        val acl = config.acl?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            acl == null && body.isEmpty() -> ""
            acl == null -> body + "\n"
            body.isEmpty() -> acl + "\n"
            else -> body + "\n\n" + acl + "\n"
        }
    }

    private fun applyVpnPortal(entries: LinkedHashMap<String, TomlValue>, portal: VpnPortal?) {
        val table = ensureTable(entries, "vpn_portal_config")
        if (portal == null) {
            table.remove("wireguard_listen")
            table.remove("wireguard_private_key")
            table.remove("enabled")
            table.remove("clients")
        } else {
            table["wireguard_listen"] = TomlValue.Str(portal.wireguardListen)
            portal.enabled?.let { table["enabled"] = TomlValue.BoolVal(it) } ?: table.remove("enabled")
            portal.wireguardPrivateKey?.takeIf { it.isNotBlank() }?.let {
                table["wireguard_private_key"] = TomlValue.Str(it)
            } ?: table.remove("wireguard_private_key")
            if (portal.clients.isEmpty()) {
                table.remove("clients")
            } else {
                table["clients"] = TomlValue.Arr(
                    portal.clients.mapTo(ArrayList<TomlValue>()) { client ->
                        val item = LinkedHashMap<String, TomlValue>()
                        item["name"] = TomlValue.Str(client.name)
                        item["virtual_ip"] = TomlValue.Str(client.virtualIp)
                        if (client.groups.isNotEmpty()) {
                            item["groups"] = TomlValue.Arr(
                                client.groups.mapTo(ArrayList<TomlValue>()) { TomlValue.Str(it) },
                            )
                        }
                        TomlValue.Tbl(item)
                    },
                )
            }
        }
        dropIfEmpty(entries, "vpn_portal_config", table)
    }

    private fun applySecureMode(entries: LinkedHashMap<String, TomlValue>, mode: SecureMode?) {
        val table = ensureTable(entries, "secure_mode")
        if (mode == null) {
            table.remove("enabled")
            table.remove("local_private_key")
            table.remove("local_public_key")
        } else {
            table["enabled"] = TomlValue.BoolVal(mode.enabled)
            mode.localPrivateKey?.takeIf { it.isNotBlank() }?.let {
                table["local_private_key"] = TomlValue.Str(it)
            } ?: table.remove("local_private_key")
            mode.localPublicKey?.takeIf { it.isNotBlank() }?.let {
                table["local_public_key"] = TomlValue.Str(it)
            } ?: table.remove("local_public_key")
        }
        dropIfEmpty(entries, "secure_mode", table)
    }

    private fun readFlags(table: TomlTable?): Flags {
        if (table == null) return Flags()
        fun bool(vararg names: String): Boolean? = names.firstNotNullOfOrNull { name ->
            (table.entries[name] as? TomlValue.BoolVal)?.value
        }
        fun long(vararg names: String): Long? = names.firstNotNullOfOrNull { name ->
            when (val value = table.entries[name]) {
                is TomlValue.IntVal -> value.value
                is TomlValue.Str -> value.value.toLongOrNull()
                else -> null
            }
        }
        fun text(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
            when (val value = table.entries[name]) {
                is TomlValue.Str -> value.value
                is TomlValue.IntVal -> value.raw
                is TomlValue.RawVal -> value.raw
                else -> null
            }
        }

        val enableEncryption = bool("enable_encryption", "enableEncryption")
            ?: bool("disable_encryption", "disableEncryption")?.let { !it }
            ?: true
        val enableIpv6 = bool("enable_ipv6", "enableIpv6")
            ?: bool("disable_ipv6", "disableIpv6")?.let { !it }
            ?: true
        return Flags(
            defaultProtocol = text("default_protocol", "defaultProtocol") ?: "tcp",
            devName = text("dev_name", "devName") ?: "",
            enableEncryption = enableEncryption,
            enableIpv6 = enableIpv6,
            mtu = long("mtu")?.toInt() ?: 1380,
            latencyFirst = bool("latency_first", "latencyFirst") ?: false,
            enableExitNode = bool("enable_exit_node", "enableExitNode") ?: false,
            noTun = bool("no_tun", "noTun") ?: false,
            useSmoltcp = bool("use_smoltcp", "useSmoltcp") ?: false,
            relayNetworkWhitelist = text("relay_network_whitelist", "relayNetworkWhitelist") ?: "*",
            disableP2p = bool("disable_p2p", "disableP2p") ?: false,
            relayAllPeerRpc = bool("relay_all_peer_rpc", "relayAllPeerRpc") ?: false,
            disableUdpHolePunching = bool("disable_udp_hole_punching", "disableUdpHolePunching") ?: false,
            multiThread = bool("multi_thread", "multiThread") ?: true,
            dataCompressAlgo = normalizeCompression(text("data_compress_algo", "dataCompressAlgo")),
            bindDevice = bool("bind_device", "bindDevice") ?: true,
            enableKcpProxy = bool("enable_kcp_proxy", "enableKcpProxy") ?: false,
            disableKcpInput = bool("disable_kcp_input", "disableKcpInput") ?: false,
            disableRelayKcp = bool("disable_relay_kcp", "disableRelayKcp") ?: false,
            proxyForwardBySystem = bool("proxy_forward_by_system", "proxyForwardBySystem") ?: false,
            acceptDns = bool("accept_dns", "acceptDns") ?: false,
            privateMode = bool("private_mode", "privateMode") ?: false,
            enableQuicProxy = bool("enable_quic_proxy", "enableQuicProxy") ?: false,
            disableQuicInput = bool("disable_quic_input", "disableQuicInput") ?: false,
            disableRelayQuic = bool("disable_relay_quic", "disableRelayQuic") ?: false,
            foreignRelayBpsLimit = long("foreign_relay_bps_limit", "foreignRelayBpsLimit"),
            multiThreadCount = long("multi_thread_count", "multiThreadCount")?.toInt() ?: 2,
            enableRelayForeignNetworkKcp = bool(
                "enable_relay_foreign_network_kcp",
                "enableRelayForeignNetworkKcp",
            ) ?: false,
            enableRelayForeignNetworkQuic = bool(
                "enable_relay_foreign_network_quic",
                "enableRelayForeignNetworkQuic",
            ) ?: false,
            encryptionAlgorithm = text("encryption_algorithm", "encryptionAlgorithm") ?: "aes-gcm",
            disableSymHolePunching = bool("disable_sym_hole_punching", "disableSymHolePunching") ?: false,
            p2pOnly = bool("p2p_only", "p2pOnly") ?: false,
            disableTcpHolePunching = bool("disable_tcp_hole_punching", "disableTcpHolePunching") ?: false,
            lazyP2p = bool("lazy_p2p", "lazyP2p") ?: false,
            needP2p = bool("need_p2p", "needP2p") ?: false,
            instanceRecvBpsLimit = long("instance_recv_bps_limit", "instanceRecvBpsLimit"),
            disableUpnp = bool("disable_upnp", "disableUpnp") ?: false,
            disableRelayData = bool("disable_relay_data", "disableRelayData") ?: false,
            enableUdpBroadcastRelay = bool(
                "enable_udp_broadcast_relay",
                "enableUdpBroadcastRelay",
            ) ?: false,
            socketMark = long("socket_mark", "socketMark")?.toInt(),
            preferPeerRelay = bool("prefer_peer_relay", "preferPeerRelay") ?: false,
            enableUdpFec = bool("enable_udp_fec", "enableUdpFec") ?: false,
            enableUdpFecKcp = bool("enable_udp_fec_kcp", "enableUdpFecKcp") ?: false,
            enableUdpFecQuic = bool("enable_udp_fec_quic", "enableUdpFecQuic") ?: false,
        )
    }

    private fun writeFlags(table: LinkedHashMap<String, TomlValue>, flags: Flags) {
        fun bool(key: String, value: Boolean, default: Boolean) {
            if (value == default) table.remove(key) else table[key] = TomlValue.BoolVal(value)
        }
        fun text(key: String, value: String, default: String) {
            if (value == default) table.remove(key) else table[key] = TomlValue.Str(value)
        }
        fun int(key: String, value: Int, default: Int) {
            if (value == default) table.remove(key) else table[key] = TomlValue.IntVal(value.toLong())
        }

        text("default_protocol", flags.defaultProtocol, "tcp")
        text("dev_name", flags.devName, "")
        bool("enable_encryption", flags.enableEncryption, true)
        bool("enable_ipv6", flags.enableIpv6, true)
        int("mtu", flags.mtu, 1380)
        bool("latency_first", flags.latencyFirst, false)
        bool("enable_exit_node", flags.enableExitNode, false)
        bool("no_tun", flags.noTun, false)
        bool("use_smoltcp", flags.useSmoltcp, false)
        text("relay_network_whitelist", flags.relayNetworkWhitelist, "*")
        bool("disable_p2p", flags.disableP2p, false)
        bool("relay_all_peer_rpc", flags.relayAllPeerRpc, false)
        bool("disable_udp_hole_punching", flags.disableUdpHolePunching, false)
        bool("multi_thread", flags.multiThread, true)
        text("data_compress_algo", flags.dataCompressAlgo, "None")
        bool("bind_device", flags.bindDevice, true)
        bool("enable_kcp_proxy", flags.enableKcpProxy, false)
        bool("disable_kcp_input", flags.disableKcpInput, false)
        bool("disable_relay_kcp", flags.disableRelayKcp, false)
        bool("proxy_forward_by_system", flags.proxyForwardBySystem, false)
        bool("accept_dns", flags.acceptDns, false)
        bool("private_mode", flags.privateMode, false)
        bool("enable_quic_proxy", flags.enableQuicProxy, false)
        bool("disable_quic_input", flags.disableQuicInput, false)
        bool("disable_relay_quic", flags.disableRelayQuic, false)
        putLong(table, "foreign_relay_bps_limit", flags.foreignRelayBpsLimit)
        int("multi_thread_count", flags.multiThreadCount, 2)
        bool("enable_relay_foreign_network_kcp", flags.enableRelayForeignNetworkKcp, false)
        bool("enable_relay_foreign_network_quic", flags.enableRelayForeignNetworkQuic, false)
        text("encryption_algorithm", flags.encryptionAlgorithm, "aes-gcm")
        bool("disable_sym_hole_punching", flags.disableSymHolePunching, false)
        bool("p2p_only", flags.p2pOnly, false)
        bool("disable_tcp_hole_punching", flags.disableTcpHolePunching, false)
        bool("lazy_p2p", flags.lazyP2p, false)
        bool("need_p2p", flags.needP2p, false)
        putLong(table, "instance_recv_bps_limit", flags.instanceRecvBpsLimit)
        bool("disable_upnp", flags.disableUpnp, false)
        bool("disable_relay_data", flags.disableRelayData, false)
        bool("enable_udp_broadcast_relay", flags.enableUdpBroadcastRelay, false)
        if (flags.socketMark == null) {
            table.remove("socket_mark")
        } else {
            table["socket_mark"] = TomlValue.IntVal(flags.socketMark.toLong())
        }
        bool("prefer_peer_relay", flags.preferPeerRelay, false)
        bool("enable_udp_fec", flags.enableUdpFec, false)
        bool("enable_udp_fec_kcp", flags.enableUdpFecKcp, false)
        bool("enable_udp_fec_quic", flags.enableUdpFecQuic, false)
    }

    private fun normalizeCompression(raw: String?): String = when (raw?.trim()) {
        null, "" -> "None"
        "0" -> "Invalid"
        "1" -> "None"
        "2" -> "Zstd"
        "3" -> "Lzo"
        else -> requireNotNull(raw).trim().replaceFirstChar { it.uppercase() }
    }

    private fun cleanExtras(root: TomlTable): TomlTable {
        val extras = root.copyDeep()
        KNOWN_ROOT_KEYS.forEach { extras.entries.remove(it) }
        cleanKnownTable(extras, "network_identity", NETWORK_IDENTITY_KEYS)
        cleanKnownTable(extras, "secure_mode", SECURE_MODE_KEYS)
        cleanKnownTable(extras, "vpn_portal_config", VPN_PORTAL_KEYS)
        cleanKnownTable(extras, "flags", FLAG_KEYS)
        return extras
    }

    private fun cleanKnownTable(root: TomlTable, tableKey: String, knownKeys: Set<String>) {
        val table = (root.entries[tableKey] as? TomlValue.Tbl)?.entries ?: return
        knownKeys.forEach { table.remove(it) }
        if (table.isEmpty()) root.entries.remove(tableKey)
    }

    private fun optionalStrings(table: TomlTable, key: String): List<String>? {
        val value = table.entries[key] as? TomlValue.Arr ?: return null
        return value.items.mapNotNull { (it as? TomlValue.Str)?.value }
    }

    private fun putString(entries: LinkedHashMap<String, TomlValue>, key: String, value: String?) {
        if (value.isNullOrBlank()) entries.remove(key) else entries[key] = TomlValue.Str(value)
    }

    private fun putBoolean(entries: LinkedHashMap<String, TomlValue>, key: String, value: Boolean?) {
        if (value == null) entries.remove(key) else entries[key] = TomlValue.BoolVal(value)
    }

    private fun putLong(entries: LinkedHashMap<String, TomlValue>, key: String, value: Long?) {
        if (value == null) entries.remove(key) else entries[key] = TomlValue.IntVal(value)
    }

    private fun putStrings(entries: LinkedHashMap<String, TomlValue>, key: String, values: List<String>) {
        if (values.isEmpty()) {
            entries.remove(key)
        } else {
            entries[key] = TomlValue.Arr(values.mapTo(ArrayList<TomlValue>()) { TomlValue.Str(it) })
        }
    }

    private fun putOptionalStrings(
        entries: LinkedHashMap<String, TomlValue>,
        key: String,
        values: List<String>?,
    ) {
        if (values == null) {
            entries.remove(key)
        } else {
            entries[key] = TomlValue.Arr(values.mapTo(ArrayList<TomlValue>()) { TomlValue.Str(it) })
        }
    }

    private fun ensureTable(
        entries: LinkedHashMap<String, TomlValue>,
        key: String,
    ): LinkedHashMap<String, TomlValue> {
        val existing = (entries[key] as? TomlValue.Tbl)?.entries
        if (existing != null) return existing
        val created = LinkedHashMap<String, TomlValue>()
        entries[key] = TomlValue.Tbl(created)
        return created
    }

    private fun dropIfEmpty(
        entries: LinkedHashMap<String, TomlValue>,
        key: String,
        table: LinkedHashMap<String, TomlValue>,
    ) {
        if (table.isEmpty()) entries.remove(key)
    }

    private val KNOWN_ROOT_KEYS = setOf(
        "instance_name", "instance_id", "hostname", "netns", "ipv4", "ipv6", "dhcp",
        "dhcpv6", "dhcp_ipv4_cidr", "dhcp_ipv6_cidr", "ipv6_public_addr_provider",
        "ipv6_public_addr_auto", "ipv6_public_addr_prefix", "listeners", "mapped_listeners",
        "routes", "exit_nodes", "tcp_whitelist", "udp_whitelist", "stun_servers",
        "tcp_stun_servers", "stun_servers_v6", "socks5_proxy", "credential_file",
        "peer", "proxy_network", "port_forward",
        "managed_credentials", "acl",
    )

    private val NETWORK_IDENTITY_KEYS = setOf("network_name", "network_secret")

    private val SECURE_MODE_KEYS = setOf("enabled", "local_private_key", "local_public_key")

    private val VPN_PORTAL_KEYS = setOf("enabled", "wireguard_listen", "wireguard_private_key", "clients")

    private val FLAG_KEYS: Set<String> = setOf(
        "default_protocol", "defaultProtocol", "dev_name", "devName", "enable_encryption",
        "enableEncryption", "disable_encryption", "disableEncryption", "enable_ipv6", "enableIpv6",
        "disable_ipv6", "disableIpv6", "mtu", "latency_first", "latencyFirst", "enable_exit_node",
        "enableExitNode", "no_tun", "noTun", "use_smoltcp", "useSmoltcp",
        "relay_network_whitelist", "relayNetworkWhitelist", "disable_p2p", "disableP2p",
        "relay_all_peer_rpc", "relayAllPeerRpc", "disable_udp_hole_punching",
        "disableUdpHolePunching", "multi_thread", "multiThread", "data_compress_algo",
        "dataCompressAlgo", "bind_device", "bindDevice", "enable_kcp_proxy", "enableKcpProxy",
        "disable_kcp_input", "disableKcpInput", "disable_relay_kcp", "disableRelayKcp",
        "proxy_forward_by_system", "proxyForwardBySystem", "accept_dns", "acceptDns",
        "private_mode", "privateMode", "enable_quic_proxy", "enableQuicProxy",
        "disable_quic_input", "disableQuicInput", "disable_relay_quic", "disableRelayQuic",
        "foreign_relay_bps_limit", "foreignRelayBpsLimit", "multi_thread_count",
        "multiThreadCount", "enable_relay_foreign_network_kcp", "enableRelayForeignNetworkKcp",
        "enable_relay_foreign_network_quic", "enableRelayForeignNetworkQuic",
        "encryption_algorithm", "encryptionAlgorithm", "disable_sym_hole_punching",
        "disableSymHolePunching", "p2p_only", "p2pOnly", "disable_tcp_hole_punching",
        "disableTcpHolePunching", "lazy_p2p", "lazyP2p", "need_p2p", "needP2p",
        "instance_recv_bps_limit", "instanceRecvBpsLimit", "disable_upnp", "disableUpnp",
        "disable_relay_data", "disableRelayData", "enable_udp_broadcast_relay",
        "enableUdpBroadcastRelay", "socket_mark", "socketMark", "prefer_peer_relay",
        "preferPeerRelay", "enable_udp_fec", "enableUdpFec", "enable_udp_fec_kcp",
        "enableUdpFecKcp", "enable_udp_fec_quic", "enableUdpFecQuic",
    )
}

private fun TomlTable.string(key: String): String? = (entries[key] as? TomlValue.Str)?.value

private fun TomlTable.bool(key: String): Boolean? = (entries[key] as? TomlValue.BoolVal)?.value

private fun TomlTable.long(key: String): Long? = when (val value = entries[key]) {
    is TomlValue.IntVal -> value.value
    is TomlValue.Str -> value.value.toLongOrNull()
    else -> null
}

private fun TomlTable.strings(key: String): List<String> = (entries[key] as? TomlValue.Arr)
    ?.items?.mapNotNull { (it as? TomlValue.Str)?.value } ?: emptyList()

private fun TomlTable.tables(key: String): List<TomlTable> = (entries[key] as? TomlValue.Arr)
    ?.items?.filterIsInstance<TomlValue.Tbl>()?.map { TomlTable(it.entries) } ?: emptyList()

/** Splits the `[acl*]` sections out of a document so they can be passed through verbatim. */
internal object TomlAcl {
    fun split(text: String): Pair<String?, String> {
        val acl = StringBuilder()
        val rest = StringBuilder()
        var inAcl = false
        var captured = false
        for (line in text.split("\n")) {
            headerFirstSegment(line)?.let { inAcl = it == "acl" }
            if (inAcl) {
                acl.append(line).append('\n')
                captured = true
            } else {
                rest.append(line).append('\n')
            }
        }
        return if (captured) {
            acl.toString().trim().takeIf { it.isNotEmpty() } to rest.toString()
        } else {
            null to text
        }
    }

    private fun headerFirstSegment(line: String): String? {
        val trimmed = line.trimStart()
        if (!trimmed.startsWith("[")) return null
        val isArray = trimmed.startsWith("[[")
        val bodyStart = if (isArray) 2 else 1
        val close = if (isArray) trimmed.indexOf("]]") else trimmed.indexOf(']')
        if (close < 0 || close <= bodyStart) return null
        val body = trimmed.substring(bodyStart, close).trim()
        if (body.isEmpty()) return null
        return body.substringBefore('.').trim().removeSurrounding("\"")
    }
}

/** Convenience entry point for [TomlCodec.importToml]. */
public fun importToml(text: String): NetworkConfig = TomlCodec.importToml(text)

/** Convenience entry point for [TomlCodec.exportToml]. */
public fun exportToml(config: NetworkConfig): String = TomlCodec.exportToml(config)
