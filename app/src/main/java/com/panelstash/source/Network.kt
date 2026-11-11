package com.panelstash.source

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

object Network {

    /**
     * MangaDex requires a real, descriptive User-Agent and forbids spoofing one.
     * One stack serves API calls and images so @Home reporting has a single hook point.
     */
    const val USER_AGENT = "PanelStash/0.1 (personal-use Android reader)"

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    fun apiClient(): HttpClient = HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
        }
        defaultRequest {
            header(HttpHeaders.UserAgent, USER_AGENT)
            header(HttpHeaders.Accept, "application/json")
        }
    }

    /**
     * A page image is a GET of immutable bytes, so a failure is always safe to retry. Coil has no
     * retry of its own: without this one dropped connection or one 429 during a burst of band
     * requests left a page black until the chapter was reopened, which is exactly the reported
     * "stuck on black, fine after going back". Delays are short because the reader is waiting.
     */
    fun imageClient(reporter: AtHomeReporter): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            // Large webtoon strips on a slow connection need room, but a dead host must still be
            // given up on well before the reader assumes the page is broken.
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(75, TimeUnit.SECONDS)
            .addInterceptor(ImageRetryInterceptor())
            .addInterceptor(AtHomeReportingInterceptor(reporter))
            .addInterceptor { chain ->
                val request = chain.request()
                val builder = request.newBuilder()
                    .header("User-Agent", USER_AGENT)
                // Scanlation image CDNs sit behind the same edge as the site and some reject a
                // request that arrives with no Referer. MangaDex uploads do not need one, and
                // sending a foreign Referer there would be wrong.
                refererFor(request.url.host)?.let { builder.header("Referer", it) }
                chain.proceed(builder.build())
            }
            .build()

    /**
     * Retries an image request that failed before its body was used: a dropped connection, a
     * timeout, or the 408/429/5xx an image CDN answers a burst with. Retrying a response whose
     * body the caller has not read yet is safe for any GET.
     */
    internal class ImageRetryInterceptor(
        private val attempts: Int = 3,
        private val baseDelayMs: Long = 250L,
    ) : Interceptor {

        override fun intercept(chain: Interceptor.Chain): Response {
            var attempt = 1
            while (true) {
                val response = try {
                    chain.proceed(chain.request())
                } catch (error: IOException) {
                    if (attempt >= attempts || chain.call().isCanceled()) throw error
                    sleepBeforeRetry(attempt, retryAfterMs = null)
                    attempt++
                    continue
                }
                if (attempt >= attempts || !retryable(response.code)) return response
                val wait = retryAfterMs(response.header("Retry-After"))
                response.close()
                sleepBeforeRetry(attempt, wait)
                attempt++
            }
        }

        private fun retryable(code: Int): Boolean =
            code == 408 || code == 429 || code in 500..599

        private fun retryAfterMs(header: String?): Long? =
            header?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.times(1_000)

        private fun sleepBeforeRetry(attempt: Int, retryAfterMs: Long?) {
            // 250 ms, then 750 ms: a transient is usually gone by the second try, and a reader
            // waiting on a page notices a second, not five.
            val backoff = baseDelayMs * (1L shl (attempt - 1)).coerceAtMost(2_000L)
            val wait = (retryAfterMs ?: backoff).coerceIn(50L, 2_000L)
            runCatching { Thread.sleep(wait) }
        }
    }

    /** The site whose reader embeds this image host, or null when it is not a scan source. */
    private fun refererFor(host: String): String? = when {
        host.endsWith("asurascans.com") -> "https://asurascans.com/"
        host.endsWith("vortexscans.org") -> "https://vortexscans.org/"
        host.endsWith("nyxscans.com") -> "https://nyxscans.com/"
        host.endsWith("orion-scans.com") -> "https://orion-scans.com/"
        host.endsWith("kaynscans.com") -> "https://kaynscans.com/"
        // Also covers cdn.flamecomics.xyz, which serves the pages.
        host.endsWith("flamecomics.xyz") -> "https://flamecomics.xyz/"
        // Weeb Central serves pages and covers from its own storage hosts.
        host.endsWith("weebcentral.com") -> "https://weebcentral.com/"
        host.endsWith("planeptune.us") -> "https://weebcentral.com/"
        host.endsWith("compsci88.com") -> "https://weebcentral.com/"
        host.endsWith("mangapill.com") -> "https://mangapill.com/"
        host.endsWith("readdetectiveconan.com") -> "https://mangapill.com/"
        // Arena Scans and Lua Comic serve their pages from their own CDNs.
        host.endsWith("arenascan.com") -> "https://arenascan.com/"
        host.endsWith("luacomic.org") -> "https://luacomic.org/"
        // Mgeko and the image hosts its reader points at.
        host.endsWith("mgeko.cc") -> "https://www.mgeko.cc/"
        host.endsWith("mangageko.com") -> "https://www.mgeko.cc/"
        host.endsWith("imgsrv4.com") -> "https://www.mgeko.cc/"
        host.endsWith("imgsrv5.com") -> "https://www.mgeko.cc/"
        else -> null
    }
}
