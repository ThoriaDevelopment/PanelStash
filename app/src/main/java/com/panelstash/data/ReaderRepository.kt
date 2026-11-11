package com.panelstash.data

import com.panelstash.data.db.ChapterEntity
import com.panelstash.data.db.ManhwaEntity
import android.graphics.BitmapFactory
import com.panelstash.download.DownloadStore
import com.panelstash.source.SourceRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface ReaderPage {
    /** Pixels, when the source reports them. Zero means "unknown", not "empty". */
    val width: Int
    val height: Int

    /** Identity of the bytes, used to remember a shape a page turned out to have. */
    val key: String

    /** Width over height, or null when the source did not say. */
    fun aspectOrNull(): Float? =
        if (width > 0 && height > 0) width.toFloat() / height.toFloat() else null

    data class Local(
        val file: File,
        override val width: Int = 0,
        override val height: Int = 0,
    ) : ReaderPage {
        override val key: String get() = file.path
    }

    data class Remote(
        val url: String,
        override val width: Int = 0,
        override val height: Int = 0,
    ) : ReaderPage {
        override val key: String get() = url
    }
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

    /**
     * One resolution per chapter at a time. The reader opening a chapter and the prefetch of the
     * chapter after it can both ask for the same pages, and each used to resolve them: two rounds
     * of requests for one list. The second caller now waits and shares the answer.
     */
    private val locks = mutableMapOf<Long, Mutex>()

    suspend fun pages(chapter: ChapterEntity, manhwa: ManhwaEntity): List<ReaderPage> {
        // A downloaded copy is checked before the cache, so finishing a download takes effect on
        // the very next open rather than after the cached page list expires.
        val local = downloads.localPages(chapter.id)
        if (local != null) return withContext(Dispatchers.IO) {
            // Read the bounds so a downloaded webtoon keeps its exact shape before the bytes
            // load. Without them a tall strip was requested at its full height, decoded as a
            // hardware bitmap, and drawn as a black rectangle on phones with an 8k texture limit.
            local.map { file ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                ReaderPage.Local(file, bounds.outWidth, bounds.outHeight)
            }
        }

        cached(chapter.id)?.let { return it }

        val lock = synchronized(locks) {
            // Only needed while callers are waiting on it, so the map is dropped once it is large.
            if (locks.size > LOCK_LIMIT) locks.clear()
            locks.getOrPut(chapter.id) { Mutex() }
        }
        return lock.withLock {
            cached(chapter.id) ?: resolveRemote(chapter, manhwa).also { pages ->
                cache[chapter.id] = Entry(pages, clock())
                if (cache.size > CACHE_LIMIT) cache.remove(cache.keys.first())
            }
        }
    }

    private fun cached(chapterId: Long): List<ReaderPage>? {
        val entry = cache[chapterId] ?: return null
        if (clock() - entry.at < CACHE_TTL_MS) return entry.pages
        cache.remove(chapterId)
        return null
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

        /** Chapters whose page list is remembered, so stepping between them stays a cache hit. */
        const val CACHE_LIMIT = 16
        const val LOCK_LIMIT = 64
    }
}
