package app.timiao.et.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.timiao.et.core.PeerInfo
import app.timiao.et.ui.AppViewModel
import app.timiao.et.ui.components.AvatarBadge
import app.timiao.et.ui.components.DockInset
import app.timiao.et.ui.components.EmptyGlassCard
import app.timiao.et.ui.components.GlassLevel
import app.timiao.et.ui.components.GlassTextField
import app.timiao.et.ui.components.InfoRow
import app.timiao.et.ui.components.PeerRow
import app.timiao.et.ui.components.PillButton
import app.timiao.et.ui.components.ScreenHeader
import app.timiao.et.ui.components.SegmentedGlass
import app.timiao.et.ui.components.glass
import app.timiao.et.ui.theme.LocalGlassColors
import app.timiao.et.ui.util.copyToClipboard
import app.timiao.et.ui.util.costLabel
import app.timiao.et.ui.util.formatBytes
import app.timiao.et.ui.util.isRelay
import app.timiao.et.ui.util.natLabel
import app.timiao.et.ui.util.tunnelLabel
import androidx.compose.ui.platform.LocalContext
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeersScreen(vm: AppViewModel) {
    val status by vm.activeStatus.collectAsStateWithLifecycle()
    val active by vm.activeNetwork.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val g = LocalGlassColors.current

    var mode by rememberSaveable { mutableStateOf(0) }
    var filter by rememberSaveable { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<PeerInfo?>(null) }

    val filtered = status.peers.filter { peer ->
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
    val directCount = status.peers.count { !it.isRelay() }
    val relayCount = status.peers.size - directCount

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = DockInset,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "节点",
                subtitle = active?.let { "${it.name} · ${status.peers.size} 只" } ?: "未选择网络",
            )
        }
        item {
            SegmentedGlass(
                options = listOf("列表", "拓扑"),
                selected = mode,
                onSelect = { mode = it },
            )
        }
        item {
            GlassTextField(
                value = query,
                onValueChange = { query = it },
                label = "搜索",
                placeholder = "主机名或 IP",
                mono = true,
            )
        }
        item {
            SegmentedGlass(
                options = listOf("全部 ${status.peers.size}", "直连 $directCount", "中继 $relayCount"),
                selected = filter,
                onSelect = { filter = it },
            )
        }

        if (mode == 0) {
            if (filtered.isEmpty()) {
                item {
                    EmptyGlassCard(
                        text = when {
                            status.peers.isEmpty() -> "还没有节点。连接网络后显示。"
                            else -> "没有匹配的节点。"
                        },
                    )
                }
            } else {
                items(filtered.size) { index ->
                    val peer = filtered[index]
                    PeerRow(
                        peer = peer,
                        onClick = { selected = peer },
                        onLongClick = {
                            peer.ipv4?.let { copyToClipboard(context, "节点 IP", it) }
                        },
                    )
                }
            }
        } else {
            item {
                if (filtered.isEmpty()) {
                    EmptyGlassCard(text = "拓扑为空。")
                } else {
                    TopologyCard(
                        peers = filtered,
                        myIpv4 = status.myIpv4,
                        myName = "本机",
                    )
                }
            }
        }

        item {
            Text(
                text = "提示：长按节点可复制 IP 地址。",
                style = MaterialTheme.typography.labelSmall,
                color = g.muted,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
    }

    val peer = selected
    if (peer != null) {
        ModalBottomSheet(
            onDismissRequest = { selected = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            PeerDetail(peer = peer, onCopy = {
                peer.ipv4?.let { copyToClipboard(context, "节点 IP", it) }
            })
        }
    }
}

@Composable
private fun PeerDetail(peer: PeerInfo, onCopy: () -> Unit) {
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
            InfoRow("IP", peer.ipv4 ?: "—")
            InfoRow("延迟", peer.latencyMs?.let { "${it.toInt()} ms" } ?: "—")
            InfoRow("丢包", peer.lossRate?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—")
            InfoRow("NAT", natLabel(peer.natType))
            InfoRow("隧道", peer.tunnelLabel())
            InfoRow("版本", peer.version ?: "—")
            InfoRow("路由", peer.cost)
            InfoRow("发送", formatBytes(peer.txBytes))
            InfoRow("接收", formatBytes(peer.rxBytes))
        }
        Spacer(Modifier.height(16.dp))
        PillButton(
            text = "复制 IP",
            onClick = onCopy,
            enabled = peer.ipv4 != null,
            secondary = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalTextApi::class)
@Composable
private fun TopologyCard(peers: List<PeerInfo>, myIpv4: String?, myName: String) {
    val g = LocalGlassColors.current
    val accent = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = onSurface, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    val subStyle = TextStyle(color = g.muted, fontSize = 8.5.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
    val peerPalette = listOf(
        Color(0xFF7CC6A4), Color(0xFF8FB6FF), Color(0xFFC39BFF),
        Color(0xFFF0A96B), Color(0xFFE58FA8), Color(0xFF74B9C9),
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(26.dp), GlassLevel.Regular)
            .padding(12.dp),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.05f),
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val orbit = min(size.width, size.height) / 2f - 56.dp.toPx()
            val nodeR = 20.dp.toPx()
            val centerR = 28.dp.toPx()

            peers.forEachIndexed { index, peer ->
                val angle = -PI / 2 + 2 * PI * index / peers.size
                val px = center.x + (orbit * cos(angle)).toFloat()
                val py = center.y + (orbit * sin(angle)).toFloat()
                val peerPos = Offset(px, py)
                val relay = peer.isRelay()
                drawLine(
                    color = if (relay) g.muted.copy(alpha = 0.55f) else accent.copy(alpha = 0.5f),
                    start = center,
                    end = peerPos,
                    strokeWidth = if (relay) 1.6.dp.toPx() else 2.dp.toPx(),
                    pathEffect = if (relay) {
                        PathEffect.dashPathEffect(floatArrayOf(9.dp.toPx(), 7.dp.toPx()))
                    } else {
                        null
                    },
                )
            }

            peers.forEachIndexed { index, peer ->
                val angle = -PI / 2 + 2 * PI * index / peers.size
                val px = center.x + (orbit * cos(angle)).toFloat()
                val py = center.y + (orbit * sin(angle)).toFloat()
                val color = peerPalette[(peer.hostname.hashCode().let { if (it < 0) -it else it }) % peerPalette.size]
                drawCircle(surface.copy(alpha = 0.92f), radius = nodeR, center = Offset(px, py))
                drawCircle(color, radius = nodeR - 3.dp.toPx(), center = Offset(px, py))
                drawCircle(color, radius = nodeR, center = Offset(px, py), style = Stroke(1.5.dp.toPx()))
                val text = measurer.measure(
                    peer.hostname,
                    style = labelStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                drawText(
                    textLayoutResult = text,
                    topLeft = Offset(px - text.size.width / 2f, py + nodeR + 5.dp.toPx()),
                )
                val latencyText = measurer.measure(
                    peer.latencyMs?.let { "${it.toInt()} ms" } ?: "—",
                    style = subStyle,
                    maxLines = 1,
                )
                drawText(
                    textLayoutResult = latencyText,
                    topLeft = Offset(px - latencyText.size.width / 2f, py + nodeR + 19.dp.toPx()),
                )
            }

            // Center = this device, with a tiny cat-ear silhouette.
            drawCircle(surface.copy(alpha = 0.95f), radius = centerR + 2.dp.toPx(), center = center)
            drawCircle(accent.copy(alpha = 0.18f), radius = centerR + 2.dp.toPx(), center = center)
            drawCircle(accent, radius = centerR, center = center, style = Stroke(2.dp.toPx()))
            val earSize = 9.dp.toPx()
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
            val myLabel = measurer.measure(myName, style = labelStyle.copy(fontSize = 11.sp), maxLines = 1)
            drawText(
                textLayoutResult = myLabel,
                topLeft = Offset(center.x - myLabel.size.width / 2f, center.y - 4.dp.toPx()),
            )
            val ipLabel = measurer.measure(myIpv4 ?: "未连接", style = subStyle, maxLines = 1)
            drawText(
                textLayoutResult = ipLabel,
                topLeft = Offset(center.x - ipLabel.size.width / 2f, center.y + centerR + 6.dp.toPx()),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(14.dp, 2.dp).glass(RoundedCornerShape(2.dp), GlassLevel.Subtle))
            Spacer(Modifier.width(5.dp))
            Text("直连", style = MaterialTheme.typography.labelSmall, color = g.muted)
            Spacer(Modifier.width(15.dp))
            Text("┄", style = MaterialTheme.typography.labelSmall, color = g.muted)
            Spacer(Modifier.width(5.dp))
            Text("中继", style = MaterialTheme.typography.labelSmall, color = g.muted)
        }
    }
}
