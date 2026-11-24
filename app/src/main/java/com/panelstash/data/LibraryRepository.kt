package com.panelstash.data

import com.panelstash.data.db.ChapterEntity
import com.panelstash.data.db.ContinueRow
import com.panelstash.data.db.FollowEntity
import com.panelstash.data.db.FollowRow
import com.panelstash.data.db.ManhwaEntity
import com.panelstash.data.db.PanelStashDatabase
import com.panelstash.data.db.ReadLaterEntity
import com.panelstash.data.db.ResumeEntity
import com.panelstash.source.BrowseFilter
import com.panelstash.source.BrowseSort
import com.panelstash.source.Source
import com.panelstash.source.SourceError
import com.panelstash.source.SourceGenre
import com.panelstash.source.SourceManhwa
import com.panelstash.source.SourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** One aggregate page: what was found, where each source resumes, and whether all are done. */
data class BrowsePage(
    val items: List<ManhwaEntity>,
    val nextOffsets: Map<String, Int>,
    val exhausted: Boolean,
)

/** The same shape for search, so "more results" advances every source instead of replaying one. */
data class SearchPage(
    val items: List<ManhwaEntity>,
    val nextOffsets: Map<String, Int>,
    val exhausted: Boolean,
)

/** Catalogue rows the reader never touched are dropped after this long. */
private const val CATALOGUE_TTL_MS = 30L * 24 * 60 * 60 * 1000

/**
 * How long one source may take to answer a catalogue or search request before it is dropped from
 * this page. Every source is asked at the same time, so without a limit the slowest site sets the
 * wait for the whole screen — and a site that never answers held Browse for the full 30 s request
 * timeout. A dropped source is not marked finished, so "load more" asks it again.
 */
private const val LIST_TIMEOUT_MS = 8_000L

/** The same, for the searches that decide which sources carry an opened title. */
private const val LINK_SEARCH_TIMEOUT_MS = 8_000L

/** A ceiling on the whole "find it on another source" sweep, however many sources there are. */
private const val MATCH_SWEEP_TIMEOUT_MS = 20_000L

/** Reading order: numbered chapters by value, unnumbered ones after them. */
private val CHAPTER_ORDER =
    compareBy<ChapterEntity> { it.sortKey == null }
        .thenBy { it.sortKey ?: 0.0 }
        .thenBy { it.numberText }

/**
 * One chapter per chapter number, across every source that carries the title.
 *
 * A chapter only one source publishes is kept as it is, which is what makes a profile whole:
 * provider A's chapters 3–13, B's 1–3 and C's 13–59 read as 1–59 instead of starting at 13.
 * Where two sources publish the same number, a readable upload wins, because an external-only
 * entry is a link to a publisher's site rather than something that can be read here; between two
 * readable copies the first-ranked source wins, which the repository's [rank] decides.
 */
internal fun mergeChapters(
    chapters: List<ChapterEntity>,
    rank: (String) -> Int,
): List<ChapterEntity> {
    val best = LinkedHashMap<String, ChapterEntity>()
    for (chapter in chapters) {
        val key = chapter.numberText.trim()
        val held = best[key]
        if (held == null || beats(chapter, held, rank)) best[key] = chapter
    }
    return best.values.sortedWith(CHAPTER_ORDER)
}

/**
 * A title as a comparison key: letters and digits only, with a bracketed annotation dropped, so
 * "Solo Leveling (Official)" and "Solo Leveling" are the same title.
 */
internal fun normaliseTitle(value: String): String = value
    .replace(Regex("\\([^)]*\\)|\\[[^]]*]|【[^】]*】"), " ")
    .lowercase()
    .filter { it.isLetterOrDigit() }

/** Every spelling of a title, as comparison keys. */
internal fun nameKeys(names: List<String>): Set<String> =
    names.map { normaliseTitle(it) }.filter { it.isNotBlank() }.toSet()

/**
 * True when any spelling of one title is any spelling of the other. Sources publish the alternates
 * a series goes by, which is how one work is recognised under two names: MangaDex lists "Revenge
 * of the Baskerville Bloodhound" among the alternates of the series Asura Scans calls "Revenge of
 * the Iron-Blooded Sword Hound".
 */
internal fun sameTitle(one: List<String>, other: List<String>): Boolean {
    val left = nameKeys(one)
    val right = nameKeys(other)
    return left.any(right::contains)
}

private fun beats(candidate: ChapterEntity, held: ChapterEntity, rank: (String) -> Int): Boolean {
    val candidateReadable = candidate.externalUrl == null
    val heldReadable = held.externalUrl == null
    if (candidateReadable != heldReadable) return candidateReadable
    val candidateRank = rank(candidate.sourceId)
    val heldRank = rank(held.sourceId)
    if (candidateRank != heldRank) return candidateRank < heldRank
    return candidate.pageCount > held.pageCount
}

class LibraryRepository(
    private val sources: SourceRegistry,
    private val db: PanelStashDatabase,
) {
    private val manhwaDao = db.manhwaDao()
    private val chapterDao = db.chapterDao()
    private val resumeDao = db.resumeDao()
    private val followDao = db.followDao()
    private val laterDao = db.readLaterDao()

    fun observeManhwa(id: Long): Flow<ManhwaEntity?> = manhwaDao.observe(id)

    /**
     * One profile's chapters, across every stored copy of the title. [mergeChapters] collapses a
     * chapter number that several sources publish into the copy the reader should get.
     */
    fun observeChaptersFor(manhwaIds: List<Long>): Flow<List<ChapterEntity>> =
        chapterDao.observeForManhwas(manhwaIds)

    fun observeContinueReading(): Flow<List<ContinueRow>> = manhwaDao.observeContinueReading()

    fun observeFollowed(): Flow<List<FollowRow>> = followDao.observeAll()

    fun observeReadLater(): Flow<List<ManhwaEntity>> = laterDao.observeAll()

    fun observeFollowedState(manhwaId: Long): Flow<Boolean> = followDao.observe(manhwaId).map { it != null }
    fun observeReadLaterState(manhwaId: Long): Flow<Boolean> = laterDao.observe(manhwaId).map { it != null }

    /**
     * A page of one source's search, or its ranking for a blank query. "Popular now" must be
     * popular where a source can rank, so the sort falls back to latest only where it cannot.
     */
    suspend fun search(sourceId: String, query: String, offset: Int, limit: Int): SearchPage {
        val source = sources.get(sourceId)
        // Scrapers parse HTML and regexes after the network resumes; that work belongs on IO,
        // not on the frame the grid is waiting to draw.
        val rows = withContext(Dispatchers.IO) {
            if (query.isBlank()) {
                source.browse(discoverySort(source), tagId = null, filter = BrowseFilter.None, offset, limit)
            } else {
                source.search(query = query, offset = offset, limit = limit)
            }
        }
        return SearchPage(
            items = storeRows(rows, source.id),
            nextOffsets = mapOf(source.id to offset + rows.size),
            exhausted = rows.size < limit,
        )
    }

    /**
     * Search every installed source at once. A source that fails is skipped rather than failing
     * the whole search: one dead site should not hide results from the others.
     *
     * Results are interleaved by rank, so "All sources" is one merged list rather than thirty
     * rows from whichever site happens to come first in the registry.
     */
    suspend fun searchAll(
        query: String,
        offsets: Map<String, Int>,
        limit: Int,
        /** Called after each source answers, so a search fills in instead of waiting for all. */
        onPartial: ((List<ManhwaEntity>) -> Unit)? = null,
    ): SearchPage {
        val nextOffsets = offsets.toMutableMap()
        val landed = mutableListOf<Pair<String, List<RankedManhwa>>>()
        val lock = Mutex()
        val pages = coroutineScope {
            sources.all.map { source ->
                val offset = offsets[source.id] ?: 0
                async(Dispatchers.IO) {
                    val result = runCatching {
                        withTimeoutOrNull(LIST_TIMEOUT_MS) {
                            if (query.isBlank()) {
                                source.browse(discoverySort(source), null, BrowseFilter.None, offset, limit)
                            } else {
                                source.search(query, offset, limit)
                            }
                        } ?: throw SourceError.Transient()
                    }
                    val fresh = result.getOrDefault(emptyList())
                    val counts = fresh.associate { it.remoteId to it.chapterCount }
                    val rows = storeRows(fresh, source.id)
                        .map { RankedManhwa(it, counts[it.remoteId]) }
                    lock.withLock {
                        if (result.isSuccess) nextOffsets[source.id] = offset + rows.size
                        landed += source.id to rows
                        onPartial?.let { publish ->
                            // Sources answer in whatever order the network gives them, but the
                            // grid's precedence is registration order, not arrival order.
                            val current = landed.sortedBy { sourceRank(it.first) }
                            publish(
                                interleaveRanked(current, current.sumOf { it.second.size })
                                    .map { it.second },
                            )
                        }
                    }
                    Triple(source, offset, result)
                }
            }.awaitAll()
        }

        val firstError = pages.firstNotNullOfOrNull { it.third.exceptionOrNull() }
        val perSource = landed.sortedBy { sourceRank(it.first) }
        val items = interleaveRanked(perSource, perSource.sumOf { it.second.size }).map { it.second }
        if (items.isEmpty()) firstError?.let { throw it }
        return SearchPage(
            items = items,
            nextOffsets = nextOffsets,
            // A failed source is not exhausted: load-more must retry it. Treating its empty
            // result as the end hid the rest of that source's matches.
            exhausted = pages.all { (_, _, result) ->
                result.isSuccess && result.getOrDefault(emptyList()).size < limit
            },
        )
    }

    /** Popular where a source ranks its catalogue, latest where it cannot. */
    private fun discoverySort(source: Source): BrowseSort = when {
        BrowseSort.POPULAR in source.capabilities.sorts -> BrowseSort.POPULAR
        BrowseSort.TOP_RATED in source.capabilities.sorts -> BrowseSort.TOP_RATED
        else -> BrowseSort.LATEST
    }

    /**
     * The same aggregate page, but over the browse catalogue. Each source keeps its own offset,
     * so "load more" advances all of them rather than replaying the first page forever.
     */
    suspend fun browseAll(
        sort: BrowseSort,
        tagId: String?,
        filter: BrowseFilter,
        offsets: Map<String, Int>,
        limit: Int,
        /**
         * Called after each source answers, with everything that has landed so far. Browse uses
         * this to draw the first sites immediately rather than holding the whole grid until the
         * slowest one answers or times out.
         */
        onPartial: ((List<ManhwaEntity>) -> Unit)? = null,
    ): BrowsePage {
        val nextOffsets = offsets.toMutableMap()
        // Only touched while holding [lock], and read once after every task has finished.
        val landed = mutableListOf<Pair<String, List<RankedManhwa>>>()
        val lock = Mutex()
        val pages = coroutineScope {
            sources.all.map { source ->
                val offset = offsets[source.id] ?: 0
                async(Dispatchers.IO) {
                    val result = runCatching {
                        withTimeoutOrNull(LIST_TIMEOUT_MS) {
                            source.browse(sort, tagId, filter, offset, limit)
                        } ?: throw SourceError.Transient()
                    }
                    // Rows are stored as each source lands, so a partial page is already
                    // openable before the rest of the catalogue has answered. The whole page
                    // is one insert/update transaction rather than three queries per row.
                    val fresh = result.getOrDefault(emptyList())
                    val counts = fresh.associate { it.remoteId to it.chapterCount }
                    val rows = storeRows(fresh, source.id)
                        .map { RankedManhwa(it, counts[it.remoteId]) }
                    lock.withLock {
                        if (result.isSuccess) nextOffsets[source.id] = offset + rows.size
                        landed += source.id to rows
                        onPartial?.let { publish ->
                            // Sources answer in whatever order the network gives them, but the
                            // grid's precedence is registration order, not arrival order.
                            val current = landed.sortedBy { sourceRank(it.first) }
                            publish(
                                interleaveRanked(current, current.sumOf { it.second.size })
                                    .map { it.second },
                            )
                        }
                    }
                    Triple(source, offset, result)
                }
            }.awaitAll()
        }

        val firstError = pages.firstNotNullOfOrNull { it.third.exceptionOrNull() }
        val exhausted = pages.all { (_, _, result) ->
            result.isSuccess && result.getOrDefault(emptyList()).size < limit
        }
        // Interleaved by rank: with every source selected, a page must not be one site's list.
        val perSource = landed.sortedBy { sourceRank(it.first) }
        val items = interleaveRanked(perSource, perSource.sumOf { it.second.size }).map { it.second }
        if (items.isEmpty()) firstError?.let { throw it }
        return BrowsePage(items = items, nextOffsets = nextOffsets, exhausted = exhausted)
    }

    /** A page of the browse catalogue, stored locally so it can be opened straight away. */
    suspend fun browse(
        sourceId: String,
        sort: BrowseSort,
        tagId: String?,
        filter: BrowseFilter,
        offset: Int,
        limit: Int,
    ): List<ManhwaEntity> {
        val source = sources.get(sourceId)
        return withContext(Dispatchers.IO) {
            storeRows(source.browse(sort, tagId, filter, offset, limit), source.id)
        }
    }

    suspend fun genres(sourceId: String): List<SourceGenre> =
        withContext(Dispatchers.IO) { sources.get(sourceId).genres() }

    suspend fun manhwa(manhwaId: Long): ManhwaEntity? = manhwaDao.byId(manhwaId)

    fun source(sourceId: String): Source? = sources.getOrNull(sourceId)

    fun installedSources(): List<Source> = sources.all

    /** Every source the app knows, including ones switched off in settings. */
    fun allSources(): List<Source> = sources.everything

    /**
     * Which source's copy of a chapter wins when two of them publish the same number. The
     * registry's registration order is the precedence, so there is one list to change rather than
     * a hardcoded pair here that can drift from it. An unknown source ranks last.
     */
    fun sourceRank(sourceId: String): Int =
        sources.everything.indexOfFirst { it.id == sourceId }.takeIf { it >= 0 } ?: Int.MAX_VALUE

    suspend fun manhwaByRemote(sourceId: String, remoteId: String): ManhwaEntity? =
        manhwaDao.byRemote(sourceId, remoteId)

    /**
     * The same title, as another installed source names it. Used when a title has no readable
     * chapters where it was found, which happens constantly on MangaDex for licensed series.
     * Sources that have already been tried for this title are passed in and skipped, so a search
     * cannot come back to where it started.
     */
    suspend fun findOnOtherSource(
        title: String,
        excluding: Set<String>,
    ): Pair<Source, SourceManhwa>? {
        val wanted = normaliseTitle(title)
        if (wanted.isBlank()) return null
        // Sources are tried in precedence order and the first hit wins, but the sweep as a whole
        // is capped: a site that hangs must not turn "this title is unreadable here" into a
        // minute of waiting before another source is opened.
        return withTimeoutOrNull(MATCH_SWEEP_TIMEOUT_MS) {
            for (source in sources.all) {
                if (source.id in excluding) continue
                val candidates = runCatching {
                    withTimeoutOrNull(LINK_SEARCH_TIMEOUT_MS) {
                        withContext(Dispatchers.IO) { source.search(title, 0, 20) }
                    }
                }.getOrNull().orEmpty()
                // Only the same title counts. A source's search happily returns a spin-off for
                // its parent, so a partial match would open "Solo Leveling: Arise" in place of
                // "Solo Leveling" — a different series with a different reader.
                val match = candidates.firstOrNull { normaliseTitle(it.title) == wanted }
                if (match != null) return@withTimeoutOrNull source to match
            }
            null
        }
    }

    /**
     * Every installed source that carries this series, not just the first. A profile merges their
     * chapter lists, so all of them are needed at once.
     *
     * [names] is the title together with the alternates the opening source publishes for it, and
     * a candidate matches when any of its own names matches any of these, which is how a series
     * that two sites title differently is still recognised as one.
     *
     * A source that fails is skipped: one site being down must not stop the others from filling
     * in the chapters it is missing.
     */
    suspend fun findEverywhere(
        names: List<String>,
        excluding: Set<String>,
    ): List<Pair<Source, SourceManhwa>> = coroutineScope {
        val wanted = nameKeys(names)
        val query = names.firstOrNull { normaliseTitle(it).isNotBlank() }
        if (wanted.isEmpty() || query == null) return@coroutineScope emptyList()
        sources.all
            .filter { it.id !in excluding }
            .map { source ->
                async(Dispatchers.IO) {
                    // The site's own search is the way in; the alternates are what make a hit on
                    // a differently-titled copy count as the same series.
                    val candidates = runCatching {
                        withTimeoutOrNull(LINK_SEARCH_TIMEOUT_MS) { source.search(query, 0, 20) }
                    }.getOrNull().orEmpty()
                    candidates.firstOrNull { sameTitle(names, listOf(it.title) + it.aliases) }
                        ?.let { source to it }
                }
            }
            .awaitAll()
            .filterNotNull()
    }

    /** Page counts arrive with the chapter, or after the first read on sources that omit them. */
    suspend fun updatePageCount(chapterId: Long, pageCount: Int) {
        val chapter = chapterDao.byId(chapterId) ?: return
        if (chapter.pageCount == pageCount) return
        chapterDao.update(chapter.copy(pageCount = pageCount))
    }


    /** Loads a manhwa from its source and refreshes its chapter list. Returns the local id. */
    suspend fun openManhwa(sourceId: String, remoteId: String): Long {
        val source = sources.get(sourceId)
        val fresh = withContext(Dispatchers.IO) { source.manhwa(remoteId) }
        val stored = upsert(fresh, source.id) ?: error("Could not store manhwa $remoteId")
        refreshChapters(stored.id)
        return stored.id
    }

    /**
     * Stores a copy of a title that a source search already found, then fills in its chapters.
     *
     * A merged profile opens every sibling copy just to add its chapters, and the search row
     * already carries the title and cover. Skipping the detail page halves the requests spent on
     * each linked source, which is the difference between a profile filling in over one round of
     * calls and two.
     */
    suspend fun openLinked(source: Source, listing: SourceManhwa): Long? {
        val stored = upsert(listing, source.id) ?: return null
        refreshChapters(stored.id)
        return stored.id
    }

    /** Fetches the chapter list and stores it, preserving read state on chapters already known. */
    suspend fun refreshChapters(manhwaId: Long): Int {
        val manhwa = manhwaDao.byId(manhwaId) ?: return 0
        val source = sources.get(manhwa.sourceId)
        val chapters = withContext(Dispatchers.IO) {
            source.chapters(manhwa.remoteId, language = "en")
        }
        val now = System.currentTimeMillis()
        // Read the stored rows once and write the differences in two batches. A per-chapter
        // lookup plus a per-chapter insert is two hundred transactions for a two-hundred-chapter
        // series, and the merged profile visibly filled in one row at a time because of it.
        val existing = chapterDao.listForManhwa(manhwaId).associateBy { it.remoteId }
        val inserts = mutableListOf<ChapterEntity>()
        val updates = mutableListOf<ChapterEntity>()
        for (chapter in chapters) {
            val held = existing[chapter.remoteId]
            if (held == null) {
                inserts += ChapterEntity(
                    manhwaId = manhwaId,
                    sourceId = manhwa.sourceId,
                    remoteId = chapter.remoteId,
                    numberText = chapter.numberText,
                    sortKey = chapter.sortKey,
                    title = chapter.title,
                    language = chapter.language,
                    pageCount = chapter.pageCount,
                    externalUrl = chapter.externalUrl,
                )
            } else {
                val next = held.copy(
                    numberText = chapter.numberText,
                    sortKey = chapter.sortKey,
                    title = chapter.title,
                    pageCount = chapter.pageCount,
                    externalUrl = chapter.externalUrl,
                )
                // Read state lives on these rows; an unchanged row is left alone entirely.
                if (next != held) updates += next
            }
        }
        if (inserts.isNotEmpty()) chapterDao.insertAll(inserts)
        if (updates.isNotEmpty()) chapterDao.updateAll(updates)
        manhwaDao.update(manhwa.copy(lastFetchedAt = now))
        return chapters.size
    }

    suspend fun setFollowed(manhwaId: Long, followed: Boolean) {
        if (followed) {
            followDao.add(FollowEntity(manhwaId, System.currentTimeMillis()))
        } else {
            followDao.remove(manhwaId)
        }
    }

    suspend fun setReadLater(manhwaId: Long, marked: Boolean) {
        if (marked) {
            laterDao.add(ReadLaterEntity(manhwaId, System.currentTimeMillis()))
        } else {
            laterDao.remove(manhwaId)
        }
    }

    /** "Read" means finished. Reaching the last page sets this; nothing else does. */
    suspend fun markRead(chapterId: Long, read: Boolean) {
        chapterDao.setReadAt(chapterId, if (read) System.currentTimeMillis() else null)
    }

    /**
     * Resume follows the most recently read chapter. The page index is clamped because a
     * chapter can be re-uploaded with a different page count.
     */
    suspend fun saveResume(manhwaId: Long, chapterId: Long, pageIndex: Int) {
        val chapter = chapterDao.byId(chapterId) ?: return
        val clamped = pageIndex.coerceIn(0, maxOf(0, chapter.pageCount - 1))
        resumeDao.put(
            ResumeEntity(
                manhwaId = manhwaId,
                chapterId = chapterId,
                pageIndex = clamped,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun resume(manhwaId: Long): ResumeEntity? = resumeDao.get(manhwaId)

    suspend fun chapter(chapterId: Long): ChapterEntity? = chapterDao.byId(chapterId)

    suspend fun chaptersFor(manhwaId: Long): List<ChapterEntity> = chapterDao.listForManhwa(manhwaId)

    /** Seeds for Browse recommendations: what the reader touched most recently, whatever it was. */
    suspend fun recentlyTouched(limit: Int): List<ManhwaEntity> =
        manhwaDao.recentlyTouched(limit).map { it.manhwa }

    /** Titles already read, followed or saved, so a recommendation never comes back around. */
    suspend fun consumedTitles(): List<String> = manhwaDao.consumedTitles()

    /** Removes catalogue rows the reader never read, followed, saved or downloaded. */
    suspend fun pruneCatalogue(olderThanMs: Long = CATALOGUE_TTL_MS): Int =
        manhwaDao.pruneUnused(System.currentTimeMillis() - olderThanMs)

    /** The next readable chapter in reading order, or null at the end of a series. */
    suspend fun nextChapter(manhwaId: Long, chapterId: Long): ChapterEntity? {
        val all = chapterDao.listForManhwa(manhwaId).filter { it.externalUrl == null }
        val index = all.indexOfFirst { it.id == chapterId }
        return if (index >= 0 && index + 1 < all.size) all[index + 1] else null
    }

    /**
     * The next readable chapter after [chapterId] in the merged list of a title, so a chapter that
     * only one source carries still leads into the next one, whichever source has it.
     */
    suspend fun nextChapterIn(
        manhwaIds: List<Long>,
        chapterId: Long,
        /** The title's source order, so a pinned source continues to win the next chapter too. */
        rank: (String) -> Int = { sourceRank(it) },
    ): ChapterEntity? {
        val current = chapterDao.byId(chapterId) ?: return null
        val merged = mergeChapters(chapterDao.listForManhwas(manhwaIds), rank)
            .filter { it.externalUrl == null }
        val exact = merged.indexOfFirst { it.id == chapterId }
        if (exact >= 0) return merged.getOrNull(exact + 1)
        // The profile lists one copy of each chapter number, and it is not always the copy the
        // reader opened: the fallback that rescued a dead chapter, or a pinned-source change,
        // can leave the reader on a row the merged list has replaced. Continue from that
        // chapter's place in the reading order instead of from its id, so a series with two
        // sources still advances instead of claiming it has ended.
        val sameNumber = merged.indexOfFirst {
            it.numberText.trim() == current.numberText.trim() && it.sortKey == current.sortKey
        }
        if (sameNumber >= 0) return merged.getOrNull(sameNumber + 1)
        // The row is not in this group at all; the first chapter that sorts after it is still
        // the right answer.
        return merged.firstOrNull { CHAPTER_ORDER.compare(it, current) > 0 }
    }

    /**
     * Other readable copies of one chapter number across the title's sources, best first.
     *
     * The merged profile keeps one copy of each chapter number, so the copy it shows is not always
     * the copy that still works: a source can delete a chapter's images, or lock it, while the row
     * stays in the list until the next refresh. This is the way round that — the reader falls back
     * to a sibling source's upload of the same chapter instead of telling the reader it is gone.
     */
    suspend fun alternativeCopies(
        manhwaIds: List<Long>,
        numberText: String,
        excludingChapterId: Long,
        rank: (String) -> Int = { sourceRank(it) },
    ): List<Pair<ChapterEntity, ManhwaEntity>> {
        val key = numberText.trim()
        val copies = chapterDao.listForManhwas(manhwaIds)
            .filter {
                it.id != excludingChapterId &&
                    it.externalUrl == null &&
                    it.numberText.trim() == key
            }
            .sortedBy { rank(it.sourceId) }
        return copies.mapNotNull { copy -> manhwaDao.byId(copy.manhwaId)?.let { copy to it } }
    }

    /** True when any copy of the title has something that can actually be read in the app. */
    suspend fun hasReadableChapter(manhwaIds: List<Long>): Boolean =
        chapterDao.listForManhwas(manhwaIds).any { it.externalUrl == null }

    /** Where "Continue Reading" should land: the resume point, else the first chapter. */
    suspend fun continuePoint(manhwaId: Long): Pair<ChapterEntity, Int>? {
        val saved = resumeDao.get(manhwaId)
        if (saved != null) {
            val chapter = chapterDao.byId(saved.chapterId)
            if (chapter != null && chapter.externalUrl == null) {
                val page = saved.pageIndex.coerceIn(0, maxOf(0, chapter.pageCount - 1))
                return chapter to page
            }
            resumeDao.clear(manhwaId)
        }
        val first = chapterDao.firstChapter(manhwaId) ?: return null
        return first to 0
    }

    /**
     * Stores one source's page of rows with a handful of queries instead of three per row.
     * Browse asks a dozen sources at once and stores up to thirty rows each; once the network
     * answered, the per-row lookup/write/lookup was the dominant cost of showing a page.
     */
    private suspend fun storeRows(rows: List<SourceManhwa>, sourceId: String): List<ManhwaEntity> {
        if (rows.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val unique = rows.distinctBy { it.remoteId }
        val existing = manhwaDao.byRemotes(sourceId, unique.map { it.remoteId })
            .associateBy { it.remoteId }
        val inserts = mutableListOf<ManhwaEntity>()
        val updates = mutableListOf<ManhwaEntity>()
        for (fresh in unique) {
            val held = existing[fresh.remoteId]
            if (held == null) {
                inserts += ManhwaEntity(
                    sourceId = sourceId,
                    remoteId = fresh.remoteId,
                    title = fresh.title,
                    description = fresh.description,
                    coverUrl = fresh.coverUrl,
                    year = fresh.year,
                    status = fresh.status,
                    lastFetchedAt = now,
                )
            } else {
                updates += held.copy(
                    title = fresh.title,
                    description = fresh.description ?: held.description,
                    coverUrl = fresh.coverUrl ?: held.coverUrl,
                    year = fresh.year ?: held.year,
                    status = fresh.status ?: held.status,
                    lastFetchedAt = now,
                )
            }
        }
        // Inserted ids come back in the same order as the list, so a new row is addressed by
        // the id it was given without a second SELECT.
        val insertedIds = if (inserts.isEmpty()) emptyList() else manhwaDao.insertAll(inserts)
        if (updates.isNotEmpty()) manhwaDao.updateAll(updates)
        val byRemote = LinkedHashMap<String, ManhwaEntity>(unique.size)
        existing.forEach { (remoteId, row) -> byRemote[remoteId] = row }
        inserts.forEachIndexed { index, row ->
            val id = insertedIds.getOrNull(index) ?: return@forEachIndexed
            byRemote[row.remoteId] = row.copy(id = id)
        }
        updates.forEach { row -> byRemote[row.remoteId] = row }
        return unique.mapNotNull { byRemote[it.remoteId] }
    }

    private suspend fun upsert(source: SourceManhwa, sourceId: String): ManhwaEntity? =
        storeRows(listOf(source), sourceId).firstOrNull()
}
