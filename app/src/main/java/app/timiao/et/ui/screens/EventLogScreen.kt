package app.timiao.et.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.timiao.et.core.NetworkEvent
import app.timiao.et.core.NetworkEventKind
import app.timiao.et.ui.AppViewModel
import app.timiao.et.ui.components.EmptyGlassCard
import app.timiao.et.ui.components.GlassLevel
import app.timiao.et.ui.components.GlassPillAction
import app.timiao.et.ui.components.ScreenHeader
import app.timiao.et.ui.components.SegmentedGlass
import app.timiao.et.ui.components.glass
import app.timiao.et.ui.components.pageInset
import app.timiao.et.ui.theme.LocalGlassColors
import app.timiao.et.ui.util.copyToClipboard
import app.timiao.et.ui.util.eventTypeLabel
import app.timiao.et.ui.util.formatEventTime

/** Kernel event journal (`events` in `collectNetworkInfos`), newest first. */
@Composable
fun EventLogScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
) {
    val status by vm.activeStatus.collectAsStateWithLifecycle()
    val active by vm.activeNetwork.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val g = LocalGlassColors.current

    var filter by rememberSaveable { mutableStateOf(0) }
    val events by remember {
        derivedStateOf {
            when (filter) {
                1 -> status.events.filter { it.kind == NetworkEventKind.Connect }
                2 -> status.events.filter { it.kind == NetworkEventKind.Error }
                else -> status.events
            }
        }
    }
    val connectCount by remember { derivedStateOf { status.events.count { it.kind == NetworkEventKind.Connect } } }
    val errorCount by remember { derivedStateOf { status.events.count { it.kind == NetworkEventKind.Error } } }

    fun line(event: NetworkEvent): String =
        "${formatEventTime(event.timeMs)} [${eventTypeLabel(event.type)}] ${event.detail}"

    fun copyAll() {
        if (events.isEmpty()) return
        copyToClipboard(context, "事件日志", events.joinToString("\n") { line(it) })
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = pageInset(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenHeader(
                title = "事件日志",
                subtitle = active?.let { "${it.name} · ${status.events.size} 条" } ?: "未选择网络",
                onBack = onBack,
                action = {
                    GlassPillAction(
                        text = "复制",
                        onClick = { copyAll() },
                        enabled = events.isNotEmpty(),
                    )
                },
            )
        }
        item {
            SegmentedGlass(
                options = listOf(
                    "全部 ${status.events.size}",
                    "连接 $connectCount",
                    "错误 $errorCount",
                ),
                selected = filter,
                onSelect = { filter = it },
            )
        }
        item {
            Text(
                text = "最新事件在前，长按一条可复制。",
                style = MaterialTheme.typography.labelSmall,
                color = g.muted,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }

        if (events.isEmpty()) {
            item {
                EmptyGlassCard(
                    text = when {
                        status.events.isEmpty() -> "暂无事件。连接网络后，内核事件会显示在这里。"
                        filter == 2 -> "没有错误事件。"
                        filter == 1 -> "没有连接事件。"
                        else -> "没有匹配的事件。"
                    },
                )
            }
        } else {
            itemsIndexed(
                items = events,
                key = { index, event -> "${event.timeMs}:${event.type}:$index" },
            ) { _, event ->
                EventRow(
                    event = event,
                    onLongClick = { copyToClipboard(context, "事件", line(event)) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EventRow(event: NetworkEvent, onLongClick: () -> Unit) {
    val g = LocalGlassColors.current
    val accent = when (event.kind) {
        NetworkEventKind.Error -> MaterialTheme.colorScheme.error
        NetworkEventKind.Connect -> g.good
        NetworkEventKind.Other -> g.muted
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(18.dp), GlassLevel.Subtle)
            .combinedClickable(onClick = {}, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(9.dp)
                .clip(CircleShape)
                .background(accent),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = eventTypeLabel(event.type),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatEventTime(event.timeMs),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = g.muted,
                )
            }
            if (event.detail.isNotBlank()) {
                Text(
                    text = event.detail,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = g.muted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
