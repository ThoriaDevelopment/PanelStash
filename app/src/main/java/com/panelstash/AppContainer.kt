package com.panelstash

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.panelstash.data.DiscoveryRepository
import com.panelstash.data.LibraryRepository
import com.panelstash.data.ReaderRepository
import com.panelstash.data.Settings
import com.panelstash.data.db.PanelStashDatabase
import com.panelstash.download.DownloadScheduler
import com.panelstash.download.DownloadStore
import com.panelstash.source.AsuraScansSource
import com.panelstash.source.AtHomeReporter
import com.panelstash.source.FlameComicsSource
import com.panelstash.source.KaynScansSource
import com.panelstash.source.MangaDexSource
import com.panelstash.source.MangaPillSource
import com.panelstash.source.Network
import com.panelstash.source.NyxScansSource
import com.panelstash.source.OrionScansSource
import com.panelstash.source.SourceGate
import com.panelstash.source.SourceRegistry
import com.panelstash.source.VortexScansSource
import com.panelstash.source.WeebCentralSource
import com.panelstash.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One module, one user: a hand-rolled container is less machinery than a DI framework.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db = PanelStashDatabase.get(appContext)
    val settings = Settings(appContext)

    private val reporter = AtHomeReporter(scope)

    /** One OkHttp stack for API and images, so @Home reporting has a single hook. */
    val imageClient = Network.imageClient(reporter)

    /**
     * One client, one gate per source: a 403 from one site must not pause the others, but MangaDex
     * shares its limit across everything, so its gate is shared with the at-home reporter.
     */
    private val apiClient = Network.apiClient()

    val sources = SourceRegistry(
        sources = listOf(
            MangaDexSource(apiClient, SourceGate()),
            AsuraScansSource(apiClient, SourceGate()),
            VortexScansSource(apiClient, SourceGate()),
            NyxScansSource(apiClient, SourceGate()),
            OrionScansSource(apiClient, SourceGate()),
            KaynScansSource(apiClient, SourceGate()),
            FlameComicsSource(apiClient, SourceGate()),
            WeebCentralSource(apiClient, SourceGate()),
            MangaPillSource(apiClient, SourceGate()),
        ),
        // Read on every access, so switching a source off takes effect without a restart.
        disabled = { settings.current.disabledSourceIds },
    )

    val library = LibraryRepository(sources, db)
    val discovery = DiscoveryRepository(library)
    val downloads = DownloadStore(appContext, db, scope)
    val reader = ReaderRepository(sources, downloads, settings)
    val scheduler = DownloadScheduler(appContext)

    /** GitHub releases on their own client: an update check must not borrow a source's gate. */
    val updater = Updater(appContext, Network.apiClient(), scope)

    /**
     * Caches are sized explicitly rather than left to the defaults: reading and browsing are
     * image-bound, and a chapter that has been seen once should come back from disk instantly.
     */
    val imageLoader: ImageLoader = ImageLoader.Builder(appContext)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { imageClient }))
        }
        .memoryCache {
            MemoryCache.Builder()
                .maxSizePercent(appContext, 0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(appContext.cacheDir.resolve("images"))
                .maxSizeBytes(512L * 1024L * 1024L)
                .build()
        }
        .crossfade(true)
        .build()

    init {
        // A download that was waiting for its undo window when the process died is unreachable.
        // Off the main thread: it is a directory walk, and this runs while the first screen is
        // being drawn.
        scope.launch { downloads.clearStaleTrash() }
        // A download interrupted by the process dying would otherwise read "downloading" for ever.
        scope.launch { downloads.resetInterrupted() }
        // Catalogue rows nobody read, followed or saved are not worth keeping indefinitely.
        scope.launch { library.pruneCatalogue() }
    }

    /**
     * The image cache is disposable, so it can be cleared on demand from settings. Called off the
     * main thread: clearing the disk cache is a directory walk.
     */
    suspend fun clearImageCache() {
        withContext(Dispatchers.IO) {
            imageLoader.memoryCache?.clear()
            imageLoader.diskCache?.clear()
        }
    }

    /** Image requests are sized against this, so a prefetch and a read decode the same bitmap. */
    fun screenWidthPx(): Int = appContext.resources.displayMetrics.widthPixels.coerceAtLeast(1)
}
