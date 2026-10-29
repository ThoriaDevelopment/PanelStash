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
import androidx.compose.material3.lightColorScheme
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
import com.panelstash.data.ThemeMode

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

    /** False on the pure canvas, where glows would turn it grey. */
    var HasGlow: Boolean = true
        private set
    var IsLight: Boolean = false
        private set

    /** Text drawn on an accent fill or a brand gradient. */
    var OnAccent: Color = Color(0xFF0C0C16)
        private set

    /** Text drawn over artwork, which keeps its dark scrim in both modes. */
    val OnArtwork: Color = Color(0xFFF7F7FA)
    val OnArtworkSecondary: Color = Color(0xFFC9C9D2)

    /** The reader stays immersive black, so its chrome needs light ink in either mode. */
    val ReaderInk: Color = Color(0xFFF5F5F7)
    val ReaderInkSecondary: Color = Color(0xFFB4B4BE)

    val AccentGradient: List<Color> get() = listOf(Accent, AccentAlt)

    /**
     * The accent as a fill behind selected controls. Lighter on a light canvas: the same wash
     * would leave pink-on-pink text and a selected label at about 3:1.
     */
    fun accentFill(alpha: Float = if (IsLight) 0.12f else 0.24f): Color = Accent.copy(alpha = alpha)
    fun accentAltFill(alpha: Float = if (IsLight) 0.08f else 0.14f): Color = AccentAlt.copy(alpha = alpha)

    /** The accent as a hairline around a selected control. */
    fun accentEdge(alpha: Float = 0.4f): Color = Accent.copy(alpha = alpha)

    /**
     * The brand's two ends. The gradient is the logo's own; on the dark canvas the purple is the
     * readable half of it, which is why the two are available apart from the accent.
     */
    val BrandPink = Color(0xFFFB4B87)
    val BrandPurple = Color(0xFFA97BFF)
    val BrandGradient: List<Color> get() = listOf(BrandPink, BrandPurple)

    var Ink = Color(0xFFF5F5F7)
        private set
    var InkSecondary = Color(0xFF9C9CA7)
        private set
    var InkTertiary = Color(0xFF6B6B76)
        private set

    var Success = Color(0xFF32D74B)
        private set
    var Danger = Color(0xFFFF453A)
        private set
    var Warning = Color(0xFFFFD60A)
        private set

    /** Surface tints. Glass reads as a light film over the canvas, never as a grey box. */
    var Glass = Color(0x0EFFFFFF)
        private set
    var GlassStrong = Color(0x1AFFFFFF)
        private set
    var GlassBorder = Color(0x26FFFFFF)
        private set
    var GlassBorderSoft = Color(0x14FFFFFF)
        private set
    var Separator = Color(0x14FFFFFF)
        private set

    /** Colours that cannot be a single alpha white once the canvas may be light. */
    var GlassFilm = Color(0x05FFFFFF)
        private set
    var GlassEdgeStrong = Color(0x33FFFFFF)
        private set
    var GlassEdgeSoft = Color(0x0AFFFFFF)
        private set
    var WellTop = Color(0x1A000000)
        private set
    var WellBottom = Color(0x0AFFFFFF)
        private set
    var WellBorder = Color(0x0DFFFFFF)
        private set
    var Control = Color(0x14FFFFFF)
        private set
    var ControlBorder = Color(0x14FFFFFF)
        private set
    var Track = Color(0x1AFFFFFF)
        private set
    var ShimmerBase = Color(0x0AFFFFFF)
        private set
    var ShimmerHighlight = Color(0x1FFFFFFF)
        private set

    var Scrim = Color(0xE6000000)
        private set
    var Shadow = Color(0xFF000000)
        private set

    /** The chrome over artwork. Kept dark in both modes so covers stay readable. */
    fun artworkScrim(): List<Color> =
        listOf(Color(0xD907070A), Color(0x8007070A), Color.Transparent)

    /** The top bar fade. On a light canvas a dark fade would look like a dirty band. */
    fun topBarScrim(): List<Color> = if (IsLight) {
        listOf(Canvas.copy(alpha = 0.94f), Canvas.copy(alpha = 0.62f), Color.Transparent)
    } else {
        listOf(Color(0xD907070A), Color(0x8007070A), Color.Transparent)
    }

    /** The profile hero: artwork at the top, canvas at the bottom. */
    fun heroScrim(): List<Color> = if (IsLight) {
        listOf(Canvas.copy(alpha = 0.55f), Canvas.copy(alpha = 0.82f), Canvas)
    } else {
        listOf(Color(0x9907070A), Color(0xCC07070A), Canvas)
    }

    fun canvasColor(mode: ThemeMode, canvas: CanvasStyle): Color = when {
        mode == ThemeMode.LIGHT && canvas == CanvasStyle.GLASS -> Color(0xFFF3F1F7)
        mode == ThemeMode.LIGHT -> Color(0xFFFFFFFF)
        canvas == CanvasStyle.OLED -> Color(0xFF000000)
        else -> Color(0xFF07070A)
    }

    internal fun configure(accent: AccentTheme, canvas: CanvasStyle, mode: ThemeMode = ThemeMode.DARK) {
        IsLight = mode == ThemeMode.LIGHT
        val colors = accentColors(accent, IsLight)
        Accent = colors.first
        AccentAlt = colors.second
        Canvas = canvasColor(mode, canvas)

        if (IsLight) {
            OnAccent = Color.White
            Ink = Color(0xFF15151C)
            InkSecondary = Color(0xFF5C5C68)
            InkTertiary = Color(0xFF8B8B97)
            Success = Color(0xFF17803D)
            Danger = Color(0xFFB42318)
            Warning = Color(0xFF946200)
            Glass = Color(0xBFFFFFFF)
            GlassStrong = Color(0xF2FFFFFF)
            GlassBorder = Color(0x1F000000)
            GlassBorderSoft = Color(0x12000000)
            Separator = Color(0x14000000)
            GlassFilm = Color(0x33FFFFFF)
            GlassEdgeStrong = Color(0x24000000)
            GlassEdgeSoft = Color(0x0A000000)
            WellTop = Color(0x0F000000)
            WellBottom = Color(0x05FFFFFF)
            WellBorder = Color(0x10000000)
            Control = Color(0x0F000000)
            ControlBorder = Color(0x14000000)
            Track = Color(0x1A000000)
            ShimmerBase = Color(0x0A000000)
            ShimmerHighlight = Color(0x1FFFFFFF)
            Scrim = Color(0xB3000000)
            Shadow = Color(0xFF8E8798)
            GlowWarm = Color(0xFFFFDDE9)
            GlowCool = Color(0xFFDDEBFF)
            HasGlow = canvas == CanvasStyle.GLASS
        } else {
            OnAccent = Color(0xFF0C0C16)
            Ink = Color(0xFFF5F5F7)
            InkSecondary = Color(0xFF9C9CA7)
            InkTertiary = Color(0xFF6B6B76)
            Success = Color(0xFF32D74B)
            Danger = Color(0xFFFF453A)
            Warning = Color(0xFFFFD60A)
            Glass = Color(0x0EFFFFFF)
            GlassStrong = Color(0x1AFFFFFF)
            GlassBorder = Color(0x26FFFFFF)
            GlassBorderSoft = Color(0x14FFFFFF)
            Separator = Color(0x14FFFFFF)
            GlassFilm = Color(0x05FFFFFF)
            GlassEdgeStrong = Color(0x33FFFFFF)
            GlassEdgeSoft = Color(0x0AFFFFFF)
            WellTop = Color(0x1A000000)
            WellBottom = Color(0x0AFFFFFF)
            WellBorder = Color(0x0DFFFFFF)
            Control = Color(0x14FFFFFF)
            ControlBorder = Color(0x14FFFFFF)
            Track = Color(0x1AFFFFFF)
            ShimmerBase = Color(0x0AFFFFFF)
            ShimmerHighlight = Color(0x1FFFFFFF)
            Scrim = Color(0xE6000000)
            Shadow = Color(0xFF000000)
            GlowWarm = Color(0xFF232A5C)
            GlowCool = Color(0xFF0B3237)
            HasGlow = canvas == CanvasStyle.GLASS
        }
    }

    /** Light mode needs darker accents: the pastel originals vanish on a white canvas. */
    private fun accentColors(theme: AccentTheme, light: Boolean): Pair<Color, Color> =
        if (light) {
            when (theme) {
                AccentTheme.BRAND -> Color(0xFFE11D64) to Color(0xFF7C3AED)
                AccentTheme.INDIGO -> Color(0xFF4F46E5) to Color(0xFF0284C7)
                AccentTheme.VIOLET -> Color(0xFF7C3AED) to Color(0xFFDB2777)
                AccentTheme.EMERALD -> Color(0xFF047857) to Color(0xFF0369A1)
                AccentTheme.ROSE -> Color(0xFFE11D48) to Color(0xFFEA580C)
                AccentTheme.AMBER -> Color(0xFFB45309) to Color(0xFFC2410C)
            }
        } else {
            when (theme) {
                AccentTheme.BRAND -> Color(0xFFFB4B87) to Color(0xFFA97BFF)
                AccentTheme.INDIGO -> Color(0xFF8E8CFF) to Color(0xFF5AC8FA)
                AccentTheme.VIOLET -> Color(0xFFC58CFF) to Color(0xFFFF8CD1)
                AccentTheme.EMERALD -> Color(0xFF5CDFA4) to Color(0xFF5AC8FA)
                AccentTheme.ROSE -> Color(0xFFFF8CA8) to Color(0xFFFFA36B)
                AccentTheme.AMBER -> Color(0xFFFFC75A) to Color(0xFFFF8A5A)
            }
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
    Palette.configure(preferences.accent, preferences.canvas, preferences.themeMode)
    val accent = Palette.Accent
    val canvas = Palette.Canvas
    val scheme = if (Palette.IsLight) {
        lightColorScheme(
            primary = accent,
            onPrimary = Palette.OnAccent,
            primaryContainer = accent.mix(canvas, 0.16f),
            onPrimaryContainer = Palette.Ink,
            secondary = Palette.AccentAlt,
            onSecondary = Palette.OnAccent,
            background = canvas,
            onBackground = Palette.Ink,
            surface = canvas.mix(Color.Black, 0.035f),
            onSurface = Palette.Ink,
            surfaceVariant = canvas.mix(Color.Black, 0.06f),
            onSurfaceVariant = Palette.InkSecondary,
            outline = Palette.GlassBorder,
            outlineVariant = Palette.Separator,
            error = Palette.Danger,
            onError = Color.White,
        )
    } else {
        darkColorScheme(
            primary = accent,
            onPrimary = Palette.OnAccent,
            primaryContainer = accent.mix(canvas, 0.68f),
            onPrimaryContainer = Palette.Ink,
            secondary = Palette.AccentAlt,
            onSecondary = Palette.OnAccent,
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
    }

    // A new accent, canvas or mode changes colours read all over the tree, so the content is
    // keyed on them: everything redraws with the new palette in one pass.
    key(preferences.accent, preferences.themeMode, preferences.canvas) {
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
