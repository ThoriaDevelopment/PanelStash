package com.panelstash.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.panelstash.data.db.ContinueRow
import com.panelstash.data.db.DownloadState
import com.panelstash.source.PageQuality
import com.panelstash.ui.AppViewModel
import com.panelstash.ui.Cover
import com.panelstash.ui.EmptyState
import com.panelstash.ui.Eyebrow
import com.panelstash.ui.Glyph
import com.panelstash.ui.GlyphIcon
import com.panelstash.ui.IconButton
import com.panelstash.ui.LibrarySection
import com.panelstash.ui.ListRow
import com.panelstash.ui.Screen
import com.panelstash.ui.SectionTitle
import com.panelstash.ui.SegmentedControl
import com.panelstash.ui.formatBytes
import com.panelstash.ui.pressable
import com.panelstash.ui.theme.Palette

@Composable
fun LibraryScreen(vm: AppViewModel) {
    val continueRows by vm.continueReading.collectAsState()
    val followed by vm.followed.collectAsState()
    val later by vm.readLater.collectAsState()
    val groups by vm.downloadGroups.collectAsState()
    val totalBytes by vm.totalDownloadBytes.collectAsState()
    val section by vm.librarySection.collectAsState()
    var quality by remember { mutableStateOf(vm.downloadQuality()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = WindowInsets.statusBars.asPaddingValues() +
            PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 116.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Eyebrow("YOUR SHELF")
                    Text(
                        text = "Library",
                        style = MaterialTheme.typography.headlineLarge,
                        color = Palette.Ink,
                    )
                }
                IconButton(Glyph.Settings, { vm.push(Screen.Settings) }, size = 38.dp)
            }
        }

        item(key = "continue") {
            Column {
                SectionTitle("Continue reading")
                if (continueRows.isEmpty()) {
                    Text(
                        text = "Start a chapter and it will show up here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.InkSecondary,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                    )
                } else {
                    Eyebrow("TAP TO RESUME · HOLD FOR DETAILS", Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(top = 10.dp, bottom = 4.dp),
                    ) {
                        items(continueRows, key = { it.id }) { row ->
                            ContinueCard(
                                row = row,
                                onResume = { vm.openChapter(row.chapterId, row.pageIndex) },
                                onDetails = { vm.openProfile(row.id) },
                            )
                        }
                    }
                }
            }
        }

        item(key = "selector") {
            SegmentedControl(
                options = LibrarySection.entries.map { it.label },
                selectedIndex = LibrarySection.entries.indexOf(section).coerceAtLeast(0),
                onSelect = { vm.selectLibrarySection(LibrarySection.entries[it]) },
                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
            )
        }

        when (section) {
            LibrarySection.FOLLOWING -> {
                if (followed.isEmpty()) {
                    item(key = "following-empty") {
                        EmptyState(
                            glyph = Glyph.Bookmark,
                            title = "Not following anything yet",
                            message = "Follow a manhwa from its page and it lands here.",
                        )
                    }
                } else {
                    items(followed, key = { "f-${it.id}" }) { manhwa ->
                        ListRow(
                            title = manhwa.title,
                            coverUrl = manhwa.coverUrl,
                            onClick = { vm.openProfile(manhwa.id) },
                            trailing = {
                                GlyphIcon(Glyph.ChevronRight, size = 16.dp, tint = Palette.InkTertiary)
                            },
                        )
                    }
                }
            }

            LibrarySection.LATER -> {
                if (later.isEmpty()) {
                    item(key = "later-empty") {
                        EmptyState(
                            glyph = Glyph.Clock,
                            title = "Nothing saved for later",
                            message = "Use the Later button on a manhwa page to park it here.",
                        )
                    }
                } else {
                    items(later, key = { "l-${it.id}" }) { manhwa ->
                        ListRow(
                            title = manhwa.title,
                            coverUrl = manhwa.coverUrl,
                            onClick = { vm.openProfile(manhwa.id) },
                            trailing = {
                                GlyphIcon(Glyph.ChevronRight, size = 16.dp, tint = Palette.InkTertiary)
                            },
                        )
                    }
                }
            }

            LibrarySection.DOWNLOADS -> {
                item(key = "quality") {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Eyebrow("NEW DOWNLOADS ARE SAVED AS", Modifier.weight(1f))
                            Eyebrow(formatBytes(totalBytes), color = Palette.InkSecondary)
                        }
                        Spacer(Modifier.height(8.dp))
                        SegmentedControl(
                            options = listOf("Original", "Data saver"),
                            selectedIndex = if (quality == PageQuality.ORIGINAL) 0 else 1,
                            onSelect = {
                                quality = if (it == 0) PageQuality.ORIGINAL else PageQuality.DATA_SAVER
                                vm.setDownloadQuality(quality)
                            },
                        )
                    }
                }

                if (groups.isEmpty()) {
                    item(key = "downloads-empty") {
                        EmptyState(
                            glyph = Glyph.Download,
                            title = "No downloads yet",
                            message = "Download chapters from a manhwa page to read them offline.",
                        )
                    }
                } else {
                    groups.forEach { group ->
                        item(key = "g-${group.manhwaId}") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Cover(
                                    url = group.coverUrl,
                                    modifier = Modifier.width(34.dp).height(46.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    fallback = group.manhwaTitle,
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = group.manhwaTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Palette.Ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Eyebrow(formatBytes(group.chapters.sumOf { it.byteSize }))
                            }
                        }
                        items(group.chapters, key = { "d-${it.chapterId}" }) { chapter ->
                            ListRow(
                                title = "Chapter ${chapter.number}",
                                coverUrl = null,
                                badge = chapter.number.take(4),
                                subtitle = when (chapter.state) {
                                    DownloadState.COMPLETE -> "Saved offline · ${formatBytes(chapter.byteSize)}"
                                    DownloadState.FAILED -> "Download failed"
                                    else -> "Downloading…"
                                },
                                onClick = { vm.openChapter(chapter.chapterId, 0) },
                                trailing = {
                                    IconButton(
                                        glyph = Glyph.Trash,
                                        onClick = { vm.deleteDownload(chapter.chapterId, chapter.number) },
                                        size = 34.dp,
                                        tint = Palette.InkSecondary,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContinueCard(row: ContinueRow, onResume: () -> Unit, onDetails: () -> Unit) {
    Column(
        Modifier
            .width(132.dp)
            .pressable(onResume, onLongClick = onDetails, scaleDown = 0.955f),
    ) {
        Cover(
            url = row.coverUrl,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.66f),
            shape = RoundedCornerShape(18.dp),
            fallback = row.title,
        )
        Text(
            text = row.title,
            style = MaterialTheme.typography.titleSmall,
            color = Palette.Ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = "Ch. ${row.chapterNumber} · page ${row.pageIndex + 1}",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
