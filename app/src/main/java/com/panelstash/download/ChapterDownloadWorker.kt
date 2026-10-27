package com.panelstash.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.panelstash.PanelStashApp
import com.panelstash.source.PageQuality
import com.panelstash.source.SourceError
import com.panelstash.source.shortLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * One chapter is one unit of work, which keeps every job well inside the platform limits
 * and makes a failed chapter cheap to retry.
 */
class ChapterDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val container = (applicationContext as PanelStashApp).container
        val chapterId = inputData.getLong(KEY_CHAPTER_ID, -1L)
        if (chapterId <= 0) return@withContext Result.failure()

        val chapter = container.db.chapterDao().byId(chapterId) ?: return@withContext Result.failure()
        val manhwa = container.db.manhwaDao().byId(chapter.manhwaId) ?: return@withContext Result.failure()
        val quality = PageQuality.valueOf(
            inputData.getString(KEY_QUALITY) ?: container.settings.downloadQuality.name,
        )

        DownloadNotifications.notifyProgress(applicationContext, manhwa.title, chapter.numberText, 0f)

        var dir: File? = null
        try {
            val source = container.sources.get(manhwa.sourceId)
            dir = container.downloads.begin(
                manhwa = manhwa,
                chapter = chapter,
                quality = quality,
                // The queue's own policy, recorded rather than re-derived from the network here.
                meteredAllowed = inputData.getBoolean(KEY_METERED, false),
            )
            val pages = source.pages(chapter.remoteId, quality).images
            var written = 0L

            for (page in pages) {
                val target = container.downloads.pageFile(dir, page.index, page.url)
                container.imageClient.newCall(Request.Builder().url(page.url).build()).execute().use { response ->
                    if (!response.isSuccessful) throw SourceError.Transient()
                    val body = response.body ?: throw SourceError.Transient()
                    target.outputStream().use { output -> body.byteStream().copyTo(output) }
                    written += target.length()
                }
                DownloadNotifications.notifyProgress(
                    applicationContext,
                    manhwa.title,
                    chapter.numberText,
                    (page.index + 1f) / pages.size,
                )
            }

            container.downloads.complete(manhwa.id, chapterId, dir, written, quality)
            DownloadNotifications.notifyDone(applicationContext, manhwa.title, chapter.numberText)
            Result.success()
        } catch (error: SourceError) {
            container.downloads.fail(manhwa.id, chapterId, dir, quality, error.shortLabel())
            when (error) {
                is SourceError.RateLimited -> Result.retry()
                is SourceError.Banned -> Result.retry()
                is SourceError.Transient -> Result.retry()
                is SourceError.Offline -> Result.retry()
                is SourceError.Unavailable -> Result.failure()
            }
        } catch (error: Exception) {
            container.downloads.fail(manhwa.id, chapterId, dir, quality, "Failed")
            Result.retry()
        }
    }

    companion object {
        const val KEY_CHAPTER_ID = "chapter_id"
        const val KEY_QUALITY = "quality"
        const val KEY_METERED = "metered_allowed"
    }
}
