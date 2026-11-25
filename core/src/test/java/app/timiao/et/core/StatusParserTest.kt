package app.timiao.et.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class StatusParserTest {

    private val json = """
        {
          "home": {
            "dev_name": "tun0",
            "my_node_info": {
              "virtual_ipv4": {"address": {"addr": 177246218}, "network_length": 24},
              "hostname": "phone",
              "version": "2.7.1"
            },
            "routes": [
              {
                "peer_id": 2,
                "ipv4_addr": {"address": {"addr": 177246210}, "network_length": 24},
                "next_hop_peer_id": 2,
                "cost": 1,
                "hostname": "nas-home",
                "version": "2.7.1",
                "stun_info": {"udp_nat_type": "FullCone"}
              }
            ],
            "peers": [],
            "peer_route_pairs": [
              {
                "route": {
                  "peer_id": 2,
                  "ipv4_addr": {"address": {"addr": 177246210}, "network_length": 24},
                  "next_hop_peer_id": 2,
                  "cost": 1,
                  "hostname": "nas-home",
                  "version": "2.7.1",
                  "stun_info": {"udp_nat_type": "FullCone"}
                },
                "peer": {
                  "peer_id": 2,
                  "conns": [
                    {
                      "conn_id": "a",
                      "tunnel": {"tunnel_type": "udp"},
                      "stats": {"rx_bytes": "2048", "tx_bytes": "1024", "latency_us": "8000"},
                      "loss_rate": 0.01
                    }
                  ]
                }
              },
              {
                "route": {
                  "peer_id": 3,
                  "ipv4_addr": {"address": {"addr": 177246211}, "network_length": 24},
                  "next_hop_peer_id": 9,
                  "cost": 2,
                  "hostname": "relay-hk",
                  "version": "2.7.0",
                  "stun_info": {"udp_nat_type": "Symmetric"}
                },
                "peer": {
                  "peer_id": 3,
                  "conns": [
                    {
                      "conn_id": "b",
                      "tunnel": {"tunnel_type": "tcp"},
                      "stats": {"rx_bytes": "4096", "tx_bytes": "8192", "latency_us": "62000"},
                      "loss_rate": 0.5
                    }
                  ]
                }
              }
            ],
            "running": true
          }
        }
    """.trimIndent()

    @Test
    fun parsesRunningInfo() {
        val status = StatusParser.parse(json, "home", uptimeSec = 12)

        assertNotNull(status)
        assertEquals(LinkState.Connected, status!!.state)
        assertEquals("10.144.144.10/24", status.myIpv4)
        assertEquals(12L, status.uptimeSec)
        assertEquals(2, status.peers.size)

        val nas = status.peers[0]
        assertEquals("nas-home", nas.hostname)
        assertEquals("10.144.144.2/24", nas.ipv4)
        assertEquals("p2p", nas.cost)
        assertEquals("udp", nas.tunnel)
        assertEquals(8.0, nas.latencyMs!!, 0.001)
        assertEquals(0.01, nas.lossRate!!, 0.0001)
        assertEquals("FullCone", nas.natType)
        assertEquals(1024L, nas.txBytes)
        assertEquals(2048L, nas.rxBytes)
        assertEquals("2.7.1", nas.version)

        val relay = status.peers[1]
        assertEquals("relay(2)", relay.cost)
        assertEquals(62.0, relay.latencyMs!!, 0.001)
    }

    @Test
    fun computesRatesFromByteDeltas() {
        val first = StatusParser.parse(json, "home", 0)!!
        val withBase = StatusParser.applyRates(first, null, 1000)
        assertEquals(0L, withBase.txBps)
        assertEquals(0L, withBase.rxBps)

        val next = first.copy(
            peers = first.peers.mapIndexed { index, peer ->
                if (index == 0) peer.copy(txBytes = peer.txBytes + 1000, rxBytes = peer.rxBytes + 500)
                else peer
            },
        )
        val rated = StatusParser.applyRates(next, first, 1000)

        assertEquals(1000L, rated.txBps)
        assertEquals(500L, rated.rxBps)
    }

    @Test
    fun reportsErrorWhenInstanceIsNotRunning() {
        val stopped = """
            {"home": {"running": false, "error_msg": "tun closed"}}
        """.trimIndent()

        val status = StatusParser.parse(stopped, "home", 3)

        assertEquals(LinkState.Error, status!!.state)
        assertEquals("tun closed", status.error)
    }

    @Test
    fun returnsNullForInvalidJson() {
        assertEquals(null, StatusParser.parse("not json", "home", 0))
    }
}
