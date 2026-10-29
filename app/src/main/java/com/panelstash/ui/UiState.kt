package com.panelstash.ui

import com.panelstash.data.DiscoveryCard
import com.panelstash.data.DiscoveryRail
import com.panelstash.data.ReaderPage
import com.panelstash.data.db.ChapterEntity
import com.panelstash.data.db.DownloadEntity
import com.panelstash.data.db.ManhwaEntity
import com.panelstash.source.BrowseFilter
import com.panelstash.source.BrowseSort
import com.panelstash.source.Source
import com.panelstash.source.SourceCapabilities
import com.panelstash.source.SourceGenre

/** A source id that means "every installed source", used by Browse and Search. */
const val ALL_SOURCES = "all"

/**
 * Two top-level destinations. Search, a manhwa profile, the reader and settings are pushed on top
 * of whichever tab opened them.
 */
sealed interface Screen {
    data object Browse : Screen
    data object Library : Screen
    data object Settings : Screen
    data object Search : Screen
    data class Profile(val manhwaId: Long) : Screen
    data class Reader(val chapterId: Long) : Screen
}

/** The selector inside the Library tab. */
enum class LibrarySection(val label: String) {
    FOLLOWING("Following"),
    LATER("Later"),
    DOWNLOADS("Downloads"),
}

data class BrowseState(
    val sourceId: String = "mangadex",
    val sourceName: String = "MangaDex",
    val capabilities: SourceCapabilities = SourceCapabilities(),
    val sources: List<Source> = emptyList(),
    val sort: BrowseSort = BrowseSort.LATEST,
    val filter: BrowseFilter = BrowseFilter.None,
    val genres: List<SourceGenre> = emptyList(),
    val tagId: String? = null,
    val tagName: String? = null,
    val manhwa: List<ManhwaEntity> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    val error: String? = null,
) {
    val availableSorts: List<BrowseSort>
        get() = BrowseSort.entries.filter { it in capabilities.sorts }.ifEmpty { listOf(BrowseSort.LATEST) }
}

/** What the Browse screen shows above the grid: a leaderboard and personalised rows. */
data class DiscoveryState(
    val loading: Boolean = false,
    val rails: List<DiscoveryRail> = emptyList(),
)

/** The "similar" row on an opened manhwa. [attempted] keeps an empty result from re-querying. */
data class SimilarState(
    val manhwaId: Long? = null,
    val loading: Boolean = false,
    val attempted: Boolean = false,
    val cards: List<DiscoveryCard> = emptyList(),
)

/** Per-manhwa chapter sync status, so the profile can explain an empty chapter list. */
data class ChapterLoad(
    val manhwaId: Long,
    val loading: Boolean = false,
    /** True while the same title is being looked for on the other installed sources. */
    val matching: Boolean = false,
    val error: String? = null,
)

data class ProfileState(
    val manhwa: ManhwaEntity,
    val chapters: List<ChapterEntity>,
    val downloads: Map<Long, DownloadEntity>,
    val resumeChapterId: Long?,
    val resumePage: Int,
)

data class SearchState(
    val query: String = "",
    val sourceId: String = ALL_SOURCES,
    val results: List<ManhwaEntity> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    val message: String? = null,
)

data class ReaderState(
    val manhwa: ManhwaEntity,
    val chapter: ChapterEntity,
    val pages: List<ReaderPage>,
    val startPage: Int,
    val nextChapterId: Long?,
)

data class DownloadGroup(
    val manhwaId: Long,
    val manhwaTitle: String,
    val coverUrl: String?,
    val chapters: List<DownloadChapter>,
)

data class DownloadChapter(
    val chapterId: Long,
    val number: String,
    val title: String?,
    val state: String,
    val byteSize: Long,
)

/** A destructive action that can still be taken back while its toast is on screen. */
data class PendingUndo(
    val chapterId: Long,
    val label: String,
)
