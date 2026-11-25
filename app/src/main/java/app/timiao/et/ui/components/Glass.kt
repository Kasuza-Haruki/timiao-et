package app.timiao.et.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import app.timiao.et.ui.theme.LocalGlassColors

enum class GlassLevel { Subtle, Regular, Strong }

/**
 * Liquid-glass surface.
 *
 * The blurred backdrop itself comes from [GlassBackdrop], which applies
 * RenderEffect (via Modifier.blur) to the background colour-blob layer on
 * Android 12+. Surfaces only add the translucent fill, 1px light border and
 * top highlight. [GlassLevel.Subtle] is intended for list rows: no extra
 * blur pass, lighter fill, so scrolling stays cheap.
 */
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(26.dp),
    level: GlassLevel = GlassLevel.Regular,
): Modifier = composed {
    val g = LocalGlassColors.current
    val fillTop = when (level) {
        GlassLevel.Subtle -> g.subtleFill
        GlassLevel.Regular -> g.top.copy(alpha = g.top.alpha * 0.9f)
        GlassLevel.Strong -> g.top
    }
    val fillBottom = when (level) {
        GlassLevel.Subtle -> g.subtleFill.copy(alpha = g.subtleFill.alpha * 0.7f)
        GlassLevel.Regular -> g.bottom
        GlassLevel.Strong -> g.bottom
    }
    val borderAlpha = when (level) {
        GlassLevel.Subtle -> 0.55f
        GlassLevel.Regular -> 0.85f
        GlassLevel.Strong -> 1f
    }
    this
        .clip(shape)
        .background(Brush.linearGradient(listOf(fillTop, fillBottom)))
        .border(1.dp, g.border.copy(alpha = g.border.alpha * borderAlpha), shape)
        .drawWithContent {
            drawContent()
            if (level != GlassLevel.Subtle) {
                val w = size.width * 0.82f
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to g.highlight.copy(alpha = g.highlight.alpha * if (level == GlassLevel.Strong) 1f else 0.7f),
                        0.45f to Color.Transparent,
                    ),
                    topLeft = Offset((size.width - w) / 2f, 0f),
                    size = Size(w, size.height * 0.42f),
                )
            }
        }
}
