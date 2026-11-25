package app.timiao.et.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.timiao.et.R
import app.timiao.et.auto.AutoCondition
import app.timiao.et.auto.AutoConnect
import app.timiao.et.auto.AutoDecision
import app.timiao.et.auto.AutoNetwork
import app.timiao.et.auto.AutoRule
import app.timiao.et.auto.AutoRulesStore
import app.timiao.et.auto.NetType
import app.timiao.et.auto.NetworkSnapshot
import app.timiao.et.auto.evaluate
import app.timiao.et.auto.formatWifiNames
import app.timiao.et.auto.parseWifiNames
import app.timiao.et.service.TimiaoVpnService
import app.timiao.et.ui.AppViewModel
import app.timiao.et.ui.components.ClickableGlassRow
import app.timiao.et.ui.components.EmptyGlassCard
import app.timiao.et.ui.components.GlassDropdownRow
import app.timiao.et.ui.components.GlassLevel
import app.timiao.et.ui.components.GlassSwitchRow
import app.timiao.et.ui.components.GlassTextField
import app.timiao.et.ui.components.InfoRow
import app.timiao.et.ui.components.ScreenHeader
import app.timiao.et.ui.components.SectionLabel
import app.timiao.et.ui.components.SegmentedGlass
import app.timiao.et.ui.components.glass
import app.timiao.et.ui.components.pageInset
import app.timiao.et.ui.data.UiNetwork
import app.timiao.et.ui.theme.LocalGlassColors
import kotlinx.coroutines.delay

const val AUTO_RULES_ROUTE = "auto_rules"

/**
 * Settings entry row. The host screen owns navigation: add
 * `AutoRulesEntryRow { nav.navigate(AUTO_RULES_ROUTE) }` where it belongs.
 */
@Composable
fun AutoRulesEntryRow(onClick: () -> Unit) {
    val g = LocalGlassColors.current
    ClickableGlassRow(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.auto_entry_title),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.auto_entry_sub),
                    style = MaterialTheme.typography.labelSmall,
                    color = g.muted,
                )
            }
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(R.string.auto_entry_title),
                tint = g.muted,
                modifier = Modifier.size(20.dp).rotate(-90f),
            )
        }
    }
}

@Composable
fun AutoRulesScreen(vm: AppViewModel, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { AutoRulesStore.get(context) }
    val state by store.state.collectAsStateWithLifecycle()
    val networks by vm.networks.collectAsStateWithLifecycle()
    val activeId by vm.activeNetworkId.collectAsStateWithLifecycle()
    val g = LocalGlassColors.current

    var locationGranted by remember { mutableStateOf(AutoNetwork.hasLocationPermission(context)) }
    var notificationGranted by remember { mutableStateOf(notificationsGranted(context)) }
    var vpnGranted by remember { mutableStateOf(TimiaoVpnService.prepareIntent(context) == null) }
    var snapshot by remember { mutableStateOf(AutoNetwork.snapshot(context)) }

    fun refresh() {
        locationGranted = AutoNetwork.hasLocationPermission(context)
        notificationGranted = notificationsGranted(context)
        vpnGranted = TimiaoVpnService.prepareIntent(context) == null
        snapshot = AutoNetwork.snapshot(context)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Opening the app re-arms the system callback after a force-stop or update.
    LaunchedEffect(state.enabled) {
        if (state.enabled) {
            AutoConnect.ensureRegistered(context)
            AutoConnect.ensureInProcess(context)
        }
    }

    // Keep the "current network" card live while this screen is open.
    LaunchedEffect(Unit) {
        while (true) {
            delay(3_000)
            snapshot = AutoNetwork.snapshot(context)
        }
    }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        locationGranted = AutoNetwork.hasLocationPermission(context)
        snapshot = AutoNetwork.snapshot(context)
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { notificationGranted = notificationsGranted(context) }
    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        vpnGranted = TimiaoVpnService.prepareIntent(context) == null
        refresh()
    }

    fun openLocationSettings() {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    val decision = remember(state.rules, state.enabled, snapshot) {
        evaluate(snapshot, state.rules)
    }
    val activeName = networks.firstOrNull { it.id == activeId }?.name

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = pageInset(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenHeader(
                title = stringResource(R.string.auto_title),
                subtitle = stringResource(R.string.auto_subtitle),
                onBack = onBack,
            )
        }

        item { SectionLabel(stringResource(R.string.auto_section_before)) }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(22.dp), GlassLevel.Regular)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PermissionStep(
                    index = 1,
                    title = stringResource(R.string.auto_perm_vpn),
                    detail = stringResource(R.string.auto_perm_vpn_detail),
                    granted = vpnGranted,
                    actionText = if (vpnGranted) null else stringResource(R.string.auto_go_grant),
                    onAction = {
                        TimiaoVpnService.prepareIntent(context)?.let { vpnLauncher.launch(it) }
                    },
                )
                PermissionStep(
                    index = 2,
                    title = stringResource(R.string.auto_perm_notifications),
                    detail = stringResource(R.string.auto_perm_notifications_detail),
                    granted = notificationGranted,
                    actionText = if (notificationGranted || Build.VERSION.SDK_INT < 33) null else stringResource(R.string.auto_go_grant),
                    onAction = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                )
                PermissionStep(
                    index = 3,
                    title = stringResource(R.string.auto_perm_location),
                    detail = stringResource(R.string.auto_perm_location_detail),
                    granted = locationGranted,
                    actionText = if (locationGranted) stringResource(R.string.auto_location_settings) else stringResource(R.string.auto_go_grant),
                    onAction = {
                        if (locationGranted) openLocationSettings()
                        else locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    },
                )
                if (locationGranted && snapshot.type == NetType.Wifi && snapshot.ssid == null) {
                    Text(
                        stringResource(R.string.auto_location_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = g.warn,
                    )
                }
            }
        }

        item {
            GlassSwitchRow(
                title = stringResource(R.string.auto_enable),
                subtitle = stringResource(R.string.auto_enable_sub),
                checked = state.enabled,
                onCheckedChange = { on ->
                    if (on) AutoConnect.enable(context) else AutoConnect.disable(context)
                    refresh()
                },
            )
        }

        item { SectionLabel(stringResource(R.string.auto_section_current)) }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(22.dp), GlassLevel.Regular)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                InfoRow(stringResource(R.string.auto_type), networkLabel(snapshot))
                InfoRow(stringResource(R.string.auto_wifi_name), ssidLabel(snapshot))
                InfoRow(stringResource(R.string.auto_location_permission), stringResource(if (locationGranted) R.string.auto_granted else R.string.auto_not_granted))
                InfoRow(stringResource(R.string.auto_rule_result), if (state.enabled) decisionLabel(decision, networks, activeName) else stringResource(R.string.auto_disabled))
            }
        }

        item { SectionLabel(stringResource(R.string.auto_section_global)) }
        item {
            GlobalRuleCard(
                rule = state.rules.firstOrNull { it.isGlobal },
                activeName = activeName,
                onChange = { store.upsert(it) },
            )
        }

        item { SectionLabel(stringResource(R.string.auto_section_per_network)) }
        if (networks.isEmpty()) {
            item {
                EmptyGlassCard(stringResource(R.string.auto_empty))
            }
        } else {
            items(networks.size) { index ->
                val network = networks[index]
                NetworkRuleCard(
                    network = network,
                    rule = state.rules.firstOrNull { it.networkId == network.id },
                    onChange = { store.upsert(it) },
                )
            }
        }

        item {
            Text(
                text = stringResource(R.string.auto_footer),
                style = MaterialTheme.typography.labelSmall,
                color = g.muted,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun PermissionStep(
    index: Int,
    title: String,
    detail: String,
    granted: Boolean,
    actionText: String?,
    onAction: () -> Unit,
) {
    val g = LocalGlassColors.current
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(
                    if (granted) MaterialTheme.colorScheme.primary
                    else g.muted.copy(alpha = 0.25f),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$index",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black),
                color = if (granted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(detail, style = MaterialTheme.typography.labelSmall, color = g.muted)
        }
        Spacer(Modifier.width(8.dp))
        if (actionText != null) {
            SmallAction(actionText, onAction)
        } else {
            Text(
                stringResource(R.string.auto_done),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = g.good,
            )
        }
    }
}

@Composable
private fun SmallAction(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun GlobalRuleCard(
    rule: AutoRule?,
    activeName: String?,
    onChange: (AutoRule) -> Unit,
) {
    val g = LocalGlassColors.current
    val current = rule ?: AutoRule(networkId = null, enabled = false)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(22.dp), GlassLevel.Regular)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(R.string.auto_global_target, activeName ?: stringResource(R.string.auto_last_network)),
            style = MaterialTheme.typography.labelSmall,
            color = g.muted,
        )
        GlassSwitchRow(
            title = stringResource(R.string.auto_global_enable),
            subtitle = stringResource(R.string.auto_global_enable_sub),
            checked = current.enabled,
            onCheckedChange = { onChange(current.copy(enabled = it)) },
        )
        SegmentedGlass(
            options = AutoCondition.entries.map { stringResource(it.shortLabelRes) },
            selected = AutoCondition.entries.indexOf(current.condition),
            onSelect = {
                onChange(current.copy(condition = AutoCondition.entries[it], enabled = true))
            },
        )
        if (current.condition.needsSsid) {
            GlassTextField(
                value = formatWifiNames(current.wifiNames),
                onValueChange = {
                    onChange(current.copy(wifiNames = parseWifiNames(it), enabled = true))
                },
                label = stringResource(R.string.auto_wifi_names_label),
                placeholder = stringResource(R.string.auto_wifi_names_placeholder),
            )
        }
        GlassSwitchRow(
            title = stringResource(R.string.auto_keep_notification),
            checked = current.keepNotification,
            onCheckedChange = { onChange(current.copy(keepNotification = it, enabled = true)) },
        )
    }
}

@Composable
private fun NetworkRuleCard(
    network: UiNetwork,
    rule: AutoRule?,
    onChange: (AutoRule) -> Unit,
) {
    val g = LocalGlassColors.current
    val enabled = rule?.enabled == true
    val current = rule ?: AutoRule(networkId = network.id, enabled = false)
    val conditionLabels = AutoCondition.entries.associateWith { stringResource(it.labelRes) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(22.dp), GlassLevel.Regular)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GlassSwitchRow(
            title = network.name.ifBlank { stringResource(R.string.networks_unnamed) },
            subtitle = if (enabled) current.condition.summary(current) else stringResource(R.string.auto_rule_off),
            checked = enabled,
            onCheckedChange = { on -> onChange(current.copy(enabled = on)) },
        )
        if (enabled) {
            GlassDropdownRow(
                label = stringResource(R.string.auto_condition),
                current = current.condition,
                options = AutoCondition.entries,
                optionLabel = { conditionLabels[it].orEmpty() },
                onSelect = { onChange(current.copy(condition = it)) },
            )
            if (current.condition.needsSsid) {
                GlassTextField(
                    value = formatWifiNames(current.wifiNames),
                    onValueChange = { onChange(current.copy(wifiNames = parseWifiNames(it))) },
                    label = stringResource(R.string.auto_wifi_names_label),
                    placeholder = stringResource(R.string.auto_wifi_names_placeholder),
                )
            }
            GlassSwitchRow(
                title = stringResource(R.string.auto_keep_notification),
                checked = current.keepNotification,
                onCheckedChange = { onChange(current.copy(keepNotification = it)) },
            )
        }
        if (!enabled) {
            Text(
                stringResource(R.string.auto_rule_hint),
                style = MaterialTheme.typography.labelSmall,
                color = g.muted,
            )
        }
    }
}

@Composable
private fun AutoCondition.summary(rule: AutoRule): String {
    val names = formatWifiNames(rule.wifiNames).ifBlank { stringResource(R.string.auto_not_set) }
    return when (this) {
        AutoCondition.Always -> stringResource(R.string.auto_summary_always)
        AutoCondition.CellularOnly -> stringResource(R.string.auto_summary_cellular)
        AutoCondition.WifiOnly -> stringResource(R.string.auto_summary_wifi, names)
        AutoCondition.ExceptWifi -> stringResource(R.string.auto_summary_except_wifi, names)
    }
}

private fun notificationsGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

@Composable
private fun networkLabel(snapshot: NetworkSnapshot): String = when (snapshot.type) {
    NetType.None -> stringResource(R.string.auto_type_none)
    NetType.Wifi -> stringResource(R.string.auto_type_wifi)
    NetType.Cellular -> stringResource(R.string.auto_type_cellular)
    NetType.Other -> stringResource(R.string.auto_type_other)
}

@Composable
private fun ssidLabel(snapshot: NetworkSnapshot): String = when {
    snapshot.type != NetType.Wifi -> "—"
    !snapshot.locationGranted -> stringResource(R.string.auto_location_denied)
    snapshot.ssid == null -> stringResource(R.string.auto_ssid_unreadable)
    else -> snapshot.ssid
}

@Composable
private fun decisionLabel(
    decision: AutoDecision,
    networks: List<UiNetwork>,
    activeName: String?,
): String = when (decision) {
    is AutoDecision.Connect -> {
        val name = decision.networkId
            ?.let { id -> networks.firstOrNull { it.id == id }?.name }
            ?: activeName
            ?: stringResource(R.string.auto_current_network)
        stringResource(R.string.auto_decision_connect, name)
    }
    is AutoDecision.Disconnect -> stringResource(R.string.auto_decision_disconnect)
    is AutoDecision.Skip -> stringResource(R.string.auto_decision_skip, stringResource(decision.reason.labelRes))
}
