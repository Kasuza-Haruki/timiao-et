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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.timiao.et.R
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
    val unnamedNetwork = stringResource(R.string.home_unnamed_network)

    val hasNetwork = active != null
    val reconnecting = status.reconnecting
    val statusTitle = when {
        !hasNetwork -> stringResource(R.string.home_empty_title)
        reconnecting -> stringResource(R.string.home_status_reconnecting)
        status.state == LinkState.Idle -> stringResource(R.string.home_status_idle)
        status.state == LinkState.Connecting -> stringResource(R.string.home_status_connecting)
        status.state == LinkState.Connected -> stringResource(R.string.home_status_connected, active?.name.orEmpty())
        else -> stringResource(R.string.home_status_error)
    }
    val statusSub = when {
        !hasNetwork -> ""
        reconnecting -> stringResource(R.string.home_sub_reconnecting)
        status.state == LinkState.Idle -> stringResource(R.string.home_sub_idle)
        status.state == LinkState.Connecting -> stringResource(R.string.home_sub_connecting)
        status.state == LinkState.Connected ->
            "${status.myIpv4 ?: "—"} · ${formatUptime(status.uptimeSec)}"
        else -> status.error ?: stringResource(R.string.home_sub_error)
    }
    val buttonText = if (!hasNetwork) stringResource(R.string.home_create_network) else when {
        reconnecting -> stringResource(R.string.home_btn_disconnect)
        status.state == LinkState.Idle -> stringResource(R.string.home_btn_connect)
        status.state == LinkState.Connecting -> stringResource(R.string.home_btn_cancel)
        status.state == LinkState.Connected -> stringResource(R.string.home_btn_disconnect)
        else -> stringResource(R.string.home_btn_retry)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = DockInset,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = stringResource(R.string.home_title),
                action = {
                    OrbButton(
                        icon = Icons.Filled.Settings,
                        label = stringResource(R.string.dock_settings),
                        onClick = { onNavigate("settings") },
                    )
                },
            )
        }

        if (active != null) {
            item {
                val server = active!!.servers.firstOrNull().orEmpty()
                GlassDropdownRow(
                    label = if (server.isBlank()) stringResource(R.string.home_current_network) else stringResource(R.string.home_current_network_server, server),
                    current = active!!,
                    options = networks,
                    optionLabel = { it.name.ifBlank { unnamedNetwork } },
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
                            text = stringResource(R.string.home_virtual_ip),
                            style = MaterialTheme.typography.labelSmall,
                            color = g.muted,
                        )
                        Text(
                            text = status.myIpv4 ?: stringResource(R.string.home_status_idle),
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
                        text = stringResource(R.string.home_node_info),
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
                StatTile(stringResource(R.string.home_upload), formatRate(status.txBps), Modifier.weight(1f))
                StatTile(stringResource(R.string.home_download), formatRate(status.rxBps), Modifier.weight(1f))
                StatTile(stringResource(R.string.home_peers), pluralStringResource(R.plurals.peer_count, status.peers.size, status.peers.size), Modifier.weight(1f))
            }
        }

        item {
            Text(
                text = stringResource(R.string.home_live_rate),
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
                SectionLabel(stringResource(R.string.home_nearby), Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.home_all),
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
                    text = stringResource(if (status.state == LinkState.Connected) R.string.home_no_peers_connected else R.string.home_no_peers_idle),
                )
            }
        } else {
            // Stable keys keep the 3-row preview from recomposing as a block every second.
            items(items = status.peers.take(3), key = { it.stableKey() }) { peer ->
                PeerRow(
                    peer = peer,
                    onClick = { onNavigate("peers") },
                    onLongClick = {
                        peer.ipv4?.let { copyToClipboard(context, context.getString(R.string.clip_peer_ip), it) }
                    },
                )
            }
        }
    }
}
