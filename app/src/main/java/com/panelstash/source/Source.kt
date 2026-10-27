package com.panelstash.source

/** A manhwa as delivered by a source, before it is stored locally. */
data class SourceManhwa(
    val remoteId: String,
    val title: String,
    val description: String?,
    val coverUrl: String?,
    val year: Int?,
    val status: String?,
)

/** A chapter as delivered by a source. */
data class SourceChapter(
    val remoteId: String,
    val numberText: String,
    val sortKey: Double?,
    val title: String?,
    val language: String,
    val pageCount: Int,
    /** Chapters hosted elsewhere have no readable pages and are skipped. */
    val externalOnly: Boolean,
)

data class PageImage(
    val index: Int,
    val url: String,
    /** Pixels as reported by the source, or 0 when it does not say. Used to lay out the page
     * before its bytes arrive, which is what stops a chapter from jumping while it loads. */
    val width: Int = 0,
    val height: Int = 0,
)

data class PageSet(
    val baseUrl: String,
    val images: List<PageImage>,
)

enum class PageQuality(val apiValue: String) {
    ORIGINAL("data"),
    DATA_SAVER("data-saver"),
}

/** How a browse page is ordered. Sources map these to whatever they support. */
enum class BrowseSort(val label: String) {
    LATEST("Latest"),
    POPULAR("Popular"),
    TOP_RATED("Top rated"),
}

/** A filterable genre as named by the source. The id is source-specific. */
data class SourceGenre(
    val id: String,
    val name: String,
)

enum class StatusFilter(val label: String) {
    ANY("Any"),
    ONGOING("Ongoing"),
    COMPLETED("Completed"),
    HIATUS("Hiatus"),
    CANCELLED("Cancelled"),
}

/**
 * Source-neutral browse filters. A source applies what it can and ignores the rest; the UI asks
 * [SourceCapabilities] which controls to offer so nothing is silently a no-op.
 */
data class BrowseFilter(
    val status: StatusFilter = StatusFilter.ANY,
    val year: Int? = null,
    /** Only the safest rating, dropping anything suggestive. */
    val safeOnly: Boolean = false,
    val excludedTagIds: Set<String> = emptySet(),
) {
    val isActive: Boolean
        get() = status != StatusFilter.ANY || year != null || safeOnly || excludedTagIds.isNotEmpty()

    companion object {
        val None = BrowseFilter()
    }
}

/** What a source can actually do, so the UI never offers a control that does nothing. */
data class SourceCapabilities(
    val sorts: Set<BrowseSort> = setOf(BrowseSort.LATEST),
    val genres: Boolean = false,
    val status: Boolean = false,
    val year: Boolean = false,
    val safeOnly: Boolean = false,
    val excludedGenres: Boolean = false,
)

/**
 * One installed content source. Every stored row is keyed by [id] plus the remote id,
 * so adding a second source never touches the schema.
 */
interface Source {
    val id: String
    val name: String

    /** A short line describing the catalogue, shown when picking a source. */
    val description: String
        get() = name

    val capabilities: SourceCapabilities

    /** A page of the browse catalogue, newest-or-most-read depending on [sort]. */
    suspend fun browse(
        sort: BrowseSort,
        tagId: String?,
        filter: BrowseFilter,
        offset: Int,
        limit: Int,
    ): List<SourceManhwa>

    suspend fun search(query: String, offset: Int, limit: Int): List<SourceManhwa>

    /** Genres usable as browse filters. Cached by the implementation. */
    suspend fun genres(): List<SourceGenre>

    suspend fun manhwa(remoteId: String): SourceManhwa

    suspend fun chapters(manhwaRemoteId: String, language: String): List<SourceChapter>

    /**
     * Pages are resolved per read/download because an image URL is only valid briefly.
     * A source therefore returns locations, never stored URLs.
     */
    suspend fun pages(chapterRemoteId: String, quality: PageQuality): PageSet
}

/** Derives a sort key from a chapter number string: "10.5" -> 10.5, "Extra" -> null. */
fun chapterSortKey(numberText: String): Double? = numberText.trim().toDoubleOrNull()

/**
 * Collapses duplicate chapter numbers (same number uploaded by several groups) into one.
 * Keeps the entry with the most pages, then the most recently uploaded.
 */
fun dedupeChapters(chapters: List<SourceChapter>): List<SourceChapter> {
    val best = LinkedHashMap<String, SourceChapter>()
    for (chapter in chapters) {
        if (chapter.externalOnly) continue
        val key = chapter.numberText.trim()
        val current = best[key]
        if (current == null || chapter.pageCount > current.pageCount) {
            best[key] = chapter
        }
    }
    return best.values.sortedWith(
        compareBy(
            { it.sortKey == null },
            { it.sortKey ?: 0.0 },
            { it.numberText },
        ),
    )
}
