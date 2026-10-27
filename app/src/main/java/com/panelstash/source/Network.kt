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
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", USER_AGENT)
                        .build(),
                )
            }
            .build()
}
