package app.timiao.et.core.diag

import app.timiao.et.core.Flags
import app.timiao.et.core.LinkState
import app.timiao.et.core.NetworkConfig
import app.timiao.et.core.NetworkStatus
import app.timiao.et.core.PeerConfig
import app.timiao.et.core.PeerInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsEngineTest {
    private val secret = "s3cret-value-123"
    private val baseConfig = NetworkConfig(
        instanceName = "net",
        networkName = "Home-Lab",
        networkSecret = secret,
        dhcp = true,
        peers = listOf(PeerConfig("tcp://1.2.3.4:11010")),
    )
    private val goodDevice = FakeDevice()
    private val reachable = PeerReachabilityProbe { PeerProbeResult.Reachable(12) }

    private fun engine(probe: PeerReachabilityProbe = reachable) =
        DiagnosticsEngine(probe = probe, clock = { 1_000L })

    private fun connected(vararg peers: PeerInfo) = NetworkStatus(
        state = LinkState.Connected,
        myIpv4 = "10.144.144.10/24",
        peers = peers.toList(),
        txBps = 0,
        rxBps = 0,
        uptimeSec = 60,
        error = null,
    )

    private fun report(
        config: NetworkConfig? = baseConfig,
        status: NetworkStatus? = connected(peer()),
        raw: String? = rawInfos(emptyList()),
        device: DeviceProbe? = goodDevice,
        probe: PeerReachabilityProbe = reachable,
    ): DiagnosticReport = runBlocking {
        engine(probe).run(DiagnosticInput(config, status, raw), device)
    }

    @Test
    fun `healthy setup passes every check`() {
        val result = report()
        assertEquals(DiagSeverity.Pass, result.overall)
        assertFalse(result.items.any { it.severity != DiagSeverity.Pass })
    }

    @Test
    fun `empty server list is a failure with concrete advice`() {
        val result = report(config = baseConfig.copy(peers = emptyList()))
        val item = result.item("config_servers")
        assertEquals(DiagSeverity.Fail, item.severity)
        assertTrue(item.summary.contains("没有服务器地址"))
        assertTrue(item.advice!!.contains("tcp://"))
    }

    @Test
    fun `malformed peer uri is reported`() {
        val result = report(config = baseConfig.copy(peers = listOf(PeerConfig("not a url"))))
        val item = result.item("config_servers")
        assertEquals(DiagSeverity.Fail, item.severity)
        assertTrue(item.details.any { it.contains("缺少协议") })
    }

    @Test
    fun `blank secret warns but blank network name fails`() {
        val blankSecret = report(config = baseConfig.copy(networkSecret = ""))
        assertEquals(DiagSeverity.Warn, blankSecret.item("config_identity").severity)
        assertTrue(blankSecret.item("config_identity").advice!!.contains("密钥"))

        val blankName = report(config = baseConfig.copy(networkName = ""))
        assertEquals(DiagSeverity.Fail, blankName.item("config_identity").severity)
        assertTrue(blankName.item("config_identity").advice!!.contains("网络名"))
    }

    @Test
    fun `dhcp plus static ip warns about the conflict`() {
        val result = report(config = baseConfig.copy(dhcp = true, ipv4 = "10.144.144.10/24"))
        val item = result.item("config_addressing")
        assertEquals(DiagSeverity.Warn, item.severity)
        assertTrue(item.advice!!.contains("DHCP"))
    }

    @Test
    fun `no dhcp and no static ip fails`() {
        val result = report(
            config = baseConfig.copy(dhcp = false, ipv4 = null, dhcpIpv4Cidr = null),
        )
        val item = result.item("config_addressing")
        assertEquals(DiagSeverity.Fail, item.severity)
        assertTrue(item.advice!!.contains("静态"))
    }

    @Test
    fun `p2p only and disable p2p conflict fails`() {
        val result = report(
            config = baseConfig.copy(flags = Flags(p2pOnly = true, disableP2p = true)),
        )
        val item = result.item("config_p2p")
        assertEquals(DiagSeverity.Fail, item.severity)
        assertTrue(item.advice!!.contains("中继"))
    }

    @Test
    fun `unreachable servers fail with firewall advice`() {
        val probe = PeerReachabilityProbe {
            PeerProbeResult.Unreachable("连接超时（3 秒），服务器不可达或被防火墙丢弃")
        }
        val result = report(probe = probe)
        val item = result.item("reachability")
        assertEquals(DiagSeverity.Fail, item.severity)
        assertTrue(item.advice!!.contains("防火墙"))
        assertTrue(item.details.first().contains("超时"))
    }

    @Test
    fun `connected with zero peers suggests name and secret`() {
        val result = report(status = connected())
        val item = result.item("status_peers")
        assertEquals(DiagSeverity.Warn, item.severity)
        assertTrue(item.advice!!.contains("网络名"))
        assertTrue(item.advice.contains("密钥"))
    }

    @Test
    fun `all relay with symmetric nat suggests kcp quic and disable p2p`() {
        val peers = arrayOf(
            peer("a", "relay(2)"),
            peer("b", "relay(1)"),
        )
        val result = report(
            config = baseConfig.copy(flags = Flags(disableP2p = true)),
            status = connected(*peers),
            raw = rawInfos(emptyList(), nat = "Symmetric"),
        )
        val item = result.item("status_path")
        assertEquals(DiagSeverity.Warn, item.severity)
        assertTrue(item.summary.contains("全部走中继"))
        assertTrue(item.advice!!.contains("KCP"))
        assertTrue(item.advice.contains("QUIC"))
        assertTrue(item.advice.contains("禁用 P2P"))
        assertTrue(item.details.any { it.contains("Symmetric") })
    }

    @Test
    fun `high latency warns`() {
        val result = report(status = connected(peer(latency = 420.0)))
        val item = result.item("status_quality")
        assertEquals(DiagSeverity.Warn, item.severity)
        assertTrue(item.summary.contains("延迟偏高"))
        assertTrue(item.advice!!.contains("延迟"))
    }

    @Test
    fun `high loss warns`() {
        val result = report(status = connected(peer(loss = 0.2)))
        val item = result.item("status_quality")
        assertEquals(DiagSeverity.Warn, item.severity)
        assertTrue(item.summary.contains("丢包偏高"))
        assertTrue(item.advice!!.contains("丢包"))
    }

    @Test
    fun `device gaps produce vpn failure and warnings`() {
        val device = FakeDevice(vpn = false, battery = false, notifications = false)
        val result = report(device = device)
        assertEquals(DiagSeverity.Fail, result.item("device_vpn").severity)
        assertTrue(result.item("device_vpn").advice!!.contains("VPN"))
        assertEquals(DiagSeverity.Warn, result.item("device_battery").severity)
        assertTrue(result.item("device_battery").advice!!.contains("电池"))
        assertEquals(DiagSeverity.Warn, result.item("device_notifications").severity)
    }

    @Test
    fun `connect error event is translated and gets port advice`() {
        val raw = rawInfos(
            listOf(
                """{"time":"2026-11-15T12:00:01.123+08:00","event":{"ConnectError":["tcp://1.2.3.4:11010","V4","Connection refused"]}}""",
            ),
        )
        val result = report(status = connected(), raw = raw)
        val item = result.item("events")
        assertEquals(DiagSeverity.Warn, item.severity)
        assertTrue(item.details.first().contains("连接被拒绝"))
        assertTrue(item.advice!!.contains("端口"))
    }

    @Test
    fun `dhcp conflict event is fatal`() {
        val raw = rawInfos(
            listOf("""{"event":{"DhcpIpv4Conflicted":[{"address":{"addr":176061956},"network_length":24}]}}"""),
        )
        val result = report(raw = raw)
        val item = result.item("events")
        assertEquals(DiagSeverity.Fail, item.severity)
        assertTrue(item.summary.contains("冲突"))
    }

    @Test
    fun `report never contains the secret`() {
        val status = connected().copy(
            state = LinkState.Error,
            error = "auth failed with $secret",
        )
        val result = report(status = status)
        val text = result.plainText()
        assertFalse(text.contains(secret))
        assertTrue(text.contains("***"))
        assertTrue(text.contains("本报告不包含网络密钥"))
    }

    @Test
    fun `overall is worst severity`() {
        val failing = report(config = baseConfig.copy(networkSecret = ""), status = connected())
        assertEquals(DiagSeverity.Warn, failing.overall)
        val fail = report(
            config = baseConfig.copy(flags = Flags(p2pOnly = true, disableP2p = true)),
        )
        assertEquals(DiagSeverity.Fail, fail.overall)
    }

    private fun DiagnosticReport.item(id: String): DiagItem =
        items.firstOrNull { it.id == id } ?: error("missing item $id in ${items.map { it.id }}")

    private fun peer(
        hostname: String = "node",
        cost: String = "p2p",
        latency: Double = 10.0,
        loss: Double = 0.0,
    ) = PeerInfo(
        hostname = hostname,
        ipv4 = "10.144.144.2/24",
        cost = cost,
        tunnel = "udp",
        latencyMs = latency,
        lossRate = loss,
        natType = "PortRestricted",
        txBytes = 0,
        rxBytes = 0,
        version = "2.7.1",
    )

    private class FakeDevice(
        private val vpn: Boolean = true,
        private val battery: Boolean = true,
        private val notifications: Boolean = true,
    ) : DeviceProbe {
        override fun hasVpnPermission(): Boolean = vpn
        override fun isIgnoringBatteryOptimizations(): Boolean = battery
        override fun hasNotificationPermission(): Boolean = notifications
    }

    private fun rawInfos(events: List<String>, nat: String? = "PortRestricted"): String {
        val eventsJson = events.joinToString(",") { jsonString(it) }
        return buildString {
            append("""{"map":{"net":{"my_node_info":{""")
            if (nat != null) append(""""stun_info":{"udp_nat_type":"$nat"},""")
            append(""""hostname":"Timiao-Test"},"events":[$eventsJson]}}}""")
        }
    }

    private fun jsonString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
