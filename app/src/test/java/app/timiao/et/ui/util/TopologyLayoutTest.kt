package app.timiao.et.ui.util

import app.timiao.et.core.PeerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TopologyLayoutTest {

    private fun peer(index: Int, nextHop: String?, relay: Boolean) = PeerInfo(
        hostname = "host-$index",
        ipv4 = "10.0.0.${index + 2}/24",
        cost = if (relay) "relay(2)" else "p2p",
        tunnel = "udp",
        latencyMs = 10.0 + index,
        lossRate = 0.0,
        natType = "FullCone",
        txBytes = 0,
        rxBytes = 0,
        version = "2.7.1",
        peerId = (index + 2).toString(),
        nextHopPeerId = nextHop,
    )

    @Test
    fun handlesZeroToOneAndThreeNodes() {
        listOf(0, 1, 3).forEach { count -> assertLayout(count, width = 360f) }
    }

    @Test
    fun handlesTenAndThirtyNodesWithRelays() {
        listOf(10, 30).forEach { count -> assertLayout(count, width = 360f) }
    }

    @Test
    fun handlesThirtyDirectNodes() {
        val peers = (0 until 30).map { index ->
            peer(index, nextHop = (index + 2).toString(), relay = false)
        }
        val layout = TopologyLayout.layout(peers, "10.0.0.1/24", "1", 360f)
        assertEquals(31, layout.nodes.size)
        assertNoOverlapAndInsideWidth(layout, 360f)
    }

    @Test
    fun placesRelayAfterItsNextHop() {
        val peers = listOf(
            peer(0, nextHop = "2", relay = false),
            peer(1, nextHop = "2", relay = true),
            peer(2, nextHop = "3", relay = true),
        )
        val layout = TopologyLayout.layout(peers, "10.0.0.1/24", "1", 360f)
        val direct = layout.nodes.first { it.key == "peer:2" }
        val relay = layout.nodes.first { it.key == "peer:3" }
        val relayOfRelay = layout.nodes.first { it.key == "peer:4" }

        val childEdge = layout.edges.first { it.toKey == "peer:3" }
        assertEquals("peer:2", childEdge.fromKey)
        assertTrue(childEdge.isRelay)
        // Each hop adds a row below the parent, so relays are drawn after their next hop.
        assertTrue(relay.yDp > direct.yDp)
        assertTrue(relayOfRelay.yDp > relay.yDp)
        val chainEdge = layout.edges.first { it.toKey == "peer:4" }
        assertEquals("peer:3", chainEdge.fromKey)
    }

    @Test
    fun cyclicNextHopDoesNotHangOrEscape() {
        val peers = listOf(
            peer(0, nextHop = "3", relay = true),
            peer(1, nextHop = "2", relay = true),
        )
        val layout = TopologyLayout.layout(peers, "10.0.0.1/24", "1", 360f)
        assertEquals(3, layout.nodes.size)
        assertNoOverlapAndInsideWidth(layout, 360f)
    }

    private fun assertLayout(count: Int, width: Float) {
        val direct = minOf(count, 5)
        val peers = (0 until count).map { index ->
            if (index < direct) {
                peer(index, nextHop = (index + 2).toString(), relay = false)
            } else {
                peer(index, nextHop = (2 + index % direct.coerceAtLeast(1)).toString(), relay = true)
            }
        }
        val layout = TopologyLayout.layout(peers, "10.0.0.1/24", "1", width)
        assertEquals(count + 1, layout.nodes.size)
        assertEquals(count, layout.edges.size)
        assertNoOverlapAndInsideWidth(layout, width)
    }

    private fun assertNoOverlapAndInsideWidth(layout: TopoLayout, width: Float) {
        val nodes = layout.nodes
        nodes.forEach { node ->
            assertTrue("node ${node.key} x inside", node.xDp >= TopologyLayout.NODE_R_DP)
            assertTrue("node ${node.key} x inside", node.xDp <= width - TopologyLayout.NODE_R_DP)
            assertTrue("node ${node.key} y positive", node.yDp > 0f)
            assertTrue("node ${node.key} inside height", node.yDp + TopologyLayout.NODE_R_DP <= layout.heightDp)
        }
        for (i in nodes.indices) {
            for (j in i + 1 until nodes.size) {
                val a = nodes[i]
                val b = nodes[j]
                val dx = abs(a.xDp - b.xDp)
                val dy = abs(a.yDp - b.yDp)
                // Cells are 92 dp wide and rows 70 dp high; labels are clipped to the cell.
                assertFalse(
                    "overlap ${a.key}/${b.key}",
                    dx < TopologyLayout.CELL_W_DP - 8f && dy < TopologyLayout.ROW_H_DP - 8f,
                )
            }
        }
    }
}
