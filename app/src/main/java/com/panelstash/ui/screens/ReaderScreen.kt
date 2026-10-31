package com.panelstash.ui.screens

import android.app.Activity
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Precision
import com.panelstash.data.PageFit
import com.panelstash.data.ReaderPage
import com.panelstash.ui.AppViewModel
import com.panelstash.ui.Eyebrow
import com.panelstash.ui.FALLBACK_PAGE_ASPECT
import com.panelstash.ui.GlassCard
import com.panelstash.ui.Glyph
import com.panelstash.ui.IconButton
import com.panelstash.ui.ListRow
import com.panelstash.ui.PillButton
import com.panelstash.ui.PillTone
import com.panelstash.ui.ProgressBar
import com.panelstash.ui.Sheet
import com.panelstash.ui.theme.Palette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ceil

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

    // The reader owns the whole window. Left alone, the clock and battery icons sat on top of
    // the artwork, which is brightest exactly where they are. They come back on the way out.
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
            ?: return@DisposableEffect onDispose { }
        val controller = WindowInsetsControllerCompat(window, view)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
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
    val window = LocalWindowInfo.current.containerSize
    val widthPx = window.width
    LaunchedEffect(widthPx) { vm.setViewportWidth(widthPx) }
    val heightPx = window.height
    // How tall one screen is, as a width-over-height ratio. A page can only be shown whole at a
    // readable size if it is not much taller than this; a webtoon strip is many screens tall.
    val viewportAspect = if (heightPx > 0) widthPx.toFloat() / heightPx else 0f

    // Shapes of pages the source did not measure, learned from the first decode. A page taller
    // than one decodable bitmap is cut into bands, and that decision needs its height.
    val measuredAspects = remember(current.chapter.id) { mutableStateMapOf<String, Float>() }
    // A page is cut into bands only when it cannot be decoded as one bitmap at screen width.
    // Shorter than that and a single request is both sharper and cheaper.
    val slots by remember(current.chapter.id, current.pages, widthPx, heightPx) {
        derivedStateOf {
            val pages = current.pages
            val bands = IntArray(pages.size) { index ->
                val page = pages[index]
                val measured = page.aspectOrNull()
                pageBandCount(
                    widthPx = widthPx,
                    heightPx = heightPx,
                    aspect = measured ?: measuredAspects[page.key],
                    measured = measured != null,
                )
            }
            val firstSlot = IntArray(pages.size)
            var total = 0
            for (index in pages.indices) {
                firstSlot[index] = total
                total += bands[index]
            }
            val pageOf = IntArray(total)
            val bandOf = IntArray(total)
            for (index in pages.indices) {
                for (band in 0 until bands[index]) {
                    pageOf[firstSlot[index] + band] = index
                    bandOf[firstSlot[index] + band] = band
                }
            }
            ReaderLayout(firstSlot = firstSlot, pageOf = pageOf, bandOf = bandOf, bands = bands, count = total)
        }
    }

    // Effects that outlive a composition (progress, resume) read the layout through this, so a
    // page that turns into bands is seen at its new index rather than the one it had at launch.
    val liveSlots by rememberUpdatedState(slots)

    var markedFinished by remember(current.chapter.id) { mutableStateOf(false) }
    var chromeVisible by remember(current.chapter.id) { mutableStateOf(true) }
    var showChapters by remember { mutableStateOf(false) }
    // Until the resume jump has landed, the list reports index 0 and would overwrite the saved
    // page with it. Progress is only recorded once the starting page is actually on screen.
    var resumeApplied by remember(current.chapter.id) { mutableStateOf(false) }
    val tapScroll = preferences.tapScroll

    // Resume first, then follow the direction of travel. The two belong together: a scroll to the
    // saved page is a jump, not the reader scrolling back up, so watching has to start after it.
    // The chrome comes back when the reader scrolls up the chapter and goes again when they
    // scroll on. A little travel is required before it moves, so a fling settling on a frame or
    // two does not make the bars flicker.
    LaunchedEffect(current.chapter.id) {
        if (current.startPage > 0) {
            listState.scrollToItem(liveSlots.firstSlot.getOrElse(current.startPage) { 0 })
        }
        resumeApplied = true
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

    LaunchedEffect(current.chapter.id) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { slot ->
            if (!resumeApplied) return@collect
            // One page is several bands, so the list position is a band, not a page number.
            val index = liveSlots.pageOf.getOrElse(slot) { current.pages.lastIndex.coerceAtLeast(0) }
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

    /**
     * The trailing viewport as the current layout sees it: how much of the window the list's last
     * item covers. 0 while it is off screen, 1 once it fills the window.
     *
     * It is read from the layout's own last item rather than from a remembered item index. A page
     * that turns into bands, or one whose shape is corrected when its bytes land, changes the item
     * count mid-chapter, and a remembered index then names an ordinary page that can be tall
     * enough to look exactly like the end.
     */
    val endFill = remember(current.chapter.id) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf 0f
            if (last.index != layout.totalItemsCount - 1) return@derivedStateOf 0f
            val viewport = layout.viewportSize.height
            if (last.size <= 0 || viewport <= 0) {
                0f
            } else {
                ((viewport - last.offset).toFloat() / last.size).coerceIn(0f, 1f)
            }
        }
    }

    // True while a finger is on the page. The ring only fills while the reader is actually
    // dragging it: a fling that carries the list past the last page is distance nobody asked for,
    // and completing the ring from it turned the chapter over under the reader's thumb.
    var fingerDown by remember(current.chapter.id) { mutableStateOf(false) }

    // Kept as a running maximum: a finger that reaches the end and lifts there keeps its fill.
    // A boxed Float would allocate on every scroll frame; this state is written that often.
    var ringFilled by remember(current.chapter.id) { mutableFloatStateOf(0f) }
    LaunchedEffect(current.chapter.id) {
        snapshotFlow { fingerDown to endFill.value }.collect { (dragging, fill) ->
            if (fill <= 0f) {
                // Scrolling back out of the end means the chapter is being read again, so the
                // ring has to be earned again rather than jump back to full on the next touch.
                ringFilled = 0f
            } else if (dragging && fill > ringFilled) {
                ringFilled = fill
            }
        }
    }

    // One shot per chapter: two frames that both look complete must not open two chapters.
    var advanced by remember(current.chapter.id) { mutableStateOf(false) }
    LaunchedEffect(current.chapter.id) {
        snapshotFlow { ringFilled }.collect { value ->
            if (value >= 0.985f && !advanced) {
                advanced = true
                vm.advance(current.chapter.id)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(current.chapter.id, preferences.pageFit, tapScroll) {
                    // Taps are read here rather than with detectTapGestures because a tap scroll
                    // has to be retargetable: touching a moving page stops it where it is, and
                    // lifting starts a fresh scroll from there, however many taps deep the
                    // reader is. The gesture is never consumed, so dragging still scrolls.
                    var tapScrollJob: Job? = null
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // The end ring may only fill under a finger, so the drag has to be visible
                        // to the end-of-chapter logic above.
                        fingerDown = true
                        tapScrollJob?.cancel()
                        var dragged = false
                        var last = down
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            last = change
                            // Measured from where the finger went down, not from the previous
                            // event: a slow scroll moves less than the slop between two frames,
                            // so a per-event check let every slow drag end as a tap. A change
                            // the scrollable has consumed is a drag whatever the distance.
                            if (change.isConsumed ||
                                (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                            ) {
                                dragged = true
                            }
                            if (!change.pressed) break
                        }
                        // The finger is up: whatever the list does from here is a fling, and a
                        // fling must never be able to complete the ring.
                        fingerDown = false
                        // A drag belongs to the scrollable, and a long hold was the reader
                        // deliberately stopping the page, so neither counts as a tap.
                        val heldFor = last.uptimeMillis - down.uptimeMillis
                        if (dragged || heldFor >= viewConfiguration.longPressTimeoutMillis) {
                            return@awaitEachGesture
                        }

                        // A webtoon is one long scroll, so a tap carries the reader forward: less
                        // than a full screenful, so the story keeps its context, and animated, so
                        // the move is followed rather than teleported over.
                        val viewport = size.height.toFloat()
                        // Fit page shows one page at a time, so a 70% nudge would leave the
                        // reader looking at two halves of one: a whole viewport lands on the
                        // next page and still honours the chosen duration.
                        val distance = if (preferences.pageFit == PageFit.HEIGHT) {
                            viewport
                        } else {
                            viewport * TAP_FRACTION
                        }
                        tapScrollJob = scope.launch {
                            // A tap that lands while the list is still winding down from the
                            // previous one can lose the scroll lock to the gesture that stopped
                            // it. Asking again on the next frame keeps the tap from being lost.
                            repeat(TAP_SCROLL_TRIES) {
                                try {
                                    if (tapScroll.durationMs <= 0) {
                                        listState.scrollBy(distance)
                                    } else {
                                        listState.animateScrollBy(
                                            value = distance,
                                            animationSpec = tween(
                                                durationMillis = tapScroll.durationMs,
                                                easing = FastOutSlowInEasing,
                                            ),
                                        )
                                    }
                                    return@launch
                                } catch (error: CancellationException) {
                                    // Cancelled by a new tap or by the reader touching the page:
                                    // that is the gesture winning, not a lock to wait out.
                                    if (!isActive) throw error
                                    withFrameNanos { }
                                }
                            }
                        }
                    }
                },
        ) {
            items(slots.count) { slot ->
                val pageIndex = slots.pageOf.getOrElse(slot) { 0 }
                val bands = slots.bands.getOrElse(pageIndex) { 1 }
                val page = current.pages[pageIndex]
                val model = when (page) {
                    is ReaderPage.Local -> page.file
                    is ReaderPage.Remote -> page.url
                }
                if (bands <= 1) {
                    ReaderPageImage(
                        model = model,
                        aspectHint = page.aspectOrNull(),
                        sourceWidth = page.width,
                        sourceHeight = page.height,
                        fitPage = preferences.pageFit == PageFit.HEIGHT,
                        viewportAspect = viewportAspect,
                        widthPx = widthPx,
                        onMeasured = { aspect -> measuredAspects[page.key] = aspect },
                    )
                } else {
                    // A band is 1/n of a page, so it is n times as wide as it is tall relative to
                    // the page's own shape.
                    val pageAspect = page.aspectOrNull() ?: measuredAspects[page.key] ?: FALLBACK_PAGE_ASPECT
                    PageBand(
                        model = model,
                        band = slots.bandOf.getOrElse(slot) { 0 },
                        bands = bands,
                        aspect = pageAspect * bands,
                    )
                }
            }

            // One viewport of travel after the last page, with the end card drawn inside it. The
            // card therefore scrolls in with this empty stretch and can never sit over the last
            // page, which is what a card pinned to the window does — and it cannot be tapped
            // before it is visible either. Without the item the list would end on the last page
            // and the card could never be reached.
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillParentMaxHeight(1f)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    EndCard(
                        progress = ringFilled,
                        hasNext = current.nextChapterId != null,
                        onAdvance = { vm.advance(current.chapter.id) },
                        onDetails = { showChapters = true },
                    )
                }
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
                pageOf = {
                    val page = slots.pageOf.getOrElse(listState.firstVisibleItemIndex) { 0 }
                    (page + 1).coerceAtMost(current.pages.size)
                },
                onChapters = { showChapters = true },
            )
        }

        if (showChapters) {
            val chapters by remember(current.manhwa.id) { vm.chapters(current.manhwa.id) }
                .collectAsState(initial = emptyList())
            Sheet(
                visible = showChapters,
                onDismiss = { showChapters = false },
                title = current.manhwa.title,
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(chapters, key = { it.id }) { chapter ->
                        val here = chapter.id == current.chapter.id
                        ListRow(
                            title = "Chapter ${chapter.numberText}",
                            coverUrl = null,
                            badge = chapter.numberText.take(4),
                            subtitle = chapter.title,
                            onClick = {
                                showChapters = false
                                vm.switchChapter(chapter.id)
                            },
                            trailing = {
                                if (here) Eyebrow("READING", color = Palette.Accent)
                            },
                        )
                    }
                }
            }
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
/** One page, or one band of a page, as the list sees it. */
private class ReaderLayout(
    /** Page index to the first slot it owns. */
    val firstSlot: IntArray,
    /** Slot index to the page it belongs to. */
    val pageOf: IntArray,
    /** Slot index to which band of its page it is. */
    val bandOf: IntArray,
    /** Page index to how many bands it is cut into. */
    val bands: IntArray,
    val count: Int,
)

/**
 * One band of a page too tall to decode whole, asked for and drawn as its own slice of the list.
 * Every band reuses the page's cached bytes, so the extra slices cost decoding time, not traffic.
 */
@Composable
private fun PageBand(model: Any, band: Int, bands: Int, aspect: Float) {
    val context = LocalContext.current
    val request = remember(model, band, bands) { bandRequest(context, model, band, bands) }
    // The shape of the page is an estimate, and cutting it into equal bands amplifies any error
    // in it by the number of bands: at 26 bands a fifth of a percent left a five-pixel strip of
    // background at every seam. The band's own shape is exact, so it is taken from the decoded
    // bitmap as soon as it arrives.
    var bandAspect by remember(model, band, bands) { mutableFloatStateOf(aspect) }
    AsyncImage(
        model = request,
        contentDescription = null,
        // Pinned to the top so the band's one row of overlap is cropped from its bottom edge,
        // where the band below carries the same pixels.
        alignment = Alignment.TopCenter,
        contentScale = ContentScale.FillWidth,
        onState = { state ->
            if (state is AsyncImagePainter.State.Success) {
                val size = state.painter.intrinsicSize
                if (size.width > 0f && size.height > 0f) bandAspect = size.width / size.height
            }
        },
        modifier = Modifier.fillMaxWidth().aspectRatio(bandAspect),
    )
}

@Composable
private fun LazyItemScope.ReaderPageImage(
    model: Any,
    aspectHint: Float?,
    sourceWidth: Int,
    sourceHeight: Int,
    fitPage: Boolean,
    viewportAspect: Float,
    widthPx: Int,
    onMeasured: (Float) -> Unit,
) {
    val context = LocalContext.current
    var aspect by remember(model) { mutableFloatStateOf(aspectHint ?: FALLBACK_PAGE_ASPECT) }
    // Fit page shows the whole page at once, with black above and below, instead of scrolling it.
    // That only works while the page is not much taller than the screen it has to fit: a 720×4000
    // webtoon strip sized that way became a 40% wide column of artwork with black either side,
    // which is the opposite of what the mode is for. Pages too tall to show at full width fill
    // the width and scroll instead.
    val wholePage = fitPage && viewportAspect > 0f && aspect >= viewportAspect
    val shape = if (wholePage) {
        Modifier.fillMaxWidth().fillParentMaxHeight()
    } else {
        Modifier.fillMaxWidth().aspectRatio(aspect)
    }

    val request = remember(model, widthPx, aspectHint, sourceWidth, sourceHeight) {
        if (widthPx <= 0) {
            model
        } else {
            // Never ask for more pixels than the source has: an 800 px webtoon strip scaled to
            // a 1080 px viewport is a 20,000 px-tall bitmap, past the texture limit on many
            // phones, and the page renders as a black rectangle.
            val requestWidth = if (sourceWidth > 0) minOf(widthPx, sourceWidth) else widthPx
            val estimatedHeight = when {
                sourceWidth > 0 && sourceHeight > 0 ->
                    (requestWidth.toLong() * sourceHeight / sourceWidth).toInt()

                else -> (requestWidth / (aspectHint ?: FALLBACK_PAGE_ASPECT)).toInt()
            }.coerceAtLeast(1)
            val requestHeight = estimatedHeight.coerceAtMost(MAX_BITMAP_DIMENSION)
            ImageRequest.Builder(context)
                .data(model)
                .size(requestWidth, requestHeight)
                .precision(Precision.INEXACT)
                // A hardware bitmap is uploaded as one texture. Very tall strips exceed the
                // limit and fail to draw, so those decode in software instead.
                .allowHardware(requestHeight <= MAX_HARDWARE_DIMENSION)
                .build()
        }
    }

    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = if (wholePage) ContentScale.Fit else ContentScale.FillWidth,
        onState = { state ->
            if (state is AsyncImagePainter.State.Success) {
                val size = state.painter.intrinsicSize
                if (size.width > 0f && size.height > 0f) {
                    val measured = size.width / size.height
                    aspect = measured
                    // Only pages whose source did not measure them need telling: the answer
                    // decides whether the page has to be cut into bands.
                    if (aspectHint == null) onMeasured(measured)
                }
            }
        },
        modifier = shape,
    )
}

/**
 * Conservative ceiling for a page drawn as one bitmap on GPUs whose texture limit is 8192 px.
 *
 * It is also what decides banding. A page taller than this at screen width cannot be a hardware
 * bitmap, so it decodes in software — and a 1080 x 16,384 software bitmap is 70 MB, far past what
 * a phone can hold for more than one page. A run of 13,000 px strips (Asura's, for instance) would
 * exhaust the heap mid-chapter, which is why the limit here is the texture size, not the bitmap
 * ceiling below.
 */
private const val MAX_HARDWARE_DIMENSION = 8_192

/** Bands a single page may be cut into. Past this the page is pathological, not a webtoon. */
private const val MAX_PAGE_BANDS = 60

/** How many screens a page of unknown height may be before it is worth cutting into bands. */
private const val UNMEASURED_PAGE_SCREENS = 2f

/** Last-resort cap for a single request, for a page whose height nobody has measured yet. */
private const val MAX_BITMAP_DIMENSION = 16_384

/** How much of the readable screen a tap carries the reader forward. */
private const val TAP_FRACTION = 0.7f

/** How far the reader has to travel before the chrome follows them. */
private const val CHROME_TRAVEL = 24L

/** Larger than any item's offset, so position stays ordered across item boundaries. */
private const val POSITION_UNIT = 1_000_000L

/** How many frames a tap gives the previous scroll session to release the list. */
private const val TAP_SCROLL_TRIES = 3

/**
 * How many bands a page is cut into at [widthPx].
 *
 * A page the source measured is decoded at exactly its own size, so the texture limit is what
 * forces bands. A page nobody measured is requested blind and comes back sampled to a guess, so
 * anything more than a couple of screens tall is banded to get its detail back.
 */
internal fun pageBandCount(
    widthPx: Int,
    heightPx: Int,
    aspect: Float?,
    measured: Boolean,
): Int {
    if (aspect == null || aspect <= 0f || widthPx <= 0 || heightPx <= 0) return 1
    val displayHeight = widthPx / aspect
    val limit = if (measured) MAX_HARDWARE_DIMENSION.toFloat() else heightPx * UNMEASURED_PAGE_SCREENS
    if (displayHeight <= limit) return 1
    return ceil(displayHeight / heightPx).toInt().coerceIn(2, MAX_PAGE_BANDS)
}

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
                color = Palette.ReaderInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Eyebrow("CHAPTER $chapter", color = Palette.ReaderInkSecondary)
        }
    }
}

@Composable
private fun ReaderBottomBar(total: Int, pageOf: () -> Int, onChapters: () -> Unit) {
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
        ProgressBar(progress, height = 3.dp, track = Color(0x33FFFFFF))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("PAGE $page / $total", color = Palette.ReaderInkSecondary)
            Spacer(Modifier.weight(1f))
            PillButton("Chapter list", onChapters, tone = PillTone.Glass)
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
                            color = Palette.Track,
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
