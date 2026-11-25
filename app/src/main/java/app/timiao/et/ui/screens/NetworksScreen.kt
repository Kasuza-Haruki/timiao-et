package app.timiao.et.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.timiao.et.core.LinkState
import app.timiao.et.ui.AppViewModel
import app.timiao.et.ui.components.AvatarBadge
import app.timiao.et.ui.components.ConfirmDialog
import app.timiao.et.ui.components.DockInset
import app.timiao.et.ui.components.EmptyGlassCard
import app.timiao.et.ui.components.GlassLevel
import app.timiao.et.ui.components.OrbButton
import app.timiao.et.ui.components.PillButton
import app.timiao.et.ui.components.ScreenHeader
import app.timiao.et.ui.components.glass
import app.timiao.et.ui.data.UiNetwork
import app.timiao.et.ui.theme.LocalGlassColors

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NetworksScreen(
    vm: AppViewModel,
    onNavigate: (String) -> Unit,
) {
    val networks by vm.networks.collectAsStateWithLifecycle()
    val activeId by vm.activeNetworkId.collectAsStateWithLifecycle()
    val statuses by vm.statuses.collectAsStateWithLifecycle()
    val g = LocalGlassColors.current
    var deleteTarget by remember { mutableStateOf<UiNetwork?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = DockInset,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "网络",
                subtitle = "${networks.size} 个网络",
                action = {
                    OrbButton(
                        icon = Icons.Filled.Add,
                        label = "新建网络",
                        onClick = { onNavigate("network/new") },
                    )
                },
            )
        }
        item {
            Text(
                text = "点击网络可编辑配置，长按删除。",
                style = MaterialTheme.typography.labelSmall,
                color = g.muted,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }

        if (networks.isEmpty()) {
            item {
                EmptyGlassCard(
                    text = "还没有网络。创建一个，让小猫出门找朋友。",
                    actionText = "新建网络",
                    onAction = { onNavigate("network/new") },
                )
            }
        } else {
            items(networks.size) { index ->
                val network = networks[index]
                val status = statuses[network.id]
                val active = network.id == activeId
                NetworkCard(
                    network = network,
                    active = active,
                    peerCount = status?.peers?.size ?: 0,
                    state = status?.state ?: LinkState.Idle,
                    onClick = { onNavigate("network/${network.id}") },
                    onLongClick = { deleteTarget = network },
                )
            }
        }

        item {
            Spacer(Modifier.size(1.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PillButton(
                    text = "导入 TOML",
                    onClick = { onNavigate("network/new?open=import") },
                    secondary = true,
                    modifier = Modifier.weight(1f),
                )
                PillButton(
                    text = "扫码导入",
                    onClick = { onNavigate("network/new?open=scan") },
                    secondary = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    deleteTarget?.let { target ->
        ConfirmDialog(
            title = "删除网络",
            text = "确定删除「${target.name}」？此操作不可撤销。",
            confirmText = "删除",
            onConfirm = {
                vm.deleteNetwork(target.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NetworkCard(
    network: UiNetwork,
    active: Boolean,
    peerCount: Int,
    state: LinkState,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val g = LocalGlassColors.current
    val stateText = when (state) {
        LinkState.Connected -> "已连接"
        LinkState.Connecting -> "连接中"
        LinkState.Error -> "出错"
        LinkState.Idle -> "未连接"
    }
    val stateColor = when (state) {
        LinkState.Connected -> g.good
        LinkState.Connecting -> MaterialTheme.colorScheme.primary
        LinkState.Error -> MaterialTheme.colorScheme.error
        LinkState.Idle -> g.muted
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(22.dp), GlassLevel.Regular)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarBadge(network.name.ifBlank { "?" }, size = 42)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = network.name.ifBlank { "未命名网络" },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (active) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "当前",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 7.dp, vertical = 1.dp),
                    )
                }
            }
            Text(
                text = network.servers.firstOrNull() ?: "未设置服务器",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = g.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "$stateText · $peerCount 只节点 · ${network.flags.count { it.value }} 个选项",
                style = MaterialTheme.typography.labelSmall,
                color = stateColor,
                maxLines = 1,
            )
        }
    }
}
