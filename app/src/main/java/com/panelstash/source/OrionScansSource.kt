package com.panelstash.source

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import java.io.IOException

/**
 * Orion Scans, a scanlation site: a JSON catalogue plus server-rendered series and reader pages.
 *
 * Identity: a series is its slug, and a chapter is its own slug scoped to the series slug, so the
 * two are joined into one string (`"<seriesSlug>|<chapterSlug>"`). Nothing outside this module
 * parses those values.
 *
 * The catalogue API can search and filter by status, but ignores genre parameters, so
 * [capabilities] reports no genre *filtering* and Browse shows no chips rather than a control that
 * quietly does nothing. Each title still reports its genres, so a profile can show them. Ordering
 * cannot be verified either: the API accepts `orderBy` and returns a list that barely changes, so
 * only newest-first is offered.
 */
class OrionScansSource(
    private val client: HttpClient,
    private val gate: SourceGate,
) : Source {

    override val id = "orionscans"

    override val name = "Orion Scans"

    override val description = "Scanlation site. Small catalogue, newest chapters free."

    override val capabilities = SourceCapabilities(
        sorts = setOf(BrowseSort.LATEST),
        genres = false,
        titleGenres = true,
        status = true,
        year = false,
        safeOnly = false,
        excludedGenres = false,
    )

    /** Politeness: undocumented endpoints, so stay well under a request every 300ms. */
    private val limiter = RateLimiter(300)

    /** Detail and chapter list come from one page, so both calls share a single fetch. */
    @Volatile
    private var cached: Pair<String, OrionSeries>? = null
    @Volatile
    private var cachedAt = 0L

    override suspend fun browse(
        sort: BrowseSort,
        tagId: String?,
        filter: BrowseFilter,
        offset: Int,
        limit: Int,
    ): List<SourceManhwa> {
        val body = request("/api/query") {
            parameter("page", pageOf(offset, limit))
            parameter("perPage", limit)
            statusValue(filter.status)?.let { parameter("seriesStatus", it) }
        }
        return decode(OrionQueryResponse.serializer(), body).posts.toSourceManhwa()
    }

    override suspend fun search(query: String, offset: Int, limit: Int): List<SourceManhwa> {
        if (query.isBlank()) return browse(BrowseSort.LATEST, null, BrowseFilter.None, offset, limit)
        val body = request("/api/query") {
            parameter("page", pageOf(offset, limit))
            parameter("perPage", limit)
            parameter("searchTerm", query)
        }
        return decode(OrionQueryResponse.serializer(), body).posts.toSourceManhwa()
    }

    /** Listed on the series pages, but not usable as a filter, so offering them would be a lie. */
    override suspend fun genres(): List<SourceGenre> = emptyList()

    override suspend fun manhwa(remoteId: String): SourceManhwa {
        val series = series(remoteId)
        return SourceManhwa(
            remoteId = remoteId,
            title = series.title,
            description = series.description,
            coverUrl = series.coverUrl,
            year = series.year,
            status = series.status,
            genres = series.genres,
        )
    }

    override suspend fun chapters(manhwaRemoteId: String, language: String): List<SourceChapter> =
        dedupeChapters(series(manhwaRemoteId).chapters)

    override suspend fun pages(chapterRemoteId: String, quality: PageQuality): PageSet {
        val parts = chapterRemoteId.split(SEPARATOR)
        if (parts.size != 2) throw SourceError.Unavailable()
        val body = request("/series/${parts[0]}/${parts[1]}") { html() }
        val images = orionPageImages(body, parts[0])
        // A coin-locked chapter renders without pages, which is terminal for this chapter.
        if (images.isEmpty()) throw SourceError.Unavailable()
        return PageSet(
            baseUrl = images.first().url.substringBefore("/public/upload/"),
            images = images,
        )
    }

    private suspend fun series(seriesSlug: String): OrionSeries {
        val now = System.currentTimeMillis()
        cached?.let { (slug, series) ->
            if (slug == seriesSlug && now - cachedAt < SERIES_CACHE_MS) return series
        }
        val body = request("/series/$seriesSlug") { html() }
        return parseOrionSeries(body, seriesSlug).also {
            cached = seriesSlug to it
            cachedAt = now
        }
    }

    private suspend fun request(
        path: String,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): String {
        if (gate.isBlocked()) throw SourceError.Banned()
        limiter.acquire()
        val response = try {
            client.get("$BASE$path") {
                configure()
                // A plain browser-shaped request is what the site's own front end sends; some
                // edge rules reject anything that looks like automation.
                header("Referer", "$BASE/")
            }
        } catch (error: IOException) {
            throw SourceError.Offline(error)
        }

        return when {
            response.status == HttpStatusCode.TooManyRequests -> throw SourceError.RateLimited(null)
            response.status == HttpStatusCode.Forbidden -> {
                gate.block()
                throw SourceError.Banned()
            }

            response.status == HttpStatusCode.NotFound -> throw SourceError.Unavailable()
            response.status.value >= 500 -> throw SourceError.Transient()
            !response.status.isSuccess() -> throw SourceError.Transient()
            else -> response.bodyAsText()
        }
    }

    private fun <T> decode(
        serializer: kotlinx.serialization.DeserializationStrategy<T>,
        body: String,
    ): T = try {
        Network.json.decodeFromString(serializer, body)
    } catch (error: Exception) {
        throw SourceError.Transient(error)
    }

    private fun HttpRequestBuilder.html() {
        header("Accept", "text/html,application/xhtml+xml")
    }

    private companion object {
        const val BASE = "https://orion-scans.com"
        const val SEPARATOR = "|"

        /** Long enough to serve detail and chapters together, short enough to see new chapters. */
        const val SERIES_CACHE_MS = 60_000L

        fun pageOf(offset: Int, limit: Int): Int = offset / limit.coerceAtLeast(1) + 1
    }
}

private fun OrionScansSource.statusValue(status: StatusFilter): String? = when (status) {
    StatusFilter.ANY -> null
    StatusFilter.ONGOING -> "ONGOING"
    StatusFilter.COMPLETED -> "COMPLETED"
    StatusFilter.HIATUS -> "HIATUS"
    StatusFilter.CANCELLED -> "CANCELLED"
}

/**
 * Prose novels share the catalogue but have no pages, so they never reach the UI. Neither does a
 * series the catalogue reports with no chapters: it would open to an empty profile.
 */
internal fun List<OrionPostDto>.toSourceManhwa(): List<SourceManhwa> =
    filter { !it.seriesType.equals("NOVEL", ignoreCase = true) }
        .filter { it.slug.isNotBlank() }
        .filter { it.chapters.isNotEmpty() }
        .map { it.toSourceManhwa() }

internal fun OrionPostDto.toSourceManhwa(): SourceManhwa = SourceManhwa(
    remoteId = slug,
    title = postTitle.trim(),
    // The synopsis lives on the series page only, so it arrives with the detail.
    description = null,
    coverUrl = featuredImage?.takeIf { it.isNotBlank() },
    year = null,
    status = seriesStatus,
    genres = genres.mapNotNull { genre ->
        val name = genre.name.trim()
        if (name.isBlank() || genre.id == 0) null else SourceGenre(genre.id.toString(), name)
    },
)
