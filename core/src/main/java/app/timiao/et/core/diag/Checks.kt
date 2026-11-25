package app.timiao.et.core.diag

import app.timiao.et.core.LinkState
import app.timiao.et.core.NetworkConfig
import app.timiao.et.core.NetworkStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal object ConfigChecks {
    fun run(config: NetworkConfig?): List<DiagItem> {
        if (config == null) {
            return listOf(
                DiagItem(
                    id = "config_missing",
                    title = "网络配置",
                    severity = DiagSeverity.Fail,
                    summary = "没有选中的网络配置",
                    advice = "先在『网络』页创建并选中一个网络，再回来诊断。",
                ),
            )
        }
        return listOf(servers(config), identity(config), addressing(config), p2pFlags(config))
    }

    private fun servers(config: NetworkConfig): DiagItem {
        val uris = config.peers.map { it.uri }
        val provided = uris.filter { it.isNotBlank() }
        val blankCount = uris.size - provided.size
        if (provided.isEmpty()) {
            val hasListener = config.listenerUrls.any { it.isNotBlank() }
            return if (hasListener) {
                DiagItem(
                    id = "config_servers",
                    title = "服务器地址",
                    severity = DiagSeverity.Warn,
                    summary = "没有配置服务器地址，只能等待对方连接",
                    advice = "在『服务器』里添加对方的 tcp://主机:11010；被动接入时把监听地址发给对方，并确认端口已放行。",
                    details = config.listenerUrls.map { "监听：$it" },
                )
            } else {
                DiagItem(
                    id = "config_servers",
                    title = "服务器地址",
                    severity = DiagSeverity.Fail,
                    summary = "没有服务器地址，无法加入网络",
                    advice = "填写至少一个服务器地址，例如 tcp://1.2.3.4:11010。地址来自服务器上的 EasyTier 监听端口。",
                )
            }
        }

        val invalid = provided.mapNotNull { uri ->
            (parsePeerUri(uri) as? PeerUri.Invalid)?.let { "$uri：${it.reason}" }
        }
        if (invalid.isNotEmpty()) {
            return DiagItem(
                id = "config_servers",
                title = "服务器地址",
                severity = DiagSeverity.Fail,
                summary = "有 ${invalid.size} 个服务器地址格式不对",
                advice = "按『协议://主机:端口』填写，例如 tcp://1.2.3.4:11010。支持 tcp/udp/ws/wss/wg/quic/ring。",
                details = invalid,
            )
        }
        val details = provided.map { uri ->
            val endpoint = parsePeerUri(uri) as PeerUri.Endpoint
            "${endpoint.describe()}（${if (endpoint.tcp) "TCP" else "非 TCP"}）"
        }
        val blankNote = if (blankCount > 0) listOf("另有 $blankCount 个空地址会被忽略") else emptyList()
        return DiagItem(
            id = "config_servers",
            title = "服务器地址",
            severity = if (blankCount > 0) DiagSeverity.Warn else DiagSeverity.Pass,
            summary = if (blankCount > 0) {
                "已配置 ${provided.size} 个服务器地址，有 $blankCount 个空地址"
            } else {
                "已配置 ${provided.size} 个服务器地址"
            },
            advice = if (blankCount > 0) "删除空的服务器地址，避免运行配置混乱。" else null,
            details = details + blankNote,
        )
    }

    private fun identity(config: NetworkConfig): DiagItem {
        val problems = mutableListOf<String>()
        val advice = mutableListOf<String>()
        var severity = DiagSeverity.Pass
        if (config.networkName.isBlank()) {
            severity = DiagSeverity.Fail
            problems += "网络名为空"
            advice += "填写网络名，必须与对方完全一致（区分大小写）。"
        }
        if (config.networkSecret.isBlank()) {
            if (severity == DiagSeverity.Pass) severity = DiagSeverity.Warn
            problems += "网络密钥为空"
            advice += "设置网络密钥，避免任何人都能加入；密钥也要与对方完全一致。"
        }
        return if (problems.isEmpty()) {
            DiagItem(
                id = "config_identity",
                title = "网络名与密钥",
                severity = DiagSeverity.Pass,
                summary = "网络名与密钥已填写",
            )
        } else {
            DiagItem(
                id = "config_identity",
                title = "网络名与密钥",
                severity = severity,
                summary = problems.joinToString("；"),
                advice = advice.joinToString(" "),
            )
        }
    }

    private fun addressing(config: NetworkConfig): DiagItem {
        val staticIpv4 = config.ipv4?.isNotBlank() == true
        return when {
            config.dhcp && staticIpv4 -> DiagItem(
                id = "config_addressing",
                title = "虚拟 IP 分配",
                severity = DiagSeverity.Warn,
                summary = "已开启 DHCP，同时填写了静态 IPv4 ${config.ipv4}",
                advice = "静态 IPv4 会被 DHCP 覆盖。要固定 IP 就关闭 DHCP；要自动分配就清空静态 IPv4。",
            )
            !config.dhcp && !staticIpv4 && config.dhcpIpv4Cidr.isNullOrBlank() -> DiagItem(
                id = "config_addressing",
                title = "虚拟 IP 分配",
                severity = DiagSeverity.Fail,
                summary = "未开启 DHCP，且没有静态 IPv4",
                advice = "开启 DHCP，或填写静态 IP（例如 10.144.144.10/24），否则拿不到虚拟 IP。",
            )
            else -> DiagItem(
                id = "config_addressing",
                title = "虚拟 IP 分配",
                severity = DiagSeverity.Pass,
                summary = if (config.dhcp) "使用 DHCP 自动分配" else "使用静态 IPv4",
            )
        }
    }

    private fun p2pFlags(config: NetworkConfig): DiagItem {
        val conflict = config.flags.p2pOnly && config.flags.disableP2p
        return if (conflict) {
            DiagItem(
                id = "config_p2p",
                title = "P2P 设置",
                severity = DiagSeverity.Fail,
                summary = "『仅 P2P』与『禁用 P2P』同时开启，互相冲突",
                advice = "两者同时开启会导致既不能直连也不能中继。要直连就保留『仅 P2P』；要中继就关闭『禁用 P2P』。",
            )
        } else {
            DiagItem(
                id = "config_p2p",
                title = "P2P 设置",
                severity = DiagSeverity.Pass,
                summary = "P2P 开关无冲突",
            )
        }
    }
}

internal object ReachabilityCheck {
    suspend fun run(config: NetworkConfig, probe: PeerReachabilityProbe): DiagItem? {
        val uris = config.peers.map { it.uri.trim() }.filter { it.isNotEmpty() }
        if (uris.isEmpty()) return null
        val results = coroutineScope {
            uris.map { uri -> async { uri to probe.probe(uri) } }.awaitAll()
        }
        val unreachable = results.filter { it.second is PeerProbeResult.Unreachable }
        val reachable = results.filter { it.second is PeerProbeResult.Reachable }
        val inconclusive = results.filter { it.second is PeerProbeResult.Inconclusive }

        val severity = when {
            reachable.isEmpty() && unreachable.isNotEmpty() -> DiagSeverity.Fail
            unreachable.isNotEmpty() -> DiagSeverity.Warn
            reachable.isEmpty() -> DiagSeverity.Warn
            else -> DiagSeverity.Pass
        }
        val summary = when {
            severity == DiagSeverity.Fail -> "所有服务器地址都连不上"
            unreachable.isNotEmpty() -> "${reachable.size} 个服务器可连接，${unreachable.size} 个不可达"
            reachable.isNotEmpty() && inconclusive.isNotEmpty() ->
                "${reachable.size} 个服务器可连接，${inconclusive.size} 个无法确认"
            reachable.isNotEmpty() -> "全部 ${reachable.size} 个服务器端口可连接"
            else -> "服务器地址无法直接确认可达"
        }
        val advice = when {
            unreachable.isNotEmpty() ->
                "确认服务器上的 EasyTier 正在运行并监听该端口；云服务器安全组、防火墙、路由器端口转发都要放行。域名失败时检查 DNS 或改用 IP。"
            severity == DiagSeverity.Warn ->
                "UDP 没有回应不代表服务坏了。连不上时优先改用 tcp:// 地址，便于用 TCP 探测确认端口。"
            else -> null
        }
        val details = results.map { (uri, result) ->
            val target = (parsePeerUri(uri) as? PeerUri.Endpoint)?.describe() ?: uri
            val outcome = when (result) {
                is PeerProbeResult.Reachable ->
                    if (result.latencyMs > 0) "可连接，耗时 ${result.latencyMs} ms" else "收到 UDP 回应"
                is PeerProbeResult.Unreachable -> result.reason
                is PeerProbeResult.Inconclusive -> result.reason
            }
            "$target — $outcome"
        }
        return DiagItem(
            id = "reachability",
            title = "服务器可达性",
            severity = severity,
            summary = summary,
            advice = advice,
            details = details,
        )
    }
}

internal object StatusChecks {
    private const val HIGH_LATENCY_MS = 200.0
    private const val HIGH_LOSS_RATE = 0.05

    fun run(config: NetworkConfig, status: NetworkStatus, snapshot: InfoSnapshot?): List<DiagItem> {
        val items = mutableListOf<DiagItem>(link(status))
        if (status.peers.isNotEmpty()) items += path(config, status, snapshot)
        if (status.state == LinkState.Connected || status.state == LinkState.Connecting) {
            items += peers(status)
        }
        if (status.peers.isNotEmpty()) items += quality(status)
        return items
    }

    private fun link(status: NetworkStatus): DiagItem = when (status.state) {
        LinkState.Idle -> DiagItem(
            id = "status_link",
            title = "连接状态",
            severity = DiagSeverity.Warn,
            summary = "网络未启动",
            advice = "回到首页点击『连接』，等状态变为已连接后再诊断。",
        )
        LinkState.Connecting -> DiagItem(
            id = "status_link",
            title = "连接状态",
            severity = DiagSeverity.Warn,
            summary = "正在连接，还没有完成握手",
            advice = "若长时间停在这里，检查服务器地址、端口和防火墙，并查看下方事件日志。",
        )
        LinkState.Connected -> DiagItem(
            id = "status_link",
            title = "连接状态",
            severity = DiagSeverity.Pass,
            summary = "网络已连接${status.myIpv4?.let { "，本机 IP $it" } ?: ""}",
        )
        LinkState.Error -> DiagItem(
            id = "status_link",
            title = "连接状态",
            severity = DiagSeverity.Fail,
            summary = "连接出错${status.error?.takeIf { it.isNotBlank() }?.let { "：$it" } ?: ""}",
            advice = "先确认服务器地址可达；若是认证或解密错误，核对网络名与密钥；查看事件日志中的最近错误。",
        )
    }

    private fun peers(status: NetworkStatus): DiagItem {
        if (status.peers.isNotEmpty()) {
            return DiagItem(
                id = "status_peers",
                title = "节点发现",
                severity = DiagSeverity.Pass,
                summary = "已发现 ${status.peers.size} 个节点",
            )
        }
        return DiagItem(
            id = "status_peers",
            title = "节点发现",
            severity = DiagSeverity.Warn,
            summary = "运行中，但还没有发现其他节点",
            advice = "确认网络名与密钥和对方完全一致；确认服务器地址可达且服务器上有同一网络的节点；若是新建网络，等待其他节点加入。",
        )
    }

    private fun path(
        config: NetworkConfig,
        status: NetworkStatus,
        snapshot: InfoSnapshot?,
    ): DiagItem {
        val relay = status.peers.filter { it.cost.startsWith("relay", ignoreCase = true) }
        if (relay.size != status.peers.size) {
            return DiagItem(
                id = "status_path",
                title = "连接方式",
                severity = DiagSeverity.Pass,
                summary = "直连 ${status.peers.size - relay.size} 个，中继 ${relay.size} 个",
                details = relay.map { "${it.hostname} 经中继（${it.cost}）" },
            )
        }

        val nat = snapshot?.myNatType
        val symmetricNat = nat?.contains("Symmetric", ignoreCase = true) == true ||
            status.peers.any { it.natType?.contains("Symmetric", ignoreCase = true) == true }
        val advice = buildList {
            if (config.flags.disableP2p) {
                add("当前开启了『禁用 P2P』，所有流量只能走中继；需要直连请关闭它。")
            }
            if (config.flags.p2pOnly) {
                add("当前开启了『仅 P2P』却仍在中继，说明直连未建立。")
            }
            if (symmetricNat) {
                add("检测到对称 NAT，UDP 打洞通常失败。可在编辑页开启 KCP 或 QUIC 代理，让流量更稳定。")
            } else {
                add("可在编辑页开启 KCP 或 QUIC 代理改善中继质量；同时检查是否误开了『禁用 P2P』。")
            }
        }.joinToString(" ")
        val details = buildList {
            nat?.let { add("本机 NAT：$it") }
            relay.forEach { add("${it.hostname} 经中继（${it.cost}，${it.tunnel ?: "未知隧道"}）") }
        }
        return DiagItem(
            id = "status_path",
            title = "连接方式",
            severity = DiagSeverity.Warn,
            summary = "${status.peers.size} 个节点全部走中继，没有直连",
            advice = advice,
            details = details,
        )
    }

    private fun quality(status: NetworkStatus): DiagItem {
        val slow = status.peers.filter { (it.latencyMs ?: 0.0) > HIGH_LATENCY_MS }
        val lossy = status.peers.filter { (it.lossRate ?: 0.0) > HIGH_LOSS_RATE }
        if (slow.isEmpty() && lossy.isEmpty()) {
            return DiagItem(
                id = "status_quality",
                title = "延迟与丢包",
                severity = DiagSeverity.Pass,
                summary = "延迟与丢包都在正常范围",
            )
        }
        val parts = mutableListOf<String>()
        val advice = mutableListOf<String>()
        if (slow.isNotEmpty()) {
            val worst = slow.maxByOrNull { it.latencyMs ?: 0.0 }!!
            parts += "延迟偏高：${worst.hostname} ${worst.latencyMs?.toInt()} ms"
            advice += "延迟高：换更近的服务器或中继；开启 KCP/QUIC 代理；检查 Wi-Fi 或移动信号。"
        }
        if (lossy.isNotEmpty()) {
            val worst = lossy.maxByOrNull { it.lossRate ?: 0.0 }!!
            val percent = ((worst.lossRate ?: 0.0) * 100)
            parts += "丢包偏高：${worst.hostname} ${"%.1f".format(java.util.Locale.US, percent)}%"
            advice += "丢包高：关闭省电限制、避免弱信号 Wi-Fi，并确认服务器带宽没有被占满。"
        }
        val details = status.peers.map { peer ->
            val latency = peer.latencyMs?.let { "${it.toInt()} ms" } ?: "延迟未知"
            val loss = peer.lossRate?.let {
                "${"%.1f".format(java.util.Locale.US, it * 100)}%"
            } ?: "丢包未知"
            "${peer.hostname} · $latency · 丢包 $loss"
        }
        return DiagItem(
            id = "status_quality",
            title = "延迟与丢包",
            severity = DiagSeverity.Warn,
            summary = parts.joinToString("；"),
            advice = advice.joinToString(" "),
            details = details,
        )
    }
}

internal object DeviceChecks {
    fun run(probe: DeviceProbe): List<DiagItem> = listOf(
        network(probe),
        vpn(probe),
        battery(probe),
        notifications(probe),
    )

    /** Answers "no Internet at all" without waiting for a kernel event. */
    private fun network(probe: DeviceProbe): DiagItem =
        if (probe.hasUnderlyingNetwork()) {
            DiagItem("device_network", "设备网络", DiagSeverity.Pass, "设备当前有可用网络")
        } else {
            DiagItem(
                id = "device_network",
                title = "设备网络",
                severity = DiagSeverity.Fail,
                summary = "设备当前没有可用网络",
                advice = "打开 Wi-Fi 或移动数据，等系统显示网络可用后再诊断和连接。",
            )
        }

    private fun vpn(probe: DeviceProbe): DiagItem = if (probe.hasVpnPermission()) {
        DiagItem("device_vpn", "VPN 权限", DiagSeverity.Pass, "已获得 VPN 授权")
    } else {
        DiagItem(
            id = "device_vpn",
            title = "VPN 权限",
            severity = DiagSeverity.Fail,
            summary = "尚未获得 VPN 授权",
            advice = "点首页『连接』时在系统弹窗选择允许；若拒绝过，去 系统设置 > 网络 > VPN 中允许缇喵。",
        )
    }

    private fun battery(probe: DeviceProbe): DiagItem =
        if (probe.isIgnoringBatteryOptimizations()) {
            DiagItem("device_battery", "省电限制", DiagSeverity.Pass, "已不受电池优化限制")
        } else {
            DiagItem(
                id = "device_battery",
                title = "省电限制",
                severity = DiagSeverity.Warn,
                summary = "系统正在限制缇喵的后台运行",
                advice = "系统设置 > 电池 > 不受限制，允许缇喵后台常驻，否则锁屏后 VPN 可能被断开。",
            )
        }

    private fun notifications(probe: DeviceProbe): DiagItem =
        if (probe.hasNotificationPermission()) {
            DiagItem("device_notifications", "通知权限", DiagSeverity.Pass, "已允许通知")
        } else {
            DiagItem(
                id = "device_notifications",
                title = "通知权限",
                severity = DiagSeverity.Warn,
                summary = "通知权限未开启",
                advice = "允许通知权限，否则前台服务状态不可见，部分系统会更积极清理后台。",
            )
        }
}

internal object EventChecks {
    fun run(snapshot: InfoSnapshot?): DiagItem {
        if (snapshot == null) {
            return DiagItem(
                id = "events",
                title = "事件日志",
                severity = DiagSeverity.Warn,
                summary = "没有获取到内核事件日志",
                advice = "先连接网络并运行几秒，再点『重新诊断』。",
            )
        }
        val events = snapshot.events
        if (events.isEmpty()) {
            return DiagItem(
                id = "events",
                title = "事件日志",
                severity = DiagSeverity.Pass,
                summary = "最近没有记录到异常事件",
            )
        }

        val fatal = events.filter { it.fatal }
        val errors = events.filter {
            it.type == "ConnectError" || it.type == "ConnectionError" ||
                it.type == "ListenerAddFailed" || it.type == "ListenerAcceptFailed"
        }
        // A local link can look "connected" while an auth or network failure blocks
        // every peer. Those are failures, not FYI warnings.
        val blocking = errors.any(::blocksConnectivity)
        val severity = when {
            fatal.isNotEmpty() -> DiagSeverity.Fail
            blocking -> DiagSeverity.Fail
            errors.isNotEmpty() -> DiagSeverity.Warn
            else -> DiagSeverity.Pass
        }
        val summary = when {
            fatal.isNotEmpty() -> fatal.first().text
            errors.isNotEmpty() -> "最近有 ${errors.size} 条连接错误：${errors.first().text}"
            else -> "最近 ${events.size} 条事件中没有错误"
        }
        val advice = if (severity == DiagSeverity.Pass) null else adviceFor(errors.firstOrNull() ?: fatal.first())
        val details = events.take(6).map { event ->
            event.time?.let { "${it} ${event.text}" } ?: event.text
        }
        return DiagItem(
            id = "events",
            title = "事件日志",
            severity = severity,
            summary = summary,
            advice = advice,
            details = details,
        )
    }

    private fun adviceFor(event: DiagEvent): String {
        val raw = event.errorText.orEmpty().lowercase(java.util.Locale.US)
        return when {
            "refused" in raw -> "服务器端口没有开放：确认 EasyTier 已启动，防火墙与云服务器安全组已放行该端口。"
            "timed out" in raw || "timeout" in raw -> "连接超时：检查服务器地址、端口转发和防火墙规则。"
            "dns" in raw || "resolve" in raw || "lookup" in raw ->
                "域名解析失败：检查域名拼写、DNS 设置，或改用 IP 地址。"
            "unreachable" in raw || "no route" in raw || "network is down" in raw ->
                "网络不可达：检查手机网络、代理设置，以及是否有其他 VPN 冲突。"
            "handshake" in raw || "decrypt" in raw || "auth" in raw ->
                "握手或认证失败：核对网络名与密钥，确认双方版本兼容。"
            event.fatal -> "先解决上面的严重事件（虚拟网卡或 IP 冲突），再重新连接。"
            else -> "根据上方事件详情检查服务器状态、网络名与密钥。"
        }
    }

    /** Auth failures and a dead underlying network stop all peers; treat them as failures. */
    private fun blocksConnectivity(event: DiagEvent): Boolean {
        val raw = event.errorText.orEmpty().lowercase(java.util.Locale.US)
        return listOf(
            "handshake", "decrypt", "auth", "invalid secret", "wrong secret",
            "unreachable", "no route", "network is down",
        ).any { it in raw }
    }
}
