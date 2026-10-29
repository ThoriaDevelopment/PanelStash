package com.panelstash.source

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

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

    fun imageClient(reporter: AtHomeReporter): OkHttpClient =
        OkHttpClient.Builder()
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

    /** The site whose reader embeds this image host, or null when it is not a scan source. */
    private fun refererFor(host: String): String? = when {
        host.endsWith("asurascans.com") -> "https://asurascans.com/"
        host.endsWith("vortexscans.org") -> "https://vortexscans.org/"
        host.endsWith("nyxscans.com") -> "https://nyxscans.com/"
        host.endsWith("orion-scans.com") -> "https://orion-scans.com/"
        host.endsWith("kaynscans.com") -> "https://kaynscans.com/"
        // Also covers cdn.flamecomics.xyz, which serves the pages.
        host.endsWith("flamecomics.xyz") -> "https://flamecomics.xyz/"
        else -> null
    }
}
