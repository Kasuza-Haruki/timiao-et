package app.timiao.et.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.timiao.et.ui.data.AppSettings
import app.timiao.et.ui.data.ThemeMode

val LightPrimary = Color(0xFFFF8A5C)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFFFDCCB)
val LightOnPrimaryContainer = Color(0xFF4A1D08)
val LightBackground = Color(0xFFFDF3EC)
val LightOnBackground = Color(0xFF3A2C24)
val LightSurface = Color(0xFFFFF9F5)
val LightOnSurface = Color(0xFF3A2C24)
val LightSurfaceVariant = Color(0xFFF3E5DA)
val LightOnSurfaceVariant = Color(0xFF8A776B)
val LightOutline = Color(0xFFE6D6C9)
val LightError = Color(0xFFB3261E)

val DarkPrimary = Color(0xFFFFB08F)
val DarkOnPrimary = Color(0xFF4A1D08)
val DarkPrimaryContainer = Color(0xFF5C2B14)
val DarkOnPrimaryContainer = Color(0xFFFFDCCB)
val DarkBackground = Color(0xFF1B1622)
val DarkOnBackground = Color(0xFFF3EBE4)
val DarkSurface = Color(0xFF251F2D)
val DarkOnSurface = Color(0xFFF3EBE4)
val DarkSurfaceVariant = Color(0xFF332B3C)
val DarkOnSurfaceVariant = Color(0xFFB3A3A0)
val DarkOutline = Color(0xFF4B4053)
val DarkError = Color(0xFFFFB4AB)

@Immutable
data class GlassColors(
    val top: Color,
    val bottom: Color,
    val border: Color,
    val highlight: Color,
    val subtleFill: Color,
    val dockSelected: Color,
    val muted: Color,
    val blobPeach: Color,
    val blobLilac: Color,
    val blobBlue: Color,
    val good: Color,
    val warn: Color,
)

val LightGlass = GlassColors(
    top = Color(0xCCFFFFFF),
    bottom = Color(0x47FFFFFF),
    border = Color(0xCCFFFFFF),
    highlight = Color(0x8CFFFFFF),
    subtleFill = Color(0x4DFFFFFF),
    dockSelected = Color(0xE6FFFFFF),
    muted = LightOnSurfaceVariant,
    blobPeach = Color(0xFFFFC6A8),
    blobLilac = Color(0xFFD9CBFF),
    blobBlue = Color(0xFFBFE3FF),
    good = Color(0xFF2FA36B),
    warn = Color(0xFFE08A2E),
)

val DarkGlass = GlassColors(
    top = Color(0x29FFFFFF),
    bottom = Color(0x0AFFFFFF),
    border = Color(0x2EFFFFFF),
    highlight = Color(0x47FFFFFF),
    subtleFill = Color(0x14FFFFFF),
    dockSelected = Color(0x2EFFFFFF),
    muted = DarkOnSurfaceVariant,
    blobPeach = Color(0xFFFF8A5C),
    blobLilac = Color(0xFFA66BFF),
    blobBlue = Color(0xFF4C7DFF),
    good = Color(0xFF6FD3A2),
    warn = Color(0xFFFFB570),
)

val LocalGlassColors = staticCompositionLocalOf { LightGlass }

/** True when the system animator scale is zero, or the caller disables motion. */
val LocalReducedMotion = staticCompositionLocalOf { false }

private val LightScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutline,
    error = LightError,
)

private val DarkScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutline,
    error = DarkError,
)

val TimiaoShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(32.dp),
)

val TimiaoTypography: Typography = Typography()

@Composable
fun TimiaoTheme(
    settings: AppSettings,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val dark = when (settings.themeMode) {
        ThemeMode.System -> systemDark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val dynamic = settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val scheme = when {
        dynamic && dark -> dynamicDarkColorScheme(context)
        dynamic -> dynamicLightColorScheme(context)
        dark -> DarkScheme
        else -> LightScheme
    }
    val glass = if (dark) DarkGlass else LightGlass
    CompositionLocalProvider(LocalGlassColors provides glass) {
        MaterialTheme(
            colorScheme = scheme,
            typography = TimiaoTypography,
            shapes = TimiaoShapes,
            content = content,
        )
    }
}
