package app.timiao.et.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import app.timiao.et.core.LinkState
import app.timiao.et.ui.theme.LocalReducedMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 缇喵 mascot, drawn from scratch in Compose Canvas.
 *
 * Siamese palette: cream face, dark-brown ears and muzzle mask, big glass-bead
 * eyes (black pupil, pale-blue lower crescent, white highlights). No details
 * borrowed from any existing IP; the antenna is our own connection metaphor.
 */
@Composable
fun CatMascot(
    state: LinkState,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalReducedMotion.current
    val accent = MaterialTheme.colorScheme.primary

    val phase = if (reduced) {
        0f
    } else {
        val transition = rememberInfiniteTransition(label = "cat-motion")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 4_200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "cat-phase",
        ).value
    }

    val antennaTarget = when (state) {
        LinkState.Connecting, LinkState.Connected -> 1f
        LinkState.Idle, LinkState.Error -> 0.14f
    }
    val antennaExt by animateFloatAsState(
        targetValue = antennaTarget,
        animationSpec = tween(durationMillis = if (reduced) 0 else 420, easing = FastOutSlowInEasing),
        label = "antenna",
    )

    val breath = if (reduced) 1f else 1f + sin(phase * 2f * PI).toFloat() * 0.012f
    val pulse = if (reduced) 0.6f else (sin(phase * 2f * PI).toFloat() * 0.5f + 0.5f)
    val blinking = state == LinkState.Connected && !reduced && phase in 0.70f..0.725f

    val face = Color(0xFFF7EADB)
    val outline = Color(0xFF3E2A1E)
    val earOuter = Color(0xFF6B4A33)
    val earInner = Color(0xFFC99C7B)
    val muzzle = Color(0xFFA97F60)
    val pupil = Color(0xFF1D1822)
    val eyeBlue = Color(0xFFBFE3EF)
    val tearBlue = Color(0xFF9FD3FF)
    val description = when (state) {
        LinkState.Idle -> "小猫在睡觉（未连接）"
        LinkState.Connecting -> "小猫在找节点（连接中）"
        LinkState.Connected -> "小猫已连接"
        LinkState.Error -> "小猫出错"
    }

    Canvas(
        modifier = modifier.semantics { contentDescription = description },
    ) {
        val vw = 120f
        val vh = 112f
        val s = min(size.width / vw, size.height / vh)
        val ox = (size.width - vw * s) / 2f
        val oy = (size.height - vh * s) / 2f

        withTransform({
            translate(ox, oy)
            scale(s, s, Offset.Zero)
        }) {
            // Connection glow behind the cat.
            if (state == LinkState.Connected) {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(accent.copy(alpha = 0.18f + 0.08f * pulse), Color.Transparent),
                    ),
                    radius = 58f,
                    center = Offset(60f, 62f),
                )
            }

            drawAntenna(state, antennaExt, accent, pulse, outline, face)
            drawCatBody(face, outline, earOuter, earInner, muzzle, breath)
            drawFace(state, pupil, eyeBlue, outline, accent, blinking)
            drawWhiskers(outline)
            if (state == LinkState.Error) {
                drawTear(tearBlue)
            }
            if (state == LinkState.Idle) {
                drawSleepBubbles(pulse, outline, accent, reduced)
            }
        }
    }
}

private fun DrawScope.drawCatBody(
    face: Color,
    outline: Color,
    earOuter: Color,
    earInner: Color,
    muzzle: Color,
    breath: Float,
) {
    // Ears (mirrored paths, hand-drawn triangle with curved tips).
    val leftEar = Path().apply {
        moveTo(19f, 51f)
        lineTo(23f, 10f)
        quadraticBezierTo(24f, 4f, 30f, 8f)
        lineTo(57f, 31f)
        close()
    }
    val rightEar = Path().apply {
        moveTo(101f, 51f)
        lineTo(97f, 10f)
        quadraticBezierTo(96f, 4f, 90f, 8f)
        lineTo(63f, 31f)
        close()
    }
    drawPath(leftEar, earOuter)
    drawPath(leftEar, outline, style = Stroke(width = 2.2f))
    drawPath(rightEar, earOuter)
    drawPath(rightEar, outline, style = Stroke(width = 2.2f))

    val leftInner = Path().apply {
        moveTo(29f, 40f)
        lineTo(30f, 20f)
        lineTo(46f, 31f)
        close()
    }
    val rightInner = Path().apply {
        moveTo(91f, 40f)
        lineTo(90f, 20f)
        lineTo(74f, 31f)
        close()
    }
    drawPath(leftInner, earInner)
    drawPath(rightInner, earInner)

    withTransform({
        scale(breath, breath, Offset(60f, 82f))
    }) {
        drawOval(
            color = face,
            topLeft = Offset(14f, 25f),
            size = Size(92f, 74f),
        )
        drawOval(
            color = outline,
            topLeft = Offset(14f, 25f),
            size = Size(92f, 74f),
            style = Stroke(width = 2.4f),
        )
        // Siamese dark muzzle mask.
        drawOval(
            color = muzzle,
            topLeft = Offset(44f, 58f),
            size = Size(32f, 26f),
        )
    }

    // Nose.
    val nose = Path().apply {
        moveTo(57f, 67.5f)
        lineTo(63f, 67.5f)
        lineTo(60f, 71f)
        close()
    }
    drawPath(nose, outline)
}

private fun DrawScope.drawFace(
    state: LinkState,
    pupil: Color,
    eyeBlue: Color,
    outline: Color,
    accent: Color,
    blinking: Boolean,
) {
    val eyeStroke = Stroke(width = 2.4f, cap = StrokeCap.Round)
    when (state) {
        LinkState.Idle -> {
            // Sleeping: two gentle downward arcs.
            drawArc(
                color = outline,
                startAngle = 20f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(26f, 52f),
                size = Size(26f, 18f),
                style = eyeStroke,
            )
            drawArc(
                color = outline,
                startAngle = 20f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(68f, 52f),
                size = Size(26f, 18f),
                style = eyeStroke,
            )
            val mouth = Path().apply {
                moveTo(55f, 76f)
                quadraticBezierTo(60f, 78.5f, 65f, 76f)
            }
            drawPath(mouth, outline, style = eyeStroke)
        }

        LinkState.Connecting -> {
            // Wide searching eyes.
            drawCircle(pupil, radius = 8.5f, center = Offset(39f, 59f))
            drawCircle(pupil, radius = 8.5f, center = Offset(81f, 59f))
            drawCircle(Color.White, radius = 2.8f, center = Offset(42f, 55.5f))
            drawCircle(Color.White, radius = 2.8f, center = Offset(84f, 55.5f))
            drawCircle(
                color = outline,
                radius = 2.4f,
                center = Offset(60f, 76f),
                style = Stroke(width = 1.8f),
            )
        }

        LinkState.Connected -> {
            if (blinking) {
                drawArc(
                    color = outline,
                    startAngle = 20f,
                    sweepAngle = 140f,
                    useCenter = false,
                    topLeft = Offset(25f, 53f),
                    size = Size(28f, 17f),
                    style = eyeStroke,
                )
                drawArc(
                    color = outline,
                    startAngle = 20f,
                    sweepAngle = 140f,
                    useCenter = false,
                    topLeft = Offset(67f, 53f),
                    size = Size(28f, 17f),
                    style = eyeStroke,
                )
            } else {
                drawGlassEye(39f, 59f, pupil, eyeBlue)
                drawGlassEye(81f, 59f, pupil, eyeBlue)
            }
            // Original omega-style happy mouth.
            val mouth = Path().apply {
                moveTo(53.5f, 74.5f)
                quadraticBezierTo(56.7f, 78.5f, 60f, 74.5f)
                quadraticBezierTo(63.3f, 78.5f, 66.5f, 74.5f)
            }
            drawPath(mouth, outline, style = eyeStroke)
        }

        LinkState.Error -> {
            // Squeezed >< eyes and a small frown.
            val right = Path().apply {
                moveTo(29f, 53f)
                lineTo(41f, 59f)
                lineTo(29f, 65f)
            }
            val left = Path().apply {
                moveTo(91f, 53f)
                lineTo(79f, 59f)
                lineTo(91f, 65f)
            }
            drawPath(right, outline, style = eyeStroke)
            drawPath(left, outline, style = eyeStroke)
            val mouth = Path().apply {
                moveTo(55f, 78f)
                quadraticBezierTo(60f, 73.5f, 65f, 78f)
            }
            drawPath(mouth, outline, style = eyeStroke)
        }
    }
}

private fun DrawScope.drawGlassEye(cx: Float, cy: Float, pupil: Color, eyeBlue: Color) {
    val r = 13.2f
    val eyePath = Path().apply {
        addOval(Rect(Offset(cx - r, cy - r), Size(r * 2, r * 2)))
    }
    drawCircle(eyeBlue, radius = r, center = Offset(cx, cy))
    clipPath(eyePath) {
        drawCircle(pupil, radius = r, center = Offset(cx, cy - 2.6f))
    }
    drawCircle(eyeBlue.copy(alpha = 0.55f), radius = 5.2f, center = Offset(cx, cy + 7.4f))
    drawCircle(Color.White, radius = 4.1f, center = Offset(cx - 4.2f, cy - 5.4f))
    drawCircle(Color.White.copy(alpha = 0.9f), radius = 1.7f, center = Offset(cx - 6.8f, cy + 1.4f))
}

private fun DrawScope.drawAntenna(
    state: LinkState,
    extension: Float,
    accent: Color,
    pulse: Float,
    outline: Color,
    face: Color,
) {
    val lit = state == LinkState.Connected
    val tipX: Float
    val tipY: Float
    if (state == LinkState.Error) {
        tipX = 69f
        tipY = 20f
    } else {
        tipX = 60f
        tipY = 27f - 21f * extension
    }
    val path = Path().apply {
        moveTo(60f, 27f)
        if (state == LinkState.Error) {
            quadraticBezierTo(68f, 24f, tipX, tipY)
        } else {
            quadraticBezierTo(60f, (27f + tipY) / 2f - 1.5f, tipX, tipY)
        }
    }
    drawPath(path, if (lit) accent else outline.copy(alpha = 0.85f), style = Stroke(width = 2.4f, cap = StrokeCap.Round))

    if (lit) {
        drawCircle(accent.copy(alpha = 0.16f + 0.16f * pulse), radius = 9.5f, center = Offset(tipX, tipY))
        drawCircle(accent, radius = 3.8f, center = Offset(tipX, tipY))
        drawCircle(Color.White.copy(alpha = 0.85f), radius = 1.3f, center = Offset(tipX - 1f, tipY - 1.2f))
        val ray = 5.2f
        listOf(-0.75f, 0.75f).forEach { dx ->
            drawLine(
                color = accent.copy(alpha = 0.75f),
                start = Offset(tipX + dx * (ray + 1.5f), tipY - 2.4f),
                end = Offset(tipX + dx * (ray + 3.4f), tipY - 4.6f),
                strokeWidth = 1.6f,
                cap = StrokeCap.Round,
            )
        }
    } else {
        drawCircle(if (state == LinkState.Connecting) accent.copy(alpha = 0.35f + 0.35f * pulse) else face, radius = 3.4f, center = Offset(tipX, tipY))
        drawCircle(outline.copy(alpha = 0.8f), radius = 3.4f, center = Offset(tipX, tipY), style = Stroke(width = 1.6f))
    }
}

private fun DrawScope.drawWhiskers(outline: Color) {
    val c = outline.copy(alpha = 0.8f)
    drawLine(c, Offset(23f, 67f), Offset(6f, 64f), strokeWidth = 1.4f, cap = StrokeCap.Round)
    drawLine(c, Offset(23f, 73f), Offset(7f, 77f), strokeWidth = 1.4f, cap = StrokeCap.Round)
    drawLine(c, Offset(97f, 67f), Offset(114f, 64f), strokeWidth = 1.4f, cap = StrokeCap.Round)
    drawLine(c, Offset(97f, 73f), Offset(113f, 77f), strokeWidth = 1.4f, cap = StrokeCap.Round)
}

private fun DrawScope.drawTear(color: Color) {
    val tear = Path().apply {
        moveTo(96f, 63f)
        quadraticBezierTo(101f, 71f, 96f, 75f)
        quadraticBezierTo(91f, 71f, 96f, 63f)
        close()
    }
    drawPath(tear, color)
}

private fun DrawScope.drawSleepBubbles(
    pulse: Float,
    outline: Color,
    accent: Color,
    reduced: Boolean,
) {
    val alpha = if (reduced) 0.6f else 0.35f + 0.5f * pulse
    drawCircle(outline.copy(alpha = alpha), radius = 2.6f, center = Offset(100f, 24f))
    drawCircle(outline.copy(alpha = alpha * 0.8f), radius = 1.9f, center = Offset(108f, 15f))
    drawCircle(accent.copy(alpha = alpha * 0.5f), radius = 1.2f, center = Offset(112f, 8f))
}
