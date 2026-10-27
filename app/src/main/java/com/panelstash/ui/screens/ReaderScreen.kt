package com.panelstash.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.allowHardware
import com.panelstash.data.PageFit
import com.panelstash.data.ReaderPage
import com.panelstash.ui.AppViewModel
import com.panelstash.ui.Eyebrow
import com.panelstash.ui.GlassCard
import com.panelstash.ui.Glyph
import com.panelstash.ui.IconButton
import com.panelstash.ui.PillButton
import com.panelstash.ui.PillTone
import com.panelstash.ui.ProgressBar
import com.panelstash.ui.theme.Palette
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

@Composable
fun ReaderScreen(vm: AppViewModel) {
    val state by vm.reader.collectAsState()
    val preferences by vm.preferences.collectAsState()
    val current = state
    val view = LocalView.current

    // A chapter is long enough that the screen would otherwise sleep halfway through it.
    DisposableEffect(preferences.keepAwake) {
        view.keepScreenOn = preferences.keepAwake
        onDispose { view.keepScreenOn = false }
    }

    if (current == null) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // The window's own width, not the screen's: a split-screen or freeform window is narrower.
    // Pages are decoded at this size, and prefetches are told about it, so both ask for the same
    // bitmap and the second one is a memory-cache hit.
    val widthPx = LocalWindowInfo.current.containerSize.width
    LaunchedEffect(widthPx) { vm.setViewportWidth(widthPx) }

    var armed by remember(current.chapter.id) { mutableStateOf(false) }
    var markedFinished by remember(current.chapter.id) { mutableStateOf(false) }
    var chromeVisible by remember(current.chapter.id) { mutableStateOf(true) }
    val tapScroll = preferences.tapScroll

    // Resume first, then follow the direction of travel. The two belong together: a scroll to the
    // saved page is a jump, not the reader scrolling back up, so watching has to start after it.
    // The chrome comes back when the reader scrolls up the chapter and goes again when they
    // scroll on. A little travel is required before it moves, so a fling settling on a frame or
    // two does not make the bars flicker.
    LaunchedEffect(current.chapter.id) {
        if (current.startPage > 0) listState.scrollToItem(current.startPage)
        var previous = -1L
        var travel = 0L
        snapshotFlow {
            listState.isScrollInProgress to
                (listState.firstVisibleItemIndex.toLong() * POSITION_UNIT + listState.firstVisibleItemScrollOffset)
        }.collect { (scrolling, position) ->
            val delta = if (previous < 0) 0L else position - previous
            previous = position
            // Only the reader moving the list counts. A resume jump, or a page that measures
            // itself taller once its bytes land, changes the same numbers without anybody
            // scrolling, and neither should move the chrome.
            if (!scrolling || delta == 0L || delta >= POSITION_UNIT || delta <= -POSITION_UNIT) {
                return@collect
            }
            travel = if (travel == 0L || (travel > 0) == (delta > 0)) travel + delta else delta
            when {
                travel <= -CHROME_TRAVEL && !chromeVisible -> {
                    chromeVisible = true
                    travel = 0L
                }

                travel >= CHROME_TRAVEL && chromeVisible -> {
                    chromeVisible = false
                    travel = 0L
                }
            }
        }
    }

    // The end ring is a session event: it only arms once the user actually scrolls.
    LaunchedEffect(current.chapter.id) {
        snapshotFlow { listState.firstVisibleItemScrollOffset }
            .drop(1)
            .collect { armed = true }
    }

    LaunchedEffect(current.chapter.id) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
            if (index <= current.pages.lastIndex) {
                vm.saveProgress(current.manhwa.id, current.chapter.id, index)
                // Keep the next few pages warm as the reader moves down the chapter, so a fast
                // scroll or a tap never waits on the network for a page that is about to appear.
                vm.prefetch(current.pages, index)
            }
            if (index >= current.pages.lastIndex && !markedFinished) {
                markedFinished = true
                vm.markFinished(current.chapter.id)
            }
        }
    }

    val endIndex = current.pages.size

    /** How far the end card has been pushed off the top, which is what the ring shows. */
    val endCardProgress = remember(current.chapter.id) {
        derivedStateOf {
            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == endIndex }
            if (info == null || info.size == 0) 0f
            else ((-info.offset).toFloat() / info.size).coerceIn(0f, 1f)
        }
    }

    /** True once the trailing viewport is fully on screen, i.e. the list is scrolled to its end. */
    val atBottom = remember(current.chapter.id) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()
            last != null && last.index == layout.totalItemsCount - 1 && last.offset <= 0
        }
    }

    // Kept as a running maximum: a fling can carry the card out of view in a single frame, and
    // the ring should not fall back to empty once the user has scrolled that far.
    // A boxed Float would allocate on every scroll frame; this state is written that often.
    var ringFilled by remember(current.chapter.id) { mutableFloatStateOf(0f) }
    LaunchedEffect(current.chapter.id) {
        snapshotFlow { endCardProgress.value }.collect { value ->
            if (value > ringFilled) ringFilled = value
        }
    }

    val progress = when {
        !armed -> 0f
        atBottom.value -> 1f
        else -> ringFilled
    }

    LaunchedEffect(current.chapter.id, progress) {
        if (armed && progress >= 0.985f) vm.advance(current.chapter.id)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(current.chapter.id, preferences.pageFit, tapScroll) {
                    detectTapGestures(
                        // A webtoon is one long scroll, so a tap carries the reader forward: less
                        // than a full screenful, so the story keeps its context, and animated, so
                        // the move is followed rather than teleported over. Back is a scroll up,
                        // which also brings the chrome back.
                        onTap = {
                            val viewport = size.height.toFloat()
                            scope.launch {
                                when {
                                    preferences.pageFit == PageFit.HEIGHT -> {
                                        // Fit page shows one page at a time, so a 70% nudge would
                                        // leave the reader looking at two halves of one.
                                        val index = listState.firstVisibleItemIndex
                                        if (index >= current.pages.size) return@launch
                                        val next = (index + 1).coerceAtMost(current.pages.size)
                                        if (tapScroll.durationMs <= 0) {
                                            listState.scrollToItem(next)
                                        } else {
                                            listState.animateScrollToItem(next)
                                        }
                                    }

                                    tapScroll.durationMs <= 0 ->
                                        listState.scrollBy(viewport * TAP_FRACTION)

                                    else -> listState.animateScrollBy(
                                        value = viewport * TAP_FRACTION,
                                        animationSpec = tween(
                                            durationMillis = tapScroll.durationMs,
                                            easing = FastOutSlowInEasing,
                                        ),
                                    )
                                }
                            }
                        },
                    )
                },
        ) {
            items(current.pages.size) { index ->
                val page = current.pages[index]
                ReaderPageImage(
                    model = when (page) {
                        is ReaderPage.Local -> page.file
                        is ReaderPage.Remote -> page.url
                    },
                    aspectHint = page.aspectOrNull(),
                    fitHeight = preferences.pageFit == PageFit.HEIGHT,
                    widthPx = widthPx,
                )
            }

            item {
                EndCard(
                    progress = progress,
                    hasNext = current.nextChapterId != null,
                    onAdvance = { vm.advance(current.chapter.id) },
                    onDetails = { vm.back() },
                    modifier = Modifier.fillParentMaxHeight(1f),
                )
            }

            // One more viewport to scroll, so the card can move past itself and fill the ring.
            // Without it the list ends exactly on the card and the ring can never complete.
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillParentMaxHeight(1f)
                        .background(Color.Black),
                )
            }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
        ) {
            ReaderTopBar(
                title = current.manhwa.title,
                chapter = current.chapter.numberText,
                onBack = { vm.back() },
            )
        }

        AnimatedVisibility(
            visible = chromeVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
        ) {
            ReaderBottomBar(
                total = current.pages.size,
                pageOf = { (listState.firstVisibleItemIndex + 1).coerceAtMost(current.pages.size) },
                onExit = { vm.back() },
            )
        }
    }
}

/**
 * Pages are tall images with no intrinsic size until they load. Reserving their shape up front
 * keeps the list measurable: without it every item is zero-height, the viewport lands on the
 * last one, and the chapter looks empty.
 *
 * When the source reports page dimensions the reserved shape is exact, so nothing moves once the
 * bytes arrive. Requests are also sized to the screen, which makes them a third smaller, and lets
 * a page the prefetcher already fetched be served from the disk cache unchanged.
 */
@Composable
private fun LazyItemScope.ReaderPageImage(
    model: Any,
    aspectHint: Float?,
    fitHeight: Boolean,
    widthPx: Int,
) {
    val context = LocalContext.current
    var aspect by remember(model) { mutableFloatStateOf(aspectHint ?: FALLBACK_PAGE_ASPECT) }
    // Fit page shows the whole page at once, with black above and below, instead of scrolling it.
    val shape = if (fitHeight) {
        Modifier.fillMaxWidth().fillParentMaxHeight()
    } else {
        Modifier.fillMaxWidth().aspectRatio(aspect)
    }

    val request = remember(model, widthPx, aspectHint) {
        if (widthPx <= 0 || model !is String) {
            model
        } else {
            val height = (widthPx / (aspectHint ?: FALLBACK_PAGE_ASPECT)).toInt().coerceAtLeast(1)
            ImageRequest.Builder(context)
                .data(model)
                .size(widthPx, height)
                .allowHardware(true)
                .build()
        }
    }

    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = if (fitHeight) ContentScale.Fit else ContentScale.FillWidth,
        onState = { state ->
            if (state is AsyncImagePainter.State.Success) {
                val size = state.painter.intrinsicSize
                if (size.width > 0f && size.height > 0f) aspect = size.width / size.height
            }
        },
        modifier = shape,
    )
}

/** Roughly the shape of one slice of a webtoon page, used until the real size is known. */
private const val FALLBACK_PAGE_ASPECT = 0.64f

/** How much of the readable screen a tap carries the reader forward. */
private const val TAP_FRACTION = 0.7f

/** How far the reader has to travel before the chrome follows them. */
private const val CHROME_TRAVEL = 24L

/** Larger than any item's offset, so position stays ordered across item boundaries. */
private const val POSITION_UNIT = 1_000_000L

@Composable
private fun ReaderTopBar(title: String, chapter: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xE6000000), Color(0x80000000), Color.Transparent)))
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconButton(Glyph.ChevronLeft, onBack, strong = true)
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Palette.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Eyebrow("CHAPTER $chapter", color = Palette.InkSecondary)
        }
    }
}

@Composable
private fun ReaderBottomBar(total: Int, pageOf: () -> Int, onExit: () -> Unit) {
    // Read through a derived state so scrolling only recomposes this bar, never the pages above.
    val page by remember { derivedStateOf { pageOf() } }
    val progress = page.toFloat() / total.coerceAtLeast(1).toFloat()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000), Color(0xE6000000))))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ProgressBar(progress, height = 3.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("PAGE $page / $total", color = Palette.InkSecondary)
            Spacer(Modifier.weight(1f))
            PillButton("Chapter list", onExit, tone = PillTone.Glass)
        }
    }
}

@Composable
private fun EndCard(
    progress: Float,
    hasNext: Boolean,
    onAdvance: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        GlassCard(
            shape = RoundedCornerShape(28.dp),
            strong = true,
            elevation = 20.dp,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 22.dp, vertical = 26.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(84.dp)) {
                        val stroke = 6.dp.toPx()
                        val inset = stroke / 2
                        val arc = Size(size.width - stroke, size.height - stroke)
                        drawArc(
                            color = Color(0x26FFFFFF),
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = arc,
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                        drawArc(
                            brush = Brush.sweepGradient(Palette.AccentGradient),
                            startAngle = -90f,
                            sweepAngle = 360f * progress,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = arc,
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                    }
                    Text(
                        text = if (hasNext) "NEXT" else "END",
                        style = MaterialTheme.typography.labelSmall,
                        color = Palette.InkSecondary,
                    )
                }

                Text(
                    text = if (hasNext) "Keep scrolling" else "That is the end",
                    style = MaterialTheme.typography.titleLarge,
                    color = Palette.Ink,
                    modifier = Modifier.padding(top = 18.dp),
                )
                Text(
                    text = if (hasNext) {
                        "The circle fills as you scroll, then the next chapter opens."
                    } else {
                        "You have run out of chapters for this manhwa."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.InkSecondary,
                    modifier = Modifier.padding(top = 6.dp),
                )

                Row(
                    modifier = Modifier.padding(top = 22.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (hasNext) {
                        PillButton("Next chapter", onAdvance, tone = PillTone.Accent, glyph = Glyph.Play)
                    }
                    PillButton("Chapter list", onDetails, tone = PillTone.Glass)
                }
            }
        }
    }
}
