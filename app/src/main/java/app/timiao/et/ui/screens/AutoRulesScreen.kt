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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import app.timiao.et.ui.components.DockInset
import app.timiao.et.ui.components.GlassDropdownRow
import app.timiao.et.ui.components.GlassLevel
import app.timiao.et.ui.components.GlassSwitchRow
import app.timiao.et.ui.components.GlassTextField
import app.timiao.et.ui.components.InfoRow
import app.timiao.et.ui.components.ScreenHeader
import app.timiao.et.ui.components.SectionLabel
import app.timiao.et.ui.components.SegmentedGlass
import app.timiao.et.ui.components.glass
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
                    "自动连接",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "按 Wi-Fi / 移动网络自动开关",
                    style = MaterialTheme.typography.labelSmall,
                    color = g.muted,
                )
            }
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = "进入自动连接",
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
        contentPadding = DockInset,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenHeader(
                title = "自动连接",
                subtitle = "按网络环境自动开关 VPN",
                onBack = onBack,
            )
        }

        item { SectionLabel("开始前") }
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
                    title = "允许 VPN",
                    detail = "自动连接需要在后台启动 VPN 服务。",
                    granted = vpnGranted,
                    actionText = if (vpnGranted) null else "去授权",
                    onAction = {
                        TimiaoVpnService.prepareIntent(context)?.let { vpnLauncher.launch(it) }
                    },
                )
                PermissionStep(
                    index = 2,
                    title = "允许通知",
                    detail = "授权提醒和断开提示会以通知显示。",
                    granted = notificationGranted,
                    actionText = if (notificationGranted || Build.VERSION.SDK_INT < 33) null else "去授权",
                    onAction = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                )
                PermissionStep(
                    index = 3,
                    title = "允许定位（读取 Wi-Fi 名称）",
                    detail = "Android 10+ 读取 Wi-Fi 名称需要定位权限。拒绝后只能使用「仅移动数据」与「总是连接」规则。",
                    granted = locationGranted,
                    actionText = if (locationGranted) "定位设置" else "去授权",
                    onAction = {
                        if (locationGranted) openLocationSettings()
                        else locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    },
                )
                if (locationGranted && snapshot.type == NetType.Wifi && snapshot.ssid == null) {
                    Text(
                        "已授权但读不到名称。请打开系统定位总开关，或保持应用在前台后再试。",
                        style = MaterialTheme.typography.labelSmall,
                        color = g.warn,
                    )
                }
            }
        }

        item {
            GlassSwitchRow(
                title = "启用自动连接",
                subtitle = "关闭后保留规则，但不再响应网络变化",
                checked = state.enabled,
                onCheckedChange = { on ->
                    if (on) AutoConnect.enable(context) else AutoConnect.disable(context)
                    refresh()
                },
            )
        }

        item { SectionLabel("当前网络") }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(22.dp), GlassLevel.Regular)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                InfoRow("类型", networkLabel(snapshot))
                InfoRow("Wi-Fi 名称", ssidLabel(snapshot))
                InfoRow("定位权限", if (locationGranted) "已授权" else "未授权")
                InfoRow("规则结果", if (state.enabled) decisionLabel(decision, networks, activeName) else "自动连接已关闭")
            }
        }

        item { SectionLabel("全局规则") }
        item {
            GlobalRuleCard(
                rule = state.rules.firstOrNull { it.isGlobal },
                activeName = activeName,
                onChange = { store.upsert(it) },
            )
        }

        item { SectionLabel("按网络") }
        if (networks.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glass(RoundedCornerShape(22.dp), GlassLevel.Regular)
                        .padding(18.dp),
                ) {
                    Text(
                        "还没有网络。先在「网络」页创建，再回到这里设置规则。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = g.muted,
                    )
                }
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
                text = "规则按「网络规则 → 全局规则」匹配，连接优先于断开。Android 12+ 可能阻止后台自动启动，此时会发一条通知，点按即可连接。",
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
                "已完成",
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
            "作用于当前网络：${activeName ?: "上次使用的网络"}",
            style = MaterialTheme.typography.labelSmall,
            color = g.muted,
        )
        GlassSwitchRow(
            title = "启用全局规则",
            subtitle = "没有匹配的网络规则时生效",
            checked = current.enabled,
            onCheckedChange = { onChange(current.copy(enabled = it)) },
        )
        SegmentedGlass(
            options = AutoCondition.entries.map { it.shortLabel },
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
                label = "Wi-Fi 名称（逗号分隔）",
                placeholder = "例如：Home, Office",
            )
        }
        GlassSwitchRow(
            title = "断开后保留通知",
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(22.dp), GlassLevel.Regular)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GlassSwitchRow(
            title = network.name.ifBlank { "未命名网络" },
            subtitle = if (enabled) current.condition.summary(current) else "未启用自动规则",
            checked = enabled,
            onCheckedChange = { on -> onChange(current.copy(enabled = on)) },
        )
        if (enabled) {
            GlassDropdownRow(
                label = "连接条件",
                current = current.condition,
                options = AutoCondition.entries,
                optionLabel = { it.label },
                onSelect = { onChange(current.copy(condition = it)) },
            )
            if (current.condition.needsSsid) {
                GlassTextField(
                    value = formatWifiNames(current.wifiNames),
                    onValueChange = { onChange(current.copy(wifiNames = parseWifiNames(it))) },
                    label = "Wi-Fi 名称（逗号分隔）",
                    placeholder = "例如：Home, Office",
                )
            }
            GlassSwitchRow(
                title = "断开后保留通知",
                checked = current.keepNotification,
                onCheckedChange = { onChange(current.copy(keepNotification = it)) },
            )
        }
        if (!enabled) {
            Text(
                "打开开关后，这条网络会按所选条件自动连接。",
                style = MaterialTheme.typography.labelSmall,
                color = g.muted,
            )
        }
    }
}

private fun AutoCondition.summary(rule: AutoRule): String = when (this) {
    AutoCondition.Always -> "总是连接"
    AutoCondition.CellularOnly -> "仅移动数据时连接"
    AutoCondition.WifiOnly -> "仅这些 Wi-Fi：${formatWifiNames(rule.wifiNames).ifBlank { "未设置" }}"
    AutoCondition.ExceptWifi -> "排除这些 Wi-Fi：${formatWifiNames(rule.wifiNames).ifBlank { "未设置" }}"
}

private fun notificationsGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

private fun networkLabel(snapshot: NetworkSnapshot): String = when (snapshot.type) {
    NetType.None -> "无网络"
    NetType.Wifi -> "Wi-Fi"
    NetType.Cellular -> "移动数据"
    NetType.Other -> "其他网络"
}

private fun ssidLabel(snapshot: NetworkSnapshot): String = when {
    snapshot.type != NetType.Wifi -> "—"
    !snapshot.locationGranted -> "未授权定位"
    snapshot.ssid == null -> "读取不到"
    else -> snapshot.ssid
}

private fun decisionLabel(
    decision: AutoDecision,
    networks: List<UiNetwork>,
    activeName: String?,
): String = when (decision) {
    is AutoDecision.Connect -> {
        val name = decision.networkId
            ?.let { id -> networks.firstOrNull { it.id == id }?.name }
            ?: activeName
            ?: "当前网络"
        "连接「$name」"
    }
    is AutoDecision.Disconnect -> "断开自动连接"
    is AutoDecision.Skip -> "保持现状（${decision.reason.label}）"
}
