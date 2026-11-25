package app.timiao.et.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.timiao.et.R
import app.timiao.et.core.PeerConn
import app.timiao.et.core.PeerInfo
import app.timiao.et.ui.AppViewModel
import app.timiao.et.ui.components.AvatarBadge
import app.timiao.et.ui.components.DockInset
import app.timiao.et.ui.components.EmptyGlassCard
import app.timiao.et.ui.components.GlassLevel
import app.timiao.et.ui.components.GlassPillAction
import app.timiao.et.ui.components.GlassSearchField
import app.timiao.et.ui.components.GlassTextField
import app.timiao.et.ui.components.InfoRow
import app.timiao.et.ui.components.OrbButton
import app.timiao.et.ui.components.PeerRow
import app.timiao.et.ui.components.PillButton
import app.timiao.et.ui.components.ScreenHeader
import app.timiao.et.ui.components.SegmentedGlass
import app.timiao.et.ui.components.glass
import app.timiao.et.ui.navigation.Routes
import app.timiao.et.ui.theme.LocalGlassColors
import app.timiao.et.ui.util.TopologyLayout
import app.timiao.et.ui.util.copyToClipboard
import app.timiao.et.ui.util.costLabel
import app.timiao.et.ui.util.formatBytes
import app.timiao.et.ui.util.initialOf
import app.timiao.et.ui.util.isRelay
import app.timiao.et.ui.util.natLabel
import app.timiao.et.ui.util.stableKey
import app.timiao.et.ui.util.tunnelLabel
import app.timiao.et.ui.util.versionLabel
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeersScreen(
    vm: AppViewModel,
    onNavigate: (String) -> Unit = {},
    /** Reserved slot for node actions wired by the main agent. Rendered at the bottom of the sheet. */
    actions: @Composable (PeerInfo) -> Unit = {},
) {
    val status by vm.activeStatus.collectAsStateWithLifecycle()
    val active by vm.activeNetwork.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val g = LocalGlassColors.current

    var mode by rememberSaveable { mutableStateOf(0) }
    var filter by rememberSaveable { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    // Store the stable key, not the object: the 1 s poll replaces the PeerInfo list.
    var selectedKey by remember { mutableStateOf<String?>(null) }

    // derivedStateOf keeps the search/filter work out of the whole screen recomposition.
    val filtered by remember {
        derivedStateOf {
            status.peers.filter { peer ->
                val matchQuery = query.isBlank() ||
                    peer.hostname.contains(query, ignoreCase = true) ||
                    peer.ipv4?.contains(query) == true
                val matchFilter = when (filter) {
                    1 -> !peer.isRelay()
                    2 -> peer.isRelay()
                    else -> true
                }
                matchQuery && matchFilter
            }
        }
    }
    val directCount = status.peers.count { !it.isRelay() }
    val relayCount = status.peers.size - directCount
    val selectedPeer: PeerInfo? = remember(selectedKey, status.peers) {
        val key = selectedKey
        if (key == null) null else status.peers.firstOrNull { it.stableKey() == key }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = DockInset,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = stringResource(R.string.peers_title),
                subtitle = active?.let { stringResource(R.string.peers_subtitle_with_count, it.name, status.peers.size) } ?: stringResource(R.string.peers_subtitle_no_net),
                action = {
                    GlassPillAction(
                        text = stringResource(R.string.peers_log),
                        icon = Icons.Filled.List,
                        onClick = { onNavigate(Routes.EVENT_LOG) },
                    )
                },
            )
        }
        item {
            SegmentedGlass(
                options = listOf(stringResource(R.string.peers_list), stringResource(R.string.peers_topology)),
                selected = mode,
                onSelect = { mode = it },
            )
        }
        item {
            GlassSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.search_host_or_ip),
            )
        }
        item {
            SegmentedGlass(
                options = listOf(
                    stringResource(R.string.peers_filter_all, status.peers.size),
                    stringResource(R.string.peers_filter_direct, directCount),
                    stringResource(R.string.peers_filter_relay, relayCount),
                ),
                selected = filter,
                onSelect = { filter = it },
            )
        }

        if (mode == 0) {
            if (filtered.isEmpty()) {
                item {
                    EmptyGlassCard(
                        text = when {
                            status.peers.isEmpty() -> stringResource(R.string.peers_empty)
                            else -> stringResource(R.string.peers_no_match)
                        },
                    )
                }
            } else {
                // Stable peer keys let LazyColumn reuse rows; only changed rows recompose.
                items(items = filtered, key = { it.stableKey() }) { peer ->
                    PeerRow(
                        peer = peer,
                        onClick = { selectedKey = peer.stableKey() },
                        onLongClick = {
                            peer.ipv4?.let { copyToClipboard(context, context.getString(R.string.clip_peer_ip), it) }
                        },
                    )
                }
            }
        } else {
            item {
                TopologyCard(
                    peers = filtered,
                    myIpv4 = status.myIpv4,
                    myPeerId = status.node?.peerId,
                )
            }
        }

        item {
            Text(
                text = stringResource(R.string.peers_long_press_copy),
                style = MaterialTheme.typography.labelSmall,
                color = g.muted,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
    }

    val peer = selectedPeer
    if (peer != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedKey = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            PeerDetail(
                peer = peer,
                onCopy = {
                    peer.ipv4?.let { copyToClipboard(context, context.getString(R.string.clip_peer_ip), it) }
                },
                actions = { actions(peer) },
            )
        }
    }
}

@Composable
private fun PeerDetail(
    peer: PeerInfo,
    onCopy: () -> Unit,
    actions: @Composable () -> Unit = {},
) {
    val g = LocalGlassColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AvatarBadge(peer.hostname, size = 46)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    peer.hostname,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                )
                Text(
                    peer.costLabel(),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (peer.isRelay()) g.warn else g.good,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(20.dp), GlassLevel.Subtle)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            InfoRow(stringResource(R.string.peers_info_ip), peer.ipv4 ?: "—")
            InfoRow("IPv6", peer.ipv6 ?: "—")
            InfoRow(stringResource(R.string.peers_info_latency), peer.latencyMs?.let { "${it.toInt()} ms" } ?: "—")
            InfoRow(stringResource(R.string.peers_info_loss), peer.lossRate?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—")
            InfoRow("NAT", natLabel(peer.natType))
            InfoRow("TCP NAT", natLabel(peer.tcpNatType))
            InfoRow(stringResource(R.string.peers_info_tunnel), peer.tunnelLabel())
            InfoRow(stringResource(R.string.peers_info_version), peer.versionLabel())
            InfoRow(stringResource(R.string.peers_info_route), peer.costLabel())
            InfoRow(stringResource(R.string.peers_info_tx), formatBytes(peer.txBytes))
            InfoRow(stringResource(R.string.peers_info_rx), formatBytes(peer.rxBytes))
            if (peer.isPublicServer) {
                InfoRow(stringResource(R.string.peers_info_tag), stringResource(R.string.peers_public_server))
            }
            if (peer.avoidRelayData) {
                InfoRow(stringResource(R.string.peers_info_tag), stringResource(R.string.peers_avoid_relay_data))
            }
        }

        if (peer.conns.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.peers_connections, peer.conns.size),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = g.muted,
            )
            Spacer(Modifier.height(8.dp))
            peer.conns.forEachIndexed { index, conn ->
                ConnCard(index = index, conn = conn)
                Spacer(Modifier.height(8.dp))
            }
        }

        actions()
        Spacer(Modifier.height(12.dp))
        PillButton(
            text = stringResource(R.string.peers_copy_ip),
            onClick = onCopy,
            enabled = peer.ipv4 != null,
            secondary = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ConnCard(index: Int, conn: PeerConn) {
    val g = LocalGlassColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(16.dp), GlassLevel.Subtle)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.peers_connection_n, index + 1),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (conn.isDefault) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.peers_default),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = conn.tunnel ?: "—",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = g.muted,
            )
        }
        InfoRow(stringResource(R.string.peers_local), conn.localAddr ?: "—")
        InfoRow(stringResource(R.string.peers_remote), conn.remoteAddr ?: "—")
        InfoRow(stringResource(R.string.peers_info_latency), conn.latencyMs?.let { "${it.toInt()} ms" } ?: "—")
        InfoRow(stringResource(R.string.peers_info_loss), conn.lossRate?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—")
        InfoRow(stringResource(R.string.peers_info_tx), formatBytes(conn.txBytes))
        InfoRow(stringResource(R.string.peers_info_rx), formatBytes(conn.rxBytes))
    }
}

@OptIn(ExperimentalTextApi::class)
@Composable
private fun TopologyCard(peers: List<PeerInfo>, myIpv4: String?, myPeerId: String?) {
    val g = LocalGlassColors.current
    val accent = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    val measurer = rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val labelStyle = TextStyle(color = onSurface, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    val subStyle = TextStyle(color = g.muted, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace)
    val initialStyle = TextStyle(
        color = onSurface,
        fontSize = 14.sp,
        fontWeight = FontWeight.Black,
    )
    val rootLabel = stringResource(R.string.topology_this_device)
    val offlineLabel = stringResource(R.string.topology_not_connected)
    val peerFallback = stringResource(R.string.topology_peer_fallback)
    val relaySuffix = stringResource(R.string.topology_relay_suffix)
    val peerPalette = listOf(
        Color(0xFF7CC6A4), Color(0xFF8FB6FF), Color(0xFFC39BFF),
        Color(0xFFF0A96B), Color(0xFFE58FA8), Color(0xFF74B9C9),
    )

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val widthDp = maxWidth.value
        // Recomputed once per poll (or width/font-scale change); positions are pure data.
        val layout = remember(peers, myIpv4, myPeerId, widthDp, density.fontScale) {
            TopologyLayout.layout(
                peers, myIpv4, myPeerId, widthDp, density.fontScale,
                rootLabel = rootLabel,
                offlineLabel = offlineLabel,
                peerFallback = peerFallback,
                relaySuffix = relaySuffix,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(26.dp), GlassLevel.Regular)
                .padding(horizontal = 10.dp, vertical = 12.dp),
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(layout.heightDp.dp),
            ) {
                val nodeR = TopologyLayout.NODE_R_DP.dp.toPx()
                val centerR = nodeR + 5.dp.toPx()
                val labelGap = 5.dp.toPx()
                val subGap = 2.dp.toPx()
                val edgeGap = 4.dp.toPx()
                val nodesByKey = layout.nodes.associateBy { it.key }
                // Measure first: edges start below the label block, so they never cross text.
                val measured = layout.nodes.associateWith { node ->
                    val maxW = (node.cellWDp - 12f).dp.roundToPx().coerceAtLeast(1)
                    val label = measurer.measure(
                        text = node.label,
                        style = labelStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        constraints = Constraints(maxWidth = maxW),
                    )
                    val sub = measurer.measure(
                        text = node.sub,
                        style = subStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        constraints = Constraints(maxWidth = maxW),
                    )
                    label to sub
                }

                layout.edges.forEach { edge ->
                    val fromNode = nodesByKey[edge.fromKey] ?: return@forEach
                    val toNode = nodesByKey[edge.toKey] ?: return@forEach
                    val (label, sub) = measured[fromNode] ?: return@forEach
                    val from = Offset(fromNode.xDp.dp.toPx(), fromNode.yDp.dp.toPx())
                    val to = Offset(toNode.xDp.dp.toPx(), toNode.yDp.dp.toPx())
                    val startY = from.y + nodeR + labelGap + label.size.height + subGap + sub.size.height + edgeGap
                    val endY = to.y - nodeR
                    val midY = (startY + endY) / 2f
                    val path = Path().apply {
                        moveTo(from.x, startY)
                        cubicTo(from.x, midY, to.x, midY, to.x, endY)
                    }
                    drawPath(
                        path = path,
                        color = if (edge.isRelay) g.muted.copy(alpha = 0.6f) else accent.copy(alpha = 0.55f),
                        style = Stroke(
                            width = if (edge.isRelay) 1.6.dp.toPx() else 2.2.dp.toPx(),
                            pathEffect = if (edge.isRelay) {
                                PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()))
                            } else {
                                null
                            },
                        ),
                    )
                }

                layout.nodes.forEach { node ->
                    val center = Offset(node.xDp.dp.toPx(), node.yDp.dp.toPx())
                    if (node.isRoot) {
                        drawCircle(surface.copy(alpha = 0.95f), radius = centerR + 2.dp.toPx(), center = center)
                        drawCircle(accent.copy(alpha = 0.16f), radius = centerR + 2.dp.toPx(), center = center)
                        drawCircle(accent, radius = centerR, center = center, style = Stroke(2.4.dp.toPx()))
                        val earSize = 11.dp.toPx()
                        val leftEar = Path().apply {
                            moveTo(center.x - centerR * 0.62f, center.y - centerR * 0.55f)
                            lineTo(center.x - centerR * 0.8f, center.y - centerR - earSize)
                            lineTo(center.x - centerR * 0.2f, center.y - centerR * 0.75f)
                            close()
                        }
                        val rightEar = Path().apply {
                            moveTo(center.x + centerR * 0.62f, center.y - centerR * 0.55f)
                            lineTo(center.x + centerR * 0.8f, center.y - centerR - earSize)
                            lineTo(center.x + centerR * 0.2f, center.y - centerR * 0.75f)
                            close()
                        }
                        drawPath(leftEar, accent)
                        drawPath(rightEar, accent)
                    } else {
                        val color = peerPalette[abs(node.key.hashCode()) % peerPalette.size]
                        drawCircle(surface.copy(alpha = 0.95f), radius = nodeR, center = center)
                        drawCircle(color.copy(alpha = 0.22f), radius = nodeR, center = center)
                        drawCircle(
                            color = color,
                            radius = nodeR,
                            center = center,
                            style = Stroke(
                                width = 2.dp.toPx(),
                                pathEffect = if (node.isRelay) {
                                    PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
                                } else {
                                    null
                                },
                            ),
                        )
                        val initial = measurer.measure(
                            text = initialOf(node.label),
                            style = initialStyle,
                            maxLines = 1,
                        )
                        drawText(
                            textLayoutResult = initial,
                            topLeft = Offset(
                                center.x - initial.size.width / 2f,
                                center.y - initial.size.height / 2f,
                            ),
                        )
                    }

                    val (label, sub) = measured.getValue(node)
                    val labelTop = center.y + nodeR + labelGap
                    drawText(
                        textLayoutResult = label,
                        topLeft = Offset(center.x - label.size.width / 2f, labelTop),
                    )
                    drawText(
                        textLayoutResult = sub,
                        topLeft = Offset(
                            center.x - sub.size.width / 2f,
                            labelTop + label.size.height + subGap,
                        ),
                    )
                }
            }
            if (peers.isEmpty()) {
                Text(
                    text = stringResource(R.string.peers_empty_topology),
                    style = MaterialTheme.typography.labelSmall,
                    color = g.muted,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Canvas(Modifier.size(width = 24.dp, height = 8.dp)) {
                    drawLine(
                        color = accent,
                        start = Offset(0f, size.height / 2f),
                        end = Offset(size.width, size.height / 2f),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
                Spacer(Modifier.width(5.dp))
                Text(stringResource(R.string.peers_direct), style = MaterialTheme.typography.labelSmall, color = g.muted)
                Spacer(Modifier.width(16.dp))
                Canvas(Modifier.size(width = 24.dp, height = 8.dp)) {
                    drawLine(
                        color = g.muted,
                        start = Offset(0f, size.height / 2f),
                        end = Offset(size.width, size.height / 2f),
                        strokeWidth = 1.6.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(6.dp.toPx(), 4.dp.toPx()),
                        ),
                    )
                }
                Spacer(Modifier.width(5.dp))
                Text(stringResource(R.string.peers_relay), style = MaterialTheme.typography.labelSmall, color = g.muted)
            }
        }
    }
}
