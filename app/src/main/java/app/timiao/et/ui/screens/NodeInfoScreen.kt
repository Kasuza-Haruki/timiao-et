package app.timiao.et.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.timiao.et.R
import app.timiao.et.ui.AppViewModel
import app.timiao.et.ui.components.EmptyGlassCard
import app.timiao.et.ui.components.GlassLevel
import app.timiao.et.ui.components.ScreenHeader
import app.timiao.et.ui.components.glass
import app.timiao.et.ui.components.pageInset
import app.timiao.et.ui.theme.LocalGlassColors
import app.timiao.et.ui.util.copyToClipboard
import app.timiao.et.ui.util.natLabel

/** Local node information: identity, addresses, listeners and NAT/STUN details. */
@Composable
fun NodeInfoScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
) {
    val status by vm.activeStatus.collectAsStateWithLifecycle()
    val node = status.node
    val context = LocalContext.current
    val g = LocalGlassColors.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = pageInset(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = stringResource(R.string.node_title),
                subtitle = node?.hostname?.takeIf { it.isNotBlank() } ?: stringResource(R.string.node_not_connected),
                onBack = onBack,
            )
        }

        if (node == null) {
            item { EmptyGlassCard(text = stringResource(R.string.node_empty)) }
        } else {
            item {
                InfoSection(stringResource(R.string.node_identity)) {
                    CopyRow("Peer ID", node.peerId)
                    CopyRow(stringResource(R.string.node_virtual_ipv4), node.virtualIpv4 ?: status.myIpv4)
                    CopyRow(stringResource(R.string.node_virtual_ipv6), node.virtualIpv6)
                    CopyRow(stringResource(R.string.node_hostname), node.hostname)
                    CopyRow(stringResource(R.string.node_version), node.version)
                }
            }
            item {
                InfoSection(stringResource(R.string.node_addresses)) {
                    if (node.localIpv4s.isEmpty()) {
                        CopyRow(stringResource(R.string.node_local_ipv4), null)
                    } else {
                        node.localIpv4s.forEachIndexed { index, ip ->
                            CopyRow(if (node.localIpv4s.size == 1) stringResource(R.string.node_local_ipv4) else stringResource(R.string.node_local_ipv4_n, index + 1), ip)
                        }
                    }
                    if (node.localIpv6s.isEmpty()) {
                        CopyRow(stringResource(R.string.node_local_ipv6), null)
                    } else {
                        node.localIpv6s.forEachIndexed { index, ip ->
                            CopyRow(if (node.localIpv6s.size == 1) stringResource(R.string.node_local_ipv6) else stringResource(R.string.node_local_ipv6_n, index + 1), ip)
                        }
                    }
                    CopyRow(stringResource(R.string.node_public_ipv4), node.publicIpv4)
                    CopyRow(stringResource(R.string.node_public_ipv6), node.publicIpv6)
                }
            }
            item {
                InfoSection(stringResource(R.string.node_listen_nat)) {
                    if (node.listeners.isEmpty()) {
                        CopyRow(stringResource(R.string.node_listener), null)
                    } else {
                        node.listeners.forEachIndexed { index, url ->
                            CopyRow(if (node.listeners.size == 1) stringResource(R.string.node_listener) else stringResource(R.string.node_listener_n, index + 1), url)
                        }
                    }
                    CopyRow("UDP NAT", node.udpNatType?.let { natLabel(it) })
                    CopyRow("TCP NAT", node.tcpNatType?.let { natLabel(it) })
                    val ports = when {
                        node.stunMinPort != null && node.stunMaxPort != null -> "${node.stunMinPort}–${node.stunMaxPort}"
                        node.stunMinPort != null -> node.stunMinPort.toString()
                        node.stunMaxPort != null -> node.stunMaxPort.toString()
                        else -> null
                    }
                    CopyRow(stringResource(R.string.node_stun_port), ports)
                }
            }
            item {
                Text(
                    text = stringResource(R.string.node_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = g.muted,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun InfoSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    val g = LocalGlassColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(22.dp), GlassLevel.Regular)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = g.muted,
        )
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CopyRow(label: String, value: String?) {
    val g = LocalGlassColors.current
    val context = LocalContext.current
    val shown = value?.takeIf { it.isNotBlank() } ?: "—"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    if (value != null && value.isNotBlank()) copyToClipboard(context, label, value)
                },
            )
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = g.muted,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = shown,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
