package com.panelstash.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.panelstash.ui.theme.Palette
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------------------
// Surfaces
// ---------------------------------------------------------------------------

/**
 * The house surface: a translucent light film, a hairline that is brighter at the top-left
 * than the bottom-right, and a soft dark drop shadow. Glass on top, a hint of relief
 * underneath, which is what makes a button feel like an object you can press.
 */
fun Modifier.glass(
    shape: androidx.compose.ui.graphics.Shape,
    strong: Boolean = false,
    elevation: Dp = 10.dp,
): Modifier = this
    .shadow(
        elevation = elevation,
        shape = shape,
        clip = false,
        ambientColor = Palette.Shadow.copy(alpha = 0.45f),
        spotColor = Palette.Shadow.copy(alpha = 0.6f),
    )
    .clip(shape)
    .background(
        Brush.linearGradient(
            colors = if (strong) {
                listOf(Palette.GlassStrong, Palette.Glass)
            } else {
                listOf(Palette.Glass, Color(0x05FFFFFF))
            },
        ),
    )
    .border(
        width = 1.dp,
        brush = Brush.linearGradient(
            listOf(Color(0x33FFFFFF), Color(0x0AFFFFFF)),
        ),
        shape = shape,
    )

/** A pressed-in variant of [glass], used where an element reads as a well rather than a card. */
fun Modifier.well(shape: androidx.compose.ui.graphics.Shape): Modifier = this
    .clip(shape)
    .background(
        Brush.linearGradient(
            listOf(Color(0x1A000000), Color(0x0AFFFFFF)),
        ),
    )
    .border(1.dp, Color(0x0DFFFFFF), shape)

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(24.dp),
    strong: Boolean = false,
    elevation: Dp = 12.dp,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .then(if (onClick != null) Modifier.pressable(onClick, onLongClick) else Modifier)
            .glass(shape, strong, elevation)
            .padding(contentPadding),
        content = content,
    )
}

// ---------------------------------------------------------------------------
// Press feedback
// ---------------------------------------------------------------------------

/**
 * Every interactive surface in the app goes through this: a spring-damped scale and a light
 * haptic tick, so a tap feels confirmed even before the screen reacts.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.pressable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    scaleDown: Float = 0.96f,
    haptics: Boolean = true,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val feedback = LocalHapticFeedback.current
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) scaleDown else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
        label = "press-scale",
    )
    val tick = {
        if (haptics) feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }.then(
        if (onLongClick != null) {
            Modifier.combinedClickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = { tick(); onClick() },
                onLongClick = {
                    if (haptics) feedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            )
        } else {
            Modifier.clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = { tick(); onClick() },
            )
        },
    )
}

// ---------------------------------------------------------------------------
// Glyphs
// ---------------------------------------------------------------------------

/** Hand-drawn line icons, on a 24x24 grid, so the stroke weight matches everywhere. */
/**
 * The brand mark: the pink-to-purple square with the heart on it, drawn from the same geometry as
 * the launcher icon so the two never drift apart. Sizes are given, never scaled by a parent.
 */
@Composable
fun BrandMark(modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 512f
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = Palette.BrandGradient,
                start = Offset.Zero,
                end = Offset(this.size.width, this.size.height),
            ),
            cornerRadius = CornerRadius(114f * u, 114f * u),
        )
        val heart = Path().apply {
            moveTo(256f * u, 362f * u)
            cubicTo(246.7f * u, 353.5f * u, 169f * u, 290.6f * u, 169f * u, 236.9f * u)
            cubicTo(169f * u, 208f * u, 191.8f * u, 185f * u, 219.4f * u, 185f * u)
            cubicTo(236f * u, 185f * u, 251.9f * u, 192.6f * u, 256f * u, 204.9f * u)
            cubicTo(260.1f * u, 192.6f * u, 276f * u, 185f * u, 292.6f * u, 185f * u)
            cubicTo(320.2f * u, 185f * u, 343f * u, 208f * u, 343f * u, 236.9f * u)
            cubicTo(343f * u, 290.6f * u, 265.3f * u, 353.5f * u, 256f * u, 362f * u)
            close()
        }
        drawPath(heart, Color.White)
    }
}

/** The brand's wordmark: the first syllable in ink, the second in the brand's purple. */
@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Panel",
            style = MaterialTheme.typography.headlineSmall,
            color = Palette.Ink,
        )
        Text(
            text = "Stash",
            style = MaterialTheme.typography.headlineSmall,
            color = Palette.BrandPurple,
        )
    }
}

enum class Glyph {
    ChevronLeft,
    ChevronRight,            ChevronDown,
    Search,
    Compass,
    Books,
    Bookmark,
    Clock,
    Download,
    Check,
    Close,
    Trash,
    Refresh,
    Play,
    Settings,
    Filter,
    List,
    Undo,
}

@Composable
fun GlyphIcon(
    glyph: Glyph,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    tint: Color = Palette.Ink,
    stroke: Dp = 1.8.dp,
) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val w = stroke.toPx()
        val line = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun path(vararg points: Float, close: Boolean = false): Path {
            val p = Path()
            p.moveTo(points[0] * u, points[1] * u)
            var i = 2
            while (i < points.size) {
                p.lineTo(points[i] * u, points[i + 1] * u)
                i += 2
            }
            if (close) p.close()
            return p
        }

        when (glyph) {
            Glyph.ChevronLeft -> drawPath(path(15f, 5f, 8.4f, 12f, 15f, 19f), tint, style = line)
            Glyph.ChevronRight -> drawPath(path(9f, 5f, 15.6f, 12f, 9f, 19f), tint, style = line)
            Glyph.ChevronDown -> drawPath(path(5.6f, 9.6f, 12f, 16f, 18.4f, 9.6f), tint, style = line)
            Glyph.Search -> {
                drawCircle(tint, radius = 6.2f * u, center = Offset(10.5f * u, 10.5f * u), style = line)
                drawLine(tint, Offset(15.2f * u, 15.2f * u), Offset(19.6f * u, 19.6f * u), w, StrokeCap.Round)
            }

            Glyph.Compass -> {
                drawCircle(tint, radius = 8.7f * u, center = Offset(12f * u, 12f * u), style = line)
                drawPath(path(16f, 8f, 13.2f, 13.2f, 8f, 16f, 10.8f, 10.8f, close = true), tint, style = line)
            }

            Glyph.Books -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(4f * u, 6.5f * u),
                    size = Size(6.4f * u, 13.5f * u),
                    cornerRadius = CornerRadius(2.2f * u, 2.2f * u),
                    style = line,
                )
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(13.6f * u, 4f * u),
                    size = Size(6.4f * u, 16f * u),
                    cornerRadius = CornerRadius(2.2f * u, 2.2f * u),
                    style = line,
                )
            }

            Glyph.Bookmark -> drawPath(path(7f, 4.4f, 17f, 4.4f, 17f, 20f, 12f, 15.2f, 7f, 20f, close = true), tint, style = line)
            Glyph.Clock -> {
                drawCircle(tint, radius = 8.6f * u, center = Offset(12f * u, 12f * u), style = line)
                drawPath(path(12f, 7.4f, 12f, 12.4f, 15.6f, 14.2f), tint, style = line)
            }

            Glyph.Download -> {
                drawPath(path(12f, 4.2f, 12f, 14.8f), tint, style = line)
                drawPath(path(7.8f, 10.8f, 12f, 15.2f, 16.2f, 10.8f), tint, style = line)
                drawPath(path(5f, 19.6f, 19f, 19.6f), tint, style = line)
            }

            Glyph.Check -> drawPath(path(5.2f, 12.6f, 9.8f, 17.4f, 18.8f, 6.6f), tint, style = line)
            Glyph.Close -> {
                drawPath(path(6.6f, 6.6f, 17.4f, 17.4f), tint, style = line)
                drawPath(path(17.4f, 6.6f, 6.6f, 17.4f), tint, style = line)
            }

            Glyph.Trash -> {
                drawPath(path(4.6f, 6.8f, 19.4f, 6.8f), tint, style = line)
                drawPath(path(9.2f, 6.8f, 9.2f, 4.6f, 14.8f, 4.6f, 14.8f, 6.8f), tint, style = line)
                drawPath(path(7f, 6.8f, 8f, 19.8f, 16f, 19.8f, 17f, 6.8f), tint, style = line)
            }

            Glyph.Refresh -> {
                drawArc(
                    color = tint,
                    startAngle = -50f,
                    sweepAngle = 285f,
                    useCenter = false,
                    topLeft = Offset(4.6f * u, 4.6f * u),
                    size = Size(14.8f * u, 14.8f * u),
                    style = line,
                )
                drawPath(path(15.4f, 3.6f, 16.4f, 8.4f, 11.6f, 8.6f), tint, style = line)
            }

            Glyph.Play -> drawPath(path(8.6f, 5.6f, 18.6f, 12f, 8.6f, 18.4f, close = true), tint)

            Glyph.Settings -> {
                val centre = Offset(12f * u, 12f * u)
                drawCircle(tint, radius = 3.3f * u, center = centre, style = line)
                drawCircle(tint, radius = 7.4f * u, center = centre, style = line)
                for (tooth in 0 until 6) {
                    val angle = (Math.PI / 3.0 * tooth).toFloat()
                    val inner = 7.4f
                    val outer = 10.4f
                    drawLine(
                        color = tint,
                        start = Offset((12f + inner * cos(angle)) * u, (12f + inner * sin(angle)) * u),
                        end = Offset((12f + outer * cos(angle)) * u, (12f + outer * sin(angle)) * u),
                        strokeWidth = w,
                        cap = StrokeCap.Round,
                    )
                }
            }

            Glyph.Filter -> drawPath(
                path(4.4f, 5.4f, 19.6f, 5.4f, 13.8f, 12.6f, 13.8f, 19.4f, 10.2f, 16.8f, 10.2f, 12.6f, close = true),
                tint,
                style = line,
            )

            Glyph.List -> {
                for (row in 0 until 3) {
                    val y = 6.6f + row * 5.4f
                    drawLine(tint, Offset(9.4f * u, y * u), Offset(19.6f * u, y * u), w, StrokeCap.Round)
                    drawCircle(tint, radius = 1.15f * u, center = Offset(5.6f * u, y * u))
                }
            }

            Glyph.Undo -> {
                drawArc(
                    color = tint,
                    startAngle = 125f,
                    sweepAngle = 285f,
                    useCenter = false,
                    topLeft = Offset(4.6f * u, 4.6f * u),
                    size = Size(14.8f * u, 14.8f * u),
                    style = line,
                )
                drawPath(path(8.4f, 3.8f, 7.4f, 8.6f, 12.2f, 8.8f), tint, style = line)
            }
        }
    }
}

@Composable
fun IconButton(
    glyph: Glyph,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    tint: Color = Palette.Ink,
    strong: Boolean = false,
) {
    Box(
        modifier = modifier
            .size(size)
            .pressable(onClick, scaleDown = 0.9f)
            .glass(CircleShape, strong = strong, elevation = if (strong) 12.dp else 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, size = size * 0.5f, tint = tint, stroke = 1.9.dp)
    }
}

// ---------------------------------------------------------------------------
// Controls
// ---------------------------------------------------------------------------

enum class PillTone { Accent, Glass, Quiet }

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: PillTone = PillTone.Glass,
    glyph: Glyph? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(18.dp)
    val background: Modifier = when {
        !enabled -> Modifier
            .clip(shape)
            .background(Color(0x0AFFFFFF))

        selected -> Modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(Palette.accentFill(), Palette.accentAltFill()),
                ),
            )
            .border(1.dp, Palette.accentEdge(), shape)

        tone == PillTone.Accent -> Modifier
            .clip(shape)
            .background(Brush.linearGradient(Palette.AccentGradient))
            .shadow(
                elevation = 14.dp,
                shape = shape,
                clip = false,
                ambientColor = Palette.Accent.copy(alpha = 0.5f),
                spotColor = Palette.Accent.copy(alpha = 0.65f),
            )

        tone == PillTone.Quiet -> Modifier
            .clip(shape)
            .background(Color(0x0FFFFFFF))

        else -> Modifier.glass(shape, elevation = 8.dp)
    }
    val contentColor = when {
        !enabled -> Palette.InkTertiary
        selected -> Palette.Accent
        tone == PillTone.Accent -> Color(0xFF0C0C16)
        else -> Palette.Ink
    }

    Row(
        modifier = modifier
            .then(background)
            .pressable(onClick, onLongClick, enabled = enabled, scaleDown = 0.95f)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (glyph != null) {
            GlyphIcon(glyph, size = 16.dp, tint = contentColor, stroke = 2.dp)
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.isEmpty()) return
    val shape = RoundedCornerShape(18.dp)
    val inner = RoundedCornerShape(14.dp)
    val segmentHeight = 38.dp

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .glass(shape, elevation = 6.dp)
            .padding(4.dp),
    ) {
        val segmentWidth = maxWidth / options.size
        val thumbOffset by animateDpAsState(
            targetValue = segmentWidth * selectedIndex.coerceIn(0, options.lastIndex),
            animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
            label = "segment-thumb",
        )
        Box(
            Modifier
                .offset { IntOffset(thumbOffset.roundToPx(), 0) }
                .width(segmentWidth)
                .height(segmentHeight)
                .clip(inner)
                .background(
                    Brush.linearGradient(
                        listOf(Palette.accentFill(), Palette.accentAltFill(0.12f)),
                    ),
                )
                .border(1.dp, Palette.accentEdge(0.35f), inner),
        )
        Row(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, label ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(segmentHeight)
                        .clip(inner)
                        .pressable({ onSelect(index) }, scaleDown = 0.94f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (index == selectedIndex) Palette.Ink else Palette.InkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun Chip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .then(
                if (selected) {
                    Modifier
                        .clip(shape)
                        .background(
                            Brush.linearGradient(
                                listOf(Palette.accentFill(), Palette.accentAltFill()),
                            ),
                        )
                        .border(1.dp, Palette.accentEdge(), shape)
                } else {
                    Modifier.glass(shape, elevation = 4.dp)
                },
            )
            .pressable(onClick, scaleDown = 0.93f)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Palette.Accent else Palette.InkSecondary,
            maxLines = 1,
        )
    }
}

@Composable
fun SearchPill(
    value: String,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit = {},
    onOpen: (() -> Unit)? = null,
    onSubmit: () -> Unit = {},
) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .glass(shape, elevation = 6.dp)
            .then(if (onOpen != null) Modifier.pressable(onOpen) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        GlyphIcon(Glyph.Search, size = 17.dp, tint = Palette.InkTertiary, stroke = 2.dp)
        if (onOpen != null) {
            Text(
                text = value.ifBlank { "Search manhwa" },
                style = MaterialTheme.typography.bodyMedium,
                color = if (value.isBlank()) Palette.InkTertiary else Palette.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Box(Modifier.weight(1f)) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Palette.Ink),
                    cursorBrush = SolidColor(Palette.Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (value.isEmpty()) {
                            Text(
                                text = "Search manhwa",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Palette.InkTertiary,
                            )
                        }
                        inner()
                    },
                )
            }
        }
    }
}

@Composable
fun TopBar(
    title: String? = null,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    scrim: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Box(
        Modifier
            .fillMaxWidth()
            .then(
                if (scrim) {
                    Modifier.background(
                        Brush.verticalGradient(
                            listOf(Color(0xD907070A), Color(0x8007070A), Color.Transparent),
                        ),
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (onBack != null) {
                IconButton(Glyph.ChevronLeft, onBack, strong = true)
            }
            Column(Modifier.weight(1f)) {
                if (title != null) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = Palette.Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = Palette.InkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            actions()
        }
    }
}

// ---------------------------------------------------------------------------
// Content
// ---------------------------------------------------------------------------

@Composable
fun Shimmer(modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(18.dp)) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer-sweep",
    )
    Box(
        modifier
            .clip(shape)
            .background(Color(0x0AFFFFFF))
            .drawBehind {
                val startX = -size.width + (size.width * 2.4f) * sweep
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color.Transparent, Color(0x1FFFFFFF), Color.Transparent),
                        start = Offset(startX, 0f),
                        end = Offset(startX + size.width * 0.55f, size.height),
                    ),
                    size = size,
                )
            },
    )
}

/**
 * Artwork with a designed failure: while it loads a shimmer holds the space, and if it never
 * arrives the space is filled with the title's initials on a tinted panel. A missing cover is
 * common (a dead CDN, a source without artwork) and a grey rectangle reads as a rendering bug.
 */
@Composable
fun Cover(
    url: String?,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(16.dp),
    contentScale: ContentScale = ContentScale.Crop,
    border: Boolean = true,
    fallback: String? = null,
) {
    var settled by remember(url) { mutableStateOf(false) }
    var failed by remember(url) { mutableStateOf(false) }
    Box(
        modifier
            .clip(shape)
            .background(Color(0x0FFFFFFF))
            .then(
                if (border) {
                    Modifier.border(1.dp, Color(0x1AFFFFFF), shape)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null && !failed) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                onState = { state ->
                    if (state is AsyncImagePainter.State.Success) settled = true
                    if (state is AsyncImagePainter.State.Error) {
                        failed = true
                        settled = true
                    }
                },
            )
        } else {
            settled = true
        }
        if (!settled) {
            Shimmer(Modifier.fillMaxSize(), shape)
        } else if (failed || url == null) {
            InitialsPanel(fallback)
        }
    }
}

/** First letters of the first two words, on a faint accent wash. */
@Composable
private fun InitialsPanel(text: String?) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(Color(0x1AFFFFFF), Palette.accentFill(0.10f)))),
        contentAlignment = Alignment.Center,
    ) {
        val initials = remember(text) {
            text.orEmpty()
                .split(' ', '\n')
                .filter { it.isNotBlank() }
                .take(2)
                .map { it.first().uppercaseChar() }
                .joinToString("")
        }
        Text(
            text = initials.ifEmpty { "·" },
            style = MaterialTheme.typography.titleLarge,
            color = Palette.InkTertiary,
        )
    }
}

/** A poster in the browse grid: artwork, a scrim, and the title sitting on the artwork. */
@Composable
fun PosterCard(
    title: String,
    coverUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    badge: String? = null,
) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .aspectRatio(0.66f)
            .pressable(onClick, onLongClick, scaleDown = 0.955f)
            .shadow(
                elevation = 18.dp,
                shape = shape,
                clip = false,
                ambientColor = Palette.Shadow.copy(alpha = 0.5f),
                spotColor = Palette.Shadow.copy(alpha = 0.7f),
            )
            .clip(shape),
    ) {
        Cover(coverUrl, Modifier.fillMaxSize(), shape, border = false, fallback = title)
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color(0x05000000), Color(0xE6000000)),
                        startY = 90f,
                    ),
                ),
        )
        if (badge != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xB3000000))
                    .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(50))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.Ink,
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = Palette.Ink,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp),
        )
    }
}

@Composable
fun ListRow(
    title: String,
    coverUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    /** Shown instead of artwork when there is none, e.g. a chapter number. */
    badge: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = 8.dp,
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (coverUrl != null) {
                Cover(
                    url = coverUrl,
                    modifier = Modifier.width(52.dp).height(72.dp),
                    shape = RoundedCornerShape(12.dp),
                    fallback = title,
                )
            } else {
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(Color(0x14FFFFFF))
                        .border(1.dp, Color(0x14FFFFFF), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = badge ?: "·",
                        style = MaterialTheme.typography.labelMedium,
                        color = Palette.Ink,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Palette.Ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.InkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing()
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleLarge,
            color = Palette.Ink,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

@Composable
fun ProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 5.dp,
    track: Color = Color(0x1AFFFFFF),
) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessLow),
        label = "progress",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(track),
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .height(height)
                .clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient(Palette.AccentGradient)),
        )
    }
}

@Composable
fun EmptyState(
    glyph: Glyph,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(62.dp)
                .glass(CircleShape, elevation = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(glyph, size = 26.dp, tint = Palette.InkSecondary, stroke = 1.7.dp)
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Palette.Ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = Palette.InkSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (actionText != null && onAction != null) {
            PillButton(
                text = actionText,
                onClick = onAction,
                tone = PillTone.Accent,
                modifier = Modifier.padding(top = 18.dp),
            )
        }
    }
}

@Composable
fun Toast(
    text: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    AnimatedVisibility(
        visible = true,
        modifier = modifier,
        enter = fadeIn() + slideInVertically(initialOffsetY = { -it / 2 }),
        exit = fadeOut(),
    ) {
        Row(
            modifier = Modifier
                .glass(RoundedCornerShape(50), strong = true, elevation = 20.dp)
                .padding(start = 16.dp, end = if (actionText != null) 6.dp else 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(Palette.AccentGradient)),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = Palette.Ink,
                maxLines = 2,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (actionText != null && onAction != null) {
                PillButton(
                    text = actionText,
                    onClick = onAction,
                    tone = PillTone.Quiet,
                    glyph = Glyph.Undo,
                )
            }
        }
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Palette.Separator),
    )
}

/** An eyebrow label: small, spaced out, used above sections and over artwork. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = Palette.InkTertiary) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

// ---------------------------------------------------------------------------
// Settings controls
// ---------------------------------------------------------------------------

/** A switch that shares the accent, so it stays legible on every accent choice. */
@Composable
fun Toggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(50)
    val knob by animateDpAsState(
        targetValue = if (checked) 22.dp else 3.dp,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
        label = "toggle-knob",
    )
    Box(
        modifier = modifier
            .size(width = 46.dp, height = 28.dp)
            .clip(shape)
            .then(
                if (checked) {
                    Modifier
                        .background(Brush.linearGradient(Palette.AccentGradient))
                        .border(1.dp, Palette.accentEdge(0.5f), shape)
                } else {
                    Modifier
                        .background(Color(0x14FFFFFF))
                        .border(1.dp, Color(0x1AFFFFFF), shape)
                },
            )
            .pressable({ onCheckedChange(!checked) }, scaleDown = 0.94f),
    ) {
        Box(
            Modifier
                .offset { IntOffset(knob.roundToPx(), 3.dp.roundToPx()) }
                .size(22.dp)
                .clip(CircleShape)
                .background(if (checked) Color(0xFF0C0C16) else Palette.InkSecondary),
        )
    }
}

/** One line of a settings group: a label, an optional explanation, and a control. */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        elevation = 4.dp,
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = Palette.Ink,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.InkSecondary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            trailing()
        }
    }
}

/** A labelled segmented choice, the standard control for a small set of options. */
@Composable
fun ChoiceRow(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Column(modifier.fillMaxWidth()) {
        SettingRow(title, subtitle = subtitle) {
            Eyebrow(options.getOrElse(selectedIndex) { options.first() }, color = Palette.Accent)
        }
        Spacer(Modifier.height(6.dp))
        SegmentedControl(options, selectedIndex, onSelect)
    }
}

/**
 * A sheet over the current screen: a scrim that dismisses on tap and a glass card that slides up
 * from the bottom. Used for filters, the chapter list and pickers, so every screen behaves alike.
 */
@Composable
fun Sheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Back closes the sheet before it leaves the screen: otherwise a pressed-back filter sheet
    // would exit the app on a tab that has nothing to pop.
    BackHandler(enabled = visible, onBack = onDismiss)

    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xCC000000))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
    }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glass(
                    RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    strong = true,
                    elevation = 28.dp,
                )
                .windowInsetsPadding(WindowInsets.navigationBars)
                .heightIn(max = 620.dp)
                .padding(horizontal = 18.dp, vertical = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Palette.Ink,
                    modifier = Modifier.weight(1f),
                )
                IconButton(Glyph.Close, onDismiss, size = 34.dp, tint = Palette.InkSecondary)
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.0f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
