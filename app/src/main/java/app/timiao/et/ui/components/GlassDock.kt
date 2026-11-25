package app.timiao.et.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.timiao.et.ui.theme.LocalGlassColors
import app.timiao.et.ui.theme.LocalReducedMotion

data class DockItem(val route: String, val label: String)

val DockItems = listOf(
    DockItem("home", "首页"),
    DockItem("peers", "节点"),
    DockItem("networks", "网络"),
    DockItem("settings", "设置"),
)

/** Floating glass dock. Big surface, so it gets the strong glass treatment. */
@Composable
fun GlassDock(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val g = LocalGlassColors.current
    val reduced = LocalReducedMotion.current
    val pill = RoundedCornerShape(999.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .glass(pill, GlassLevel.Strong)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DockItems.forEach { item ->
            val active = currentRoute == item.route ||
                (item.route == "networks" && currentRoute?.startsWith("network/") == true)
            val bg by animateColorAsState(
                targetValue = if (active) g.dockSelected else androidx.compose.ui.graphics.Color.Transparent,
                animationSpec = if (reduced) snap() else tween(180),
                label = "dock-bg",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(pill)
                    .background(bg)
                    .clickable { onNavigate(item.route) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = if (active) MaterialTheme.colorScheme.primary else g.muted,
                )
            }
        }
    }
}
