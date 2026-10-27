package com.panelstash.ui.screens

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
import androidx.compose.foundation.lazy.items
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
import com.panelstash.ui.Cover
import com.panelstash.ui.EmptyState
import com.panelstash.ui.Eyebrow
import com.panelstash.ui.Glyph
import com.panelstash.ui.GlassCard
import com.panelstash.ui.GlyphIcon
import com.panelstash.ui.IconButton
import com.panelstash.ui.PillButton
import com.panelstash.ui.PillTone
import com.panelstash.ui.ProgressBar
import com.panelstash.ui.SectionTitle
import com.panelstash.ui.SearchPill
import com.panelstash.ui.Sheet
import com.panelstash.ui.Shimmer
import com.panelstash.ui.TopBar
import com.panelstash.ui.formatBytes
import com.panelstash.ui.isMetered
import com.panelstash.ui.pressable
import com.panelstash.ui.theme.Palette

@Composable
fun ProfileScreen(vm: AppViewModel, manhwaId: Long) {
    val profileFlow = remember(manhwaId) { vm.profile(manhwaId) }
    val state by profileFlow.collectAsState(initial = null)
    val load by vm.chapterLoad.collectAsState()
    val preferences by vm.preferences.collectAsState()
    val followed by remember(manhwaId) { vm.followedState(manhwaId) }.collectAsState(initial = false)
    val later by remember(manhwaId) { vm.readLaterState(manhwaId) }.collectAsState(initial = false)
    val context = LocalContext.current
    var pendingDownload by remember { mutableStateOf<List<ChapterEntity>?>(null) }
    var showJump by remember { mutableStateOf(false) }
    var jumpQuery by remember { mutableStateOf("") }

    LaunchedEffect(manhwaId) { vm.ensureChapters(manhwaId) }

    val current = state
    val chapterLoad = if (load?.manhwaId == manhwaId) load else null

    fun startDownload(chapters: List<ChapterEntity>) {
        if (chapters.isEmpty() || current == null) return
        // The queue's own policy decides whether a phone connection is enough. When it is not,
        // this asks once, for this request only.
        if (preferences.unmeteredOnly && isMetered(context)) {
            pendingDownload = chapters
        } else {
            vm.download(chapters, current.manhwa, metered = !preferences.unmeteredOnly)
        }
    }

    pendingDownload?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingDownload = null },
            title = { Text("Use mobile data?") },
            text = { Text("${pending.size} chapter(s) will download now.") },
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

    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = current?.manhwa?.title,
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
        val readCount = current.chapters.count { it.readAt != null }
        val continueChapter = current.chapters.firstOrNull { it.id == current.resumeChapterId }
            ?: current.chapters.firstOrNull()
        val ordered = if (preferences.reverseChapters) {
            current.chapters.asReversed()
        } else {
            current.chapters
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 116.dp),
        ) {
            item(key = "hero") {
                Box {
                    val backdrop = manhwa.coverUrl
                    if (backdrop != null) {
                        Backdrop(backdrop)
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(230.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color(0x9907070A), Color(0xCC07070A), Palette.Canvas),
                                ),
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
                                manhwa.status?.replaceFirstChar { it.uppercase() },
                                vm.sourceName(manhwa.sourceId),
                            ).joinToString(" · ")
                            if (meta.isNotEmpty()) {
                                Eyebrow(meta, Modifier.padding(top = 6.dp), color = Palette.InkSecondary)
                            }
                            if (current.chapters.isNotEmpty()) {
                                Text(
                                    text = "$readCount of ${current.chapters.size} chapters read",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Palette.InkSecondary,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                                ProgressBar(
                                    fraction = readCount.toFloat() / current.chapters.size,
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
                                vm.openChapter(it.id, page)
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
                    PillButton(
                        text = "Download all",
                        glyph = Glyph.Download,
                        enabled = current.chapters.isNotEmpty(),
                        onClick = { startDownload(current.chapters) },
                    )
                }
            }

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
                            "this one, which usually means it was licensed away. The same title " +
                            "is being looked for on the sources you have installed.",
                    )
                }

                ordered.isEmpty() -> item(key = "chapters-empty") {
                    EmptyState(
                        glyph = Glyph.Compass,
                        title = "No readable chapters here",
                        message = "${vm.sourceName(manhwa.sourceId)} has no English chapters for " +
                            "this one — often because it was licensed elsewhere — and no other " +
                            "installed source has it either. Delete it and search for another " +
                            "title.",
                    )
                }

                else -> items(ordered, key = { it.id }) { chapter ->
                    ChapterCard(
                        chapter = chapter,
                        isResumePoint = chapter.id == current.resumeChapterId,
                        download = current.downloads[chapter.id],
                        onOpen = {
                            val page = if (chapter.id == current.resumeChapterId) current.resumePage else 0
                            vm.openChapter(chapter.id, page)
                        },
                        onToggleRead = { vm.setChapterRead(chapter.id, chapter.readAt == null) },
                        onDownload = { startDownload(listOf(chapter)) },
                        onDelete = { vm.deleteDownload(chapter.id, chapter.numberText) },
                    )
                }
            }
        }
    }

    Sheet(
        visible = showJump,
        onDismiss = { showJump = false },
        title = "Jump to chapter",
    ) {
        val chapterList = current?.chapters.orEmpty()
        val firstUnread = chapterList.firstOrNull { it.readAt == null }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                PillButton(
                    text = "First",
                    tone = PillTone.Quiet,
                    onClick = {
                        chapterList.firstOrNull()?.let { vm.openChapter(it.id, 0) }
                        showJump = false
                    },
                )
                PillButton(
                    text = "Latest",
                    tone = PillTone.Quiet,
                    onClick = {
                        chapterList.lastOrNull()?.let { vm.openChapter(it.id, 0) }
                        showJump = false
                    },
                )
                PillButton(
                    text = "Next unread",
                    tone = PillTone.Quiet,
                    enabled = firstUnread != null,
                    onClick = {
                        firstUnread?.let { vm.openChapter(it.id, 0) }
                        showJump = false
                    },
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                            vm.openChapter(target.id, 0)
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
                            vm.openChapter(target.id, 0)
                            showJump = false
                        } else {
                            vm.showMessage("No chapter $jumpQuery")
                        }
                    },
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(240.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
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
                                vm.openChapter(chapter.id, 0)
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
}

/**
 * The blurred backdrop only appears once the artwork actually loaded. A failed cover would
 * otherwise leave a grey rectangle stretched behind the whole hero.
 */
@Composable
private fun Backdrop(url: String) {
    var loaded by remember(url) { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().height(230.dp)) {
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
                                .background(Color(0x14FFFFFF))
                                .border(1.dp, Color(0x14FFFFFF), CircleShape)
                        },
                    )
                    .graphicsLayer { alpha = if (read) 0.6f else 1f },
                contentAlignment = Alignment.Center,
            ) {
                if (read) {
                    GlyphIcon(
                        Glyph.Check,
                        size = 18.dp,
                        tint = if (isResumePoint) Color(0xFF0C0C16) else Palette.Success,
                        stroke = 2.2.dp,
                    )
                } else {
                    Text(
                        text = chapter.numberText.take(4),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isResumePoint) Color(0xFF0C0C16) else Palette.Ink,
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
                download == null -> IconButton(
                    glyph = Glyph.Download,
                    onClick = onDownload,
                    size = 34.dp,
                    tint = Palette.InkSecondary,
                )

                download.state == DownloadState.COMPLETE -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow(formatBytes(download.byteSize))
                    IconButton(
                        glyph = Glyph.Trash,
                        onClick = onDelete,
                        size = 34.dp,
                        tint = Palette.InkSecondary,
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
