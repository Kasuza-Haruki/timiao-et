package app.timiao.et.ui.screens

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.timiao.et.core.LinkState
import app.timiao.et.traffic.NetworkKind
import app.timiao.et.traffic.TrafficDecision
import app.timiao.et.traffic.TrafficMath
import app.timiao.et.traffic.TrafficRecorder
import app.timiao.et.traffic.TrafficStore
import app.timiao.et.traffic.TrafficUsage
import app.timiao.et.ui.AppViewModel
import app.timiao.et.ui.components.DockInset
import app.timiao.et.ui.components.GlassDropdownRow
import app.timiao.et.ui.components.GlassLevel
import app.timiao.et.ui.components.GlassSwitchRow
import app.timiao.et.ui.components.ScreenHeader
import app.timiao.et.ui.components.SectionLabel
import app.timiao.et.ui.components.glass
import app.timiao.et.ui.theme.LocalGlassColors
import app.timiao.et.ui.util.formatBytes
import java.io.File
import java.time.LocalDate
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Traffic dashboard: today/month totals split by network kind, a 7-day total bar chart,
 * and the mobile-data policy (block, or remind above N MB).
 *
 * Callers that want recording to continue in the background should construct a
 * [TrafficRecorder], feed it engine status, and pass it in. Otherwise the screen owns
 * a recorder and records while it is visible.
 */
@Composable
fun TrafficScreen(
    vm: AppViewModel,
    recorder: TrafficRecorder? = null,
) {
    val context = LocalContext.current
    val store = remember(context) {
        TrafficStore(File(context.filesDir, "traffic-stats.txt"))
    }
    val ownRecorder = remember(store) { TrafficRecorder(store) }
    val traffic = recorder ?: ownRecorder
    val data by traffic.state.collectAsStateWithLifecycle()
    val kind = rememberNetworkKind()
    val g = LocalGlassColors.current

    LaunchedEffect(traffic, kind) {
        var active = emptySet<String>()
        vm.statuses.collect { statuses ->
            val connected = statuses.filterValues { it.state == LinkState.Connected }
            val current = connected.keys
            val gone = active - current
            active = current
            withContext(Dispatchers.IO) {
                gone.forEach { traffic.release(it) }
                connected.forEach { (id, status) ->
                    traffic.record(id, kind, status.peers)
                }
            }
        }
    }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }
    val today = remember(data, now) { traffic.today(now) }
    val month = remember(data, now) { traffic.month(now) }
    val days = remember(data, now) { traffic.lastDays(7, now) }
    val policy = data.policy
    val monthMobile = month.byKind[NetworkKind.MOBILE]?.total ?: 0L
    val decision = TrafficMath.decide(kind, monthMobile, policy)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = DockInset,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { ScreenHeader(title = "流量", subtitle = "按网络类型统计，保存在本机") }

        item { SectionLabel("当前网络") }
        item {
            val (title, detail, color) = when (decision) {
                TrafficDecision.Block -> Triple(
                    "移动数据下禁止连接",
                    "本月移动数据已用 ${formatBytes(monthMobile)}。可在下方关闭此限制。",
                    MaterialTheme.colorScheme.error,
                )
                TrafficDecision.Warn -> Triple(
                    "达到移动数据提醒阈值",
                    "本月移动数据已用 ${formatBytes(monthMobile)}，阈值 ${policy.warnAtMb} MB。",
                    g.warn,
                )
                TrafficDecision.Allow -> Triple(
                    "允许连接",
                    "当前网络：${kind.label}",
                    g.good,
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(20.dp), GlassLevel.Regular)
                    .padding(14.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
                    color = color,
                )
                Text(detail, style = MaterialTheme.typography.bodySmall, color = g.muted)
            }
        }

        item { SectionLabel("今日") }
        item { UsageCard(today, kind) }
        item { SectionLabel("本月") }
        item { UsageCard(month, kind) }

        item { SectionLabel("近 7 天总流量") }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(22.dp), GlassLevel.Regular)
                    .padding(horizontal = 12.dp, vertical = 14.dp),
            ) {
                SevenDayChart(
                    days = days,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp),
                )
                Spacer(Modifier.height(6.dp))
                val busiest = days.maxByOrNull { it.second }
                Text(
                    text = if (busiest == null || busiest.second == 0L) {
                        "还没有流量记录。连接网络后开始统计。"
                    } else {
                        "最高 ${busiest.first.monthValue}/${busiest.first.dayOfMonth} · ${formatBytes(busiest.second)}" +
                            " · 7 天合计 ${formatBytes(days.sumOf { it.second })}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = g.muted,
                )
            }
        }

        item { SectionLabel("移动数据限制") }
        item {
            GlassSwitchRow(
                title = "移动数据下不连接",
                subtitle = "开启后，当前网络是移动数据时阻止建立连接。",
                checked = policy.blockOnMobile,
                onCheckedChange = {
                    traffic.setPolicy(policy.copy(blockOnMobile = it))
                },
            )
        }
        item {
            GlassDropdownRow(
                label = "超过阈值提醒",
                current = policy.warnAtMb,
                options = WarnThresholds,
                optionLabel = { if (it == 0) "关闭" else "$it MB" },
                onSelect = { traffic.setPolicy(policy.copy(warnAtMb = it)) },
            )
        }
        item {
            Text(
                text = "统计口径：内核每秒返回的节点累计字节数，取相邻两次差值的非负增量，按本地日期累计。重连后自动重置基线。",
                style = MaterialTheme.typography.labelSmall,
                color = g.muted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

private val WarnThresholds = listOf(0, 100, 500, 1_024, 2_048, 5_120)

@Composable
private fun UsageCard(usage: TrafficUsage, kind: NetworkKind) {
    val g = LocalGlassColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(20.dp), GlassLevel.Regular)
            .padding(14.dp),
    ) {
        Row {
            ValueColumn("上传", formatBytes(usage.tx), Modifier.weight(1f))
            ValueColumn("下载", formatBytes(usage.rx), Modifier.weight(1f))
            ValueColumn("总计", formatBytes(usage.total), Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row {
            ValueColumn("Wi-Fi", formatBytes(usage.byKind[NetworkKind.WIFI]?.total ?: 0), Modifier.weight(1f))
            ValueColumn(
                "移动数据",
                formatBytes(usage.byKind[NetworkKind.MOBILE]?.total ?: 0),
                Modifier.weight(1f),
            )
            ValueColumn("当前", kind.label, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ValueColumn(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = LocalGlassColors.current.muted)
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Single-series bar chart. One colour (theme primary) means no categorical palette risk;
 * the axis and the "busiest day" caption carry the numeric values. Bars are capped at
 * 24 dp with 4 dp rounded tops, square at the baseline.
 */
@Composable
private fun SevenDayChart(
    days: List<Pair<LocalDate, Long>>,
    modifier: Modifier = Modifier,
) {
    val g = LocalGlassColors.current
    val barColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outline
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(
        color = g.muted,
        fontSize = 9.sp,
    )
    val valueStyle = TextStyle(
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
    )
    val maxValue = niceMax(days.maxOfOrNull { it.second } ?: 0L)

    Canvas(modifier) {
        val leftGutter = 52.dp.toPx()
        val bottomGutter = 20.dp.toPx()
        val topPad = 16.dp.toPx()
        val plotWidth = size.width - leftGutter - 2.dp.toPx()
        val plotHeight = size.height - bottomGutter - topPad
        if (plotWidth <= 0f || plotHeight <= 0f) return@Canvas

        // Recessive hairline grid with real values on the axis.
        val ticks = if (maxValue <= 1L) listOf(0L) else listOf(0L, maxValue / 2, maxValue)
        ticks.forEach { value ->
            val y = topPad + plotHeight - (value.toFloat() / maxValue) * plotHeight
            drawLine(
                color = gridColor.copy(alpha = 0.45f),
                start = Offset(leftGutter, y),
                end = Offset(leftGutter + plotWidth, y),
                strokeWidth = 1.dp.toPx(),
            )
            val text = formatBytes(value)
            val layout = textMeasurer.measure(text, labelStyle)
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(leftGutter - layout.size.width - 6.dp.toPx(), y - layout.size.height / 2f),
            )
        }

        val band = plotWidth / days.size.coerceAtLeast(1)
        val barWidth = min(24.dp.toPx(), band * 0.52f)
        val busiest = days.maxByOrNull { it.second }
        days.forEachIndexed { index, (date, value) ->
            val ratio = if (maxValue == 0L) 0f else value.toFloat() / maxValue
            val barHeight = plotHeight * ratio
            val left = leftGutter + band * index + (band - barWidth) / 2f
            val top = topPad + plotHeight - barHeight
            if (barHeight > 0f) {
                // Round only the data end; the baseline stays square.
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(left, top),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                )
                if (barHeight > 4.dp.toPx()) {
                    drawRect(
                        color = barColor,
                        topLeft = Offset(left, top + barHeight - 4.dp.toPx()),
                        size = Size(barWidth, 4.dp.toPx()),
                    )
                }
            }
            val caption = "${date.monthValue}/${date.dayOfMonth}"
            val layout = textMeasurer.measure(caption, labelStyle)
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(left + barWidth / 2f - layout.size.width / 2f, topPad + plotHeight + 4.dp.toPx()),
            )
            // Label only the extreme, never every bar.
            if (busiest != null && value == busiest.second && value > 0L) {
                val valueLayout = textMeasurer.measure(formatBytes(value), valueStyle)
                drawText(
                    textLayoutResult = valueLayout,
                    topLeft = Offset(
                        (left + barWidth / 2f - valueLayout.size.width / 2f)
                            .coerceAtLeast(leftGutter),
                        (top - valueLayout.size.height - 2.dp.toPx()).coerceAtLeast(0f),
                    ),
                )
            }
        }
    }
}

/** 1 / 2 / 5 × 10^n, so ticks read as round numbers. */
private fun niceMax(value: Long): Long {
    if (value <= 0L) return 1L
    val exponent = floor(log10(value.toDouble())).toInt().coerceAtLeast(0)
    val base = 10.0.pow(exponent).toLong().coerceAtLeast(1L)
    val fraction = value.toDouble() / base
    val nice = when {
        fraction <= 1.0 -> 1L
        fraction <= 2.0 -> 2L
        fraction <= 5.0 -> 5L
        else -> 10L
    }
    return max(1L, nice * base)
}

@Composable
private fun rememberNetworkKind(): NetworkKind {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(ConnectivityManager::class.java) }
    var kind by remember(manager) { mutableStateOf(currentNetworkKind(manager)) }
    DisposableEffect(manager) {
        if (manager == null) return@DisposableEffect onDispose { }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                kind = currentNetworkKind(manager)
            }

            override fun onLost(network: Network) {
                kind = currentNetworkKind(manager)
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
        onDispose {
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }
    return kind
}

private fun currentNetworkKind(manager: ConnectivityManager?): NetworkKind {
    if (manager == null) return NetworkKind.OTHER
    val active = manager.activeNetwork ?: return NetworkKind.OTHER
    val caps = manager.getNetworkCapabilities(active) ?: return NetworkKind.OTHER
    // Our own VPN takes over the default network. NetworkCapabilities only exposes
    // underlying transports through a system API, so scan the other networks and use
    // the first non-VPN internet bearer as the best-effort physical transport.
    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
        manager.allNetworks.forEach { network ->
            if (network == active) return@forEach
            val candidate = manager.getNetworkCapabilities(network) ?: return@forEach
            if (candidate.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !candidate.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            ) {
                return kindOf(candidate)
            }
        }
    }
    return kindOf(caps)
}

private fun kindOf(caps: NetworkCapabilities): NetworkKind = when {
    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.MOBILE
    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.WIFI
    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.WIFI
    else -> NetworkKind.OTHER
}
