package com.panelstash

import android.app.Application
import coil3.SingletonImageLoader
import com.panelstash.download.DownloadNotifications

class PanelStashApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        DownloadNotifications.ensureChannel(this)
        // Streaming images must go through the same reporting HTTP stack as downloads.
        SingletonImageLoader.setSafe { container.imageLoader }
    }
}
