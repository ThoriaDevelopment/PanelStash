package com.panelstash.download

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.panelstash.R

/**
 * The only notification the app posts: download status, silent and low importance.
 * Content notifications are out of scope by decision.
 */
object DownloadNotifications {

    private const val CHANNEL_ID = "downloads"
    private const val NOTIFICATION_ID = 1

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        val channel = NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun notifyProgress(context: Context, title: String, chapter: String, fraction: Float) {
        post(context, "$title · $chapter", progress = (fraction * 100).toInt(), showProgress = true)
    }

    fun notifyDone(context: Context, title: String, chapter: String) {
        post(context, "$title · $chapter", progress = 0, showProgress = false)
    }

    private fun post(context: Context, text: String, progress: Int, showProgress: Boolean) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Downloading")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOngoing(showProgress)
            .setProgress(100, progress, false)
            .build()
        // Notification permission can be denied; downloads still run, only the progress
        // notification goes missing.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }
}
