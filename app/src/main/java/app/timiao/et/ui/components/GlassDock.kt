package app.timiao.et.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.timiao.et.R
import app.timiao.et.ui.theme.LocalGlassColors
import app.timiao.et.ui.theme.LocalReducedMotion

data class DockItem(val route: String, val labelRes: Int)

val DockItems = listOf(
    DockItem("home", R.string.dock_home),
    DockItem("peers", R.string.dock_peers),
    DockItem("networks", R.string.dock_networks),
    DockItem("settings", R.string.dock_settings),
)

/**
 * Floating glass dock. One selection pill slides between items on a spring, so a tab change
 * reads as one moving object instead of four colour fades.
 */
@Composable
fun GlassDock(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val g = LocalGlassColors.current
    val reduced = LocalReducedMotion.current
    val pill = RoundedCornerShape(999.dp)
    val activeIndex = DockItems.indexOfFirst { item ->
        currentRoute == item.route || (item.route == "networks" && currentRoute?.startsWith("network/") == true)
    }
    val position by animateFloatAsState(
        targetValue = activeIndex.coerceAtLeast(0).toFloat(),
        animationSpec = if (reduced) snap() else spring(dampingRatio = 0.72f, stiffness = 380f),
        label = "dock-pos",
    )
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            // The dock floats over scrolling content, which the backdrop blur does not reach.
            // A near-opaque surface under the glass keeps labels readable over any card.
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), pill)
            .glass(pill, GlassLevel.Strong)
            .padding(8.dp),
    ) {
        val slot = maxWidth / DockItems.size
        val slotPx = with(LocalDensity.current) { slot.toPx() }
        if (activeIndex >= 0) {
            Box(
                Modifier
                    .offset { IntOffset((position * slotPx).roundToInt(), 0) }
                    .width(slot)
                    .height(38.dp)
                    .clip(pill)
                    .background(g.dockSelected),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            DockItems.forEachIndexed { index, item ->
                // Label colour follows the pill: it is "active" while the pill is mostly over it.
                val near = (1f - kotlin.math.abs(position - index)).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(pill)
                        .clickable { onNavigate(item.route) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(item.labelRes),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold),
                        color = lerp(g.muted, MaterialTheme.colorScheme.primary, near),
                    )
                }
            }
        }
    }
}
