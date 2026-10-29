package com.panelstash.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.panelstash.source.Network
import java.io.File

/**
 * Where the app is in the update conversation. Every screen state comes from this one flow, so
 * checking, downloading and installing cannot disagree about what is happening.
 */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val version: String, val size: Long) : UpdateState
    data class Downloading(val version: String, val received: Long, val total: Long) : UpdateState
    data class Ready(val version: String) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** A check is a network request, so an automatic one waits rather than firing on every visit. */
private const val CHECK_INTERVAL_MS = 10 * 60 * 1000L

/** Progress is reported in steps this large, so a download does not recompose on every packet. */
private const val REPORT_STEP_BYTES = 256L * 1024L

/** An APK on a slow connection can take minutes; the usual 30 second limit would cut it off. */
private const val DOWNLOAD_TIMEOUT_MS = 10 * 60 * 1000L

/**
 * Self-updating without an app store: read the newest GitHub release, download the APK attached
 * to it, and hand that file to the system installer. Nothing here installs silently, which the
 * platform does not allow for a sideloaded app anyway.
 */
class Updater(
    context: Context,
    private val client: HttpClient,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val releaseDir = File(appContext.cacheDir, "updates")

    /** The installed version, from the package itself rather than a generated constant. */
    val currentVersion: String = runCatching {
        @Suppress("DEPRECATION")
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
    }.getOrNull().orEmpty().ifBlank { "0.0" }

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var job: Job? = null
    private var lastCheckAt = 0L
    private var pendingVersion: String? = null
    private var pendingAsset: GithubAsset? = null
    private var pendingFile: File? = null

    /**
     * [force] is the button; the quiet check that runs when Settings opens passes false and is
     * skipped if one happened recently or one is already in flight.
     */
    fun check(force: Boolean = false) {
        if (job?.isActive == true) return
        val now = System.currentTimeMillis()
        if (!force && _state.value !is UpdateState.Idle) return
        if (!force && now - lastCheckAt < CHECK_INTERVAL_MS) return
        lastCheckAt = now
        _state.value = UpdateState.Checking
        job = scope.launch {
            val release = runCatching {
                val response = client.get(RELEASE_API) {
                    header(HttpHeaders.Accept, "application/vnd.github+json")
                }
                if (!response.status.isSuccess()) {
                    _state.value = UpdateState.Failed("GitHub answered ${response.status.value}")
                    return@launch
                }
                Network.json.decodeFromString<GithubRelease>(response.bodyAsText())
            }.getOrElse { error ->
                _state.value = UpdateState.Failed(describe(error))
                return@launch
            }

            // A draft or a prerelease is not something to push at someone who asked for updates.
            if (release.draft || release.prerelease) {
                _state.value = UpdateState.UpToDate
                return@launch
            }
            val asset = release.apkAsset()
            if (asset == null) {
                _state.value = UpdateState.Failed("That release has no APK attached")
                return@launch
            }
            if (!isNewerVersion(release.tagName, currentVersion)) {
                _state.value = UpdateState.UpToDate
                return@launch
            }
            val version = release.tagName.trim().removePrefix("v").removePrefix("V")
            pendingVersion = version
            pendingAsset = asset
            pendingFile = null
            _state.value = UpdateState.Available(version, asset.size)
        }
    }

    /** Starts the download for the release the last check found. */
    fun download() {
        if (job?.isActive == true) return
        val version = pendingVersion ?: return
        val asset = pendingAsset ?: return
        _state.value = UpdateState.Downloading(version, 0, asset.size)
        job = scope.launch { fetch(version, asset) }
    }

    private suspend fun fetch(version: String, asset: GithubAsset) {
        val directory = withContext(Dispatchers.IO) {
            releaseDir.apply { mkdirs() }
            // An abandoned attempt from an earlier version is not worth keeping.
            releaseDir.listFiles()?.forEach { it.delete() }
            releaseDir
        }
        val target = File(directory, "PanelStash-$version.apk")
        val temp = File(directory, "download.tmp")
        var failure: String? = null

        try {
            withContext(Dispatchers.IO) {
                client.prepareGet(asset.downloadUrl) {
                    timeout { requestTimeoutMillis = DOWNLOAD_TIMEOUT_MS }
                }.execute { response ->
                    if (!response.status.isSuccess()) {
                        failure = "Download failed (${response.status.value})"
                        return@execute
                    }
                    val total = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                        ?: asset.size
                    val channel = response.bodyAsChannel()
                    var received = 0L
                    var reported = 0L
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = channel.readAvailable(buffer, 0, buffer.size)
                            if (read == -1) break
                            if (read == 0) continue
                            output.write(buffer, 0, read)
                            received += read
                            if (received - reported >= REPORT_STEP_BYTES ||
                                (total > 0 && received >= total)
                            ) {
                                reported = received
                                _state.value = UpdateState.Downloading(version, received, total)
                            }
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            failure = describe(error)
        }

        val problem = failure
        if (problem != null) {
            temp.delete()
            _state.value = UpdateState.Failed(problem)
            return
        }

        // A truncated file or another app's APK would fail in the installer with a message that
        // explains nothing, so both are caught here, where the reason can be shown.
        if (asset.size > 0 && temp.length() != asset.size) {
            temp.delete()
            _state.value = UpdateState.Failed("The download was incomplete")
            return
        }
        val archive = runCatching {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageArchiveInfo(temp.absolutePath, 0)
        }.getOrNull()
        if (archive == null || archive.packageName != appContext.packageName) {
            temp.delete()
            _state.value = UpdateState.Failed("The download is not a PanelStash APK")
            return
        }

        target.delete()
        if (!temp.renameTo(target)) {
            // Some caches live on a volume that refuses a rename; copying is still correct.
            runCatching { temp.copyTo(target, overwrite = true) }
            temp.delete()
        }
        if (!target.isFile) {
            _state.value = UpdateState.Failed("Could not save the update")
            return
        }
        pendingFile = target
        _state.value = UpdateState.Ready(version)
    }

    /**
     * Hands the downloaded APK to the package installer. Returns null when the installer was
     * opened, or the sentence to show when it was not.
     *
     * Android will not let an app install another one without that being granted first, so the
     * first tap opens the switch for this app and leaves the file ready for the second.
     */
    fun install(): String? {
        val file = pendingFile
        if (file == null || !file.isFile) return "Download the update first"
        if (!appContext.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${appContext.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching { appContext.startActivity(intent) }
                .fold(
                    onSuccess = { "Allow PanelStash to install apps, then tap Install again" },
                    onFailure = { "Allow PanelStash to install apps in Settings" },
                )
        }
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { appContext.startActivity(intent) }
            .fold(onSuccess = { null }, onFailure = { "Could not open the installer" })
    }

    private fun describe(error: Throwable): String = when (error) {
        is java.io.IOException -> "Could not reach GitHub"
        else -> "Update check failed"
    }
}
