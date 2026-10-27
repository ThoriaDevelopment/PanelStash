package com.panelstash.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.panelstash.data.db.ChapterEntity
import com.panelstash.data.db.ManhwaEntity
import com.panelstash.source.PageQuality
import java.util.concurrent.TimeUnit

/**
 * Chapter-by-chapter downloads. Unmetered by default; a queue explicitly allowed to use
 * mobile data passes meteredAllowed = true for that queue only.
 */
class DownloadScheduler(private val context: Context) {

    fun enqueue(
        manhwa: ManhwaEntity,
        chapters: List<ChapterEntity>,
        quality: PageQuality,
        meteredAllowed: Boolean,
    ) {
        val networkType = if (meteredAllowed) NetworkType.CONNECTED else NetworkType.UNMETERED
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .build()

        for (chapter in chapters) {
            val request = OneTimeWorkRequestBuilder<ChapterDownloadWorker>()
                .setConstraints(constraints)
                .setInputData(
                    Data.Builder()
                        .putLong(ChapterDownloadWorker.KEY_CHAPTER_ID, chapter.id)
                        .putString(ChapterDownloadWorker.KEY_QUALITY, quality.name)
                        .putBoolean(ChapterDownloadWorker.KEY_METERED, meteredAllowed)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("chapter-${chapter.id}")
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "chapter-${chapter.id}",
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
        DownloadNotifications.ensureChannel(context)
    }

    fun cancel(chapterId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork("chapter-$chapterId")
    }

    fun cancelAll() {
        WorkManager.getInstance(context).cancelAllWork()
    }
}
