package app.timiao.et.ui.components

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import app.timiao.et.ui.theme.LocalGlassColors
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Full-screen cream/peach/lilac backdrop. The three colour blobs are drawn into
 * one layer. On Android 12+ that layer gets a RenderEffect blur (Compose
 * Modifier.blur); older versions fall back to the same soft radial gradients
 * with no live blur. Dark mode is a deep-purple night sky with brighter blobs.
 */
@Composable
fun GlassBackdrop(modifier: Modifier = Modifier) {
    val g = LocalGlassColors.current
    val bg = MaterialTheme.colorScheme.background
    val dark = bg.luminance() < 0.5f
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val stars = remember(dark) {
        if (!dark) emptyList() else List(26) {
            Offset(Random.nextFloat(), Random.nextFloat()) to (0.15f + Random.nextFloat() * 0.35f)
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .then(if (canBlur) Modifier.blur(52.dp) else Modifier),
    ) {
        drawRect(bg)
        val peachAlpha = if (dark) 0.34f else 0.9f
        val lilacAlpha = if (dark) 0.4f else 0.82f
        val blueAlpha = if (dark) 0.32f else 0.85f

        drawCircle(
            brush = Brush.radialGradient(
                listOf(g.blobPeach.copy(alpha = peachAlpha), Color.Transparent),
            ),
            radius = size.width * 0.72f,
            center = Offset(size.width * 0.12f, size.height * 0.23f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                listOf(g.blobLilac.copy(alpha = lilacAlpha), Color.Transparent),
            ),
            radius = size.width * 0.62f,
            center = Offset(size.width * 0.94f, size.height * 0.05f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                listOf(g.blobBlue.copy(alpha = blueAlpha), Color.Transparent),
            ),
            radius = size.width * 0.8f,
            center = Offset(size.width * 0.78f, size.height * 0.86f),
        )
    }

    // Stars stay crisp: drawn outside the blurred layer.
    if (dark) {
        Canvas(Modifier.fillMaxSize()) {
            val count = stars.size
            val starSize = (1.2f * density).roundToInt().coerceAtLeast(2)
            stars.forEachIndexed { i, (pos, alpha) ->
                val jitter = if (i % 3 == 0) 0.5f else 0f
                drawCircle(
                    color = Color.White.copy(alpha = alpha * (0.7f + jitter)),
                    radius = starSize / 2f,
                    center = Offset(pos.x * size.width, pos.y * size.height * 0.6f),
                )
            }
            if (count == 0) Unit
        }
    }
}
