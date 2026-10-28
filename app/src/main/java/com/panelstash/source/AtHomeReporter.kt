package com.panelstash.source

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

/**
 * MangaDex asks every client to report each image fetch it makes from an at-home node:
 * success, byte count, duration, and whether the node served it from cache. Without this
 * the network cannot tell a broken node from an idle one.
 */
class AtHomeReporter(private val scope: CoroutineScope) {

    private val client = OkHttpClient()

    fun report(
        url: String,
        success: Boolean,
        bytes: Long,
        durationMs: Long,
        cached: Boolean,
    ) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("url", url)
                    .put("success", success)
                    .put("bytes", bytes)
                    .put("duration", durationMs)
                    .put("cached", cached)
                    .toString()
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url(REPORT_ENDPOINT)
                    .header("User-Agent", Network.USER_AGENT)
                    .post(body)
                    .build()

                client.newCall(request).execute().close()
            }
        }
    }

    companion object {
        private const val REPORT_ENDPOINT = "https://api.mangadex.network/report"
    }
}

/**
 * Times and reports image traffic. Only at-home nodes are reported: they live under
 * `mangadex.network`, while MangaDex's own upload host and every scan-site image host are not
 * part of the network accounting.
 */
class AtHomeReportingInterceptor(private val reporter: AtHomeReporter) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.host.endsWith("mangadex.network")) return chain.proceed(request)

        val startedAt = System.nanoTime()
        return try {
            val response = chain.proceed(request)
            val durationMs = (System.nanoTime() - startedAt) / 1_000_000
            val bytes = response.header("Content-Length")?.toLongOrNull() ?: 0L
            val cached = response.header("X-Cache")?.startsWith("HIT") == true
            reporter.report(request.url.toString(), response.isSuccessful, bytes, durationMs, cached)
            response
        } catch (error: IOException) {
            val durationMs = (System.nanoTime() - startedAt) / 1_000_000
            reporter.report(request.url.toString(), false, 0L, durationMs, false)
            throw error
        }
    }
}
