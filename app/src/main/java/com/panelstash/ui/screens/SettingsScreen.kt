package com.panelstash.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.verticalScroll
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
import com.panelstash.ui.ProgressBar
import com.panelstash.ui.SectionTitle
import com.panelstash.ui.SettingRow
import com.panelstash.ui.Sheet
import com.panelstash.ui.Toggle
import com.panelstash.ui.TopBar
import com.panelstash.ui.Wordmark
import com.panelstash.ui.formatBytes
import com.panelstash.ui.pressable
import com.panelstash.ui.theme.Palette
import com.panelstash.update.UpdateState
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
    // Every known source: switched-off ones stay listed so they can be switched back on.
    val sources = remember { vm.allSources() }
    val enabledSources = sources.filter { it.id !in preferences.disabledSourceIds }

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
                    // Every accent is on screen: in one row the last one was cut off by the
                    // right edge, which made it look like it did not exist.
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AccentTheme.entries.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { accent ->
                                    Chip(
                                        text = accent.label,
                                        selected = preferences.accent == accent,
                                        onClick = { vm.updatePreferences { it.copy(accent = accent) } },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item(key = "theme") {
                ChoiceRow(
                    title = "Theme",
                    subtitle = "Same design, on a light background.",
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

            item(key = "updates") {
                SectionTitle("Updates", Modifier.padding(top = 8.dp))
            }

            item(key = "update") {
                UpdateCard(vm)
            }

            item(key = "sources") {
                SectionTitle("Sources", Modifier.padding(top = 8.dp))
            }

            item(key = "default-source") {
                val default = enabledSources.firstOrNull { it.id == preferences.defaultSourceId }
                    ?: enabledSources.firstOrNull()
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
                        val on = source.id !in preferences.disabledSourceIds
                        GlassCard(
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            shape = RoundedCornerShape(18.dp),
                            elevation = 4.dp,
                            contentPadding = PaddingValues(14.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = source.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (on) Palette.Ink else Palette.InkSecondary,
                                    modifier = Modifier.weight(1f),
                                )
                                Toggle(
                                    checked = on,
                                    onCheckedChange = { vm.setSourceEnabled(source.id, it) },
                                )
                            }
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
                    Text(
                        text = "A source you turn off drops out of Browse, search and the " +
                            "recommendation rows. Titles you already opened still open.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.InkTertiary,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                    )
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
                            text = "${vm.appVersion} · manhwa only, English chapters",
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
        // The list scrolls: the sheet is height-capped, and every installed source gets a card.
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            enabledSources.forEach { source ->
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

/**
 * The updater in one card: the installed version, what the last check found, and the one action
 * that makes sense next. A download keeps running if the screen is left, so its progress is
 * drawn here rather than in a dialog that would have to be dismissed to get anything else done.
 */
@Composable
private fun UpdateCard(vm: AppViewModel) {
    val state by vm.update.collectAsState()
    // Read once into a local: `state` is delegated, so it cannot be smart cast inside `when`.
    val current = state

    // Settings is where someone looks for an update, so one quiet check runs on the way in. The
    // updater throttles it and ignores it while a download or an install is waiting.
    LaunchedEffect(Unit) { vm.checkForUpdate(force = false) }

    val downloading = current as? UpdateState.Downloading

    Column {
        SettingRow(
            title = "PanelStash ${vm.appVersion}",
            subtitle = current.statusText(),
            trailing = {
                Eyebrow(
                    text = current.badgeText(),
                    color = when (current) {
                        is UpdateState.Failed -> Palette.Danger
                        is UpdateState.UpToDate, is UpdateState.Ready -> Palette.Success
                        else -> Palette.Accent
                    },
                )
            },
        )

        if (downloading != null) {
            Spacer(Modifier.height(8.dp))
            val fraction = if (downloading.total > 0) {
                downloading.received.toFloat() / downloading.total
            } else {
                0f
            }
            ProgressBar(fraction, Modifier.padding(horizontal = 4.dp))
        }

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (current) {
                is UpdateState.Checking -> PillButton(
                    text = "Checking…",
                    glyph = Glyph.Refresh,
                    enabled = false,
                    onClick = {},
                )

                is UpdateState.Available -> PillButton(
                    text = "Download ${current.version}",
                    glyph = Glyph.Download,
                    tone = PillTone.Accent,
                    onClick = { vm.downloadUpdate() },
                )

                is UpdateState.Downloading -> PillButton(
                    text = "Downloading…",
                    glyph = Glyph.Download,
                    enabled = false,
                    onClick = {},
                )

                is UpdateState.Ready -> PillButton(
                    text = "Install",
                    glyph = Glyph.Download,
                    tone = PillTone.Accent,
                    onClick = { vm.installUpdate() },
                )

                else -> PillButton(
                    text = "Check for updates",
                    glyph = Glyph.Refresh,
                    onClick = { vm.checkForUpdate() },
                )
            }
        }

        Text(
            text = "The update installs over this copy, so downloads and reading progress are kept.",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.InkTertiary,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        )
    }
}

private fun UpdateState.statusText(): String = when (this) {
    UpdateState.Idle -> "Checks GitHub for a newer release."
    UpdateState.Checking -> "Looking for a newer release…"
    UpdateState.UpToDate -> "This is the newest release."
    is UpdateState.Available -> "Version $version is available · ${formatBytes(size)}."
    is UpdateState.Downloading -> "Downloading $version · ${formatBytes(received)}" +
        if (total > 0) " of ${formatBytes(total)}" else ""
    is UpdateState.Ready -> "Version $version is downloaded. Install it to update."
    is UpdateState.Failed -> message
}

private fun UpdateState.badgeText(): String = when (this) {
    UpdateState.Idle -> "CHECK"
    UpdateState.Checking -> "CHECKING"
    UpdateState.UpToDate -> "UP TO DATE"
    is UpdateState.Available -> "AVAILABLE"
    is UpdateState.Downloading -> if (total > 0) {
        "${(received.toFloat() / total * 100).toInt().coerceIn(0, 100)}%"
    } else {
        "DOWNLOADING"
    }
    is UpdateState.Ready -> "READY"
    is UpdateState.Failed -> "FAILED"
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
