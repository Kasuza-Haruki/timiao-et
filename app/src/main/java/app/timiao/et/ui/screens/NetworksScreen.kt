package app.timiao.et.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.timiao.et.R
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
import app.timiao.et.ui.data.PendingImport
import app.timiao.et.ui.data.UiNetwork
import app.timiao.et.ui.data.hasInstanceIdConflict
import app.timiao.et.ui.data.readTomlUri
import app.timiao.et.ui.data.resolveImportId
import app.timiao.et.ui.data.tomlErrorMessage
import app.timiao.et.ui.theme.LocalGlassColors
import kotlinx.coroutines.launch

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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var deleteTarget by remember { mutableStateOf<UiNetwork?>(null) }
    var pendingImport by remember { mutableStateOf<UiNetwork?>(null) }
    var importFailure by remember { mutableStateOf<String?>(null) }

    fun openImport(network: UiNetwork, saveAsNew: Boolean) {
        val config = network.base ?: return
        val existing = networks.map { it.id }.toSet()
        val explicitId = config.instanceId
        val id = resolveImportId(
            importedId = explicitId,
            existingIds = existing,
            saveAsNew = saveAsNew || explicitId.isNullOrBlank(),
        )
        PendingImport.config = if (id == explicitId) config else config.copy(instanceId = id)
        pendingImport = null
        onNavigate(if (saveAsNew) "network/new?open=file" else "network/$id?open=file")
    }

    fun importText(text: String) {
        scope.launch {
            vm.importToml(text).onSuccess { imported ->
                val existing = networks.map { it.id }.toSet()
                if (hasInstanceIdConflict(imported.base?.instanceId, existing)) {
                    pendingImport = imported
                } else {
                    openImport(imported, saveAsNew = false)
                }
            }.onFailure {
                importFailure = tomlErrorMessage(context, it) ?: context.getString(R.string.networks_parse_failed)
            }
        }
    }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                readTomlUri(context, uri)
                    .onSuccess(::importText)
                    .onFailure { importFailure = tomlErrorMessage(context, it) ?: context.getString(R.string.networks_read_failed) }
            }
        }
    }


    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = DockInset,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = stringResource(R.string.networks_title),
                subtitle = pluralStringResource(R.plurals.networks_count, networks.size, networks.size),
                action = {
                    OrbButton(
                        icon = Icons.Filled.Add,
                        label = stringResource(R.string.networks_new),
                        onClick = { onNavigate("network/new") },
                    )
                },
            )
        }
        item {
            Text(
                text = stringResource(R.string.networks_hint),
                style = MaterialTheme.typography.labelSmall,
                color = g.muted,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
        item {
            PillButton(
                text = stringResource(R.string.networks_import_file),
                onClick = {
                    openFile.launch(
                        arrayOf("text/*", "application/toml", "application/octet-stream", "*/*"),
                    )
                },
                secondary = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (networks.isEmpty()) {
            item {
                EmptyGlassCard(
                    text = stringResource(R.string.networks_empty),
                    actionText = stringResource(R.string.networks_new),
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
                    text = stringResource(R.string.networks_import_toml),
                    onClick = { onNavigate("network/new?open=import") },
                    secondary = true,
                    modifier = Modifier.weight(1f),
                )
                PillButton(
                    text = stringResource(R.string.networks_scan),
                    onClick = { onNavigate("network/new?open=scan") },
                    secondary = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    deleteTarget?.let { target ->
        ConfirmDialog(
            title = stringResource(R.string.networks_delete_title),
            text = stringResource(R.string.networks_delete_text, target.name),
            confirmText = stringResource(R.string.action_delete),
            onConfirm = {
                scope.launch {
                    vm.deleteNetwork(target.id)
                        .onFailure { error ->
                            importFailure = context.getString(
                                if (error is app.timiao.et.core.ConfigWriteBlockedException) R.string.storage_write_blocked else R.string.storage_save_failed,
                            )
                        }
                }
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }

    pendingImport?.let { imported ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(stringResource(R.string.networks_conflict_title)) },
            text = {
                Text(
                    stringResource(R.string.networks_conflict_text, imported.base?.instanceId.orEmpty()),
                )
            },
            confirmButton = {
                TextButton(onClick = { openImport(imported, saveAsNew = true) }) {
                    Text(stringResource(R.string.networks_save_as_new))
                }
            },
            dismissButton = {
                TextButton(onClick = { openImport(imported, saveAsNew = false) }) { Text(stringResource(R.string.action_overwrite)) }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    importFailure?.let { message ->
        AlertDialog(
            onDismissRequest = { importFailure = null },
            title = { Text(stringResource(R.string.networks_import_failed)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { importFailure = null }) { Text(stringResource(R.string.action_close)) }
            },
            containerColor = MaterialTheme.colorScheme.surface,
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
        LinkState.Connected -> stringResource(R.string.networks_state_connected)
        LinkState.Connecting -> stringResource(R.string.networks_state_connecting)
        LinkState.Error -> stringResource(R.string.networks_state_error)
        LinkState.Idle -> stringResource(R.string.networks_state_idle)
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
                    text = network.name.ifBlank { stringResource(R.string.networks_unnamed) },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (active) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.networks_current),
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
                text = network.servers.firstOrNull() ?: stringResource(R.string.networks_no_server),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = g.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = pluralStringResource(R.plurals.networks_summary, peerCount, stateText, peerCount, network.flags.count { it.value }),
                style = MaterialTheme.typography.labelSmall,
                color = stateColor,
                maxLines = 1,
            )
        }
    }
}
