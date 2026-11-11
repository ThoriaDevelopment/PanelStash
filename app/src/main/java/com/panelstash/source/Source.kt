package com.panelstash.source

/**
 * The pseudo-source id Browse and Search use for "every installed source at once". It is a
 * catalogue-wide view, not a real source, so no [Source] ever declares this id.
 */
const val ALL_SOURCES = "all"

/** A manhwa as delivered by a source, before it is stored locally. */
data class SourceManhwa(
    val remoteId: String,
    val title: String,
    val description: String?,
    val coverUrl: String?,
    val year: Int?,
    val status: String?,
    /**
     * Genres as this source publishes them on the title itself. Empty on the sources that only
     * expose them as a browse filter, and on catalogue rows that do not carry them.
     */
    val genres: List<SourceGenre> = emptyList(),
    /**
     * How many chapters the source says the title has, when it says. Used to prefer the complete
     * copy of a title that two sources both publish; a source that does not report it leaves this
     * null rather than guessing from the handful of chapters on a catalogue row.
     */
    val chapterCount: Int? = null,
    /**
     * The other names the source itself lists for the title: English titles under a Korean one,
     * romanisations, and the names other sites use for it. This is what lets the same series be
     * recognised on two sources that spell it differently, without guessing from word overlap.
     * Empty on sources that do not publish any.
     */
    val aliases: List<String> = emptyList(),
)

/**
 * Alternate names as one list per line. The scanlation APIs publish them as a single string, one
 * title per line, sometimes with a trailing newline and sometimes with blank lines between them.
 */
internal fun aliasList(raw: String?): List<String> = raw.orEmpty()
    .split('\n', '\r')
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .distinct()

/** A chapter as delivered by a source. */
data class SourceChapter(
    val remoteId: String,
    val numberText: String,
    val sortKey: Double?,
    val title: String?,
    val language: String,
    val pageCount: Int,
    /** True when the chapter has no readable pages here (external, paid, coin-locked). */
    val externalOnly: Boolean,
    /**
     * Where an external chapter actually lives, when the source says so. MangaDex publishes
     * licensed English chapters this way; they are listed but cannot be read in the app.
     */
    val externalUrl: String? = null,
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
    /**
     * Every title reports the genres it was filed under. Separate from [genres], which means the
     * catalogue can be *filtered* by one: a site can publish genres on a title yet ignore every
     * filter parameter, and then the profile can show chips while Browse shows none.
     */
    val titleGenres: Boolean = false,
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
 * A chapter's place on a profile: the text a reader sees ("12", "12.5", "Prologue") and where it
 * sorts against its neighbours.
 */
data class ChapterLabel(val numberText: String, val sortKey: Double?)

private val NUMBERED_LABEL = Regex(
    """\b(?:chapter|chap\.?|ch\.?|episode|ep\.?)\s*#?\s*([0-9]+(?:\.[0-9]+)?)""",
    RegexOption.IGNORE_CASE,
)
private val BARE_NUMBER = Regex("""#?\s*([0-9]+(?:\.[0-9]+)?)""")

/** A prologue is read before chapter 1, whatever number a site gives it. */
private val PROLOGUE_LABEL = Regex("""\b(?:prologue|prolog|preface|prelude)\b""", RegexOption.IGNORE_CASE)

/** The chapters a site publishes beside the numbered run. */
private val NAMED_LABEL = Regex(
    """\b(?:epilogues?|epilogs?|afterwords?|extras?|bonus|side[\s_-]*stor(?:y|ies)|specials?|interludes?|recaps?|omake|one[\s_-]*shots?|announcements?|notices?)\b(?:\s*#?\s*[0-9]+(?:\.[0-9]+)?)?""",
    RegexOption.IGNORE_CASE,
)

/**
 * Reads the label a source puts on a chapter into a number and a sort key.
 *
 * Most chapters are numbered, and a site writes that number in any of a few ways ("12",
 * "Chapter 12", "Ch. 12.5"), so all of those are recognised. The rest are real chapters a site
 * publishes without a number — a prologue, a side story, an epilogue — which are kept rather than
 * dropped, because a reader has to be able to reach them. Their own label is the number text,
 * since that is what the profile should show: a prologue sorts in front of chapter 1, and every
 * other named chapter sorts after the numbered run, where a null key already puts it.
 *
 * Anything else is not a chapter and returns null, so a stray row is still skipped.
 */
fun chapterLabel(raw: String): ChapterLabel? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    NUMBERED_LABEL.find(text)?.groupValues?.get(1)?.let { number ->
        return ChapterLabel(number, number.toDoubleOrNull())
    }
    if (BARE_NUMBER.matchEntire(text) != null) {
        val number = text.trimStart('#').trim()
        return ChapterLabel(number, number.toDoubleOrNull())
    }
    PROLOGUE_LABEL.find(text)?.let { return ChapterLabel(titleCase(it.value), 0.0) }
    NAMED_LABEL.find(text)?.let { return ChapterLabel(titleCase(it.value), null) }
    return null
}

/** "side-story 2" -> "Side Story 2", so two sites spelling a label differently still match. */
private fun titleCase(value: String): String = value
    .replace(Regex("""[\s_-]+"""), " ")
    .trim()
    .split(' ')
    .filter { it.isNotEmpty() }
    .joinToString(" ") { word -> word.lowercase().replaceFirstChar { it.titlecase() } }

/**
 * Collapses duplicate chapter numbers (same number uploaded by several groups) into one.
 * Keeps the entry with the most pages, then the most recently uploaded.
 *
 * External-only chapters are dropped by default. A source that lists them deliberately (MangaDex
 * does for licensed titles) passes [includeExternal] so they remain visible as bookmarks, while a
 * readable upload of the same number still wins.
 */
fun dedupeChapters(chapters: List<SourceChapter>, includeExternal: Boolean = false): List<SourceChapter> {
    val best = LinkedHashMap<String, SourceChapter>()
    for (chapter in chapters) {
        if (chapter.externalOnly && !includeExternal) continue
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
