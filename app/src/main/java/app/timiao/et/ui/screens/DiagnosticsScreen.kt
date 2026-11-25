package app.timiao.et.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.timiao.et.core.NetworkConfig
import app.timiao.et.core.diag.DeviceProbe
import app.timiao.et.core.diag.DiagItem
import app.timiao.et.core.diag.DiagSeverity
import app.timiao.et.core.diag.DiagStrings
import app.timiao.et.core.diag.DiagnosticInput
import app.timiao.et.core.diag.DiagnosticReport
import app.timiao.et.core.diag.ChineseDiagStrings
import app.timiao.et.core.diag.DiagnosticsEngine
import app.timiao.et.R
import app.timiao.et.ui.AppViewModel
import app.timiao.et.ui.components.GlassBackdrop
import app.timiao.et.ui.components.GlassLevel
import app.timiao.et.ui.components.OrbButton
import app.timiao.et.ui.components.PillButton
import app.timiao.et.ui.components.ScreenHeader
import app.timiao.et.ui.components.SectionLabel
import app.timiao.et.ui.components.glass
import app.timiao.et.ui.components.pageInset
import app.timiao.et.ui.data.AppSettings
import app.timiao.et.ui.data.UiNetwork
import app.timiao.et.ui.data.toConfig
import app.timiao.et.ui.theme.LocalGlassColors
import app.timiao.et.ui.theme.LocalReducedMotion
import app.timiao.et.ui.theme.TimiaoTheme
import app.timiao.et.update.UpdateResult
import com.easytier.jni.EasyTierJNI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Android implementation of the injectable device checks. */
class AndroidDeviceProbe(private val context: Context) : DeviceProbe {
    override fun hasVpnPermission(): Boolean = try {
        VpnService.prepare(context) == null
    } catch (_: Exception) {
        false
    }

    override fun isIgnoringBatteryOptimizations(): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return try {
            power.isIgnoringBatteryOptimizations(context.packageName)
        } catch (_: Exception) {
            true
        }
    }

    override fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    override fun hasUnderlyingNetwork(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        return try {
            manager.allNetworks.any { network ->
                val capabilities = manager.getNetworkCapabilities(network) ?: return@any false
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        } catch (_: Exception) {
            // If the state cannot be read, do not claim the device is offline.
            true
        }
    }
}

/** Optional kernel payload. Kept injectable so the screen and previews do not require the .so. */
interface NetworkInfoCollector {
    suspend fun collect(): String?
}

object JniNetworkInfoCollector : NetworkInfoCollector {
    override suspend fun collect(): String? = withContext(Dispatchers.IO) {
        runCatching { EasyTierJNI.collectNetworkInfos(8) }.getOrNull()
    }
}

/**
 * One-tap diagnosis. [configProvider] lets the wiring read the full stored config
 * (with flags); by default it rebuilds one from the editable UI model.
 */
@Composable
fun DiagnosticsScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    configProvider: suspend (UiNetwork) -> NetworkConfig? = { it.toConfig() },
    infoCollector: NetworkInfoCollector = JniNetworkInfoCollector,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val network by vm.activeNetwork.collectAsStateWithLifecycle()
    val status by vm.activeStatus.collectAsStateWithLifecycle()
    var report by remember { mutableStateOf<DiagnosticReport?>(null) }
    var running by remember { mutableStateOf(false) }
    val diagStrings = remember(context) { AndroidDiagStrings(context) }
    val engine = remember(diagStrings) { DiagnosticsEngine(strings = diagStrings) }
    val deviceProbe = remember(context) { AndroidDeviceProbe(context) }

    val runDiagnostics: () -> Unit = {
        if (!running) {
            running = true
            scope.launch {
                try {
                    val current = network
                    val config = current?.let { configProvider(it) }
                    val raw = infoCollector.collect()
                    report = engine.run(DiagnosticInput(config, status, raw), deviceProbe)
                } finally {
                    running = false
                }
            }
        }
    }

    DiagnosticsContent(
        report = report,
        running = running,
        onRun = runDiagnostics,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
internal fun DiagnosticsContent(
    report: DiagnosticReport?,
    running: Boolean,
    onRun: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    updateSection: @Composable () -> Unit = { UpdateCard() },
) {
    val context = LocalContext.current
    val diagStrings = remember(context) { AndroidDiagStrings(context) }
    var expanded by remember { mutableStateOf(emptySet<String>()) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = pageInset(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenHeader(
                title = stringResource(R.string.diag_title),
                subtitle = stringResource(R.string.diag_subtitle),
                onBack = onBack,
                action = { OrbButton(Icons.Filled.Refresh, stringResource(R.string.diag_rerun), onRun) },
            )
        }
        item {
            OverallCard(
                report = report,
                running = running,
                onRun = onRun,
                onCopy = { copyReport(context, report, diagStrings) },
                strings = diagStrings,
            )
        }
        if (report != null) {
            items(report.items, key = { it.id }) { item ->
                DiagItemCard(
                    item = item,
                    expanded = item.id in expanded,
                    onToggle = {
                        expanded = if (item.id in expanded) expanded - item.id else expanded + item.id
                    },
                )
            }
        }
        item { Spacer(Modifier.height(2.dp)) }
        item { SectionLabel(stringResource(R.string.diag_update_section)) }
        item { updateSection() }
    }
}

@Composable
private fun OverallCard(
    report: DiagnosticReport?,
    running: Boolean,
    onRun: () -> Unit,
    onCopy: () -> Unit,
    strings: DiagStrings,
) {
    val g = LocalGlassColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(26.dp), GlassLevel.Strong)
            .padding(16.dp),
    ) {
        when {
            running -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            stringResource(R.string.diag_running_title),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            stringResource(R.string.diag_running_sub),
                            style = MaterialTheme.typography.bodySmall,
                            color = g.muted,
                        )
                    }
                }
            }
            report == null -> {
                Image(
                    painter = painterResource(app.timiao.et.R.drawable.ic_empty_cat),
                    contentDescription = null,
                    modifier = Modifier.size(width = 64.dp, height = 56.dp),
                    alpha = 0.92f,
                )
                Text(
                    stringResource(R.string.diag_empty_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.diag_empty_text),
                    style = MaterialTheme.typography.bodySmall,
                    color = g.muted,
                    modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
                )
                PillButton(stringResource(R.string.diag_start), onRun)
            }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = severityIcon(report.overall),
                        contentDescription = severityLabel(report.overall),
                        modifier = Modifier.size(30.dp),
                        tint = severityColor(report.overall),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            report.headline(strings),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(R.string.diag_counts, report.items.size, report.warnCount, report.failCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = g.muted,
                        )
                    }
                }
                Text(
                    stringResource(R.string.diag_generated, reportTime(report.generatedAtEpochMs)),
                    style = MaterialTheme.typography.labelSmall,
                    color = g.muted,
                    modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(stringResource(R.string.diag_rerun), onRun, Modifier.weight(1f))
                    PillButton(stringResource(R.string.diag_copy_report), onCopy, Modifier.weight(1f), secondary = true)
                }
            }
        }
    }
}

@Composable
private fun DiagItemCard(
    item: DiagItem,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val g = LocalGlassColors.current
    val reduced = LocalReducedMotion.current
    val color = severityColor(item.severity)
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = if (reduced) snap() else tween(180),
        label = "diag-chevron",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(20.dp), GlassLevel.Subtle)
            .clickable(onClick = onToggle)
            .padding(horizontal = 13.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = severityIcon(item.severity),
                contentDescription = severityLabel(item.severity),
                modifier = Modifier.size(20.dp),
                tint = color,
            )
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.summary,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
                    maxLines = if (expanded) 6 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(if (expanded) R.string.action_collapse else R.string.action_expand),
                modifier = Modifier
                    .size(18.dp)
                    .rotate(rotation),
                tint = g.muted,
            )
        }
        if (expanded) {
            item.advice?.takeIf { it.isNotBlank() }?.let { advice ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.diag_advice, advice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(color.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
            if (item.details.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                item.details.forEach { line ->
                    Text(
                        text = stringResource(R.string.diag_detail_bullet, line),
                        style = MaterialTheme.typography.labelSmall,
                        color = g.muted,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun severityColor(severity: DiagSeverity): Color = when (severity) {
    DiagSeverity.Pass -> LocalGlassColors.current.good
    DiagSeverity.Warn -> LocalGlassColors.current.warn
    DiagSeverity.Fail -> MaterialTheme.colorScheme.error
}

private fun severityIcon(severity: DiagSeverity): ImageVector = when (severity) {
    DiagSeverity.Pass -> Icons.Filled.CheckCircle
    DiagSeverity.Warn -> Icons.Filled.Warning
    DiagSeverity.Fail -> Icons.Filled.Close
}

private fun reportTime(epochMs: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(epochMs))

private fun copyReport(context: Context, report: DiagnosticReport?, strings: DiagStrings) {
    if (report == null) return
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(
        ClipData.newPlainText(context.getString(R.string.diag_clipboard_label), report.plainText(strings)),
    )
    Toast.makeText(context, context.getString(R.string.diag_copied), Toast.LENGTH_SHORT).show()
}

/** Resolves a diagnostic text key through this app's string resources. */
internal class AndroidDiagStrings(private val context: Context) : DiagStrings {
    override fun get(key: String, vararg args: Any?): String {
        val id = context.resources.getIdentifier(key, "string", context.packageName)
        if (id == 0) return key
        return if (args.isEmpty()) {
            context.getString(id)
        } else {
            context.getString(id, *args.map { it ?: "" }.toTypedArray())
        }
    }
}

@Composable
private fun severityLabel(severity: DiagSeverity): String = stringResource(
    when (severity) {
        DiagSeverity.Pass -> R.string.diag_sev_pass
        DiagSeverity.Warn -> R.string.diag_sev_warn
        DiagSeverity.Fail -> R.string.diag_sev_fail
    },
)

@Preview(showBackground = true, widthDp = 390, heightDp = 1600)
@Preview(
    showBackground = true,
    widthDp = 390,
    heightDp = 1600,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun DiagnosticsPreview() {
    TimiaoTheme(AppSettings()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            GlassBackdrop()
            DiagnosticsContent(
                report = sampleReport(),
                running = false,
                onRun = {},
                onBack = {},
                updateSection = {
                    UpdateCardContent(
                        appResult = UpdateResult.Unknown,
                        kernelResult = UpdateResult.UpToDate("2.7.1"),
                        currentVersion = "0.1.0",
                        currentKernel = "v2.7.1",
                        loading = false,
                        onRefresh = {},
                    )
                },
            )
        }
    }
}

private fun sampleReport(): DiagnosticReport = DiagnosticReport(
    items = listOf(
        DiagItem(
            id = "config_servers",
            title = ChineseDiagStrings.get("diag_servers_title"),
            severity = DiagSeverity.Pass,
            summary = ChineseDiagStrings.get("diag_servers_configured_summary", 1),
            details = listOf("tcp://203.0.113.9:11010 (TCP)"),
        ),
        DiagItem(
            id = "reachability",
            title = ChineseDiagStrings.get("diag_reach_title"),
            severity = DiagSeverity.Warn,
            summary = ChineseDiagStrings.get("diag_reach_partial_summary", 0, 1),
            advice = ChineseDiagStrings.get("diag_reach_unreachable_advice"),
            details = listOf("tcp://203.0.113.9:11010 — timeout"),
        ),
        DiagItem(
            id = "status_path",
            title = ChineseDiagStrings.get("diag_path_title"),
            severity = DiagSeverity.Fail,
            summary = ChineseDiagStrings.get("diag_path_all_relay_summary", 2),
            advice = ChineseDiagStrings.get("diag_path_symmetric_advice"),
            details = listOf("NAT: Symmetric", "laptop relay(2), tcp"),
        ),
        DiagItem(
            id = "device_battery",
            title = ChineseDiagStrings.get("diag_device_battery_title"),
            severity = DiagSeverity.Warn,
            summary = ChineseDiagStrings.get("diag_device_battery_fail"),
            advice = ChineseDiagStrings.get("diag_device_battery_advice"),
        ),
    ),
    generatedAtEpochMs = 1_764_000_000_000,
)
