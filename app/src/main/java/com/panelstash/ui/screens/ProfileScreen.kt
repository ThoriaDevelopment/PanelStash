package com.panelstash.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.panelstash.data.db.ChapterEntity
import com.panelstash.data.db.DownloadEntity
import com.panelstash.data.db.DownloadState
import com.panelstash.ui.AppViewModel
import com.panelstash.ui.ChapterLoad
import com.panelstash.ui.Chip
import com.panelstash.ui.Cover
import com.panelstash.ui.EmptyState
import com.panelstash.ui.Eyebrow
import com.panelstash.ui.Glyph
import com.panelstash.ui.GlassCard
import com.panelstash.ui.GlyphIcon
import com.panelstash.ui.IconButton
import com.panelstash.ui.PillButton
import com.panelstash.ui.PillTone
import com.panelstash.ui.PosterCard
import com.panelstash.ui.ProgressBar
import com.panelstash.ui.SectionTitle
import com.panelstash.ui.SearchPill
import com.panelstash.ui.SegmentedControl
import com.panelstash.ui.Sheet
import com.panelstash.ui.Shimmer
import com.panelstash.ui.SimilarState
import com.panelstash.ui.TopBar
import com.panelstash.ui.availableStorageBytes
import com.panelstash.ui.formatBytes
import com.panelstash.ui.isMetered
import com.panelstash.ui.pressable
import com.panelstash.ui.theme.Palette

/** Above this many chapters, a download asks before filling the queue and the disk. */
private const val BATCH_CONFIRM_ABOVE = 5

/** How much of a synopsis is shown before "Read more". */
private const val DESCRIPTION_PREVIEW_CHARS = 240

/** A synopsis cut to a preview, and whether there is more of it to show. */
private data class SynopsisPreview(val text: String, val truncated: Boolean)

private fun synopsisPreview(description: String): SynopsisPreview {
    if (description.length <= DESCRIPTION_PREVIEW_CHARS) {
        return SynopsisPreview(description, false)
    }
    val flat = description.replace(Regex("\\s+"), " ").trim()
    if (flat.length <= DESCRIPTION_PREVIEW_CHARS) {
        return SynopsisPreview(description, false)
    }
    val cut = flat.lastIndexOf(' ', DESCRIPTION_PREVIEW_CHARS)
        .let { if (it > DESCRIPTION_PREVIEW_CHARS / 2) it else DESCRIPTION_PREVIEW_CHARS }
    val head = flat.take(cut).trimEnd().trimEnd(',', ';', ':', '-', '—')
    return SynopsisPreview("$head…", true)
}

@Composable
fun ProfileScreen(vm: AppViewModel, manhwaId: Long) {
    val profileFlow = remember(manhwaId) { vm.profile(manhwaId) }
    val state by profileFlow.collectAsState(initial = null)
    val load by vm.chapterLoad.collectAsState()
    val preferences by vm.preferences.collectAsState()
    val followed by remember(manhwaId) { vm.followedState(manhwaId) }.collectAsState(initial = false)
    val later by remember(manhwaId) { vm.readLaterState(manhwaId) }.collectAsState(initial = false)
    val similarState by vm.similar.collectAsState()
    val genresByManhwa by vm.profileGenres.collectAsState()
    val listState = rememberLazyListState()
    var descriptionOpen by remember(manhwaId) { mutableStateOf(false) }
    // Saved, so coming back from a similar title lands on the tab it was opened from.
    var section by rememberSaveable(manhwaId) { mutableStateOf(ProfileSection.CHAPTERS) }
    val context = LocalContext.current
    var pendingDownload by remember { mutableStateOf<List<ChapterEntity>?>(null) }
    var pendingBatch by remember { mutableStateOf<List<ChapterEntity>?>(null) }
    var showJump by remember { mutableStateOf(false) }
    var jumpQuery by remember { mutableStateOf("") }

    /** External chapters are bookmarks to the publisher; everything else opens in the reader. */
    fun openChapter(chapter: ChapterEntity, page: Int = 0) {
        val url = chapter.externalUrl
        if (url != null) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                .onFailure { vm.showMessage("No browser to open this chapter") }
        } else {
            vm.openChapter(chapter.id, page)
        }
    }

    LaunchedEffect(manhwaId) {
        vm.ensureChapters(manhwaId)
        vm.loadGenresFor(manhwaId)
    }

    // Recommendations cost a request, so they are only fetched once the tab is actually opened.
    LaunchedEffect(manhwaId, section) {
        if (section == ProfileSection.SIMILAR) vm.loadSimilar(manhwaId)
    }

    val current = state
    val chapterLoad = if (load?.manhwaId == manhwaId) load else null

    fun queueDownload(chapters: List<ChapterEntity>) {
        if (chapters.isEmpty() || current == null) return
        // The queue's own policy decides whether a phone connection is enough. When it is not,
        // this asks once, for this request only.
        if (preferences.unmeteredOnly && isMetered(context)) {
            pendingDownload = chapters
        } else {
            vm.download(chapters, current.manhwa, metered = !preferences.unmeteredOnly)
        }
    }

    /** A long queue is confirmed once, with the space it has to fit in, before anything starts. */
    fun startDownload(chapters: List<ChapterEntity>) {
        if (chapters.isEmpty() || current == null) return
        if (chapters.size > BATCH_CONFIRM_ABOVE) {
            pendingBatch = chapters
        } else {
            queueDownload(chapters)
        }
    }

    pendingBatch?.let { batch ->
        AlertDialog(
            onDismissRequest = { pendingBatch = null },
            title = { Text("Download ${batch.size} chapters?") },
            text = {
                Text(
                    "They download one after another in the background. " +
                        "${formatBytes(availableStorageBytes())} is free on this device.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingBatch = null
                    queueDownload(batch)
                }) { Text("Download") }
            },
            dismissButton = {
                TextButton(onClick = { pendingBatch = null }) { Text("Cancel") }
            },
        )
    }

    pendingDownload?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingDownload = null },
            title = { Text("Use mobile data?") },
            text = {
                Text(
                    "${pending.size} " +
                        (if (pending.size == 1) "chapter" else "chapters") +
                        " will download now.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDownload = null
                    current?.let { vm.download(pending, it.manhwa, metered = true) }
                }) { Text("Download") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDownload = null }) { Text("Cancel") }
            },
        )
    }

    // The title moves into the bar only once the hero has scrolled away, so it is never on
    // screen twice and the bar still says where the reader is.
    val heroScrolled = listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0

    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = if (heroScrolled) current?.manhwa?.title else null,
            onBack = { vm.back() },
            scrim = false,
        )

        if (current == null) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                Shimmer(Modifier.fillMaxWidth().height(190.dp))
                Spacer(Modifier.height(14.dp))
                Shimmer(Modifier.fillMaxWidth().height(48.dp))
            }
            return
        }

        val manhwa = current.manhwa
        // Cut at a word boundary rather than with maxLines: a maxLines cut put the synopsis's
        // paragraph break on a line of its own, which came out as a row of dots above the link
        // and pushed the link past the card's bottom edge.
        val description = manhwa.description?.trim().orEmpty()
        val preview = remember(description) { synopsisPreview(description) }
        // External chapters are bookmarks to a publisher, so they must not make 100% progress
        // unreachable for a title that also has readable chapters here.
        val readable = current.chapters.filter { it.externalUrl == null }
        val readCount = readable.count { it.readAt != null }
        val continueChapter = current.chapters.firstOrNull {
            it.id == current.resumeChapterId && it.externalUrl == null
        } ?: current.chapters.firstOrNull { it.externalUrl == null }
        val ordered = if (preferences.reverseChapters) {
            current.chapters.asReversed()
        } else {
            current.chapters
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 116.dp),
        ) {
            item(key = "hero") {
                // The hero is as tall as the cover and the text beside it. It used to be a fixed
                // 230dp, which left a dead band between the progress bar and the action row on
                // every title whose blurb was short enough to fit.
                Box {
                    val backdrop = manhwa.coverUrl
                    if (backdrop != null) {
                        Backdrop(backdrop, Modifier.matchParentSize())
                    }
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(
                                Brush.verticalGradient(Palette.heroScrim()),
                            ),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(top = 4.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Cover(
                            url = manhwa.coverUrl,
                            modifier = Modifier.width(104.dp).height(148.dp),
                            shape = RoundedCornerShape(20.dp),
                            fallback = manhwa.title,
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = manhwa.title,
                                style = MaterialTheme.typography.headlineSmall,
                                color = Palette.Ink,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val meta = listOfNotNull(
                                manhwa.year?.toString(),
                                // Sources spell it "Ongoing", "ONGOING" or "ongoing".
                                manhwa.status?.lowercase()?.replaceFirstChar { it.uppercase() },
                                vm.sourceName(manhwa.sourceId),
                            ).joinToString(" · ")
                            if (meta.isNotEmpty()) {
                                Eyebrow(meta, Modifier.padding(top = 6.dp), color = Palette.InkSecondary)
                            }
                            if (readable.isNotEmpty()) {
                                Text(
                                    text = "$readCount of ${readable.size} chapters read",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Palette.InkSecondary,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                                ProgressBar(
                                    fraction = readCount.toFloat() / readable.size,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                    }
                }
            }

            item(key = "actions") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PillButton(
                        text = if (current.resumeChapterId != null) "Continue" else "Start reading",
                        glyph = Glyph.Play,
                        tone = PillTone.Accent,
                        enabled = continueChapter != null,
                        onClick = {
                            continueChapter?.let {
                                val page = if (it.id == current.resumeChapterId) current.resumePage else 0
                                openChapter(it, page)
                            }
                        },
                    )
                    PillButton(
                        text = "Jump",
                        glyph = Glyph.List,
                        enabled = current.chapters.isNotEmpty(),
                        onClick = { showJump = true },
                    )
                }
            }

            item(key = "actions2") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PillButton(
                        text = if (followed) "Following" else "Follow",
                        glyph = Glyph.Bookmark,
                        selected = followed,
                        onClick = { vm.toggleFollow(manhwaId, !followed) },
                    )
                    PillButton(
                        text = if (later) "Saved" else "Later",
                        glyph = Glyph.Clock,
                        selected = later,
                        onClick = { vm.toggleReadLater(manhwaId, !later) },
                    )
                    val downloadable = current.chapters.filter { it.externalUrl == null }
                    PillButton(
                        text = "Download all",
                        glyph = Glyph.Download,
                        enabled = downloadable.isNotEmpty(),
                        onClick = { startDownload(downloadable) },
                    )
                }
            }

            if (current.downloads.isNotEmpty()) {
                item(key = "delete-downloads") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 4.dp),
                    ) {
                        PillButton(
                            text = "Delete ${current.downloads.size} downloaded " +
                                (if (current.downloads.size == 1) "chapter" else "chapters"),
                            glyph = Glyph.Trash,
                            tone = PillTone.Quiet,
                            onClick = { vm.deleteAllDownloads(manhwaId) },
                        )
                    }
                }
            }

            val genres = genresByManhwa[manhwaId].orEmpty()
            if (genres.isNotEmpty()) {
                item(key = "genres") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 4.dp),
                    ) {
                        items(genres, key = { it.id }) { genre ->
                            Chip(
                                text = genre.name,
                                selected = false,
                                onClick = { vm.openGenre(manhwa.sourceId, genre) },
                            )
                        }
                    }
                }
            }

            if (description.isNotEmpty()) {
                item(key = "description") {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(14.dp),
                    ) {
                        Text(
                            text = if (descriptionOpen) description else preview.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.InkSecondary,
                        )
                        if (preview.truncated) {
                            Text(
                                text = if (descriptionOpen) "Show less" else "Read more",
                                style = MaterialTheme.typography.labelLarge,
                                color = Palette.Accent,
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .pressable({ descriptionOpen = !descriptionOpen }),
                            )
                        }
                    }
                }
            }

            item(key = "sections") {
                SegmentedControl(
                    options = listOf("Chapters", "Similar"),
                    selectedIndex = section.ordinal,
                    onSelect = { section = ProfileSection.entries[it] },
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 6.dp),
                )
            }

            if (section == ProfileSection.CHAPTERS) {
                item(key = "chapters-title") {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SectionTitle(
                            text = if (ordered.isEmpty()) "Chapters" else {
                                "Chapters · ${ordered.size}"
                            },
                            modifier = Modifier.weight(1f),
                        )
                        PillButton(
                            text = if (preferences.reverseChapters) "Newest first" else "Oldest first",
                            tone = PillTone.Quiet,
                            onClick = {
                                vm.updatePreferences { it.copy(reverseChapters = !it.reverseChapters) }
                            },
                        )
                        IconButton(
                            glyph = Glyph.Refresh,
                            onClick = { vm.refreshChapters(manhwaId) },
                            size = 34.dp,
                            tint = Palette.InkSecondary,
                            label = "Refresh chapters",
                        )
                    }
                }

                when {
                    ordered.isEmpty() && chapterLoad?.loading == true -> items(5) {
                        Shimmer(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 5.dp)
                                .height(66.dp),
                        )
                    }

                    ordered.isEmpty() && chapterLoad?.error != null -> item(key = "chapters-error") {
                        EmptyState(
                            glyph = Glyph.Refresh,
                            title = "Chapters did not load",
                            message = chapterLoad?.error,
                            actionText = "Try again",
                            onAction = { vm.refreshChapters(manhwaId) },
                        )
                    }

                    ordered.isEmpty() && chapterLoad?.matching == true -> item(key = "chapters-matching") {
                        EmptyState(
                            glyph = Glyph.Compass,
                            title = "Looking on your other sources",
                            message = "${vm.sourceName(manhwa.sourceId)} has no English chapters for " +
                                "this one, which usually means it was licensed away elsewhere. " +
                                "Checking your other sources for the same title.",
                        )
                    }

                    ordered.isEmpty() -> item(key = "chapters-empty") {
                        EmptyState(
                            glyph = Glyph.Compass,
                            title = "No readable chapters here",
                            message = "${vm.sourceName(manhwa.sourceId)} has no English chapters for " +
                                "this one, which usually means it was licensed elsewhere. None of " +
                                "your other sources has it either. Try searching for another copy.",
                        )
                    }

                    else -> items(ordered, key = { it.id }) { chapter ->
                        ChapterCard(
                            chapter = chapter,
                            isResumePoint = chapter.id == current.resumeChapterId,
                            download = current.downloads[chapter.id],
                            onOpen = {
                                val page = if (chapter.id == current.resumeChapterId) current.resumePage else 0
                                openChapter(chapter, page)
                            },
                            onToggleRead = { vm.setChapterRead(chapter.id, chapter.readAt == null) },
                            onDownload = { startDownload(listOf(chapter)) },
                            onDelete = { vm.deleteDownload(chapter.id, chapter.numberText) },
                        )
                    }
                }
            } else {
                similarItems(
                    similar = similarState,
                    onOpen = { vm.openProfile(it) },
                    onRefresh = { vm.loadSimilar(manhwaId, force = true) },
                )
            }
        }
    }

    Sheet(
        visible = showJump,
        onDismiss = { showJump = false },
        title = "Jump to chapter",
    ) {
        val chapterList = current?.chapters.orEmpty()
        val firstUnread = chapterList.firstOrNull { it.readAt == null && it.externalUrl == null }
        val readableChapters = chapterList.filter { it.externalUrl == null }
        // One scroller for the actions, the search field and the chapters. The list used to sit
        // in a fixed 240dp window inside a fixed-height sheet, which left everything past the
        // fold unreachable exactly when a series is long enough to need the jump sheet.
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "actions") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                ) {
                    PillButton(
                        text = "First",
                        tone = PillTone.Quiet,
                        onClick = {
                            readableChapters.firstOrNull()?.let { openChapter(it) }
                            showJump = false
                        },
                    )
                    PillButton(
                        text = "Latest",
                        tone = PillTone.Quiet,
                        onClick = {
                            readableChapters.lastOrNull()?.let { openChapter(it) }
                            showJump = false
                        },
                    )
                    PillButton(
                        text = "Next unread",
                        tone = PillTone.Quiet,
                        enabled = firstUnread != null,
                        onClick = {
                            firstUnread?.let { openChapter(it) }
                            showJump = false
                        },
                    )
                }
            }
            item(key = "search") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 4.dp),
                ) {
                    SearchPill(
                        value = jumpQuery,
                        modifier = Modifier.weight(1f),
                        onValueChange = { jumpQuery = it },
                        onSubmit = {
                            val wanted = jumpQuery.trim().trimStart('0').ifEmpty { "0" }
                            val target = chapterList.firstOrNull { it.numberText == wanted }
                                ?: chapterList.firstOrNull { it.numberText.startsWith(wanted) }
                            if (target != null) {
                                openChapter(target)
                                showJump = false
                            } else {
                                vm.showMessage("No chapter $jumpQuery")
                            }
                        },
                    )
                    PillButton(
                        text = "Go",
                        tone = PillTone.Accent,
                        onClick = {
                            val wanted = jumpQuery.trim().trimStart('0').ifEmpty { "0" }
                            val target = chapterList.firstOrNull { it.numberText == wanted }
                                ?: chapterList.firstOrNull { it.numberText.startsWith(wanted) }
                            if (target != null) {
                                openChapter(target)
                                showJump = false
                            } else {
                                vm.showMessage("No chapter $jumpQuery")
                            }
                        },
                    )
                }
            }
            items(
                items = if (preferences.reverseChapters) chapterList.asReversed() else chapterList,
                // Keyed by chapter, not by position: reversing the order or syncing new
                // chapters must move a row, never restyle the one that was already there.
                key = { it.id },
            ) { chapter ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .pressable({
                            openChapter(chapter)
                            showJump = false
                        })
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Chapter ${chapter.numberText}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.Ink,
                        modifier = Modifier.weight(1f),
                    )
                    Eyebrow(
                        if (chapter.id == current?.resumeChapterId) {
                            "READING"
                        } else if (chapter.readAt != null) {
                            "READ"
                        } else {
                            ""
                        },
                        color = Palette.Accent,
                    )
                }
            }
        }
    }
}

/**
 * The blurred backdrop only appears once the artwork actually loaded. A failed cover would
 * otherwise leave a grey rectangle stretched behind the whole hero.
 */
@Composable
private fun Backdrop(url: String, modifier: Modifier = Modifier) {
    var loaded by remember(url) { mutableStateOf(false) }
    Box(modifier) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onState = { state -> loaded = state is AsyncImagePainter.State.Success },
            modifier = Modifier
                .fillMaxSize()
                .blur(38.dp)
                .graphicsLayer { scaleX = 1.25f; scaleY = 1.25f }
                .graphicsLayer { alpha = if (loaded) 1f else 0f },
        )
    }
}

@Composable
private fun ChapterCard(
    chapter: ChapterEntity,
    isResumePoint: Boolean,
    download: DownloadEntity?,
    onOpen: () -> Unit,
    onToggleRead: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    val read = chapter.readAt != null
    val external = chapter.externalUrl != null

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
        shape = RoundedCornerShape(20.dp),
        elevation = if (isResumePoint) 14.dp else 6.dp,
        onClick = onOpen,
        onLongClick = onToggleRead,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .then(
                        if (isResumePoint) {
                            Modifier.background(Brush.linearGradient(Palette.AccentGradient))
                        } else {
                            Modifier
                                .background(Palette.Control)
                                .border(1.dp, Palette.ControlBorder, CircleShape)
                        },
                    )
                    .graphicsLayer { alpha = if (read) 0.6f else 1f },
                contentAlignment = Alignment.Center,
            ) {
                if (read) {
                    GlyphIcon(
                        Glyph.Check,
                        size = 18.dp,
                        tint = if (isResumePoint) Palette.OnAccent else Palette.Success,
                        stroke = 2.2.dp,
                    )
                } else {
                    Text(
                        text = chapter.numberText.take(4),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isResumePoint) Palette.OnAccent else Palette.Ink,
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f).graphicsLayer { alpha = if (read) 0.62f else 1f }) {
                Text(
                    text = "Chapter ${chapter.numberText}",
                    style = MaterialTheme.typography.titleMedium,
                    color = Palette.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = buildList {
                    if (!chapter.title.isNullOrBlank()) add(chapter.title)
                    if (chapter.pageCount > 0) add("${chapter.pageCount} pages")
                    if (external) add("External · opens in browser")
                    if (isResumePoint) add("reading now")
                }.joinToString(" · ")
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isResumePoint) Palette.Accent else Palette.InkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            when {
                external -> Eyebrow("EXTERNAL", color = Palette.Accent)

                download == null -> IconButton(
                    glyph = Glyph.Download,
                    onClick = onDownload,
                    size = 34.dp,
                    tint = Palette.InkSecondary,
                    label = "Download chapter ${chapter.numberText}",
                )

                download.state == DownloadState.COMPLETE -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow(formatBytes(download.byteSize))
                    IconButton(
                        glyph = Glyph.Trash,
                        onClick = onDelete,
                        size = 34.dp,
                        tint = Palette.InkSecondary,
                        label = "Delete chapter ${chapter.numberText}",
                    )
                }

                download.state == DownloadState.FAILED -> PillButton(
                    text = "Retry",
                    onClick = onDownload,
                    tone = PillTone.Quiet,
                )

                else -> Eyebrow("SAVING", color = Palette.Accent)
            }
        }
    }
}

/** Which list a profile shows under its title: the chapters, or what to read next. */
private enum class ProfileSection { CHAPTERS, SIMILAR }

/**
 * The "Similar" tab. It is grouped the way the source itself groups titles, so the heading says
 * so rather than implying a taste engine the app does not have.
 */
private fun LazyListScope.similarItems(
    similar: SimilarState,
    onOpen: (Long) -> Unit,
    onRefresh: () -> Unit,
) {
    item(key = "similar-title") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("If you like this", Modifier.weight(1f))
                PillButton("Refresh", onRefresh, tone = PillTone.Quiet)
            }
            Eyebrow(
                "Grouped by genre, or by what else is new here.",
                Modifier.padding(bottom = 6.dp),
                color = Palette.InkSecondary,
            )
        }
    }
    when {
        similar.loading -> items(2, key = { "similar-loading-$it" }) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                repeat(3) { Shimmer(Modifier.weight(1f).height(160.dp)) }
            }
        }

        similar.cards.isEmpty() -> item(key = "similar-empty") {
            EmptyState(
                glyph = Glyph.Compass,
                title = "Nothing similar to show",
                message = "This title has not been grouped with anything else here yet. Its " +
                    "source's catalogue is the next best place to look.",
            )
        }

        else -> items(
            items = similar.cards.chunked(3),
            key = { chunk -> "similar-${chunk.first().manhwa.id}" },
        ) { chunk ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (card in chunk) {
                    Column(Modifier.weight(1f)) {
                        PosterCard(
                            title = card.manhwa.title,
                            coverUrl = card.manhwa.coverUrl,
                            onClick = { onOpen(card.manhwa.id) },
                        )
                        if (card.caption != null) {
                            Eyebrow(card.caption, Modifier.padding(top = 6.dp, start = 2.dp))
                        }
                    }
                }
                // A short last row keeps the same column width as the rows above it.
                repeat(3 - chunk.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
