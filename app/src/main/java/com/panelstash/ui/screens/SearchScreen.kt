package com.panelstash.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.panelstash.data.db.ManhwaEntity
import com.panelstash.ui.AppViewModel
import com.panelstash.ui.EmptyState
import com.panelstash.ui.Glyph
import com.panelstash.ui.ListRow
import com.panelstash.ui.SearchPill
import com.panelstash.ui.Shimmer
import com.panelstash.ui.TopBar

@Composable
fun SearchScreen(vm: AppViewModel) {
    val state by vm.search.collectAsState()
    val browsing = state.query.isBlank()

    LaunchedEffect(Unit) {
        if (state.results.isEmpty() && !state.loading) vm.runSearch("")
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = if (browsing) "Popular now" else "Results", onBack = { vm.back() })

        SearchPill(
            value = state.query,
            onValueChange = vm::setQuery,
            onSubmit = { vm.runSearch(state.query) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )

        Spacer(Modifier.height(6.dp))

        when {
            state.loading -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(List(6) { it }) { Shimmer(Modifier.fillMaxWidth().height(92.dp)) }
            }

            state.message != null -> EmptyState(
                glyph = Glyph.Refresh,
                title = "Search failed",
                message = state.message,
                actionText = "Try again",
                onAction = { vm.runSearch(state.query) },
                modifier = Modifier.padding(top = 20.dp),
            )

            state.results.isEmpty() -> EmptyState(
                glyph = Glyph.Search,
                title = if (browsing) "Nothing to show" else "No manhwa found",
                message = if (browsing) {
                    "MangaDex returned nothing for this list."
                } else {
                    "\"${state.query}\" has no English manhwa chapters on MangaDex."
                },
                modifier = Modifier.padding(top = 20.dp),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 116.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.results, key = { it.id }) { manhwa ->
                    ListRow(
                        title = manhwa.title,
                        coverUrl = manhwa.coverUrl,
                        subtitle = manhwa.meta(),
                        onClick = { vm.openProfile(manhwa.id) },
                    )
                }
            }
        }
    }
}

private fun ManhwaEntity.meta(): String? {
    val parts = listOfNotNull(year?.toString(), status?.replaceFirstChar { it.uppercase() })
    return parts.joinToString(" · ").ifBlank { null }
}
