package app.timiao.et.core.diag

import app.timiao.et.core.LinkState
import app.timiao.et.core.NetworkConfig
import app.timiao.et.core.NetworkStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

private fun DiagStrings.text(key: String, vararg args: Any?): String = get(key, *args)

internal object ConfigChecks {
    fun run(config: NetworkConfig?, strings: DiagStrings): List<DiagItem> {
        if (config == null) {
            return listOf(
                DiagItem(
                    id = "config_missing",
                    title = strings.text("diag_config_missing_title"),
                    severity = DiagSeverity.Fail,
                    summary = strings.text("diag_config_missing_summary"),
                    advice = strings.text("diag_config_missing_advice"),
                ),
            )
        }
        return listOf(servers(config, strings), identity(config, strings), addressing(config, strings), p2pFlags(config, strings))
    }

    private fun servers(config: NetworkConfig, strings: DiagStrings): DiagItem {
        val uris = config.peers.map { it.uri }
        val provided = uris.filter { it.isNotBlank() }
        val blankCount = uris.size - provided.size
        if (provided.isEmpty()) {
            val hasListener = config.listenerUrls.any { it.isNotBlank() }
            return if (hasListener) {
                DiagItem(
                    id = "config_servers",
                    title = strings.text("diag_servers_title"),
                    severity = DiagSeverity.Warn,
                    summary = strings.text("diag_servers_none_listener_summary"),
                    advice = strings.text("diag_servers_none_listener_advice"),
                    details = config.listenerUrls.map { strings.text("diag_servers_listener_detail", it) },
                )
            } else {
                DiagItem(
                    id = "config_servers",
                    title = strings.text("diag_servers_title"),
                    severity = DiagSeverity.Fail,
                    summary = strings.text("diag_servers_none_fail_summary"),
                    advice = strings.text("diag_servers_none_fail_advice"),
                )
            }
        }

        val invalid = provided.mapNotNull { uri ->
            (parsePeerUri(uri) as? PeerUri.Invalid)?.let {
                strings.text(
                    "diag_servers_invalid_detail",
                    uri,
                    strings.text(it.reasonKey, *it.args.toTypedArray()),
                )
            }
        }
        if (invalid.isNotEmpty()) {
            return DiagItem(
                id = "config_servers",
                title = strings.text("diag_servers_title"),
                severity = DiagSeverity.Fail,
                summary = strings.text("diag_servers_invalid_summary", invalid.size),
                advice = strings.text("diag_servers_invalid_advice"),
                details = invalid,
            )
        }
        val details = provided.map { uri ->
            val endpoint = parsePeerUri(uri) as PeerUri.Endpoint
            strings.text(
                "diag_servers_endpoint_detail",
                endpoint.describe(),
                strings.text(if (endpoint.tcp) "diag_tcp" else "diag_non_tcp"),
            )
        }
        val blankNote = if (blankCount > 0) {
            listOf(strings.text("diag_servers_blank_note", blankCount))
        } else {
            emptyList()
        }
        return DiagItem(
            id = "config_servers",
            title = strings.text("diag_servers_title"),
            severity = if (blankCount > 0) DiagSeverity.Warn else DiagSeverity.Pass,
            summary = if (blankCount > 0) {
                strings.text("diag_servers_configured_blank_summary", provided.size, blankCount)
            } else {
                strings.text("diag_servers_configured_summary", provided.size)
            },
            advice = if (blankCount > 0) strings.text("diag_servers_blank_advice") else null,
            details = details + blankNote,
        )
    }

    private fun identity(config: NetworkConfig, strings: DiagStrings): DiagItem {
        val problems = mutableListOf<String>()
        val advice = mutableListOf<String>()
        var severity = DiagSeverity.Pass
        if (config.networkName.isBlank()) {
            severity = DiagSeverity.Fail
            problems += strings.text("diag_identity_name_empty")
            advice += strings.text("diag_identity_name_advice")
        }
        if (config.networkSecret.isBlank()) {
            if (severity == DiagSeverity.Pass) severity = DiagSeverity.Warn
            problems += strings.text("diag_identity_secret_empty")
            advice += strings.text("diag_identity_secret_advice")
        }
        return if (problems.isEmpty()) {
            DiagItem(
                id = "config_identity",
                title = strings.text("diag_identity_title"),
                severity = DiagSeverity.Pass,
                summary = strings.text("diag_identity_ok_summary"),
            )
        } else {
            DiagItem(
                id = "config_identity",
                title = strings.text("diag_identity_title"),
                severity = severity,
                summary = problems.joinToString(strings.text("diag_list_sep")),
                advice = advice.joinToString(" "),
            )
        }
    }

    private fun addressing(config: NetworkConfig, strings: DiagStrings): DiagItem {
        val staticIpv4 = config.ipv4?.isNotBlank() == true
        return when {
            config.dhcp && staticIpv4 -> DiagItem(
                id = "config_addressing",
                title = strings.text("diag_addressing_title"),
                severity = DiagSeverity.Warn,
                summary = strings.text("diag_addressing_dhcp_static_summary", config.ipv4),
                advice = strings.text("diag_addressing_dhcp_static_advice"),
            )
            !config.dhcp && !staticIpv4 && config.dhcpIpv4Cidr.isNullOrBlank() -> DiagItem(
                id = "config_addressing",
                title = strings.text("diag_addressing_title"),
                severity = DiagSeverity.Fail,
                summary = strings.text("diag_addressing_none_summary"),
                advice = strings.text("diag_addressing_none_advice"),
            )
            else -> DiagItem(
                id = "config_addressing",
                title = strings.text("diag_addressing_title"),
                severity = DiagSeverity.Pass,
                summary = if (config.dhcp) {
                    strings.text("diag_addressing_dhcp_summary")
                } else {
                    strings.text("diag_addressing_static_summary")
                },
            )
        }
    }

    private fun p2pFlags(config: NetworkConfig, strings: DiagStrings): DiagItem {
        val conflict = config.flags.p2pOnly && config.flags.disableP2p
        return if (conflict) {
            DiagItem(
                id = "config_p2p",
                title = strings.text("diag_p2p_title"),
                severity = DiagSeverity.Fail,
                summary = strings.text("diag_p2p_conflict_summary"),
                advice = strings.text("diag_p2p_conflict_advice"),
            )
        } else {
            DiagItem(
                id = "config_p2p",
                title = strings.text("diag_p2p_title"),
                severity = DiagSeverity.Pass,
                summary = strings.text("diag_p2p_ok_summary"),
            )
        }
    }
}

internal object ReachabilityCheck {
    suspend fun run(
        config: NetworkConfig,
        probe: PeerReachabilityProbe,
        strings: DiagStrings,
    ): DiagItem? {
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
            severity == DiagSeverity.Fail -> strings.text("diag_reach_all_fail_summary")
            unreachable.isNotEmpty() ->
                strings.text("diag_reach_partial_summary", reachable.size, unreachable.size)
            reachable.isNotEmpty() && inconclusive.isNotEmpty() ->
                strings.text("diag_reach_inconclusive_summary", reachable.size, inconclusive.size)
            reachable.isNotEmpty() -> strings.text("diag_reach_all_ok_summary", reachable.size)
            else -> strings.text("diag_reach_unknown_summary")
        }
        val advice = when {
            unreachable.isNotEmpty() -> strings.text("diag_reach_unreachable_advice")
            severity == DiagSeverity.Warn -> strings.text("diag_reach_warn_advice")
            else -> null
        }
        val details = results.map { (uri, result) ->
            val target = (parsePeerUri(uri) as? PeerUri.Endpoint)?.describe() ?: uri
            val outcome = when (result) {
                is PeerProbeResult.Reachable ->
                    if (result.latencyMs > 0) {
                        strings.text("diag_reach_detail_reachable", result.latencyMs)
                    } else {
                        strings.text("diag_reach_detail_udp")
                    }
                is PeerProbeResult.Unreachable ->
                    strings.text(result.reasonKey, *result.args.toTypedArray())
                is PeerProbeResult.Inconclusive ->
                    strings.text(result.reasonKey, *result.args.toTypedArray())
            }
            strings.text("diag_reach_detail_line", target, outcome)
        }
        return DiagItem(
            id = "reachability",
            title = strings.text("diag_reach_title"),
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

    fun run(
        config: NetworkConfig,
        status: NetworkStatus,
        snapshot: InfoSnapshot?,
        strings: DiagStrings,
    ): List<DiagItem> {
        val items = mutableListOf<DiagItem>(link(status, strings))
        if (status.peers.isNotEmpty()) items += path(config, status, snapshot, strings)
        if (status.state == LinkState.Connected || status.state == LinkState.Connecting) {
            items += peers(status, strings)
        }
        if (status.peers.isNotEmpty()) items += quality(status, strings)
        return items
    }

    private fun link(status: NetworkStatus, strings: DiagStrings): DiagItem = when (status.state) {
        LinkState.Idle -> DiagItem(
            id = "status_link",
            title = strings.text("diag_link_title"),
            severity = DiagSeverity.Warn,
            summary = strings.text("diag_link_idle_summary"),
            advice = strings.text("diag_link_idle_advice"),
        )
        LinkState.Connecting -> DiagItem(
            id = "status_link",
            title = strings.text("diag_link_title"),
            severity = DiagSeverity.Warn,
            summary = strings.text("diag_link_connecting_summary"),
            advice = strings.text("diag_link_connecting_advice"),
        )
        LinkState.Connected -> DiagItem(
            id = "status_link",
            title = strings.text("diag_link_title"),
            severity = DiagSeverity.Pass,
            summary = status.myIpv4?.let {
                strings.text("diag_link_connected_ip_summary", it)
            } ?: strings.text("diag_link_connected_summary"),
        )
        LinkState.Error -> DiagItem(
            id = "status_link",
            title = strings.text("diag_link_title"),
            severity = DiagSeverity.Fail,
            summary = status.error?.takeIf { it.isNotBlank() }?.let {
                strings.text("diag_link_error_detail_summary", it)
            } ?: strings.text("diag_link_error_summary"),
            advice = strings.text("diag_link_error_advice"),
        )
    }

    private fun peers(status: NetworkStatus, strings: DiagStrings): DiagItem {
        if (status.peers.isNotEmpty()) {
            return DiagItem(
                id = "status_peers",
                title = strings.text("diag_peers_title"),
                severity = DiagSeverity.Pass,
                summary = strings.text("diag_peers_found_summary", status.peers.size),
            )
        }
        return DiagItem(
            id = "status_peers",
            title = strings.text("diag_peers_title"),
            severity = DiagSeverity.Warn,
            summary = strings.text("diag_peers_none_summary"),
            advice = strings.text("diag_peers_none_advice"),
        )
    }

    private fun path(
        config: NetworkConfig,
        status: NetworkStatus,
        snapshot: InfoSnapshot?,
        strings: DiagStrings,
    ): DiagItem {
        val relay = status.peers.filter { it.cost.startsWith("relay", ignoreCase = true) }
        if (relay.size != status.peers.size) {
            return DiagItem(
                id = "status_path",
                title = strings.text("diag_path_title"),
                severity = DiagSeverity.Pass,
                summary = strings.text(
                    "diag_path_mixed_summary",
                    status.peers.size - relay.size,
                    relay.size,
                ),
                details = relay.map {
                    strings.text("diag_path_relay_detail", it.hostname, it.cost)
                },
            )
        }

        val nat = snapshot?.myNatType
        val symmetricNat = nat?.contains("Symmetric", ignoreCase = true) == true ||
            status.peers.any { it.natType?.contains("Symmetric", ignoreCase = true) == true }
        val advice = buildList {
            if (config.flags.disableP2p) {
                add(strings.text("diag_path_disable_p2p_advice"))
            }
            if (config.flags.p2pOnly) {
                add(strings.text("diag_path_p2p_only_advice"))
            }
            if (symmetricNat) {
                add(strings.text("diag_path_symmetric_advice"))
            } else {
                add(strings.text("diag_path_general_advice"))
            }
        }.joinToString(" ")
        val details = buildList {
            nat?.let { add(strings.text("diag_path_nat_detail", it)) }
            relay.forEach {
                add(
                    strings.text(
                        "diag_path_relay_full_detail",
                        it.hostname,
                        it.cost,
                        it.tunnel ?: strings.text("diag_unknown_tunnel"),
                    ),
                )
            }
        }
        return DiagItem(
            id = "status_path",
            title = strings.text("diag_path_title"),
            severity = DiagSeverity.Warn,
            summary = strings.text("diag_path_all_relay_summary", status.peers.size),
            advice = advice,
            details = details,
        )
    }

    private fun quality(status: NetworkStatus, strings: DiagStrings): DiagItem {
        val slow = status.peers.filter { (it.latencyMs ?: 0.0) > HIGH_LATENCY_MS }
        val lossy = status.peers.filter { (it.lossRate ?: 0.0) > HIGH_LOSS_RATE }
        if (slow.isEmpty() && lossy.isEmpty()) {
            return DiagItem(
                id = "status_quality",
                title = strings.text("diag_quality_title"),
                severity = DiagSeverity.Pass,
                summary = strings.text("diag_quality_ok_summary"),
            )
        }
        val parts = mutableListOf<String>()
        val advice = mutableListOf<String>()
        if (slow.isNotEmpty()) {
            val worst = slow.maxByOrNull { it.latencyMs ?: 0.0 }!!
            parts += strings.text(
                "diag_quality_latency_part",
                worst.hostname,
                worst.latencyMs?.toInt() ?: 0,
            )
            advice += strings.text("diag_quality_latency_advice")
        }
        if (lossy.isNotEmpty()) {
            val worst = lossy.maxByOrNull { it.lossRate ?: 0.0 }!!
            val percent = ((worst.lossRate ?: 0.0) * 100)
            parts += strings.text(
                "diag_quality_loss_part",
                worst.hostname,
                String.format(java.util.Locale.US, "%.1f", percent),
            )
            advice += strings.text("diag_quality_loss_advice")
        }
        val details = status.peers.map { peer ->
            val latency = peer.latencyMs?.let { "${it.toInt()} ms" }
                ?: strings.text("diag_latency_unknown")
            val loss = peer.lossRate?.let {
                "${String.format(java.util.Locale.US, "%.1f", it * 100)}%"
            } ?: strings.text("diag_loss_unknown")
            strings.text("diag_quality_detail", peer.hostname, latency, loss)
        }
        return DiagItem(
            id = "status_quality",
            title = strings.text("diag_quality_title"),
            severity = DiagSeverity.Warn,
            summary = parts.joinToString(strings.text("diag_list_sep")),
            advice = advice.joinToString(" "),
            details = details,
        )
    }
}

internal object DeviceChecks {
    fun run(probe: DeviceProbe, strings: DiagStrings): List<DiagItem> = listOf(
        network(probe, strings),
        vpn(probe, strings),
        battery(probe, strings),
        notifications(probe, strings),
    )

    /** Answers "no Internet at all" without waiting for a kernel event. */
    private fun network(probe: DeviceProbe, strings: DiagStrings): DiagItem =
        if (probe.hasUnderlyingNetwork()) {
            DiagItem(
                "device_network",
                strings.text("diag_device_network_title"),
                DiagSeverity.Pass,
                strings.text("diag_device_network_ok"),
            )
        } else {
            DiagItem(
                id = "device_network",
                title = strings.text("diag_device_network_title"),
                severity = DiagSeverity.Fail,
                summary = strings.text("diag_device_network_fail"),
                advice = strings.text("diag_device_network_advice"),
            )
        }

    private fun vpn(probe: DeviceProbe, strings: DiagStrings): DiagItem = if (probe.hasVpnPermission()) {
        DiagItem(
            "device_vpn",
            strings.text("diag_device_vpn_title"),
            DiagSeverity.Pass,
            strings.text("diag_device_vpn_ok"),
        )
    } else {
        DiagItem(
            id = "device_vpn",
            title = strings.text("diag_device_vpn_title"),
            severity = DiagSeverity.Fail,
            summary = strings.text("diag_device_vpn_fail"),
            advice = strings.text("diag_device_vpn_advice"),
        )
    }

    private fun battery(probe: DeviceProbe, strings: DiagStrings): DiagItem =
        if (probe.isIgnoringBatteryOptimizations()) {
            DiagItem(
                "device_battery",
                strings.text("diag_device_battery_title"),
                DiagSeverity.Pass,
                strings.text("diag_device_battery_ok"),
            )
        } else {
            DiagItem(
                id = "device_battery",
                title = strings.text("diag_device_battery_title"),
                severity = DiagSeverity.Warn,
                summary = strings.text("diag_device_battery_fail"),
                advice = strings.text("diag_device_battery_advice"),
            )
        }

    private fun notifications(probe: DeviceProbe, strings: DiagStrings): DiagItem =
        if (probe.hasNotificationPermission()) {
            DiagItem(
                "device_notifications",
                strings.text("diag_device_notifications_title"),
                DiagSeverity.Pass,
                strings.text("diag_device_notifications_ok"),
            )
        } else {
            DiagItem(
                id = "device_notifications",
                title = strings.text("diag_device_notifications_title"),
                severity = DiagSeverity.Warn,
                summary = strings.text("diag_device_notifications_fail"),
                advice = strings.text("diag_device_notifications_advice"),
            )
        }
}

internal object EventChecks {
    fun run(snapshot: InfoSnapshot?, strings: DiagStrings): DiagItem {
        if (snapshot == null) {
            return DiagItem(
                id = "events",
                title = strings.text("diag_events_title"),
                severity = DiagSeverity.Warn,
                summary = strings.text("diag_events_none_summary"),
                advice = strings.text("diag_events_none_advice"),
            )
        }
        val events = snapshot.events
        if (events.isEmpty()) {
            return DiagItem(
                id = "events",
                title = strings.text("diag_events_title"),
                severity = DiagSeverity.Pass,
                summary = strings.text("diag_events_empty_summary"),
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
            errors.isNotEmpty() -> strings.text("diag_events_error_summary", errors.size, errors.first().text)
            else -> strings.text("diag_events_clean_summary", events.size)
        }
        val advice = if (severity == DiagSeverity.Pass) {
            null
        } else {
            adviceFor(errors.firstOrNull() ?: fatal.first(), strings)
        }
        val details = events.take(6).map { event ->
            event.time?.let { strings.text("diag_events_detail_line", it, event.text) } ?: event.text
        }
        return DiagItem(
            id = "events",
            title = strings.text("diag_events_title"),
            severity = severity,
            summary = summary,
            advice = advice,
            details = details,
        )
    }

    private fun adviceFor(event: DiagEvent, strings: DiagStrings): String {
        val raw = event.errorText.orEmpty().lowercase(java.util.Locale.US)
        return when {
            "refused" in raw -> strings.text("diag_events_advice_refused")
            "timed out" in raw || "timeout" in raw -> strings.text("diag_events_advice_timeout")
            "dns" in raw || "resolve" in raw || "lookup" in raw ->
                strings.text("diag_events_advice_dns")
            "unreachable" in raw || "no route" in raw || "network is down" in raw ->
                strings.text("diag_events_advice_unreachable")
            "handshake" in raw || "decrypt" in raw || "auth" in raw ->
                strings.text("diag_events_advice_handshake")
            event.fatal -> strings.text("diag_events_advice_fatal")
            else -> strings.text("diag_events_advice_default")
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
