package com.panelstash.data

import com.panelstash.data.db.ChapterEntity
import com.panelstash.data.db.ContinueRow
import com.panelstash.data.db.FollowEntity
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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

    fun observeFollowed(): Flow<List<ManhwaEntity>> = followDao.observeAll()

    fun observeReadLater(): Flow<List<ManhwaEntity>> = laterDao.observeAll()

    fun observeFollowedState(manhwaId: Long): Flow<Boolean> = followDao.observe(manhwaId).map { it != null }
    fun observeReadLaterState(manhwaId: Long): Flow<Boolean> = laterDao.observe(manhwaId).map { it != null }

    suspend fun search(sourceId: String, query: String): List<ManhwaEntity> {
        val source = sources.get(sourceId)
        val results = if (query.isBlank()) {
            source.browse(BrowseSort.LATEST, tagId = null, filter = BrowseFilter.None, offset = 0, limit = 30)
        } else {
            source.search(query = query, offset = 0, limit = 30)
        }
        return results.mapNotNull { upsert(it, source.id) }
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

    fun source(sourceId: String): Source? = sources.getOrNull(sourceId)

    fun installedSources(): List<Source> = sources.all

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
            val match = candidates.firstOrNull { normaliseTitle(it.title) == wanted }
                ?: candidates.firstOrNull { normaliseTitle(it.title).contains(wanted) }
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

    suspend fun previousChapter(manhwaId: Long, chapterId: Long): ChapterEntity? {
        val all = chapterDao.listForManhwa(manhwaId)
        val index = all.indexOfFirst { it.id == chapterId }
        return if (index > 0) all[index - 1] else null
    }

    private fun normaliseTitle(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }

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
                    ),
                )
            } else {
                chapterDao.update(
                    existing.copy(
                        numberText = chapter.numberText,
                        sortKey = chapter.sortKey,
                        title = chapter.title,
                        pageCount = chapter.pageCount,
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

    suspend fun firstChapter(manhwaId: Long): ChapterEntity? = chapterDao.firstChapter(manhwaId)

    /** The next chapter in reading order, or null at the end of a series. */
    suspend fun nextChapter(manhwaId: Long, chapterId: Long): ChapterEntity? {
        val all = chapterDao.listForManhwa(manhwaId)
        val index = all.indexOfFirst { it.id == chapterId }
        return if (index >= 0 && index + 1 < all.size) all[index + 1] else null
    }

    /** Where "Continue Reading" should land: the resume point, else the first chapter. */
    suspend fun continuePoint(manhwaId: Long): Pair<ChapterEntity, Int>? {
        val saved = resumeDao.get(manhwaId)
        if (saved != null) {
            val chapter = chapterDao.byId(saved.chapterId)
            if (chapter != null) {
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
