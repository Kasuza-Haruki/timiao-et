package app.timiao.et.ui.util

import app.timiao.et.core.PeerInfo

/**
 * Layered tree layout for the topology card.
 *
 * Peers are placed in BFS layers by `next_hop_peer_id`: direct peers are below the local node,
 * relays go below their next hop. Each layer wraps into centred rows of fixed-width cells, so
 * 0/1/3/10/30 nodes never overlap and always stay inside the given width. The card height grows
 * with the number of rows; the surrounding lazy list scrolls.
 */
internal data class TopoNode(
    val key: String,
    val label: String,
    val sub: String,
    val isRoot: Boolean,
    val isRelay: Boolean,
    val xDp: Float,
    val yDp: Float,
)

internal data class TopoEdge(
    val fromKey: String,
    val toKey: String,
    val isRelay: Boolean,
)

internal data class TopoLayout(
    val nodes: List<TopoNode>,
    val edges: List<TopoEdge>,
    val heightDp: Float,
)

internal object TopologyLayout {
    const val ROOT_KEY = "__self__"
    const val CELL_W_DP = 92f
    const val ROW_H_DP = 70f
    const val NODE_R_DP = 15f
    private const val TOP_PAD_DP = 12f
    private const val BOTTOM_PAD_DP = 10f
    private const val SIDE_PAD_DP = 6f
    private const val LEVEL_GAP_DP = 12f

    fun layout(
        peers: List<PeerInfo>,
        myIpv4: String?,
        myPeerId: String?,
        widthDp: Float,
    ): TopoLayout {
        val keys = peers.mapIndexed { index, peer ->
            peer.peerId?.let { "peer:$it" } ?: "n:$index"
        }
        val byPeerId = HashMap<String, Int>()
        peers.forEachIndexed { index, peer ->
            peer.peerId?.let { byPeerId[it] = index }
        }

        val parent = IntArray(peers.size) { -1 }
        peers.forEachIndexed { index, peer ->
            val nextHop = peer.nextHopPeerId
            if (nextHop != null && nextHop != myPeerId) {
                val candidate = byPeerId[nextHop]
                if (candidate != null && candidate != index) parent[index] = candidate
            }
        }
        breakCycles(parent)

        val levelOf = IntArray(peers.size) { -1 }
        val children = Array(peers.size) { mutableListOf<Int>() }
        parent.forEachIndexed { index, p ->
            if (p >= 0) children[p].add(index)
        }
        val queue = ArrayDeque<Int>()
        peers.indices.filter { parent[it] < 0 }.forEach {
            levelOf[it] = 0
            queue.addLast(it)
        }
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            children[p].forEach { child ->
                if (levelOf[child] < 0) {
                    levelOf[child] = levelOf[p] + 1
                    queue.addLast(child)
                }
            }
        }
        // Defensive: anything still unassigned becomes a root so it is still drawn.
        peers.indices.filter { levelOf[it] < 0 }.forEach { levelOf[it] = 0 }

        val layers = (0..(levelOf.maxOrNull() ?: 0)).map { level ->
            peers.indices.filter { levelOf[it] == level }.sorted()
        }

        val usable = (widthDp - SIDE_PAD_DP * 2).coerceAtLeast(CELL_W_DP)
        val cellW = CELL_W_DP
        val perRow = (usable / cellW).toInt().coerceAtLeast(1)

        val nodes = mutableListOf<TopoNode>()
        val rootY = TOP_PAD_DP + NODE_R_DP
        nodes += TopoNode(
            key = ROOT_KEY,
            label = "本机",
            sub = myIpv4 ?: "未连接",
            isRoot = true,
            isRelay = false,
            xDp = widthDp / 2f,
            yDp = rootY,
        )

        var cursor = TOP_PAD_DP + ROW_H_DP
        layers.forEach { layer ->
            val rows = (layer.size + perRow - 1) / perRow
            for (row in 0 until rows) {
                val from = row * perRow
                val rowItems = layer.subList(from, minOf(from + perRow, layer.size))
                val rowWidth = rowItems.size * cellW
                val startX = (widthDp - rowWidth) / 2f + cellW / 2f
                rowItems.forEachIndexed { column, index ->
                    val peer = peers[index]
                    val latency = peer.latencyMs?.let { "${it.toInt()} ms" } ?: "—"
                    nodes += TopoNode(
                        key = keys[index],
                        label = peer.hostname.ifBlank { peer.ipv4 ?: "节点" },
                        sub = if (peer.isRelay()) "$latency · 中继" else latency,
                        isRoot = false,
                        isRelay = peer.isRelay(),
                        xDp = startX + column * cellW,
                        yDp = cursor + row * ROW_H_DP + NODE_R_DP,
                    )
                }
            }
            cursor += rows * ROW_H_DP + LEVEL_GAP_DP
        }

        val edges = mutableListOf<TopoEdge>()
        peers.indices.forEach { index ->
            val p = parent[index]
            edges += TopoEdge(
                fromKey = if (p >= 0) keys[p] else ROOT_KEY,
                toKey = keys[index],
                isRelay = peers[index].isRelay(),
            )
        }

        val height = (cursor - LEVEL_GAP_DP + BOTTOM_PAD_DP).coerceAtLeast(ROW_H_DP)
        return TopoLayout(nodes, edges, height)
    }

    /** A malformed route can point back into a cycle; treating its first node as a root is safe. */
    private fun breakCycles(parent: IntArray) {
        for (start in parent.indices) {
            var current = parent[start]
            var steps = 0
            while (current >= 0 && steps <= parent.size) {
                if (current == start) {
                    parent[start] = -1
                    break
                }
                current = parent[current]
                steps++
            }
        }
    }
}
