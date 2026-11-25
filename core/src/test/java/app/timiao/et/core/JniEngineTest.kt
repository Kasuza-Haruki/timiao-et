package app.timiao.et.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
            assertEquals(listOf("10.144.144.10", 24, listOf("192.168.0.0/16"), 1380), tun.last)

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
            assertEquals(LinkState.Idle, engine.status.value.getValue(config.id).state)
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
