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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.panelstash.data.db.ManhwaEntity
import com.panelstash.ui.ALL_SOURCES
import com.panelstash.ui.AppViewModel
import com.panelstash.ui.Chip
import com.panelstash.ui.EmptyState
import com.panelstash.ui.Eyebrow
import com.panelstash.ui.Glyph
import com.panelstash.ui.ListRow
import com.panelstash.ui.PillButton
import com.panelstash.ui.PillTone
import com.panelstash.ui.SearchPill
import com.panelstash.ui.Shimmer
import com.panelstash.ui.TopBar

@Composable
fun SearchScreen(vm: AppViewModel) {
    val state by vm.search.collectAsState()
    val recents by vm.recentSearches.collectAsState()
    val sources = remember { vm.installedSources() }
    val browsing = state.query.isBlank()
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        if (state.results.isEmpty() && !state.loading) vm.runSearch("")
    }

    // The next page is fetched before the list runs out, so scrolling does not stall on it.
    LaunchedEffect(listState, state.results.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last ->
                if (last >= vm.search.value.results.size - 4) vm.loadMoreSearch()
            }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = if (browsing) "Popular now" else "Results", onBack = { vm.back() })

        SearchPill(
            value = state.query,
            onValueChange = vm::setQuery,
            onSubmit = { vm.runSearch(state.query) },
            onClear = vm::clearSearch,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
        ) {
            item(key = ALL_SOURCES) {
                Chip(
                    text = "All sources",
                    selected = state.sourceId == ALL_SOURCES,
                    onClick = { vm.setSearchSource(ALL_SOURCES) },
                )
            }
            items(sources, key = { it.id }) { source ->
                Chip(
                    text = source.name.removeSuffix(" Scans"),
                    selected = state.sourceId == source.id,
                    onClick = { vm.setSearchSource(source.id) },
                )
            }
        }

        if (browsing && recents.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Eyebrow("RECENT", Modifier.weight(1f))
                PillButton("Clear", vm::clearRecentSearches, tone = PillTone.Quiet)
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
            ) {
                items(recents, key = { it }) { query ->
                    Chip(text = query, selected = false, onClick = { vm.runSearch(query) })
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        when {
            state.loading && state.results.isEmpty() -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(List(6) { it }) { Shimmer(Modifier.fillMaxWidth().height(92.dp)) }
            }

            state.message != null && state.results.isEmpty() -> EmptyState(
                glyph = Glyph.Refresh,
                title = "Search failed",
                message = state.message,
                actionText = "Try again",
                onAction = { vm.runSearch(state.query) },
                modifier = Modifier.padding(top = 20.dp),
            )

            state.results.isEmpty() && !state.loading -> EmptyState(
                glyph = Glyph.Search,
                title = if (browsing) "Nothing to show" else "No manhwa found",
                message = if (state.sourceId == ALL_SOURCES) {
                    if (browsing) {
                        "None of the installed sources returned anything for this list."
                    } else {
                        "No English manhwa for \"${state.query}\" on any installed source."
                    }
                } else {
                    val name = vm.sourceName(state.sourceId)
                    if (browsing) {
                        "$name returned nothing for this list."
                    } else {
                        "No English manhwa for \"${state.query}\" on $name."
                    }
                },
                modifier = Modifier.padding(top = 20.dp),
            )

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 116.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.results, key = { it.id }) { manhwa ->
                    ListRow(
                        title = manhwa.title,
                        coverUrl = manhwa.coverUrl,
                        subtitle = listOfNotNull(manhwa.meta(), vm.sourceName(manhwa.sourceId))
                            .joinToString(" · "),
                        onClick = { vm.openProfile(manhwa.id) },
                    )
                }
                if (state.loadingMore) {
                    item(key = "more") {
                        Shimmer(Modifier.fillMaxWidth().height(8.dp).padding(vertical = 12.dp))
                    }
                } else if (state.endReached && state.results.size > 8) {
                    item(key = "end") {
                        Eyebrow(
                            if (browsing) "THAT IS EVERYTHING RIGHT NOW" else "NO MORE RESULTS",
                            Modifier.fillMaxWidth().padding(top = 14.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun ManhwaEntity.meta(): String? {
    // Sources spell it "Ongoing", "ONGOING" or "ongoing".
    val parts = listOfNotNull(year?.toString(), status?.lowercase()?.replaceFirstChar { it.uppercase() })
    return parts.joinToString(" · ").ifBlank { null }
}
