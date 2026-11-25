package app.timiao.et.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.timiao.et.split.AppIconCache
import app.timiao.et.split.InstalledApp
import app.timiao.et.split.InstalledAppsLoader
import app.timiao.et.split.SplitMode
import app.timiao.et.split.SplitPolicy
import app.timiao.et.split.SplitStore
import app.timiao.et.ui.components.AvatarBadge
import app.timiao.et.ui.components.ClickableGlassRow
import app.timiao.et.ui.components.EmptyGlassCard
import app.timiao.et.ui.components.GlassLevel
import app.timiao.et.ui.components.GlassSearchField
import app.timiao.et.ui.components.GlassSwitchRow
import app.timiao.et.ui.components.GlassTextField
import app.timiao.et.ui.components.ScreenHeader
import app.timiao.et.ui.components.SectionLabel
import app.timiao.et.ui.components.SegmentedGlass
import app.timiao.et.ui.components.glass
import app.timiao.et.ui.components.pageInset
import app.timiao.et.ui.theme.LocalGlassColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Routes for the app-split page; wire these in AppNav. */
object SplitRoutes {
    const val PATTERN = "split/{networkId}"
    fun of(networkId: String): String = "split/$networkId"
}

/**
 * Entry row for NetworkEditScreen. The navigation owner passes the real click handler.
 * Re-reads the stored policy on resume so the summary is fresh after returning.
 */
@Composable
fun AppSplitEntryRow(networkId: String, onClick: () -> Unit) {
    val policy by rememberPolicyOnResume(networkId)
    val g = LocalGlassColors.current
    ClickableGlassRow(onClick = onClick, shape = RoundedCornerShape(18.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("按应用分流", style = MaterialTheme.typography.labelSmall, color = g.muted)
                Text(
                    text = policy.summary(),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.Filled.ArrowForward,
                contentDescription = "打开",
                tint = g.muted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
fun AppSplitScreen(networkId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val pm = context.packageManager
    val store = remember(networkId) { SplitStore(context) }
    val iconCache = remember { AppIconCache() }
    val g = LocalGlassColors.current

    var policy by remember(networkId) { mutableStateOf(store.load(networkId)) }
    var query by rememberSaveable(networkId) { mutableStateOf("") }
    var showSystem by rememberSaveable(networkId) { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    fun update(next: SplitPolicy) {
        policy = next
        store.save(networkId, next)
    }

    LaunchedEffect(showSystem) {
        loading = true
        apps = withContext(Dispatchers.IO) {
            InstalledAppsLoader(pm).load(
                includeSystem = showSystem,
                excludePackage = context.packageName,
            )
        }
        loading = false
    }

    val filtered = remember(apps, query) {
        val q = query.trim()
        if (q.isEmpty()) apps else apps.filter {
            it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentPadding = pageInset(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenHeader(
                title = "按应用分流",
                subtitle = "改动在下次连接后生效",
                onBack = onBack,
            )
        }
        item { SectionLabel("模式") }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(22.dp), GlassLevel.Regular)
                    .padding(12.dp),
            ) {
                SegmentedGlass(
                    options = SplitMode.entries.map { it.label },
                    selected = policy.mode.ordinal,
                    onSelect = { update(policy.copy(mode = SplitMode.entries[it])) },
                )
            }
        }
        item {
            Text(
                text = when (policy.mode) {
                    SplitMode.All -> "所有应用都通过 EasyTier 访问网络。"
                    SplitMode.Allow -> "只有勾选的应用通过 EasyTier，其余应用直连。"
                    SplitMode.Deny -> "勾选的应用直连，其余应用通过 EasyTier。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = g.muted,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }

        if (policy.mode == SplitMode.All) {
            item { EmptyGlassCard("当前模式无需选择应用。") }
        } else {
            item {
                GlassSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "搜索应用名或包名",
                )
            }
            item {
                GlassSwitchRow(
                    title = "显示系统应用",
                    subtitle = "通常不需要为系统组件分流",
                    checked = showSystem,
                    onCheckedChange = { showSystem = it },
                )
            }
            item {
                SectionLabel(
                    if (policy.mode == SplitMode.Allow) {
                        "已选 ${policy.packages.size} 个应用走 EasyTier"
                    } else {
                        "已选 ${policy.packages.size} 个应用直连"
                    },
                )
            }
            when {
                loading -> item { EmptyGlassCard("正在读取应用…", loading = true) }
                filtered.isEmpty() -> item {
                    EmptyGlassCard(
                        if (!showSystem && apps.isEmpty()) {
                            "未找到第三方应用，可打开上方的“显示系统应用”"
                        } else {
                            "没有匹配的应用"
                        },
                    )
                }
                else -> items(filtered, key = { it.packageName }) { app ->
                    AppRow(
                        app = app,
                        checked = app.packageName in policy.packages,
                        iconCache = iconCache,
                        onToggle = { checked ->
                            update(
                                policy.copy(
                                    packages = if (checked) {
                                        policy.packages + app.packageName
                                    } else {
                                        policy.packages - app.packageName
                                    },
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    checked: Boolean,
    iconCache: AppIconCache,
    onToggle: (Boolean) -> Unit,
) {
    val pm = LocalContext.current.packageManager
    val g = LocalGlassColors.current
    var icon by remember(app.packageName) { mutableStateOf(iconCache.cached(app.packageName)) }
    LaunchedEffect(app.packageName) {
        if (icon == null) icon = iconCache.load(pm, app.packageName)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(18.dp), GlassLevel.Subtle)
            .clickable { onToggle(!checked) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(icon, app.label)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.labelSmall,
                color = g.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = g.muted.copy(alpha = 0.35f),
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun AppIcon(icon: ImageBitmap?, label: String) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            AvatarBadge(name = label, size = 36)
        }
    }
}

@Composable
private fun rememberPolicyOnResume(networkId: String): androidx.compose.runtime.State<SplitPolicy> {
    val context = LocalContext.current
    val store = remember(networkId) { SplitStore(context) }
    val state = remember(networkId) { mutableStateOf(store.load(networkId)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, networkId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state.value = store.load(networkId)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return state
}
