package com.panelstash.data

import com.panelstash.data.db.ChapterEntity
import com.panelstash.data.db.ManhwaEntity
import com.panelstash.download.DownloadStore
import com.panelstash.source.SourceRegistry
import java.io.File

sealed interface ReaderPage {
    /** Pixels, when the source reports them. Zero means "unknown", not "empty". */
    val width: Int
    val height: Int

    /** Width over height, or null when the source did not say. */
    fun aspectOrNull(): Float? =
        if (width > 0 && height > 0) width.toFloat() / height.toFloat() else null

    data class Local(
        val file: File,
        override val width: Int = 0,
        override val height: Int = 0,
    ) : ReaderPage

    data class Remote(
        val url: String,
        override val width: Int = 0,
        override val height: Int = 0,
    ) : ReaderPage
}

/**
 * Read order is downloaded file, then the disposable image cache, then the network.
 * A downloaded chapter is never re-fetched.
 *
 * Resolved page lists are kept in memory briefly: stepping out of a chapter and back in should
 * not cost another round trip, but a stale list must not outlive the signed URLs it holds.
 */
class ReaderRepository(
    private val sources: SourceRegistry,
    private val downloads: DownloadStore,
    private val settings: Settings,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val pages: List<ReaderPage>, val at: Long)

    private val cache = LinkedHashMap<Long, Entry>()

    suspend fun pages(chapter: ChapterEntity, manhwa: ManhwaEntity): List<ReaderPage> {
        // A downloaded copy is checked before the cache, so finishing a download takes effect on
        // the very next open rather than after the cached page list expires.
        val local = downloads.localPages(chapter.id)
        if (local != null) return local.map { ReaderPage.Local(it) }

        cache[chapter.id]?.let { entry ->
            if (clock() - entry.at < CACHE_TTL_MS) return entry.pages
            cache.remove(chapter.id)
        }

        val pages = resolveRemote(chapter, manhwa)
        cache[chapter.id] = Entry(pages, clock())
        if (cache.size > CACHE_LIMIT) cache.remove(cache.keys.first())
        return pages
    }

    /** Drops the cached page list for a chapter, e.g. after its download was deleted. */
    fun invalidate(chapterId: Long) {
        cache.remove(chapterId)
    }

    private suspend fun resolveRemote(chapter: ChapterEntity, manhwa: ManhwaEntity): List<ReaderPage> {
        val source = sources.get(manhwa.sourceId)
        val set = source.pages(chapter.remoteId, settings.streamingQuality)
        return set.images.map { ReaderPage.Remote(it.url, it.width, it.height) }
    }

    private companion object {
        const val CACHE_TTL_MS = 10 * 60 * 1000L
        const val CACHE_LIMIT = 8
    }
}
