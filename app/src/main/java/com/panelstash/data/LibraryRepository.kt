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
import com.panelstash.source.SourceGenre
import com.panelstash.source.SourceManhwa
import com.panelstash.source.SourceRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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

    fun observeChapters(manhwaId: Long): Flow<List<ChapterEntity>> = chapterDao.observeForManhwa(manhwaId)

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
        val rows = if (query.isBlank()) {
            source.browse(discoverySort(source), tagId = null, filter = BrowseFilter.None, offset, limit)
        } else {
            source.search(query = query, offset = offset, limit = limit)
        }
        return SearchPage(
            items = rows.mapNotNull { upsert(it, source.id) },
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
    suspend fun searchAll(query: String, offsets: Map<String, Int>, limit: Int): SearchPage = coroutineScope {
        val pages = sources.all.map { source ->
            val offset = offsets[source.id] ?: 0
            async {
                val result = runCatching {
                    if (query.isBlank()) {
                        source.browse(discoverySort(source), null, BrowseFilter.None, offset, limit)
                    } else {
                        source.search(query, offset, limit)
                    }
                }
                Triple(source, offset, result)
            }
        }.awaitAll()

        val firstError = pages.firstNotNullOfOrNull { it.third.exceptionOrNull() }
        val nextOffsets = offsets.toMutableMap()
        val perSource = ranked(pages, nextOffsets)
        val items = interleaveRanked(perSource, perSource.sumOf { it.second.size }).map { it.second }
        if (items.isEmpty()) firstError?.let { throw it }
        SearchPage(
            items = items,
            nextOffsets = nextOffsets,
            exhausted = pages.all { it.third.getOrDefault(emptyList()).size < limit },
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
    ): BrowsePage = coroutineScope {
        val pages = sources.all.map { source ->
            val offset = offsets[source.id] ?: 0
            async {
                Triple(source, offset, runCatching { source.browse(sort, tagId, filter, offset, limit) })
            }
        }.awaitAll()

        val firstError = pages.firstNotNullOfOrNull { it.third.exceptionOrNull() }
        val nextOffsets = offsets.toMutableMap()
        val exhausted = pages.all { (_, _, result) ->
            result.isSuccess && result.getOrDefault(emptyList()).size < limit
        }
        // Interleaved by rank: with every source selected, a page must not be one site's list.
        val perSource = ranked(pages, nextOffsets)
        val items = interleaveRanked(perSource, perSource.sumOf { it.second.size }).map { it.second }
        if (items.isEmpty()) firstError?.let { throw it }
        BrowsePage(items = items, nextOffsets = nextOffsets, exhausted = exhausted)
    }

    /**
     * Stores an aggregate page's rows and keeps the chapter count each source reported, so the
     * merge can prefer the complete copy of a title two sites both publish.
     */
    private suspend fun ranked(
        pages: List<Triple<Source, Int, Result<List<SourceManhwa>>>>,
        nextOffsets: MutableMap<String, Int>,
    ): List<Pair<String, List<RankedManhwa>>> = pages.map { (source, offset, result) ->
        val rows = result.getOrDefault(emptyList())
        if (result.isSuccess) nextOffsets[source.id] = offset + rows.size
        source.id to rows.mapNotNull { fresh ->
            upsert(fresh, source.id)?.let { RankedManhwa(it, fresh.chapterCount) }
        }
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
        return source.browse(sort, tagId, filter, offset, limit).mapNotNull { upsert(it, source.id) }
    }

    suspend fun genres(sourceId: String): List<SourceGenre> = sources.get(sourceId).genres()

    suspend fun manhwa(manhwaId: Long): ManhwaEntity? = manhwaDao.byId(manhwaId)

    fun source(sourceId: String): Source? = sources.getOrNull(sourceId)

    fun installedSources(): List<Source> = sources.all

    /** Every source the app knows, including ones switched off in settings. */
    fun allSources(): List<Source> = sources.everything

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
        for (source in sources.all) {
            if (source.id in excluding) continue
            val candidates = runCatching { source.search(title, 0, 20) }.getOrNull().orEmpty()
            // Only the same title counts. A source's search happily returns a spin-off for its
            // parent, so a partial match would open "Solo Leveling: Arise" in place of "Solo
            // Leveling" — a different series with a different reader.
            val match = candidates.firstOrNull { normaliseTitle(it.title) == wanted }
            if (match != null) return source to match
        }
        return null
    }

    /** Page counts arrive with the chapter, or after the first read on sources that omit them. */
    suspend fun updatePageCount(chapterId: Long, pageCount: Int) {
        val chapter = chapterDao.byId(chapterId) ?: return
        if (chapter.pageCount == pageCount) return
        chapterDao.update(chapter.copy(pageCount = pageCount))
    }

    /**
     * A title as a comparison key: letters and digits only, with a bracketed annotation dropped,
     * so "Solo Leveling (Official)" and "Solo Leveling" are the same title.
     */
    private fun normaliseTitle(value: String): String = value
        .replace(Regex("\\([^)]*\\)|\\[[^]]*]|【[^】]*】"), " ")
        .lowercase()
        .filter { it.isLetterOrDigit() }

    /** Loads a manhwa from its source and refreshes its chapter list. Returns the local id. */
    suspend fun openManhwa(sourceId: String, remoteId: String): Long {
        val source = sources.get(sourceId)
        val fresh = source.manhwa(remoteId)
        val stored = upsert(fresh, source.id) ?: error("Could not store manhwa $remoteId")
        refreshChapters(stored.id)
        return stored.id
    }

    /** Fetches the chapter list and stores it, preserving read state on chapters already known. */
    suspend fun refreshChapters(manhwaId: Long): Int {
        val manhwa = manhwaDao.byId(manhwaId) ?: return 0
        val source = sources.get(manhwa.sourceId)
        val chapters = source.chapters(manhwa.remoteId, language = "en")
        val now = System.currentTimeMillis()
        for (chapter in chapters) {
            val existing = chapterDao.byRemote(manhwa.sourceId, chapter.remoteId)
            if (existing == null) {
                chapterDao.insert(
                    ChapterEntity(
                        manhwaId = manhwaId,
                        sourceId = manhwa.sourceId,
                        remoteId = chapter.remoteId,
                        numberText = chapter.numberText,
                        sortKey = chapter.sortKey,
                        title = chapter.title,
                        language = chapter.language,
                        pageCount = chapter.pageCount,
                        externalUrl = chapter.externalUrl,
                    ),
                )
            } else {
                chapterDao.update(
                    existing.copy(
                        numberText = chapter.numberText,
                        sortKey = chapter.sortKey,
                        title = chapter.title,
                        pageCount = chapter.pageCount,
                        externalUrl = chapter.externalUrl,
                    ),
                )
            }
        }
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

    suspend fun firstChapter(manhwaId: Long): ChapterEntity? = chapterDao.firstChapter(manhwaId)

    /** The next readable chapter in reading order, or null at the end of a series. */
    suspend fun nextChapter(manhwaId: Long, chapterId: Long): ChapterEntity? {
        val all = chapterDao.listForManhwa(manhwaId).filter { it.externalUrl == null }
        val index = all.indexOfFirst { it.id == chapterId }
        return if (index >= 0 && index + 1 < all.size) all[index + 1] else null
    }

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

    private suspend fun upsert(source: SourceManhwa, sourceId: String): ManhwaEntity? {
        val existing = manhwaDao.byRemote(sourceId, source.remoteId)
        val now = System.currentTimeMillis()
        return if (existing == null) {
            val id = manhwaDao.insert(
                ManhwaEntity(
                    sourceId = sourceId,
                    remoteId = source.remoteId,
                    title = source.title,
                    description = source.description,
                    coverUrl = source.coverUrl,
                    year = source.year,
                    status = source.status,
                    lastFetchedAt = now,
                ),
            )
            manhwaDao.byId(id)
        } else {
            manhwaDao.update(
                existing.copy(
                    title = source.title,
                    description = source.description ?: existing.description,
                    coverUrl = source.coverUrl ?: existing.coverUrl,
                    year = source.year ?: existing.year,
                    status = source.status ?: existing.status,
                    lastFetchedAt = now,
                ),
            )
            manhwaDao.byId(existing.id)
        }
    }
}
