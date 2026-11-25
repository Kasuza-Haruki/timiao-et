package app.timiao.et.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.timiao.et.core.LinkState
import app.timiao.et.ui.AppViewModel
import app.timiao.et.ui.components.CatMascot
import app.timiao.et.ui.components.ClickableGlassRow
import app.timiao.et.ui.components.DockInset
import app.timiao.et.ui.components.EmptyGlassCard
import app.timiao.et.ui.components.GlassDropdownRow
import app.timiao.et.ui.components.GlassLevel
import app.timiao.et.ui.components.OrbButton
import app.timiao.et.ui.components.PeerRow
import app.timiao.et.ui.components.PillButton
import app.timiao.et.ui.components.ScreenHeader
import app.timiao.et.ui.components.SectionLabel
import app.timiao.et.ui.components.StatTile
import app.timiao.et.ui.components.glass
import app.timiao.et.ui.navigation.Routes
import app.timiao.et.ui.theme.LocalGlassColors
import app.timiao.et.ui.util.copyToClipboard
import app.timiao.et.ui.util.formatRate
import app.timiao.et.ui.util.formatUptime
import app.timiao.et.ui.util.stableKey
import androidx.compose.foundation.shape.RoundedCornerShape

@Composable
fun HomeScreen(
    vm: AppViewModel,
    onNavigate: (String) -> Unit,
) {
    val networks by vm.networks.collectAsStateWithLifecycle()
    val active by vm.activeNetwork.collectAsStateWithLifecycle()
    val status by vm.activeStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val g = LocalGlassColors.current

    val hasNetwork = active != null
    val reconnecting = status.reconnecting
    val statusTitle = when {
        !hasNetwork -> "还没有网络"
        reconnecting -> "正在重连…"
        status.state == LinkState.Idle -> "未连接"
        status.state == LinkState.Connecting -> "正在连接…"
        status.state == LinkState.Connected -> "已连接 ${active?.name.orEmpty()}"
        else -> "连接出错"
    }
    val statusSub = when {
        !hasNetwork -> ""
        reconnecting -> "网络中断，正在重连"
        status.state == LinkState.Idle -> "小猫在睡觉，点按钮唤醒天线"
        status.state == LinkState.Connecting -> "竖起天线找节点"
        status.state == LinkState.Connected ->
            "${status.myIpv4 ?: "—"} · ${formatUptime(status.uptimeSec)}"
        else -> status.error ?: "配置有问题，点开看原因"
    }
    val buttonText = if (!hasNetwork) "创建网络" else when {
        reconnecting -> "断开连接"
        status.state == LinkState.Idle -> "连接"
        status.state == LinkState.Connecting -> "取消连接"
        status.state == LinkState.Connected -> "断开连接"
        else -> "重试"
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = DockInset,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "缇喵",
                action = {
                    OrbButton(
                        icon = Icons.Filled.Settings,
                        label = "设置",
                        onClick = { onNavigate("settings") },
                    )
                },
            )
        }

        if (active != null) {
            item {
                val server = active!!.servers.firstOrNull().orEmpty()
                GlassDropdownRow(
                    label = if (server.isBlank()) "当前网络" else "当前网络 · $server",
                    current = active!!,
                    options = networks,
                    optionLabel = { it.name.ifBlank { "未命名网络" } },
                    onSelect = { vm.selectNetwork(it.id) },
                    enabled = networks.size > 1,
                )
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(28.dp), GlassLevel.Strong)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CatMascot(
                    state = if (reconnecting) LinkState.Connecting else status.state,
                    modifier = Modifier.size(width = 208.dp, height = 184.dp),
                )
                Text(
                    text = statusTitle,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Black,
                        fontSize = 22.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (statusSub.isNotEmpty()) {
                    Text(
                        text = statusSub,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = g.muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(12.dp))
                PillButton(
                    text = buttonText,
                    onClick = {
                        if (active == null) onNavigate(Routes.NETWORKS)
                        else vm.toggleActiveNetwork()
                    },
                    enabled = true,
                )
            }
        }

        item {
            ClickableGlassRow(onClick = { onNavigate(Routes.NODE_INFO) }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "虚拟 IP",
                            style = MaterialTheme.typography.labelSmall,
                            color = g.muted,
                        )
                        Text(
                            text = status.myIpv4 ?: "未连接",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = "本机信息",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = "›",
                        style = MaterialTheme.typography.titleLarge,
                        color = g.muted,
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatTile("上传", formatRate(status.txBps), Modifier.weight(1f))
                StatTile("下载", formatRate(status.rxBps), Modifier.weight(1f))
                StatTile("节点", "${status.peers.size} 只", Modifier.weight(1f))
            }
        }

        item {
            Text(
                text = "实时速率，每秒刷新。",
                style = MaterialTheme.typography.labelSmall,
                color = g.muted,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }

        if (status.state == LinkState.Error && status.error != null) {
            item {
                Text(
                    text = status.error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .glass(RoundedCornerShape(18.dp), GlassLevel.Subtle)
                        .padding(14.dp),
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel("附近节点", Modifier.weight(1f))
                Text(
                    text = "全部 ›",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { onNavigate("peers") }
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }
        }

        if (status.peers.isEmpty()) {
            item {
                EmptyGlassCard(
                    text = if (status.state == LinkState.Connected) "还没有发现节点。" else "连接后显示节点。",
                )
            }
        } else {
            // Stable keys keep the 3-row preview from recomposing as a block every second.
            items(items = status.peers.take(3), key = { it.stableKey() }) { peer ->
                PeerRow(
                    peer = peer,
                    onClick = { onNavigate("peers") },
                    onLongClick = {
                        peer.ipv4?.let { copyToClipboard(context, "节点 IP", it) }
                    },
                )
            }
        }
    }
}
