package app.timiao.et.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class JniEngineTest {

    private val instanceJson = """
        {
          "phone": {
            "my_node_info": {"virtual_ipv4": {"address": {"addr": 177246218}, "network_length": 24}},
            "peer_route_pairs": [
              {
                "route": {
                  "peer_id": 2,
                  "ipv4_addr": {"address": {"addr": 177246210}, "network_length": 24},
                  "cost": 1,
                  "hostname": "nas-home",
                  "version": "2.7.1"
                },
                "peer": {
                  "conns": [
                    {"tunnel": {"tunnel_type": "udp"}, "stats": {"tx_bytes": "1000", "rx_bytes": "2000", "latency_us": "8000"}}
                  ]
                }
              }
            ],
            "running": true
          }
        }
    """.trimIndent()

    private class RecordingBridge(
        var runResult: Int = 0,
        var json: String? = null,
        private val beforeRun: (() -> Unit)? = null,
    ) : NativeBridge {
        val calls = mutableListOf<String>()
        val observedToml = mutableListOf<String>()

        override fun parseConfig(config: String): Int {
            calls += "parseConfig"
            observedToml += config
            return 0
        }

        override fun runNetworkInstance(config: String): Int {
            calls += "runNetworkInstance"
            observedToml += config
            beforeRun?.invoke()
            return runResult
        }

        override fun setTunFd(instanceName: String, fd: Int): Int {
            calls += "setTunFd:$instanceName:$fd"
            return 0
        }

        override fun collectNetworkInfos(maxLength: Int): String? {
            calls += "collectNetworkInfos"
            return json
        }

        override fun deleteNetworkInstance(instanceName: String): Int {
            calls += "deleteNetworkInstance:$instanceName"
            return 0
        }

        override fun getLastError(): String? = "native failure"
    }

    private class RecordingTunProvider : TunProvider {
        var last: List<Any>? = null

        override suspend fun establish(
            addr: String,
            prefix: Int,
            routes: List<String>,
            mtu: Int,
        ): Int {
            last = listOf(addr, prefix, routes, mtu)
            return 7
        }
    }

    private class RecordingMultiTun : MultiTunProvider {
        val merged = mutableListOf<MergedTun>()
        val adopted = mutableListOf<Int>()
        val released = mutableListOf<Int>()
        var closeCalls = 0
        private var nextFd = 10

        override suspend fun establish(
            addr: String,
            prefix: Int,
            routes: List<String>,
            mtu: Int,
        ): Int = error("single establish must not be used")

        override suspend fun establishMerged(tun: MergedTun): Int {
            merged += tun
            return nextFd++
        }

        override fun adopt(fd: Int) {
            adopted += fd
        }

        override fun release(fd: Int) {
            released += fd
        }

        override fun closeTun() {
            closeCalls++
        }
    }

    @Test
    fun startsPollsAndStopsInstance() = runBlocking {
        val bridge = RecordingBridge(json = instanceJson)
        val tun = RecordingTunProvider()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = JniEngine(
            configs = FakeConfigRepository(),
            tunProvider = tun,
            bridge = bridge,
            scope = scope,
            pollIntervalMs = 10,
        )
        val config = sampleNetworkConfig().copy(
            instanceName = "phone",
            ipv4 = "10.144.144.10/24",
            routes = listOf("192.168.0.0/16"),
        )

        try {
            engine.start(config)

            assertTrue(bridge.calls.indexOf("parseConfig") < bridge.calls.indexOf("runNetworkInstance"))
            assertTrue(bridge.calls.contains("setTunFd:phone:7"))
            assertEquals(listOf("10.144.144.10", 24, listOf("192.168.0.0/16"), 1300), tun.last)

            val connected = withTimeout(2_000) {
                engine.status.first { it[config.id]?.state == LinkState.Connected }
            }
            val status = connected.getValue(config.id)
            assertEquals("10.144.144.10/24", status.myIpv4)
            assertEquals(1, status.peers.size)
            assertEquals("nas-home", status.peers.single().hostname)
            assertEquals("p2p", status.peers.single().cost)

            engine.stop(config.id)
            assertTrue(bridge.calls.contains("deleteNetworkInstance:phone"))
            // TUN must be released (fd=0) before the instance is deleted.
            assertTrue(bridge.calls.indexOf("setTunFd:phone:0") in 0 until bridge.calls.indexOf("deleteNetworkInstance:phone"))
            assertEquals(LinkState.Idle, engine.status.value.getValue(config.id).state)
        } finally {
            engine.close()
            scope.cancel()
        }
    }

    @Test
    fun sharesTunAcrossNetworksAndRebuildsOnStop() = runBlocking {
        val bridge = RecordingBridge()
        val tun = RecordingMultiTun()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = JniEngine(
            configs = FakeConfigRepository(),
            tunProvider = tun,
            bridge = bridge,
            scope = scope,
            pollIntervalMs = 10,
        )
        val a = sampleNetworkConfig().copy(
            instanceName = "a",
            dhcp = false,
            ipv4 = "10.0.0.1/24",
            routes = listOf("192.168.1.0/24"),
        )
        val b = sampleNetworkConfig().copy(
            instanceName = "b",
            dhcp = false,
            ipv4 = "10.1.0.1/24",
            routes = listOf("192.168.1.0/24", "172.16.0.0/16"),
        )

        try {
            engine.start(a)
            engine.start(b)

            val combined = tun.merged.last()
            assertEquals(2, combined.addresses.size)
            assertEquals(1, combined.routes.count { "${it.network}/${it.prefix}" == "192.168.1.0/24" })
            // The second network rebuilt the TUN and attached every running instance to the new fd.
            assertTrue(bridge.calls.contains("setTunFd:a:11"))
            assertTrue(bridge.calls.contains("setTunFd:b:11"))
            assertEquals(listOf(10, 11), tun.adopted)
            assertEquals(listOf(10), tun.released)

            engine.stop(a.id)

            val remaining = tun.merged.last()
            assertEquals(listOf(TunAddress("10.1.0.1", 24)), remaining.addresses)
            assertEquals(
                listOf("10.1.0.0/24", "192.168.1.0/24", "172.16.0.0/16"),
                remaining.routes.map { "${it.network}/${it.prefix}" },
            )
            assertTrue(bridge.calls.contains("setTunFd:b:12"))
            assertTrue(tun.released.contains(11))

            val beforeLastStop = tun.merged.size
            engine.stop(b.id)
            assertEquals(beforeLastStop, tun.merged.size) // no TUN left to build
            assertEquals(LinkState.Idle, engine.status.value.getValue(b.id).state)
        } finally {
            engine.close()
            scope.cancel()
        }
    }

    @Test
    fun rebuildsWhenReportedAddressChanges() = runBlocking {
        fun statusJson(address: Int) = """
            {
              "a": {
                "my_node_info": {"virtual_ipv4": {"address": {"addr": $address}, "network_length": 24}},
                "running": true
              }
            }
        """.trimIndent()
        val bridge = RecordingBridge(json = statusJson(167772161)) // 10.0.0.1
        val tun = RecordingMultiTun()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = JniEngine(
            configs = FakeConfigRepository(),
            tunProvider = tun,
            bridge = bridge,
            scope = scope,
            pollIntervalMs = 10,
        )

        try {
            engine.start(sampleNetworkConfig().copy(instanceName = "a", dhcp = true))
            bridge.json = statusJson(167772259) // 10.0.0.99
            withTimeout(2_000) {
                while (tun.merged.last().addresses.single().address != "10.0.0.99") delay(10)
            }
            assertTrue(tun.adopted.size >= 2)
            assertTrue(tun.released.contains(10))
        } finally {
            engine.close()
            scope.cancel()
        }
    }

    @Test
    fun dhcpIgnoresResidualStaticIpv4() = runBlocking {
        val json = """
            {
              "a": {
                "my_node_info": {"virtual_ipv4": {"address": {"addr": 167772259}, "network_length": 24}},
                "running": true
              }
            }
        """.trimIndent()
        val bridge = RecordingBridge(json = json)
        val tun = RecordingMultiTun()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = JniEngine(
            configs = FakeConfigRepository(),
            tunProvider = tun,
            bridge = bridge,
            scope = scope,
            pollIntervalMs = 10,
        )

        try {
            engine.start(
                sampleNetworkConfig().copy(
                    instanceName = "a",
                    dhcp = true,
                    ipv4 = "10.9.9.9/24",
                ),
            )
            assertEquals(listOf(TunAddress("10.0.0.99", 24)), tun.merged.single().addresses)
        } finally {
            engine.close()
            scope.cancel()
        }
    }

    @Test
    fun lateDhcpAddressCreatesTheTunLater() = runBlocking {
        val json = """
            {
              "a": {
                "my_node_info": {"virtual_ipv4": {"address": {"addr": 167772259}, "network_length": 24}},
                "running": true
              }
            }
        """.trimIndent()
        val bridge = RecordingBridge(json = null)
        val tun = RecordingMultiTun()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = JniEngine(
            configs = FakeConfigRepository(),
            tunProvider = tun,
            bridge = bridge,
            scope = scope,
            pollIntervalMs = 10,
            maxDhcpAttempts = 2,
        )

        try {
            engine.start(sampleNetworkConfig().copy(instanceName = "a", dhcp = true))
            assertTrue(tun.merged.isEmpty()) // no address yet, so no TUN is built

            bridge.json = json
            withTimeout(2_000) {
                while (tun.merged.lastOrNull()?.addresses?.singleOrNull()?.address != "10.0.0.99") {
                    delay(10)
                }
            }
        } finally {
            engine.close()
            scope.cancel()
        }
    }

    @Test
    fun dhcpFalseWithoutStaticAddressFailsClearly() = runBlocking {
        val engine = JniEngine(
            configs = FakeConfigRepository(),
            tunProvider = RecordingMultiTun(),
            bridge = RecordingBridge(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            pollIntervalMs = 10,
        )

        try {
            var message: String? = null
            try {
                engine.start(sampleNetworkConfig().copy(instanceName = "a", dhcp = false, ipv4 = null))
            } catch (e: IllegalStateException) {
                message = e.message
            }
            assertTrue(message!!.contains("ipv4 is not configured"))
            assertEquals(LinkState.Error, engine.status.value.getValue("a").state)
        } finally {
            engine.close()
        }
    }

    @Test
    fun noTunNetworkNeverBuildsOrAttachesTun() = runBlocking {
        val bridge = RecordingBridge()
        val tun = RecordingMultiTun()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = JniEngine(
            configs = FakeConfigRepository(),
            tunProvider = tun,
            bridge = bridge,
            scope = scope,
            pollIntervalMs = 10,
        )
        val config = sampleNetworkConfig().copy(
            instanceName = "a",
            dhcp = false,
            ipv4 = "10.0.0.1/24",
            flags = Flags(noTun = true),
        )

        try {
            engine.start(config)
            assertTrue(tun.merged.isEmpty())
            assertTrue(bridge.calls.none { it.startsWith("setTunFd") })
            assertEquals("10.0.0.1/24", engine.status.value.getValue("a").myIpv4)

            engine.stop(config.id)
            assertTrue(bridge.calls.none { it == "setTunFd:a:0" })
            assertTrue(bridge.calls.contains("deleteNetworkInstance:a"))
        } finally {
            engine.close()
            scope.cancel()
        }
    }

    @Test
    fun stoppingLastTunNetworkClosesTunWhileNoTunRemains() = runBlocking {
        val bridge = RecordingBridge()
        val tun = RecordingMultiTun()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = JniEngine(
            configs = FakeConfigRepository(),
            tunProvider = tun,
            bridge = bridge,
            scope = scope,
            pollIntervalMs = 10,
        )
        val t = sampleNetworkConfig().copy(
            instanceName = "t",
            dhcp = false,
            ipv4 = "10.0.0.1/24",
        )
        val keep = sampleNetworkConfig().copy(
            instanceName = "keep",
            dhcp = false,
            ipv4 = "10.1.0.1/24",
            flags = Flags(noTun = true),
        )

        try {
            engine.start(t)
            engine.start(keep)
            assertEquals(1, tun.merged.size) // no_tun did not rebuild
            engine.stop(t.id)
            assertEquals(1, tun.closeCalls)
            assertEquals(LinkState.Connecting, engine.status.value.getValue(keep.id).state)
        } finally {
            engine.close()
            scope.cancel()
        }
    }

    @Test
    fun peerProxyRoutesRebuildTheTun() = runBlocking {
        val json = """
            {
              "a": {
                "my_node_info": {"virtual_ipv4": {"address": {"addr": 167772161}, "network_length": 24}},
                "peer_route_pairs": [
                  {"route": {"proxy_cidrs": ["192.168.50.0/24"]}}
                ],
                "running": true
              }
            }
        """.trimIndent()
        val bridge = RecordingBridge(json = json)
        val tun = RecordingMultiTun()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = JniEngine(
            configs = FakeConfigRepository(),
            tunProvider = tun,
            bridge = bridge,
            scope = scope,
            pollIntervalMs = 10,
        )

        try {
            engine.start(
                sampleNetworkConfig().copy(instanceName = "a", dhcp = false, ipv4 = "10.0.0.1/24"),
            )
            withTimeout(2_000) {
                while (tun.merged.last().routes.none { "${it.network}/${it.prefix}" == "192.168.50.0/24" }) {
                    delay(10)
                }
            }
            // Stable routes must not rebuild on every poll.
            val builds = tun.merged.size
            delay(100)
            assertEquals(builds, tun.merged.size)
        } finally {
            engine.close()
            scope.cancel()
        }
    }

    @Test
    fun rebuildTunReattachesRunningNetworks() = runBlocking {
        val bridge = RecordingBridge()
        val tun = RecordingMultiTun()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = JniEngine(
            configs = FakeConfigRepository(),
            tunProvider = tun,
            bridge = bridge,
            scope = scope,
            pollIntervalMs = 10,
        )
        val config = sampleNetworkConfig().copy(
            instanceName = "a",
            dhcp = false,
            ipv4 = "10.0.0.1/24",
        )

        try {
            engine.start(config)
            assertEquals(1, tun.merged.size)
            assertEquals(listOf(10), tun.adopted)

            // A split-policy change asks the engine to rebuild the TUN in place.
            engine.rebuildTun()

            assertEquals(2, tun.merged.size)
            assertEquals(listOf(10, 11), tun.adopted)
            assertTrue(tun.released.contains(10))
            assertTrue(bridge.calls.contains("setTunFd:a:11"))
        } finally {
            engine.close()
            scope.cancel()
        }
    }

    @Test
    fun rebuildTunWithoutNetworksIsANoOp() = runBlocking {
        val tun = RecordingMultiTun()
        val engine = JniEngine(
            configs = FakeConfigRepository(),
            tunProvider = tun,
            bridge = RecordingBridge(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            pollIntervalMs = 10,
        )

        try {
            engine.rebuildTun()
            assertTrue(tun.merged.isEmpty())
        } finally {
            engine.close()
        }
    }

    @Test
    fun concurrentStartRunsOneNativeInstance() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bridge = RecordingBridge(beforeRun = {
            entered.countDown()
            release.await()
        })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = JniEngine(
            configs = FakeConfigRepository(),
            tunProvider = RecordingMultiTun(),
            bridge = bridge,
            scope = scope,
            pollIntervalMs = 10,
        )
        val config = sampleNetworkConfig().copy(
            instanceName = "a",
            dhcp = false,
            ipv4 = "10.0.0.1/24",
        )

        try {
            val first = launch(Dispatchers.Default) { engine.start(config) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            // The second start returns at once instead of running a second native sequence.
            engine.start(config)
            release.countDown()
            first.join()

            assertEquals(1, bridge.calls.count { it == "runNetworkInstance" })
        } finally {
            engine.close()
            scope.cancel()
        }
    }

    @Test
    fun rejectsSecondNetworkWithOverlappingStaticSubnet() = runBlocking {
        val bridge = RecordingBridge()
        val tun = RecordingMultiTun()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = JniEngine(
            configs = FakeConfigRepository(),
            tunProvider = tun,
            bridge = bridge,
            scope = scope,
            pollIntervalMs = 10,
        )
        val a = sampleNetworkConfig().copy(
            instanceName = "a",
            dhcp = false,
            ipv4 = "10.144.144.1/24",
        )
        val b = sampleNetworkConfig().copy(
            instanceName = "b",
            networkName = "second",
            dhcp = false,
            ipv4 = "10.144.144.9/24",
        )

        try {
            engine.start(a)
            var message: String? = null
            try {
                engine.start(b)
            } catch (e: IllegalStateException) {
                message = e.message
            }

            assertTrue(message!!.contains("与正在运行的「${a.networkName}」网段重叠（10.144.144.0/24）"))
            assertTrue(message.contains("请先断开它或修改网段"))
            assertEquals(LinkState.Error, engine.status.value.getValue("b").state)
            assertEquals(message, engine.status.value.getValue("b").error)
            // The kernel instance for b never ran and never attached to the shared TUN.
            assertEquals(1, bridge.calls.count { it == "runNetworkInstance" })
            assertTrue(bridge.calls.none { it.startsWith("setTunFd:b") })
        } finally {
            engine.close()
            scope.cancel()
        }
    }

    @Test
    fun rejectsDhcpNetworkWhenAssignedAddressOverlapsAtStartup() = runBlocking {
        val bJson = """
            {
              "a": {
                "my_node_info": {"virtual_ipv4": {"address": {"addr": 177246209}, "network_length": 24}},
                "running": true
              },
              "b": {
                "my_node_info": {"virtual_ipv4": {"address": {"addr": 177246217}, "network_length": 24}},
                "running": true
              }
            }
        """.trimIndent()
        val bridge = RecordingBridge(json = bJson)
        val tun = RecordingMultiTun()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = JniEngine(
            configs = FakeConfigRepository(),
            tunProvider = tun,
            bridge = bridge,
            scope = scope,
            pollIntervalMs = 10,
            maxDhcpAttempts = 2,
        )
        val a = sampleNetworkConfig().copy(
            instanceName = "a",
            dhcp = false,
            ipv4 = "10.144.144.1/24",
        )
        val b = sampleNetworkConfig().copy(
            instanceName = "b",
            networkName = "dhcp-b",
            dhcp = true,
            ipv4 = null,
        )

        try {
            engine.start(a)
            var message: String? = null
            try {
                engine.start(b)
            } catch (e: IllegalStateException) {
                message = e.message
            }

            assertTrue(message!!.contains("与正在运行的「${a.networkName}」网段重叠（10.144.144.0/24）"))
            assertEquals(LinkState.Error, engine.status.value.getValue("b").state)
            assertTrue(bridge.calls.contains("deleteNetworkInstance:b"))
            assertTrue(bridge.calls.none { it.startsWith("setTunFd:b") })
        } finally {
            engine.close()
            scope.cancel()
        }
    }

    @Test
    fun lateDhcpOverlapStopsTheLaterNetworkAndReportsIt() = runBlocking {
        val bJson = """
            {
              "a": {
                "my_node_info": {"virtual_ipv4": {"address": {"addr": 177246209}, "network_length": 24}},
                "running": true
              },
              "b": {
                "my_node_info": {"virtual_ipv4": {"address": {"addr": 177246217}, "network_length": 24}},
                "running": true
              }
            }
        """.trimIndent()
        val bridge = RecordingBridge(json = null)
        val tun = RecordingMultiTun()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = JniEngine(
            configs = FakeConfigRepository(),
            tunProvider = tun,
            bridge = bridge,
            scope = scope,
            pollIntervalMs = 10,
            maxDhcpAttempts = 1,
        )
        val a = sampleNetworkConfig().copy(
            instanceName = "a",
            dhcp = false,
            ipv4 = "10.144.144.1/24",
        )
        val b = sampleNetworkConfig().copy(
            instanceName = "b",
            networkName = "dhcp-b",
            dhcp = true,
            ipv4 = null,
        )

        try {
            engine.start(a)
            engine.start(b) // no DHCP address yet, so no TUN spec
            bridge.json = bJson

            val failed = withTimeout(3_000) {
                engine.status.first { it["b"]?.state == LinkState.Error }
            }
            val status = failed.getValue("b")
            assertTrue(status.error!!.contains("与正在运行的「${a.networkName}」网段重叠（10.144.144.0/24）"))
            assertTrue(bridge.calls.contains("deleteNetworkInstance:b"))
            // The first network keeps running.
            assertTrue(engine.status.value.getValue("a").state != LinkState.Error)
            assertTrue(tun.merged.last().addresses.any { it.address == "10.144.144.1" })
        } finally {
            engine.close()
            scope.cancel()
        }
    }

    @Test
    fun reportsNativeFailure() = runBlocking {
        val bridge = RecordingBridge(runResult = -1)
        val engine = JniEngine(
            configs = FakeConfigRepository(),
            tunProvider = RecordingTunProvider(),
            bridge = bridge,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            pollIntervalMs = 10,
        )

        try {
            var failed = false
            try {
                engine.start(sampleNetworkConfig().copy(instanceName = "phone", ipv4 = "10.144.144.10/24"))
            } catch (e: IllegalStateException) {
                failed = true
                assertTrue(e.message!!.contains("runNetworkInstance failed"))
                assertTrue(e.message!!.contains("native failure"))
            }
            assertTrue(failed)
            assertEquals(
                LinkState.Error,
                engine.status.value.getValue("phone").state,
            )
        } finally {
            engine.close()
        }
    }
}
