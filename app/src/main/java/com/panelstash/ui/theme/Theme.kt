package com.panelstash.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.panelstash.data.AccentTheme
import com.panelstash.data.CanvasStyle
import com.panelstash.data.Preferences

/**
 * A dark, glassy palette: near-black canvas with two faint colour glows, translucent white
 * surfaces for anything that floats, and one soft accent the user picks. Colours are
 * deliberately desaturated so the covers supply the colour.
 *
 * The accent and the canvas are the two things the user can change, so they live in mutable
 * fields that [PanelStashTheme] writes before drawing. Everything else is a constant. The theme
 * keys its content on those choices, so a change recomposes the whole tree rather than leaving
 * half the screen on the old accent.
 */
object Palette {
    /** Set by the theme from preferences; do not assign anywhere else. */
    var Accent: Color = Color(0xFFFB4B87)
        private set
    var AccentAlt: Color = Color(0xFFA97BFF)
        private set
    var Canvas: Color = Color(0xFF07070A)
        private set
    var GlowWarm: Color = Color(0xFF232A5C)
        private set
    var GlowCool: Color = Color(0xFF0B3237)
        private set

    /** False on the pure-black canvas, where glows would turn it grey. */
    var HasGlow: Boolean = true
        private set

    val AccentGradient: List<Color> get() = listOf(Accent, AccentAlt)

    /** The accent as a fill behind selected controls. */
    fun accentFill(alpha: Float = 0.24f): Color = Accent.copy(alpha = alpha)
    fun accentAltFill(alpha: Float = 0.14f): Color = AccentAlt.copy(alpha = alpha)

    /** The accent as a hairline around a selected control. */
    fun accentEdge(alpha: Float = 0.4f): Color = Accent.copy(alpha = alpha)

    /**
     * The brand's two ends. The gradient is the logo's own; on the dark canvas the purple is the
     * readable half of it, which is why the two are available apart from the accent.
     */
    val BrandPink = Color(0xFFFB4B87)
    val BrandPurple = Color(0xFFA97BFF)
    val BrandGradient: List<Color> get() = listOf(BrandPink, BrandPurple)

    val Ink = Color(0xFFF5F5F7)
    val InkSecondary = Color(0xFF9C9CA7)
    val InkTertiary = Color(0xFF6B6B76)

    val Success = Color(0xFF32D74B)
    val Danger = Color(0xFFFF453A)
    val Warning = Color(0xFFFFD60A)

    /** Surface tints. Glass reads as a light film over the canvas, never as a grey box. */
    val Glass = Color(0x0EFFFFFF)
    val GlassStrong = Color(0x1AFFFFFF)
    val GlassBorder = Color(0x26FFFFFF)
    val GlassBorderSoft = Color(0x14FFFFFF)
    val Separator = Color(0x14FFFFFF)

    val Scrim = Color(0xE6000000)
    val Shadow = Color(0xFF000000)

    internal fun configure(accent: AccentTheme, canvas: CanvasStyle) {
        val colors = accentColors(accent)
        Accent = colors.first
        AccentAlt = colors.second
        when (canvas) {
            CanvasStyle.GLASS -> {
                Canvas = Color(0xFF07070A)
                GlowWarm = Color(0xFF232A5C)
                GlowCool = Color(0xFF0B3237)
                HasGlow = true
            }

            CanvasStyle.OLED -> {
                Canvas = Color(0xFF000000)
                GlowWarm = Color.Transparent
                GlowCool = Color.Transparent
                HasGlow = false
            }
        }
    }

    private fun accentColors(theme: AccentTheme): Pair<Color, Color> = when (theme) {
        AccentTheme.BRAND -> Color(0xFFFB4B87) to Color(0xFFA97BFF)
        AccentTheme.INDIGO -> Color(0xFF8E8CFF) to Color(0xFF5AC8FA)
        AccentTheme.VIOLET -> Color(0xFFC58CFF) to Color(0xFFFF8CD1)
        AccentTheme.EMERALD -> Color(0xFF5CDFA4) to Color(0xFF5AC8FA)
        AccentTheme.ROSE -> Color(0xFFFF8CA8) to Color(0xFFFFA36B)
        AccentTheme.AMBER -> Color(0xFFFFC75A) to Color(0xFFFF8A5A)
    }
}

private fun Color.mix(other: Color, amount: Float): Color = Color(
    red = red * (1 - amount) + other.red * amount,
    green = green * (1 - amount) + other.green * amount,
    blue = blue * (1 - amount) + other.blue * amount,
    alpha = 1f,
)

private val Type = Typography(
    headlineLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
    headlineSmall = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

private val Shapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

@Composable
fun PanelStashTheme(
    preferences: Preferences = Preferences(),
    content: @Composable () -> Unit,
) {
    Palette.configure(preferences.accent, preferences.canvas)
    val accent = Palette.Accent
    val canvas = Palette.Canvas
    val scheme = darkColorScheme(
        primary = accent,
        onPrimary = Color(0xFF0B0B14),
        primaryContainer = accent.mix(canvas, 0.68f),
        onPrimaryContainer = Palette.Ink,
        secondary = Palette.AccentAlt,
        onSecondary = Color(0xFF04141C),
        background = canvas,
        onBackground = Palette.Ink,
        surface = canvas.mix(Color.White, 0.05f),
        onSurface = Palette.Ink,
        surfaceVariant = canvas.mix(Color.White, 0.09f),
        onSurfaceVariant = Palette.InkSecondary,
        outline = Palette.GlassBorder,
        outlineVariant = Palette.Separator,
        error = Palette.Danger,
        onError = Color(0xFF1A0603),
    )

    // A new accent or canvas changes colours read all over the tree, so the content is keyed on
    // them: everything redraws with the new palette in one pass.
    key(preferences.accent, preferences.canvas) {
        MaterialTheme(colorScheme = scheme, typography = Type, shapes = Shapes, content = content)
    }
}

/**
 * The canvas every screen draws on. Two off-centre glows give the translucent surfaces
 * something to sit on, so they read as glass rather than as flat rectangles. On the pure-black
 * canvas the glows are gone, which is the point of that option on an OLED panel.
 */
@Composable
fun AppCanvas(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    var base = modifier
        .fillMaxSize()
        .background(Palette.Canvas)
    if (Palette.HasGlow) {
        base = base
            .background(
                Brush.radialGradient(
                    colors = listOf(Palette.GlowWarm, Color.Transparent),
                    center = Offset(0.08f, 0f),
                    radius = 900f,
                ),
            )
            .background(
                Brush.radialGradient(
                    colors = listOf(Palette.GlowCool, Color.Transparent),
                    center = Offset(1f, 1f),
                    radius = 1000f,
                ),
            )
    }
    Box(modifier = base, content = content)
}
