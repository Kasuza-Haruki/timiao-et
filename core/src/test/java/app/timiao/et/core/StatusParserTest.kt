package app.timiao.et.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusParserTest {

    private val json = """
        {
          "home": {
            "dev_name": "tun0",
            "my_node_info": {
              "virtual_ipv4": {"address": {"addr": 177246218}, "network_length": 24},
              "virtual_ipv6": {"address": {"part1": 536939960, "part2": 0, "part3": 0, "part4": 1}, "network_length": 64},
              "hostname": "phone",
              "version": "2.7.1",
              "peer_id": 42,
              "ips": {
                "public_ipv4": {"addr": 3405803777},
                "interface_ipv4s": [{"addr": 167772687}],
                "interface_ipv6s": [{"part4": 1}]
              },
              "stun_info": {"udp_nat_type": "FullCone", "tcp_nat_type": "NoPAT", "min_port": 10000, "max_port": 20000},
              "listeners": [{"url": "udp://0.0.0.0:11010"}]
            },
            "routes": [
              {
                "peer_id": 2,
                "ipv4_addr": {"address": {"addr": 177246210}, "network_length": 24},
                "next_hop_peer_id": 2,
                "cost": 1,
                "path_latency": 99,
                "hostname": "nas-home",
                "version": "2.7.1",
                "stun_info": {"udp_nat_type": "FullCone", "tcp_nat_type": "Restricted"}
              },
              {
                "peer_id": 3,
                "ipv4_addr": {"address": {"addr": 177246211}, "network_length": 24},
                "next_hop_peer_id": 2,
                "cost": 2,
                "path_latency": 62,
                "hostname": "relay-hk",
                "version": "2.7.0"
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
                  "path_latency": 99,
                  "hostname": "nas-home",
                  "version": "2.7.1",
                  "feature_flag": {"is_public_server": true, "avoid_relay_data": true},
                  "stun_info": {"udp_nat_type": "FullCone", "tcp_nat_type": "Restricted"}
                },
                "peer": {
                  "peer_id": 2,
                  "conns": [
                    {
                      "conn_id": "e7508c58-c194-416d-937f-23f7871c0df2",
                      "tunnel": {"tunnel_type": "tcp", "local_addr": {"url": "tcp://10.0.2.15:33236"}, "remote_addr": {"url": "tcp://203.0.113.9:11010"}},
                      "stats": {"rx_bytes": "2048", "tx_bytes": "1024", "latency_us": "8000"},
                      "loss_rate": 0.01
                    },
                    {
                      "conn_id": "cb506d1c-ff71-476e-8e29-15de9b092224",
                      "tunnel": {"tunnel_type": "udp", "local_addr": {"url": "udp://[::]:46455"}, "remote_addr": {"url": "udp://203.0.113.9:11010"}},
                      "stats": {"rx_bytes": "4096", "tx_bytes": "8192", "latency_us": "50000"},
                      "loss_rate": 0.3
                    }
                  ],
                  "default_conn_id": {
                    "part1": 3411045660,
                    "part2": 4285613934,
                    "part3": 2385057246,
                    "part4": 2601067044
                  }
                }
              },
              {
                "route": {
                  "peer_id": 3,
                  "ipv4_addr": {"address": {"addr": 177246211}, "network_length": 24},
                  "next_hop_peer_id": 2,
                  "cost": 2,
                  "path_latency": 62,
                  "hostname": "relay-hk",
                  "version": "2.7.0"
                },
                "peer": {"peer_id": 3, "conns": []}
              }
            ],
            "events": [
              "{\"time\":\"2026-11-25T23:51:28+08:00\",\"event\":{\"PeerConnAdded\":{\"peer_id\":2,\"tunnel\":{\"tunnel_type\":\"udp\",\"remote_addr\":{\"url\":\"udp://203.0.113.9:11010\"}}}}}",
              "{\"time\":\"2026-11-25T23:51:27+08:00\",\"event\":{\"ConnectError\":[\"udp://203.0.113.9:11010\",\"4\",\"timeout\"]}}",
              "{\"time\":\"2026-11-25T23:51:26+08:00\",\"event\":{\"DhcpIpv4Changed\":[null,\"10.144.144.10/24\"]}}"
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
        assertEquals("2", nas.peerId)
        assertEquals("2", nas.nextHopPeerId)
        assertEquals(2, nas.conns.size)
        assertTrue("default connection is ordered first", nas.conns.first().isDefault)
        // Official rule: default connection latency wins over the smaller non-default one.
        assertEquals(50.0, nas.latencyMs!!, 0.001)
        // Official rule: loss comes from the default-first connection, not the smallest.
        assertEquals(0.3, nas.lossRate!!, 0.0001)
        assertEquals("tcp,udp6", nas.tunnel)
        assertEquals("FullCone", nas.natType)
        assertEquals("Restricted", nas.tcpNatType)
        assertEquals(9216L, nas.txBytes)
        assertEquals(6144L, nas.rxBytes)
        assertEquals("2.7.1", nas.version)
        assertTrue(nas.isPublicServer)
        assertTrue(nas.avoidRelayData)

        val relay = status.peers[1]
        assertEquals("relay(2)", relay.cost)
        assertEquals(62.0, relay.latencyMs!!, 0.001)
        assertEquals("2", relay.nextHopPeerId)
        assertNull(relay.lossRate)
        assertEquals(emptyList<PeerConn>(), relay.conns)
    }

    @Test
    fun parsesMyNodeInfo() {
        val node = StatusParser.parse(json, "home", 0)!!.node!!

        assertEquals("42", node.peerId)
        assertEquals("phone", node.hostname)
        assertEquals("2.7.1", node.version)
        assertEquals("10.144.144.10/24", node.virtualIpv4)
        assertEquals("2001:db8::1/64", node.virtualIpv6)
        assertEquals(listOf("10.0.2.15"), node.localIpv4s)
        assertEquals(listOf("::1"), node.localIpv6s)
        assertEquals("203.0.113.1", node.publicIpv4)
        assertEquals(listOf("udp://0.0.0.0:11010"), node.listeners)
        assertEquals("FullCone", node.udpNatType)
        assertEquals("NoPAT", node.tcpNatType)
        assertEquals(10000, node.stunMinPort)
        assertEquals(20000, node.stunMaxPort)
    }

    @Test
    fun parsesAndClassifiesEvents() {
        val events = StatusParser.parse(json, "home", 0)!!.events

        assertEquals(3, events.size)
        assertEquals("PeerConnAdded", events[0].type)
        assertEquals(NetworkEventKind.Connect, events[0].kind)
        assertTrue(events[0].detail.contains("对端 2"))
        assertNotNull(events[0].timeMs)

        assertEquals("ConnectError", events[1].type)
        assertEquals(NetworkEventKind.Error, events[1].kind)
        assertTrue(events[1].detail.contains("timeout"))

        assertEquals("DhcpIpv4Changed", events[2].type)
        assertEquals(NetworkEventKind.Connect, events[2].kind)
        assertEquals("无 → 10.144.144.10/24", events[2].detail)
    }

    @Test
    fun mapsEveryNatTypeName() {
        val expected = mapOf(
            0 to "Unknown",
            1 to "OpenInternet",
            2 to "NoPAT",
            3 to "FullCone",
            4 to "Restricted",
            5 to "PortRestricted",
            6 to "Symmetric",
            7 to "SymUdpFirewall",
            8 to "SymmetricEasyInc",
            9 to "SymmetricEasyDec",
        )
        for ((value, name) in expected) {
            val text = """
                {"home":{"running":true,"peer_route_pairs":[
                  {"route":{"peer_id":1,"cost":1,"stun_info":{"udp_nat_type":$value}},"peer":{"conns":[]}}
                ]}}
            """.trimIndent()
            val peer = StatusParser.parse(text, "home", 0)!!.peers.single()
            assertEquals("enum $value", name, peer.natType)
        }
    }

    @Test
    fun acceptsNamedNatTypeAndMissingStunInfo() {
        val named = """
            {"home":{"running":true,"peer_route_pairs":[
              {"route":{"peer_id":1,"cost":1,"stun_info":{"udp_nat_type":"PortRestricted"}},"peer":{"conns":[]}},
              {"route":{"peer_id":2,"cost":1},"peer":{"conns":[]}}
            ]}}
        """.trimIndent()
        val peers = StatusParser.parse(named, "home", 0)!!.peers
        assertEquals("PortRestricted", peers[0].natType)
        assertNull(peers[1].natType)
    }

    @Test
    fun fallsBackToPathLatencyWhenNoConnectionStats() {
        val text = """
            {"home":{"running":true,"peer_route_pairs":[
              {"route":{"peer_id":1,"cost":1,"path_latency":131},"peer":{"conns":[]}}
            ]}}
        """.trimIndent()
        assertEquals(131.0, StatusParser.parse(text, "home", 0)!!.peers.single().latencyMs!!, 0.001)
    }

    @Test
    fun computesSmoothedRatesFromByteDeltas() {
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
        // alpha = 0.4, previous smoothed rate = 0
        assertEquals(400L, rated.txBps)
        assertEquals(200L, rated.rxBps)

        val next2 = next.copy(
            peers = next.peers.mapIndexed { index, peer ->
                if (index == 0) peer.copy(txBytes = peer.txBytes + 1000, rxBytes = peer.rxBytes + 500)
                else peer
            },
        )
        val rated2 = StatusParser.applyRates(next2, rated, 1000)
        // 0.4 * 1000 + 0.6 * 400 = 640
        assertEquals(640L, rated2.txBps)
        assertEquals(320L, rated2.rxBps)
    }

    @Test
    fun reportsErrorWhenInstanceIsNotRunning() {
        val stopped = """
            {"home": {"running": false, "error_msg": "tun closed"}}
        """.trimIndent()

        val status = StatusParser.parse(stopped, "home", 3)

        assertEquals(LinkState.Error, status!!.state)
        assertEquals("tun closed", status.error)
        assertTrue(status.events.isEmpty())
    }

    @Test
    fun returnsNullForInvalidJson() {
        assertEquals(null, StatusParser.parse("not json", "home", 0))
    }
}

class StatusParserRealPayloadTest {
    private val text = javaClass.getResource("/sample_infos.json")!!.readText()

    @org.junit.Test
    fun parsesMapWrappedPayloadFromRealKernel() {
        val ip = StatusParser.primaryIpv4(text, "Home-Lab")
        org.junit.Assert.assertEquals("10.126.126.4/24", ip)
        val st = StatusParser.parse(text, "Home-Lab", 5)!!
        org.junit.Assert.assertEquals(LinkState.Connected, st.state)
        org.junit.Assert.assertTrue(st.peers.isNotEmpty())
        val touma = st.peers.first { it.hostname == "relay-1" }
        org.junit.Assert.assertEquals("p2p", touma.cost)
        org.junit.Assert.assertEquals("PortRestricted", touma.natType)
        org.junit.Assert.assertEquals("NoPAT", touma.tcpNatType)
        org.junit.Assert.assertEquals(29.0, touma.latencyMs!!, 0.001)
        org.junit.Assert.assertEquals(0.0, touma.lossRate!!, 0.0001)
        org.junit.Assert.assertEquals("udp,tcp", touma.tunnel)
        org.junit.Assert.assertEquals(2, touma.conns.size)
        org.junit.Assert.assertTrue(touma.conns.first().isDefault)
        org.junit.Assert.assertEquals(touma.peerId, touma.nextHopPeerId)

        val desktop = st.peers.first { it.hostname == "desktop-a" }
        // Connection stats beat route path_latency (500 ms).
        org.junit.Assert.assertEquals(2.0, desktop.latencyMs!!, 0.001)

        val phone = st.peers.first { it.hostname == "Phone" }
        org.junit.Assert.assertEquals("relay(2)", phone.cost)
        // No peer entry: falls back to route path_latency.
        org.junit.Assert.assertEquals(131.0, phone.latencyMs!!, 0.001)
        org.junit.Assert.assertTrue(phone.conns.isEmpty())
        org.junit.Assert.assertEquals(touma.peerId, phone.nextHopPeerId)

        val node = st.node!!
        org.junit.Assert.assertEquals("471554288", node.peerId)
        org.junit.Assert.assertEquals("emu-a", node.hostname)
        org.junit.Assert.assertEquals("10.126.126.4/24", node.virtualIpv4)
        org.junit.Assert.assertEquals("Symmetric", node.udpNatType)
        org.junit.Assert.assertEquals("Symmetric", node.tcpNatType)
        org.junit.Assert.assertEquals(12228, node.stunMinPort)
        org.junit.Assert.assertEquals(47163, node.stunMaxPort)
        org.junit.Assert.assertEquals(listOf("ring://2ba27110-f35f-493e-bd0b-28f7504cbb63"), node.listeners)
        org.junit.Assert.assertTrue(node.localIpv4s.isNotEmpty())
        org.junit.Assert.assertTrue(node.localIpv6s.isNotEmpty())
        org.junit.Assert.assertNotNull(node.publicIpv4)
        org.junit.Assert.assertTrue(st.events.isEmpty())
    }

    @org.junit.Test
    fun parsesSanitizedEventJournal() {
        val eventsText = javaClass.getResource("/sample_infos_events.json")!!.readText()
        assertFalse(eventsText.contains("198.51.100.7"))
        assertFalse(eventsText.contains("203.0.113.9"))
        assertFalse(eventsText.contains("relay-1"))

        val st = StatusParser.parse(eventsText, "Home-Lab", 5)!!
        org.junit.Assert.assertTrue(st.events.isNotEmpty())
        org.junit.Assert.assertTrue(st.events.all { it.timeMs != null })
        // Newest first.
        org.junit.Assert.assertEquals("PeerConnAdded", st.events.first().type)
        org.junit.Assert.assertEquals(NetworkEventKind.Connect, st.events.first().kind)
        org.junit.Assert.assertTrue(st.events.first().detail.contains("对端 2651160302"))
        org.junit.Assert.assertTrue(st.events.any { it.type == "DhcpIpv4Changed" })
        org.junit.Assert.assertTrue(st.events.any { it.type == "ListenerAdded" })
        org.junit.Assert.assertEquals(0, st.events.count { it.kind == NetworkEventKind.Error })

        val node = st.node!!
        org.junit.Assert.assertEquals("host-a", node.hostname)
        org.junit.Assert.assertEquals("203.0.113.1", node.publicIpv4)
        org.junit.Assert.assertTrue(st.peers.any { it.hostname == "host-b" })
        org.junit.Assert.assertTrue(st.peers.any { it.hostname == "host-c" })
        org.junit.Assert.assertTrue(st.peers.any { it.hostname == "host-d" })
        org.junit.Assert.assertFalse(st.peers.any { it.hostname == "relay-1" })
    }
}
