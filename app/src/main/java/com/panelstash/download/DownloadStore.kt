package com.panelstash.download

import android.content.Context
import com.panelstash.data.db.ChapterEntity
import com.panelstash.data.db.DownloadEntity
import com.panelstash.data.db.DownloadState
import com.panelstash.data.db.ManhwaEntity
import com.panelstash.data.db.PanelStashDatabase
import com.panelstash.source.PageQuality
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


/**
 * A downloaded chapter is a directory of page images owned by the downloader and recorded
 * in Room. "Downloaded" is answered by the record, never by the image cache, and the two
 * never share storage.
 */
class DownloadStore(
    private val context: Context,
    private val db: PanelStashDatabase,
    /** Only used for work the caller cannot wait on, such as emptying the undo bin. */
    private val scope: CoroutineScope,
) {
    private val dao = db.downloadDao()

    private val trashDir = File(context.cacheDir, "undo")

    private class Pending(val record: DownloadEntity, val dir: File)

    private var pending: Pending? = null

    fun chapterDir(manhwa: ManhwaEntity, chapter: ChapterEntity): File =
        File(context.filesDir, "downloads/${manhwa.sourceId}/${manhwa.remoteId}/${chapter.remoteId}")

    fun pageFile(dir: File, index: Int, url: String): File {
        val extension = url.substringAfterLast('.', "jpg").substringBefore('?').take(4).lowercase()
        val safe = if (extension.matches(Regex("[a-z0-9]{2,4}"))) extension else "jpg"
        return File(dir, "%04d.%s".format(index, safe))
    }

    suspend fun record(chapterId: Long): DownloadEntity? = dao.byChapter(chapterId)

    fun observeForManhwa(manhwaId: Long) = dao.observeForManhwa(manhwaId)

    fun observeAll() = dao.observeAll()

    fun observeTotalBytes() = dao.observeTotalBytes()

    /** Marks a chapter as in progress and returns the (clean) directory to write into. */
    suspend fun begin(
        manhwa: ManhwaEntity,
        chapter: ChapterEntity,
        quality: PageQuality,
        meteredAllowed: Boolean,
    ): File = withContext(Dispatchers.IO) {
        val dir = chapterDir(manhwa, chapter)
        dir.deleteRecursively()
        dir.mkdirs()
        dao.put(
            DownloadEntity(
                chapterId = chapter.id,
                manhwaId = manhwa.id,
                quality = quality.name,
                meteredAllowed = meteredAllowed,
                state = DownloadState.RUNNING,
                localDir = dir.absolutePath,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        dir
    }

    suspend fun complete(manhwaId: Long, chapterId: Long, dir: File, bytes: Long, quality: PageQuality) {
        // Keep the policy the queue was created with rather than rewriting it here.
        val existing = dao.byChapter(chapterId)
        dao.put(
            DownloadEntity(
                chapterId = chapterId,
                manhwaId = manhwaId,
                quality = existing?.quality ?: quality.name,
                meteredAllowed = existing?.meteredAllowed ?: false,
                state = DownloadState.COMPLETE,
                byteSize = bytes,
                localDir = dir.absolutePath,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** A failed or cancelled download leaves no half-usable chapter behind. */
    suspend fun fail(manhwaId: Long, chapterId: Long, dir: File?, quality: PageQuality, error: String) {
        dir?.deleteRecursively()
        val existing = dao.byChapter(chapterId)
        dao.put(
            DownloadEntity(
                chapterId = chapterId,
                manhwaId = manhwaId,
                quality = existing?.quality ?: quality.name,
                meteredAllowed = existing?.meteredAllowed ?: false,
                state = DownloadState.FAILED,
                localDir = null,
                error = error,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun markQueued(manhwaId: Long, chapterId: Long, quality: PageQuality, meteredAllowed: Boolean) {
        dao.put(
            DownloadEntity(
                chapterId = chapterId,
                manhwaId = manhwaId,
                quality = quality.name,
                meteredAllowed = meteredAllowed,
                state = DownloadState.QUEUED,
                localDir = dao.byChapter(chapterId)?.localDir,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** Pages of a completed download, ordered. Null means "not downloaded". */
    suspend fun localPages(chapterId: Long): List<File>? = withContext(Dispatchers.IO) {
        val record = dao.byChapter(chapterId) ?: return@withContext null
        if (record.state != DownloadState.COMPLETE) return@withContext null
        val dir = record.localDir?.let(::File) ?: return@withContext null
        val files = dir.listFiles { file -> file.isFile }?.sortedBy { it.name } ?: return@withContext null
        files.ifEmpty { null }
    }

    /**
     * Deletes a downloaded chapter.
     *
     * A completed download is moved aside rather than erased, so the undo the user is shown is a
     * real restore and not a redownload. Returns true when there is something to take back; a
     * queued or failed chapter has nothing to keep.
     */
    suspend fun delete(chapterId: Long, keepForUndo: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val record = dao.byChapter(chapterId) ?: return@withContext false
        discardPending()
        val dir = record.localDir?.let(::File)
        val undoable = keepForUndo &&
            record.state == DownloadState.COMPLETE &&
            dir != null &&
            dir.isDirectory

        if (undoable) {
            val target = File(trashDir, chapterId.toString())
            target.deleteRecursively()
            target.parentFile?.mkdirs()
            if (dir!!.renameTo(target)) {
                pending = Pending(record, target)
            } else {
                dir.deleteRecursively()
            }
        } else {
            dir?.deleteRecursively()
        }

        dao.delete(chapterId)
        pending != null
    }

    /** Puts a moved-aside chapter back. False means the copy is gone and a redownload is needed. */
    suspend fun restore(chapterId: Long): Boolean = withContext(Dispatchers.IO) {
        val saved = pending ?: return@withContext false
        if (saved.record.chapterId != chapterId) return@withContext false
        pending = null
        val target = saved.record.localDir?.let(::File) ?: return@withContext false
        target.deleteRecursively()
        target.parentFile?.mkdirs()
        if (!saved.dir.renameTo(target)) {
            saved.dir.deleteRecursively()
            return@withContext false
        }
        dao.put(saved.record)
        true
    }

    /** Called when the undo window closes, or when another delete replaces it. */
    fun discardPending() {
        pending?.let { saved ->
            // Deleted off the caller's thread: this runs from the UI when the undo window shuts.
            scope.launch(Dispatchers.IO) { saved.dir.deleteRecursively() }
        }
        pending = null
    }

    /** Anything left in the trash is from a process that died mid-undo, so it is unreachable. */
    suspend fun clearStaleTrash() = withContext(Dispatchers.IO) {
        trashDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    /**
     * A row that was still RUNNING when the process died has no worker behind it. It is closed
     * out at startup so the chapter reads "failed" instead of "downloading" for ever, and its
     * half-written directory is removed. If WorkManager still has the job, it starts again and
     * sets the row back to RUNNING by itself.
     */
    suspend fun resetInterrupted() = withContext(Dispatchers.IO) {
        for (row in dao.running()) {
            row.localDir?.let { File(it).deleteRecursively() }
            dao.put(
                row.copy(
                    state = DownloadState.FAILED,
                    localDir = null,
                    error = "Interrupted",
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /** Chapter ids with a download row, so their queued work can be cancelled before deletion. */
    suspend fun chapterIdsFor(manhwaId: Long): List<Long> = dao.byManhwa(manhwaId).map { it.chapterId }

    suspend fun deleteAllForManhwa(manhwaId: Long) = withContext(Dispatchers.IO) {
        for (row in dao.byManhwa(manhwaId)) {
            row.localDir?.let { File(it).deleteRecursively() }
        }
        dao.deleteForManhwa(manhwaId)
    }
}
