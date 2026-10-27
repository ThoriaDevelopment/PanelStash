package com.panelstash.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.panelstash.data.Preferences
import com.panelstash.source.BrowseFilter
import com.panelstash.source.SourceGenre
import com.panelstash.source.StatusFilter
import com.panelstash.ui.AppViewModel
import com.panelstash.ui.Chip
import com.panelstash.ui.ChoiceRow
import com.panelstash.ui.EmptyState
import com.panelstash.ui.Eyebrow
import com.panelstash.ui.Glyph
import com.panelstash.ui.GlassCard
import com.panelstash.ui.IconButton
import com.panelstash.ui.PillButton
import com.panelstash.ui.PillTone
import com.panelstash.ui.PosterCard
import com.panelstash.ui.Screen
import com.panelstash.ui.SearchPill
import com.panelstash.ui.SegmentedControl
import com.panelstash.ui.SettingRow
import com.panelstash.ui.Sheet
import com.panelstash.ui.Shimmer
import com.panelstash.ui.Toggle
import com.panelstash.ui.pressable
import com.panelstash.ui.theme.Palette

/** Exact years offered by the filter. MangaDex takes one year, not a range. */
private val FILTER_YEARS = (2026 downTo 2015).toList()

@Composable
fun BrowseScreen(vm: AppViewModel) {
    val state by vm.browse.collectAsState()
    val preferences by vm.preferences.collectAsState()
    val gridState = rememberLazyGridState()
    var showFilters by remember { mutableStateOf(false) }
    var showSources by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.primeBrowse() }

    // A new sort, genre, filter or source is a new list, so the grid starts at the top again.
    LaunchedEffect(state.sort, state.tagId, state.filter, state.sourceId) { gridState.scrollToItem(0) }

    LaunchedEffect(gridState, state.manhwa.size) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last ->
                if (last >= vm.browse.value.manhwa.size - 5) vm.loadMoreBrowse()
            }
    }

    // A tablet, or a window wide enough to behave like one, has room for a column more than the
    // phone setting without looking sparse. The window size is read instead of the screen so a
    // split-screen window gets the right answer too.
    val wide = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.width.toDp() >= 720.dp
    }
    val columns = (preferences.gridColumns + if (wide) 1 else 0).coerceIn(2, 5)

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp)
                .padding(top = 14.dp, bottom = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Eyebrow("MANHWA · ENGLISH · ${state.sourceName.uppercase()}")
                    Text(
                        text = "Browse",
                        style = MaterialTheme.typography.headlineLarge,
                        color = Palette.Ink,
                    )
                }
                IconButton(Glyph.Settings, { vm.push(Screen.Settings) }, size = 38.dp)
                Spacer(Modifier.width(8.dp))
                IconButton(Glyph.Refresh, { vm.refreshBrowse() })
            }
            Spacer(Modifier.height(14.dp))
            SearchPill(value = "", onOpen = { vm.push(Screen.Search) })
            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (vm.installedSources().size > 1) {
                    PillButton(
                        text = state.sourceName,
                        glyph = Glyph.Compass,
                        tone = PillTone.Quiet,
                        onClick = { showSources = true },
                    )
                }
                PillButton(
                    text = if (state.filter.isActive) "Filters · on" else "Filters",
                    glyph = Glyph.Filter,
                    selected = state.filter.isActive,
                    onClick = { showFilters = true },
                )
            }
            if (state.availableSorts.size > 1) {
                Spacer(Modifier.height(10.dp))
                SegmentedControl(
                    options = state.availableSorts.map { it.label },
                    selectedIndex = state.availableSorts.indexOf(state.sort).coerceAtLeast(0),
                    onSelect = { vm.setBrowseSort(state.availableSorts[it]) },
                )
            }
        }

        if (state.genres.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 14.dp),
            ) {
                item(key = "all") {
                    Chip(
                        text = "All genres",
                        selected = state.tagId == null,
                        onClick = { vm.setBrowseGenre(null) },
                    )
                }
                items(state.genres, key = { it.id }) { genre ->
                    Chip(
                        text = genre.name,
                        selected = state.tagId == genre.id,
                        onClick = { vm.setBrowseGenre(genre) },
                    )
                }
            }
        }

        val showInitialLoad = state.loading && state.manhwa.isEmpty()
        val showError = state.error != null && state.manhwa.isEmpty()

        when {
            showInitialLoad -> SkeletonGrid(columns)
            showError -> EmptyState(
                glyph = Glyph.Refresh,
                title = "Could not load the catalogue",
                message = state.error,
                actionText = "Try again",
                onAction = { vm.refreshBrowse() },
                modifier = Modifier.padding(top = 24.dp),
            )

            state.manhwa.isEmpty() -> EmptyState(
                glyph = Glyph.Compass,
                title = "Nothing to browse here",
                message = when {
                    state.tagName != null -> "No manhwa in ${state.tagName} on ${state.sourceName} yet."
                    state.filter.isActive -> "${state.sourceName} returned nothing for this filter."
                    else -> "${state.sourceName} returned an empty catalogue."
                },
                actionText = if (state.filter.isActive) "Clear filters" else null,
                onAction = {
                    if (state.filter.isActive) vm.setBrowseFilter(BrowseFilter.None)
                },
                modifier = Modifier.padding(top = 24.dp),
            )

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 116.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (state.error != null) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "error") {
                        GlassCard(
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(14.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = state.error.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Palette.InkSecondary,
                                    modifier = Modifier.weight(1f),
                                )
                                PillButton("Retry", { vm.loadMoreBrowse() }, tone = PillTone.Quiet)
                            }
                        }
                    }
                }
                items(state.manhwa, key = { it.id }) { manhwa ->
                    PosterCard(
                        title = manhwa.title,
                        coverUrl = manhwa.coverUrl,
                        badge = manhwa.year?.toString(),
                        onClick = { vm.openProfile(manhwa.id) },
                    )
                }
                if (state.loadingMore) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "more") {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Shimmer(Modifier.fillMaxWidth().height(6.dp))
                        }
                    }
                } else if (state.endReached && state.manhwa.size > 8) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "end") {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 18.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Eyebrow("THAT IS EVERYTHING FOR THIS FILTER")
                        }
                    }
                }
            }
        }
    }

    Sheet(
        visible = showSources,
        onDismiss = { showSources = false },
        title = "Source",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            vm.installedSources().forEach { source ->
                val selected = source.id == state.sourceId
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressable({
                            vm.setBrowseSource(source.id)
                            showSources = false
                        }),
                    shape = RoundedCornerShape(18.dp),
                    strong = selected,
                    elevation = if (selected) 12.dp else 4.dp,
                    contentPadding = PaddingValues(14.dp),
                ) {
                    Text(
                        text = source.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = Palette.Ink,
                    )
                    Text(
                        text = source.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.InkSecondary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Eyebrow(
                "Switching source changes what Browse shows. Filters a source cannot apply are " +
                    "hidden rather than ignored.",
                Modifier.padding(top = 6.dp, start = 4.dp),
            )
        }
    }

    FilterSheet(
        visible = showFilters,
        current = state.filter,
        genres = state.genres,
        capabilities = state.capabilities,
        preferences = preferences,
        onDismiss = { showFilters = false },
        onApply = { vm.setBrowseFilter(it) },
    )
}

/**
 * Filters are chosen as a draft and applied once, so a source is not queried for every chip tap.
 * Only the filters the current source can apply are offered.
 */
@Composable
private fun FilterSheet(
    visible: Boolean,
    current: BrowseFilter,
    genres: List<SourceGenre>,
    capabilities: com.panelstash.source.SourceCapabilities,
    preferences: Preferences,
    onDismiss: () -> Unit,
    onApply: (BrowseFilter) -> Unit,
) {
    var draft by remember(visible, current) { mutableStateOf(current) }

    Sheet(
        visible = visible,
        onDismiss = onDismiss,
        title = "Filters",
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (capabilities.status) {
                ChoiceRow(
                    title = "Status",
                    options = StatusFilter.entries.map { it.label },
                    selectedIndex = StatusFilter.entries.indexOf(draft.status).coerceAtLeast(0),
                    onSelect = { draft = draft.copy(status = StatusFilter.entries[it]) },
                )
            }

            if (capabilities.year) {
                Column {
                    Eyebrow("YEAR", Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item(key = "any-year") {
                            Chip(
                                text = "Any",
                                selected = draft.year == null,
                                onClick = { draft = draft.copy(year = null) },
                            )
                        }
                        items(FILTER_YEARS, key = { it }) { year ->
                            Chip(
                                text = year.toString(),
                                selected = draft.year == year,
                                onClick = { draft = draft.copy(year = year) },
                            )
                        }
                    }
                }
            }

            if (capabilities.safeOnly) {
                SettingRow(
                    title = "Safe only",
                    subtitle = "Hides suggestive titles as well.",
                ) {
                    Toggle(
                        checked = draft.safeOnly,
                        onCheckedChange = { on -> draft = draft.copy(safeOnly = on) },
                    )
                }
            }

            if (capabilities.excludedGenres && genres.isNotEmpty()) {
                Column {
                    Eyebrow(
                        "EXCLUDE GENRES · ${draft.excludedTagIds.size} SELECTED",
                        Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        genres.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { genre ->
                                    Chip(
                                        text = genre.name,
                                        selected = genre.id in draft.excludedTagIds,
                                        onClick = {
                                            draft = draft.copy(
                                                excludedTagIds = if (genre.id in draft.excludedTagIds) {
                                                    draft.excludedTagIds - genre.id
                                                } else {
                                                    draft.excludedTagIds + genre.id
                                                },
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PillButton(
                    text = "Apply",
                    tone = PillTone.Accent,
                    glyph = Glyph.Check,
                    onClick = {
                        onApply(draft)
                        onDismiss()
                    },
                )
                PillButton(
                    text = "Reset",
                    tone = PillTone.Quiet,
                    onClick = { draft = BrowseFilter.None },
                )
            }

            Eyebrow(
                "Filters are remembered, and only apply to a source that supports them.",
                Modifier.padding(horizontal = 4.dp).padding(bottom = 6.dp),
            )
        }
    }
}

@Composable
private fun SkeletonGrid(columns: Int) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 116.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        userScrollEnabled = false,
    ) {
        items(List(columns * 3) { it }) { index ->
            Shimmer(
                Modifier
                    .fillMaxWidth()
                    .height(if (index < columns) 262.dp else 238.dp),
                RoundedCornerShape(20.dp),
            )
        }
    }
}
