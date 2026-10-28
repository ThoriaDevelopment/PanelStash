package com.panelstash.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.panelstash.data.AccentTheme
import com.panelstash.data.CanvasStyle
import com.panelstash.data.PageFit
import com.panelstash.data.TapScroll
import com.panelstash.data.ThemeMode
import com.panelstash.source.BrowseSort
import com.panelstash.source.PageQuality
import com.panelstash.ui.AppViewModel
import com.panelstash.ui.BrandMark
import com.panelstash.ui.Chip
import com.panelstash.ui.ChoiceRow
import com.panelstash.ui.Eyebrow
import com.panelstash.ui.Glyph
import com.panelstash.ui.GlassCard
import com.panelstash.ui.PillButton
import com.panelstash.ui.PillTone
import com.panelstash.ui.SectionTitle
import com.panelstash.ui.SettingRow
import com.panelstash.ui.Sheet
import com.panelstash.ui.Toggle
import com.panelstash.ui.TopBar
import com.panelstash.ui.Wordmark
import com.panelstash.ui.formatBytes
import com.panelstash.ui.pressable
import com.panelstash.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Every knob in one place, grouped by what it affects. Controls are only shown where they do
 * something: the source list states what each source can filter by, so a missing control is
 * explained rather than merely absent.
 */
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val preferences by vm.preferences.collectAsState()
    var cacheBytes by remember { mutableStateOf<Long?>(null) }
    var showSources by remember { mutableStateOf(false) }
    val sources = remember { vm.installedSources() }

    suspend fun measureCache() {
        cacheBytes = withContext(Dispatchers.IO) { vm.imageCacheBytes() }
    }

    // Also re-runs when the measurement is invalidated by "Re-measure".
    LaunchedEffect(cacheBytes) {
        if (cacheBytes == null) measureCache()
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "Settings", onBack = { vm.back() })

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "appearance") {
                SectionTitle("Look")
            }

            item(key = "accent") {
                Column {
                    Eyebrow("ACCENT", Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AccentTheme.entries.forEach { accent ->
                            Chip(
                                text = accent.label,
                                selected = preferences.accent == accent,
                                onClick = { vm.updatePreferences { it.copy(accent = accent) } },
                            )
                        }
                    }
                }
            }

            item(key = "theme") {
                ChoiceRow(
                    title = "Theme",
                    subtitle = "Light keeps the same glass and pure surfaces, on a light canvas.",
                    options = ThemeMode.entries.map { it.label },
                    selectedIndex = ThemeMode.entries.indexOf(preferences.themeMode).coerceAtLeast(0),
                    onSelect = { index ->
                        vm.updatePreferences { it.copy(themeMode = ThemeMode.entries[index]) }
                    },
                )
            }

            item(key = "canvas") {
                val pureLabel = if (preferences.themeMode == ThemeMode.LIGHT) "Pure white" else "Pure black"
                ChoiceRow(
                    title = "Canvas",
                    subtitle = "$pureLabel switches off the colour glows behind the glass.",
                    options = CanvasStyle.entries.map { it.labelFor(preferences.themeMode) },
                    selectedIndex = CanvasStyle.entries.indexOf(preferences.canvas).coerceAtLeast(0),
                    onSelect = { index ->
                        vm.updatePreferences { it.copy(canvas = CanvasStyle.entries[index]) }
                    },
                )
            }

            item(key = "density") {
                ChoiceRow(
                    title = "Browse density",
                    subtitle = "Columns in the poster grid.",
                    options = listOf("2", "3", "4"),
                    selectedIndex = (preferences.gridColumns - 2).coerceIn(0, 2),
                    onSelect = { index -> vm.updatePreferences { it.copy(gridColumns = index + 2) } },
                )
            }

            item(key = "reading") {
                SectionTitle("Reading", Modifier.padding(top = 8.dp))
            }

            item(key = "fit") {
                ChoiceRow(
                    title = "Page fit",
                    subtitle = "Fit width scrolls like a webtoon. Fit page shows one page at a time.",
                    options = PageFit.entries.map { it.label },
                    selectedIndex = PageFit.entries.indexOf(preferences.pageFit).coerceAtLeast(0),
                    onSelect = { index -> vm.updatePreferences { it.copy(pageFit = PageFit.entries[index]) } },
                )
            }

            item(key = "tap-scroll") {
                ChoiceRow(
                    title = "Tap scroll speed",
                    subtitle = "How long a tap takes to move forward a screenful.",
                    options = TapScroll.entries.map { it.label },
                    selectedIndex = TapScroll.entries.indexOf(preferences.tapScroll).coerceAtLeast(0),
                    onSelect = { index ->
                        vm.updatePreferences { it.copy(tapScroll = TapScroll.entries[index]) }
                    },
                )
            }

            item(key = "awake") {
                SettingRow(
                    title = "Keep the screen on while reading",
                    subtitle = "Stops the phone sleeping mid-chapter.",
                ) {
                    Toggle(
                        checked = preferences.keepAwake,
                        onCheckedChange = { on -> vm.updatePreferences { it.copy(keepAwake = on) } },
                    )
                }
            }

            item(key = "order") {
                SettingRow(
                    title = "Newest chapters first",
                    subtitle = "Applies to every manhwa page.",
                ) {
                    Toggle(
                        checked = preferences.reverseChapters,
                        onCheckedChange = { on -> vm.updatePreferences { it.copy(reverseChapters = on) } },
                    )
                }
            }

            item(key = "streaming") {
                ChoiceRow(
                    title = "Streaming quality",
                    subtitle = "Data saver loads chapters faster on a phone connection.",
                    options = listOf("Original", "Data saver"),
                    selectedIndex = if (preferences.streamingQuality == PageQuality.ORIGINAL) 0 else 1,
                    onSelect = { index ->
                        vm.updatePreferences {
                            it.copy(
                                streamingQuality = if (index == 0) PageQuality.ORIGINAL else PageQuality.DATA_SAVER,
                            )
                        }
                    },
                )
            }

            item(key = "downloads") {
                SectionTitle("Downloads", Modifier.padding(top = 8.dp))
            }

            item(key = "download-quality") {
                ChoiceRow(
                    title = "Download quality",
                    subtitle = "Applies to new downloads only.",
                    options = listOf("Original", "Data saver"),
                    selectedIndex = if (preferences.downloadQuality == PageQuality.ORIGINAL) 0 else 1,
                    onSelect = { index ->
                        vm.updatePreferences {
                            it.copy(
                                downloadQuality = if (index == 0) PageQuality.ORIGINAL else PageQuality.DATA_SAVER,
                            )
                        }
                    },
                )
            }

            item(key = "metered") {
                SettingRow(
                    title = "Download on Wi-Fi only",
                    subtitle = "Off asks before using mobile data.",
                ) {
                    Toggle(
                        checked = preferences.unmeteredOnly,
                        onCheckedChange = { on -> vm.updatePreferences { it.copy(unmeteredOnly = on) } },
                    )
                }
            }

            item(key = "storage") {
                val total by vm.totalDownloadBytes.collectAsState()
                SettingRow(
                    title = "Chapters kept offline",
                    subtitle = formatBytes(total),
                    trailing = {
                        Eyebrow(
                            when (val bytes = cacheBytes) {
                                null -> "MEASURING"
                                else -> "${formatBytes(bytes)} CACHED"
                            },
                            color = Palette.InkSecondary,
                        )
                    },
                )
            }

            item(key = "cache") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(
                        text = "Clear image cache",
                        glyph = Glyph.Trash,
                        onClick = {
                            vm.clearImageCache()
                            cacheBytes = 0
                        },
                    )
                    PillButton(
                        text = "Re-measure",
                        tone = PillTone.Quiet,
                        onClick = { cacheBytes = null },
                    )
                }
            }

            item(key = "sources") {
                SectionTitle("Sources", Modifier.padding(top = 8.dp))
            }

            item(key = "default-source") {
                val default = sources.firstOrNull { it.id == preferences.defaultSourceId } ?: sources.firstOrNull()
                SettingRow(
                    title = "Default source",
                    subtitle = default?.let { "${it.name} · ${it.description}" } ?: "No sources installed",
                    onClick = { showSources = true },
                    trailing = {
                        Eyebrow("CHANGE", color = Palette.Accent)
                    },
                )
            }

            item(key = "source-notes") {
                Column {
                    sources.forEach { source ->
                        GlassCard(
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            shape = RoundedCornerShape(18.dp),
                            elevation = 4.dp,
                            contentPadding = PaddingValues(14.dp),
                        ) {
                            Text(
                                text = source.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = Palette.Ink,
                            )
                            Text(
                                text = source.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = Palette.InkSecondary,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                            Text(
                                text = source.capabilitySummary(),
                                style = MaterialTheme.typography.bodySmall,
                                color = Palette.InkTertiary,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }

            item(key = "about") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, top = 18.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BrandMark(size = 44.dp)
                    Column {
                        Wordmark()
                        Text(
                            text = "0.1 · manhwa only, English chapters",
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.InkSecondary,
                        )
                    }
                }
                Text(
                    text = "Content comes from the sources above, so what is available is " +
                        "whatever they publish.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.InkTertiary,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp, end = 4.dp),
                )
            }
        }
    }

    Sheet(
        visible = showSources,
        onDismiss = { showSources = false },
        title = "Source",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            sources.forEach { source ->
                val selected = source.id == preferences.defaultSourceId
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressable({
                            vm.updatePreferences { it.copy(defaultSourceId = source.id) }
                            vm.setBrowseSource(source.id)
                            showSources = false
                        }),
                    shape = RoundedCornerShape(18.dp),
                    strong = selected,
                    elevation = if (selected) 12.dp else 4.dp,
                    contentPadding = PaddingValues(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = source.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = Palette.Ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = source.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = Palette.InkSecondary,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                            Eyebrow(
                                source.capabilitySummary(),
                                Modifier.padding(top = 6.dp),
                                color = Palette.InkTertiary,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Eyebrow(if (selected) "DEFAULT" else "USE", color = Palette.Accent)
                    }
                }
            }
        }
    }
}

private fun com.panelstash.source.Source.capabilitySummary(): String {
    val parts = buildList {
        add("Sort: " + capabilities.sorts.map(BrowseSort::label).joinToString(", "))
        if (capabilities.genres) add("Genre chips")
        if (capabilities.status) add("Status filter")
        if (capabilities.year) add("Year filter")
    }
    return parts.joinToString(" · ")
}
